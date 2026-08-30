package com.roastcurve.shared.protocol

/**
 * MODBUS TCP 协议编解码（MBAP 封装）
 *
 * 实测适配：Wi-Fi 转 RS485 设备（Modbus TCP 网关模式）+ PID 温控器
 *
 * 实测确认的寄存器表（2026-08-24 真机验证）：
 * - 从站 ID = 1
 * - 实时温度 PV：保持寄存器 0x0002，FC03，uint16 大端，整数分辨率
 *   （真机对照：App 读 228 ↔ 温控器面板 228）
 *
 * 帧结构（请求）:
 * [事务ID 2B][协议ID 2B=0][长度 2B=6][从站 1B][功能码 1B][地址 2B][数量 2B]
 * 帧结构（响应）:
 * [事务ID 2B][协议ID 2B][长度 2B][从站 1B][功能码 1B][字节数 1B][数据 N B]
 */
object ModbusTcp {

    const val FUNCTION_READ_HOLDING = 0x03
    const val FUNCTION_READ_INPUT = 0x04
    const val FUNCTION_WRITE_SINGLE = 0x06

    /** 温控器实测寄存器表（2026-08-24 面板对照验证） */
    object Tc4s {
        const val DEFAULT_SLAVE_ID = 1

        /** 实时豆温 PV — 保持寄存器 0x0000（面板对照：App=31 ↔ 面板PV=31） */
        const val PV_ADDRESS = 0x0000

        /** 设定温度 SV — 保持寄存器 0x0002（FC06 可写下发） */
        const val SV_ADDRESS = 0x0002
    }

    /** 构造读寄存器请求 */
    fun buildReadRequest(
        transactionId: Int,
        slaveId: Int,
        functionCode: Int,
        startAddress: Int,
        quantity: Int,
    ): ByteArray = byteArrayOf(
        (transactionId shr 8).toByte(), (transactionId and 0xFF).toByte(),
        0, 0,                            // 协议标识符恒为 0
        0, 6,                            // 后续长度固定 6 字节
        slaveId.toByte(),
        functionCode.toByte(),
        (startAddress shr 8).toByte(), (startAddress and 0xFF).toByte(),
        (quantity shr 8).toByte(), (quantity and 0xFF).toByte(),
    )

    /** 构造写单寄存器请求（如下发 SV） */
    fun buildWriteSingleRegister(
        transactionId: Int,
        slaveId: Int,
        address: Int,
        value: Int,
    ): ByteArray = byteArrayOf(
        (transactionId shr 8).toByte(), (transactionId and 0xFF).toByte(),
        0, 0,
        0, 6,
        slaveId.toByte(),
        FUNCTION_WRITE_SINGLE.toByte(),
        (address shr 8).toByte(), (address and 0xFF).toByte(),
        (value shr 8).toByte(), (value and 0xFF).toByte(),
    )

    /**
     * 校验写单寄存器（FC06）响应
     * FC06 响应 = MBAP 头 + 回显（功能码 0x06 + 地址 2B + 值 2B），共 12 字节
     * 不能复用 parseReadResponse（那是读响应的 byteCount 布局）
     * @return true = 响应合法（事务匹配 + 功能码对 + 回显地址值一致）
     */
    fun verifyWriteResponse(request: ByteArray, response: ByteArray): Boolean {
        if (response.size < 12) return false
        if (response[0] != request[0] || response[1] != request[1]) return false
        if (response[7].toInt() and 0xFF == 0x86) return false   // 异常码
        if (response[7] != request[7]) return false               // 功能码必须 0x06
        if (response[6] != request[6]) return false               // 从站号一致
        // 回显地址与值必须与请求一致（FC06 响应是请求的回显）
        if (response[8] != request[8] || response[9] != request[9]) return false   // 地址
        if (response[10] != request[10] || response[11] != request[11]) return false // 值
        return true
    }

    /**
     * 解析读响应
     * @return 各寄存器的 16 位无符号值
     * @throws ModbusException 事务不匹配 / 异常码 / CRC 类错误（长度不足）
     */
    fun parseReadResponse(request: ByteArray, response: ByteArray): List<Int> {
        if (response.size < 9) throw ModbusException("response too short: ${response.size}")
        if (response[0] != request[0] || response[1] != request[1]) {
            throw ModbusException("transaction id mismatch")
        }
        if (response[7].toInt() and 0xFF == (request[7].toInt() and 0xFF) or 0x80) {
            val code = if (response.size >= 9) response[8].toInt() else -1
            throw ModbusException(exceptionText(code))
        }
        if (response[7] != request[7]) throw ModbusException("function code mismatch")

        val byteCount = response[8].toInt() and 0xFF
        val expectedTotal = 9 + byteCount
        if (response.size < expectedTotal) throw ModbusException("incomplete response")

        return (0 until byteCount step 2).map { i ->
            ((response[9 + i].toInt() and 0xFF) shl 8) or (response[10 + i].toInt() and 0xFF)
        }
    }

    private fun exceptionText(code: Int): String = when (code) {
        1 -> "illegal function"
        2 -> "illegal data address"
        3 -> "illegal data value"
        4 -> "slave device failure"
        else -> "unknown exception ($code)"
    }
}

class ModbusException(message: String) : Exception(message)
/** 连接阶段失败（占用/不可达等），区别于运行期协议错误 */
class ModbusConnectionException(message: String) : Exception(message)
