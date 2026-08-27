package com.roastcurve.shared

import com.roastcurve.shared.protocol.ModbusException
import com.roastcurve.shared.protocol.ModbusRtu
import kotlin.test.*

/**
 * MODBUS RTU 协议层测试（透传模式）
 * CRC16 标准测试向量：帧 [01 03 00 00 00 03] → CRC = 0xCB05（传输低字节在前）
 */
class ModbusRtuTest {

    @Test
    fun `crc16 standard vector`() {
        val frame = byteArrayOf(0x01, 0x03, 0x00, 0x00, 0x00, 0x03)
        assertEquals(0xCB05, ModbusRtu.crc16(frame))
    }

    @Test
    fun `build read request matches standard wire format`() {
        val req = ModbusRtu.buildReadRequest(
            slaveId = 1,
            functionCode = ModbusRtu.FUNCTION_READ_HOLDING,
            startAddress = 0x0000,
            quantity = 3,
        )
        val expected = byteArrayOf(
            0x01, 0x03, 0x00, 0x00, 0x00, 0x03, 0x05, 0xCB.toByte()
        )
        assertContentEquals(expected, req)
    }

    @Test
    fun `parse read response returns register values`() {
        val request = ModbusRtu.buildReadRequest(1, 0x03, 0x0000, 3)
        // 响应数据段：从站1, FC03, 字节数6, 数据[227, 0, 28]
        val data = byteArrayOf(0x01, 0x03, 0x06, 0x00, 0xE3.toByte(), 0x00, 0x00, 0x00, 0x1C)
        val crc = ModbusRtu.crc16(data)
        val response = data + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
        val values = ModbusRtu.parseReadResponse(request, response)
        assertEquals(listOf(227, 0, 28), values)
    }

    @Test
    fun `crc mismatch should throw`() {
        val request = ModbusRtu.buildReadRequest(1, 0x03, 0x0000, 3)
        val data = byteArrayOf(0x01, 0x03, 0x06, 0x00, 0xE3.toByte(), 0x00, 0x00, 0x00, 0x1C)
        // 故意塞错误 CRC
        val response = data + byteArrayOf(0x00, 0x00)
        assertFailsWith<ModbusException> { ModbusRtu.parseReadResponse(request, response) }
    }

    @Test
    fun `exception response should throw with reason`() {
        val request = ModbusRtu.buildReadRequest(1, 0x03, 0xFFFF, 1)
        // 非法地址异常: fc|0x80=83, 异常码02
        val data = byteArrayOf(0x01, 0x83.toByte(), 0x02)
        val crc = ModbusRtu.crc16(data)
        val response = data + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
        try {
            ModbusRtu.parseReadResponse(request, response)
            fail("should throw")
        } catch (e: ModbusException) {
            assertTrue(e.message!!.contains("illegal data address"))
        }
    }

    @Test
    fun `build write single register includes crc`() {
        val req = ModbusRtu.buildWriteSingleRegister(slaveId = 1, address = 0x0002, value = 220)
        assertEquals(8, req.size)
        assertEquals(1, req[0].toInt())
        assertEquals(0x06, req[1].toInt())
        assertEquals(0x0002, ((req[2].toInt() and 0xFF) shl 8) or (req[3].toInt() and 0xFF))
        assertEquals(220, ((req[4].toInt() and 0xFF) shl 8) or (req[5].toInt() and 0xFF))
        // 帧尾 CRC 校验通过
        val crc = ModbusRtu.crc16(req, 0, 6)
        val lo = req[6].toInt() and 0xFF
        val hi = req[7].toInt() and 0xFF
        assertEquals(crc and 0xFF, lo)
        assertEquals((crc shr 8) and 0xFF, hi)
    }
}
