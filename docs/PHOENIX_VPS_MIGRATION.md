# PHOENIX backend — DGX → cloud migration

## Why

DGX (`spark-b0c1`) is being used as both (1) the GPU detector pipeline and (2) the public-facing API/website host. Reboot loops on the DGX silently take adr-wildfire.com offline for minutes-to-hours at a time. As long as the public endpoint lives on DGX, hardware flakes = public outage = no markers on the ADRIZ app.

**Primary goal:** the public API and website are *never* down because of DGX hardware. DGX stays as the detector pipeline; it pushes updates to a small cloud node that handles all external traffic.

**Mission goals (the reason this matters at all):**

1. **Anyone can report a wildfire** from the website or the Android app, simply, in <10 seconds, with optional photo or voice. They get immediate confirmation their report was received and a status they can come back to ("received → corroborated → fire confirmed / not corroborated / expired").
2. **Anyone can view the current wildfire situation** in their area without an account, without an app install (web works), with the map showing every available signal — PHOENIX detections, satellite comparators (FIRMS, EFFIS, SLSTR, MTG, etc.), citizen reports, Google Wildfire layer, news/ANSA mentions — all on one map, color-coded by source.
3. **Citizen reports are visually unmistakable on the map.** When someone sees a fire and is deciding whether to report it, they need to see at a glance "is someone already reporting this?" so they don't assume someone else has called it in. A citizen-report marker must look different from a satellite detection — distinct icon, distinct color, with a small counter when multiple people report the same fire. This is *the* feature that breaks the diffusion-of-responsibility effect.
4. **Multiple data sources merged in one view** — PHOENIX, Google Wildfire, FIRMS/VIIRS/MODIS, EFFIS, ANSA news, citizen reports — so the common user gets the maximum-information picture without having to check 6 different sites.

## Reliability architecture — no single point of failure

PHOENIX is a public-safety system. The original "one VPS in front of DGX" plan still has one VPS as a SPOF. This section replaces that with a multi-tier design where each failure domain has a fallback.

### Failure domains + their fallbacks

| What dies | Public impact | What keeps serving |
|---|---|---|
| DGX hardware | 0 — site stays up | Both VPSes serve from replicated read-only DB |
| One VPS | 0 — site stays up | Other VPS picks up via Cloudflare HA tunnel routing |
| Both VPSes | 0–2 min, then degraded | **DGX cloudflared stays registered too** (as lowest-priority backup) — when both VPSes lose all connections, Cloudflare auto-routes back to DGX. Site stays up. CF Worker also caches reads as a second layer. |
| DGX + both VPSes | ≤2 min, then degraded | Cloudflare Worker serves cached read responses + queues writes in CF KV. Public sees "PHOENIX offline, viewing cached data." |
| Cloudflare itself | site down | Out of scope (CF 99.99%+ SLA) |
| Sicily internet | citizen submits queue locally in the app, replay on reconnect | App caches submissions in IndexedDB / Room, retries every 30s with `client_token` idempotency |

### The DGX fallback — explicit

Mark's hard requirement: **if v2 fails, traffic must fall back to PHOENIX-as-it-exists-now (the DGX-hosted setup).** We achieve this by:

1. **Never decommission the DGX tunnel.** After cutover, the DGX cloudflared keeps running with its existing 4 HA connections to the same tunnel ID. We don't stop it. We don't disable it.
2. **VPS connections take priority by edge-location proximity.** Both VPSes are EU (Frankfurt, Helsinki). DGX is in Virginia. Cloudflare's tunnel HA picks the connection with lowest edge latency. Sicily traffic naturally lands on the EU VPSes when they're healthy; if all 8 EU VPS connections drop, the 4 DGX connections take over within seconds.
3. **No DNS change is needed for fallback.** Same tunnel ID across all three origins. Cloudflare handles failover transparently. The user sees an extra ~100ms of latency on the DGX path, but the site stays up.
4. **DGX continues to be authoritative for ingest.** Even when VPSes are serving everything, all satellite ingest + ML still runs on DGX. The VPSes are read replicas + write queues, not independent brains.

This is the "v2 with a soft landing" Mark asked for. There is no big-bang cutover — we add VPSes alongside DGX, take preferential traffic, and the DGX path keeps existing as the always-available fallback.

### Target architecture (HA)

```
┌───────────────────────┐
│   DGX (detectors)     │
│   subpixel_v1         │
│   wind_diff           │
│   FCI/SEVIRI pulls    │
│   FIRMS / EFFIS /     │
│   EUMETSAT / Google   │
│   Wildfire ingest     │
│   citizen drain       │
└───────────┬───────────┘
            │ litestream
            │ (continuous WAL push, fan-out to BOTH VPSes)
            │
   ┌────────┴────────┐
   ▼                 ▼
┌──────────────┐   ┌──────────────┐         drain        ┌─────────────────┐
│ VPS-A        │   │ VPS-B        │  ◄── pull queue ────│ DGX drain timer │
│ Hetzner fsn1 │   │ Hetzner hel1 │                      └─────────────────┘
│ (Frankfurt)  │   │ (Helsinki)   │
│              │   │              │
│ gunicorn API │   │ gunicorn API │
│ cloudflared  │   │ cloudflared  │
│ static pages │   │ static pages │
│ write queue  │◄─►│ write queue  │  ← queue replicated A↔B via litestream
│ read replica │   │ read replica │
└──────┬───────┘   └──────┬───────┘
       │                  │
       └─────────┬────────┘
                 │
                 ▼
       ┌──────────────────────────┐
       │ Cloudflare Tunnel HA     │  ← both VPSes register same tunnel,
       │ (routes to healthy VPS)  │     CF auto-routes to healthy conn
       └────────────┬─────────────┘
                    │
                    ▼
       ┌──────────────────────────────┐
       │ Cloudflare Worker (front)    │  ← terminates adr-wildfire.com,
       │ /api/* + static + read cache │     5-min KV cache of read responses,
       │ KV-backed write queue (FB)   │     CF KV write-queue if both VPSes
       └────────────┬─────────────────┘     return 5xx; replays to VPS later
                    │
                    ▼
       ┌──────────────────────────┐
       │ adr-wildfire.com         │
       │ ADRIZ app + web + others │
       └──────────────────────────┘
```

