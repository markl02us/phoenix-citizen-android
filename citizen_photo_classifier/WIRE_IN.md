# WIRE_IN — dropping the trained model into `classify_photo()`

PHOENIX **citizen-photo track only**. This wires the trained whole-image
smoke/flame classifier into the single integration point that already exists:
`cf_edge/dgx_push/push_to_d1.py :: classify_photo(r)`.

> Do NOT touch the satellite detectors, the Pi-5/Hailo ground-sensor YOLO,
> any drone model, or any OLDGABE asset. This model is clean-slate Sicilian
> citizen photos only.

---

## The contract (already defined in push_to_d1.py — do not change it)

```python
def classify_photo(r: dict[str, Any]) -> tuple[str | None, float | None]:
    # INPUT : the citizen-report dict r. Attached media are R2 object keys in
    #         r["media_keys"] (JSON array). Score the first image.
    # OUTPUT: (photo_class, confidence)
    #   photo_class ∈ {"smoke","flame","fire","none", None}
    #   confidence  ∈ [0.0, 1.0] or None
```

Downstream, `_grade()` treats a return of `smoke`/`flame`/`fire` with
`confidence >= PHOTO_CONF_HIGH` (0.70) as ONE of the three Grade-A
strengtheners (alongside high device reputation and ≥3 distinct devices), but
**only when the report is already corroborated** by an independent fire
signal. A `none` / low-confidence return never downgrades — it just doesn't
strengthen. So a wrong positive can, with corroboration, push a report to
Grade A: the positive channel must be precise (that is exactly what
`eval_photo.py` measures at conf≥0.70).

**Class mapping the wrapper MUST apply:** the trained model has 6 classes
(`smoke, flame, no_fire, cloud, dust, chimney`). The contract only cares
about the positive ones. Map:

| model top-1 class | return to push_to_d1 |
|---|---|
| `smoke` | `("smoke", conf)` |
| `flame` | `("flame", conf)` |
| `no_fire`, `cloud`, `dust`, `chimney` | `("none", conf)` |

Returning `"none"` (not the raw FP class) keeps the contract's enum
(`smoke/flame/fire/none/None`) intact; the raw FP class can be logged in the
moderation-log notes if useful, but `_grade()` only reads the returned tuple.

---

## Exact replacement for the stubbed body

The current stub (lines ~475-482) only surfaces an edge-provided class. Replace
the TODO body with a lazy-loaded model + R2 fetch + inference. Keep the
edge-provided shortcut as a fallback so nothing regresses if the model file is
missing.

