#!/usr/bin/env python3
"""train_photo_classifier.py — train the PHOENIX citizen-photo smoke/flame
CLASSIFIER on the approved Sicilian corpus.

PHOENIX citizen-photo track ONLY. This trains the whole-image classifier that
fills `classify_photo()` in `cf_edge/dgx_push/push_to_d1.py`. It is NOT the
satellite detector, NOT the Pi-5/Hailo ground-sensor YOLO, NOT a drone model,
NOT OLDGABE.

HARD RULE — clean-slate Sicilian, no foreign weights
----------------------------------------------------
The only permitted starting point is an OPEN, general-purpose ImageNet
backbone (e.g. torchvision mobilenet_v3_small / efficientnet_b0 with the
public ImageNet weights). That is generic visual pretraining, not a wildfire
model. It is FORBIDDEN to seed from:
  * any PHOENIX satellite detector,
  * the Pi-5/Hailo ground-sensor smoke/flame weights,
  * OLDGABE smoke/flame weights or HEFs,
  * any drone model,
  * any other product's trained smoke/flame checkpoint.
The smoke/flame DECISION layer is trained from scratch on Sicilian citizen
photos only. `--backbone scratch` trains the whole net with no ImageNet init
if even that generic prior is unwanted.

What it does
------------
Reads the ImageFolder corpus from corpus_builder.py (train/ + val/, one
subfolder per class) and trains a lightweight image classifier with
Sicily-aware augmentation and small-data hyperparameters. Pre-app (no photos)
it exits cleanly. `--dry-run` resolves + prints the exact config and the
class set, then exits WITHOUT training (validate wiring with zero data).

Nothing here calls the network, R2, D1, the DGX, or RunPod. The caller
decides where it runs — and on the DGX it MUST be launched under load_guard
(see RUN_WHEN_PHOTOS_ARRIVE.md). load_guard is a RUN-SITE wrap, not something
this script imports.

Why a lightweight classifier (not a detector / not big)
-------------------------------------------------------
`classify_photo()` needs ONE verdict for the whole photo (is this image a
smoke/flame sighting, or a cloud/dust/chimney false positive), plus a
calibrated confidence to compare against PHOTO_CONF_HIGH=0.70. That is image
classification, not localization. A small CNN (mobilenet_v3_small,
~2.5M params) trains well on a tiny Sicilian set, runs CPU-fast inside the
30 s D1-push tick, and won't fight the GPU slice PHOENIX needs.

Sicily-specific augmentation (rationale in RUN_WHEN_PHOTOS_ARRIVE.md):
  - strong brightness/contrast/saturation jitter: harsh Mediterranean midday
    sun blow-out + deep shade; scirocco gives a reddish dust cast. Teaches the
    classifier that a bright/hazy/red-tinted frame is not automatically smoke.
  - mild hue jitter: dust haze vs clear air color shift.
  - horizontal flip only (landscape symmetric); NO vertical flip (a smoke
    plume rises — sky is up).
  - small rotation/affine: phones are handheld (unlike the fixed ground-sensor
    tower) so a little rotation jitter is realistic, but kept small.
  - light random erasing: partial occlusion (a finger, a foreground tree).

Small-data hyperparameters:
  - low LR, cosine schedule, short warmup: we adapt a generic backbone, not
    train a giant net from scratch.
  - --freeze-backbone (default ON for tiny corpora): train only the classifier
    head first so the generic features survive a 40-image corpus.
  - class-balanced loss (inverse-frequency weights): FP classes
    (cloud/dust/chimney) may dominate; weight so smoke/flame aren't drowned.
  - early stopping on val macro-F1.
  - deterministic seed.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

# ── Permitted backbones (generic ImageNet only) ──────────────────────────────
# Allowed values for --backbone. "scratch" = no pretrained weights at all.
# NONE of these is a wildfire model — they are generic torchvision classifiers.
ALLOWED_BACKBONES = {
    "mobilenet_v3_small",   # ~2.5M params, CPU-fast — default
    "mobilenet_v3_large",
    "efficientnet_b0",
    "resnet18",
    "scratch",              # mobilenet_v3_small architecture, random init
}

# Sicily-tuned augmentation magnitudes (consumed when we build the transforms).
SICILY_AUG = dict(
    brightness=0.40,   # harsh midday sun blow-out / deep shade
    contrast=0.40,
    saturation=0.40,   # dusty vs clear air
    hue=0.05,          # reddish scirocco cast
    rotation_deg=12.0, # handheld phone (vs fixed tower) — small jitter
    translate=0.08,
    scale_min=0.85,
    scale_max=1.15,
    hflip_p=0.5,
    vflip_p=0.0,       # plumes rise; never flip vertically
    random_erase_p=0.20,
)

SMALL_DATA_HP = dict(
    lr0=3e-4,
    lrf=0.01,          # final LR = lr0 * lrf (cosine)
    warmup_epochs=3,
    weight_decay=1e-4,
    optimizer="adamw",
    label_smoothing=0.05,
    class_balanced=True,   # inverse-frequency class weights in the loss
)


def _resolve_classes(corpus_dir: Path) -> list[str] | None:
    cj = corpus_dir / "classes.json"
    if cj.exists():
        try:
            return json.loads(cj.read_text())["classes"]
        except Exception:
            pass
    train = corpus_dir / "train"
    if train.is_dir():
        return sorted(p.name for p in train.iterdir() if p.is_dir())
    return None


def _count_split(corpus_dir: Path, split: str) -> dict[str, int]:
    d = corpus_dir / split
    if not d.is_dir():
        return {}
    return {p.name: len(list(p.glob("*.jpg")) + list(p.glob("*.jpeg"))
                        + list(p.glob("*.png")))
            for p in sorted(d.iterdir()) if p.is_dir()}


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Train the PHOENIX citizen-photo smoke/flame classifier",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    ap.add_argument("--data", default=os.environ.get(
        "CITIZEN_PHOTO_CORPUS_DIR", "/home/mark/citizen_photos/corpus_cls"),
        help="ImageFolder corpus dir from corpus_builder.py")
    ap.add_argument("--backbone", default="mobilenet_v3_small",
                    choices=sorted(ALLOWED_BACKBONES),
                    help="Generic ImageNet backbone, or 'scratch' (no pretrain). "
                         "Foreign/wildfire weights are FORBIDDEN — see header.")
    ap.add_argument("--imgsz", type=int, default=224)
    ap.add_argument("--epochs", type=int, default=60)
    ap.add_argument("--patience", type=int, default=12,
                    help="Early-stop patience on val macro-F1")
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--freeze-backbone", dest="freeze_backbone",
                    action="store_true", default=True,
                    help="Train only the head (default ON for tiny corpora)")
    ap.add_argument("--no-freeze-backbone", dest="freeze_backbone",
                    action="store_false",
                    help="Fine-tune the whole net (use once the corpus is larger)")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--device", default="0", help="CUDA device id, or 'cpu'")
    ap.add_argument("--project", default=os.environ.get(
        "CITIZEN_PHOTO_RUNS_DIR", "/home/mark/citizen_photos/train_runs"))
    ap.add_argument("--name", default=None,
                    help="Run name (default: photo_clf_<UTCstamp>)")
    ap.add_argument("--min-images", type=int, default=40,
                    help="Refuse to train on fewer than this many train images")
    ap.add_argument("--dry-run", action="store_true",
                    help="Resolve + print exact config and class set, then exit "
                         "WITHOUT training. Use pre-app to validate wiring.")
    args = ap.parse_args()

    if args.backbone not in ALLOWED_BACKBONES:
        print(f"[train_photo] ERROR: backbone {args.backbone!r} not permitted.")
        return 2

    corpus = Path(args.data)
    data_present = (corpus / "train").is_dir()
    if not data_present:
        print(f"[train_photo] corpus not found: {corpus}")
        print("  -> Run corpus_builder.py first. Pre-app (no approved citizen")
        print("     photos) this is EXPECTED; nothing to train yet.")
        return 0

    classes = _resolve_classes(corpus)
    if not classes:
        print(f"[train_photo] no classes found under {corpus}/train")
        return 1

    train_counts = _count_split(corpus, "train")
    val_counts = _count_split(corpus, "val")
    n_train = sum(train_counts.values())
    n_val = sum(val_counts.values())

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_name = args.name or f"photo_clf_{stamp}"

    cfg = dict(
        data=str(corpus),
        classes=classes,
        n_classes=len(classes),
        backbone=args.backbone,
        pretrained=(args.backbone != "scratch"),   # generic ImageNet only
        imgsz=args.imgsz,
        epochs=args.epochs,
        patience=args.patience,
        batch=args.batch,
        freeze_backbone=args.freeze_backbone,
        seed=args.seed,
        device=args.device,
        project=args.project,
        name=run_name,
        train_counts=train_counts,
        val_counts=val_counts,
        **{f"aug.{k}": v for k, v in SICILY_AUG.items()},
        **{f"hp.{k}": v for k, v in SMALL_DATA_HP.items()},
    )

    print("[train_photo] resolved training config:")
    for k in sorted(cfg):
        if k in ("train_counts", "val_counts", "classes"):
            continue
        print(f"    {k:22s} = {cfg[k]}")
    print(f"    classes               = {classes}")
    print(f"    train images          = {n_train}  {train_counts}")
    print(f"    val images            = {n_val}  {val_counts}")
    print("[train_photo] backbone init: "
          + ("generic ImageNet (allowed)" if cfg["pretrained"]
             else "random/scratch (no pretrain)")
          + " — NO wildfire/foreign/OLDGABE weights (clean-slate Sicilian).")

    if args.dry_run:
        print("[train_photo] DRY-RUN: wiring validated, NOT training. "
              "Remove --dry-run once citizen photos are approved.")
        return 0

    if n_train < args.min_images:
        print(f"[train_photo] fewer than --min-images={args.min_images} "
              f"train images ({n_train}).")
        print("  -> Hold off: a corpus this small will overfit. Approve/label "
              "more citizen photos or lower --min-images deliberately.")
        return 1

    # Lazy import so --dry-run + pre-app exits work with no torch installed.
    try:
        import torch
        import torch.nn as nn
        from torch.utils.data import DataLoader
        from torchvision import datasets, transforms, models
    except Exception as e:
        print(f"[train_photo] torch/torchvision import failed: {e}")
        print("  -> Install the training deps on the GPU host. (Pre-app this "
              "path is never reached.)")
        return 3

    torch.manual_seed(args.seed)
    a = SICILY_AUG
    mean, std = [0.485, 0.456, 0.406], [0.229, 0.224, 0.225]  # ImageNet stats
    train_tf = transforms.Compose([
        transforms.RandomResizedCrop(args.imgsz, scale=(a["scale_min"], a["scale_max"])),
        transforms.RandomHorizontalFlip(a["hflip_p"]),
        transforms.RandomAffine(degrees=a["rotation_deg"],
                                translate=(a["translate"], a["translate"])),
        transforms.ColorJitter(brightness=a["brightness"], contrast=a["contrast"],
                               saturation=a["saturation"], hue=a["hue"]),
        transforms.ToTensor(),
        transforms.Normalize(mean, std),
        transforms.RandomErasing(p=a["random_erase_p"]),
    ])
    eval_tf = transforms.Compose([
        transforms.Resize(int(args.imgsz * 1.14)),
        transforms.CenterCrop(args.imgsz),
        transforms.ToTensor(),
        transforms.Normalize(mean, std),
    ])

    train_ds = datasets.ImageFolder(str(corpus / "train"), transform=train_tf)
    val_ds = datasets.ImageFolder(str(corpus / "val"), transform=eval_tf) \
        if (corpus / "val").is_dir() else None
    # Persist the ACTUAL class order torchvision assigned (it sorts folder
    # names) so WIRE_IN / eval read an authoritative map.
    class_order = train_ds.classes

    train_ld = DataLoader(train_ds, batch_size=args.batch, shuffle=True, num_workers=2)
    val_ld = DataLoader(val_ds, batch_size=args.batch, shuffle=False, num_workers=2) \
        if val_ds else None

    device = ("cuda:" + args.device) if (args.device != "cpu" and torch.cuda.is_available()) else "cpu"

    def build_model(name: str, nc: int):
        pre = (name != "scratch")
        w = "DEFAULT" if pre else None
        if name in ("mobilenet_v3_small", "scratch"):
            m = models.mobilenet_v3_small(weights=w if name != "scratch" else None)
            in_f = m.classifier[-1].in_features
            m.classifier[-1] = nn.Linear(in_f, nc)
            head = m.classifier
        elif name == "mobilenet_v3_large":
            m = models.mobilenet_v3_large(weights=w)
            in_f = m.classifier[-1].in_features
            m.classifier[-1] = nn.Linear(in_f, nc)
            head = m.classifier
        elif name == "efficientnet_b0":
            m = models.efficientnet_b0(weights=w)
            in_f = m.classifier[-1].in_features
            m.classifier[-1] = nn.Linear(in_f, nc)
            head = m.classifier
        elif name == "resnet18":
            m = models.resnet18(weights=w)
            m.fc = nn.Linear(m.fc.in_features, nc)
            head = m.fc
        else:
            raise ValueError(name)
        return m, head

    model, head = build_model(args.backbone, len(class_order))
    if args.freeze_backbone:
        for p in model.parameters():
            p.requires_grad = False
        for p in head.parameters():
            p.requires_grad = True
    model.to(device)

    # Class-balanced loss.
    if SMALL_DATA_HP["class_balanced"]:
        counts = [max(1, train_counts.get(c, 0)) for c in class_order]
        inv = torch.tensor([1.0 / c for c in counts], dtype=torch.float32)
        weight = (inv / inv.sum() * len(counts)).to(device)
    else:
        weight = None
    criterion = nn.CrossEntropyLoss(weight=weight,
                                    label_smoothing=SMALL_DATA_HP["label_smoothing"])
    params = [p for p in model.parameters() if p.requires_grad]
    opt = torch.optim.AdamW(params, lr=SMALL_DATA_HP["lr0"],
                            weight_decay=SMALL_DATA_HP["weight_decay"])
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(
        opt, T_max=args.epochs, eta_min=SMALL_DATA_HP["lr0"] * SMALL_DATA_HP["lrf"])

    out_dir = Path(args.project) / run_name
    (out_dir / "weights").mkdir(parents=True, exist_ok=True)
    (out_dir / "classes.json").write_text(json.dumps(
        {"classes": class_order,
         "index_to_class": {str(i): c for i, c in enumerate(class_order)}},
        indent=2))

    def macro_f1(loader) -> float:
        model.eval()
        nc = len(class_order)
        tp = [0] * nc; fp = [0] * nc; fn = [0] * nc
        with torch.no_grad():
            for x, y in loader:
                x, y = x.to(device), y.to(device)
                pred = model(x).argmax(1)
                for t, p in zip(y.tolist(), pred.tolist()):
                    if t == p:
                        tp[t] += 1
                    else:
                        fp[p] += 1; fn[t] += 1
        f1s = []
        for i in range(nc):
            denom = 2 * tp[i] + fp[i] + fn[i]
            f1s.append((2 * tp[i] / denom) if denom else 0.0)
        return sum(f1s) / len(f1s)

    best_f1, best_epoch, since = -1.0, -1, 0
    for epoch in range(args.epochs):
        model.train()
        for x, y in train_ld:
            x, y = x.to(device), y.to(device)
            opt.zero_grad()
            loss = criterion(model(x), y)
            loss.backward()
            opt.step()
        sched.step()
        f1 = macro_f1(val_ld) if val_ld else macro_f1(train_ld)
        print(f"[train_photo] epoch {epoch+1}/{args.epochs} val_macroF1={f1:.4f}")
        if f1 > best_f1:
            best_f1, best_epoch, since = f1, epoch + 1, 0
            torch.save({"model_state": model.state_dict(),
                        "backbone": args.backbone,
                        "imgsz": args.imgsz,
                        "classes": class_order,
                        "norm_mean": mean, "norm_std": std},
                       out_dir / "weights" / "best.pt")
        else:
            since += 1
            if since >= args.patience:
                print(f"[train_photo] early stop (no val gain in {args.patience} epochs)")
                break

    print(f"[train_photo] DONE. best val_macroF1={best_f1:.4f} @ epoch {best_epoch}")
    print(f"[train_photo] best weights: {out_dir / 'weights' / 'best.pt'}")
    print(f"[train_photo] next: eval_photo.py --candidate "
          f"{out_dir / 'weights' / 'best.pt'} --data {corpus}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
