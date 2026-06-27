# Phone-side IMU collector

This folder is documentation, not code — the phone runs an existing app,
and your laptop / ESP32-S3 is the receiver.

## Option A — Sensor Logger (recommended for milestone 1)

**Android**: [Sensor Logger by Kelvin Choi](https://play.google.com/store/apps/details?id=com.kelvin.sensorapp)
**iOS**: [Sensor Logger by Kelvin Choi](https://apps.apple.com/us/app/sensor-logger/id1531582925)

Both versions support:
- Live UDP streaming of any selected sensor stream (great for milestone 1)
- HTTP push (alternative)
- Local recording to CSV (good for offline training data collection)

Configure for milestone 1:

1. Settings → Streaming → enable UDP
2. Set target IP = your laptop's LAN IP
3. Set target port = `8080`
4. Select sensors: Accelerometer + Gyroscope (raw, 100 Hz)
5. Press the big red record button to start streaming

Receive on the laptop with `../tools/udp_imu_listener.py`.

**Note**: Sensor Logger's free version may not expose BLE peripheral mode.
For milestone 2 (ESP32-S3 BLE central) we likely need a custom app.

## Option B — Custom Android app for BLE GATT (milestone 2)

Minimal Android app that exposes a custom GATT service:

| Field | Value |
|---|---|
| Service UUID | `0000ec00-b87f-490c-92cb-11ba5ea5167c` (random) |
| Characteristic UUID | `0000ec01-b87f-490c-92cb-11ba5ea5167c` (notify) |
| Payload | 12 bytes: 6 × `int16` (ax, ay, az, gx, gy, gz) |
| Notify rate | 50 Hz |

Skeleton Android Studio project will be added under `android_app/` once we
get to milestone 2. For now any of these alternatives also work:

- **nRF Connect for Mobile** (Nordic) — has an Advertiser feature
- **Bluefruit Connect** (Adafruit) — has UART-style streaming
- **LightBlue** (iOS) — virtual peripheral mode

## Option C — Skip the phone, use M5StickC Plus2 (~30€)

If you want a real wearable form factor, the M5StickC Plus2:
- ESP32-S3 with built-in 6-axis IMU (MPU6886)
- LCD + button + battery + USB-C
- Can be flashed with MicroPython or Arduino in 15 minutes to advertise
  the same custom BLE GATT service
- Wrist-strappable

This is recommended for the final demo if you want it to look polished.

## Option D — Wear OS smartwatch (advanced)

Most Wear OS watches expose IMU and can advertise BLE. A custom Wear OS app
would let you use the watch as the gesture sensor — matches Marquardt's
HMI domain very well. Treat this as a stretch goal.
