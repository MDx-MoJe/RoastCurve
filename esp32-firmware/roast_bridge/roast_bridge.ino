/*
 * RoastCurve ESP32-S3 桥接固件 v1.5「服务端化」
 * ================================================
 * v1.3 基线：WiFi(Modbus TCP) <-> RS485(Modbus RTU) 协议网关 + /fan 风速 + BLE 配网
 * v1.5 新增（docs/固件v1.5-服务端化设计.md）：
 *   1. 温控器状态轮询器 + 缓存（SIM 模式可虚拟化，空板全链路开发）
 *   2. WebSocket 服务（8898 与 HTTP 共用端口）：状态广播 + 命令通道
 *   3. 会话仲裁：协议即身份（TCP=App 主控候选 / WS=observer），单主控锁，可接管
 *   4. 安全看门狗：无主控 30s -> 安全模式（SV=safe_sv 风机=safe_fan）
 *      -> safe_off_minutes 后二段熄火（SV=0），全参数 NVS 可配
 *   5. SIM 模拟温控器：一阶滞后虚拟锅炉，零硬件全链路演示
 *
 * 设计文档：docs/固件v1.5-服务端化设计.md（Stage 0-8 测试阶梯）
 * 设计不变量：
 *   A. 8899 Modbus TCP 网关语义与 v1.3 完全一致（App 零改动）
 *   B. RS485 总线任意时刻只有一个事务（轮询让路业务帧）
 *   C. 主控失联 = TCP 断开（App）或心跳超时（WS 主控），与"是否还有连接"无关
 *   D. 安全模式粘性：重连不自动恢复，必须显式 sv_set 退出
 *
 * 引脚/接线（与 v1.3 一致，未改动）：
 *   GPIO17 (TX) -> RS485模块 DI   GPIO18 (RX) <- RS485模块 RO
 *   GPIO2 -> 风机 PWM（R20 焊点）  GPIO48 -> WS2812  GPIO0 -> BOOT 键
 *
 * 状态灯 v1.5 扩充：
 *   绿常亮=待机  青呼吸=有客户端  红闪=WiFi 断  紫闪=配网
 *   黄闪=看门狗倒计时  红常亮=安全模式  橙闪=二段熄火倒计时
 */

#include <WiFi.h>
#include <ESPmDNS.h>
#include <HardwareSerial.h>
#include <Preferences.h>
#include <WebSocketsServer.h>
#include <ArduinoJson.h>
#include "webui_gzip.h"   // Web UI gzip 体（webui/index.html 生成）
#include <ArduinoOTA.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <driver/ledc.h>
#include <vector>
#include <functional>

// OTA 升级口令（同局域网可达）
constexpr const char* OTA_PASSWORD = "roastota";

// ==================== 版本 ====================
constexpr const char* FIRMWARE_VERSION = "1.6.2";

// ==================== 用户配置区（未改动）====================
constexpr uint16_t TCP_PORT       = 8899;   // App Modbus TCP
constexpr uint16_t HTTP_PORT      = 8898;   // /fan /status /page + WS
constexpr const char* MDNS_NAME   = "roastbridge";

constexpr int      PIN_485_TX     = 17;
constexpr int      PIN_485_RX     = 18;
constexpr uint32_t MODBUS_BAUD    = 1200;

constexpr int      PIN_FAN_PWM       = 2;
constexpr uint32_t FAN_PWM_FREQ      = 1000;
constexpr int      FAN_PWM_BITS      = 8;
constexpr uint16_t FAN_DUTY_FLOOR    = 18;
constexpr uint16_t FAN_DUTY_CEIL     = 252;

constexpr int      PIN_BOOT       = 0;
constexpr int      PIN_LED        = 48;

// BLE→AP 配网降级：长按清凭据后先 BLE（App 兼容），10 分钟无配网自动转 AP（iOS 主路径）
uint32_t apAfter = 0;

// 时序参数
constexpr uint32_t RSP_FIRST_TIMEOUT_MS = 800;
constexpr uint32_t RSP_GAP_TIMEOUT_MS   = 30;
constexpr uint16_t MBAP_HEADER_LEN      = 7;

// ==================== v1.5 看门狗参数默认值（NVS 可覆盖）====================
// 二段熄火默认开（MDx 2026-09-05 拍板）；一段温度/风速用户可自定义，UI 给足提示
constexpr uint8_t  DEF_WD_ENABLED        = 1;
constexpr uint16_t DEF_WD_GRACE_S        = 30;
constexpr uint8_t  DEF_SAFE_SV           = 70;
constexpr uint8_t  DEF_SAFE_FAN          = 30;
constexpr uint8_t  DEF_SAFE_OFF_ENABLED  = 1;
constexpr uint8_t  DEF_SAFE_OFF_MIN      = 10;
constexpr uint8_t  DEF_SIM_ENABLED       = 0;
// SIM 模拟锅炉参数
constexpr uint8_t  DEF_SIM_RAMP          = 12;   // °C/min 满功率爬温斜率
constexpr uint8_t  DEF_AMBIENT           = 25;   // 环境温度
// Modbus 通讯参数（可配：支持其他品牌温控器换寄存器表）
constexpr uint16_t DEF_REG_PV            = 0x0000; // PV 豆温寄存器（台泉 TC4S 实测值）
constexpr uint16_t DEF_REG_SV            = 0x0002; // SV 设定寄存器
constexpr uint32_t DEF_MODBUS_BAUD       = 1200;   // 自动收发模块最稳档
constexpr uint8_t  DEF_MODBUS_SLAVE      = 1;      // 从站地址

// NVS 命名空间
constexpr const char* NVS_NS = "roastbridge";
constexpr const char* NVS_NS15 = "rb15";

HardwareSerial rs485(1);
WiFiServer server(TCP_PORT);
WiFiServer statusServer(HTTP_PORT);
WiFiClient client;
Preferences nvs, nvs15;
String storedSsid, storedPass;

// 前置声明（Arduino .ino 自动原型对 enum class 参数不可靠，手动补齐）
enum class Holder : uint8_t;
enum class WdState : uint8_t;
void setHolder(Holder h);
void triggerWatchdogCancel();
void broadcastState();
void setStatusLedByWd();

// 风机风速状态：0-100（v1.3 语义：0 停机，1-100 映射 duty，下限锁死）
uint8_t fanSpeed = 0;

// ==================== v1.5 看门狗/安全配置（运行时状态，NVS 持久化）====================
struct SafeConfig {
  bool     wdEnabled;
  uint16_t graceS;
  uint8_t  safeSv;
  uint8_t  safeFan;
  bool     safeOffEnabled;
  uint8_t  safeOffMin;
  bool     simEnabled;
  uint8_t  simRamp;
  uint8_t  ambient;
  // Modbus 通讯参数（v1.6.2 可配：兼容其他品牌温控器）
  uint16_t regPv;
  uint16_t regSv;
  uint32_t baud;
  uint8_t  slaveId;
};
SafeConfig cfg = { true, DEF_WD_GRACE_S, DEF_SAFE_SV, DEF_SAFE_FAN,
                   true, DEF_SAFE_OFF_MIN, false, DEF_SIM_RAMP, DEF_AMBIENT,
                   DEF_REG_PV, DEF_REG_SV, DEF_MODBUS_BAUD, DEF_MODBUS_SLAVE };

