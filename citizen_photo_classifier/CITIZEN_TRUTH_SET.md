# CITIZEN_TRUTH_SET — the Sicily citizen-app visual + location truth set

PHOENIX **citizen-app track only** (ADRIZ v2 / `com.phoenix.citizen`). This is
the standing spec + inventory for assembling Sicilian citizen submissions
(photos + reports) into a growing **visual + location truth set** that feeds:

- **(a) the ground-sensor model** — every confirmed citizen photo of real
  Sicilian smoke/flame is a clean-slate Sicilian training/eval sample (the one
  thing public datasets lack until Gaetano's on-the-ground footage arrives);
- **(b) confirmation** — corroboration of independent fire signals (FIRMS /
  SEVIRI / FCI / S1 / S2) by an on-the-ground human;
- **(c) detection + geolocation assessment** — measuring how early / how
  location-precise / how confident PHOENIX is, against a human standing near
  the fire.

> Clean-slate Sicilian. No foreign weights, no foreign data. NOT the satellite
> detectors, NOT the Pi-5/Hailo ground-sensor YOLO, NOT a drone model, NOT
> OLDGABE. Internal only — this is real user data; respect privacy (see below).

---

## Where it lives (storage map, verified 2026-06-14)

```
Android app (com.phoenix.citizen / ADRIZ v2)
  │  QuickReport "big button"  → submitFlameAtCurrentLocation()  [raw GPS]
  │  ReportForm                → seeded GPS, lat/lon EDITABLE     [GPS or typed]
  │  camera capture            → FileProvider → app cache .jpg
  ▼
Cloudflare Worker  adriz-cf-edge   (src/writes.ts)
  │  POST /api/citizen_upload_intent → /api/citizen_upload_relay (PUT)
  │       → R2 bucket  phoenix-citizen-media   key: citizen-photo/<date>/<uuid>.jpg
  │  POST /api/citizen_report  → INSERT D1 citizen_reports(media_keys=[r2 keys])
  │       → CF Queue  citizen-writes
  ▼
Cloudflare D1   phoenix-public   (uuid 5828a8a4-9f26-45a6-9bee-37276861879d)
  │  citizen_reports   (report_id, lat, lon, accuracy_m, media_keys, status, …)
  │  citizen_confirms  ("I see this too" votes)
  │  detections        (PHOENIX/comparator fire signals, for corroboration)
  ▼
DGX  push_to_d1.py  (phoenix-d1-push.timer, 30s, load_guard-wrapped)
  │  GET /api/_internal/dgx_pull_pending → _classify_citizen_report()
  │       grade A/B/C (corroboration × photo × reputation × confirms)
  │       Grade A/B → citizen_moderation_log (review candidate, human gate)
  │  (future) pull_labeled_photos.py:  media_keys → R2 bytes ONCE
  ▼
DGX  /home/mark/citizen_photos/
        labeled/pending/   <sha>.jpg <sha>.json   (UNLABELED — never train)
        labeled/approved/  <sha>.jpg <sha>.json   (human-labeled — THE truth set)
        corpus_cls/        train/ val/ (ImageFolder)  ← corpus_builder.py
```

**R2 bucket:** `phoenix-citizen-media` (bound as `MEDIA` in wrangler.toml).
Photos + voice memos. Keys recorded in `citizen_reports.media_keys` (JSON array).

---

## Inventory (live, as of 2026-06-14)

Queried directly against D1 `phoenix-public`:

| table | rows | notes |
|---|---:|---|
| `citizen_reports` | **1** | the single row is a migration smoke test (device_id=`test-device-001`, note="Test report from migration smoke test"), media_keys NULL, accuracy_m NULL, lat/lon 37.5757/13.4567 (≈ Alessandria della Rocca). NOT a real submission. |
| `citizen_confirms` | **1** | paired test confirm. |
| `detections` | 20,885 | corroboration pool: wind_diff 12,827 · subpixel_v1_alpha 5,608 · fci_l1c 2,374 · s2_swir 74 · adr 2. Time range 2026-05-22 → 2026-06-14. |

**Photos with EXIF/GPS:** 0. **Real citizen reports:** 0. **The set is
pre-launch** — it grows only after the app ships, Sicilians install + submit,
and a human labels. Everything below is the standing pipeline that fires
automatically as real reports land.

---

## CRITICAL — location-quality split (village-attribution bias)

Mark's rule (2026-06-14): fires are often **reported as coming from a
village/town** but the actual fire is **outside the village, km away in the
countryside**. So report coordinates are biased toward village centroids. Each
submission MUST be assessed for **location quality** and split accordingly.

### How location enters a report (this determines quality)

| path | source of lat/lon | accuracy_m | quality |
|---|---|---|---|
| QuickReport big button | `FusedLocationProvider` PRIORITY_HIGH_ACCURACY (device GPS) | should be present | **PRECISE** — geolocation truth, IF the reporter is at/near the fire |
| ReportForm (seeded) | GPS seed, **user did NOT edit** the fields | present | **PRECISE** |
| ReportForm (edited) | user **overtyped** lat/lon, or entered a place | absent / coarse | **VILLAGE-ATTRIBUTED / COARSE** — detection corroboration ONLY |
| Web PWA (migration doc) | browser geolocation **or a draggable pin** | pin → none | pin = **COARSE** |

**Known gap (flagged, not yet fixed):** `util/LocationProvider.kt ::
currentOrNull()` returns only `(lat, lon)` and **discards the Android
`Location.accuracy`** before submission, and the app does not stamp a
`location_source` ("gps" vs "manual_edit" vs "map_pin"). So today `accuracy_m`
arrives NULL even on a precise GPS fix. The split below therefore uses a
**multi-signal heuristic** and a recommended app fix (see "Owed app changes").

### The split (what `split_citizen_truth.py` computes)

Each report is bucketed into one of:

- **`gps_precise`** — usable as **geolocation truth**. Gate (any sufficient):
  `accuracy_m` present AND ≤ 100 m; OR submitted via QuickReport with an
  unedited GPS fix; OR coordinates carry ≥4 decimal places AND do **not** sit
  within `VILLAGE_SNAP_M` of a known comune centroid.
- **`village_attributed`** — **detection corroboration ONLY**, never precise
  geolocation. Gate (any): `accuracy_m` missing or > 500 m; OR coordinates
  land within `VILLAGE_SNAP_M` (default 250 m) of a comune centroid (the
  tell-tale of a tapped-place / centroid pin); OR coordinates are suspiciously
  round (≤3 decimals).
- **`coarse_unknown`** — between the two; treated as `village_attributed` for
  geolocation, kept for detection.

`gps_precise` feeds **both** the ground model (visual) and geoloc truth.
`village_attributed` feeds detection corroboration + the ground model
(visual) but is **excluded from any geolocation-error metric**.

---

## Measuring the bias directly — village-to-scar distance

The **physical burn scar is the TRUE location** (S2 dNBR centroid / S1 SAR
centroid, built in parallel — `manual_truth_events` carries `dnbr_centroid`
slots; `adr_phoenix_integration/triangulation.py` + the S2 active-fire work
produce scars). For each report we can match to a scar, compute:

```
bias_km = haversine(report.lat/lon, scar.centroid)
```

- For `village_attributed` reports, `bias_km` **is the village-attribution
  bias** — it quantifies how far the reported village sits from the real fire.
  This is the number that tells us how much report-based "localization error"
  is really the bias, **not PHOENIX error**.
- For `gps_precise` reports, `bias_km` is genuine on-the-ground geoloc
  residual (reporter standoff + GPS), usable to bound PHOENIX geoloc.

`split_citizen_truth.py` emits the **bias distribution** (median / p90 / max)
per bucket. Expectation: `village_attributed` median ≫ `gps_precise` median;
the gap is the measured bias magnitude.

Scar match gate: nearest scar within `SCAR_MATCH_DT_H` (default 72 h, scars
lag the fire) AND `SCAR_MATCH_DD_KM` (default 15 km, generous — a village can
be far from its countryside fire; matches beyond this are "unmatched").

---

## How it feeds the three consumers

- **(a) Ground-sensor model.** `labeled/approved/` → `corpus_builder.py` →
  ImageFolder → `train_photo_classifier.py`. Every approved photo (regardless
  of location bucket) is a real-Sicily visual sample. The positive classes
  (`smoke`, `flame`) are the precious ones; FP classes (`cloud`, `dust`,
  `chimney`, `no_fire`) teach the model what NOT to fire on (scirocco dust,
  Etna plume, chimney/ag-burn). **Per the standing rule, any baseline-beating
  ground model ships HEF + IT README to Gaetano.**
- **(b) Confirmation.** `push_to_d1.py::find_corroborators()` already matches
  each report to independent fire signals within 2 h / 5 km. A corroborated
  citizen report is Grade B (or A with a strengthener) and a human-gated
  candidate for `manual_truth_events`. Both location buckets contribute here —
  corroboration is detection, not geolocation.
- **(c) Detection + geolocation assessment.**
  - *Detection:* every confirmed photo = ground truth a fire existed at that
    place/time → scores PHOENIX recall + the comparator delta (were we earlier
    than FIRMS/SEVIRI?).
  - *Geolocation:* **only the `gps_precise` subset** is geoloc truth. The
    `village_attributed` subset's apparent "error" is quarantined as bias, so
    it never contaminates a PHOENIX localization metric (this is exactly the
    trap that produced the refuted "7 km NNE bias").

---

## How it compounds over time

The set is **append-only and self-reinforcing**:

1. More installs → more reports → more photos → bigger `labeled/approved/`
   corpus → stronger ground model → (HEF to Gaetano).
2. More reports → more corroboration pairs → more human-gated
   `manual_truth_events` → bigger gold set for the satellite eval harness
   (`sat_mission_eval_v3`).
3. More `gps_precise` + scar matches → tighter, honest PHOENIX geoloc bound;
   more `village_attributed` + scar matches → a stable, well-characterized
   bias distribution we subtract before ever blaming PHOENIX.
4. `device reputation` accrues per device_id → repeat reliable reporters earn
   Grade-A-eligible trust, raising auto-confirmation throughput.

Re-run cadence: `pull_labeled_photos.py` (future) on each push tick;
`split_citizen_truth.py` on demand / nightly; `corpus_builder.py` +
`train_photo_classifier.py` whenever `labeled/approved/` grows past
`--min-images` (40) — gated by Mark's eyeball + soak per `RUN_WHEN_PHOTOS_ARRIVE.md`.

---

## Privacy / internal-only (this is real user data)

- **No external exposure.** The corpus, the split, the bias report, the
  per-report rows — all stay on DGX (`/home/mark/citizen_photos/`) + the
  private D1. Nothing is published, nothing goes to a public bucket, nothing
  is committed with real coordinates/photos.
- **PII minimization already in the schema:** `ip_hash` (hashed, not raw IP),
  `device_id` (opaque app-generated, no account). The split/bias artifacts
  key on `report_id` + `sha256(photo)` only — never device_id, never ip_hash,
  never raw note text in any shareable summary.
- **Gaetano deliverable is the MODEL, not the data.** Only the trained ground
  HEF + Italian README is pushed to Gaetano — never the raw Sicilian citizen
  photos or report coordinates.
- Photos: app captures via FileProvider into app cache; most Android camera
  intents strip EXIF GPS, so the photo binary generally carries no geotag —
  location provenance is the report's lat/lon, handled by the split above.

---

## Owed app changes (close the location-provenance gap)

Small, high-value, NOT yet done (flagged for Mark's go — no app edits made):

1. `LocationProvider.currentOrNull()` → return `accuracy_m` too, and plumb it
   into `ReportRepository.submitOrQueue()` → the `accuracy_m` body field that
   `writes.ts` already persists. Makes the `gps_precise` gate exact instead of
   heuristic.
2. Stamp a `location_source ∈ {gps, manual_edit, map_pin}` on each report
   (set `manual_edit` the moment a user overtypes the seeded lat/lon;
   `map_pin` for the web draggable pin). One enum field ends the ambiguity at
   the source. Requires a `citizen_reports.location_source` column (migration).

Until then `split_citizen_truth.py` runs on the multi-signal heuristic and is
conservative (ambiguous → `village_attributed`, so geoloc truth stays clean).

---

## Artifacts

- This spec: `citizen_photo_classifier/CITIZEN_TRUTH_SET.md`
- Split + bias tool: `citizen_photo_classifier/split_citizen_truth.py`
- Corpus builder (existing): `citizen_photo_classifier/corpus_builder.py`
- Wire-in / eval (existing): `WIRE_IN.md`, `eval_photo.py`,
  `train_photo_classifier.py`, `RUN_WHEN_PHOTOS_ARRIVE.md`
- Ingest/grading (existing): `cf_edge/dgx_push/push_to_d1.py`
- Edge writes (existing): `cf_edge/src/writes.ts`
