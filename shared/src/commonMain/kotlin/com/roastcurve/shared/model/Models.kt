package com.roastcurve.shared.model

import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

/**
 * 单个温度采样点
 */
@Serializable
data class CurvePoint(
    val timeSeconds: Float,       // 从 CHARGE 开始的时间（秒）
    val bt: Float,                // 豆温 Bean Temperature (°C)
    val et: Float? = null,        // 炉温 Environment Temperature (°C)，单探头时为 null
    val ror: Float = 0f,          // 升温速率 Rate of Rise (°C/min)
    val heaterDuty: Float? = null, // 加热占空比 (%)
    val fanDuty: Float? = null,   // 风扇占空比 (%)
    val weight: Float? = null,    // 重量 (g)
)

/**
 * 烘焙事件
 */
@Serializable
enum class RoastEvent {
    CHARGE,    // 入豆
    DRY,       // 干燥结束（黄点）
    FCs,       // 一爆开始
    FCe,       // 一爆结束
    SCs,       // 二爆开始
    SCe,       // 二爆结束
    DROP,      // 出豆
    CUSTOM,    // 自定义标记
}

/**
 * 事件标记（带时间戳）
 */
@Serializable
data class EventMarker(
    val event: RoastEvent,
    val timeSeconds: Float,
    val label: String = "",
    val temperature: Float = 0f,
)

/**
 * 烘焙阶段
 */
@Serializable
enum class RoastPhase {
    PREHEAT,     // 预热
    DRYING,      // 干燥期（CHARGE → DRY）
    MAILLARD,    // 梅纳反应期（DRY → FCs）
    DEVELOPMENT, // 发展期（FCs → DROP）
}

/**
 * 应用设置（JSON 持久化）
 */
@Serializable
data class Settings(
    val autoFollowOnCharge: Boolean = false,   // 入豆时自动开始跟随曲线
    val autoConnectOnLaunch: Boolean = false,  // 启动时自动连接上次的桥接器
    val showSecondCrack: Boolean = false,      // 深烘模式：显示二爆/二爆止事件
    val lookaheadSec: Int = 0,                 // 跟随前瞻秒数：SV 提前参考 N 秒后的目标
    val lastBridgeHost: String = "",           // 上次成功连接的桥接器 IP（记住上次用）
    val consentVersion: Int = 0,               // 已同意的隐私政策版本（0 = 从未同意，需弹窗）
    val langBuiltin: String = "zh-CN",         // 语言：内置语言代码（zh-CN / en）
    val langPackFile: String = "",             // 语言包文件名（非空=使用导入的语言包）
    val fanAutoFloorPct: Int = 30,             // 自动风速下限%：低于流化阈值的豆子不翻滚会积热局部焦化（可调 7~60）
    val modbusPvReg: Int = 0x0000,             // 温控器 PV 寄存器地址（台泉 TC4S=0x0000，其他品牌按手册改）
    val modbusSvReg: Int = 0x0002,             // 温控器 SV 寄存器地址（台泉 TC4S=0x0002）
    val modbusBaud: Int = 1200,                // 温控器通讯波特率（桥接器 RS485 侧）
    val modbusSlaveId: Int = 1,                // Modbus 从站地址（1~247）
)

/**
 * 烘焙模板：从历史记录提取的目标曲线，供「跟随曲线」自动模式回放
 * points 只使用 timeSeconds 与 bt 字段
 */
/**
 * 自定义模板锚点
 *
 * @param label 锚点名（如 脱水/一爆），空则预览回退序号显示
 */
@Serializable
data class AnchorPoint(
    val timeSeconds: Float,
    val bt: Float,
    val label: String = "",
)

@Serializable
data class RoastProfile(
    val id: String = "",
    val name: String = "",
    val sourceRecordId: String = "",     // 来源记录
    val points: List<CurvePoint> = emptyList(),
    val anchors: List<AnchorPoint> = emptyList(), // 自定义模板的锚点（历史提取的模板为空）
    val fanAnchors: List<AnchorPoint> = emptyList(), // 风速曲线锚点（bt 字段存风速 0-100%，双变量烘焙用）
)

/**
 * 全量备份包：导出/导入的数据载体
 */
@Serializable
data class BackupBundle(
    val version: Int = 1,
    val records: List<RoastRecord> = emptyList(),
    val profiles: List<RoastProfile> = emptyList(),
    val settings: Settings? = null,
)
/**
 * 烘焙记录
 */
