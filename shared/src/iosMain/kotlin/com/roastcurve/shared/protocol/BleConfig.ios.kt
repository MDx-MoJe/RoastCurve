package com.roastcurve.shared.protocol

/** iOS 实现：蓝牙配网暂未接入（iOS 端整体开发中），返回空列表 */
actual suspend fun bleScanConfigDevices(timeoutMs: Long): List<BleConfigDevice> = emptyList()
