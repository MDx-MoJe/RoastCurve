package com.roastcurve.shared.protocol

/**
 * 蓝牙配网：给 ESP32 桥接器设置 WiFi 凭据（App 连板子 BLE，发「SSID\n密码」）。
 * 复用现有 BLE 透传（Nordic UART Service）的通道，无需切 WiFi。
 */

/** 扫描到的可配网 BLE 设备 */
data class BleConfigDevice(val name: String, val address: String)

/** 扫描板子广播的 BLE 配网设备（板子设备名 RoastBridge / NUS 服务） */
expect suspend fun bleScanConfigDevices(timeoutMs: Long): List<BleConfigDevice>

/** 通过 BLE 发送 WiFi 凭据（SSID\n密码），板子收到后保存并重启连接 */
suspend fun bleConfigure(address: String, ssid: String, pass: String): Boolean {
    val transport = createBleTransport(address)
    return try {
        transport.open()
        transport.write("$ssid\n$pass".encodeToByteArray())
        transport.close()
        true
    } catch (_: Exception) {
        false
    }
}
