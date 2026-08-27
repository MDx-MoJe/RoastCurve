package com.roastcurve.app.consent

/**
 * 隐私政策配置（唯一配置点）
 *
 * - 条款实质性变化时 POLICY_VERSION +1，老用户下次启动自动重弹
 * - 在线链接预留给未来内外网分发：为空字符串时不显示网页入口，
 *   仅展示内置全文；届时外网包填 GitHub Pages 地址、国内包填 Gitee Pages 地址
 */
object ConsentConfig {
    const val POLICY_VERSION = 1

    /** 外网分发渠道在线版地址（GitHub Pages），空 = 不显示该入口 */
    const val PRIVACY_URL_EXTERNAL = ""

    /** 国内分发渠道在线版地址（Gitee Pages 等），空 = 不显示该入口 */
    const val PRIVACY_URL_CN = ""
}
