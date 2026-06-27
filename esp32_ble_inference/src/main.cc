/*
 * BLE Gesture central on ESP32-S3 — Stage 1 (link verification, no inference).
 *
 *   - Scan for a peripheral advertising 0000ec00-b87f-490c-92cb-11ba5ea5167c.
 *   - Connect, discover the service, the notify characteristic, its CCC desc.
 *   - Subscribe; print each 12-byte (6 × int16 LE) IMU frame over the console.
 *
 * Once this stage prints a steady stream from your phone, move to Stage 2
 * by re-enabling the TFLM inference (see git history of this file).
 *
 * Build:
 *   cd ~/zephyrproject && source zephyr/zephyr-env.sh
 *   west build -p always -b esp32s3_devkitc/esp32s3/procpu \
 *       /home/puer/codespace/ble-gesture-recognition/esp32_ble_inference
 *   west flash
 *   tio /dev/ttyACM0 -b 115200
 */

#include <string.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>

#include "model_data.h"
#include "tensorflow/lite/micro/micro_interpreter.h"
#include "tensorflow/lite/micro/micro_mutable_op_resolver.h"
#include "tensorflow/lite/schema/schema_generated.h"
#include <math.h>

/* === Custom GATT service the phone advertises ===========================
 * Must match BleGattServer.kt on the Android side.
 */
#define BT_UUID_GESTURE_SVC_VAL                                                \
  BT_UUID_128_ENCODE(0x0000ec00, 0xb87f, 0x490c, 0x92cb, 0x11ba5ea5167cULL)
#define BT_UUID_GESTURE_CHR_VAL                                                \
  BT_UUID_128_ENCODE(0x0000ec01, 0xb87f, 0x490c, 0x92cb, 0x11ba5ea5167cULL)
#define BT_UUID_GESTURE_RES_VAL                                                \
  BT_UUID_128_ENCODE(0x0000ec02, 0xb87f, 0x490c, 0x92cb, 0x11ba5ea5167cULL)

static struct bt_uuid_128 svc_uuid = BT_UUID_INIT_128(BT_UUID_GESTURE_SVC_VAL);
static struct bt_uuid_128 chr_uuid = BT_UUID_INIT_128(BT_UUID_GESTURE_CHR_VAL);
static struct bt_uuid_128 res_uuid = BT_UUID_INIT_128(BT_UUID_GESTURE_RES_VAL);
static struct bt_uuid_16 ccc_uuid = BT_UUID_INIT_16(BT_UUID_GATT_CCC_VAL);

/* Handle of the phone's result characteristic; we write the gesture index here. */
static uint16_t result_handle = 0;

/* === Connection / subscribe state ======================================= */
static struct bt_conn *default_conn;
static struct bt_gatt_discover_params discover_params;
static struct bt_gatt_subscribe_params subscribe_params;

/* === Stats (used by main loop for once-per-second status print) ========= */
static volatile uint32_t notifications = 0;
static volatile int16_t last_ax, last_ay, last_az, last_gx, last_gy, last_gz;

static void start_scan(void);

constexpr int kWindow = 100; // 一个窗口 100 个样本(和训练一致)
constexpr int kChannels = 6;
constexpr int kArenaSz = 24 * 1024;

static uint8_t tensor_arena[kArenaSz] __attribute__((aligned(16)));
static float window_buf[kWindow][kChannels]; // notify_cb 往这里填

static float infer_buf[kWindow][kChannels]; // 满了拷到这里去推理
static int widx = 0;
static K_SEM_DEFINE(window_ready, 0, 1); // 窗口满了的信号

// TFLM 对象(在 main 里初始化后,推理循环用)
static tflite::MicroInterpreter *g_interp = nullptr;
static TfLiteTensor *g_input = nullptr;
static TfLiteTensor *g_output = nullptr;

static const char *kLabels[] = {"idle", "single_tap", "double_tap", "shake",
                                "rotate"};
static constexpr int kNumClasses = 5;
static float kEnergyThresh = 400.0f; // 归一化后能量阈值,低于这个值的窗口不推理
static float kConfThresh = 0.7f;   // 置信度阈值,低于这个值的推理结果不输出

