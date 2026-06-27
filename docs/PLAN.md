# Project plan

Three milestones, each independently demoable.

## Milestone 1 — Validate phone-as-IMU (zero hardware change)

**Goal**: confirm a phone can stream usable IMU data over the network at a
rate sufficient for gesture recognition (target: ≥50 Hz, ≤200 ms end-to-end
latency to laptop).

- Install **Sensor Logger** (Android) or equivalent iOS app.
- Configure it to stream IMU (accel + gyro) over UDP to laptop on local Wi-Fi.
- Run `tools/udp_imu_listener.py` to receive + visualize.
- Verify sample rate, gaps, drift.

**Deliverable**: 30 s recording of each gesture class, real-time matplotlib
plot showing the 6 channels.

**Exit criteria**: data quality at least as good as I²C MPU6050. If yes → continue.

## Milestone 2 — BLE GATT end-to-end (phone ⇄ ESP32-S3)

**Goal**: replace the laptop UDP listener with the ESP32-S3 as a BLE central
that subscribes to the phone's GATT notification characteristic.

- Configure phone's BLE peripheral mode (Sensor Logger has BLE option, or
  use a custom Android app — see `phone_collector/`).
- Flash ESP32-S3 with `esp32_ble_inference/` Zephyr app (BLE central + IMU
  buffer + serial print, no inference yet).
- Verify ESP32 prints incoming 6-axis samples over USB.

**Deliverable**: ESP32-S3 console showing live IMU data from the phone.

**Exit criteria**: stable BLE connection for ≥10 minutes without disconnects.

## Milestone 3 — TFLite Micro inference on the BLE stream

**Goal**: same model from `edge-ai-gestures` runs on the BLE-sourced data.

- Possibly retrain on BLE-sampled dataset if sample rate differs significantly.
- Embed `qat.tflite` via `tools/tflite_to_c.py` into the ESP32-S3 firmware.
- Add windowing + znorm + Invoke() to the BLE receive path.
- LED color or serial output indicates classified gesture.

**Deliverable**: working demo — make a gesture with the phone, ESP32-S3
identifies it within ~2 s.

**Exit criteria**: accuracy ≥ 80% on a manual 25-sample validation; latency
under 100 ms from gesture end to classification print.

## Stretch goals

- M5StickC Plus2 instead of phone (real wearable form factor)
- Wear OS smartwatch as IMU source
- Compare BLE 4.2 vs 5.0 throughput / latency
- Energy measurement on the ESP32 side (BLE radio is expensive)
- Write `docs/REPORT.md` comparing I²C and BLE variants quantitatively
