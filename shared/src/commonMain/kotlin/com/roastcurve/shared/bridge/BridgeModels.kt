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

package com.roastcurve.shared.bridge

/**
 * 豆袋互联数据（commonMain 定义，平台无关）
 */
data class GreenBeanSummary(
    val id: Long,
    val name: String,
    val remainingGrams: Double,
)

/** 推送结果 */
sealed interface BridgeResult {
    data class Ok(val message: String) : BridgeResult
    data class Err(val message: String) : BridgeResult
}
