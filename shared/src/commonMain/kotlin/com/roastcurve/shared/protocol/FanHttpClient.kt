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

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode

/**
 * 跨平台创建轻量 HTTP 客户端（Android=OkHttp / iOS=Darwin）。
 * 用途：RoastBridge 状态口（8898）的 /fan 风速控制等 HTTP 指令。
 * 短超时：风速指令是一次性小请求，失败交给上层重试/下拍覆盖，不阻塞轮询节奏。
 */
expect fun createFanHttpClient(): HttpClient

/**
 * 向 RoastBridge 发送风速指令：GET http://<host>:8898/fan?speed=NN（NN=0-100）
 * @return true=HTTP 2xx（固件已接受）；false=网络失败/超时/非 2xx
 */
suspend fun sendFanSpeed(client: HttpClient, host: String, speed: Int): Boolean {
    val v = speed.coerceIn(0, 100)
    return try {
        val resp = client.get("http://$host:8898/fan?speed=$v")
        resp.status in HttpStatusCode.OK..HttpStatusCode(299, "")
        // 解析 body 校验固件回显（fan_speed 一致）更稳，但 2xx 足够：
        // 固件 /fan 只可能返回 200 JSON，失败则连接异常走 catch
    } catch (e: Exception) {
        false
    }
}
