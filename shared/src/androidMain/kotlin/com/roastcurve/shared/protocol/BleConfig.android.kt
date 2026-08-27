package com.roastcurve.shared.protocol

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android 实现：扫描 BLE 配网设备。
 * 过滤条件：设备名含「RoastBridge」，或广播了 NUS 服务（6E400001…）。
 */
actual suspend fun bleScanConfigDevices(timeoutMs: Long): List<BleConfigDevice> =
    withContext(Dispatchers.IO) {
        val context = com.roastcurve.shared.AppDirs.androidContext as? Context
            ?: return@withContext emptyList()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return@withContext emptyList()
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) return@withContext emptyList()

        val scanner = adapter.bluetoothLeScanner ?: return@withContext emptyList()
        val results = linkedMapOf<String, BleConfigDevice>()
        val done = CompletableDeferred<Unit>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName
                    ?: result.device?.name
                    ?: "未知设备"
                val hasNus = result.scanRecord?.serviceUuids?.any {
                    it.uuid.toString().uppercase().contains("6E400001")
                } == true
                if (name.contains("RoastBridge", ignoreCase = true) || hasNus) {
                    result.device?.address?.let { addr ->
                        results[addr] = BleConfigDevice(name, addr)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {}
        }

        scanner.startScan(callback)
        withTimeoutOrNull(timeoutMs) { done.await() }
        runCatching { scanner.stopScan(callback) }
        results.values.toList()
    }
