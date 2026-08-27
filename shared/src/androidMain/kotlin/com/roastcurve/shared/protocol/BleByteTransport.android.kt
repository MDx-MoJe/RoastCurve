package com.roastcurve.shared.protocol

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * BLE 透传外壳（Android）：实现 ByteTransport，让透传通道走低功耗蓝牙。
 *
 * 默认适配 Nordic UART Service（NUS）——最常见的 BLE 串口透传标准，
 * 大量「蓝牙转 RS485 模块」都用它。若非 NUS，构造时传入自定义 UUID。
 *
 * 注意：本实现为框架完整版，尚未实机验证（缺 BLE 模块），
 * 实机验证后可能需要按模块行为微调（MTU、写类型、通知时序）。
 */
class BleByteTransport(
    private val context: Context,
    private val deviceAddress: String,
    private val serviceUuid: UUID = NUS_SERVICE,
    private val txCharUuid: UUID = NUS_TX,     // 手机写 → 模块
    private val rxCharUuid: UUID = NUS_RX,     // 模块通知 → 手机
    private val readTimeoutMs: Long = 2500L,
    private val subscribeNotifications: Boolean = true,
) : ByteTransport {

    /** 接收缓冲：onCharacteristicChanged 收到的字节先进这里，readExact 从这里读 */
    private var received = Channel<Byte>(Channel.UNLIMITED)
    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var ready = CompletableDeferred<Unit>()
    private var writeResult = CompletableDeferred<Int>()

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else {
                // 断开：关闭接收通道让 readExact 解除阻塞
                received.close()
                if (!ready.isCompleted) {
                    ready.completeExceptionally(ModbusConnectionException("BLE 连接断开"))
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                ready.completeExceptionally(ModbusConnectionException("BLE 服务发现失败"))
                return
            }
            val service = gatt.getService(serviceUuid)
            txChar = service?.getCharacteristic(txCharUuid)
            rxChar = service?.getCharacteristic(rxCharUuid)
            if (txChar == null || rxChar == null) {
                ready.completeExceptionally(ModbusConnectionException("BLE 透传服务特征未找到"))
                return
            }
            if (subscribeNotifications) {
                gatt.setCharacteristicNotification(rxChar, true)
                val desc = rxChar?.getDescriptor(CCCD_UUID)
                if (desc != null) {
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(desc)
                    // 不在里 complete，等 onDescriptorWrite 完成（BLE GATT 操作串行，
                    // 否则后续 write 会因描述符写入未完成而失败）
                } else {
                    ready.complete(Unit)   // 无 CCCD 描述符，直接完成
                }
            } else {
                // 不订阅通知（配网场景）：连 setCharacteristicNotification 也不调，
                // 避免触发任何 GATT 操作导致后续 write 报 201（GATT_WRITE_REQUEST_BUSY）
                ready.complete(Unit)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            // CCCD 订阅写完成，连接才算真正就绪（避免写操作与描述符写冲突）
            if (!ready.isCompleted) ready.complete(Unit)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            characteristic.value?.forEach { received.trySend(it) }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            // 有响应写的真正结果：status==GATT_SUCCESS 才算板子确认收到
            if (!writeResult.isCompleted) writeResult.complete(status)
        }
    }

    override suspend fun open(): Unit = withContext(Dispatchers.IO) {
        received = Channel(Channel.UNLIMITED)
        ready = CompletableDeferred()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = manager.adapter.getRemoteDevice(deviceAddress)
        gatt = device.connectGatt(context, false, gattCallback)
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() }
            ?: throw ModbusConnectionException("BLE 连接超时（请确认模块已通电且在范围内）")
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null; txChar = null; rxChar = null
        received.close()
    }

    override suspend fun write(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val g = gatt ?: throw ModbusConnectionException("BLE 未连接")
        val c = txChar ?: throw ModbusConnectionException("BLE 写特征未就绪")
        // 有响应写（Write Request）：ESP32 板子侧 onWrite 回调只对有响应写触发，
        // 无响应写（Write Command）虽然 writeCharacteristic 返回 true 但板子收不到
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        // 默认 MTU 23，有效载荷 20 字节；凭据一包即可，仍做分块兜底
        val chunkSize = 20
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            val ok = writeChunkWithRetry(g, c, chunk, writeType)
            if (!ok) throw ModbusConnectionException("BLE 写入失败")
            offset = end
            if (offset < bytes.size) delay(15)   // 分包间隔，防栈溢出
        }
    }

    /** 写一个分块；busy(201) 是瞬时的，重试直到 onCharacteristicWrite 确认板子收到；true=成功 */
    private suspend fun writeChunkWithRetry(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        chunk: ByteArray,
        writeType: Int,
    ): Boolean {
        var attempt = 0
        while (attempt < 5) {
            writeResult = CompletableDeferred()
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, chunk, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                c.writeType = writeType
                c.value = chunk
                g.writeCharacteristic(c)
            }
            if (!ok) {
                attempt++
                delay(120)
                continue
            }
            // 写请求已发出，等 onCharacteristicWrite 的真实结果（板子确认）
            val status = withTimeoutOrNull(2000L) { writeResult.await() }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                return true
            }
            attempt++
            delay(120)
        }
        return false
    }

    override suspend fun readExact(count: Int): ByteArray? = withTimeoutOrNull(readTimeoutMs) {
        val buffer = ByteArray(count)
        for (i in 0 until count) {
            buffer[i] = received.receive()
        }
        buffer
    }

    override suspend fun drain(): Unit = withContext(Dispatchers.IO) {
        while (true) {
            val r = received.tryReceive()
            if (r.isFailure) break
        }
    }

    companion object {
        /** 连接 + 服务发现 + 订阅通知的总超时 */
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** CCCD（Client Characteristic Configuration Descriptor） */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Nordic UART Service（NUS）—— BLE 串口透传事实标准 */
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    }
}

/** Android：从 AppDirs 注入的 applicationContext 创建 BLE 透传传输 */
actual fun createBleTransport(deviceAddress: String, subscribeNotifications: Boolean): ByteTransport =
    BleByteTransport(
        com.roastcurve.shared.AppDirs.androidContext as android.content.Context,
        deviceAddress,
        subscribeNotifications = subscribeNotifications,
    )
