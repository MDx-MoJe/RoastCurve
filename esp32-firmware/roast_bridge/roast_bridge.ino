/*
 * RoastCurve ESP32-S3 桥接固件
 * ================================================
 * 角色：WiFi(Modbus TCP) <-> RS485(Modbus RTU) 协议网关
 *       TCP 收到 MBAP 帧 -> 拆头取 PDU -> 拼 RTU 发串口
 *       串口收到 RTU 应答 -> 校验 CRC -> 重新封装 MBAP 回 TCP
 * App 端零改动，只换桥接器 IP。
 *
 * 串口参数（2026-08-28 降速实验验证，自动收发模块唯一稳定档位）：
 *   波特率 1200 / 数据位 8 / 无校验 / 停止位 1
 *
 * 引脚（ESP32-S3 DevKitC-1 N16R8，2026-08-28 验证通过的原始接线）：
 *   GPIO17 (TX) -> RS485模块 DI（数据输入）
 *   GPIO18 (RX) <- RS485模块 RO（数据输出）
 *   3V3         -> RS485模块 VCC
 *   GND         -> RS485模块 GND
 *   A/B         -> 温控器 RS485 A/B（极性与原设备接法一致）
 *
 * 板载 WS2812（GPIO48）状态灯：
 *   绿常亮 = WiFi 已连，等待客户端
 *   青呼吸 = 客户端已连上
 *   红闪烁 = WiFi 断开重连中
 *   紫闪烁 = 等待配网
 */

#include <WiFi.h>
#include <ESPmDNS.h>
#include <HardwareSerial.h>
#include <Preferences.h>
#include <ArduinoOTA.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <driver/ledc.h>

// OTA 升级口令（同局域网可达）
constexpr const char* OTA_PASSWORD = "roastota";

// ==================== 用户配置区 ====================
constexpr uint16_t TCP_PORT       = 8899;   // 与原设备一致，App 无需改动
constexpr const char* MDNS_NAME   = "roastbridge";

// RS485 串口参数（与温控器一致，自动收发模块唯一稳定档位 1200）
// 2026-08-28 验证通过的原始接线：TX=17 -> 模块 DI，RX=18 <- 模块 RO
constexpr int      PIN_485_TX     = 17;      // 固件 TX -> 模块 DI（输入）
constexpr int      PIN_485_RX     = 18;      // 固件 RX <- 模块 RO（输出）
constexpr uint32_t MODBUS_BAUD    = 1200;

// ==================== 风机 PWM（风速自动化，2026-09-01 接入）====================
// 旋钮模块（C030_PCB_V2）输出 PWM 给主控板 ADJ，实测频率 1.002kHz（占空比调速）
// 验证阶段：全范围 0-100% 映射，确认电机最低可转占空比后再锁下限（FAN_DUTY_FLOOR 预留）
constexpr int      PIN_FAN_PWM       = 2;      // 空闲 GPIO，可改（避开 17/18 RS485、48 LED、0 BOOT）
constexpr uint32_t FAN_PWM_FREQ      = 1000;   // 与旋钮模块一致（实测 1.002kHz）
constexpr int      FAN_PWM_BITS      = 8;      // 占空比分辨率 8 位（duty 0-255）
constexpr uint16_t FAN_DUTY_FLOOR    = 0;      // 预留下限锁死（如 26=10%），验证后启用

// 自动收发模块：无需 DIR 控制，模块靠 RC 电路自己切方向，固件只管发/收
// （带 DIR 脚的模块才需要手动控方向，本固件当前用自动收发模块，故无 DIR 代码）


// BOOT 键（GPIO0）：长按 3 秒强制进入配网模式
constexpr int      PIN_BOOT       = 0;

// 时序参数
constexpr uint32_t RSP_FIRST_TIMEOUT_MS = 800;  // 等待温控器应答首字节
constexpr uint32_t RSP_GAP_TIMEOUT_MS   = 30;   // 字节间静默视为帧结束
constexpr uint16_t MBAP_HEADER_LEN      = 7;
// ===================================================

HardwareSerial rs485(1);
WiFiServer server(TCP_PORT);
WiFiClient client;
Preferences nvs;
String storedSsid, storedPass;

// BLE 配网（复用 Nordic UART Service，与 App 现有 BLE 透传对齐）
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

// ---------- WS2812 状态灯 ----------
constexpr int PIN_LED = 48;
uint32_t lastBlink = 0;
bool blinkOn = false;

void setStatusLed(int r, int g, int b, bool blink = false) {
  if (blink) {
    if (millis() - lastBlink > 400) { lastBlink = millis(); blinkOn = !blinkOn; }
    if (!blinkOn) { r = g = b = 0; }
  }
  neopixelWrite(PIN_LED, r, g, b);
}

// ---------- Modbus CRC16 ----------
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

// ---------- TCP -> RTU：解析 MBAP 下发串口 ----------
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

  // 清掉串口残留，再发送（自动收发模块自己管方向）
  while (rs485.available()) rs485.read();
  rs485.write(rtu, length + 2);
  rs485.flush();
  return true;
}

// ---------- RTU -> TCP：收应答重新封装 MBAP ----------
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
    } else if (gotFirst && millis() - lastByte >= RSP_GAP_TIMEOUT_MS) {
      break;
    }
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

// ---------- 蓝牙配网 ----------
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
  BLECharacteristic* rxChar = pService->createCharacteristic(NUS_RX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
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
      ssid.trim();
      pass.trim();
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
  }
}