static float znorm(float buf[kWindow][kChannels]) {
  float energy = 0;
  for (int c = 0; c < kChannels; ++c) {
    float mean = 0, var = 0;
    for (int i = 0; i < kWindow; ++i)
      mean += buf[i][c];
    mean /= kWindow;
    for (int i = 0; i < kWindow; ++i) {
      float d = buf[i][c] - mean;
      var += d * d;
    }
    float std = sqrtf(var / kWindow) + 1e-6f;
    energy += std;
    for (int i = 0; i < kWindow; ++i)
      buf[i][c] = (buf[i][c] - mean) / std;
  }
  return energy;
}

/* ------------------------------------------------------------------------
 *  Notification handler — runs in the BT RX thread, keep it short.
 * ------------------------------------------------------------------------ */
static uint8_t notify_cb(struct bt_conn *conn,
                         struct bt_gatt_subscribe_params *params,
                         const void *data, uint16_t length) {
  if (!data) {
    printk("[unsubscribed]\n");
    params->value_handle = 0U;
    return BT_GATT_ITER_STOP;
  }
  if (length != 12) {
    printk("notify: unexpected len %u\n", length);
    return BT_GATT_ITER_CONTINUE;
  }
  const int16_t *s = (const int16_t *)data;
  for (int c = 0; c < kChannels; c++) {
    window_buf[widx][c] = (float)s[c];
  }
  widx++;
  if (widx >= kWindow) {
    memcpy(infer_buf, window_buf, sizeof(window_buf));
    widx = 0;
    k_sem_give(&window_ready);
  }
  notifications++;
  return BT_GATT_ITER_CONTINUE;
}

/* ------------------------------------------------------------------------
 *  GATT discovery — 3 stages: service -> characteristic -> CCC.
 * ------------------------------------------------------------------------ */
static uint8_t discover_cb(struct bt_conn *conn,
                           const struct bt_gatt_attr *attr,
                           struct bt_gatt_discover_params *params) {
  if (!attr) {
    printk("Discover complete; no result for stage %d\n", params->type);
    memset(params, 0, sizeof(*params));
    return BT_GATT_ITER_STOP;
  }

  int err;
  if (!bt_uuid_cmp(discover_params.uuid, &svc_uuid.uuid)) {
    /* Service found — now look for the notify characteristic. */
    printk("Service found at handle %u, discovering characteristic...\n",
           attr->handle);
    discover_params.uuid = &chr_uuid.uuid;
    discover_params.start_handle = attr->handle + 1;
    discover_params.type = BT_GATT_DISCOVER_CHARACTERISTIC;
    err = bt_gatt_discover(conn, &discover_params);
    if (err) {
      printk("char discover failed (err %d)\n", err);
    }
    return BT_GATT_ITER_STOP;
  }
  if (!bt_uuid_cmp(discover_params.uuid, &chr_uuid.uuid)) {
    /* Characteristic found — value handle is attr->handle + 1.
     * Now find the CCC descriptor (UUID 0x2902). */
    printk("Characteristic found at handle %u, discovering CCC...\n",
           attr->handle);
    discover_params.uuid = &ccc_uuid.uuid;
    discover_params.start_handle = attr->handle + 2;
    discover_params.type = BT_GATT_DISCOVER_DESCRIPTOR;
    subscribe_params.value_handle = bt_gatt_attr_value_handle(attr);
    err = bt_gatt_discover(conn, &discover_params);
    if (err) {
      printk("descriptor discover failed (err %d)\n", err);
    }
    return BT_GATT_ITER_STOP;
  }
  if (!bt_uuid_cmp(discover_params.uuid, &ccc_uuid.uuid)) {
    /* CCC found — subscribe. */
    printk("CCC found at handle %u, subscribing...\n", attr->handle);
    subscribe_params.notify = notify_cb;
    subscribe_params.value = BT_GATT_CCC_NOTIFY;
    subscribe_params.ccc_handle = attr->handle;

    err = bt_gatt_subscribe(conn, &subscribe_params);
    if (err && err != -EALREADY) {
      printk("subscribe failed (err %d)\n", err);
    } else {
      printk("[SUBSCRIBED] expecting 12-byte notifications now\n");
    }

    /* Now discover the result characteristic so we can write predictions back. */
    discover_params.uuid = &res_uuid.uuid;
    discover_params.start_handle = BT_ATT_FIRST_ATTRIBUTE_HANDLE;
    discover_params.end_handle = BT_ATT_LAST_ATTRIBUTE_HANDLE;
    discover_params.type = BT_GATT_DISCOVER_CHARACTERISTIC;
    err = bt_gatt_discover(conn, &discover_params);
    if (err) {
      printk("result char discover failed (err %d)\n", err);
    }
    return BT_GATT_ITER_STOP;
  }
  if (!bt_uuid_cmp(discover_params.uuid, &res_uuid.uuid)) {
    /* Result characteristic found — store its value handle for writing. */
    result_handle = bt_gatt_attr_value_handle(attr);
    printk("Result char found, value handle %u — will write predictions back\n",
           result_handle);
    return BT_GATT_ITER_STOP;
  }
  return BT_GATT_ITER_STOP;
}

