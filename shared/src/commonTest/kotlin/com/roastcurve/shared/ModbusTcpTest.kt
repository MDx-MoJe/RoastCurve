package com.roastcurve.shared

import com.roastcurve.shared.protocol.ModbusException
import com.roastcurve.shared.protocol.ModbusTcp
import kotlin.test.*

/**
 * MODBUS TCP 协议层测试
 * 测试向量来自 2026-08-24 真机抓包（汉枫EW-11 + 台泉TC4S）：
 * 读 PV(0x0002) 请求: 00 01 00 00 00 06 01 03 00 02 00 01
 * 温度227°C 响应:     00 01 00 00 00 05 01 03 02 00 E3
 */
class ModbusTcpTest {

    @Test
    fun `build read PV request should match real device wire format`() {
        val req = ModbusTcp.buildReadRequest(
            transactionId = 1,
            slaveId = 1,
            functionCode = ModbusTcp.FUNCTION_READ_HOLDING,
            startAddress = 0x0002,
            quantity = 1,
        )
        val expected = byteArrayOf(
            0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0x01, 0x03, 0x00, 0x02, 0x00, 0x01
        )
        assertContentEquals(expected, req)
    }

    @Test
    fun `parse real PV response of 227C`() {
        val request = ModbusTcp.buildReadRequest(1, 1, 0x03, 0x0002, 1)
        val response = byteArrayOf(
            0x00, 0x01, 0x00, 0x00, 0x00, 0x05, 0x01, 0x03, 0x02, 0x00, 0xE3.toByte()
        )
        val values = ModbusTcp.parseReadResponse(request, response)
        assertEquals(listOf(227), values)
    }

    @Test
    fun `transaction id mismatch should throw`() {
        val request = ModbusTcp.buildReadRequest(1, 1, 0x03, 0x0002, 1)
        val otherTrans = byteArrayOf(
            0x00, 0x09, 0x00, 0x00, 0x00, 0x05, 0x01, 0x03, 0x02, 0x00, 0xE3.toByte()
        )
        assertFailsWith<ModbusException> { ModbusTcp.parseReadResponse(request, otherTrans) }
    }

    @Test
    fun `exception response should throw with reason`() {
        val request = ModbusTcp.buildReadRequest(1, 1, 0x03, 0xFFFF, 1)
        // 非法地址异常: fc|0x80=83, 异常码02
        val exceptionResp = byteArrayOf(
            0x00, 0x01, 0x00, 0x00, 0x00, 0x03, 0x01, 0x83.toByte(), 0x02
        )
        try {
            ModbusTcp.parseReadResponse(request, exceptionResp)
            fail("should throw")
        } catch (e: ModbusException) {
            assertTrue(e.message!!.contains("illegal data address"))
        }
    }

    @Test
    fun `incomplete response should throw`() {
        val request = ModbusTcp.buildReadRequest(1, 1, 0x03, 0x0002, 4)
        val truncated = byteArrayOf(
            0x00, 0x01, 0x00, 0x00, 0x00, 0x08, 0x01, 0x03, 0x04, 0x00, 0x10   // 少了2字节
        )
        assertFailsWith<ModbusException> { ModbusTcp.parseReadResponse(request, truncated) }
    }

    @Test
    fun `build write single register`() {
        val req = ModbusTcp.buildWriteSingleRegister(transactionId = 5, slaveId = 1, address = 0x0001, value = 220)
        assertEquals(12, req.size)
        assertEquals(5, ((req[0].toInt() and 0xFF) shl 8) or (req[1].toInt() and 0xFF))
        assertEquals(6, req[7].toInt())
        assertEquals(220, ((req[10].toInt() and 0xFF) shl 8) or (req[11].toInt() and 0xFF))
    }
}