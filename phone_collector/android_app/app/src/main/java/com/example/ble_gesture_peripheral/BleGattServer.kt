package com.example.ble_gesture_peripheral

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.UUID

/**
 * BLE GATT server that exposes a single Gesture-IMU service:
 *   service   0000ec00-b87f-490c-92cb-11ba5ea5167c
 *   char (N)  0000ec01-b87f-490c-92cb-11ba5ea5167c  - 12 bytes notify
 *
 * Lifecycle:
 *   1. start() -> open GATT server, register service, start advertising.
 *   2. central connects, writes 0x0001 to the CCC descriptor.
 *   3. sendImuFrame(...) is called by ImuStreamer at ~60 Hz; it pushes
 *      a 12-byte notify packet to every subscribed central.
 *   4. stop() -> close GATT server, stop advertising.
 *
 * Permission note: this class assumes BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT
 * are already granted; MainActivity is responsible for asking the user.
 */
class BleGattServer(
    private val context: Context,
    private val onStateChange: (String) -> Unit,
    private val onResult: (Int) -> Unit = {},   // gesture index written back by the ESP32
) {
    companion object {
        private const val TAG = "BleGattServer"
        val SERVICE_UUID: UUID     = UUID.fromString("0000ec00-b87f-490c-92cb-11ba5ea5167c")
        val IMU_CHAR_UUID: UUID    = UUID.fromString("0000ec01-b87f-490c-92cb-11ba5ea5167c")
        val RESULT_CHAR_UUID: UUID = UUID.fromString("0000ec02-b87f-490c-92cb-11ba5ea5167c")
        val CCC_UUID: UUID         = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var imuChar: BluetoothGattCharacteristic? = null
    private val subscribers = mutableSetOf<BluetoothDevice>()

    private fun hasPermission(perm: String): Boolean =
        ActivityCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (adapter == null || !adapter.isEnabled) {
            onStateChange("Bluetooth disabled")
            return false
        }
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT) ||
            !hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        ) {
            onStateChange("Missing BLE permissions")
            return false
        }

        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            onStateChange("LE advertising not supported by this device")
            return false
        }

        // Build the GATT service: one notify characteristic with a CCC descriptor.
        imuChar = BluetoothGattCharacteristic(
            IMU_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCC_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }
        // Second characteristic: the ESP32 (GATT client) writes the recognized
        // gesture index here. Write-only — this is an inbox, nothing is read out.
        val resultChar = BluetoothGattCharacteristic(
            RESULT_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val service = BluetoothGattService(
            SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY
        ).apply {
            addCharacteristic(imuChar)
            addCharacteristic(resultChar)
        }

        gattServer = bluetoothManager.openGattServer(context, gattCallback).apply {
            addService(service)
        }

        startAdvertising()
        onStateChange("Advertising")
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            // close() alone does NOT drop the BLE link, so a connected central
            // (the ESP32) would never see a disconnect and would stay stuck
            // instead of rescanning. Explicitly cancel each connection first.
            gattServer?.let { srv ->
                bluetoothManager
                    .getConnectedDevices(BluetoothProfile.GATT_SERVER)
                    .forEach { srv.cancelConnection(it) }
                srv.close()
            }
        } catch (_: SecurityException) { /* permission may have been revoked */ }
        subscribers.clear()
        advertiser = null
        gattServer = null
        imuChar = null
        onStateChange("Stopped")
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Advertising payload is tight (31 bytes). A 128-bit UUID already
        // eats 18 bytes, so we put the device name in the scan response.
        val data = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val scanResp = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        advertiser?.startAdvertising(settings, data, scanResp, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising started")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "advertising failed: $errorCode")
            onStateChange("Advertising failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "central connected: ${device.address}")
                    onStateChange("Connected: ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "central disconnected: ${device.address}")
                    subscribers.remove(device)
                    onStateChange("Disconnected, advertising again")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == CCC_UUID) {
                val enable = value.isNotEmpty() && value[0].toInt() == 0x01
                if (enable) {
                    subscribers.add(device)
                    Log.i(TAG, "${device.address} subscribed")
                    onStateChange("Subscribed: ${device.address}")
                } else {
                    subscribers.remove(device)
                    Log.i(TAG, "${device.address} unsubscribed")
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == RESULT_CHAR_UUID && value.isNotEmpty()) {
                onResult(value[0].toInt())          // gesture index 0-4 -> UI
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
                )
            }
        }
    }

    /**
     * Push one 12-byte IMU sample to every subscribed central.
     * Called by ImuStreamer at the chosen sampling rate.
     */
    @SuppressLint("MissingPermission")
    fun sendImuFrame(payload: ByteArray) {
        val char = imuChar ?: return
        val srv = gattServer ?: return
        if (subscribers.isEmpty()) return
        char.value = payload
        for (dev in subscribers.toList()) {
            try {
                srv.notifyCharacteristicChanged(dev, char, false)
            } catch (e: SecurityException) {
                Log.w(TAG, "notify failed", e)
            }
        }
    }

    fun isAdvertising(): Boolean = advertiser != null
}