/* ------------------------------------------------------------------------
 *  Connection lifecycle.
 * ------------------------------------------------------------------------ */
static void connected(struct bt_conn *conn, uint8_t err) {
  char addr[BT_ADDR_LE_STR_LEN];
  bt_addr_le_to_str(bt_conn_get_dst(conn), addr, sizeof(addr));

  if (err) {
    printk("Failed to connect to %s (%u)\n", addr, err);
    bt_conn_unref(default_conn);
    default_conn = NULL;
    start_scan();
    return;
  }
  printk("Connected: %s — starting GATT discovery\n", addr);

  discover_params.uuid = &svc_uuid.uuid;
  discover_params.func = discover_cb;
  discover_params.start_handle = BT_ATT_FIRST_ATTRIBUTE_HANDLE;
  discover_params.end_handle = BT_ATT_LAST_ATTRIBUTE_HANDLE;
  discover_params.type = BT_GATT_DISCOVER_PRIMARY;
  int e = bt_gatt_discover(conn, &discover_params);
  if (e) {
    printk("Discover failed (err %d)\n", e);
  }
}

static void disconnected(struct bt_conn *conn, uint8_t reason) {
  char addr[BT_ADDR_LE_STR_LEN];
  bt_addr_le_to_str(bt_conn_get_dst(conn), addr, sizeof(addr));
  printk("Disconnected: %s (reason 0x%02x)\n", addr, reason);

  if (default_conn) {
    bt_conn_unref(default_conn);
    default_conn = NULL;
  }
  memset(&subscribe_params, 0, sizeof(subscribe_params));
  result_handle = 0;
  start_scan();
}

BT_CONN_CB_DEFINE(conn_callbacks) = {
    .connected = connected,
    .disconnected = disconnected,
};

/* ------------------------------------------------------------------------
 *  Scan: connect to first device advertising our service UUID.
 * ------------------------------------------------------------------------ */
static bool ad_has_our_uuid(struct net_buf_simple *ad) {
  while (ad->len > 1) {
    uint8_t len = net_buf_simple_pull_u8(ad);
    if (len == 0 || len > ad->len) {
      return false;
    }
    uint8_t type = net_buf_simple_pull_u8(ad);
    if ((type == BT_DATA_UUID128_ALL || type == BT_DATA_UUID128_SOME) &&
        (len - 1) >= 16) {
      if (memcmp(ad->data, svc_uuid.val, 16) == 0) {
        return true;
      }
    }
    net_buf_simple_pull(ad, len - 1);
  }
  return false;
}

static void device_found(const bt_addr_le_t *addr, int8_t rssi, uint8_t type,
                         struct net_buf_simple *ad) {
  if (default_conn) {
    return;
  }
  if (type != BT_GAP_ADV_TYPE_ADV_IND &&
      type != BT_GAP_ADV_TYPE_ADV_DIRECT_IND) {
    return;
  }
  if (!ad_has_our_uuid(ad)) {
    return;
  }
  char addr_str[BT_ADDR_LE_STR_LEN];
  bt_addr_le_to_str(addr, addr_str, sizeof(addr_str));
  printk("Found gesture peripheral %s (rssi %d), connecting...\n", addr_str,
         rssi);

  int err = bt_le_scan_stop();
  if (err) {
    printk("Scan stop failed (err %d)\n", err);
    return;
  }

  err = bt_conn_le_create(addr, BT_CONN_LE_CREATE_CONN,
                          BT_LE_CONN_PARAM_DEFAULT, &default_conn);
  if (err) {
    printk("Create conn failed (err %d), rescanning\n", err);
    start_scan();
  }
}

