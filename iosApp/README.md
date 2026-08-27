# iOS 端

Xcode 工程已生成（基于 JetBrains Compose Multiplatform 官方模板改造）。

## 结构

```
iosApp/
├── Configuration/Config.xcconfig   # BUNDLE_ID / APP_NAME / TEAM_ID
├── iosApp.xcodeproj/
└── iosApp/
    ├── ContentView.swift           # ComposeView 桥接 → MainViewControllerKt.MainViewController()
    ├── iOSApp.swift                # SwiftUI 入口
    ├── Info.plist
    └── Assets.xcassets
```

Kotlin 侧入口桥接：`composeApp/src/iosMain/kotlin/com/roastcurve/app/MainViewController.kt`
使用 `ComposeUIViewController { App() }`（CMP 1.7 API）。
注意：@Composable 函数经编译器变换后无法直接从 Swift 调用，必须经过此桥接。

## 命令行构建（模拟器）

```bash
cd iosApp
xcodebuild -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath build/dd build

# 启动模拟器并安装
xcrun simctl boot "iPhone 17 Pro"
xcrun simctl install "iPhone 17 Pro" "build/dd/Build/Products/Debug-iphonesimulator/烤豆.app"
xcrun simctl launch "iPhone 17 Pro" com.roastcurve.ios
```

构建时会自动触发 Gradle 任务 `:composeApp:embedAndSignAppleFrameworkForXcode`
编译 iOS framework 并放入 `composeApp/build/xcode-frameworks/`。

## 部署到真机（需本人操作一次）

1. Xcode 打开 `iosApp/iosApp.xcodeproj`
2. 选择 iPhone 目标设备
3. Target `iosApp` → Signing & Capabilities：
   - Team 选择个人 Apple ID（免费账号，7 天有效期）或付费开发者账号
4. 点击 Run

免费签名 7 天过期后重复步骤 3-4 即可。
