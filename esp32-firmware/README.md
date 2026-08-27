# RoastBridge — ESP32-S3 烘焙桥接固件

替代 Wi-Fi 转 RS485 设备的自研网关：**WiFi (Modbus TCP) ↔ RS485 (Modbus RTU)**。
App 端零改动，只需把「桥接器 IP」换成这块板子的 IP。

## 兼容性

这类设备工作在 Modbus TCP 网关模式（收 MBAP 帧 → 转成带 CRC 的 RTU 帧上总线），
本固件 1:1 复刻该行为：

| 能力 | 原设备 | RoastBridge |
|---|---|---|
| Modbus TCP↔RTU 转换 | ✅ | ✅ |
| 端口 | 8899 | 8899 |
| 无应答处理 | 超时/挂起 | 回标准异常帧（0x0B），App 不卡死 |
| CRC 校验失败处理 | 未知 | 丢弃并记日志 |
| 断线自愈 | 一般 | WiFi 自动重连 + 客户端顶替机制 |

## 接线（4 根线）

```
ESP32-S3 DevKitC-1          TTL转RS485模块（自动收发款）
┌──────────────┐            ┌──────────┐
│  GPIO17 (TX) ├────────────┤ DI       │
│  GPIO18 (RX) ├────────────┤ RO       │
│  3V3         ├────────────┤ VCC      │
│  GND         ├────────────┤ GND      │
└──────────────┘            └──┬───┬───┘
                               A   B
                               │   │
                          温控器 RS485 端子（A对A、B对B，
                          极性照抄原设备接法；接反不烧但通不了）
```

注意：测试 ESP32 时请给原设备断电。总线上两个主站同时说话会冲突。

## 刷写方式二：直接刷 .bin（无需 Arduino IDE）

到 [Releases](https://github.com/MDx-MoJe/RoastCurve/releases) 下载编译好的固件 `.bin`，任选一种方式：

### A. 网页刷写（推荐，零安装）

1. 浏览器打开 Espressif 官方网页刷写器：https://espressif.github.io/esptool-js/
2. 串口选择板子（COM 口），芯片选 ESP32-S3，波特率 115200
3. 偏移填 `0x0`，文件选下载的 `roastbridge-*.merged.bin`
4. 点 Program 刷入，完成后按 RST 重启

### B. esptool 命令行

```bash
pip install esptool
esptool.py --chip esp32s3 --port /dev/cu.usbmodemXXXX write_flash 0x0 roastbridge-*.merged.bin
```

`merged.bin` 是合并镜像（含 bootloader + 分区表 + App），单文件刷 `0x0` 即可。

## 烧录步骤（Arduino IDE）

1. 装 Arduino IDE 2.x（arduino.cc 下载）
2. 文件 → 首选项 → 附加开发板管理器网址，填：
   `https://espressif.github.io/arduino-esp32/package_esp32_index.json`
3. 工具 → 开发板 → 开发板管理器 → 搜索 `esp32`，装
   **esp32 by Espressif Systems**（3.x 版本）
4. 打开 `roast_bridge.ino`，顶部可改 OTA 口令 `OTA_PASSWORD`（WiFi 不用改，见下方配网）
5. 工具菜单选择：
   - 开发板：ESP32S3 Dev Module
   - USB CDC On Boot: **Enabled**
   - Flash Size: 16MB
   - PSRAM: OPI PSRAM
   - 端口：插上板子后出现的 COM 口
6. 点左箭头上传；若提示按住 BOOT 键，按住板上 BOOT 直到开始传输

## 串口配网（首次使用）

固件不内置 WiFi 密码。首次上电后：

1. 板子紫灯闪烁 = 等待配网
2. 打开串口监视器（115200），依次输入：第一行 WiFi 名、第二行密码
3. 连接成功后凭据写入 NVS（非易失存储），之后上电自动连，换网络在串口重输一遍即可
4. 看到 `[配网] 已保存！IP=...` 后，记住这个 IP 给 App 用

## OTA 无线升级

固件已开启 ArduinoOTA（同局域网可达，口令见 `OTA_PASSWORD`）。
重刷固件不必再插 USB：Arduino IDE 的「工具 → 端口」会列出网络端口 `roastbridge`，选它上传即可。

## 验证

1. 串口监视器看到 `[TCP] 监听端口 8899` + IP 地址
2. 状态灯绿色常亮 = WiFi 就绪
3. 浏览器打开 `http://<板子IP>:8898/status` 应返回 JSON（含 `rssi` 信号强度）
4. App「桥接器 IP」填板子的 IP → 显示「已连接温控器」→ BT 有读数即全链路通
5. 可用现成探测脚本验证：`python3 tools/probe_tc4s.py <板子IP>`（在仓库根目录执行）

## 后续玩法（固件预留的演进空间）

- 直连热电偶测温（MAX6675/MAX31855），绕开温控器自成一体
- 断电续传、烘焙会话缓存等 App 端配合的高级特性