void loadCfg() {
  nvs15.begin(NVS_NS15, false);
  cfg.wdEnabled      = nvs15.getUChar("wdEn", DEF_WD_ENABLED) != 0;
  cfg.graceS         = nvs15.getUShort("graceS", DEF_WD_GRACE_S);
  cfg.safeSv         = nvs15.getUChar("safeSv", DEF_SAFE_SV);
  cfg.safeFan        = nvs15.getUChar("safeFan", DEF_SAFE_FAN);
  cfg.safeOffEnabled = nvs15.getUChar("offEn", DEF_SAFE_OFF_ENABLED) != 0;
  cfg.safeOffMin     = nvs15.getUChar("offMin", DEF_SAFE_OFF_MIN);
  cfg.simEnabled     = nvs15.getUChar("simEn", DEF_SIM_ENABLED) != 0;
  cfg.simRamp        = nvs15.getUChar("simRamp", DEF_SIM_RAMP);
  cfg.ambient        = nvs15.getUChar("amb", DEF_AMBIENT);
  cfg.regPv          = nvs15.getUShort("regPv", DEF_REG_PV);
  cfg.regSv          = nvs15.getUShort("regSv", DEF_REG_SV);
  cfg.baud           = nvs15.getULong("baud", DEF_MODBUS_BAUD);
  cfg.slaveId        = nvs15.getUChar("slave", DEF_MODBUS_SLAVE);
  nvs15.end();
  if (cfg.graceS < 5 || cfg.graceS > 600) cfg.graceS = DEF_WD_GRACE_S;
  if (cfg.safeSv < 20 || cfg.safeSv > 150) cfg.safeSv = DEF_SAFE_SV;
  if (cfg.safeFan > 100) cfg.safeFan = DEF_SAFE_FAN;
  if (cfg.safeOffMin < 1 || cfg.safeOffMin > 120) cfg.safeOffMin = DEF_SAFE_OFF_MIN;
  if (cfg.simRamp < 1 || cfg.simRamp > 60) cfg.simRamp = DEF_SIM_RAMP;
  if (cfg.ambient < 0 || cfg.ambient > 60) cfg.ambient = DEF_AMBIENT;
}

bool saveCfg() {
  nvs15.begin(NVS_NS15, false);
  nvs15.putUChar("wdEn",   cfg.wdEnabled ? 1 : 0);
  nvs15.putUShort("graceS", cfg.graceS);
  nvs15.putUChar("safeSv",  cfg.safeSv);
  nvs15.putUChar("safeFan", cfg.safeFan);
  nvs15.putUChar("offEn",   cfg.safeOffEnabled ? 1 : 0);
  nvs15.putUChar("offMin",  cfg.safeOffMin);
  nvs15.putUChar("simEn",   cfg.simEnabled ? 1 : 0);
  nvs15.putUChar("simRamp", cfg.simRamp);
  nvs15.putUChar("amb",     cfg.ambient);
  nvs15.putUShort("regPv",  cfg.regPv);
  nvs15.putUShort("regSv",  cfg.regSv);
  nvs15.putULong("baud",    cfg.baud);
  nvs15.putUChar("slave",   cfg.slaveId);
  nvs15.end();
  return true;
}

// ==================== v1.5 温控器抽象层（真机 / SIM 双后端）====================
// 全部 Modbus 访问的唯一入口，天然满足"总线单事务"不变量 B
struct HeaterState {
  uint8_t  pv;        // 豆温（当前实际温度）
  uint8_t  sv;        // 设定温度
  bool     link;      // 温控器通信正常
  uint32_t lastPoll;  // 最后成功轮询时刻
};
HeaterState heater = { 0, 0, false, 0 };

// SIM 虚拟锅炉（一阶滞后：PV 以有限斜率逼近 SV）
struct SimBoiler {
  float pv;
  float sv;
  uint32_t lastTick;
};
SimBoiler sim = { DEF_AMBIENT, DEF_AMBIENT, 0 };

// Modbus 事务（总线队列的唯一消费者）
bool modbusTransaction(const uint8_t* pdu, size_t pduLen, uint8_t* rspPdu, size_t& rspLen) {
  if (cfg.simEnabled) { rspLen = 0; return false; }  // SIM 模式不碰总线

  uint8_t rtu[260];
  rtu[0] = cfg.slaveId;
  memcpy(rtu + 1, pdu, pduLen);
  uint16_t crc = crc16(rtu, pduLen + 1);
  rtu[pduLen + 1] = crc & 0xFF;
  rtu[pduLen + 2] = crc >> 8;

  while (rs485.available()) rs485.read();
  rs485.write(rtu, pduLen + 3);
  rs485.flush();

  uint8_t rsp[260];
  size_t n = 0;
  uint32_t start = millis();
  uint32_t lastByte = millis();
  bool gotFirst = false;
  while (millis() - start < RSP_FIRST_TIMEOUT_MS) {
    if (rs485.available()) {
      if (n >= sizeof(rsp)) break;
      rsp[n++] = rs485.read();
      gotFirst = true;
      lastByte = millis();
    } else if (gotFirst && millis() - lastByte >= RSP_GAP_TIMEOUT_MS) break;
    yield();
  }

  if (!gotFirst || n < 4) { rspLen = 0; return false; }
  uint16_t expect = crc16(rsp, n - 2);
  uint16_t actual = rsp[n - 2] | (rsp[n - 1] << 8);
  if (expect != actual || rsp[0] != rtu[0]) { rspLen = 0; return false; }
  // 异常应答（功能码最高位置位）
  if (rsp[1] & 0x80) { rspLen = 0; return false; }
  memcpy(rspPdu, rsp + 1, n - 3);
  rspLen = n - 3;
  return true;
}

// 读保持寄存器（默认台泉 TC4S：PV=0x0000 SV=0x0002；地址 NVS 可配）
bool heaterRead(uint16_t reg, uint16_t& value) {
  if (cfg.simEnabled) {
    // SIM 后端：SV 寄存器返回 sim.sv，PV 返回 sim.pv
    value = (reg == cfg.regSv) ? (uint16_t)sim.sv : (uint16_t)sim.pv;
    return true;
  }
  uint8_t pdu[4] = { 0x03, (uint8_t)(reg >> 8), (uint8_t)(reg & 0xFF), 0x00 };
  uint8_t rsp[64]; size_t rspLen = 0;
  if (!modbusTransaction(pdu, 4, rsp, rspLen) || rspLen < 3) return false;
  value = ((uint16_t)rsp[1] << 8) | rsp[2];
  return true;
}

// 写单寄存器（SV 地址走配置）
bool heaterWriteSv(uint16_t value) {
  if (cfg.simEnabled) { sim.sv = value; return true; }
  uint8_t pdu[5] = { 0x06, (uint8_t)(cfg.regSv >> 8), (uint8_t)(cfg.regSv & 0xFF), (uint8_t)(value >> 8), (uint8_t)(value & 0xFF) };
  uint8_t rsp[64]; size_t rspLen = 0;
  return modbusTransaction(pdu, 4, rsp, rspLen) && rspLen >= 2;
}

// 每秒轮询：真机走总线（让路业务帧），SIM 走虚拟锅炉积分
void heaterPoll() {
  static uint32_t lastPoll = 0;
  static uint8_t  failCount = 0;
  if (cfg.simEnabled) {
    // 一阶滞后：PV 以 simRamp(°C/min) 逼近 SV
    uint32_t now = millis();
    if (sim.lastTick == 0) sim.lastTick = now;
    float dtMin = (now - sim.lastTick) / 60000.0f;
    sim.lastTick = now;
    float delta = sim.sv - sim.pv;
    float step  = cfg.simRamp * dtMin;
    if (fabsf(delta) <= step) sim.pv = sim.sv;
    else                       sim.pv += (delta > 0 ? step : -step);
    heater.pv = (uint8_t)(sim.pv + 0.5f);
    heater.sv = (uint8_t)(sim.sv + 0.5f);
    heater.link = true;
    heater.lastPoll = now;
    return;
  }
  uint32_t now = millis();
  if (now - lastPoll < 1000) return;
  uint16_t v;
  if (heaterRead(cfg.regPv, v)) { heater.pv = v & 0xFF; lastPoll = now; failCount = 0; heater.link = true; heater.lastPoll = now; }
  else if (++failCount >= 5) heater.link = false;
  if (heaterRead(cfg.regSv, v)) heater.sv = v & 0xFF;
}