// ---------- 串口配网（无凭据时阻塞等待输入）----------
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
  nvs.begin("roastbridge", false);
  storedSsid = nvs.getString("ssid", "");
  storedPass = nvs.getString("pass", "");
}

// ---------- WiFi ----------
void ensureWifi() {
  if (WiFi.status() == WL_CONNECTED) return;
  if (storedSsid.isEmpty()) { startBleConfig(); }
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
    Serial.println("\n[WiFi] 连接失败，稍后重试");
  }
}

// ---------- 主循环状态 ----------
uint8_t  tcpBuf[300];
size_t   tcpLen = 0;
WiFiServer statusServer(8898);
uint32_t bootMs = 0;
uint32_t statReqCount = 0;
uint8_t wifiFailCount = 0;

// 风机风速状态：0-100（验证阶段全范围，duty = speed*255/100）
uint8_t fanSpeed = 0;

// ---------- HTTP 状态口（8898）：/status 返回信号；/reset 清除 WiFi ----------
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
    sc.flush();
    delay(10);
    sc.stop();
    Serial.println("[重置] 收到 /reset，清除 WiFi 凭据并重启进配网");
    nvs.clear();
    delay(200);
    ESP.restart();
    return;
  }

  if (reqLine.indexOf("/fan") >= 0) {
    // /fan?speed=NN  ->  NN 0-100，验证阶段全范围映射 duty = speed*255/100
    int sp = 0;
    int eq = reqLine.indexOf("speed=");
    if (eq >= 0) sp = atoi(reqLine.c_str() + eq + 6);
    if (sp < 0) sp = 0;
    if (sp > 100) sp = 100;
    fanSpeed = (uint8_t)sp;
    uint16_t duty = (uint16_t)fanSpeed * 255 / 100;
    if (duty < FAN_DUTY_FLOOR && fanSpeed > 0) duty = FAN_DUTY_FLOOR;
    ledcWrite(PIN_FAN_PWM, duty);
    Serial.printf("[风] 风速 %u%% -> duty %u\n", (unsigned)fanSpeed, (unsigned)duty);
    char body[96];
    snprintf(body, sizeof(body), "{\"fan_speed\":%u,\"duty\":%u}",
             (unsigned)fanSpeed, (unsigned)duty);
    char head[128];
    snprintf(head, sizeof(head),
      "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %u\r\nConnection: close\r\n\r\n",
      (unsigned)strlen(body));
    sc.print(head);
    sc.print(body);
    sc.flush();
    delay(2);
    sc.stop();
    return;
  }

  int32_t rssi = WiFi.RSSI();
  uint32_t upS = (millis() - bootMs) / 1000;
  char body[128];
  snprintf(body, sizeof(body),
    "{\"rssi\":%ld,\"uptime\":%lu,\"client\":%d,\"req\":%lu,\"fan_speed\":%u}",
    (long)rssi, (unsigned long)upS,
    (client && client.connected()) ? 1 : 0, (unsigned long)statReqCount,
    (unsigned)fanSpeed);
  char head[128];
  snprintf(head, sizeof(head),
    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %u\r\nConnection: close\r\n\r\n",
    (unsigned)strlen(body));
  sc.print(head);
  sc.print(body);
  sc.flush();
  delay(2);
  sc.stop();
}

void setup() {
  Serial.begin(115200);
  WiFi.setSleep(false);
  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BOOT, INPUT_PULLUP);
  rs485.begin(MODBUS_BAUD, SERIAL_8N1, PIN_485_RX, PIN_485_TX);

  // 风机 PWM 初始化：频率 1000Hz（与旋钮模块一致），起始占空比 0（风机停）
  ledcAttach(PIN_FAN_PWM, FAN_PWM_FREQ, FAN_PWM_BITS);
  ledcWrite(PIN_FAN_PWM, 0);
  fanSpeed = 0;
  Serial.printf("[风] PWM 引脚 GPIO%d，频率 %uHz\n", PIN_FAN_PWM, (unsigned)FAN_PWM_FREQ);
  loadCreds();
  ensureWifi();
  server.begin();
  server.setNoDelay(true);
  statusServer.begin();
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
  Serial.printf("[RS485] 波特率 %u, 8N1\n", (unsigned)MODBUS_BAUD);
}

void loop() {
  ArduinoOTA.handle();
  handleStatus();

  // BOOT 键长按 3 秒强制配网
  {
    static uint32_t bootPressStart = 0;
    if (digitalRead(PIN_BOOT) == LOW) {
      if (bootPressStart == 0) bootPressStart = millis();
      else if (millis() - bootPressStart > 3000) {
        Serial.println("[配网] BOOT 键长按，进入蓝牙配网模式");
        bootPressStart = 0;
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
      if (++wifiFailCount >= 3) {
        Serial.println("[配网] 连续连接失败，进入蓝牙配网模式");
        wifiFailCount = 0;
        startBleConfig();
      }
    }
    return;
  }
  wifiFailCount = 0;

  // 客户端管理：单连接，新连接顶替旧连接
  WiFiClient fresh = server.available();
  if (fresh) {
    if (client) client.stop();
    client = fresh;
    tcpLen = 0;
    Serial.printf("[TCP] 客户端接入 %s\n", client.remoteIP().toString().c_str());
  }

  bool active = client && client.connected();
  if (active) setStatusLed(0, 60, 60, true);
  else        setStatusLed(0, 80, 0);
  if (!active) { delay(20); return; }

  // 攒齐一条完整 MBAP 帧再处理
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
