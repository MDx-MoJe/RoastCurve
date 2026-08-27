package com.roastcurve.android

import android.app.AlertDialog
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * 签名校验（防二次打包/换壳重签盗版的第一道防线）
 *
 * 原理：运行时读取当前 APK 的签名证书 SHA-256 指纹，与官方指纹比对。
 * 攻击者把 APK 解包改包名、换成自己的证书重签名后，指纹必然不同 → 校验失败。
 *
 * 注意：
 * 1. 这是「抬高成本」的手段，不是绝对安全，攻击者可定位并 patch 掉本校验；
 *    与后续的混淆/加固/Native 化叠加才能形成有效防线。
 * 2. debug 构建（FLAG_DEBUGGABLE）自动跳过校验，因为 debug 用调试证书。
 */
object SignatureGuard {

    /** 官方 release 证书 SHA-256 指纹（构建时从 keystore.properties 注入；开源用户自构建为空则跳过校验） */
    private val officialSha256 = BuildConfig.OFFICIAL_SHA256

    /**
     * @return true=签名合法（或 debug 构建 / 未注入官方指纹）；false=被重签/篡改
     */
    fun verify(context: Context): Boolean {
        // debug 构建跳过校验
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            return true
        }
        // 未注入官方指纹（开源用户自构建 release）：跳过校验
        if (officialSha256.isBlank()) {
            return true
        }
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners?.toList() ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                info.signatures?.toList() ?: emptyList()
            }
            certs.any { cert -> sha256(cert.toByteArray()) == officialSha256 }
        } catch (_: Exception) {
            false   // 读取失败按不通过处理（宁可误伤不可放过）
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(":") { b ->
            (b.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
        }
    }

    /** 校验失败时弹出阻断对话框，关闭即退出 */
    fun showBlockDialog(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("非官方版本")
            .setMessage("检测到应用被篡改或重新签名，已停止运行。请从官方渠道下载安装。")
            .setCancelable(false)
            .setPositiveButton("退出") { _, _ ->
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            .show()
    }
}
