# 烤豆 RoastCurve

[English](README_EN.md) | 简体中文

[![License](https://img.shields.io/badge/code%20license-Apache%202.0-blue)](LICENSE) [![Trademark](https://img.shields.io/badge/name%20%26%20logo-%E4%B8%8D%E5%9C%A8%E6%8E%88%E6%9D%83%E8%8C%83%E5%9B%B4-important)](NOTICE.txt)
[![Platform](https://img.shields.io/badge/platform-Android-brightgreen)](#)
[![iOS](https://img.shields.io/badge/iOS-开发中-yellow)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin%20Multiplatform-Compose-orange)](#)

移动端咖啡烘焙实时监控与曲线设计 App，面向 DIY 热风烘豆机，兼容 Artisan 生态。

> **Android 已可用，iOS 正在进行中，敬请期待。**

CoffeeBeanTracker（豆袋，管豆子库存）的姊妹应用：

```
豆袋 CoffeeBeanTracker → 管豆子（库存、记录、杯测）→ GitHub: github.com/MDx-MoJe/CoffeeBeanTracker · Gitee: gitee.com/MDx-MoJe/coffee-bean-tracker
烤豆 RoastCurve       → 管曲线（监控、设计、导出）
```

两 App 通过 Android ContentProvider（内容提供器）互联：烘完一炉，豆袋自动「扣生豆 + 熟豆入库」。

## 界面预览

实时监控主界面：双栏仪表盘，豆温 / 时间 / RoR 实时卡片，烘焙阶段统计，手动或跟随曲线自动烘焙，底部事件按钮一键标记入豆、黄点、一爆、出豆。

<img src="docs/monitor_overview.png" alt="烤豆·监控 实时监控界面" width="360"/>

## 技术栈

| 层 | 选型 |
|---|---|
| 框架 | Kotlin Multiplatform + Compose Multiplatform |
| 图表 | Compose Canvas 手写渲染（双 Y 轴 / 阶段着色 / 实时滚动） |
| 设备协议 | MODBUS TCP（Wi-Fi 转 RS485 设备 / 自制 ESP32 固件）、TCP 透传、BLE 透传 |
| 参考协议 | Artisan WebSocket JSON、简易串口文本 |
| 网络 | Ktor Client (OkHttp / Darwin) |
| 序列化 | kotlinx.serialization |

## 模块结构

```
RoastCurve/
├── shared/            # 跨平台共享模块
│   ├── model/         # 数据模型（RoastRecord、CurvePoint、EventMarker…）
│   ├── protocol/      # 设备通信抽象 + Modbus TCP/RTU 透传/BLE 实现
│   └── math/          # RoR 计算、EMA 平滑、阶段检测、DTR
├── design-system/     # 设计系统（暖米色/深色主题，与 CoffeeBeanTracker 同风格）
├── composeApp/        # Compose UI
│   ├── chart/         # 手写 Canvas 烘焙曲线图
│   ├── monitor/       # 实时监控面板（跟随曲线、事件标记、阶段统计）
│   └── util/          # 跨平台格式化工具
├── androidApp/        # Android 入口
├── iosApp/            # iOS 入口（Xcode 工程，开发中，见其 README）
```

## 📦 最新发布（2026-09-06）

**App v1.3.20**（versionCode 158）· **桥接固件 v1.8.3** · **Web UI 大版本更新**

建议同时更新 App 与固件：

### 跟随曲线：自主执行

- **曲线自主跑完**：不再因豆温与目标偏差中断
- **走完自动回落**：SV 降到 25°C、风机 25%
- **回落值可调**：设置 →「跟随结束回落」，温度 20~120°C、风机 0~100%
- **温度硬保护**：实测 ≥250°C 持续 5s 自动中断回落

### Web UI（浏览器控制台）：全面重构

- **浏览器直接烘焙**：连板子 WiFi 后打开网页即可监控/控制/记录
- **记录不丢**：刷新/锁屏/换设备后自动恢复计时、事件与曲线
- **手绘豆温曲线**：点加锚点、拖动调点、实时看锚点温度/时间，存为模板直接跟随
- **模板生态**：本炉存模板、Artisan .alog 导入、模板管理，与 App 互通
- **风机实时控制**：滑块即生效
- **导出 zip**：标准 zip 格式，兼容导入
- **信号强度显示** + 曲线事件标记点

### GPIO 自定义（换板不换线）

- **TX/RX/风机三脚可配**：App 设置 → 桥接器 GPIO 引脚，或 Web UI 设置 → GPIO 引脚配置，重启生效
- 可用池已排除硬件占用脚（flash 26-32 / PSRAM 33-37 / USB 19-20 / 争议脚 1,3,45,46），三脚互斥
- 改错失联？长按 BOOT 键 3 秒恢复默认（17/18/2）

### 安全修复

- **锁屏/断连不再死锁**：跟随中主控断开后，新页面可正常「接管跟随」/「停止跟随」
- **看门狗倒计时修复**：不再出现异常超长倒计时

### ⚠️ 注意事项

- **固件与 App 建议一起更新**
- **4MB flash 板子已不支持**（v1.4 起），请用 8MB 或 16MB 板
- **跟随中锁屏/断线 = 炉子继续按曲线跑**，跑完自动回落 25°C，回来接管即可
- Web UI 与 App 是**两套独立跟随入口**，同一时间只用一端
- **GPIO 配置需固件 v1.8.1+**，旧固件会提示升级，需 USB 刷 v1.8.3

## 下载

不想自己构建？直接下载现成的（发布包见本仓库「Releases / 发行版」页面）：

- **APK**：Android 直接安装
- **固件 .bin**：ESP32 桥接器刷写文件（配合 esp32-firmware/ 使用）

> 🆕 第一次上手？从下载到第一炉咖啡的完整步骤见 **[《快速上手》](docs/快速上手.md)**（接线 / 刷固件 / 配网 / 连接）。

## 构建

```bash
# Android Debug APK
./gradlew :androidApp:assembleDebug
# 产物: androidApp/build/outputs/apk/debug/androidApp-debug.apk

# 编译验证 iOS（模拟器架构）
./gradlew :composeApp:compileKotlinIosSimulatorArm64
```

环境要求：JDK 17+、Android SDK 34、Xcode 15+（iOS 端）

> **自构建说明**：debug 构建无需签名配置，直接可用。如需构建 release，请复制 `keystore.properties.example` 为 `keystore.properties` 并填入你自己的 keystore；`officialSha256` 留空则跳过运行时签名校验（开源自构建默认行为）。

## 当前状态

核心功能已完成，真机验证通过。

- [x] 实时监控：Modbus TCP 真机采集、跟随曲线自动烘焙、事件标记、阶段统计、RoR
- [x] 多协议链路：Modbus TCP（Wi-Fi 转 RS485 设备 / 自制 ESP32 固件）、TCP 透传、BLE 透传
- [x] 记录系统：历史记录、失重率、CSV/JSON 导出、数据备份/导入
- [x] 模板系统：历史提取、Artisan .alog 导入（含事件节点）、自定义锚点编辑器
- [x] 跟随曲线自主执行：曲线跑完自动回落、回落值可调、温度硬保护
- [x] 桥接器 Web UI：浏览器直接烘焙、固件自主记录刷新不丢、手绘曲线、zip 导出
- [x] 桥接器 GPIO 自定义：TX/RX/风机引脚可配（换板不换线）
- [x] 折叠屏双栏仪表盘、内置使用手册、前台服务保活 + 会话恢复
- [x] 桥接器信号强度实时显示、上次 IP 记忆
- [x] Apache 2.0 开源（签名密码分离、开源自构建免校验）
- [ ] iOS 端（开发中，敬请期待）
- [ ] lookahead 前瞻量实机调参

## 自制 ESP32 桥接固件（esp32-firmware/）

为替代成品 Wi-Fi 转 RS485 设备而写，App 端零改动只需换 IP。特性：

- 完整复刻 Modbus TCP↔RTU 网关行为（非裸透传），无应答/CRC 错回标准异常帧不卡死
- 串口配网（首次烧录后串口输入 WiFi，凭据存 NVS 断电不丢，换网络免重烧）
- OTA 无线升级（重刷固件走 WiFi，免拆机插 USB）
- HTTP 状态口 `:8898` 返回 JSON（`rssi`/`uptime`/`client`），App 据此显示信号强度
- WS2812 状态灯：绿=就绪、青=通信中、红=重连、紫闪=待配网
- 详见 [esp32-firmware/README.md](esp32-firmware/README.md)

## 协议约定（主力：MODBUS TCP — 真机验证通过）

实测链路：`温控器 --RS485--> 桥接设备(TCP:8899, Modbus网关模式) --> 烤豆App`

| 参数 | 值 |
|---|---|
| 从站 ID | 1 |
| 功能码 | FC03 读保持寄存器 / FC06 写单寄存器 |
| **PV 实时豆温** | 寄存器 `0x0000`，uint16 大端，整数℃ |
| SV 设定温度 | 寄存器 `0x0002`（可写入下发） |
| PID 参数 | 0x0009=P, 0x000A=I, 0x000B=D |
| 对照验证 | App 读数与面板 PV 完全同步（31↔31） |

请求帧示例：`00 01 00 00 00 06 01 03 00 02 00 01`
响应帧示例：`00 01 00 00 00 05 01 03 02 00 E3`（=227℃）

工具：`tools/probe_tc4s.py <IP>` 可独立探测连通性。

## 开源协议与版权

**代码授权**：本项目（App + ESP32 固件）采用 [Apache License 2.0](LICENSE) 协议开源，可自由使用、学习、修改与再分发。

**不在授权范围**：应用名称「烤豆 / RoastCurve」、应用图标、官方签名证书不受许可证保护，其权利归 MDx 所有。任何 fork（分叉再发布）必须改名换图标，并在显著位置注明来源；以原名称或原图标分发、上架即构成侵权。详见 [NOTICE.txt](NOTICE.txt)。

**自构建说明**：开源用户自构建的版本功能完整、不受任何限制，应用内会标注「社区构建」以示与官方发布的区别；这不影响任何使用。

**辨识官方版**：官方安装包的签名指纹为 `A9:2E:...:64:7D`（完整值见 [NOTICE.txt](NOTICE.txt)），可通过包名 `com.roastcurve.*` 与签名指纹核对真伪。

## 支持开发者

烤豆永久免费、开源、无广告。如果你喜欢它，欢迎通过 [爱发电](https://afdian.com/a/RoastCurve) 支持，或给仓库点个 Star ⭐。详见 [SPONSOR.md](SPONSOR.md)。

Copyright © 2026 MDx
