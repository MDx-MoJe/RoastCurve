# RoastBridge 分区布局说明

## 版本兼容矩阵

| 板子 | 布局 | 可用固件 | 上限 |
|---|---|---|---|
| 4MB（N4） | default（默认 fqbn） | v1.3.x 及以前 + 安卓 App | 1.25MB app，v1.4+ 装不下 |
| 8MB（N8） | default_8MB | **v1.6.1 全功能** | 3.3MB app（当前用 37%） |
| 16MB（N16R8，推荐） | custom（仓库内 partitions.csv） | v1.6.1 全功能 + 未来扩展 | 6.4MB app（当前用 7%）+ 3.4MB 文件系统 |

## 16M 板（主推）
fqbn: `esp32:esp32:esp32s3:FlashSize=16M,PartitionScheme=custom`
sketch 目录的 `partitions.csv` 会被 platform.txt prebuild.3 自动采用。

> ⚠️ **upload/compile 必须带完整 fqbn**（含 `FlashSize=16M,PartitionScheme=custom`）。
> 只写 `esp32:esp32:esp32s3` 会按默认 4MB 重写 bootloader，与 16MB 分区表不匹配 →
> 开机死循环（`partition invalid ... exceeds flash chip size 0x400000`）。
> 若已踩：用 esptool 显式 `--flash-size 16MB` 强制重写 bootloader（勿用 arduino-cli upload，
> 它 keep 模式会跳过不修）。完整救砖命令见 README「刷死（分区表错误）怎么救」。（2026-09-06 实踩）

## 8M 板（兼容）
fqbn: `esp32:esp32:esp32s3:FlashSize=8M,PartitionScheme=default_8MB`
⚠️ 编译前把 sketch 目录的 `partitions.csv` 移走（否则 custom 表覆盖 8M 布局），
编译完再放回。产出与 16M 版功能完全一致（UI 走 PROGMEM，未用文件系统）。

## 4M 板（遗留）
v1.3.x 历史标签配合安卓 App 使用，AP 配网/Web UI/跟随等 v1.5+ 功能不可用。
