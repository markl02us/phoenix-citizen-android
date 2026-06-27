#!/usr/bin/env python3
"""eval_photo.py — score a trained citizen-photo classifier on the held-out
Sicilian val split: per-class accuracy / precision / recall / F1, the
confusion matrix, and the operating point that matters for grading.

PHOENIX citizen-photo track ONLY. Evaluates the model that fills
`classify_photo()` in push_to_d1.py. Network-free: reads only the local
ImageFolder corpus + the trained best.pt. No DGX/RunPod/R2/D1 calls.

The grade-critical number
-------------------------
push_to_d1.py strengthens a report to Grade A only when classify_photo()
returns a class in {smoke, flame, fire} with confidence >= PHOTO_CONF_HIGH
(0.70). So the headline metric here is the PRECISION OF THE POSITIVE CHANNEL
at conf >= 0.70: of all photos the model calls smoke/flame with conf >= 0.70,
how many really are wildfire smoke/flame. A false positive here would wrongly
strengthen a non-fire report to Grade A, so this precision must be high. We
report it explicitly alongside the standard per-class metrics, and we report
the positive-channel recall at the same threshold (how many true smoke/flame
photos clear the bar) so Mark sees the precision/recall trade.

This is an ADVISORY report. No promotion happens here — per the
variant-lifecycle rule, Mark eyeballs -> soak -> explicit wire-in.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

POSITIVE_CLASSES = {"smoke", "flame", "fire"}
PHOTO_CONF_HIGH = 0.70   # must match push_to_d1.py PHOTO_CONF_HIGH


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Eval the citizen-photo classifier on the Sicilian held-out val split",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    ap.add_argument("--candidate", required=True, help="Trained best.pt")
    ap.add_argument("--data", default="/home/mark/citizen_photos/corpus_cls",
                    help="ImageFolder corpus dir (uses its val/ split)")
    ap.add_argument("--split", default="val", choices=["val", "train"])
    ap.add_argument("--imgsz", type=int, default=224)
    ap.add_argument("--device", default="0", help="CUDA device id, or 'cpu'")
    ap.add_argument("--pos-conf", type=float, default=PHOTO_CONF_HIGH,
                    help="Positive-channel confidence threshold (grade gate)")
    ap.add_argument("--out-dir", default=str(Path(__file__).resolve().parent / "bench"))
    ap.add_argument("--dry-run", action="store_true",
                    help="Resolve model + split and report availability, no inference")
    args = ap.parse_args()

    corpus = Path(args.data)
    split_dir = corpus / args.split
    cand = Path(args.candidate)

    if args.dry_run:
        print(f"[eval_photo] candidate : {cand} (exists={cand.exists()})")
        print(f"[eval_photo] split dir : {split_dir} (exists={split_dir.is_dir()})")
        print("[eval_photo] DRY-RUN: resolved, no inference run.")
        return 0

    if not cand.exists():
        print(f"[eval_photo] candidate not found: {cand}")
        return 2
    if not split_dir.is_dir():
        print(f"[eval_photo] split dir not found: {split_dir}")
        print("  -> Build the corpus + train first. Pre-app this is EXPECTED.")
        return 0

    try:
        import torch
        from torchvision import datasets, transforms, models
        import torch.nn as nn
    except Exception as e:
        print(f"[eval_photo] torch/torchvision import failed: {e}")
        return 3

    ckpt = torch.load(str(cand), map_location="cpu")
    classes = ckpt["classes"]
    backbone = ckpt.get("backbone", "mobilenet_v3_small")
    mean = ckpt.get("norm_mean", [0.485, 0.456, 0.406])
    std = ckpt.get("norm_std", [0.229, 0.224, 0.225])
    imgsz = ckpt.get("imgsz", args.imgsz)

    def build_model(name, nc):
        if name in ("mobilenet_v3_small", "scratch"):
            m = models.mobilenet_v3_small(weights=None)
            m.classifier[-1] = nn.Linear(m.classifier[-1].in_features, nc)
        elif name == "mobilenet_v3_large":
            m = models.mobilenet_v3_large(weights=None)
            m.classifier[-1] = nn.Linear(m.classifier[-1].in_features, nc)
        elif name == "efficientnet_b0":
            m = models.efficientnet_b0(weights=None)
            m.classifier[-1] = nn.Linear(m.classifier[-1].in_features, nc)
        elif name == "resnet18":
            m = models.resnet18(weights=None)
            m.fc = nn.Linear(m.fc.in_features, nc)
        else:
            raise ValueError(name)
        return m

    model = build_model(backbone, len(classes))
    model.load_state_dict(ckpt["model_state"])
    device = ("cuda:" + args.device) if (args.device != "cpu" and torch.cuda.is_available()) else "cpu"
    model.to(device).eval()

    eval_tf = transforms.Compose([
        transforms.Resize(int(imgsz * 1.14)),
        transforms.CenterCrop(imgsz),
        transforms.ToTensor(),
        transforms.Normalize(mean, std),
    ])
    ds = datasets.ImageFolder(str(split_dir), transform=eval_tf)
    # ImageFolder may see a different class subset/order than training; map.
    folder_classes = ds.classes
    from torch.utils.data import DataLoader
    ld = DataLoader(ds, batch_size=32, shuffle=False, num_workers=2)

    nc = len(classes)
    idx = {c: i for i, c in enumerate(classes)}
    conf_mat = defaultdict(lambda: defaultdict(int))   # true -> pred -> n
    tp = [0] * nc; fp = [0] * nc; fn = [0] * nc; support = [0] * nc
    # positive-channel @ threshold
    pos_tp = pos_fp = pos_fn = 0

    with torch.no_grad():
        for x, y_folder in ld:
            x = x.to(device)
            probs = torch.softmax(model(x), dim=1).cpu()
            top_conf, top_idx = probs.max(1)
            for i in range(len(y_folder)):
                true_name = folder_classes[y_folder[i].item()]
                pred_name = classes[top_idx[i].item()]
                conf = float(top_conf[i].item())
                conf_mat[true_name][pred_name] += 1
                if true_name in idx:
                    support[idx[true_name]] += 1
                if true_name == pred_name and true_name in idx:
                    tp[idx[true_name]] += 1
                else:
                    if pred_name in idx:
                        fp[idx[pred_name]] += 1
                    if true_name in idx:
                        fn[idx[true_name]] += 1
                # positive-channel @ pos-conf (the grade gate)
                pred_pos = (pred_name in POSITIVE_CLASSES) and (conf >= args.pos_conf)
                true_pos = true_name in POSITIVE_CLASSES
                if pred_pos and true_pos:
                    pos_tp += 1
                elif pred_pos and not true_pos:
                    pos_fp += 1
                elif (not pred_pos) and true_pos:
                    pos_fn += 1

    per_class = {}
    correct = total = 0
    for c in classes:
        i = idx[c]
        P = tp[i] / (tp[i] + fp[i]) if (tp[i] + fp[i]) else 0.0
        R = tp[i] / (tp[i] + fn[i]) if (tp[i] + fn[i]) else 0.0
        F1 = 2 * P * R / (P + R) if (P + R) else 0.0
        per_class[c] = {"support": support[i], "P": round(P, 4),
                        "R": round(R, 4), "F1": round(F1, 4)}
        correct += tp[i]; total += support[i]

    overall_acc = correct / total if total else 0.0
    macro_f1 = sum(v["F1"] for v in per_class.values()) / len(per_class) if per_class else 0.0
    pos_P = pos_tp / (pos_tp + pos_fp) if (pos_tp + pos_fp) else 0.0
    pos_R = pos_tp / (pos_tp + pos_fn) if (pos_tp + pos_fn) else 0.0

    report = {
        "generated_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "candidate": str(cand),
        "split": args.split,
        "classes": classes,
        "imgsz": imgsz,
        "overall_accuracy": round(overall_acc, 4),
        "macro_f1": round(macro_f1, 4),
        "per_class": per_class,
        "confusion_matrix": {t: dict(row) for t, row in conf_mat.items()},
        "positive_channel_at_conf": {
            "threshold": args.pos_conf,
            "precision": round(pos_P, 4),
            "recall": round(pos_R, 4),
            "tp": pos_tp, "fp": pos_fp, "fn": pos_fn,
            "note": ("This is the grade-critical number: precision of "
                     "smoke/flame@>=conf is how often a Grade-A photo-strengthen "
                     "is justified. push_to_d1.py PHOTO_CONF_HIGH must match the "
                     "threshold this was measured at."),
        },
    }

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_json = out_dir / f"photo_eval_{stamp}.json"
    out_json.write_text(json.dumps(report, indent=2))

    print(f"[eval_photo] overall accuracy : {overall_acc:.4f}")
    print(f"[eval_photo] macro-F1         : {macro_f1:.4f}")
    print(f"[eval_photo] positive channel @ conf>={args.pos_conf}: "
          f"P={pos_P:.4f} R={pos_R:.4f} (tp={pos_tp} fp={pos_fp} fn={pos_fn})")
    for c, v in per_class.items():
        print(f"[eval_photo]   {c:9s} n={v['support']:4d}  "
              f"P={v['P']:.3f} R={v['R']:.3f} F1={v['F1']:.3f}")
    print(f"[eval_photo] wrote {out_json}")
    print("[eval_photo] REMINDER: advisory only. No auto-promotion / no wire-in. "
          "Mark eyeballs -> soak -> explicit go.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