// ==================== v1.5 会话仲裁 ====================
// 协议即身份：8899 TCP = App 主控候选；8898 WS = observer（可 takeover）
// 主控 = "最后接管者"（App 连接即获得主控并顶掉 WS 主控；WS takeover 同理顶掉 App）
enum class Holder : uint8_t { NONE, APP, WEB };
Holder holder = Holder::NONE;

// 看门狗状态机
enum class WdState : uint8_t { ARMED, COUNTDOWN, SAFE_MODE, OFF_COUNTDOWN };
WdState wdState = WdState::ARMED;
uint32_t wdStateSince = 0;

// ==================== v1.6 跟随引擎（固件侧查表式跟随）====================
// 客户端把锚点曲线预采样成等间隔目标数组上传，固件逐拍查表写 SV。
//   - 跟随运行时固件是"事实控制者"：看门狗挂起，sv_set 拒绝，App 的 FC06 写 SV 回 Busy 异常
//   - 脱轨熔断：|PV - 目标| > 15°C 持续 30s → 停跟随 + 进入安全模式
//   - 曲线走完自动结束，SV 停在最后目标值
constexpr uint16_t FOLLOW_MAX_POINTS = 720;   // 5s 间隔 × 720 = 60 分钟
constexpr uint8_t  FOLLOW_INTERVAL_S = 5;
constexpr uint8_t  FOLLOW_ABORT_DIFF = 15;    // °C
constexpr uint32_t FOLLOW_ABORT_MS   = 30000;

struct FollowEngine {
  bool     on;
  uint8_t  pts[FOLLOW_MAX_POINTS];
  uint16_t count;
  uint32_t t0;
  int16_t  lastWritten;
  uint32_t abortSince;
};
FollowEngine follow = { false, {0}, 0, 0, -1, 0 };

void followStart(uint16_t count, const uint8_t* pts) {
  if (count == 0 || count > FOLLOW_MAX_POINTS) return;
  memcpy(follow.pts, pts, count);
  follow.count = count;
  follow.on = true;
  follow.t0 = millis();
  follow.lastWritten = -1;
  follow.abortSince = 0;
  // 跟随启动 = 固件接管控制，取消未决倒计时
  if (wdState == WdState::COUNTDOWN) wdState = WdState::ARMED;
  Serial.printf("[跟随] 启动 %u 点（%u 秒）\n", count, count * FOLLOW_INTERVAL_S);
}

void followStop(bool done) {
  follow.on = false;
  // 跟随结束恢复看门狗武装：若主控已失联，倒计时重新起算
  if (wdState == WdState::COUNTDOWN || wdState == WdState::OFF_COUNTDOWN) {
    wdStateSince = millis();
  }
  Serial.printf("[跟随] %s\n", done ? "曲线走完自动结束" : "手动停止");
}

void followTick() {
  if (!follow.on) return;
  uint32_t elapsed = (millis() - follow.t0) / 1000;
  uint16_t idx = elapsed / FOLLOW_INTERVAL_S;
  if (idx >= follow.count) {
    followStop(true);
    broadcastState();
    return;
  }
  uint8_t target = follow.pts[idx];
  // 最小步长过滤：与上次写入差 ≥1° 才写（省总线流量，与 App 语义一致）
  if (follow.lastWritten < 0 || abs((int)target - (int)follow.lastWritten) >= 1) {
    if (heaterWriteSv(target)) follow.lastWritten = target;
  }
  // 脱轨熔断
  int16_t diff = (int16_t)heater.pv - (int16_t)target;
  if (abs(diff) > FOLLOW_ABORT_DIFF) {
    if (follow.abortSince == 0) {
      follow.abortSince = millis();
    } else if (millis() - follow.abortSince >= FOLLOW_ABORT_MS) {
      Serial.printf("[跟随] 脱轨熔断 PV=%u 目标=%u\n", heater.pv, target);
      follow.on = false;
      // 直接进安全模式
      wdState = WdState::SAFE_MODE;
      wdStateSince = millis();
      heaterWriteSv(cfg.safeSv);
      heater.sv = cfg.safeSv;
      ledcWrite(PIN_FAN_PWM, (uint16_t)cfg.safeFan * 255 / 100);
      fanSpeed = cfg.safeFan;
      broadcastState();
      return;
    }
  } else {
    follow.abortSince = 0;
  }
  heater.sv = target;  // 广播显示当前跟随目标
}


void broadcastState();

void setHolder(Holder h) {
  if (holder == h) return;
  holder = h;
  Serial.printf("[仲裁] 主控 -> %s\n",
    h == Holder::APP ? "App" : h == Holder::WEB ? "Web" : "无");
  if (holder != Holder::NONE && wdState != WdState::ARMED) {
    // 主控恢复：取消倒计时/退出安全模式（粘性规则：SAFE_MODE 需显式 sv_set 退出）
    if (wdState == WdState::COUNTDOWN || wdState == WdState::OFF_COUNTDOWN) {
      wdState = WdState::ARMED;
      Serial.println("[看门狗] 主控恢复，倒计时取消");
    }
  }
  broadcastState();
}

// 看门狗状态机推进（每 loop 调用）
void watchdogTick() {
  if (follow.on) return;  // 跟随运行中看门狗挂起（跟随自带脱轨熔断）
  if (!cfg.wdEnabled || holder != Holder::NONE) {
    if (holder != Holder::NONE && wdState != WdState::ARMED) {
      // 主控在位，COUNTDOWN/OFF_COUNTDOWN 自动取消（SAFE_MODE 粘性，不自动退出）
      if (wdState == WdState::COUNTDOWN) { wdState = WdState::ARMED; broadcastState(); }
      // OFF_COUNTDOWN 意味着安全模式已生效，主控回来也保持在安全模式直到显式退出
    }
    return;
  }
  uint32_t now = millis();
  switch (wdState) {
    case WdState::ARMED:
      // 进入 COUNTDOWN 的时机由"主控失联"事件触发（见 TCP/WS 断开处理），这里只计时
      break;
    case WdState::COUNTDOWN:
      if (now - wdStateSince >= cfg.graceS * 1000UL) {
        wdState = WdState::SAFE_MODE;
        wdStateSince = now;
        // 一段：保温（用户可自定义 safe_sv / safe_fan）
        heaterWriteSv(cfg.safeSv);
        heater.sv = cfg.safeSv;  // 立即刷新缓存，广播帧里 sv 不滞后一拍
        ledcWrite(PIN_FAN_PWM, (uint16_t)cfg.safeFan * 255 / 100);
        fanSpeed = cfg.safeFan;
        Serial.printf("[看门狗] 进入安全模式 SV=%u 风机=%u%%\n", cfg.safeSv, cfg.safeFan);
        if (!cfg.safeOffEnabled) { wdState = WdState::ARMED; }
        broadcastState();
      }
      break;
    case WdState::SAFE_MODE:
      if (cfg.safeOffEnabled && now - wdStateSince >= cfg.safeOffMin * 60000UL) {
        wdState = WdState::OFF_COUNTDOWN;
        wdStateSince = now;
        heaterWriteSv(0);  // 二段熄火（TC4S 写 0 的行为 Stage 5 实测确认）
        heater.sv = 0;     // 立即刷新缓存
        Serial.println("[看门狗] 二段熄火 SV=0");
        broadcastState();
      }
      break;
    case WdState::OFF_COUNTDOWN:
      // 熄火已执行，此状态仅保持标志直至主控显式 sv_set 恢复
      break;
  }
}