### Reliability targets

- **Read availability** (viewing the map / detections): **99.97%** (≤ 2.5 hr down/year)
  - Achieved by 2 VPS HA + CF Worker cached fallback. Both VPS would have to be down AND CF KV cache would have to be cold for a full outage.
- **Write availability** (submitting a citizen report): **99.95%** (≤ 4.4 hr down/year)
  - Same as above, plus CF KV write-queue fallback (writes accepted into KV when VPSes down, replayed to VPS when they're back).
- **Data freshness on the map**: ≤ 60 s during normal operation (litestream lag).
- **Citizen-report submit → DGX processing**: ≤ 30 s p50, ≤ 5 min p99.
- **DGX-outage public effect**: zero on availability; satellite-feed freshness gaps only.

### Outage behavior (summary)

- **DGX dies** → both VPSes serve last-known detections. Freshness gap for new satellite ingests. Citizen reports queue on the VPSes; when DGX is back, drain catches up (idempotent via `client_token`). Map shows degraded banner.
- **One VPS dies** → CF tunnel HA routes 100% to other VPS; no public-visible impact. UptimeRobot alerts Mark.
- **Both VPSes die** → CF Worker serves last-cached read responses from KV (up to 5 min stale). Writes are accepted into CF KV write-queue; replayed when a VPS is back. Map shows red banner "PHOENIX backend offline, viewing cached data."
- **Cloudflare tunnel flaps on one VPS** → other VPS keeps serving; auto-recovers.
- **Sicily-side internet outage at the user** → Android app caches the submission locally and retries with the same `client_token` until accepted.

## Hosting options

We're buying TWO VPSes (not one) for HA. Different datacenters / power domains.

| Provider | Spec | EUR/mo each | Notes |
|---|---|---:|---|
| **Hetzner CX22 × 2 (recommended)** | 2 vCPU / 4 GB / 40 GB SSD | **€4.59 × 2 = €9.18** | One in `fsn1` (Frankfurt), one in `hel1` (Helsinki). Different failure domains. |
| Hetzner CX32 × 2 | 4 vCPU / 8 GB / 80 GB | €7.55 × 2 = €15.10 | Buy this tier if also putting Postgres on the VPSes (Option B). |
| DigitalOcean Basic × 2 | 1 vCPU / 2 GB / 50 GB / FRA + AMS | $6 × 2 = $12 | Brand familiarity, less perf. |
| Fly.io multi-region | shared-cpu-1x / 256 MB | ~$4–10 | Fine for read-heavy API; tighter RAM. |

**Pick Hetzner CX22 × 2 (Frankfurt + Helsinki).** ~€9/mo for full HA serving.

> Note: the original plan called for a single VPS at €4.59/mo. We doubled it for high availability. The added €4.59/mo is the price of "no one VPS outage takes the public site down." For PHOENIX as a public-safety service, that math is trivial.

## Database choice

PHOENIX currently uses SQLite (WAL mode, per `reference_phoenix_github_repo_2026_05_24.md`).

Two viable paths:

### Option A — SQLite + rsync (fastest to ship)
- Keep SQLite as the authoritative store on DGX.
- Cron on DGX: every 60 s, `litestream`-style WAL push or plain `rsync -a phoenix.db phoenix.db-wal phoenix.db-shm` to VPS.
- VPS opens DB **read-only**. All writes (incl. citizen reports) still go to DGX.
- Citizen reports: VPS receives `POST /api/reports`, writes to a small **queue table** (e.g. `incoming_reports` in a tiny VPS-local SQLite), DGX polls + drains the queue every minute.

Pros: minimal code change, single source of truth on DGX.
Cons: read-only on VPS means user-facing reads are always at most ~60 s stale; ingest path needs a tiny queue.

### Option B — Postgres on VPS (cleaner long-term)
- Stand up Postgres 16 on the VPS (or use **Neon** / **Supabase** free tier).
- Migrate PHOENIX schema (one-time).
- DGX writes detections directly to remote Postgres over TLS.
- VPS reads from same DB. No queue needed.

Pros: one DB, real-time, simpler app code.
Cons: DGX needs reliable outbound TLS to Postgres; if DGX network dies, ingest stalls (but public site stays up).

**Recommendation: start with A** (rsync SQLite, < 1 day's work). Migrate to B only when SQLite stops scaling (likely far away — PHOENIX's row volume is small).

## Phase 0 — Prep (do before standing up VPS)

1. Pick provider + region. Hetzner CX22 in `fsn1` (Frankfurt) for best Sicily latency.
2. Provision SSH key in provider console. Disable password auth.
3. Buy a small fallback domain or use a subdomain like `api.adr-wildfire.com` for staged cutover.
4. Snapshot current SQLite + assets on DGX.

## Phase 1 — Stand up VPS shell (1–2 hr)

```bash
# On VPS:
apt update && apt install -y python3-pip python3-venv nginx git rsync sqlite3 \
    cloudflared certbot python3-certbot-nginx
useradd -m -s /bin/bash phoenix
sudo -u phoenix git clone https://github.com/markl02us/persistent-thermal-sources-sicily.git /home/phoenix/phoenix
# private repo? use deploy key
sudo -u phoenix python3 -m venv /home/phoenix/venv
sudo -u phoenix /home/phoenix/venv/bin/pip install -r /home/phoenix/phoenix/requirements.txt
```

systemd unit for gunicorn:
```ini
# /etc/systemd/system/phoenix-api.service
[Unit]
Description=PHOENIX public API
After=network-online.target

[Service]
User=phoenix
WorkingDirectory=/home/phoenix/phoenix
Environment=PHOENIX_DB=/var/lib/phoenix/phoenix.db
Environment=PHOENIX_READ_ONLY=1
ExecStart=/home/phoenix/venv/bin/gunicorn \
    --workers 2 --bind 127.0.0.1:8181 \
    --access-logfile /var/log/phoenix/access.log \
    wsgi:app
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

## Phase 2 — Replicate DB DGX → VPS (1 hr)

Pick one:

**A1 — Litestream (preferred; continuous, low-latency, recovers crashes):**
```bash
# On DGX:
litestream replicate /home/mark/pdlens/phoenix.db \
    sftp://phoenix@vps.example.com:22/var/lib/phoenix/phoenix.db
```
Run under systemd. Survives reboots cleanly.

**A2 — Plain rsync cron:** (only if litestream too heavy)
```bash
# /etc/cron.d/phoenix-sync (on DGX)
* * * * * mark rsync -aq --delete \
    /home/mark/pdlens/phoenix.db* \
    phoenix@vps.example.com:/var/lib/phoenix/
```
60 s freshness lag. Acceptable for fire-detection horizons.

## Phase 3 — Citizen-report intake on VPS (2.5 hr)

This is the half the original plan under-specified. Covers all write endpoints the Android app + web call, media uploads, ACK-to-user semantics, and degraded-DGX behavior.

### 3.1 — All citizen write endpoints proxied to a VPS queue

The Android app + web hit several POSTs, not just one. Each needs a queue + drain. Single shared queue table on VPS, discriminated by `endpoint` column. Endpoints to mirror:

| Endpoint | Purpose | Media? | Authoritative state |
|---|---|---|---|
| `POST /api/citizen_report` | Main fire sighting | yes (photo / voice) | DGX `citizen_reports` table |
| `POST /api/citizen_photo_classify` | Submit photo, get back ML classification | yes (photo) | DGX classifier + `citizen_reports` |
| `POST /api/citizen_voice_report` | Voice-memo report | yes (audio) | DGX speech-to-text + `citizen_reports` |
| `POST /api/citizen_confirm` | "I see this fire too" from another user | no | DGX `citizen_feedback` table |
| `POST /api/user_fp_flag` | "This detection is wrong" | no | DGX `user_fp_flags` table |

VPS queue schema (single SQLite DB, `/var/lib/phoenix/incoming.db`):
```sql
CREATE TABLE incoming_writes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  endpoint TEXT NOT NULL,           -- e.g. "citizen_report"
  client_token TEXT NOT NULL,       -- app-generated UUID, for idempotency + status polling
  device_id TEXT,
  ip TEXT,
  payload_json TEXT NOT NULL,       -- the body (lat/lon/text/etc), media URLs only
  media_keys TEXT,                  -- JSON array of R2 keys for any uploaded photos/audio
  ts_received TEXT NOT NULL,        -- ISO8601 Z, when VPS received it
  ts_drained TEXT,                  -- ISO8601 Z, when DGX picked it up (NULL = still queued)
  ts_processed TEXT,                -- ISO8601 Z, when DGX finished processing
  status TEXT NOT NULL DEFAULT 'queued',
                                    -- queued / draining / accepted / rejected / corroborated / expired
  result_json TEXT                  -- DGX's decision (rejection reason, accepted report_id, etc.)
);
CREATE INDEX idx_status_ts ON incoming_writes(status, ts_received);
CREATE UNIQUE INDEX idx_client_token ON incoming_writes(client_token);
```

VPS handler (one Flask blueprint covers all 5 endpoints):
```python
ENDPOINT_MAP = {
    "/api/citizen_report": ("citizen_report",  ["lat","lon","observation_type"]),
    "/api/citizen_photo_classify": ("citizen_photo_classify", ["photo_url"]),
    "/api/citizen_voice_report": ("citizen_voice_report", ["audio_url"]),
    "/api/citizen_confirm": ("citizen_confirm", ["report_id"]),
    "/api/user_fp_flag":    ("user_fp_flag", ["detection_id","reason"]),
}

