package com.example.ble_gesture_peripheral

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Reads accelerometer + gyroscope and emits a 12-byte payload whenever
 * both have a recent sample. Payload layout (matches ESP32 main.cc):
 *
 *   bytes 0-1  : ax (int16 little-endian, scaled by 1000)
 *   bytes 2-3  : ay
 *   bytes 4-5  : az
 *   bytes 6-7  : gx (int16 little-endian, scaled by 1000)
 *   bytes 8-9  : gy
 *   bytes 10-11: gz
 *
 * Scaling: accel in m/s^2 -> int16, gyro in rad/s -> int16, both * 1000
 * then clamped. With ±32.767 range that covers ±32 m/s^2 and ±32 rad/s,
 * which is plenty for hand gestures.
 */
class ImuStreamer(
    context: Context,
    private val onPayload: (
        ByteArray,
        ax: Float, ay: Float, az: Float,
        gx: Float, gy: Float, gz: Float,
    ) -> Unit,
) {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor?  = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile private var ax = 0f
    @Volatile private var ay = 0f
    @Volatile private var az = 0f
    @Volatile private var gx = 0f
    @Volatile private var gy = 0f
    @Volatile private var gz = 0f
    @Volatile private var lastEmitNs = 0L

    /** Target sample interval. 16_000_000 ns = ~62 Hz, matching Pixel 7a. */
    private val intervalNs = 16_000_000L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    ax = event.values[0]
                    ay = event.values[1]
                    az = event.values[2]
                    maybeEmit(event.timestamp)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    gx = event.values[0]
                    gy = event.values[1]
                    gz = event.values[2]
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun maybeEmit(nowNs: Long) {
        if (nowNs - lastEmitNs < intervalNs) return
        lastEmitNs = nowNs

        val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(scale(ax))
        buf.putShort(scale(ay))
        buf.putShort(scale(az))
        buf.putShort(scale(gx))
        buf.putShort(scale(gy))
        buf.putShort(scale(gz))
        onPayload(buf.array(), ax, ay, az, gx, gy, gz)
    }

    private fun scale(v: Float): Short {
        val s = (v * 1000f).roundToInt()
        return s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    fun start(): Boolean {
        if (accel == null || gyro == null) return false
        // SENSOR_DELAY_GAME ~= 20 ms (50 Hz); FASTEST may give 200-400 Hz
        // but we throttle ourselves in maybeEmit() so we don't flood BLE.
        sensorManager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(listener, gyro,  SensorManager.SENSOR_DELAY_GAME)
        return true
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }
}