// 启动看门狗倒计时（主控失联事件）
void triggerWatchdog() {
  if (!cfg.wdEnabled) return;
  if (holder != Holder::NONE) return;
  if (wdState == WdState::ARMED) {
    wdState = WdState::COUNTDOWN;
    wdStateSince = millis();
    Serial.printf("[看门狗] 主控失联，%us 倒计时\n", cfg.graceS);
    broadcastState();
  }
}

// ==================== WS2812 状态灯 ====================
uint32_t lastBlink = 0;
bool blinkOn = false;

void setStatusLed(int r, int g, int b, bool blink = false) {
  if (blink) {
    if (millis() - lastBlink > 400) { lastBlink = millis(); blinkOn = !blinkOn; }
    if (!blinkOn) { r = g = b = 0; }
  }
  neopixelWrite(PIN_LED, r, g, b);
}

// ==================== Modbus CRC16 ====================
uint16_t crc16(const uint8_t* buf, size_t len) {
  uint16_t crc = 0xFFFF;
  for (size_t i = 0; i < len; i++) {
    crc ^= buf[i];
    for (int j = 0; j < 8; j++) {
      if (crc & 1) { crc >>= 1; crc ^= 0xA001; }
      else crc >>= 1;
    }
  }
  return crc;
}

// ==================== TCP -> RTU：解析 MBAP 下发串口 ====================
// v1.5：App TCP 连接即获主控（协议即身份），断开即失联 -> 看门狗
bool mbapToRtu(const uint8_t* frame, size_t frameLen) {
  if (frameLen < MBAP_HEADER_LEN) return false;
  uint16_t protoId = (frame[2] << 8) | frame[3];
  uint16_t length  = (frame[4] << 8) | frame[5];
  if (protoId != 0 || frameLen < 6u + length) return false;

  uint8_t rtu[260];
  rtu[0] = frame[6];
  memcpy(rtu + 1, frame + MBAP_HEADER_LEN, length - 1);
  uint16_t crc = crc16(rtu, length);
  rtu[length]     = crc & 0xFF;
  rtu[length + 1] = crc >> 8;

  while (rs485.available()) rs485.read();
  rs485.write(rtu, length + 2);
  rs485.flush();
  return true;
}

// ==================== RTU -> TCP：收应答重新封装 MBAP ====================
void rtuToMbap(WiFiClient& out, const uint8_t* reqHeader, uint16_t waitMs) {
  uint8_t rsp[260];
  size_t n = 0;
  uint32_t start = millis();
  uint32_t lastByte = millis();
  bool gotFirst = false;
  bool crcOk = false;

  while (millis() - start < waitMs) {
    if (rs485.available()) {
      if (n >= sizeof(rsp)) break;
      rsp[n++] = rs485.read();
      gotFirst = true;
      lastByte = millis();
    } else if (gotFirst && millis() - lastByte >= RSP_GAP_TIMEOUT_MS) break;
    yield();
  }

  if (gotFirst && n >= 4) {
    uint16_t expect = crc16(rsp, n - 2);
    uint16_t actual = rsp[n - 2] | (rsp[n - 1] << 8);
    crcOk = (expect == actual);
  }

  if (!gotFirst || !crcOk) {
    uint8_t ex[9];
    ex[0] = reqHeader[0]; ex[1] = reqHeader[1];
    ex[2] = 0; ex[3] = 0;
    ex[4] = 0; ex[5] = 3;
    ex[6] = reqHeader[6];
    ex[7] = reqHeader[7] | 0x80;
    ex[8] = 0x0B;
    out.write(ex, sizeof(ex));
    return;
  }

  uint8_t mbap[MBAP_HEADER_LEN];
  mbap[0] = reqHeader[0]; mbap[1] = reqHeader[1];
  mbap[2] = 0; mbap[3] = 0;
  uint16_t mlen = 1 + (n - 3);
  mbap[4] = mlen >> 8; mbap[5] = mlen & 0xFF;
  mbap[6] = rsp[0];
  out.write(mbap, MBAP_HEADER_LEN);
  out.write(rsp + 1, n - 3);
}

// ==================== AP 配网模式（iOS 主路径，无需 App）====================
// 流程：板子发热点 RoastBridge-Setup → 手机连上 → Captive Portal 自动弹配网页
//      → 选家里 WiFi+密码提交 → 存 NVS 重启连家里网络
#include <DNSServer.h>
#include <WiFiAP.h>

DNSServer* apDns = nullptr;
const byte AP_DNS_PORT = 53;
bool apMode = false;

// 配网页（AP 模式下 192.168.4.1 提供，同样 gzip）
#include "setup_gzip.h"

void apSaveAndReboot(const String& ssid, const String& pass) {
  nvs.begin(NVS_NS, false);
  nvs.putString("ssid", ssid);
  nvs.putString("pass", pass);
  nvs.end();
  Serial.printf("[配网-AP] 已保存 SSID=%s，重启\n", ssid.c_str());
  delay(300);
  ESP.restart();
}

// AP 模式的 HTTP+DNS 处理（非阻塞，主循环调用）
WiFiServer apServer(80);
void apLoop() {
  if (!apMode) return;
  if (apDns) apDns->processNextRequest();

  WiFiClient sc = apServer.available();
  if (!sc) return;
  String reqLine = "";
  uint32_t t0 = millis(); size_t n = 0;
  while (sc.connected() && millis() - t0 < 500 && n < 512 && reqLine.indexOf('\n') < 0) {
    if (sc.available()) { char c = sc.read(); reqLine += c; n++; } else delay(1);
  }
  while (sc.connected() && millis() - t0 < 500 && n < 1024) {
    if (sc.available()) { sc.read(); n++; } else break;
  }

  // POST /save：读 body 里的 ssid/pass（简化：不解析 Content-Length，直接抓关键字段）
  if (reqLine.startsWith("POST /save")) {
    // POST body 已在第二次 while 里读掉一部分；改从原始流读完整 body
    // （上面已消费，改由 GET /scan+GET 参数方案更稳，此处用 URL 参数版）
  }

  // 扫描附近 WiFi 并返回 JSON（GET /scan）
  if (reqLine.startsWith("GET /scan")) {
    int found = WiFi.scanNetworks();
    String out = "[";
    for (int i = 0; i < found && i < 15; i++) {
      if (i) out += ",";
      out += "{\"s\":\"" + WiFi.SSID(i) + "\",\"q\":" + String(WiFi.RSSI(i)) + "}";
    }
    out += "]";
    WiFi.scanDelete();
    char head[128];
    snprintf(head, sizeof(head),
      "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %u\r\nConnection: close\r\n\r\n",
      (unsigned)out.length());
    sc.print(head); sc.print(out); sc.flush(); delay(2); sc.stop();
    return;
  }

  // 提交配网：GET /save?s=<ssid>&p=<pass>（URL 编码由网页端 encodeURIComponent）
  if (reqLine.startsWith("GET /save?s=") || reqLine.startsWith("GET /save?")) {
    int qs = reqLine.indexOf('?');
    int sp = reqLine.indexOf(' ', qs);
    String q = reqLine.substring(qs + 1, sp);
    String ssid = "", pass = "";
    // 简易解析
    int amp = q.indexOf('&');
    String kv1 = (amp > 0) ? q.substring(0, amp) : q;
    String kv2 = (amp > 0) ? q.substring(amp + 1) : "";
    auto urlDecode = [](String s) {
      String r; char a, b;
      for (unsigned int i = 0; i < s.length(); i++) {
        if (s[i] == '%' && i + 2 < s.length()) {
          a = s[i+1]; b = s[i+2];
          if (isxdigit(a) && isxdigit(b)) {
            if (a >= 'a') a -= 32; if (b >= 'a') b -= 32;
            char hex[3] = {a, b, 0};
            r += (char)strtol(hex, nullptr, 16);
            i += 2;
          } else r += s[i];
        } else if (s[i] == '+') r += ' ';
        else r += s[i];
      }
      return r;
    };
    if (kv1.startsWith("s=")) ssid = urlDecode(kv1.substring(2));
    if (kv2.startsWith("p=")) pass = urlDecode(kv2.substring(2));
    ssid.trim();
    const char* okBody = "{\"ok\":true}";
    char head[128];
    snprintf(head, sizeof(head),
      "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 12\r\nConnection: close\r\n\r\n");
    sc.print(head); sc.print(okBody); sc.flush(); delay(5); sc.stop();
    if (ssid.length() > 0 && pass.length() >= 8) {
      apSaveAndReboot(ssid, pass);
    }
    return;
  }

  // 其它任何路径（含 Captive Portal 探测）：回配网页
  sc.print("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n");
  sc.print("Content-Encoding: gzip\r\nCache-Control: no-store\r\n");
  char cl[48];
  snprintf(cl, sizeof(cl), "Content-Length: %u\r\nConnection: close\r\n\r\n", SETUP_GZIP_LEN);
  sc.print(cl); sc.flush();
  uint8_t buf[512];
  for (unsigned int off = 0; off < SETUP_GZIP_LEN; off += sizeof(buf)) {
    unsigned int n2 = SETUP_GZIP_LEN - off;
    if (n2 > sizeof(buf)) n2 = sizeof(buf);
    memcpy_P(buf, SETUP_GZIP + off, n2);
    size_t w = 0;
    while (w < n2) { w += sc.write(buf + w, n2 - w); yield(); }
    yield();
  }
  sc.flush(); delay(2); sc.stop();
}