def ingest_write(endpoint_name, required_fields):
    p = request.get_json(silent=True) or {}
    for f in required_fields:
        if f not in p: return {"error": f"missing {f}"}, 400
    client_token = p.get("client_token") or _make_uuid()
    media = p.pop("_media_uploads", [])   # list of R2 keys, if any
    cur = queue_db.execute(
        "INSERT OR IGNORE INTO incoming_writes "
        "(endpoint, client_token, device_id, ip, payload_json, media_keys, ts_received, status) "
        "VALUES (?,?,?,?,?,?,?,'queued')",
        (endpoint_name, client_token, p.get("device_id"),
         _client_ip(), json.dumps(p), json.dumps(media), _now_z()))
    queue_db.commit()
    return {
        "status": "queued",
        "client_token": client_token,
        "poll_url": f"/api/citizen_report_status?token={client_token}",
        "estimated_wait_s": _wait_estimate(),  # see 3.4 below
    }, 202
```

### 3.2 — Media uploads (photo + voice) via R2 direct-upload

The queue stores *URLs*, not blobs. The actual photo/audio goes straight from the app/web to Cloudflare R2 (or Backblaze B2), bypassing the VPS entirely. This keeps the VPS small and avoids transfer through the queue.

Flow:
1. App calls `POST /api/citizen_upload_intent` with the MIME type + size hint.
2. VPS calls R2 to generate a presigned PUT URL (15-min expiry), returns it to app.
3. App PUTs the photo directly to R2.
4. App calls `POST /api/citizen_report` with the R2 key in `_media_uploads: ["citizen-photos/2026-06-12/abc123.jpg"]`.
5. DGX, when draining, fetches from R2 by key, processes (classifier, EXIF, thumb), writes thumb back to R2, stores R2 key in PHOENIX's existing schema.

R2 bucket: `phoenix-citizen-media`. Lifecycle rule: auto-delete originals after 30 days, keep thumbnails 1 year. Costs: ~€0.05/mo at expected volume.

### 3.3 — Status-polling endpoint (the ACK loop)

The user/app needs to know what happened to their report. Adds one read endpoint on the VPS:

```python
@app.get("/api/citizen_report_status")
def report_status():
    token = request.args.get("token")
    row = queue_db.execute(
        "SELECT status, ts_received, ts_drained, ts_processed, result_json "
        "FROM incoming_writes WHERE client_token=?", (token,)
    ).fetchone()
    if not row: return {"error":"unknown"}, 404
    return {
        "status": row["status"],          # queued/draining/accepted/rejected/corroborated/expired
        "received_at": row["ts_received"],
        "processed_at": row["ts_processed"],
        "result": json.loads(row["result_json"] or "{}"),
        "user_message": _humanize(row["status"], row["result_json"]),
            # e.g. "Your report was received and confirmed by a satellite. Grazie."
    }
