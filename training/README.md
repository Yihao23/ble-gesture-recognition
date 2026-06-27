# Training pipeline (BLE variant)

This folder mirrors `edge-ai-gestures/02_training/`, but consumes the
**BLE-sampled** dataset recorded via `../tools/ble_imu_recorder.py`.

## Key differences from the I²C variant

| Aspect | I²C variant | BLE variant |
|---|---|---|
| Sampling rate | 100 Hz (deterministic) | typically 25–50 Hz, jittery |
| Window length | 200 samples (2 s) | adjust to match new rate × 2 s |
| Drop tolerance | none (busy loop) | must handle BLE packet loss |
| Time base | even spacing | use the BLE `t` column |

The 1D-CNN architecture itself is unchanged — only `input_shape` and any
filter cutoffs that depend on sample rate need re-tuning.

## Notebooks (planned)

| # | Notebook | Purpose |
|---|---|---|
| 01 | `01_preprocess.ipynb` | Load CSV → resample to uniform grid → normalize → `.npz` |
| 02 | `02_train.ipynb` | Train 1D-CNN, save fp32 model |
| 03 | `03_quantize.ipynb` | PTQ + QAT |
| 04 | `04_compare_with_i2c.ipynb` | Cross-evaluate I²C model on BLE data and vice versa |

The fourth one is the **most interesting for the write-up**: does a model
trained on I²C data still work when fed BLE samples? If yes, that's a
strong portability claim; if not, it tells you the data distribution
matters more than expected.

## To be added later

Notebooks are stubbed once milestone 2 (BLE data actually flowing) is done.
For now this folder is just the plan — there's no dataset yet to train on.