void startApConfig() {
  Serial.println("\n[配网-AP] 启动热点 RoastBridge-Setup");
  apMode = true;
  WiFi.mode(WIFI_AP);
  WiFi.softAP("RoastBridge-Setup", "roast1234");
  IPAddress apIp = WiFi.softAPIP();  // 192.168.4.1
  apServer.begin();
  apDns = new DNSServer();
  apDns->start(AP_DNS_PORT, "*", apIp);   // 劫持所有域名 → 弹窗
  setStatusLed(160, 0, 255, true);  // 品红闪 = AP 配网
  Serial.printf("[配网-AP] 热点就绪 http://%s 密码 roast1234\n", apIp.toString().c_str());
}

void stopApConfig() {
  if (apDns) { apDns->stop(); delete apDns; apDns = nullptr; }
  apServer.stop();
  WiFi.softAPdisconnect(true);
  apMode = false;
}

static const BLEUUID NUS_SERVICE_UUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
static const BLEUUID NUS_TX_UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
static const BLEUUID NUS_RX_UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
String bleCredBuffer;
volatile bool bleCredReady = false;

class BLEConfigCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* ch) {
    String val = ch->getValue();
    for (unsigned int i = 0; i < val.length(); i++) {
      char c = val[i];
      if (c != '\r') bleCredBuffer += c;
    }
  }
};

// ---------- 前向声明 ----------
void handleStatus();

// ---------- WS2812（状态灯函数定义顺序修正：crc16 之前已声明，这里仅实现）----------

// ==================== WebSocket 服务 ====================
// 注：HTTP 与 WS 共用同一端口需单 accept 分流，Arduino 生态无现成方案。
// v1.5 采用独立 WS 端口 8897；8898 保留 HTTP（/status /fan /reset）。
WebSocketsServer ws(8897);

// 诊断计数器（/status 可读）：定位 WS 命令通道问题用
uint32_t diagWsEvents = 0;   // wsEvent 总触发数
uint32_t diagWsText = 0;     // TEXT 帧数
uint32_t diagWsConn = 0;     // CONNECTED 数
char diagLastType[12] = "-"; // 最近一条 TEXT 的 type 字段

// ==================== 事件日志（本次会话，内存环形）====================
// 与 App 的 RoastEvent 枚举对齐：CHARGE/DRY/FCs/FCe/SCs/SCe/DROP
constexpr uint8_t EV_MAX = 24;
struct EvRecord { char ev[8]; uint32_t t; uint8_t pv; };
EvRecord evLog[EV_MAX];
uint16_t evCount = 0;

// WS 客户端角色：全部 observer 候选，编号即身份
bool wsHolder = false;  // 当前主控是否为 WS 客户端
uint8_t wsHolderNum = 0;

void wsEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t len) {
  diagWsEvents++;
  switch (type) {
    case WStype_CONNECTED:
      diagWsConn++;
      Serial.printf("[WS] 客户端 #%u 接入 %s\n", num, ws.remoteIP(num).toString().c_str());
      // 新连接：发 hello+state 快照
      broadcastState();
      break;
    case WStype_DISCONNECTED:
      if (wsHolder && wsHolderNum == num) {
        wsHolder = false; wsHolderNum = 0;
        setHolder(Holder::NONE);
        triggerWatchdog();
      }
      break;
    case WStype_TEXT: {
      diagWsText++;
      // 解析 JSON 命令
      StaticJsonDocument<256> doc;
      DeserializationError jsonErr = deserializeJson(doc, payload, len);
      if (jsonErr) { snprintf(diagLastType, sizeof(diagLastType), "ERR:%s", jsonErr.c_str()); return; }
      const char* type_ = doc["type"] | "";
      strncpy(diagLastType, type_, sizeof(diagLastType) - 1);
      diagLastType[sizeof(diagLastType) - 1] = 0;
      if (strcmp(type_, "takeover") == 0) {
        if (follow.on) return;  // 跟随运行中不接受接管（跟随由启动者管理）
        wsHolder = true; wsHolderNum = num;
        setHolder(Holder::WEB);
      } else if (strcmp(type_, "sv_set") == 0) {
        // 只有主控能写 SV（粘性安全模式也靠显式 sv_set 退出）；跟随中拒绝
        if (!(wsHolder && wsHolderNum == num)) { /* 非主控拒绝 */ return; }
        if (follow.on) return;
        uint16_t v = doc["value"] | 0;
        if (v <= 260) {
          bool ok = heaterWriteSv(v);
          if (ok) {
            if (wdState != WdState::ARMED) { wdState = WdState::ARMED; }
            broadcastState();
          }
        }
      } else if (strcmp(type_, "follow_start") == 0) {
        // 跟随曲线启动：{type, points:[60,65,72,...]}（5s 间隔预采样）
        if (!(wsHolder && wsHolderNum == num)) return;  // 主控专用
        JsonArray arr = doc["points"].as<JsonArray>();
        if (arr.isNull() || arr.size() == 0 || arr.size() > FOLLOW_MAX_POINTS) return;
        uint8_t pts[FOLLOW_MAX_POINTS];
        uint16_t n = 0;
        for (uint8_t v : arr) { if (n >= FOLLOW_MAX_POINTS) break; pts[n++] = v; }
        followStart(n, pts);
        broadcastState();
      } else if (strcmp(type_, "follow_stop") == 0) {
        if (follow.on) {
          followStop(false);
          broadcastState();
        }
      } else if (strcmp(type_, "event") == 0) {
        // 事件标记：{type:"event", ev:"CHARGE|DRY|FCs|FCe|SCs|SCe|DROP"}
        // 固件记录（内存环形，本次会话），浏览器只读展示；任何角色可打
        const char* ev = doc["ev"] | "";
        if (strlen(ev) > 0 && evCount < EV_MAX) {
          strncpy(evLog[evCount].ev, ev, 7);
          evLog[evCount].ev[7] = 0;
          evLog[evCount].t = millis() / 1000;
          evLog[evCount].pv = heater.pv;
          evCount++;
        }
      } else if (strcmp(type_, "get_events") == 0) {
        // 回全部事件（本次会话）
        String out = "{\"type\":\"events\",\"list\":[";
        for (uint16_t i = 0; i < evCount; i++) {
          if (i) out += ",";
          out += "{\"ev\":\"" + String(evLog[i].ev) + "\",\"t\":" +
                 String(evLog[i].t) + ",\"pv\":" + String(evLog[i].pv) + "}";
        }
        out += "]}";
        ws.sendTXT(num, out);
      } else if (strcmp(type_, "estop") == 0) {
        // 任何角色可用：急停 = 终止跟随 + 压温度到安全值
        if (follow.on) followStop(false);
        heaterWriteSv(cfg.safeSv);
        heater.sv = cfg.safeSv;  // 立即刷新缓存
        ledcWrite(PIN_FAN_PWM, (uint16_t)cfg.safeFan * 255 / 100);
        fanSpeed = cfg.safeFan;
        if (wdState == WdState::ARMED) { wdState = WdState::SAFE_MODE; wdStateSince = millis(); }
        broadcastState();
      } else if (strcmp(type_, "get_config") == 0) {
        // 回 JSON 配置
        String out;
        StaticJsonDocument<512> c;
        c["type"] = "config";
        JsonObject w = c.createNestedObject("watchdog");
        w["enabled"] = cfg.wdEnabled; w["grace_seconds"] = cfg.graceS;
        w["safe_sv"] = cfg.safeSv; w["safe_fan"] = cfg.safeFan;
        w["safe_off_enabled"] = cfg.safeOffEnabled; w["safe_off_minutes"] = cfg.safeOffMin;
        c["sim"]["enabled"] = cfg.simEnabled;
        c["sim"]["ramp"] = cfg.simRamp; c["sim"]["ambient"] = cfg.ambient;
        JsonObject m = c.createNestedObject("modbus");
        m["reg_pv"] = cfg.regPv; m["reg_sv"] = cfg.regSv;
        m["baud"] = cfg.baud; m["slave_id"] = cfg.slaveId;
        serializeJson(c, out);
        ws.sendTXT(num, out);
      } else if (strcmp(type_, "set_config") == 0) {
        // ArduinoJson 7：嵌套对象用 as<JsonObject>() 提取，containsKey 改 if(!obj["k"].isNull())
        JsonObject w = doc["watchdog"].as<JsonObject>();
        if (!w.isNull()) {
          if (!w["enabled"].isNull()) cfg.wdEnabled = w["enabled"] | true;
          if (!w["grace_seconds"].isNull()) cfg.graceS = w["grace_seconds"] | DEF_WD_GRACE_S;
          if (!w["safe_sv"].isNull()) cfg.safeSv = w["safe_sv"] | DEF_SAFE_SV;
          if (!w["safe_fan"].isNull()) cfg.safeFan = w["safe_fan"] | DEF_SAFE_FAN;
          if (!w["safe_off_enabled"].isNull()) cfg.safeOffEnabled = w["safe_off_enabled"] | true;
          if (!w["safe_off_minutes"].isNull()) cfg.safeOffMin = w["safe_off_minutes"] | DEF_SAFE_OFF_MIN;
          if (cfg.graceS < 5 || cfg.graceS > 600) cfg.graceS = DEF_WD_GRACE_S;
          if (cfg.safeSv < 20 || cfg.safeSv > 150) cfg.safeSv = DEF_SAFE_SV;
          if (cfg.safeFan > 100) cfg.safeFan = DEF_SAFE_FAN;
          if (cfg.safeOffMin < 1 || cfg.safeOffMin > 120) cfg.safeOffMin = DEF_SAFE_OFF_MIN;
        }
        JsonObject s = doc["sim"].as<JsonObject>();
        if (!s.isNull()) {
          if (!s["enabled"].isNull()) cfg.simEnabled = s["enabled"] | false;
          if (!s["ramp"].isNull()) cfg.simRamp = s["ramp"] | DEF_SIM_RAMP;
          if (!s["ambient"].isNull()) cfg.ambient = s["ambient"] | DEF_AMBIENT;
          if (cfg.simRamp < 1 || cfg.simRamp > 60) cfg.simRamp = DEF_SIM_RAMP;
          if (cfg.ambient < 0 || cfg.ambient > 60) cfg.ambient = DEF_AMBIENT;
        }
        // Modbus 参数：改后需重启生效（rs485.begin 在 setup）
        JsonObject mb = doc["modbus"].as<JsonObject>();
        if (!mb.isNull()) {
          if (!mb["reg_pv"].isNull()) cfg.regPv = mb["reg_pv"] | DEF_REG_PV;
          if (!mb["reg_sv"].isNull()) cfg.regSv = mb["reg_sv"] | DEF_REG_SV;
          if (!mb["baud"].isNull()) cfg.baud = mb["baud"] | DEF_MODBUS_BAUD;
          if (!mb["slave_id"].isNull()) cfg.slaveId = mb["slave_id"] | DEF_MODBUS_SLAVE;
          if (cfg.baud < 300 || cfg.baud > 115200) cfg.baud = DEF_MODBUS_BAUD;
          if (cfg.slaveId < 1 || cfg.slaveId > 247) cfg.slaveId = DEF_MODBUS_SLAVE;
        }
        saveCfg();
        Serial.printf("[配置] sim=%d safeSv=%u safeFan=%u\n", cfg.simEnabled, cfg.safeSv, cfg.safeFan);
        broadcastState();
      } else if (strcmp(type_, "ping") == 0) {
        ws.sendTXT(num, "{\"type\":\"pong\"}");
      }
      break;
    }
    default: break;
  }
}

// ==================== 状态广播 ====================
void broadcastState() {
  StaticJsonDocument<384> d;
  d["type"] = "state";
  d["pv"] = heater.pv;
  d["sv"] = heater.sv;
  d["fan"] = fanSpeed;
  d["mode"] = cfg.simEnabled ? "sim" : "hw";
  d["link"] = heater.link;
  d["safe"] = (wdState == WdState::SAFE_MODE || wdState == WdState::OFF_COUNTDOWN);
  d["follow"] = follow.on;
  if (follow.on) {
    d["f_elapsed"] = (millis() - follow.t0) / 1000;
    d["f_total"] = follow.count * FOLLOW_INTERVAL_S;
    uint16_t idx = ((millis() - follow.t0) / 1000) / FOLLOW_INTERVAL_S;
    if (idx >= follow.count) idx = follow.count - 1;
    d["f_target"] = follow.pts[idx];
  }
  d["evn"] = evCount;  // 事件数（0 表示无）
  const char* holderStr = holder == Holder::APP ? "app" : holder == Holder::WEB ? "web" : "none";
  d["holder"] = holderStr;
  if (wdState == WdState::COUNTDOWN) {
    d["wd_remaining"] = cfg.graceS - (millis() - wdStateSince) / 1000;
  }
  d["ver"] = FIRMWARE_VERSION;
  d["uptime"] = millis() / 1000;
  String out;
  serializeJson(d, out);
  ws.broadcastTXT(out);
}

