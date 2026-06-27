# RUN_WHEN_PHOTOS_ARRIVE — citizen-photo classifier, turnkey

PHOENIX **citizen-photo track only** — the whole-image smoke/flame classifier
that fills `classify_photo()` in `cf_edge/dgx_push/push_to_d1.py`. Not the
satellite detector, not the Pi-5/Hailo ground-sensor YOLO, not drones, not
OLDGABE. Clean-slate Sicilian, no foreign weights.

This is the one-page runbook for the moment real, **labeled** Sicilian citizen
photos exist. They do NOT exist yet: they only arrive after the Android app
(`com.phoenix.citizen` / ADRIZ v2) ships, Sicilians install it and submit
photos (uploaded to Cloudflare R2; D1 rows carry `media_keys`), AND a human
reviews + assigns a class. The pipeline below is pre-staged so that, once
photos are approved, train + eval is **one command**, not a setup day.

---

## Where labeled citizen photos must land

```
Android app (com.phoenix.citizen)
  └─ uploads photo ─▶ Cloudflare R2     (D1 citizen-report row carries media_keys[])
        (future) pull_labeled_photos.py:
          resolve media_keys → download bytes ONCE → <sha>.jpg + <sha>.json
        ─▶ DGX  /home/mark/citizen_photos/labeled/pending/
              <sha>.jpg   <sha>.json   (UNLABELED — do not train on these)
```

**Mark's manual review gate (human-only):** assign each photo a class and move
the `<sha>.jpg` + `<sha>.json` pair into **`labeled/approved/`**, with the
sidecar's `review.photo_label` set to one of:

- positives: `smoke`, `flame`
- false positives / negatives: `no_fire`, `cloud`, `dust` (scirocco/Saharan),
  `chimney` (chimney / industrial / ag-burn / Etna plume — any non-wildfire
  plume).

`corpus_builder.py` maps a wide set of synonyms onto those six canonical
classes (see its `LABEL_SYNONYMS`). Nothing auto-promotes; source images are
copied, never moved/deleted.

> Class convention (matches `classify_photo()` + the report FP taxonomy):
> positives **smoke / flame**; FP types **no_fire / cloud / dust / chimney**.

---

## The gates (in order — none are skippable)

1. **Offline eval** — `eval_photo.py` reports per-class P/R/F1, the confusion
   matrix, and the grade-critical number: **positive-channel precision at
   conf ≥ 0.70** (the same `PHOTO_CONF_HIGH` push_to_d1.py uses). A weak
   positive precision means wiring the model in would wrongly strengthen
   non-fire reports to Grade A.
2. **Mark eyeball** — Mark reads the eval JSON + sample predictions.
3. **Soak** — the model can run in `classify_photo()` in shadow first (its
   output is informational; a positive only strengthens an ALREADY-corroborated
   report, so it cannot create a fire) at real volumes for days.
4. **Wire-in / promote** — only on Mark's explicit go does the model become the
   live `classify_photo()` per `WIRE_IN.md`.

No step here auto-promotes or edits push_to_d1.py.

---

## DGX execution rule (HARD)

Any command that runs on the DGX MUST be launched under `load_guard` (thermal
SIGTERM + watchdog + PHOENIX-health check + busy markers) on an experiment
slice — never `user.slice`:

```bash
/home/mark/bin/load_guard.sh /usr/local/bin/dgx-experiment  <the command>
```

If no GPU slice is free, run eval-only on CPU locally (the classifier is tiny),
or wait. Never force training onto the DGX without the wrap.

---

## THE ONE-COMMAND START (once ≥ ~40 photos are approved)

From the repo on the DGX:

```bash
cd ~/phoenix_citizen_android/citizen_photo_classifier

/home/mark/bin/load_guard.sh /usr/local/bin/dgx-experiment bash -c '
  python corpus_builder.py &&
  python train_photo_classifier.py &&
  python eval_photo.py --candidate \
    "$(ls -t /home/mark/citizen_photos/train_runs/*/weights/best.pt | head -1)"
'
```

That single wrapped command:
1. **corpus_builder.py** — `labeled/approved/` → ImageFolder dataset
   (`/home/mark/citizen_photos/corpus_cls/`, stratified train + Sicilian val
   split, one folder per class). Writes `classes.json` + `manifest.json` (full
   SHA provenance). Refuses below `--min-images` (40).