```

Android app polls this every 10–30 s after submit and displays:
- "📬 Ricevuto, in elaborazione…" (queued/draining)
- "✅ Confermato da satellite" (accepted + corroborated)
- "⏳ Nessuna corroborazione satellitare entro 2h — il tuo report è registrato ma non confermato" (accepted, no corroboration)
- "❌ Probabilmente non un incendio (motivo: …)" (rejected with reason)

### 3.4 — DGX drain worker

systemd timer on DGX, every 15 s, drains the queue via SSH + small Python script.

```bash
# /etc/systemd/system/phoenix-citizen-drain.service
[Service]
ExecStart=/home/mark/bin/load_guard.sh \
  /home/mark/.openclaw/workspace/eumetsat_wildfire_detection/venv/bin/python \
  -m src.cli drain_citizen_queue --vps phoenix@vps.example.com
Slice=phoenix.slice
```

(Note: `load_guard.sh` per the HARDEST rule — every DGX-side artifact factors load_guard.)

Drain logic:
1. SSH/SQL-pull rows where `status='queued'` (limit 50/batch).
2. For each: mark `draining`, fetch media from R2 by key, run PHOENIX's existing citizen-ingest logic (validation, geo-bounds, corroboration check), update VPS row with `status='accepted'/'rejected'` + `result_json`.
3. On accept, write to DGX `citizen_reports` table (existing schema). Litestream picks it up and replicates to VPS read-only copy within ~60s. Map renders the new dot.

### 3.5 — Stale-DGX behavior (the queue can't grow forever)

If DGX is down for >10 min, the queue stops draining. Need:

1. **VPS-side max-age sweep** (every 5 min cron): rows older than 6 h with `status='queued'` get `status='expired'`. The user's status-poll returns "DGX backend is degraded, your report was saved but couldn't be processed in time — please re-submit when site shows green."
2. **Public status indicator** on the website + app — top banner:
   - 🟢 "Tutti i sistemi operativi" (DGX healthy, last drain <2 min ago)
   - 🟡 "Modalità degradata — i nuovi report sono salvati ma in coda" (DGX unreachable >5 min)
   - 🔴 "Nessuna nuova rilevazione satellitare" (DGX down >30 min)
   The banner is computed from a `/api/system_status` endpoint on the VPS that reads the last-drain timestamp from the queue DB.
3. **DGX-down alerting** — VPS cron emails Mark via Gmail SMTP helper if queue depth > 100 or oldest-queued > 15 min.

### 3.6 — Idempotency + abuse

- `client_token` is `INSERT OR IGNORE` keyed → duplicate submits don't double-process.
- Cloudflare WAF: rate-limit each of the 5 write endpoints at 10 req/min/IP for the un-authed ones, 60/min for status-poll. Configure in Cloudflare dashboard, not in code.
- Audit log: append-only `/var/log/phoenix/writes.jsonl` on VPS with `ts_received | endpoint | ip | client_token | accepted/rejected`. Rotate daily.

## Phase 4 — Cloudflare cutover (1 hr, the only outage moment)

This is the trickiest phase because we're going from "1 origin (DGX) + 1 tunnel" to "2 origins + 1 tunnel registered from both + 1 Worker in front."

1. Stand up `cloudflared` tunnels on **both** VPSes. They register the SAME tunnel ID (`6b89404b-3c60-486b-85e6-6bbf363e9061`). Cloudflare's HA tunnel routing handles healthy-connection selection automatically. Each VPS publishes 4 HA connections, so we have 8 total tunnel connections across 2 datacenters.
2. Tunnel ingress on both VPSes routes adr-wildfire.com → `localhost:8181` (the local gunicorn).
3. Test against the VPSes via a temp DNS like `api-staging.adr-wildfire.com` (CNAME to the same tunnel). Verify reads + writes work end-to-end.
4. Once smoke tests pass, **stop the DGX-side cloudflared tunnel** for `adr-wildfire.com`. Public traffic now flows entirely through the 2 VPSes.
5. Validate from external uptime probes (NYC, FRA, AMS, ASH): `curl https://adr-wildfire.com/api/system_status` returns 200 + recent data from BOTH VPSes (rotate via `--resolve` or use CF analytics to verify split).
6. Leave DGX's tunnel **disabled but configured** so you can flip back in <2 min if something explodes.
7. **Deploy the Cloudflare Worker** (Phase 4b) in front of the tunnel as the last-resort fallback.

