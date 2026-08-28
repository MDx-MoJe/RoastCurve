/*
 * RoastCurve ESP32-S3 桥接固件 v1.0
 * ================================================
 * 角色：替代 Wi-Fi 转 RS485 设备，作为 App 与温控器之间的
 *       WiFi(Modbus TCP) <-> RS485(Modbus RTU) 协议网关
 *
 * 关键设计：完整复刻 Wi-Fi 转 RS485 设备的网关行为（不是裸透传）：
 *   TCP 收到 MBAP 帧 -> 拆头、取 PDU -> 拼 RTU(UID+PDU+CRC16) 发串口
 *   串口收到 RTU 应答 -> 校验 CRC -> 用原事务号重新封装 MBAP 回 TCP
 * 这样 App 端一行代码都不用改，只换 IP。
 *
 * 引脚（ESP32-S3 DevKitC-1 N16R8）：
 *   GPIO17 (TX) -> RS485模块 DI
 *   GPIO18 (RX) <- RS485模块 RO
 *   3V3         -> RS485模块 VCC
 *   GND         -> RS485模块 GND
 *   A/B         -> 温控器 RS485 A/B（极性与原设备接法一致）
 *
 * 板载 WS2812（GPIO48）状态灯：
 *   绿色常亮 = WiFi 已连接，等待客户端
 *   青色呼吸 = 客户端已连上（App 在通信）
 *   红色闪烁 = WiFi 断开，重连中
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

// OTA 升级口令：同一局域网才可达。开源自用请改成你自己的密码，留默认值也无妨（仅防误刷）。
constexpr const char* OTA_PASSWORD = "roastota";

// ==================== 用户配置区 ====================
// WiFi 凭据：首次烧录后通过串口监视器（115200）输入，存入 NVS（非易失存储），
// 断电不丢；换 WiFi 时任意时刻在串口重输一遍即可覆盖。
constexpr uint16_t TCP_PORT      = 8899;   // 与原设备一致，App无需改动
constexpr const char* MDNS_NAME  = "roastbridge";  // 可用 roastbridge.local 访问

// RS485 串口参数：与温控器一致（1200-8-无校验-1，自动收发模块降速验证实验）
constexpr int      PIN_485_TX   = 17;
constexpr int      PIN_485_RX   = 18;
constexpr uint32_t MODBUS_BAUD  = 1200;

// BOOT 键（GPIO0，按下为低电平）：长按 3 秒强制进入配网模式（配网写错时的重置开关）
constexpr int      PIN_BOOT      = 0;

// 时序参数
constexpr uint32_t RSP_FIRST_TIMEOUT_MS = 800;  // 等待温控器应答首字节
constexpr uint32_t RSP_GAP_TIMEOUT_MS   = 30;   // 字节间静默视为帧结束
constexpr uint16_t MBAP_HEADER_LEN      = 7;    // 事务2+协议2+长度2+单元1
// ===================================================

HardwareSerial rs485(1);
WiFiServer server(TCP_PORT);
WiFiClient client;
Preferences nvs;
String storedSsid, storedPass;

// BLE 配网：复用 Nordic UART Service（与 App 现有 BLE 透传对齐）
static const BLEUUID NUS_SERVICE_UUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
static const BLEUUID NUS_TX_UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");  // 手机写 → 板子
static const BLEUUID NUS_RX_UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");  // 板子通知 → 手机
String bleCredBuffer;
volatile bool bleCredReady = false;

class BLEConfigCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* ch) {
    String val = ch->getValue();
    // 只累积字节（含 \n），不在此解析；凭据可能因 MTU 限制被分包，
    // 由主循环等拼齐（\n 后 pass 长度 >= 8）后再解析
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

// ---------- TCP -> RTU：解析 MBAP 并下发串口 ----------
// 返回是否成功发出（合法 MBAP 才发）
bool mbapToRtu(const uint8_t* frame, size_t frameLen) {
  if (frameLen < MBAP_HEADER_LEN) return false;
  uint16_t protoId = (frame[2] << 8) | frame[3];
  uint16_t length  = (frame[4] << 8) | frame[5];
  if (protoId != 0 || frameLen < 6u + length) return false;  // 只认标准 Modbus TCP

  // RTU 帧 = 单元号(1) + PDU(length-1) + CRC(2)
  uint8_t rtu[260];
  rtu[0] = frame[6];
  memcpy(rtu + 1, frame + MBAP_HEADER_LEN, length - 1);
  uint16_t crc = crc16(rtu, length);
  rtu[length]     = crc & 0xFF;
  rtu[length + 1] = crc >> 8;

  // 清掉串口残留，再发送
  while (rs485.available()) rs485.read();
  rs485.write(rtu, length + 2);
  rs485.flush();
  return true;
}

// ---------- RTU -> TCP：收应答并重新封装 MBAP ----------
// tid 为原请求的事务号；无应答时回一个网关异常响应，避免 App 干等
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
      break;  // 静默超时 = 帧接收完成
    }
    yield();
  }

  // CRC 校验（有足够字节才算）
  if (gotFirst && n >= 4) {
    uint16_t expect = crc16(rsp, n - 2);
    uint16_t actual = rsp[n - 2] | (rsp[n - 1] << 8);
    crcOk = (expect == actual);
  }

  // 无论无应答、残帧还是 CRC 错，都必须给 TCP 回一个网关异常应答，永不静默——
  // 否则客户端只能干等超时，会被误判为桥接器假死
  if (!gotFirst || !crcOk) {
    uint8_t ex[9];
    ex[0] = reqHeader[0]; ex[1] = reqHeader[1];          // 原事务号
    ex[2] = 0; ex[3] = 0;                                 // 协议号
    ex[4] = 0; ex[5] = 3;                                 // 后续长度3
    ex[6] = reqHeader[6];                                 // 单元号
    ex[7] = reqHeader[7] | 0x80;                          // 异常功能码
    ex[8] = 0x0B;                                         // 网关目标设备未响应
    out.write(ex, sizeof(ex));
    if (gotFirst)
      Serial.printf("[RTU] 收到 %d 字节%s，已回网关异常\n", n, n >= 4 ? "CRC错误" : "残帧");
    return;
  }

  // 封装 MBAP：原事务号 + 单元号 + PDU(n-3 = 去掉UID和CRC)
  uint8_t mbap[MBAP_HEADER_LEN];
  mbap[0] = reqHeader[0]; mbap[1] = reqHeader[1];
  mbap[2] = 0; mbap[3] = 0;
  uint16_t mlen = 1 + (n - 3);
  mbap[4] = mlen >> 8; mbap[5] = mlen & 0xFF;
  mbap[6] = rsp[0];
  out.write(mbap, MBAP_HEADER_LEN);
  out.write(rsp + 1, n - 3);
}

// ---------- 手机蓝牙配网（App 连 BLE 发 WiFi 凭据，无需切 WiFi） ----------
void startBleConfig() {
  Serial.println("\n[配网] 进入蓝牙配网模式（BLE）");
  Serial.println("[配网] App 里扫描蓝牙 RoastBridge，填入 WiFi 即可");
  // 注意：不要 WiFi.mode(WIFI_OFF)！ESP32 的 BLE 与 WiFi 共享射频 PHY，
  // 关闭 WiFi 会同时关掉 PHY 时钟，导致 BLE 初始化失败/卡死。
  WiFi.mode(WIFI_STA);  // 保持 STA 模式（PHY 开启，但不连接）
  Serial.println("[配网] WiFi.mode(WIFI_STA) 完成");
  delay(200);

  BLEDevice::init("RoastBridge");
  Serial.println("[配网] BLEDevice::init 完成");
  BLEServer* pServer = BLEDevice::createServer();
  Serial.println("[配网] createServer 完成");
  BLEService* pService = pServer->createService(NUS_SERVICE_UUID);
  Serial.println("[配网] createService 完成");
  BLECharacteristic* txChar = pService->createCharacteristic(
    NUS_TX_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  txChar->setCallbacks(new BLEConfigCallbacks());
  BLECharacteristic* rxChar = pService->createCharacteristic(NUS_RX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  rxChar->addDescriptor(new BLE2902());
  pService->start();
  Serial.println("[配网] pService->start 完成");
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(NUS_SERVICE_UUID);
  adv->start();
  Serial.println("[配网] adv->start 完成，进入等待循环");

  bleCredBuffer = "";
  bleCredReady = false;
  while (true) {
    // 等凭据拼齐：buffer 里有 \n，且 \n 后的密码长度 >= 8（密码最低 8 位）
    // 这样即使凭据被 BLE MTU 拆成多包，也能等所有包到齐后再解析
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
        Serial.printf("[配网] 已收到 SSID=%s (密码 %d 位)，重启连接\n", ssid.c_str(), (int)pass.length());
        delay(500);
        ESP.restart();
      } else {
        Serial.println("[配网] 凭据格式无效，请重发");
        bleCredBuffer = "";
      }
    }
    setStatusLed(90, 0, 90, true);  // 紫闪
    delay(10);
    yield();
  }
}

// ---------- 串口配网：无凭据时阻塞等待输入 SSID\n密码 ----------
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

void serialProvision() {
  Serial.println();
  Serial.println("[配网] 未检测到 WiFi 凭据");
  Serial.println("[配网] 请依次输入：第一行=WiFi名，第二行=密码（60秒内）");
  Serial.print("WiFi名> ");
  while (!Serial.available()) { setStatusLed(90, 0, 90, true); delay(50); yield(); } // 紫闪=等配网
  String ssid = readLine(60000); if (ssid.isEmpty()) return;
  Serial.println(ssid);
  Serial.print("密码> ");
  String pass = readLine(60000);
  Serial.println("********");
  if (pass.isEmpty()) { Serial.println("[配网] 密码为空，放弃"); return; }
  Serial.printf("[配网] 尝试连接 %s …\n", ssid.c_str());
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid.c_str(), pass.c_str());
  uint32_t t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < 15000) { delay(300); Serial.print("."); }
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("\n[配网] 连接失败，凭据未保存。重启后重新输入");
    WiFi.disconnect();
    return;
  }
  nvs.putString("ssid", ssid);
  nvs.putString("pass", pass);
  storedSsid = ssid; storedPass = pass;
  Serial.printf("\n[配网] 已保存！IP=%s\n", WiFi.localIP().toString().c_str());
}

// ---------- WiFi ----------
void ensureWifi() {
  if (WiFi.status() == WL_CONNECTED) return;
  if (storedSsid.isEmpty()) { startBleConfig(); }  // 无凭据 → 蓝牙配网
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
    // 只初始化一次：WiFi 掉线重连后重复 MDNS.begin() 会触化 mDNS 重探+堆损坏（实测崩溃循环），重连后 mDNS 会自动重绑
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
WiFiServer statusServer(8898);          // 轻量 HTTP 状态口
uint32_t bootMs = 0;                    // 上电时刻，算运行时长用
uint32_t statReqCount = 0, statRspOk = 0;   // 粗粒度通信统计
uint8_t wifiFailCount = 0;              // WiFi 连续失败计数（达到阈值进配网模式）

// ---------- HTTP 状态口（8898）：/status 返回信号强度；/reset 清除 WiFi 重新配网 ----------
void handleStatus() {
  WiFiClient sc = statusServer.available();
  if (!sc) return;
  // 读请求首行（最多 256 字节，够解析 GET /xxx HTTP/1.x）
  String reqLine = "";
  uint32_t t0 = millis(); size_t n = 0;
  while (sc.connected() && millis() - t0 < 300 && n < 256 && reqLine.indexOf('\n') < 0) {
    if (sc.available()) { char c = sc.read(); reqLine += c; n++; } else delay(1);
  }
  // 读掉剩余请求头（浏览器/客户端会带 Host 等），避免脏数据
  while (sc.connected() && millis() - t0 < 300 && n < 512) {
    if (sc.available()) { sc.read(); n++; } else break;
  }
  statReqCount++;

  // /reset：清除 WiFi 凭据，重启进配网模式（换 WiFi 时 App 一键重置，无需碰硬件）
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

  int32_t rssi = WiFi.RSSI();
  uint32_t upS = (millis() - bootMs) / 1000;
  char body[192];
  snprintf(body, sizeof(body),
    "{\"rssi\":%ld,\"uptime\":%lu,\"client\":%d,\"req\":%lu}",
    (long)rssi, (unsigned long)upS,
    (client && client.connected()) ? 1 : 0, (unsigned long)statReqCount);
  char head[128];
  snprintf(head, sizeof(head),
    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %u\r\nConnection: close\r\n\r\n",
    (unsigned)strlen(body));
  sc.print(head);
  sc.print(body);
  sc.flush();
  delay(2);        // 给 TCP 栈送出的时间
  sc.stop();
}

void setup() {
  Serial.begin(115200);
  WiFi.setSleep(false);   // 服务器角色禁用省电：Modem 休眠在拥挤信道下会偶发掉线
  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BOOT, INPUT_PULLUP);
  rs485.begin(MODBUS_BAUD, SERIAL_8N1, PIN_485_RX, PIN_485_TX);
  loadCreds();
  ensureWifi();
  server.begin();
  server.setNoDelay(true);
  statusServer.begin();                 // 状态口 8898
  bootMs = millis();

  // OTA：无线升级（此后重刷固件不必再插 USB，走 WiFi 即可）
  ArduinoOTA.setHostname(MDNS_NAME);
  ArduinoOTA.setPassword(OTA_PASSWORD);   // 简单口令防误刷（同局域网才可达）
  ArduinoOTA
    .onStart([]() { Serial.println("[OTA] 开始烧写…"); })
    .onEnd([]()   { Serial.println("\n[OTA] 完成"); })
    .onProgress([](unsigned p, unsigned t) {
      if (p % 25 == 0) Serial.printf("[OTA] %u%%\n", p / (t / 100));
    })
    .onError([](ota_error_t e) { Serial.printf("[OTA] 错误 %u\n", e); });
  ArduinoOTA.begin();

  Serial.printf("[TCP] 监听端口 %u\n", TCP_PORT);
}

void loop() {
  ArduinoOTA.handle();                   // OTA 升级处理（无升级时零开销）
  handleStatus();                        // 状态口轮询（无连接时零开销）

  // BOOT 键长按 3 秒：强制进入配网模式（配网写错/换 WiFi 的手动重置）
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

  // WiFi 守护（WL_IDLE_STATUS=正在连接中，此时不再叠发 WiFi.begin，避免 "sta is connecting" 错误）
  if (WiFi.status() != WL_CONNECTED && WiFi.status() != WL_IDLE_STATUS) {
    setStatusLed(80, 0, 0, true);  // 红闪
    static uint32_t lastTry = 0;
    if (millis() - lastTry > 5000) {
      lastTry = millis();
      ensureWifi();
      server.begin();
      // 连续 3 次（每次约 15 秒连接尝试，共约 45 秒）连不上，自动进入手机配网模式
      // （换 WiFi / 密码错时不用插电脑，蓝牙配网重设）
      if (++wifiFailCount >= 3) {
        Serial.println("[配网] 连续连接失败，进入蓝牙配网模式");
        wifiFailCount = 0;
        startBleConfig();
      }
    }
    return;
  }
  wifiFailCount = 0;  // WiFi 正常，清零失败计数

  // 客户端管理：只服务一个连接，新连接顶替旧连接。
  // 关键：无论旧连接状态如何都检查新连接。半开连接（对端断电/切 WiFi 未发 FIN）
  // 会让 client.connected() 长期返回 true，若只在「断开」时才 accept，半开连接会堵死新连接。
  WiFiClient fresh = server.available();
  if (fresh) {
    if (client) client.stop();
    client = fresh;
    tcpLen = 0;
    Serial.printf("[TCP] 客户端接入 %s\n", client.remoteIP().toString().c_str());
  }

  bool active = client && client.connected();
  if (active) setStatusLed(0, 60, 60, true);   // 青色呼吸
  else        setStatusLed(0, 80, 0);          // 绿色常亮
  if (!active) { delay(20); return; }

  // ---- 攒齐一条完整 MBAP 帧再处理 ----
  while (client.available()) {
    if (tcpLen >= sizeof(tcpBuf)) tcpLen = 0;   // 防御先行：畸形数据流灤满缓冲时归零，严禁越界写（越界会破坏堆，在别处爆雷）
    tcpBuf[tcpLen++] = client.read();
      if (tcpLen >= MBAP_HEADER_LEN) {
        uint16_t length = (tcpBuf[4] << 8) | tcpBuf[5];
        size_t total = 6u + length;
        if (length == 0 || length > 253) { tcpLen = 0; continue; }  // 异常帧丢弃
        if (tcpLen < total) continue;                                // 还没攒够

      if (mbapToRtu(tcpBuf, total)) {
        // 传整帧首地址：内部要读事务号[0,1]/单元号[6]/功能码[7]
        rtuToMbap(client, tcpBuf, RSP_FIRST_TIMEOUT_MS);
      }
      // 处理缓冲区里可能紧跟着的下一条
      size_t consumed = total;
      memmove(tcpBuf, tcpBuf + consumed, tcpLen - consumed);
      tcpLen -= consumed;
    }
  }
}
