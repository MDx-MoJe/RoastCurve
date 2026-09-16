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

package com.roastcurve.shared.protocol

/**
 * 桥接器自动发现（mDNS / Bonjour）。
 * 固件连上 WiFi 后注册了 `roastbridge.local`（服务类型 `_roastbridge._tcp`），
 * App 用 mDNS 解析出真实 IP，配网完成后自动填写、免手动找 IP。
 */

/** mDNS 发现结果：桥接器的局域网 IP */
data class BridgeInfo(val host: String, val serviceName: String)

/**
 * 发现桥接器 IP。
 * @param timeoutMs 最多等待时长（桥接器配网后需几秒重启连 WiFi）
 * @return 找到的 IP，找不到返回 null
 */
expect suspend fun discoverBridge(timeoutMs: Long): BridgeInfo?
