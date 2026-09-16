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

package com.roastcurve.shared

/**
 * 平台上下文注入
 * - androidMain: MainActivity 初始化 filesDir + androidContext
 * - iosMain: 初始化 documentsDir
 */
object AppDirs {
    lateinit var filesDir: String
        internal set

    /** Android 专用：用于分享等需要 Context 的场景 */
    var androidContext: Any? = null
    var appVersion: String = "?"

    /**
     * 构建身份标注（官方发布为空串，社区构建显示「社区构建」）。
     * 由 androidApp 启动时通过 SignatureGuard.identify() 注入；
     * iOS 等未注入平台默认空串不显示。
     */
    var buildIdentityLabel: String = ""

    fun init(filesDir: String, androidContext: Any? = null) {
        this.filesDir = filesDir
        this.androidContext = androidContext
    }
}
