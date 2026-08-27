package com.roastcurve.shared.protocol

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 字节传输层抽象：透传模式下，桥接器/模块只做字节透明转发，
 * 上层（TransparentChannel）用它对收发 Modbus RTU 裸帧。
 * TCP 套接字、BLE GATT 都实现此接口。
 */
interface ByteTransport {
    suspend fun open()
    suspend fun close()

    /** 发送字节 */
    suspend fun write(bytes: ByteArray)

    /** 读指定字节数；超时/断开返回 null（不抛取消类异常） */
    suspend fun readExact(count: Int): ByteArray?

    /** 清空接收缓冲（丢弃迟到应答的残留字节，防毒化后续事务） */
    suspend fun drain()
}

/**
 * TCP 透传外壳：TCP 连接 + 字节流收发。
 * 适配「TCP Server 透传」型桥接器（TCP 收到字节原样转 RS485）。
 */
class TcpByteTransport(
    private val host: String,
    private val port: Int,
    private val readTimeoutMs: Long = 2500L,
) : ByteTransport {

    private val selector = SelectorManager(Dispatchers.IO)
    private var socket: Socket? = null
    private var input: ByteReadChannel? = null
    private var output: ByteWriteChannel? = null

    override suspend fun open(): Unit = withContext(Dispatchers.IO) {
        socket = aSocket(selector).tcp().connect(host, port) {
            keepAlive = true
            noDelay = true
        }
        input = socket!!.openReadChannel()
        output = socket!!.openWriteChannel(autoFlush = true)
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
    }

    override suspend fun write(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val out = output ?: throw ModbusConnectionException("transport not open")
        out.writeFully(bytes, 0, bytes.size)
        out.flush()
    }

    override suspend fun readExact(count: Int): ByteArray? = withContext(Dispatchers.IO) {
        val channel = input ?: return@withContext null
        withTimeoutOrNull(readTimeoutMs) {
            val buffer = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val n = channel.readAvailable(buffer, filled, count - filled)
                if (n == -1L.toInt()) return@withTimeoutOrNull null
                filled += n
            }
            buffer
        }
    }

    override suspend fun drain(): Unit = withContext(Dispatchers.IO) {
        val channel = input ?: return@withContext
        val junk = ByteArray(256)
        while (channel.availableForRead > 0) {
            val n = channel.readAvailable(junk, 0, minOf(junk.size, channel.availableForRead))
            if (n == null || n <= 0) break
        }
    }
}

/** 平台创建 BLE 透传传输（Android 实现；iOS 待接入）
 * @param subscribeNotifications 是否订阅通知（透传需要；配网只需写凭据可关闭，避开部分手机 CCCD 订阅后写入的兼容问题） */
expect fun createBleTransport(deviceAddress: String, subscribeNotifications: Boolean = true): ByteTransport
