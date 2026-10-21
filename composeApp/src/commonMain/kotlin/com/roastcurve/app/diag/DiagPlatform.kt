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

package com.roastcurve.app.diag

/**
 * 打包多文件 zip（诊断包用）。
 * 按项目约定，凡导出物一律走 zip 容器（内含固定名文本文件），不裸抛 JSON。
 */
expect fun packTextZip(files: List<Pair<String, String>>): ByteArray?

/** 设备与 App 版本信息（诊断包头部），失败返回空串 */
expect fun diagDeviceInfo(): String