static void start_scan(void) {
  int err = bt_le_scan_start(BT_LE_SCAN_ACTIVE, device_found);
  if (err) {
    printk("Scan start failed (err %d)\n", err);
    return;
  }
  printk("Scanning for gesture peripheral...\n");
}

/* ------------------------------------------------------------------------
 *  main: bring up BLE, kick off scanning, periodic status print.
 * ------------------------------------------------------------------------ */
int main(void) {
  printk("=== BLE Gesture Central (Stage 1: link verification) ===\n");

  int err = bt_enable(NULL);
  if (err) {
    printk("bt_enable failed (err %d)\n", err);
    return -1;
  }
  printk("Bluetooth initialised\n");

  // --- TFLM 初始化 ---
  const tflite::Model *model = tflite::GetModel(g_model_data);
  if (model->version() != TFLITE_SCHEMA_VERSION) {
    printk("ERROR: schema mismatch\n");
    return -1;
  }

  static tflite::MicroMutableOpResolver<7> resolver; // 7 个算子
  resolver.AddConv2D();
  resolver.AddExpandDims();
  resolver.AddFullyConnected();
  resolver.AddMaxPool2D();
  resolver.AddMean();
  resolver.AddReshape();
  resolver.AddSoftmax();
  static tflite::MicroInterpreter interp(model, resolver, tensor_arena,
                                         kArenaSz);

  if (interp.AllocateTensors() != kTfLiteOk) {
    printk("ERROR: AllocateTensors failed\n");
    return -1;
  }

  g_interp = &interp;
  g_input = interp.input(0);
  g_output = interp.output(0);

  const float out_scale = g_output->params.scale; // 自动读 = 0.00392157
  const int out_zp = g_output->params.zero_point;

  const float in_scale = g_input->params.scale; // 自动读 = 0.0714564
  const int in_zp = g_input->params.zero_point;
  printk("TFLM ready, in_scale=%.6f zp=%d\n", (double)in_scale, in_zp);

  start_scan();

  uint32_t last_count = 0;
  while (1) {
    k_sem_take(&window_ready, K_FOREVER); // 等窗口满
    float energy = znorm(infer_buf);

    if (energy < kEnergyThresh) {
      printk("idle          energy=%.0f\n", (double)energy);
      continue;
    }

    int8_t *q = g_input->data.int8; // 量化:float -> int8

    for (int i = 0; i < kWindow; ++i)
      for (int c = 0; c < kChannels; ++c) {
        int v = (int)roundf(infer_buf[i][c] / in_scale) + in_zp;
        if (v < -128)
          v = -128;
        if (v > 127)
          v = 127;
        *q++ = (int8_t)v;
      }

    if (g_interp->Invoke() != kTfLiteOk) {
      printk("Invoke failed\n");
      continue;
    }
    int best = 0; // argmax
    for (int i = 1; i < kNumClasses; ++i)
      if (g_output->data.int8[i] > g_output->data.int8[best])
        best = i;
    float prob = (g_output->data.int8[best] - out_zp) * out_scale;

    if (prob < kConfThresh) {
      printk("uncertain      %-12s p=%.2f energy=%.0f\n", kLabels[best],
             (double)prob, (double)energy);
      continue;
    }
    printk("gesture = %-12s p=%.2f energy=%.0f\n", kLabels[best], (double)prob,
           (double)energy);

    /* Send the recognized gesture index back to the phone (1 byte). */
    if (result_handle && default_conn) {
      uint8_t idx = (uint8_t)best;
      bt_gatt_write_without_response(default_conn, result_handle, &idx, 1, false);
    }
  }
  return 0;
}