2. **train_photo_classifier.py** — trains a lightweight classifier
   (mobilenet_v3_small, generic-ImageNet backbone or `--backbone scratch`)
   with Sicily augmentation + small-data HP. Refuses below `--min-images`.
3. **eval_photo.py** — per-class P/R/F1 + confusion matrix + positive-channel
   precision/recall @ conf≥0.70; writes `bench/photo_eval_<stamp>.json`.

Then **STOP** and hand the eval to Mark. Gates 2-4 are human.

### Dry-run any step first (no photos needed — validates wiring today)

```bash
python corpus_builder.py --dry-run
python train_photo_classifier.py --dry-run     # prints exact config + class set
python eval_photo.py --dry-run --candidate <best.pt>
```

---

## Classes + why (matches the report taxonomy)

| class | role | why it's its own class |
|---|---|---|
| `smoke` | positive | wildfire smoke plume — the main thing a citizen photographs |
| `flame` | positive | visible open flame — strongest Grade-A photo strengthener |
| `no_fire` | negative | clear landscape/sky — the dominant "nothing here" case |
| `cloud` | FP | clouds / haze / fog read as smoke to a naive model — must separate |
| `dust` | FP | scirocco / Saharan dust = reddish haze, the classic Sicily false smoke |
| `chimney` | FP | chimney / industrial / ag-burn / Etna plume — a real plume but NOT wildfire |

Separating the FP types (not lumping all into one "negative") teaches the
classifier the specific Sicilian look-alikes, so `classify_photo()` returns
`"none"` on a dust storm or chimney instead of a false `smoke`.

## Augmentation + HP rationale (Sicily-specific, small-data)

| knob | value | why |
|---|---|---|
| brightness/contrast/saturation jitter | 0.40 | harsh Med. midday sun blow-out + deep shade; dusty vs clear air |
| hue jitter | 0.05 | reddish scirocco/Saharan dust cast |
| horizontal flip | 0.5 | landscape horizontally symmetric — safe |
| vertical flip | 0.0 | a smoke plume rises; sky is up — never flip vertically |
| rotation/affine | ±12° | handheld phone (unlike the fixed ground-sensor tower) |
| random erasing | 0.20 | partial occlusion (finger, foreground tree) |
| backbone | mobilenet_v3_small | ~2.5M params; CPU-fast inside the 30s D1-push tick |
| freeze backbone | ON (default) | tiny first corpus — train the head, keep generic features |
| class-balanced loss | inverse-freq | FP classes may dominate; don't drown smoke/flame |
| LR / schedule | 3e-4 cosine, warmup 3 | adapting a generic backbone, not training a giant from scratch |
| early stop | patience 12 on val macro-F1 | a 40-80 image corpus must not overfit |

## Clean-slate Sicilian (HARD)

The only permitted init is a **generic ImageNet** backbone (or `scratch`). It
is FORBIDDEN to seed from any wildfire model — PHOENIX satellite detectors, the
Pi-5/Hailo ground-sensor YOLO/HEF, drone models, or any OLDGABE smoke/flame
weight. The smoke/flame decision is learned from Sicilian citizen photos only.

## After Mark says "wire it in"

Follow `WIRE_IN.md`: deploy `best.pt` to `$PHOENIX_PHOTO_MODEL`
(`/home/mark/citizen_photos/model/best.pt`) and replace the stubbed
`classify_photo()` body with the lazy-load + R2-fetch + infer block. The model
must stay inside the existing load_guard-wrapped 30s tick; no new unguarded
process. A positive return only strengthens an already-corroborated report —
it never creates a fire on its own.

---

## Files in this pipeline

| File | Role |
|---|---|
| `corpus_builder.py` | approved citizen photos → ImageFolder dataset (+ classes.json, manifest) |
| `train_photo_classifier.py` | train lightweight classifier (Sicily aug + small-data HP, clean-slate) |
| `eval_photo.py` | per-class P/R/F1 + confusion + positive-channel precision @ conf≥0.70 |
| `WIRE_IN.md` | exact `classify_photo()` replacement spec (load → R2 fetch → infer → map) |
| `RUN_WHEN_PHOTOS_ARRIVE.md` | this runbook |

All scripts are network-free in dry-run, refuse to act on an empty/too-small
corpus, copy-only (never delete a source photo), and do nothing — no training,
no GPU, no wire-in — without explicit flags and Mark's go.
