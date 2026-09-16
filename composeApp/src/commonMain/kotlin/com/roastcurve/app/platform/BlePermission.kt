/*
 * RoastCurve（烤豆）—— 咖啡烘焙曲线记录与控制
 * Copyright 2026 MDx
 * https://github.com/MDx-MoJe/RoastCurve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.roastcurve.app.platform

/**
 * 蓝牙权限申请桥（跨平台接口）
 *
 * 背景：Android 12 起蓝牙扫描需要 BLUETOOTH_SCAN/BLUETOOTH_CONNECT，
 * Android 11 及以下由系统机制绑定到 ACCESS_FINE_LOCATION。
 * 申请必须发生在用户主动使用蓝牙配网时，不能在启动瞬间弹。
 *
 * 共享 UI 代码无法直接触达 Manifest/ActivityResult，故由各平台实现：
 * - Android：MainActivity 注册真实实现（见 MainActivity.requestBluetoothPermissions）
 * - iOS：蓝牙配网暂未实现，恒返回「已授权」，不弹任何系统框
 */
expect object BlePermissionBridge {
    /** 发起权限申请（未注册时什么也不做） */
    var request: (() -> Unit)?

    /** 权限是否已齐备（仅查询，不申请） */
    var granted: (() -> Boolean)?

    /** 申请结果回调（true = 全部授予）；一次性使用，用完置空 */
    var onResult: ((Boolean) -> Unit)?
}
