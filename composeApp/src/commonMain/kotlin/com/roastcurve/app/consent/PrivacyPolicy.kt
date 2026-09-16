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

import com.roastcurve.shared.l10n.L10n

/**
 * 《隐私政策》内置全文（离线，不依赖网络）。
 *
 * 由各分节键在运行时拼装：这样正文也能跟随语言包切换，
 * 而不是写死一整段中文。分节键定义在 tools/lang/zh-CN.csv。
 *
 * 上架/合规要求：本节内容必须包含开发者名称、联系方式、生效日期，
 * 以及权限逐项用途说明（与 AndroidManifest 声明的权限一一对应）。
 * 改动权限声明时必须同步更新 privacy.perm_* 各条。
 */
/** 权限说明键（顺序即展示顺序，阅读页与弹窗共用） */
internal val PERMISSION_KEYS = listOf(
    "privacy.perm_net",
    "privacy.perm_wifi",
    "privacy.perm_ble",
    "privacy.perm_loc",
    "privacy.perm_notif",
    "privacy.perm_fg",
    "privacy.perm_storage",
    "privacy.perm_bridge",
)

/**
 * 弹窗用的纯文本摘要版全文（无 Markdown 标记）。
 * 阅读页用结构化渲染（见 PrivacyPolicyPage.Section），不走这里。
 */
internal val PRIVACY_POLICY_TEXT: String
    get() = buildString {
        fun section(titleKey: String, vararg bodyKeys: String) {
            appendLine(L10n.get(titleKey))
            bodyKeys.forEach { appendLine(L10n.get(it)) }
            appendLine()
        }

        appendLine(L10n.get("privacy.intro"))
        appendLine()

        section("privacy.h1", "privacy.p1")
        section("privacy.h2", "privacy.p2")
        section("privacy.h3", "privacy.perms_intro", *PERMISSION_KEYS.toTypedArray())
        section("privacy.h4", "privacy.p4")
        section("privacy.h5", "privacy.p5")
        section("privacy.h6", "privacy.p6")
        section("privacy.h7", "privacy.p7")
        section("privacy.h8", "privacy.p8")
    }
