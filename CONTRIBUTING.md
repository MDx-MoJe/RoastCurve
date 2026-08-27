# 贡献指南

感谢你对烤豆（RoastCurve）的关注。这是一个面向 DIY 咖啡烘焙爱好者的开源项目，欢迎任何形式的贡献。

## 项目结构

```
shared/         # Kotlin Multiplatform 共享层（协议、模型、存储、数学）
composeApp/     # Compose Multiplatform UI（监控、历史、模板、设置）
design-system/  # 设计系统（暖米色 / 深色主题）
androidApp/     # Android 入口
iosApp/         # iOS 入口（Xcode 工程）
esp32-firmware/ # ESP32-S3 桥接固件（Modbus TCP ↔ RS485 网关）
tools/          # 探测 / 安装等开发辅助脚本
docs/           # 使用手册
```

## 构建

```bash
# Android Debug APK
./gradlew :androidApp:assembleDebug

# iOS 编译验证（模拟器架构）
./gradlew :composeApp:compileKotlinIosSimulatorArm64
```

环境：JDK 17+、Android SDK 34、Xcode 15+（iOS 端）。

## 提交规范

- 一个提交做一件事，提交信息说清「做了什么」和「为什么」
- 涉及版本升级时，同时更新 `androidApp/build.gradle.kts` 里的 `versionCode`（自增 1）与 `versionName`
- 不要提交任何密钥、密码、本地路径：`keystore.properties`、`local.properties`、Xcode 的 `xcuserdata/` 均已在 `.gitignore` 中

## 签名与隐私

- 自构建无需签名：复制 `keystore.properties.example` 为 `keystore.properties`，`officialSha256` 留空即可跳过运行时签名校验
- 本应用为纯本地应用，不收集、不上传任何数据，详见内置《隐私政策》

## 许可

本项目采用 [Apache License 2.0](LICENSE)。提交即视为同意以该协议授权你的贡献。
