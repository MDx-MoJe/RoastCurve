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
 * 非 Android 平台桩：iOS 端暂无豆袋互联能力
 */
private object UnsupportedBridge : BeanBagBridge {
    override suspend fun listGreenBeans(): Result<List<GreenBeanSummary>> =
        Result.failure(IllegalStateException("当前平台不支持豆袋互联"))
    override suspend fun consume(roastId: String, greenBeanId: Long, grams: Double): BridgeResult =
        BridgeResult.Err("当前平台不支持豆袋互联")
    override suspend fun addRoasted(
        roastId: String,
        beanName: String,
        roastedGrams: Double,
        roastLevel: String,
        roastDateEpochMs: Long,
    ): BridgeResult = BridgeResult.Err("当前平台不支持豆袋互联")
}

actual fun beanBagBridge(): BeanBagBridge = UnsupportedBridge
