package com.example.ble_gesture_peripheral

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.ble_gesture_peripheral.ui.theme.BlegestureperipheralTheme
import kotlin.math.abs

// X/Y/Z axis colors (red / green / blue convention, slightly muted).
private val AxisX = Color(0xFFE5534B)
private val AxisY = Color(0xFF3FB950)
private val AxisZ = Color(0xFF539BF5)

class MainActivity : ComponentActivity() {

    private lateinit var ble: BleGattServer
    private lateinit var imu: ImuStreamer

    // Latest values used by the UI (volatile because written by the sensor thread).
    @Volatile private var status: String = "Idle"
    @Volatile private var ax = 0f
    @Volatile private var ay = 0f
    @Volatile private var az = 0f
    @Volatile private var gx = 0f
    @Volatile private var gy = 0f
    @Volatile private var gz = 0f
    @Volatile private var notifyCount: Long = 0
    @Volatile private var streaming = false
    @Volatile private var detected: String = "—"

    // Label order must match training preprocess.py and the ESP32 firmware.
    private val gestureLabels = listOf("idle", "single_tap", "double_tap", "shake", "rotate")

    private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN,
        )
    } else {
        emptyArray()
    }

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val ok = result.values.all { it }
        status = if (ok) "Permissions granted, ready" else "Permissions denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ble = BleGattServer(this, { st -> status = st }, { idx ->
            detected = gestureLabels.getOrElse(idx) { "?" }
        })
        imu = ImuStreamer(this) { payload, axv, ayv, azv, gxv, gyv, gzv ->
            ax = axv; ay = ayv; az = azv
            gx = gxv; gy = gyv; gz = gzv
            notifyCount++
            ble.sendImuFrame(payload)
        }

        // Ask once on first launch; user must accept before Start works.
        if (permissions.isNotEmpty() && permissions.any {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
            requestPerms.launch(permissions)
        }

        setContent {
            BlegestureperipheralTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Mirror the volatile fields into Compose state via a poll loop.
                    var statusUi by remember { mutableStateOf("Idle") }
                    var counterUi by remember { mutableLongStateOf(0L) }
                    var rateUi by remember { mutableLongStateOf(0L) }
                    var streamingUi by remember { mutableStateOf(false) }
                    var axUi by remember { mutableFloatStateOf(0f) }
                    var ayUi by remember { mutableFloatStateOf(0f) }
                    var azUi by remember { mutableFloatStateOf(0f) }
                    var gxUi by remember { mutableFloatStateOf(0f) }
                    var gyUi by remember { mutableFloatStateOf(0f) }
                    var gzUi by remember { mutableFloatStateOf(0f) }
                    var detectedUi by remember { mutableStateOf("—") }

                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        var lastCount = notifyCount
                        while (true) {
                            kotlinx.coroutines.delay(200)
                            // 200 ms window -> multiply by 5 to get frames/sec.
                            rateUi = (notifyCount - lastCount) * 5
                            lastCount = notifyCount
                            statusUi = status
                            counterUi = notifyCount
                            streamingUi = streaming
                            axUi = ax; ayUi = ay; azUi = az
                            gxUi = gx; gyUi = gy; gzUi = gz
                            detectedUi = detected
                        }
                    }

                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            "BLE Gesture Peripheral",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )

                        StatusCard(statusUi, streamingUi, rateUi, counterUi)

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "Detected gesture (from ESP32)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    detectedUi,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        SensorCard(
                            title = "Accelerometer",
                            unit = "m/s²",
                            maxAbs = 20f,
                            values = floatArrayOf(axUi, ayUi, azUi),
                        )

                        SensorCard(
                            title = "Gyroscope",
                            unit = "rad/s",
                            maxAbs = 10f,
                            values = floatArrayOf(gxUi, gyUi, gzUi),
                        )

                        Spacer(Modifier.height(4.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    if (ensurePermissions()) {
                                        // Open GATT server / advertising once; later
                                        // presses just resume sampling so the BLE link
                                        // to the ESP32 stays alive.
                                        if (ble.isAdvertising() || ble.start()) {
                                            imu.start()
                                            streaming = true
                                            status = "Streaming"
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Start") }

                            OutlinedButton(
                                onClick = {
                                    // Pause sampling but keep BLE up (cancelConnection
                                    // can't reliably drop a central-initiated link).
                                    imu.stop()
                                    streaming = false
                                    status = "Paused (link kept)"
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Stop") }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        imu.stop()
        ble.stop()
    }

    private fun ensurePermissions(): Boolean {
        if (permissions.isEmpty()) return true
        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        requestPerms.launch(missing.toTypedArray())
        return false
    }
}

@Composable
private fun StatusCard(status: String, streaming: Boolean, rate: Long, total: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape),
                ) {
                    drawCircle(if (streaming) AxisY else Color(0xFF8B949E))
                }
                Spacer(Modifier.width(8.dp))
                Text(status, fontWeight = FontWeight.Medium)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Metric("Rate", "$rate Hz")
                Metric("Frames", "$total")
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
    }
}

@Composable
private fun SensorCard(title: String, unit: String, maxAbs: Float, values: FloatArray) {
    val labels = listOf("X", "Y", "Z")
    val colors = listOf(AxisX, AxisY, AxisZ)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (i in 0..2) {
                ChannelBar(labels[i], values[i], maxAbs, colors[i])
            }
        }
    }
}

@Composable
private fun ChannelBar(label: String, value: Float, maxAbs: Float, color: Color) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.width(18.dp),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        // Bipolar bar: fills left for negative, right for positive, from center.
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(16.dp),
        ) {
            val w = size.width
            val h = size.height
            val center = w / 2f
            val radius = CornerRadius(h / 2f, h / 2f)
            // track
            drawRoundRect(
                color = trackColor,
                size = Size(w, h),
                cornerRadius = radius,
            )
            // center tick
            drawLine(
                color = tickColor,
                start = Offset(center, 0f),
                end = Offset(center, h),
                strokeWidth = 2f,
            )
            // value bar
            val frac = (value / maxAbs).coerceIn(-1f, 1f)
            val barW = abs(frac) * center
            if (barW > 0.5f) {
                val left = if (frac >= 0f) center else center - barW
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, 0f),
                    size = Size(barW, h),
                    cornerRadius = radius,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "%+.2f".format(value),
            modifier = Modifier.width(64.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}