// ==================== 蓝牙配网等待循环 ====================
void startBleConfig() {
  Serial.println("\n[配网] 进入蓝牙配网模式（BLE）");
  Serial.println("[配网] App 里扫描蓝牙 RoastBridge，填入 WiFi 即可");
  WiFi.mode(WIFI_STA);
  delay(200);

  BLEDevice::init("RoastBridge");
  BLEServer* pServer = BLEDevice::createServer();
  BLEService* pService = pServer->createService(NUS_SERVICE_UUID);
  BLECharacteristic* txChar = pService->createCharacteristic(
    NUS_TX_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  txChar->setCallbacks(new BLEConfigCallbacks());
  BLECharacteristic* rxChar = pService->createCharacteristic(
    NUS_RX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  rxChar->addDescriptor(new BLE2902());
  pService->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(NUS_SERVICE_UUID);
  adv->start();
  Serial.println("[配网] 进入等待循环");

  bleCredBuffer = "";
  while (true) {
    int nl = bleCredBuffer.indexOf('\n');
    if (nl > 0 && (int)bleCredBuffer.length() - nl - 1 >= 8) {
      String ssid = bleCredBuffer.substring(0, nl);
      String pass = bleCredBuffer.substring(nl + 1);
      ssid.trim(); pass.trim();
      if (ssid.length() > 0 && pass.length() >= 8) {
        nvs.putString("ssid", ssid);
        nvs.putString("pass", pass);
        storedSsid = ssid; storedPass = pass;
        Serial.printf("[配网] 已收到 SSID=%s，重启连接\n", ssid.c_str());
        delay(500);
        ESP.restart();
      } else {
        Serial.println("[配网] 凭据格式无效，请重发");
        bleCredBuffer = "";
      }
    }
    setStatusLed(90, 0, 90, true);
    delay(10);
    yield();
    // BLE 宽限窗口：apAfter 非 0 且超时 → 自动转 AP 配网（iOS 主路径接管）
    if (apAfter != 0 && millis() > apAfter) {
      Serial.println("[配网] BLE 10 分钟无配网，转 AP 配网");
      BLEDevice::deinit(false);
      apAfter = 0;
      startApConfig();
      return;
    }
  }
}

// ==================== 串口配网（保留）====================
String readLine(uint32_t timeoutMs) {
  String s; uint32_t t0 = millis();
  while (millis() - t0 < timeoutMs) {
    while (Serial.available()) {
      char c = Serial.read();
      if (c == '\r') continue;
      if (c == '\n') return s;
      if (s.length() < 64) s += c;
    }
    delay(5);
    yield();
  }
  return s;
}

void loadCreds() {
  nvs.begin(NVS_NS, false);
  storedSsid = nvs.getString("ssid", "");
  storedPass = nvs.getString("pass", "");
}

// ==================== WiFi ====================
void ensureWifi() {
  if (WiFi.status() == WL_CONNECTED) return;
  if (storedSsid.isEmpty()) { startBleConfig(); }  // 无凭据 → BLE 配网（App 路径，保留）
  if (storedSsid.isEmpty()) return;
  Serial.print("[WiFi] 连接中 ");
  WiFi.mode(WIFI_STA);
  WiFi.begin(storedSsid.c_str(), storedPass.c_str());
  uint32_t t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < 15000) {
    delay(300);
    Serial.print(".");
  }
  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("\n[WiFi] 已连接 %s\n", WiFi.localIP().toString().c_str());
    static bool mdnsStarted = false;
    if (!mdnsStarted && MDNS.begin(MDNS_NAME)) {
      mdnsStarted = true;
      MDNS.addService("roastbridge", "tcp", TCP_PORT);
    }
  } else {
    Serial.println("[WiFi] 连接失败，稍后重试");
  }
}

// ==================== HTTP 状态口 ====================
uint32_t bootMs = 0;
uint32_t statReqCount = 0;
uint8_t wifiFailCount = 0;
uint8_t tcpBuf[300];
size_t  tcpLen = 0;

void handleStatus() {
  WiFiClient sc = statusServer.available();
  if (!sc) return;
  String reqLine = "";
  uint32_t t0 = millis(); size_t n = 0;
  while (sc.connected() && millis() - t0 < 300 && n < 256 && reqLine.indexOf('\n') < 0) {
    if (sc.available()) { char c = sc.read(); reqLine += c; n++; } else delay(1);
  }
  while (sc.connected() && millis() - t0 < 300 && n < 512) {
    if (sc.available()) { sc.read(); n++; } else break;
  }
  statReqCount++;

  if (reqLine.indexOf("/reset") >= 0) {
    sc.print("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n");
    sc.print("resetting");
    sc.flush(); delay(10); sc.stop();
    Serial.println("[重置] 收到 /reset，清除 WiFi 凭据并重启进配网");
    nvs.clear();
    delay(200);
    ESP.restart();
    return;
  }

  // Web UI 首页：gzip HTML（浏览器自动解压）
  if (reqLine.startsWith("GET / ") || reqLine.startsWith("GET /?")) {
    sc.print("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n");
    sc.print("Content-Encoding: gzip\r\n");
    sc.print("Cache-Control: no-cache\r\n");
    char cl[48];
    snprintf(cl, sizeof(cl), "Content-Length: %u\r\n", WEBUI_GZIP_LEN);
    sc.print(cl);
    sc.print("Connection: close\r\n\r\n");
    sc.flush();
    // PROGMEM 分块写（512B/块，避免 TCP 缓冲溢出）
    uint8_t buf[512];
    for (unsigned int off = 0; off < WEBUI_GZIP_LEN; off += sizeof(buf)) {
      unsigned int n = WEBUI_GZIP_LEN - off;
      if (n > sizeof(buf)) n = sizeof(buf);
      memcpy_P(buf, WEBUI_GZIP + off, n);
      size_t w = 0;
      while (w < n) {
        w += sc.write(buf + w, n - w);
        yield();
      }
      yield();
    }
    sc.flush(); delay(2); sc.stop();
    return;
  }

  if (reqLine.indexOf("/fan") >= 0) {
    int sp = 0;
    int eq = reqLine.indexOf("speed=");
    if (eq >= 0) sp = atoi(reqLine.c_str() + eq + 6);
    if (sp < 0) sp = 0;
    if (sp > 100) sp = 100;
    fanSpeed = (uint8_t)sp;
    uint16_t duty = (uint16_t)fanSpeed * 255 / 100;
    if (duty < FAN_DUTY_FLOOR && fanSpeed > 0) duty = FAN_DUTY_FLOOR;
    if (duty > FAN_DUTY_CEIL) duty = FAN_DUTY_CEIL;
    ledcWrite(PIN_FAN_PWM, duty);
    Serial.printf("[风] 风速 %u%% -> duty %u\n", (unsigned)fanSpeed, (unsigned)duty);
    char body[96];
    snprintf(body, sizeof(body), "{\"fan_speed\":%u,\"duty\":%u}",
             (unsigned)fanSpeed, (unsigned)duty);
    char head[128];
    snprintf(head, sizeof(head),
      "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %u\r\nConnection: close\r\n\r\n",
      (unsigned)strlen(body));
    sc.print(head); sc.print(body); sc.flush(); delay(2); sc.stop();
    return;
  }

  int32_t rssi = WiFi.RSSI();
  uint32_t upS = (millis() - bootMs) / 1000;
  char body[256];
  snprintf(body, sizeof(body),
    "{\"rssi\":%ld,\"uptime\":%lu,\"client\":%d,\"req\":%lu,\"fan_speed\":%u,\"ver\":\"%s\",\"sim\":%d,\"holder\":\"%s\",\"safe\":%d,\"ws_ev\":%lu,\"ws_text\":%lu,\"ws_conn\":%lu,\"ws_last\":\"%s\"}",
    (long)rssi, (unsigned long)upS,
    (client && client.connected()) ? 1 : 0, (unsigned long)statReqCount,
    (unsigned)fanSpeed, FIRMWARE_VERSION, cfg.simEnabled ? 1 : 0,
    holder == Holder::APP ? "app" : holder == Holder::WEB ? "web" : "none",
    (wdState == WdState::SAFE_MODE || wdState == WdState::OFF_COUNTDOWN) ? 1 : 0,
    (unsigned long)diagWsEvents, (unsigned long)diagWsText, (unsigned long)diagWsConn, diagLastType);
  char head[128];
  snprintf(head, sizeof(head),
    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %u\r\nConnection: close\r\n\r\n",
    (unsigned)strlen(body));
  sc.print(head); sc.print(body); sc.flush(); delay(2); sc.stop();
}

