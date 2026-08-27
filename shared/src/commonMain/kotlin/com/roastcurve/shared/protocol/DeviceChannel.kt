package com.roastcurve.shared.protocol

import com.roastcurve.shared.model.CurvePoint
import com.roastcurve.shared.model.EventMarker
import com.roastcurve.shared.model.RoastEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable

/**
 * 设备通信通道抽象
 * 所有协议（WebSocket、MODBUS、BLE、串口）都实现此接口
 */
interface DeviceChannel {
    /** 设备名称 */
    val name: String

    /** 连接状态 */
    val isConnected: Boolean

    /** 实时温度数据流 */
    val temperatureFlow: Flow<CurvePoint>

    /** 当前设定温度 SV（设备支持时提供，默认空流） */
    val svFlow: Flow<Float?>
        get() = emptyFlow()

    /** 事件流（CHARGE、DROP、FCs 等） */
    val eventFlow: Flow<EventMarker>

    /** 自然时间计时（秒）：自 connect/resetTimer 起连续走动，与采样节奏无关 */
    fun elapsedSec(): Float = 0f

    /** 重置计时基准（入豆时调用）：后续采样点时间从零开始 */
    fun resetTimer() {}

    /** 连接到设备 */
    suspend fun connect()

    /** 断开连接 */
    suspend fun disconnect()

    /** 发送 CHARGE 事件 */
    suspend fun sendCharge()

    /** 发送 DROP 事件 */
    suspend fun sendDrop()

    /** 发送自定义事件 */
    suspend fun sendEvent(event: RoastEvent, label: String = "")

    /** 发送控制命令 */
    suspend fun sendCommand(command: DeviceCommand)
}

/**
 * 设备控制命令
 */
@Serializable
data class DeviceCommand(
    val type: CommandType,
    val value: Float = 0f,
    val target: String = "",
)

@Serializable
enum class CommandType {
    HEATER_DUTY,   // 加热占空比 0-100
    FAN_DUTY,      // 风扇占空比 0-100
    PID_SETPOINT,  // PID 目标温度
    PID_KP,        // PID 比例系数
    PID_KI,        // PID 积分系数
    PID_KD,        // PID 微分系数
    PID_ON,        // 开启 PID
    PID_OFF,       // 关闭 PID
    DRUM_SPEED,    // 滚筒转速
    CUSTOM,        // 自定义命令
}