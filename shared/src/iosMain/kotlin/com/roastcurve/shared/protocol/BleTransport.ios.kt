package com.roastcurve.shared.protocol

/** iOS：BLE 透传待接入（需要 CoreBluetooth 实现） */
actual fun createBleTransport(deviceAddress: String, subscribeNotifications: Boolean): ByteTransport =
    throw UnsupportedOperationException("BLE 透传待 iOS 实现")
