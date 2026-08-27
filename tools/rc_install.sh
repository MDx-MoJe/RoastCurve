#!/bin/bash
# 烤豆 App 一键安装（自动处理厂商安全确认弹窗 + 真伪校验）
# 用法: tools/rc_install.sh   默认推所有在线设备；apk 自动取最新构建产物
DIR="$(cd "$(dirname "$0")/.." && pwd)"
APK=$(ls -t "$DIR"/androidApp/build/outputs/apk/release/*.apk 2>/dev/null | head -1)
if [ -z "$APK" ]; then
    echo "❌ 未找到已构建的 APK，请先 assembleRelease"
    exit 1
fi
echo "使用安装包: $(basename "$APK")"
exec python3 "$DIR/tools/rc_install.py" "$APK"
