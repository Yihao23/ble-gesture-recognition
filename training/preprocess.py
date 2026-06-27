#!/usr/bin/env python3
"""
Preprocess the BLE-IMU dataset into a single .npz ready for training.

Pipeline (matches what the ESP32 will do at inference time):
  1. Load every dataset/<gesture>/*.csv  (cols: t, ax, ay, az, gx, gy, gz)
  2. Drop windows shorter than WINDOW (a few idle captures dropped frames)
  3. Center-crop each window to exactly WINDOW raw samples (no resampling —
     keep the native ~59 Hz temporal dynamics; the device collects the same
     fixed number of raw samples per inference)
  4. Z-normalize per window, per channel: (x - mean) / (std + 1e-6)
  5. Stack into X [N, WINDOW, 6] float32, y [N] int

Usage:
    python preprocess.py [--data training/dataset] [--out training/dataset.npz]
"""
import argparse
import csv
import glob
import os

import numpy as np

# Fixed label order — the ESP32 firmware must use this same order.
GESTURES = ["idle", "single_tap", "double_tap", "shake", "rotate"]
WINDOW = 100          # raw samples per window (~1.7 s at 59 Hz)
CHANNELS = 6          # ax, ay, az, gx, gy, gz


def load_csv(path):
    """Return an (n, 6) float array of the 6 IMU channels, or None if unreadable."""
    rows = []
    with open(path) as f:
        r = csv.reader(f)
        next(r, None)  # header
        for row in r:
            if len(row) < 7:
                continue
            rows.append([float(v) for v in row[1:7]])  # skip t column
    if not rows:
        return None
    return np.asarray(rows, dtype=np.float32)


def center_crop(arr, n):
    """Take the centered n rows of arr (assumes len(arr) >= n)."""
    start = (len(arr) - n) // 2
    return arr[start:start + n]


def znorm(window):
    """Per-channel z-normalization over the time axis."""
    mean = window.mean(axis=0, keepdims=True)
    std = window.std(axis=0, keepdims=True)
    return (window - mean) / (std + 1e-6)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="training/dataset")
    ap.add_argument("--out", default="training/dataset.npz")
    args = ap.parse_args()

    X, y = [], []
    for label, g in enumerate(GESTURES):
        files = sorted(glob.glob(os.path.join(args.data, g, "*.csv")))
        kept = dropped = 0
        for f in files:
            arr = load_csv(f)
            if arr is None or len(arr) < WINDOW:
                dropped += 1
                continue
            w = znorm(center_crop(arr, WINDOW))
            X.append(w)
            y.append(label)
            kept += 1
        print(f"  {g:12s} kept={kept:3d}  dropped={dropped}")

    X = np.asarray(X, dtype=np.float32)
    y = np.asarray(y, dtype=np.int64)
    print(f"\nDataset: X={X.shape}  y={y.shape}  classes={len(GESTURES)}")
    np.savez_compressed(args.out, X=X, y=y, gestures=np.array(GESTURES))
    print(f"Saved -> {args.out}")


if __name__ == "__main__":
    main()