```python
# ── module level (near the other config in push_to_d1.py) ──────────────────
PHOTO_MODEL_PATH = Path(os.environ.get(
    "PHOENIX_PHOTO_MODEL", "/home/mark/citizen_photos/model/best.pt"))
PHOTO_FIRE_CLASSES = {"smoke", "flame"}          # map everything else -> "none"
_PHOTO_MODEL = None                              # lazy singleton (load once)
_PHOTO_MODEL_TRIED = False

def _load_photo_model():
    """Load the clean-slate Sicilian citizen-photo classifier ONCE.

    Returns (model, transform, classes) or None if no model is deployed yet
    (pre-app state — classify_photo then falls back to edge-provided fields).
    HARD: this loads ONLY the PHOENIX citizen-photo best.pt. Never a satellite
    detector, never the ground-sensor YOLO, never OLDGABE/drone weights.
    """
    global _PHOTO_MODEL, _PHOTO_MODEL_TRIED
    if _PHOTO_MODEL is not None or _PHOTO_MODEL_TRIED:
        return _PHOTO_MODEL
    _PHOTO_MODEL_TRIED = True
    if not PHOTO_MODEL_PATH.exists():
        return None
    try:
        import torch
        import torch.nn as nn
        from torchvision import transforms, models
        ckpt = torch.load(str(PHOTO_MODEL_PATH), map_location="cpu")
        classes = ckpt["classes"]; backbone = ckpt.get("backbone", "mobilenet_v3_small")
        imgsz = ckpt.get("imgsz", 224)
        mean = ckpt.get("norm_mean", [0.485, 0.456, 0.406])
        std = ckpt.get("norm_std", [0.229, 0.224, 0.225])
        if backbone in ("mobilenet_v3_small", "scratch"):
            m = models.mobilenet_v3_small(weights=None)
            m.classifier[-1] = nn.Linear(m.classifier[-1].in_features, len(classes))
        elif backbone == "efficientnet_b0":
            m = models.efficientnet_b0(weights=None)
            m.classifier[-1] = nn.Linear(m.classifier[-1].in_features, len(classes))
        else:  # resnet18 / mobilenet_v3_large
            m = getattr(models, backbone)(weights=None)
            if hasattr(m, "fc"):
                m.fc = nn.Linear(m.fc.in_features, len(classes))
            else:
                m.classifier[-1] = nn.Linear(m.classifier[-1].in_features, len(classes))
        m.load_state_dict(ckpt["model_state"]); m.eval()
        tf = transforms.Compose([
            transforms.Resize(int(imgsz * 1.14)), transforms.CenterCrop(imgsz),
            transforms.ToTensor(), transforms.Normalize(mean, std)])
        _PHOTO_MODEL = (m, tf, classes)
    except Exception as e:
        _log(f"classify_photo: model load failed ({e}); falling back to edge fields")
        _PHOTO_MODEL = None
    return _PHOTO_MODEL


def _fetch_first_media_image(r: dict[str, Any]):
    """Resolve r['media_keys'][0] (an R2 object key) to a PIL.Image.

    Two supported resolution paths (pick whichever the deployment uses):
      (a) The DGX has the R2 bucket mounted / synced to a local dir
          ($PHOENIX_R2_MEDIA_DIR) — read the key as a file. Preferred: no
          network in the 30s tick.
      (b) The Worker pre-signs a GET URL; the DGX fetches bytes over HTTPS.
          Only if (a) isn't available; keep it inside HTTP_TIMEOUT_S.
    Returns a PIL.Image (RGB) or None.
    """
    keys = r.get("media_keys")
    if isinstance(keys, str):
        try: keys = json.loads(keys)
        except Exception: keys = [keys]
    if not keys:
        return None
    key = keys[0]
    from PIL import Image
    media_dir = os.environ.get("PHOENIX_R2_MEDIA_DIR")          # path (a)
    if media_dir:
        p = Path(media_dir) / key
        if p.exists():
            return Image.open(p).convert("RGB")
    # path (b): ask the Worker for a presigned URL, then fetch (bounded).
    try:
        signed = _http("GET", f"/api/_internal/r2_presign?key={key}")
        url = signed.get("url")
        if url:
            import io, urllib.request
            with urllib.request.urlopen(url, timeout=HTTP_TIMEOUT_S) as resp:
                return Image.open(io.BytesIO(resp.read())).convert("RGB")
    except Exception as e:
        _log(f"classify_photo: media fetch failed for key {key!r}: {e}")
    return None


def classify_photo(r: dict[str, Any]) -> tuple[str | None, float | None]:
    loaded = _load_photo_model()
    if loaded is None:
        # No model deployed yet → preserve the original edge-field shortcut.
        pclass = r.get("photo_class"); pconf = r.get("photo_class_confidence")
        if pclass is not None and pconf is not None:
            f = _coerce_float(pconf)
            if f is not None:
                return str(pclass), min(1.0, max(0.0, f))
        return None, None
    model, tf, classes = loaded
    img = _fetch_first_media_image(r)
    if img is None:
        return None, None
    try:
        import torch
        with torch.no_grad():
            probs = torch.softmax(model(tf(img).unsqueeze(0)), dim=1)[0]
        conf, idx = float(probs.max()), int(probs.argmax())
        top = classes[idx]
    except Exception as e:
        _log(f"classify_photo: inference failed: {e}")
        return None, None
    # Map the 6-class output onto the (smoke|flame|none) contract.
    return (top if top in PHOTO_FIRE_CLASSES else "none"), min(1.0, max(0.0, conf))
```

That is the entire wire-in: load once, fetch the first R2 media image, infer,
map to the contract. Every downstream consumer (`_grade`, `queue_for_review`,
`promote_reviewed_citizen_report`, the verdict push) already reads
`photo_class` / `photo_class_confidence` — nothing else changes.

---

## load_guard (only if push runs on the DGX)

`push_to_d1.py` already runs under the load_guard wrap on the DGX (the
30s `phoenix-d1-push.timer`). Adding model inference inside the tick is fine
**as long as it stays inside that existing wrap** — do NOT spawn a new,
unguarded process for inference. Keep the model CPU-bound (mobilenet_v3_small
is ~CPU-milliseconds per image, well under the 30s tick / `HTTP_TIMEOUT_S`),
or, if it must use the GPU, run it on the guarded experiment slice — never
`user.slice`, never bypassing the thermal SIGTERM / watchdog / busy markers.
If load_guard is degraded or PHOENIX is unhealthy, the tick is already
SIGTERM-able mid-flight; inference inherits that and resumes idempotently next
tick (the report stays "pending" until a verdict is pushed).

## The PHOENIX-Sicily-only rule at the call site

- `_load_photo_model()` loads **exactly** `PHOENIX_PHOTO_MODEL` (the citizen
  best.pt). It is FORBIDDEN to point this at a satellite detector, the
  ground-sensor YOLO/HEF, a drone model, or any OLDGABE weight. The class set
  baked into the checkpoint (`smoke/flame/no_fire/cloud/dust/chimney`) is the
  contract; a checkpoint with any other class set is the wrong model.
- The model is trained clean-slate on Sicilian citizen photos (see
  `train_photo_classifier.py` header). Re-training/refresh uses the same
  pipeline; it never imports foreign smoke/flame weights.
- A positive return only ever **strengthens** an already-corroborated report.
  It cannot create a fire on its own. That property lives in `_grade()` and
  must not be moved into `classify_photo()`.
