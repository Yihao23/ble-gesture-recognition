# ESP32-S3 BLE Central + TFLite Micro

Zephyr application that:

1. Scans for a BLE peripheral advertising the custom Gesture-IMU GATT service
2. Connects + subscribes to the notify characteristic
3. Each 12-byte notification (`6 × int16`) goes into a ring buffer
4. Every 200 samples → znorm → int8 quantize → TFLM Invoke → print class

## Embed the trained model

Train the model in `../training/` first, then:

```bash
python ../tools/tflite_to_c.py \
       ../training/models/qat.tflite \
       src/model_data.cc
```

## Build & flash

```bash
cd ~/zephyrproject
source .venv/bin/activate && source zephyr/zephyr-env.sh
west blobs fetch hal_espressif       # first time only

west build -p always -b esp32s3_devkitc/esp32s3/procpu \
    /home/puer/codespace/ble-gesture-recognition/esp32_ble_inference
west flash
```

Monitor:
```bash
tio /dev/ttyACM0 -b 115200
```

## Status

🟡 **Skeleton**. The BLE scan + connect logic is in place; **GATT discovery
and subscribe is left as TODO** in `connected()`. The next iteration must:

1. Call `bt_gatt_discover()` for `gesture_svc_uuid`
2. On primary service found, discover the notify characteristic
3. On characteristic found, discover its CCC descriptor
4. Populate `subscribe_params.notify = on_gesture_notify` and call `bt_gatt_subscribe()`

A reference implementation can be lifted from
`~/zephyrproject/zephyr/samples/bluetooth/central_hr/` and adapted from HR
service UUIDs to ours.

## Notes on Zephyr ESP32-S3 + BLE

- `west blobs fetch hal_espressif` is required to pull the BLE radio blob.
- `CONFIG_BT_RX_STACK_SIZE` may need bumping if you see RX stack overflows
  at sustained ≥100 Hz notification rates.
- The BLE callback runs in cooperative thread context — keep it lean
  (this file already moves heavy work to `inference_thread`).
