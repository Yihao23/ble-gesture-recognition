#!/usr/bin/env python3
"""
Post-training int8 quantization of the trained gesture model.

Produces a fully-integer (int8 in / int8 out) TFLite model for TFLite Micro,
reports the input quantization scale/zero-point (the ESP32 needs these), and
evaluates int8 accuracy on the validation split.

Usage:
    python quantize.py [--model training/models/fp32.h5] \
                       [--npz training/dataset.npz] \
                       [--out training/models/gesture_int8.tflite]
"""
import argparse
import os

import numpy as np
import tensorflow as tf
from sklearn.model_selection import train_test_split


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="training/models/fp32.h5")
    ap.add_argument("--npz", default="training/dataset.npz")
    ap.add_argument("--out", default="training/models/gesture_int8.tflite")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    d = np.load(args.npz, allow_pickle=True)
    X, y, gestures = d["X"], d["y"], list(d["gestures"])
    X_tr, X_val, y_tr, y_val = train_test_split(
        X, y, test_size=0.2, stratify=y, random_state=args.seed
    )

    model = tf.keras.models.load_model(args.model)

    def rep_data():
        for i in range(min(200, len(X_tr))):
            yield [X_tr[i:i + 1].astype(np.float32)]

    conv = tf.lite.TFLiteConverter.from_keras_model(model)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    conv.representative_dataset = rep_data
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    conv.inference_input_type = tf.int8
    conv.inference_output_type = tf.int8
    tflite = conv.convert()

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "wb") as f:
        f.write(tflite)
    print(f"Saved -> {args.out}  ({len(tflite)} bytes)")

    # Inspect + evaluate.
    interp = tf.lite.Interpreter(model_content=tflite)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    in_scale, in_zp = inp["quantization"]
    print(f"\nINPUT  quant: scale={in_scale:.10f}  zero_point={in_zp}")
    print(f"OUTPUT quant: scale={out['quantization'][0]:.10f}  "
          f"zero_point={out['quantization'][1]}")
    print(f"Input shape={inp['shape']} dtype={inp['dtype'].__name__}")

    correct = 0
    for i in range(len(X_val)):
        q = np.round(X_val[i] / in_scale + in_zp).astype(np.int8)
        interp.set_tensor(inp["index"], q[None, ...])
        interp.invoke()
        pred = interp.get_tensor(out["index"])[0].argmax()
        correct += int(pred == y_val[i])
    print(f"\nint8 val accuracy: {correct / len(X_val):.3f} "
          f"({correct}/{len(X_val)})")
    print(f"\nLabel order for the ESP32: {gestures}")


if __name__ == "__main__":
    main()
