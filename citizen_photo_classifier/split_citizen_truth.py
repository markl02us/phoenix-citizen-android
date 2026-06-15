#!/usr/bin/env python3
"""split_citizen_truth.py — split the Sicily citizen-report truth set into
GPS-precise vs village-attributed, and measure the village-to-scar bias.

PHOENIX citizen-app track ONLY. Internal, privacy-respecting: keys every row
on report_id only; never emits device_id / ip_hash / raw note text. No network
calls except an optional read-only D1 pull (when --d1 is given and creds are in
the environment). Reads the burn-scar truth (S2 dNBR / S1 SAR centroids) from
the DGX ground-truth SQLite read-only.

Three location-quality buckets (see CITIZEN_TRUTH_SET.md):
  gps_precise        — geolocation truth (precise device GPS, unedited)
  village_attributed — detection corroboration ONLY (centroid/typed/coarse)
  coarse_unknown     — ambiguous; treated as village_attributed for geoloc

For every report matched to a physical scar, bias_km = haversine(report, scar
centroid). For village_attributed rows this IS the village-attribution bias.
The script prints the bias distribution per bucket (median / p90 / max) so the
gap between buckets quantifies the bias magnitude directly.

INPUT (pick one):
  --reports-json FILE   JSON array of citizen_reports rows (offline; preferred)
  --d1                  pull live rows from D1 phoenix-public (read-only SELECT)
                        needs CF_ACCOUNT_ID + CF_API_TOKEN in env.
SCAR TRUTH:
  --truth-db PATH       DGX ground_truth.sqlite (default the push_to_d1 path).
                        Reads manual_truth_events (lat/lon/ts_approx_utc) — the
                        human-reviewed scar-backed events. Optional: if absent,
                        bias is skipped and only the bucket split is emitted.

OUTPUT:
  --out FILE            JSON report (split counts + per-bucket bias stats +
                        per-report bucket assignment, report_id-keyed only).

Nothing is written back to D1 or the truth DB. Read-only, idempotent.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sqlite3
import statistics
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from typing import Any

# ── tunables ────────────────────────────────────────────────────────────────
VILLAGE_SNAP_M     = 250.0    # within this of a comune centroid ⇒ village-attributed
ACCURACY_PRECISE_M = 100.0    # accuracy_m ≤ this ⇒ precise
ACCURACY_COARSE_M  = 500.0    # accuracy_m > this ⇒ village-attributed
SCAR_MATCH_DT_H    = 72.0     # scar lags the fire; generous time window
SCAR_MATCH_DD_KM   = 15.0     # village can be far from its countryside fire
EARTH_KM           = 6371.0088

DEFAULT_TRUTH_DB = os.environ.get(
    "PHOENIX_GROUND_TRUTH_DB",
    "/media/mark/AI_DGX/eumetsat_data/ground_truth.sqlite")

D1_DATABASE_ID = "5828a8a4-9f26-45a6-9bee-37276861879d"   # phoenix-public

# Sicilian comune centroids (lat, lon) — the village-attribution magnets. This
# is a seed list of the comuni most relevant to the PHOENIX theatre; extend
# freely. A centroid hit (within VILLAGE_SNAP_M) is the tell-tale that a report
# was attributed to a settlement rather than the countryside fire itself.
COMUNE_CENTROIDS: list[tuple[str, float, float]] = [
    ("Alessandria della Rocca", 37.5757, 13.4567),
    ("Cianciana",               37.5180, 13.4330),
    ("Bivona",                  37.6190, 13.4410),
    ("Santo Stefano Quisquina", 37.6420, 13.4880),
    ("Ribera",                  37.5000, 13.2670),
    ("Sciacca",                 37.5100, 13.0850),
    ("Agrigento",               37.3110, 13.5760),
    ("Palermo",                 38.1157, 13.3615),
    ("Corleone",                37.8140, 13.3000),
    ("Prizzi",                  37.7220, 13.4280),
    ("Catania",                 37.5079, 15.0830),
    ("Enna",                    37.5670, 14.2790),
    ("Caltanissetta",           37.4900, 14.0620),
    ("Trapani",                 38.0180, 12.5370),
    ("Messina",                 38.1938, 15.5540),
    ("Ragusa",                  36.9270, 14.7250),
    ("Siracusa",                37.0755, 15.2866),
    ("Caltagirone",             37.2380, 14.5120),
    ("Nicosia",                 37.7470, 14.3970),
    ("Cefalù",                  38.0390, 14.0230),
]


# ── geo / time helpers ──────────────────────────────────────────────────────
def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r1, r2 = math.radians(lat1), math.radians(lat2)
    dlat, dlon = r2 - r1, math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(r1) * math.cos(r2) * math.sin(dlon / 2) ** 2
    return 2 * EARTH_KM * math.asin(min(1.0, math.sqrt(a)))


def parse_ts(s: str | None) -> datetime | None:
    if not s:
        return None
    s = s.strip()
    iso = s[:-1] + "+00:00" if s.endswith("Z") else s
    try:
        d = datetime.fromisoformat(iso)
        return (d if d.tzinfo else d.replace(tzinfo=timezone.utc)).astimezone(timezone.utc)
    except Exception:
        pass
    try:
        d = parsedate_to_datetime(s)
        return (d if d.tzinfo else d.replace(tzinfo=timezone.utc)).astimezone(timezone.utc)
    except Exception:
        return None


def _decimals(v: Any) -> int:
    """How many decimal places the coordinate carries (round coords = coarse)."""
    try:
        s = repr(float(v))
        return len(s.split(".")[1].rstrip("0")) if "." in s else 0
    except Exception:
        return 0


def nearest_comune(lat: float, lon: float) -> tuple[str, float]:
    best_name, best_m = "", float("inf")
    for name, clat, clon in COMUNE_CENTROIDS:
        m = haversine_km(lat, lon, clat, clon) * 1000.0
        if m < best_m:
            best_name, best_m = name, m
    return best_name, best_m


# ── bucket logic ────────────────────────────────────────────────────────────
def classify_location_quality(r: dict[str, Any]) -> dict[str, Any]:
    """Return {bucket, reasons[], nearest_comune, comune_m}.

    gps_precise        — usable as geolocation truth
    village_attributed — detection corroboration only
    coarse_unknown     — ambiguous (→ village_attributed for geoloc)
    Conservative by design: ambiguity falls to village_attributed so the
    geoloc-truth subset stays clean.
    """
    reasons: list[str] = []
    try:
        lat = float(r["lat"]); lon = float(r["lon"])
    except (KeyError, TypeError, ValueError):
        return {"bucket": "coarse_unknown", "reasons": ["invalid_coords"],
                "nearest_comune": None, "comune_m": None}

    acc = r.get("accuracy_m")
    try:
        acc = float(acc) if acc is not None else None
    except (TypeError, ValueError):
        acc = None

    loc_src = str(r.get("location_source") or "").strip().lower()
    cname, cm = nearest_comune(lat, lon)
    near_centroid = cm <= VILLAGE_SNAP_M
    dec = min(_decimals(lat), _decimals(lon))

    # ── Explicit provenance the app stamps wins over every heuristic ──────────
    # Canonical source enum (writes.ts / Models.kt LocationSource), with the
    # legacy 'gps'/'manual_edit'/'map_pin' aliases kept so older rows still map.
    #   gps_quickreport / gps_form          → device GPS, unedited → precise
    #   manual_edited / map_pin / pwa_pin    → typed/pinned        → village-attr
    PRECISE_SOURCES = {"gps_quickreport", "gps_form", "gps"}
    VILLAGE_SOURCES = {"manual_edited", "manual_edit", "map_pin", "pwa_pin"}

    if loc_src in VILLAGE_SOURCES:
        reasons.append(f"location_source={loc_src}")
        return {"bucket": "village_attributed", "reasons": reasons,
                "nearest_comune": cname, "comune_m": round(cm, 1)}
    if loc_src in PRECISE_SOURCES:
        # GPS-sourced. If a real accuracy_m is present and coarse, that physical
        # signal still demotes it (a bad fix is a bad fix even from the big button).
        if acc is not None and acc > ACCURACY_COARSE_M:
            reasons.append(f"location_source={loc_src}_but_accuracy_m={acc:.0f}>"
                           f"{ACCURACY_COARSE_M:.0f}")
            return {"bucket": "village_attributed", "reasons": reasons,
                    "nearest_comune": cname, "comune_m": round(cm, 1)}
        reasons.append(f"location_source={loc_src}")
        if acc is not None:
            reasons.append(f"accuracy_m={acc:.0f}")
        if near_centroid:
            reasons.append(f"on_centroid({cname},{cm:.0f}m)_but_gps_source")
        return {"bucket": "gps_precise", "reasons": reasons,
                "nearest_comune": cname, "comune_m": round(cm, 1)}

    # ── No explicit source → accuracy_m signal ────────────────────────────────
    if acc is not None and acc <= ACCURACY_PRECISE_M:
        reasons.append(f"accuracy_m={acc:.0f}<=" f"{ACCURACY_PRECISE_M:.0f}")
        if near_centroid:
            # precise GPS that happens to land on a centroid: keep precise but flag
            reasons.append(f"on_centroid({cname},{cm:.0f}m)_but_gps_precise")
        return {"bucket": "gps_precise", "reasons": reasons,
                "nearest_comune": cname, "comune_m": round(cm, 1)}
    if acc is not None and acc > ACCURACY_COARSE_M:
        reasons.append(f"accuracy_m={acc:.0f}>{ACCURACY_COARSE_M:.0f}")
        return {"bucket": "village_attributed", "reasons": reasons,
                "nearest_comune": cname, "comune_m": round(cm, 1)}

    # no usable accuracy_m → heuristics
    if near_centroid:
        reasons.append(f"within_{VILLAGE_SNAP_M:.0f}m_of_{cname}({cm:.0f}m)")
        return {"bucket": "village_attributed", "reasons": reasons,
                "nearest_comune": cname, "comune_m": round(cm, 1)}
    if dec <= 3:
        reasons.append(f"round_coords({dec}dp)")
        return {"bucket": "village_attributed", "reasons": reasons,
                "nearest_comune": cname, "comune_m": round(cm, 1)}
    if dec >= 4:
        reasons.append(f"precise_coords({dec}dp)_off_centroid")
        return {"bucket": "gps_precise", "reasons": reasons,
                "nearest_comune": cname, "comune_m": round(cm, 1)}

    reasons.append("ambiguous")
    return {"bucket": "coarse_unknown", "reasons": reasons,
            "nearest_comune": cname, "comune_m": round(cm, 1)}


# ── scar matching (village-to-scar bias) ────────────────────────────────────
def load_scars(truth_db: Path) -> list[dict[str, Any]]:
    """Read scar-backed truth events (lat/lon/ts) read-only. Returns [] if the
    DB / table is unavailable (bias step is then skipped)."""
    if not truth_db.exists():
        return []
    try:
        con = sqlite3.connect(f"file:{truth_db}?mode=ro", uri=True, timeout=10.0)
        con.row_factory = sqlite3.Row
    except sqlite3.OperationalError as e:
        sys.stderr.write(f"[split] cannot open truth db ro: {e}\n")
        return []
    scars: list[dict[str, Any]] = []
    try:
        rows = con.execute(
            "SELECT lat, lon, ts_approx_utc AS ts FROM manual_truth_events "
            "WHERE lat IS NOT NULL AND lon IS NOT NULL"
        ).fetchall()
        for r in rows:
            scars.append({"lat": r["lat"], "lon": r["lon"], "ts": parse_ts(r["ts"])})
    except sqlite3.OperationalError as e:
        sys.stderr.write(f"[split] manual_truth_events read failed: {e}\n")
    finally:
        con.close()
    return scars


def match_scar(r: dict[str, Any], scars: list[dict[str, Any]]) -> dict[str, Any] | None:
    """Nearest scar within SCAR_MATCH_DD_KM and SCAR_MATCH_DT_H. The scar
    centroid is the TRUE fire location; bias_km is the report→scar distance."""
    try:
        lat = float(r["lat"]); lon = float(r["lon"])
    except (KeyError, TypeError, ValueError):
        return None
    ts = parse_ts(r.get("ts_observed_utc") or r.get("ts_utc"))
    best = None
    for s in scars:
        if s["ts"] is not None and ts is not None:
            if abs((ts - s["ts"]).total_seconds()) / 3600.0 > SCAR_MATCH_DT_H:
                continue
        dd = haversine_km(lat, lon, s["lat"], s["lon"])
        if dd > SCAR_MATCH_DD_KM:
            continue
        if best is None or dd < best["bias_km"]:
            best = {"bias_km": round(dd, 3), "scar_lat": s["lat"], "scar_lon": s["lon"]}
    return best


# ── D1 pull (read-only) ─────────────────────────────────────────────────────
def pull_reports_d1() -> list[dict[str, Any]]:
    acct = os.environ.get("CF_ACCOUNT_ID")
    token = os.environ.get("CF_API_TOKEN")
    if not (acct and token):
        sys.stderr.write("[split] --d1 needs CF_ACCOUNT_ID + CF_API_TOKEN\n")
        return []
    url = (f"https://api.cloudflare.com/client/v4/accounts/{acct}"
           f"/d1/database/{D1_DATABASE_ID}/query")
    sql = ("SELECT report_id, lat, lon, accuracy_m, location_source, "
           "ts_observed_utc, observation_type, media_keys, status, "
           "confirm_count, validation_status FROM citizen_reports")
    req = urllib.request.Request(
        url, data=json.dumps({"sql": sql}).encode(), method="POST",
        headers={"authorization": f"Bearer {token}",
                 "content-type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = json.loads(resp.read().decode())
        return body["result"][0]["results"]
    except (urllib.error.URLError, KeyError, IndexError) as e:
        sys.stderr.write(f"[split] D1 pull failed: {e}\n")
        return []


# ── stats ───────────────────────────────────────────────────────────────────
def dist_stats(vals: list[float]) -> dict[str, Any]:
    if not vals:
        return {"n": 0}
    vs = sorted(vals)
    return {
        "n": len(vs),
        "median_km": round(statistics.median(vs), 3),
        "mean_km": round(statistics.fmean(vs), 3),
        "p90_km": round(vs[min(len(vs) - 1, int(0.9 * len(vs)))], 3),
        "max_km": round(vs[-1], 3),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--reports-json", help="JSON array of citizen_reports rows")
    g.add_argument("--d1", action="store_true", help="pull live from D1 (read-only)")
    ap.add_argument("--truth-db", default=DEFAULT_TRUTH_DB,
                    help="DGX ground_truth.sqlite for scar matching")
    ap.add_argument("--out", help="write JSON report here")
    args = ap.parse_args()

    if args.d1:
        reports = pull_reports_d1()
    else:
        reports = json.loads(Path(args.reports_json).read_text())
        if isinstance(reports, dict):                 # tolerate {"rows":[...]}
            reports = reports.get("rows") or reports.get("results") or []

    scars = load_scars(Path(args.truth_db))
    scar_note = (f"{len(scars)} scar events"
                 if scars else "NO scar truth (bias skipped)")

    per_report: list[dict[str, Any]] = []
    buckets: dict[str, int] = {"gps_precise": 0, "village_attributed": 0,
                               "coarse_unknown": 0}
    bias_by_bucket: dict[str, list[float]] = {"gps_precise": [],
                                              "village_attributed": [],
                                              "coarse_unknown": []}
    n_with_media = 0

    for r in reports:
        q = classify_location_quality(r)
        buckets[q["bucket"]] = buckets.get(q["bucket"], 0) + 1
        mk = r.get("media_keys")
        has_media = bool(mk) and mk not in ("[]", "null")
        if has_media:
            n_with_media += 1
        scar = match_scar(r, scars) if scars else None
        if scar is not None:
            bias_by_bucket[q["bucket"]].append(scar["bias_km"])
        per_report.append({
            "report_id": r.get("report_id"),     # privacy: report_id only
            "bucket": q["bucket"],
            "reasons": q["reasons"],
            "nearest_comune": q["nearest_comune"],
            "comune_m": q["comune_m"],
            "has_media": has_media,
            "geoloc_truth": q["bucket"] == "gps_precise",
            "scar_match": scar,
        })

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "n_reports": len(reports),
        "n_with_photo": n_with_media,
        "scar_truth": scar_note,
        "split_counts": buckets,
        "geoloc_truth_subset": buckets.get("gps_precise", 0),
        "detection_only_subset": buckets.get("village_attributed", 0)
                                 + buckets.get("coarse_unknown", 0),
        "village_to_scar_bias_km": {
            "village_attributed": dist_stats(bias_by_bucket["village_attributed"]),
            "gps_precise":        dist_stats(bias_by_bucket["gps_precise"]),
            "coarse_unknown":     dist_stats(bias_by_bucket["coarse_unknown"]),
            "note": ("median(village_attributed) - median(gps_precise) is the "
                     "measured village-attribution bias magnitude"),
        },
        "per_report": per_report,
    }

    out = json.dumps(report, indent=2, ensure_ascii=False)
    if args.out:
        Path(args.out).write_text(out)
        print(f"[split] wrote {args.out}")
    # privacy-safe console summary (no per-report PII)
    print(f"[split] reports={report['n_reports']} with_photo={n_with_media} "
          f"| {scar_note}")
    print(f"[split] gps_precise={buckets['gps_precise']} "
          f"village_attributed={buckets['village_attributed']} "
          f"coarse_unknown={buckets['coarse_unknown']}")
    va = report["village_to_scar_bias_km"]["village_attributed"]
    gp = report["village_to_scar_bias_km"]["gps_precise"]
    if va.get("n") or gp.get("n"):
        print(f"[split] bias_km village_attributed={va} gps_precise={gp}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