// ==================== 状态灯（看门狗感知版）====================
// 优先级：红闪(WiFi断) > 红常亮(安全模式) > 黄闪(倒计时) > 橙闪(熄火段) > 青呼吸(有客户端) > 绿常亮(待机)
void setStatusLedByWd() {
  switch (wdState) {
    case WdState::SAFE_MODE:    setStatusLed(80, 0, 0);        break; // 红常亮
    case WdState::OFF_COUNTDOWN:setStatusLed(255, 60, 0, true); break; // 橙闪
    case WdState::COUNTDOWN:    setStatusLed(255, 200, 0, true); break; // 黄闪
    default:                    setStatusLed(0, 80, 0);        break; // 绿常亮
  }
}

// 取消看门狗倒计时（新主控在位）
void triggerWatchdogCancel() {
  if (wdState == WdState::COUNTDOWN || wdState == WdState::OFF_COUNTDOWN) {
    wdState = WdState::ARMED;
    Serial.println("[看门狗] 新主控在位，倒计时取消");
    broadcastState();
  }
}

// ==================== setup / loop ====================
void setup() {
  Serial.begin(115200);
  WiFi.setSleep(false);
  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BOOT, INPUT_PULLUP);
  loadCfg();  // 提前：串口波特率来自配置
  rs485.begin(cfg.baud, SERIAL_8N1, PIN_485_RX, PIN_485_TX);

  ledcAttach(PIN_FAN_PWM, FAN_PWM_FREQ, FAN_PWM_BITS);
  ledcWrite(PIN_FAN_PWM, 0);
  fanSpeed = 0;
  Serial.printf("[风] PWM 引脚 GPIO%d，频率 %uHz\n", PIN_FAN_PWM, (unsigned)FAN_PWM_FREQ);
  loadCreds();
  ensureWifi();
  server.begin();
  server.setNoDelay(true);
  statusServer.begin();
  ws.begin();  // 8897
  ws.onEvent(wsEvent);  // 注册 WS 事件回调（漏了它整个 WS 命令通道是死的）
  bootMs = millis();

  ArduinoOTA.setHostname(MDNS_NAME);
  ArduinoOTA.setPassword(OTA_PASSWORD);
  ArduinoOTA
    .onStart([]() { Serial.println("[OTA] 开始烧写…"); })
    .onEnd([]()   { Serial.println("\n[OTA] 完成"); })
    .onProgress([](unsigned p, unsigned t) {
      if (p % 25 == 0) Serial.printf("[OTA] %u%%\n", p / (t / 100));
    })
    .onError([](ota_error_t e) { Serial.printf("[OTA] 错误 %u\n", e); });
  ArduinoOTA.begin();

  Serial.printf("[TCP] 监听端口 %u\n", TCP_PORT);
  Serial.printf("[WS] 监听端口 %u\n", 8897);
  Serial.printf("[RS485] 波特率 %lu, 8N1, 从站 %u\n", (unsigned long)cfg.baud, cfg.slaveId);
  Serial.printf("[SIM] 模式=%d\n", cfg.simEnabled);
}

void loop() {
  ArduinoOTA.handle();
  apLoop();        // AP 配网模式（非激活时立即返回）
  handleStatus();
  ws.loop();
  heaterPoll();
  watchdogTick();
  followTick();  // v1.6 跟随引擎（查表写 SV + 脱轨熔断）
  // 状态广播：1Hz 节流（事件触发的广播除外）
  {
    static uint32_t lastBcast = 0;
    if (millis() - lastBcast >= 1000) { lastBcast = millis(); broadcastState(); }
  }

  // BOOT 键长按 3 秒强制配网
  {
    static uint32_t bootPressStart = 0;
    if (digitalRead(PIN_BOOT) == LOW) {
      if (bootPressStart == 0) bootPressStart = millis();
      else if (millis() - bootPressStart > 3000) {
        Serial.println("[配网] BOOT 键长按，清除凭据；先试 BLE（App），10 分钟内未配自动转 AP");
        bootPressStart = 0;
        nvs.begin(NVS_NS, false); nvs.clear(); nvs.end();
        storedSsid = ""; storedPass = "";
        apAfter = millis() + 600000UL;  // 10 分钟 BLE 窗口，超时转 AP
        startBleConfig();
      }
    } else {
      bootPressStart = 0;
    }
  }

  // WiFi 守护
  if (WiFi.status() != WL_CONNECTED && WiFi.status() != WL_IDLE_STATUS) {
    setStatusLed(80, 0, 0, true);
    static uint32_t lastTry = 0;
    if (millis() - lastTry > 5000) {
      lastTry = millis();
      ensureWifi();
      server.begin();
      statusServer.begin();
      ws.begin();
      if (++wifiFailCount >= 3) {
        Serial.println("[配网] 连续连接失败，进入 AP 配网模式");
        wifiFailCount = 0;
        startApConfig();
      }
    }
    return;
  }
  wifiFailCount = 0;

  // TCP 客户端管理（App 主控候选）
  WiFiClient fresh = server.available();
  if (fresh) {
    if (client) client.stop();
    client = fresh;
    tcpLen = 0;
    Serial.printf("[TCP] 客户端接入 %s\n", client.remoteIP().toString().c_str());
    setHolder(Holder::APP);  // 协议即身份：TCP 连接即主控（顶掉 WS 主控）
  }

  // TCP 主控失联检测（App 断开 = 失联，触发看门狗）
  if (holder == Holder::APP && !(client && client.connected())) {
    Serial.println("[仲裁] App 连接断开，主控失联");
    setHolder(Holder::NONE);
    triggerWatchdog();
  }

  bool active = client && client.connected();
  if (active) setStatusLed(0, 60, 60, true);
  else setStatusLedByWd();
  if (!active) { delay(20); return; }

  // 攒齐完整 MBAP 帧处理（语义与 v1.3 一致）
  while (client.available()) {
    if (tcpLen >= sizeof(tcpBuf)) tcpLen = 0;
    tcpBuf[tcpLen++] = client.read();
    if (tcpLen >= MBAP_HEADER_LEN) {
      uint16_t length = (tcpBuf[4] << 8) | tcpBuf[5];
      size_t total = 6u + length;
      if (length == 0 || length > 253) { tcpLen = 0; continue; }
      if (tcpLen < total) continue;

      if (mbapToRtu(tcpBuf, total)) {
        rtuToMbap(client, tcpBuf, RSP_FIRST_TIMEOUT_MS);
      }
      size_t consumed = total;
      memmove(tcpBuf, tcpBuf + consumed, tcpLen - consumed);
      tcpLen -= consumed;
    }
  }
}
