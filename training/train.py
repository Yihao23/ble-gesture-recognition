#!/usr/bin/env python3
"""
Train a tiny 1D-CNN gesture classifier on the preprocessed BLE-IMU dataset.

Architecture mirrors the edge-ai-gestures model (~3k params) so the wired
and wireless variants stay comparable. Input [WINDOW, 6] -> 5-class softmax.

Usage:
    python train.py [--npz training/dataset.npz] [--out training/models/fp32.h5]
"""
import argparse
import os

import numpy as np
import tensorflow as tf
from sklearn.metrics import confusion_matrix
from sklearn.model_selection import train_test_split


def build_model(window, channels, n_classes):
    return tf.keras.Sequential([
        tf.keras.layers.Input(shape=(window, channels)),
        tf.keras.layers.Conv1D(8, 5, activation="relu"),
        tf.keras.layers.MaxPooling1D(2),
        tf.keras.layers.Conv1D(16, 5, activation="relu"),
        tf.keras.layers.MaxPooling1D(2),
        tf.keras.layers.Conv1D(32, 3, activation="relu"),
        tf.keras.layers.GlobalAveragePooling1D(),
        tf.keras.layers.Dense(16, activation="relu"),
        tf.keras.layers.Dense(n_classes, activation="softmax"),
    ])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--npz", default="training/dataset.npz")
    ap.add_argument("--out", default="training/models/fp32.h5")
    ap.add_argument("--epochs", type=int, default=120)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    tf.random.set_seed(args.seed)
    np.random.seed(args.seed)

    d = np.load(args.npz, allow_pickle=True)
    X, y, gestures = d["X"], d["y"], list(d["gestures"])
    print(f"Loaded X={X.shape} y={y.shape} classes={gestures}")

    X_tr, X_val, y_tr, y_val = train_test_split(
        X, y, test_size=0.2, stratify=y, random_state=args.seed
    )
    print(f"train={X_tr.shape[0]}  val={X_val.shape[0]}")

    model = build_model(X.shape[1], X.shape[2], len(gestures))
    model.summary()
    model.compile(optimizer=tf.keras.optimizers.Adam(1e-3),
                  loss="sparse_categorical_crossentropy",
                  metrics=["accuracy"])

    cbs = [
        tf.keras.callbacks.EarlyStopping(
            monitor="val_accuracy", patience=25,
            restore_best_weights=True, mode="max"),
    ]
    model.fit(X_tr, y_tr, validation_data=(X_val, y_val),
              epochs=args.epochs, batch_size=16, callbacks=cbs, verbose=2)

    loss, acc = model.evaluate(X_val, y_val, verbose=0)
    print(f"\nVal accuracy: {acc:.3f}")

    y_pred = model.predict(X_val, verbose=0).argmax(1)
    cm = confusion_matrix(y_val, y_pred)
    print("\nConfusion matrix (rows=true, cols=pred):")
    print("        " + "  ".join(f"{g[:6]:>6s}" for g in gestures))
    for i, g in enumerate(gestures):
        print(f"{g[:7]:>7s} " + "  ".join(f"{v:6d}" for v in cm[i]))

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    model.save(args.out)
    print(f"\nSaved -> {args.out}")


if __name__ == "__main__":
    main()
