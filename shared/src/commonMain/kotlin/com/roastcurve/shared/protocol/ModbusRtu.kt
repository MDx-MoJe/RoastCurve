package com.roastcurve.shared.protocol

/**
 * MODBUS RTU 协议编解码（透传模式）
 *
 * 用于「透传」型桥接器/模块：App 发的是 RTU 裸帧（含 CRC16），
 * 桥接器只做字节透明转发到 RS485，不再有 Modbus TCP 的 MBAP 封装。
 *
 * 帧结构（请求）:
 * [从站 1B][功能码 1B][数据 N B][CRC16 2B 低字节在前]
 * 帧结构（读响应 FC03）:
 * [从站 1B][功能码 1B][字节数 1B][数据 N B][CRC16 2B]
 */
object ModbusRtu {

    const val FUNCTION_READ_HOLDING = 0x03
    const val FUNCTION_WRITE_SINGLE = 0x06

    /** 温控器寄存器表（与 Modbus TCP 模式共用同一套寄存器） */
    object Tc4s {
        const val DEFAULT_SLAVE_ID = 1
        const val PV_ADDRESS = 0x0000   // 实时豆温 PV
        const val SV_ADDRESS = 0x0002   // 设定温度 SV
    }

    /** 构造读寄存器请求（RTU 裸帧 + CRC） */
    fun buildReadRequest(
        slaveId: Int,
        functionCode: Int,
        startAddress: Int,
        quantity: Int,
    ): ByteArray = appendCrc(
        byteArrayOf(
            slaveId.toByte(),
            functionCode.toByte(),
            (startAddress shr 8).toByte(), (startAddress and 0xFF).toByte(),
            (quantity shr 8).toByte(), (quantity and 0xFF).toByte(),
        )
    )

    /** 构造写单寄存器请求（RTU 裸帧 + CRC，用于下发 SV） */
    fun buildWriteSingleRegister(
        slaveId: Int,
        address: Int,
        value: Int,
    ): ByteArray = appendCrc(
        byteArrayOf(
            slaveId.toByte(),
            FUNCTION_WRITE_SINGLE.toByte(),
            (address shr 8).toByte(), (address and 0xFF).toByte(),
            (value shr 8).toByte(), (value and 0xFF).toByte(),
        )
    )

    /**
     * 解析读响应（FC03）：校验 CRC、从站、功能码，返回寄存器 16 位无符号值列表
     * @throws ModbusException 校验失败
     */
    fun parseReadResponse(request: ByteArray, response: ByteArray): List<Int> {
        if (response.size < 5) throw ModbusException("RTU response too short: ${response.size}")

        // CRC 校验（低字节在前）
        val crcPos = response.size - 2
        val expectedCrc = ((response[crcPos + 1].toInt() and 0xFF) shl 8) or
            (response[crcPos].toInt() and 0xFF)
        val actualCrc = crc16(response, 0, crcPos)
        if (expectedCrc != actualCrc) throw ModbusException("RTU CRC mismatch")

        // 从站地址 + 功能码
        if (response[0] != request[0]) throw ModbusException("RTU slave id mismatch")
        val fn = response[1].toInt() and 0xFF
        if (fn and 0x80 != 0) {
            val code = response[2].toInt() and 0xFF
            throw ModbusException(exceptionText(code))
        }
        if (response[1] != request[1]) throw ModbusException("RTU function code mismatch")

        val byteCount = response[2].toInt() and 0xFF
        val dataStart = 3
        if (response.size < dataStart + byteCount + 2) {
            throw ModbusException("RTU incomplete response")
        }
        return (0 until byteCount step 2).map { i ->
            ((response[dataStart + i].toInt() and 0xFF) shl 8) or
                (response[dataStart + i + 1].toInt() and 0xFF)
        }
    }

    /**
     * MODBUS CRC16：多项式 0xA001（反射），初始 0xFFFF。
     * 标准测试向量：帧 [01 03 00 00 00 03] → CRC = 0xCB05（传输低字节在前）。
     */
    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if ((crc and 0x0001) != 0) (crc shr 1) xor 0xA001 else crc shr 1
            }
        }
        return crc and 0xFFFF
    }

    /** 追加 CRC16（低字节在前）到帧尾 */
    private fun appendCrc(frame: ByteArray): ByteArray {
        val crc = crc16(frame)
        return frame + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    private fun exceptionText(code: Int): String = when (code) {
        1 -> "illegal function"
        2 -> "illegal data address"
        3 -> "illegal data value"
        4 -> "slave device failure"
        else -> "unknown exception ($code)"
    }
}