### Phase 4b — Cloudflare Worker (last-resort fallback)

Deploy a Worker at `adr-wildfire.com/*` that:

- On **read** endpoints (`/`, `/api/detections`, `/api/citizen_reports_public`, `/api/firms`, etc.): tries the tunnel; if 5xx for >3 s, returns cached response from KV (`adr_wildfire_read_cache`); if KV cold, returns a static "PHOENIX is currently unavailable, retry shortly" page. Successful tunnel responses are written back to KV with 5-min TTL.
- On **write** endpoints (`/api/citizen_report`, `/api/citizen_photo_classify`, etc.): tries the tunnel; if 5xx, queues the request in KV (`adr_wildfire_write_fallback_queue`) and returns `202 {"status":"queued_kv_fallback","client_token":"..."}` to the client. A scheduled Worker drain job replays the KV queue to the VPSes every 5 min, ordered by `ts_received`. Same idempotency via `client_token`.

Worker source lives in this repo at `vps_migration/cf_worker/`. Cost: free tier ample.

## Phase 5 — Monitor + harden (after cutover)

External monitoring (the public-facing health checks):
- **UptimeRobot or Better Stack** hitting `https://adr-wildfire.com/api/system_status` every 60 s from 5+ regions. Alert on 2 consecutive failures.
- **CF Health Checks** per origin: each VPS exposes `/health` that returns 200 only if (a) gunicorn is up, (b) read DB lag <120 s, (c) drain timer last fired <60 s ago, (d) disk free >2 GB. Cloudflare uses this to gate HA routing.
- **Synthetic write check** every 5 min: a privileged client submits `POST /api/citizen_report?_synthetic=1` from an external location; the system_status endpoint reports last synthetic-write-accepted age. Alert if >15 min.

Internal monitoring:
- Litestream replication lag → alert if >10 min on either VPS.
- Write-queue depth → alert if >100 rows queued on either VPS.
- VPS disk free → alert if <20%.
- DGX drain timer → alert if last drain >5 min ago.

Hardening:
- Cloudflare WAF rate-limits per the table in Phase 3.6.
- Nightly `sqlite3 .backup` of phoenix.db + queue DB → R2 / Backblaze B2.
- Both VPSes auto-patch via `unattended-upgrades`, stagger reboots (different cron days) so they never both reboot at once.
- HSTS + CSP + secure cookies on all responses.

## Phase 6 — Multi-source data feeds on the map (1.5 hr)

The map must show every available wildfire signal, not just PHOENIX. The current site already pulls FIRMS / EFFIS / SLSTR / MTG via the gunicorn API — those keep working. Adding:

- **Google Wildfire Boundary Tracker** — public KML/GeoJSON of active US fire perimeters (not Sicily-relevant for now, but the architecture should support EU equivalent feeds the same way). Wire as a new `/api/google_wildfire` endpoint on the VPS that fetches + caches every 15 min, joined into the unified `/api/all_signals` feed.
- **Italian VVF (Vigili del Fuoco) RSS** — already ingested by PHOENIX (`vigili_fuoco` source in /api/detections). Keep.
- **ANSA Sicilia news RSS** — already ingested (`ansa_rss` source). Keep.
- **Pyronear EU community network** (when their public API lands) — wire same pattern.
- **EFFIS Rapid Mapping** — already ingested.

Unified-feed endpoint:
```
GET /api/all_signals?bbox=<>&since=<>&types=<phoenix,firms,viirs,modis,slstr,mtg,citizen,vigili,ansa,google_wildfire>
```
Returns a flat GeoJSON-ish array with each row carrying its `source` field. Front-end paints each source with its own color + icon (see Phase 6.1).

### 6.1 — Visual distinction on the map (the core UX requirement)

Citizen reports must be unmistakable. Map legend:

| Source | Marker | Color | Behavior |
|---|---|---|---|
| Citizen report (one reporter) | 👤 person icon | bright orange | Pulses gently |
| Citizen report (2-5 reporters at same spot) | 👥 group icon w/ counter "×3" | bright orange | Pulses + counter badge |
| Citizen report (6+ reporters, "hot spot") | 🔥 flame in orange ring | bright orange + red ring | Pulses strongly, large |
| PHOENIX subpixel/wind_diff/fci_l1c | ◆ diamond | bright red | Static |
| FIRMS (VIIRS/MODIS) | ● circle | dark red | Static |
| SLSTR / MTG / Sentinel-2 | ▲ triangle | purple | Static |
| EFFIS / Vigili del Fuoco / news | ◾ square | yellow | Static |
| Google Wildfire perimeter | shaded polygon | red outline | Polygon, not point |

Citizen markers are clearly visually different from satellite detections — different icon family (people), different color (orange not red), and they animate (pulse). When two citizen reports are within 1 km of each other, the front-end merges them into a single marker with a counter badge ("×N reporters") so users immediately see "this is being reported by multiple people."

**The "someone already reported it" effect:** When a user opens the map and sees an orange pulsing 👤 in their area, they know a citizen has reported a fire there. The counter badge tells them how many. This breaks the diffusion-of-responsibility problem — they can SEE that they should still report it (if no marker) or that confirmation has already started (if there is one).