@Serializable
data class RoastRecord(
    val id: String = "",
    val beanName: String = "",           // 豆子名称
    val beanWeight: Float = 0f,          // 入豆重量 (g)
    val roastDate: Instant? = null,       // 烘焙日期
    val chargeTime: Instant? = null,      // 入豆时间
    val dropTime: Instant? = null,        // 出豆时间
    val totalTimeSeconds: Float = 0f,     // 总烘焙时间
    val curveData: List<CurvePoint> = emptyList(),  // 完整曲线数据
    val events: List<EventMarker> = emptyList(),    // 事件列表
    val backgroundProfileId: String? = null,        // 背景曲线 ID
    val notes: String = "",               // 备注
    val roastLevel: String = "",          // 烘焙度
    val machineName: String = "",         // 烘焙机名称
    val ambientTemp: Float = 0f,          // 环境温度
    val dropWeight: Float = 0f,           // 熟豆重量 (g)，0=未记录；配合 beanWeight 算失重率
)

/**
 * 烘焙会话运行态快照：进程被杀后用于恢复「记录中」状态。
 * 曲线数据本身由 RoastStore 的草稿兜底，这里只存运行时标记与时间基准。
 */
@Serializable
data class SessionState(
    val recording: Boolean = false,
    val sessionId: String? = null,
    val followMode: Boolean = false,
    val activeProfileId: String? = null,
    val startTimeSec: Float = 0f,   // 当前累计时长（作为重连后的 timer 偏移基准）
    val savedAtEpoch: Long = 0,
)

/**
 * 背景曲线（设计或导入的参考曲线）
 */
@Serializable
data class BackgroundProfile(
    val id: String = "",
    val name: String = "",               // 曲线名称
    val description: String = "",         // 描述
    val beanType: String = "",            // 适用豆种
    val targetRoastLevel: String = "",    // 目标烘焙度
    val curvePoints: List<CurvePoint> = emptyList(),
    val events: List<EventMarker> = emptyList(),
    val createdAt: Instant? = null,
    val source: ProfileSource = ProfileSource.DESIGNED,
)

/**
 * 曲线来源
 */
@Serializable
enum class ProfileSource {
    DESIGNED,     // 手绘设计
    IMPORTED,     // 导入（Artisan/Cropster 格式）
    RECORDED,     // 从实际烘焙记录生成
}

/**
 * 设备连接配置
 */
@Serializable
data class DeviceConfig(
    val id: String = "",
    val name: String = "",               // 设备名称
    val protocol: ConnectionProtocol,    // 通信协议
    val host: String = "",               // IP 地址（WiFi）
    val port: Int = 0,                   // 端口
    val path: String = "/",              // WebSocket 路径
    val bleDeviceAddress: String = "",   // BLE 设备地址
    val serialConfig: SerialConfig? = null, // 串口配置
)

@Serializable
enum class ConnectionProtocol {
    WEBSOCKET,    // Artisan WebSocket JSON
    MODBUS_TCP,   // MODBUS TCP
    MODBUS_RTU,   // MODBUS RTU（串口）
    BLE,          // 蓝牙低功耗
    SERIAL_TEXT,  // 简易文本串口
}

/**
 * 链路类型（监控页连接选择）：决定用哪个通道与温控器通信
 */
@Serializable
enum class LinkType {
    MODBUS_TCP,       // Modbus TCP 网关（MBAP 封装，Wi-Fi 转 RS485 设备 / 自制固件）
    TCP_TRANSPARENT,  // TCP 透传（RTU 裸帧）
    BLE_TRANSPARENT,  // BLE 透传（RTU 裸帧）
}

@Serializable
data class SerialConfig(
    val baudRate: Int = 115200,
    val dataBits: Int = 8,
    val stopBits: Int = 1,
    val parity: String = "NONE",
)

/**
 * 实时烘焙状态
 */
data class RoastState(
    val isRunning: Boolean = false,
    val currentPhase: RoastPhase = RoastPhase.PREHEAT,
    val currentBT: Float = 0f,
    val currentET: Float = 0f,
    val currentRoR: Float = 0f,
    val elapsedSeconds: Float = 0f,
    val developmentTimeRatio: Float = 0f, // 发展时间占比 DTR
    val curveData: List<CurvePoint> = emptyList(),
    val events: List<EventMarker> = emptyList(),
    val backgroundProfile: BackgroundProfile? = null,
    val isConnected: Boolean = false,
    val deviceName: String = "",
)