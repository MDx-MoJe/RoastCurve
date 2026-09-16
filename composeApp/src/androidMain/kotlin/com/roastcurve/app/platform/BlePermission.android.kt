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
 * 蓝牙权限申请桥（Android 实现）
 *
 * 共享 UI 代码无法直接触达 Manifest 与 ActivityResult API，
 * 由 MainActivity 在 onCreate 时注册真实实现（见 MainActivity.registerBlePermissionBridge）。
 */
actual object BlePermissionBridge {
    /** 发起申请（未注册时什么也不做） */
    actual var request: (() -> Unit)? = null

    /** 权限是否已齐备（仅查询，不申请） */
    actual var granted: (() -> Boolean)? = null

    /** 申请结果回调（true = 全部授予）；一次性使用，用完置空 */
    actual var onResult: ((Boolean) -> Unit)? = null

    /** 注册一次性结果回调，触发后自动清空 */
    fun consumeOneShotResult(cb: (Boolean) -> Unit) {
        onResult = { ok ->
            onResult = null
            cb(ok)
        }
    }
}
