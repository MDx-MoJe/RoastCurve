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

## 接线（RS485 五根线 + 风扇 PWM 两根，v1.3 实测定稿）

### RS485（温控器通信）

```
ESP32-S3 DevKitC-1          TTL转RS485模块（自动收发款，DI/RO 命名）
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

⚠️ **线序铁律（2026-08-28 验证基线，勿改）**：自动收发模块（DI/RO 命名）
用 **GPIO17(TX)→DI、GPIO18(RX)←RO**，配合 1200 波特率读写全通。
⚠️ 若你的模块丝印是 TXD/RXD 命名，需先确认视角（TXD 是输入还是输出），
接反会全线死寂（字节永远上不了总线）——这是「读通写不通/全哑」的头号真凶。

自动收发模块无 DIR 脚，固件无 DIR 控制代码（GPIO21 不接）。

### 风扇 PWM（v1.3 新增，风速自动化）

需要用 PWM 款风机驱动板替代原电阻调速板（旋钮款），把 ESP32 接到驱动板调速输入：

```
ESP32-S3 DevKitC-1          PWM 风机驱动板
┌──────────────┐
│  GPIO2       ├──────────→ 调速输入（原旋钮信号焊点，如主控板 R20）
│  GND         ├──────────→ 旋钮信号地（非 24V 功率地！）
└──────────────┘
```

- 1kHz / 8bit（与原旋钮模块实测 1.002kHz 一致），3.3V 直连驱动板，无需电平转换
- App 经 HTTP `http://<板子IP>:8898/fan?speed=0-100` 控制；`speed=0` 停机
- 固件锁界：下限 7%（duty 18，防停转/异常）、上限 99%（duty 252，防 100% 直通模式）
- 不接风扇驱动板时这两根悬空即可，RS485 功能不受影响

注意：测试 ESP32 时请给原设备断电。总线上两个主站同时说话会冲突。

## 自定义引脚（v1.7+：Web UI 直接配，无需改代码）

**RS485 TX / RX 与风机 PWM 三脚可在网页或 App 里配**（固件 v1.7.0 起 Web UI / v1.8.2 起 App + HTTP 口）：

- **App**：设置 →「桥接器 GPIO 引脚」→ 三个下拉选择 → 保存
- **网页**：设置 →「GPIO 引脚配置」→ 三个下拉选择 → 保存
- 两者都写同一份 NVS 配置（App 走 HTTP `/gpiocfg`，网页走 WebSocket `set_config`）

默认 17 / 18 / 2 不变。换 ESP32 板子时不用改代码重刷，网页选好引脚即可，
适合 DIY 玩家已有自己板子（比如 RS485 已接 14/15 的情况）。

**可分配引脚池**（网页下拉里可选，已排除全部硬件禁区与争议脚）：

```
GPIO 2, 4-18, 21, 38-42
```

**模块侧语义永远不变**：固件 TX 接模块 **DI**（模块的数据输入）、
固件 RX 接模块 **RO**（模块的数据输出）。变的只是固件用哪两个 GPIO，
DI/RO 丝印视角不跟固件脚位走。接反会全线死寂（字节上不了总线）。

**这些 GPIO 永远不可分配（硬件占用/功能保留）**

| GPIO | 用途 | 占用后果 |
|---|---|---|
| 19/20 | USB（D-/D+） | USB 烧录/串口失效 |
| 26~32 | 片内 SPI 闪存（QIO 16MB） | 变砖（N16R8 板必碰不得） |
| 33~37 | Octal PSRAM（8MB） | 变砖 |
| 0 | BOOT 键（保留：配网/恢复默认） | 功能保留，不开放 |
| 1/3/45/46 | strapping / 32K 晶振（争议脚） | 上电启动异常 |
| 48 | 板载 WS2812 状态灯 | 功能保留，不开放 |

**改错失联了怎么办**：长按 BOOT 键 3 秒清除全部配置（含 WiFi 凭据与引脚）回出厂，
重新配网后引脚恢复默认 17/18/2。

> 旧版（≤v1.6.x）是改 `roast_bridge.ino` 顶部 `PIN_485_TX` 等常量重编译；
> v1.7.0 起该能力已网页化，代码里的常量仅作为出厂默认值。

## 刷写方式二：直接刷 .bin（无需 Arduino IDE）

到下载页获取编译好的固件 `.bin`（GitHub 与 Gitee 同步发布）：

