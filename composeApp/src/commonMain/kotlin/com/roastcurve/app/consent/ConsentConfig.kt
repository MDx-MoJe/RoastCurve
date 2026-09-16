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

package com.roastcurve.app.consent

/**
 * 隐私政策配置（唯一配置点）
 *
 * - 条款实质性变化时 POLICY_VERSION +1，老用户下次启动自动重弹
 * - 在线链接预留给未来内外网分发：为空字符串时不显示网页入口，
 *   仅展示内置全文；届时外网包填 GitHub Pages 地址、国内包填 Gitee Pages 地址
 */
object ConsentConfig {
    /**
     * 政策版本。
     * v1 → v2（2026-09-11）：补全开发者名称、联系方式、生效日期与权限逐项用途说明，
     * 并新增应用内常驻阅读入口（应用商店合规要求）。
     */
    const val POLICY_VERSION = 2

    /**
     * 外网分发渠道在线版地址（GitHub Pages）。
     * 注意：github.io 在国内网络环境无法直接访问，国内分发时请改用 PRIVACY_URL_CN。
     */
    const val PRIVACY_URL_EXTERNAL = "https://mdx-moje.github.io/RoastCurve/privacy.html"

    /**
     * 国内分发渠道在线版地址（Gitee Pages 等），空 = 不显示该入口。
     * 待 Gitee Pages 上线后填入。
     */
    const val PRIVACY_URL_CN = ""
}
