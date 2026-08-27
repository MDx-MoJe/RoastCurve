package com.roastcurve.shared.protocol

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Android 实现：扫描 BLE 配网设备。
 * 关键：BluetoothLeScanner.startScan 必须在主线程调用（有 Looper），
 * 且需做权限检查与异常防护，否则会 SecurityException 闪退。
 */
actual suspend fun bleScanConfigDevices(timeoutMs: Long): List<BleConfigDevice> =
    withContext(Dispatchers.Main) {
        val context = com.roastcurve.shared.AppDirs.androidContext as? Context
            ?: return@withContext emptyList()

        // 权限检查：API 31+ 需 BLUETOOTH_SCAN，API 23-30 需 ACCESS_FINE_LOCATION
        val granted = if (Build.VERSION.SDK_INT >= 31) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return@withContext emptyList()

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return@withContext emptyList()
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) return@withContext emptyList()

        val scanner = adapter.bluetoothLeScanner ?: return@withContext emptyList()
        val results = linkedMapOf<String, BleConfigDevice>()

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

        try {
            scanner.startScan(callback)
        } catch (_: Exception) {
            return@withContext emptyList()
        }
        delay(timeoutMs)   // 挂起等待，不阻塞主线程
        runCatching { scanner.stopScan(callback) }
        results.values.toList()
    }