- GitHub：[Releases](https://github.com/MDx-MoJe/RoastCurve/releases)
- Gitee（国内快）：[发行版](https://gitee.com/MDx-MoJe/roast-curve/releases)

任选一种方式刷写：

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

> ⚠️ **Flash Size 必须选 16MB，这是硬门槛不是建议。**
> 本固件用 16MB custom 分区表（app 区 6.4MB），若漏选/错选成 4MB/8MB，
> bootloader 会按错误容量启动，报 `partition invalid ... exceeds flash chip size 0x400000`
> 并**开机死循环**（症状：状态灯常亮但不连 WiFi、串口反复打印 Failed to verify partition table）。
> 详见下文「刷死（分区表错误）怎么救」。（2026-09-06 实踩：CLI 漏传 FlashSize 参数即触发）

1. 装 Arduino IDE 2.x（arduino.cc 下载）
2. 文件 → 首选项 → 附加开发板管理器网址，填：
   `https://espressif.github.io/arduino-esp32/package_esp32_index.json`
3. 工具 → 开发板 → 开发板管理器 → 搜索 `esp32`，装
   **esp32 by Espressif Systems**（3.x 版本）
4. 打开 `roast_bridge.ino`，顶部可改 OTA 口令 `OTA_PASSWORD`（WiFi 不用改，见下方配网）
5. 工具菜单选择（**每一项都不能漏**）：
   - 开发板：ESP32S3 Dev Module
   - USB CDC On Boot: **Enabled**
   - Flash Size: **16MB (128Mb)** ← 漏选/错选会刷死，见上警告
   - Partition Scheme: **Custom**（自动采用 sketch 目录 partitions.csv）
   - PSRAM: OPI PSRAM
   - 端口：插上板子后出现的 COM 口
6. 点左箭头上传；若提示按住 BOOT 键，按住板上 BOOT 直到开始传输

## 刷写（arduino-cli 命令行）

> ⚠️ **fqbn 必须带完整 `FlashSize=16M,PartitionScheme=custom`**，
> 漏掉（如只写 `esp32:esp32:esp32s3`）会按默认 4MB 重写 bootloader，
> 结果同上：启动死循环。（2026-09-06 实踩）

```bash
# 编译（必须完整 fqbn；custom 会自动采用 sketch 目录的 partitions.csv）
arduino-cli compile --fqbn "esp32:esp32:esp32s3:FlashSize=16M,PartitionScheme=custom" roast_bridge

# 上传（同样完整 fqbn；端口是板子 USB 口）
arduino-cli upload --fqbn "esp32:esp32:esp32s3:FlashSize=16M,PartitionScheme=custom" \
  -p /dev/cu.usbmodemXXXX roast_bridge
```

## 刷死（分区表错误）怎么救

**症状**：刷完重启串口反复打印
`partition N invalid - offset 0x10000 size 0x640000 exceeds flash chip size 0x400000` +
`boot: Failed to verify partition table`，循环重启，不连 WiFi。

**原因**：bootloader 被按错误 flash 容量（4MB）重写，与 16MB 分区表不匹配。

**救法（esptool 强制重写 bootloader，显式声明 16MB）**：

```bash
# 1. 用 arduino-cli 完整 fqbn 重新编译，产物在 ~/Library/Caches/arduino/sketches/<hash>/ 或 build/ 下
# 2. 直接 esptool 烧 4 区（关键：--flash-size 16MB 强制，不用 keep）
esptool --port /dev/cu.usbmodemXXXX --baud 921600 write-flash \
  --flash-mode dio --flash-freq 80m --flash-size 16MB \
  0x0 roast_bridge.ino.bootloader.bin \
  0x8000 roast_bridge.ino.partitions.bin \
  0xe000 boot_app0.bin \
  0x10000 roast_bridge.ino.bin
```

> ⚠️ 注意：带完整 fqbn 的 `arduino-cli upload` 因 flasher 用 `--flash-size keep` 模式，
> 在 bootloader 内容已被错误覆盖时会「No changed sectors」跳过，**不会自动修复**；
> 必须用上面 esptool 显式 `--flash-size 16MB` 强制重写。
> 4 个 bin 从编译产物目录取（bootloader.bin / partitions.bin / boot_app0.bin / 主 .ino.bin）。

## 手机配网（推荐，无需电脑）

固件不内置 WiFi 密码。首次上电、或想换 WiFi 时，用手机就能配网：

1. 板子紫灯闪烁 = 进入配网模式（首次上电自动进入；或 WiFi 连续连接失败约 100 秒后自动进入）
2. 手机 WiFi 列表会看到一个热点 **RoastBridge**（无密码）
3. 手机连上 RoastBridge，浏览器打开 **http://192.168.4.1**
4. 填入要连接的 WiFi 名和密码，点「连接」
5. 板子保存凭据到 NVS（断电不丢），自动重启并连接

## 串口配网（备选）

如需用串口配网（板子插电脑）：打开串口监视器（115200），依次输入第一行 WiFi 名、第二行密码即可。

## 安全与威胁模型（知情使用）

> 本固件定位**个人/家庭局域网设备**，未按公网对抗标准设计。请勿把桥接器端口暴露到公网或不可信网络。

| 面 | 现状 | 说明 |
|---|---|---|
| 热点口令 | `roast1234`（硬编码，见 `startApConfig`） | AP 配网热点固定口令；配网完成即回 STA，热点消失 |
| OTA 口令 | `OTA_PASSWORD`（`roastota`，硬编码，可改） | ArduinoOTA 同局域网可达，有口令可远程刷固件 |
| `/reset` | **无鉴权** | 同局域网任意设备 `GET http://<板子IP>:8898/reset` 即清 WiFi 凭据重启进配网（配网页已加固：仅精确路径触发，误请求不命中） |
| `/fan` | 无鉴权 | 同局域网可读/控风机（0-100%） |
| 温控器总线 | 无鉴权 | 同局域网可经桥接器读写 SV（8899/8897 端口） |

**结论**：攻击面 = 你的家庭局域网。若担心同网陌生人/访客网络，建议：不把桥接器接到访客 SSID；需要更强隔离时可自行在固件加 token（见 `set_config` 通道预留）。

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
