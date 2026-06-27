#!/usr/bin/env python3
"""corpus_builder.py — assemble an image-CLASSIFICATION dataset from the
APPROVED Sicilian citizen-photo corpus.

PHOENIX citizen-photo track ONLY. This is the whole-image smoke/flame
classifier that fills `classify_photo()` in
`cf_edge/dgx_push/push_to_d1.py`. It is NOT the satellite detector, NOT the
Pi-5/Hailo ground-sensor YOLO, NOT a drone model, NOT OLDGABE. Clean-slate
Sicilian only — no foreign weights, no foreign data.

Mirrors the ground-sensor pre-stage (`yolo_wildfire/sicily_finetune/
build_corpus.py`) but for a CLASSIFIER, not a detector:
  * ground sensor  -> YOLO boxes (Ultralytics images/ + labels/ trees)
  * citizen photo  -> per-class folders (ImageFolder layout), one label
                      per image (the whole photo is smoke / flame / cloud / ...)

Input layout (on DGX, under $CITIZEN_PHOTO_ROOT, default
/home/mark/citizen_photos):

    labeled/approved/
        <sha256>.jpg            image (untouched source bytes from R2)
        <sha256>.json           sidecar: provenance + the human-review label

Where do the images come from? The Android app uploads photos to Cloudflare
R2; each citizen report row in D1 carries `media_keys` (a JSON array of R2
object keys). The (future) `pull_labeled_photos.py` step resolves those keys,
downloads the bytes once, and — AFTER a human assigns a class — drops the
<sha>.jpg + <sha>.json pair into `labeled/approved/`. THIS script reads only
that human-approved folder. It never calls R2 or D1 itself, never
auto-labels, and never moves/deletes a source file (copy-only).

The review label lives in the sidecar as `review.photo_label` (preferred) or
top-level `label`. Anything else is skipped with a warning — we do not guess.

CLASS CONVENTION
----------------
Positive (fire) classes — these are what may strengthen a Grade-A report:
    smoke, flame
Negative / false-positive classes — the report taxonomy's FP types, so the
classifier learns to NOT fire on them (chimney smoke, scirocco dust, cloud,
etc.) and `classify_photo()` returns a non-fire class instead:
    no_fire, cloud, dust, chimney
The FP taxonomy mirrors push_to_d1.py grading + the ground-sensor
build_corpus FP_REVIEW_LABELS, collapsed to the few visually-separable
citizen-photo cases (scirocco/Saharan dust -> dust; haze/fog -> cloud;
industrial/ag-burn plume that is NOT wildfire -> chimney).

Output: an ImageFolder-style dataset (train/ and val/, each with one
subfolder per class) + a manifest.json with full SHA provenance and the
exact class counts that went in. Refuses to build below --min-images.

Nothing is deleted. Source images are only ever READ and COPIED.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import shutil
from collections import Counter
from pathlib import Path

# ── Canonical class set ──────────────────────────────────────────────────────
# Order is fixed so the trained class index is stable across rebuilds (the
# WIRE_IN contract depends on a stable class<->index map). New classes append.
CLASSES = ["smoke", "flame", "no_fire", "cloud", "dust", "chimney"]
POSITIVE_CLASSES = {"smoke", "flame"}          # may strengthen a Grade-A report
FIRE_RETURNED_BY_MODEL = {"smoke", "flame"}    # what classify_photo may return as "fire-ish"

# Review-label synonyms -> canonical class. Mirrors the FP taxonomy in
# push_to_d1.py (_grade / FP report types) and the ground-sensor
# build_corpus.py FP_REVIEW_LABELS, but mapped to the citizen-photo class set.
LABEL_SYNONYMS = {
    # positives
    "smoke": "smoke",
    "wildfire_smoke": "smoke",
    "plume": "smoke",
    "flame": "flame",
    "fire": "flame",
    "flames": "flame",
    "open_flame": "flame",
    # clean negative
    "no_fire": "no_fire",
    "nofire": "no_fire",
    "none": "no_fire",
    "negative": "no_fire",
    "clear": "no_fire",
    "landscape": "no_fire",
    # cloud / haze / fog family
    "cloud": "cloud",
    "clouds": "cloud",
    "haze": "cloud",
    "fog": "cloud",
    "mist": "cloud",
    # dust family (scirocco / Saharan)
    "dust": "dust",
    "scirocco": "dust",
    "scirocco_dust": "dust",
    "saharan_dust": "dust",
    "sand": "dust",
    # man-made / non-wildfire plume family
    "chimney": "chimney",
    "chimney_smoke": "chimney",
    "industrial": "chimney",
    "agricultural_burn": "chimney",
    "ag_burn": "chimney",
    "stubble_burn": "chimney",
    "bonfire": "chimney",
    # Etna volcanic plume — visually a plume but never a wildfire; treat as a
    # man-made/non-wildfire-style false positive so it does NOT strengthen.
    "etna": "chimney",
    "etna_volcanic": "chimney",
    "volcanic_plume": "chimney",
}


def _read_sidecar(json_path: Path) -> dict:
    try:
        return json.loads(json_path.read_text())
    except Exception:
        return {}


def _resolve_label(sidecar: dict) -> str | None:
    """Pull the human review label out of the sidecar and canonicalize it.

    Accepts (in order):
      sidecar["review"]["photo_label"]   (preferred — matches review tooling)
      sidecar["label"]                    (top-level convenience)
    Returns a canonical class in CLASSES, or None if unlabeled/unknown
    (we never guess a class for an unlabeled image)."""
    review = sidecar.get("review", {}) if isinstance(sidecar, dict) else {}
    raw = review.get("photo_label") or sidecar.get("label")
    if not raw:
        return None
    key = str(raw).strip().lower().replace(" ", "_").replace("-", "_")
    if key in CLASSES:
        return key
    return LABEL_SYNONYMS.get(key)


def collect(approved_dir: Path) -> tuple[list[dict], list[str]]:
    """Return ([{sha,img,cls}], [warnings]) for every approved, labeled image."""
    items: list[dict] = []
    warnings: list[str] = []
    imgs = (sorted(approved_dir.glob("*.jpg"))
            + sorted(approved_dir.glob("*.jpeg"))
            + sorted(approved_dir.glob("*.png")))
    for img in imgs:
        sha = img.stem
        sidecar = _read_sidecar(approved_dir / f"{sha}.json")
        cls = _resolve_label(sidecar)
        if cls is None:
            warnings.append(f"  ! {img.name}: no recognizable review.photo_label — skipped")
            continue
        items.append({"sha": sha, "img": img, "cls": cls})
    return items, warnings


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Build an image-classification corpus from approved citizen photos")
    ap.add_argument(
        "--approved-dir",
        default=os.environ.get(
            "CITIZEN_PHOTO_APPROVED_DIR",
            "/home/mark/citizen_photos/labeled/approved"),
        help="Directory of human-approved <sha>.jpg + <sha>.json")
    ap.add_argument(
        "--out",
        default=os.environ.get(
            "CITIZEN_PHOTO_CORPUS_DIR",
            "/home/mark/citizen_photos/corpus_cls"),
        help="Output ImageFolder dataset directory (train/ + val/)")
    ap.add_argument("--val-frac", type=float, default=0.20,
                    help="Fraction held out per class for the Sicilian val split")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--min-images", type=int, default=40,
                    help="Refuse to build a corpus with fewer than this many "
                         "labeled images (small-data overfit guard)")
    ap.add_argument("--min-per-class", type=int, default=5,
                    help="Warn if any present class has fewer than this many images")
    ap.add_argument("--dry-run", action="store_true",
                    help="Report what would be built; copy nothing")
    args = ap.parse_args()

    approved = Path(args.approved_dir)
    if not approved.is_dir():
        print(f"[corpus_builder] approved dir does not exist yet: {approved}")
        print("  -> No citizen photos approved (none exist until the app ships +")
        print("     Sicilians submit + a human labels them). Nothing to build.")
        return 0

    items, warnings = collect(approved)
    for w in warnings:
        print(w)

    n = len(items)
    counts = Counter(it["cls"] for it in items)
    n_pos = sum(counts[c] for c in POSITIVE_CLASSES)
    print(f"[corpus_builder] labeled images        : {n}")
    print(f"[corpus_builder]   positive (smoke/flame): {n_pos}")
    for c in CLASSES:
        if counts.get(c):
            flag = "  (LOW)" if counts[c] < args.min_per_class else ""
            print(f"[corpus_builder]     {c:9s}: {counts[c]}{flag}")

    if n < args.min_images:
        print(f"[corpus_builder] fewer than --min-images={args.min_images}; aborting.")
        print("  -> Approve/label more citizen photos before training.")
        return 1

    random.seed(args.seed)
    out = Path(args.out)

    # Stratified per-class split so every class appears in both train and val.
    by_class: dict[str, list[dict]] = {c: [] for c in CLASSES}
    for it in items:
        by_class[it["cls"]].append(it)

    split_assign: dict[int, str] = {}     # id(item) -> "train"|"val"
    n_train = n_val = 0
    for c, group in by_class.items():
        random.shuffle(group)
        k = int(round(len(group) * args.val_frac)) if len(group) > 1 else 0
        for i, it in enumerate(group):
            split = "val" if i < k else "train"
            split_assign[id(it)] = split
            if split == "val":
                n_val += 1
            else:
                n_train += 1

    manifest = {
        "approved_dir": str(approved),
        "n_images": n,
        "n_positive": n_pos,
        "class_counts": dict(counts),
        "classes": CLASSES,
        "positive_classes": sorted(POSITIVE_CLASSES),
        "val_frac": args.val_frac,
        "seed": args.seed,
        "n_train": n_train,
        "n_val": n_val,
        "splits": {"train": [], "val": []},
        "label_synonyms_used": True,
    }

    if args.dry_run:
        print(f"[corpus_builder] DRY-RUN: would write ImageFolder to {out} "
              f"(train={n_train}, val={n_val}, classes={len(counts)})")
        return 0

    # Create train/<class>/ and val/<class>/ for every PRESENT class.
    present = [c for c in CLASSES if counts.get(c)]
    for split in ("train", "val"):
        for c in present:
            (out / split / c).mkdir(parents=True, exist_ok=True)

    for it in items:
        split = split_assign[id(it)]
        dst = out / split / it["cls"] / it["img"].name
        shutil.copy2(it["img"], dst)      # COPY — never move/delete source
        manifest["splits"][split].append({"sha": it["sha"], "cls": it["cls"]})

    # classes.json is the AUTHORITATIVE index<->name map the trainer + WIRE_IN
    # read. Index = position in `present`, recorded explicitly.
    classes_json = out / "classes.json"
    classes_json.write_text(json.dumps(
        {"classes": present,
         "index_to_class": {str(i): c for i, c in enumerate(present)},
         "positive_classes": sorted(POSITIVE_CLASSES)},
        indent=2))
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2))

    print(f"[corpus_builder] wrote dataset : {out}")
    print(f"[corpus_builder]   classes.json: {classes_json} ({present})")
    print(f"[corpus_builder]   train={n_train}  val={n_val}")
    print(f"[corpus_builder]   manifest    : {out / 'manifest.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