### 6.2 — One-tap citizen reporting (Android + web)

The Android app already has the form. Web equivalent on adr-wildfire.com:

- Floating action button (FAB) bottom-right: "🔥 Segnala incendio" / "Report fire."
- Tap → modal with: location (autodetected via browser geolocation, draggable pin), 1-tap photo (PWA file-input with `capture=environment`), optional 1-sentence note, optional voice memo (Web Speech API).
- Submit → POST `/api/citizen_report` with `client_token`, lat/lon, optional media URLs (uploaded direct to R2 first via 3.2).
- Immediate "📬 Ricevuto" confirmation with a status link the user can come back to.
- Map updates: the user's own report appears as an orange pulsing 👤 immediately (optimistic UI), then status updates as DGX processes ("✅ Confermato da satellite" when corroborated).

## Phase 7 — Android app v2 release (3 hr)

This is the fresh-release path Mark asked for. The existing app continues to work via the fallback (DGX path), but v2 adds the new features and routes through the HA stack first.

### 7.1 — Source code changes

Repo: `C:\Users\markl\phoenix_citizen_android\` (existing). Branch: `v2-ha-stack`.

Code changes:

1. **Base URL constant**: confirm/set `BASE_URL = "https://adr-wildfire.com"` in `app/src/main/java/com/adrwildfire/Config.kt`. This URL is the same across DGX/VPS/Worker — Cloudflare handles routing.
2. **Status banner**: poll `GET /api/system_status` every 60 s in the foreground; render the banner at the top of the map screen with the 🟢🟡🔴 indicator.
3. **Citizen report submit flow** updates to match new contract:
   - Generate `client_token` (UUID v4) locally for every new report.
   - If photo/voice: first hit `POST /api/citizen_upload_intent` → get R2 presigned URL → PUT media to R2 → include R2 key in the report.
   - Submit `POST /api/citizen_report` with `client_token` and R2 key(s).
   - On 202 response, save the `client_token` + the report locally in Room DB with `status='submitted'`.
   - Poll `GET /api/citizen_report_status?token={client_token}` every 10 s for up to 5 min, then back off to every 60 s for up to 4 hr.
4. **Local outgoing queue (Room DB)**: if submit returns 5xx or network fails, queue the report locally; background `WorkManager` job retries every 30 s with the same `client_token` (idempotency means duplicate retries are safe).
5. **Local read cache (Room DB)**: cache the last-fetched `/api/detections`, `/api/citizen_reports_public`, `/api/all_signals` responses for 30 days. If device is offline, map renders from cache.
6. **Visual distinction on map** (matches Phase 6.1):
   - Citizen reports → orange pulsing person icon (one), group icon w/ counter (2–5), flame-in-ring (6+).
   - PHOENIX detections → red diamond.
   - Satellite comparators (FIRMS / SLSTR / MTG) → dark red circle, purple triangle.
   - News/VVF/EFFIS → yellow square.
   - Google Wildfire perimeters → red-outlined polygon.
   - Tap a citizen marker → bottom sheet with reporter count + earliest report time + photo thumbnails + "👁 Anch'io vedo questo" (I see it too) button → `POST /api/citizen_confirm`.
7. **"🔥 Segnala incendio" FAB** on the map screen (already exists in v1, refine the modal to match the new flow).
8. **Settings: "Phoenix backend"** picker for advanced users (hidden behind 5 taps on the version number): `auto (default)`, `force VPS`, `force DGX`. Used during cutover for testing; ship locked to `auto` for general release.

### 7.2 — Build + sign

Versioning: bump `versionCode` to 200, `versionName` to `"2.0.0"`. Tag release as `v2.0.0`.

Signing keystore: if Mark doesn't have one yet, generate one and store at `C:\Users\markl\.claude\secrets\adriz_release.jks`. Pre-flight item below specifies what's needed.

Build command (Windows, from the app repo):
```powershell
./gradlew bundleRelease assembleRelease
```
Outputs:
- `app/build/outputs/bundle/release/app-release.aab` (for Play Store)
- `app/build/outputs/apk/release/app-release.apk` (for direct distribution)

### 7.3 — Distribution paths

Pick one or all three (each layer adds reach):

**(a) Direct APK hosting on adr-wildfire.com/download — fastest, today**
- Upload signed `.apk` to a Cloudflare Pages site at `download.adr-wildfire.com`.
- Add a download page on the main site at `adr-wildfire.com/app` linking to it.
- Users tap link → enable "Install unknown apps" → install.
- Pros: no gatekeeper, instant updates.
- Cons: users must trust the side-load. Not Play-Protect verified.

**(b) Firebase App Distribution — easy gated rollout, half-day setup**
- Create Firebase project at console.firebase.google.com.
- Add the Android app, upload service-account JSON.
- Distribution via `firebase appdistribution:distribute` (or GitHub Actions).
- Testers added by email, auto-update notifications via Firebase Tester app.
- Pros: gated by email, no public listing, auto-update push.
- Cons: requires user to install Firebase Tester app first.

**(c) Play Store internal/closed track — most legit, 1–2 days**
- One-time €25 Play Console fee.
- Need: signed AAB, app icon set, screenshots, short/full descriptions, privacy policy URL.
- Privacy policy can be a simple page at `adr-wildfire.com/privacy` — I'll generate one as part of release.
- Internal track: up to 100 testers, instant rollout. Closed track: up to 2000 testers, ~hours review.
- Pros: standard Android install path, Play Protect verified, auto-update via Play Store.
- Cons: app needs to clear Google Play policies (Family-friendly, sensitive permissions explained, etc.) — citizen-reporting + camera + location all need brief justifications.

**Recommended starting combo**: ship (a) + (b) today for fast feedback, then move to (c) within 2 weeks for the wider rollout.

### 7.4 — In-app update check

Lightweight, no external dependency:

1. Worker route at `adr-wildfire.com/app/latest.json` returns:
   ```json
   {"versionCode": 200, "versionName": "2.0.0", "apkUrl": "https://download.adr-wildfire.com/adriz-2.0.0.apk", "notes": "..."}
   ```
2. App checks once on launch + every 24 h foreground. If newer version available → top-of-screen banner "Aggiornamento disponibile — tocca per scaricare."
3. Firebase Distribution / Play Store handle their own update prompts; the manual check is for users who installed via direct APK.

### 7.5 — Fallback to v1 / DGX path

Two-layer fallback for the app:

1. **Routing fallback (transparent)**: app always hits `adr-wildfire.com`. Cloudflare routes to VPS first, falls back to DGX if all VPS connections die. App doesn't know or care which origin it talked to.
2. **API contract fallback**: v2 endpoints (`/api/citizen_report_status`, `/api/citizen_upload_intent`, `/api/system_status`, `/api/all_signals`) won't exist on DGX-as-it-is-today. The DGX path falls back to v1 endpoints (`/api/citizen_report` without status polling, `/api/detections`, `/api/citizen_reports_public`). The app must detect 404 on a v2-only endpoint and degrade gracefully:
   - If `/api/system_status` 404 → assume green, hide banner.
   - If `/api/citizen_upload_intent` 404 → fall back to multipart upload of media directly in `/api/citizen_report` body.
   - If `/api/citizen_report_status` 404 → show "Report submitted (status tracking unavailable)" instead of the live status.

This means: app v2 works against either the new HA stack OR the existing DGX-only setup. If the migration is rolled back, app keeps working.

### 7.6 — Release calendar

1. Day 1: code changes + build + sign + Firebase Distribution to 5 internal testers.
2. Day 2–3: feedback round; fix bugs found.
3. Day 4: direct APK at `download.adr-wildfire.com` + announcement.
4. Day 5–14: Play Store internal → closed → production track.
5. Ongoing: in-app update check pushes minor updates automatically.

## Rollback plan

If anything breaks during cutover, you can flip back in <2 min:
1. Re-enable the DGX-side cloudflared tunnel.
2. Stop the VPS-side cloudflared tunnels.
3. Cloudflare re-routes to DGX.

Nothing destructive happens during cutover — both tunnels can coexist; you're just turning one off.

If a VPS goes bad **after** cutover, no rollback needed — the HA architecture handles it. Mark gets alerted; the bad VPS is rebuilt from the snapshot at leisure.

## Architecture options + cost comparison

Three architectures considered. **Pick Option C — it's cheaper AND more reliable than either VPS option.**

### Option A — 2× VPS HA (€9.50/mo) — what this doc described above

- 2× Hetzner CX22 (Frankfurt + Helsinki) running gunicorn
- Cloudflare tunnel HA + Worker fallback
- Reliability ceiling: ~99.97% (limited by the VPSes themselves)
- Code rewrite: minimal — reuse the existing Flask code

Cost:
| Item | Monthly |
|---|---:|
| Hetzner CX22 × 2 | €9.18 |
| Cloudflare R2 (media) | ~€0.10 |
| Backups | ~€0.05 |
| Domain | already paid |
| **Total** | **€9.50** |

### Option B — 1× VPS + Cloudflare edge (€9.00/mo)

- 1× Hetzner CX22 running Flask gunicorn (the "warm spare" for endpoints that aren't ready to be Workers yet)
- Cloudflare Workers + D1 + KV + R2 + Queues in front of it
- Most reads served from Workers + D1 cache; only writes/uncached reads hit the VPS
- VPS dies → Workers + D1 keep serving; only writes degrade (queued in CF Queues)

Cost:
| Item | Monthly |
|---|---:|
| Hetzner CX22 × 1 | €4.59 |
| Cloudflare Workers Paid plan | €4.50 (~$5) |
| D1 / KV / R2 / Queues | ~€0.10 within free tiers |
| Domain | already paid |
| **Total** | **€9.20** |

### Option C — Cloudflare-edge-only (€5.00/mo) — RECOMMENDED

No VPS at all. All API runs on Cloudflare's edge.

- **Cloudflare Workers** — all `/api/*` endpoints (read + write). 300+ POPs worldwide. Routes to user's nearest POP automatically.
- **Cloudflare D1** — SQLite-as-a-service, single primary + globally-replicated read replicas. Hosts the public `phoenix_read` DB (detections + citizen_reports + all data needed for serving). DGX writes to D1 directly via HTTP.
- **Cloudflare R2** — media storage (photos, voice memos, thumbs). Zero egress fees.
- **Cloudflare KV** — `/api/system_status`, low-cardinality caches, write fallback queue.
- **Cloudflare Queues** — durable, ordered write queue for citizen reports. DGX drains via consumer Worker.
- **Cloudflare Pages** — static frontend (the HTML/JS/CSS for adr-wildfire.com). Free.
- **DGX** — still does detection + satellite ingest + ML, pushes new rows directly to D1 over HTTPS using a scoped API token.

Reliability: ~99.99% (Cloudflare's published SLA for Workers/D1/R2). No Linux to patch, no VPS to reboot, no NetworkManager to flap. Whole stack scales automatically on demand.

Cost:
| Item | Monthly |
|---|---:|
| Cloudflare Workers Paid plan ($5) | €4.50 |
| D1 (well within free tier — 25 GB storage, 25B reads/mo, 50M writes/mo) | €0 |
| KV (well within free tier — 100k reads/day, 1k writes/day → we use ~1k reads/day) | €0 |
| R2 storage (~5 GB media / mo) | €0.05 |
| R2 egress (free for first 10 GB/mo) | €0 |
| Queues (~50k operations / mo) | €0 (free tier 1M ops/mo) |
| Pages (static frontend) | €0 |
| Domain | already paid |
| **Total** | **~€5.00** |

### Why Option C is more reliable than 2× VPS

- Cloudflare runs the same infrastructure that already terminates `adr-wildfire.com` TLS today — adding Workers in front of it is the same platform, same SLA.
- Workers run on 300+ POPs. Loss of any single POP is invisible to users — requests route to the next-nearest. To take Workers fully down, *all* of Cloudflare must be down.
- D1 has built-in primary + replicas, automatic failover.
- No Linux patching, no kernel crashes, no full-disk events, no NetworkManager flapping.
- The failure mode of "two Hetzner VPSes both dying at once" is rare but real (e.g., shared upstream peer, BGP misconfiguration). The failure mode of "all of Cloudflare dying" is several orders of magnitude rarer — and when it does happen, it'd take down most of the public internet anyway.

### Effort tradeoff for Option C

Extra work upfront: ~6 hr to port the API endpoints from Flask (Python) → Workers (TypeScript). This is straightforward — most are thin DB read endpoints.

Endpoints to port (in order of priority):
1. `GET /api/detections` — read from D1
2. `GET /api/citizen_reports_public` — read from D1
3. `GET /api/firms`, `/api/feed_accuracy`, `/api/source_health` — read from D1
4. `GET /api/all_signals` (new, unified feed)
5. `GET /api/system_status` — KV-backed
6. `POST /api/citizen_*` (5 endpoints) — write to CF Queues, return 202 + client_token
7. `GET /api/citizen_report_status` — read from D1 / KV
8. `POST /api/citizen_upload_intent` — R2 presigned URL

The static frontend (HTML/JS for the map) gets served from Cloudflare Pages with no changes.

### Fallback to DGX (Option C)

Same as before: keep the DGX cloudflared registered. If the new Workers/D1 stack hits an unrecoverable issue, point the DNS back to the DGX tunnel (1-line change in CF dashboard, propagates in seconds). Public traffic falls back to PHOENIX-as-it-exists-now.

### Pick recommendation

**Go with Option C.** Half the cost, higher reliability ceiling, no Linux to babysit. The 6-hr extra rewrite is a one-time investment that pays back forever in operational simplicity.

Option A (2 VPS) remains in this doc as a fallback design if Mark wants to keep the existing Flask code 1:1 and avoid the rewrite.

## Out of scope (do not include in this migration)

- Moving GPU detectors off DGX. Keep them on DGX — they need the GPU.
- Rewriting PHOENIX in another language. Same Python codebase moves over.
- Migrating to Kubernetes. Not justified at this scale.
- Multi-region. One Frankfurt VPS suffices.

## Effort estimate

- Phase 0 prep (provider + DNS + snapshot): 30 min
- Phase 1 VPS shell × 2 (Frankfurt + Helsinki, same image): 2 hr
- Phase 2 DB replication (litestream fan-out to both VPSes + queue replication A↔B): 1.5 hr
- Phase 3 citizen intake (all 5 endpoints + R2 media + status polling + drain + stale-DGX handling): 2.5 hr
- Phase 4 cutover (HA tunnel registration from both VPSes): 1 hr
- Phase 4b Cloudflare Worker (read cache + write fallback): 1.5 hr
- Phase 5 hardening (monitoring + WAF + backups + auto-patch): 1 hr
- Phase 6 multi-source data feeds + visual distinction on map: 1.5 hr
- Phase 7 Android app endpoint verification (+ release if needed): 1 hr

**~12 hr total wall-clock** for the HA version. Can be split across 2–3 sessions. Migration runs entirely behind staging DNS until cutover, so there's no public risk during the build.

Stage-by-stage public availability during migration:
- Phases 0–3: zero public impact, DGX still serves adr-wildfire.com as today.
- Phase 4: brief (≤2 min) cutover blip when DGX tunnel is stopped and VPS tunnels take over.
- Phases 4b–7: zero public impact (Worker + extra feeds + app updates layer on top without touching the live path).

## Execution checklist (in this window)

Pre-flight (need Mark to provide):
- [ ] Hetzner cloud account (create at hetzner.com/cloud) — gets us API token
- [ ] Cloudflare DNS edit permission for `adr-wildfire.com` (already have via wrangler-oauth in `.claude/secrets/cloudflare.json`)
- [ ] GitHub deploy key allowed on the PHOENIX repo (or repo made public deploy)
- [ ] Decision: keep current Android app or build v2 for safety (Phase 7)

In-window execution order:
1. Provision 2 Hetzner CX22 (Frankfurt + Helsinki) via API.
2. Apply same Ansible/cloud-init image to both: gunicorn + cloudflared + litestream + R2 creds.
3. Boot litestream replication from DGX to both VPSes.
4. Deploy VPS-side intake/status/drain code from this repo.
5. Register both VPSes' cloudflared into the existing tunnel (NOT a new tunnel — same ID, additional HA connections).
6. Smoke-test via `api-staging.adr-wildfire.com` CNAME.
7. Stop DGX cloudflared. Confirm public stays up via 8-connection HA.
8. Deploy CF Worker in front.
9. Wire Phase 5 monitoring.
10. Wire Phase 6 multi-source + visual distinction.
11. Verify Android app endpoint, ship v2 if needed.
12. Watch for 48 hours, fix any rough edges.
