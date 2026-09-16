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

package com.roastcurve.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.roastcurve.app.App
import com.roastcurve.app.platform.BlePermissionBridge
import com.roastcurve.app.platform.registerForExit
import com.roastcurve.app.platform.RoastKeepService
import com.roastcurve.shared.AppDirs
import com.roastcurve.shared.BackPressHook
import com.roastcurve.shared.BackupBridge

class MainActivity : ComponentActivity() {

    // 运行时权限结果（androidx.activity 1.9 起 ComponentActivity 不再有 onRequestPermissionsResult 回调，
    // 统一走 ActivityResult API；BLE 被拒时给一次性引导，避免静默不可用）
    private var lastPermRequest = 0   // 1=通知 2=BLE
    private val permLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.entries.any { !it.value }
            if (denied && lastPermRequest == 2) {
                android.widget.Toast.makeText(
                    this,
                    "蓝牙权限被拒绝：无法扫描配网设备。可到系统设置开启，或改用桥接器 IP 直连（WiFi 链路不受影响）",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            BlePermissionBridge.onResult?.invoke(!denied)
            lastPermRequest = 0
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerForExit()   // 供 Compose 层「不同意隐私政策→退出」使用
        // 蓝牙权限桥：配网页在用户主动点击时才会申请（不在启动时弹）
        BlePermissionBridge.request = { requestBluetoothPermissions() }
        BlePermissionBridge.granted = { hasBluetoothPermissions() }
        AppDirs.init(filesDir.absolutePath, applicationContext)   // 存储根目录注入
        com.roastcurve.shared.bridge.appContextBridge = applicationContext   // 豆袋互联桥上下文注入
        AppDirs.appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }
        AppDirs.buildIdentityLabel = SignatureGuard.identify(this).label   // 社区构建在设置页标注身份

        // 签名校验：防二次打包/换壳重签，不通过则阻断并退出
        if (!SignatureGuard.verify(this)) {
            SignatureGuard.showBlockDialog(this)
            return
        }

        // 通知权限（Android 13+ 运行时申请）：前台服务的"烘焙进行中"通知需要
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            lastPermRequest = 1
        }

        // 蓝牙权限不再在启动时索取：改为用户主动进入蓝牙配网页时申请（见 requestBluetoothPermissions）。
        // 上一个版本在 onCreate 里无条件申请，导致 Android 11 及以下每次启动都弹定位授权，
        // 已在 1.3.29 修正。

        // 恢复语言选择（在 UI 组合前应用，避免首帧语言闪变）
        runCatching {
            val st = kotlinx.coroutines.runBlocking { com.roastcurve.shared.storage.SettingsStore().load() }
            if (st.langPackFile.isNotBlank()) {
                val f = java.io.File(filesDir, "langpacks/" + st.langPackFile + ".json")
                if (f.exists()) {
                    com.roastcurve.shared.l10n.L10n.parsePack(f.readText())
                        .onSuccess { com.roastcurve.shared.l10n.L10n.applyPack(it) }
                }
            } else if (st.langBuiltin == "en") {
                com.roastcurve.shared.l10n.L10n.selectBuiltin(com.roastcurve.shared.l10n.L10n.BuiltinLang.EN)
            }
        }

        setContent {
            App()
        }

        // 备份导入：注册系统文件选择器发起器（设置页「从文件导入」触发）
        BackupBridge.requestPick = {
            val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "*/*"   // 不限 MIME：否则 zip 会被选择器置灰禁选
            }
            startActivityForResult(i, REQ_IMPORT)
        }
    }

    /** 返回键：先处理浮层关闭钩子；烘焙会话进行中禁止退出，防误触杀会话 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        BackPressHook.handler?.let { it(); return }
        if (RoastKeepService.isRunning) return
        super.onBackPressed()
    }

    /**
     * 申请蓝牙权限，供 UI 在用户主动进入蓝牙配网页时调用。
     * 用法说明：
     * - Android 12+ 需 BLUETOOTH_SCAN / BLUETOOTH_CONNECT；本应用已在清单中声明
     *   neverForLocation，因此系统不会把它当定位权限，也不会索要位置授权。
     * - Android 11 及以下的蓝牙扫描在系统层面绑定到定位权限（系统机制，无法绕过）。
     *   只有在旧系统上主动发起蓝牙扫描时才会询问，并且在询问前由 UI 说明用途。
     * - 定位权限仅用于蓝牙扫描，应用不读取、不存储、不上传任何位置信息。
     */
    fun requestBluetoothPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.isNotEmpty()) {
            lastPermRequest = 2
            permLauncher.launch(perms.toTypedArray())
        }
    }

    /** 文件选择器结果：读出字节与文件名交给调用方（备份导入 / Artisan 导入 / 语言包导入） */
    @Deprecated(
        "Deprecated in Java",
        ReplaceWith("super.onActivityResult(requestCode, resultCode, data)")
    )
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT) {
            var bytes: ByteArray? = null
            var nameHint: String? = null
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                if (uri != null) {
                    try {
                        bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (_: Exception) {}
                    try {
                        contentResolver.query(
                            uri,
                            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                            null, null, null
                        )?.use { c ->
                            if (c.moveToFirst()) nameHint = c.getString(0)
                        }
                    } catch (_: Exception) {}
                }
            }
            BackupBridge.onPicked?.invoke(bytes, nameHint)
        } else if (requestCode == com.roastcurve.app.settings.REQ_PICK_LANG) {
            var langBytes: ByteArray? = null
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                if (uri != null) {
                    try {
                        langBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (_: Exception) {}
                }
            }
            com.roastcurve.app.settings.handleLangPickResult(langBytes)
        }
    }

    /** 权限是否齐备（不申请，仅查询），供 UI 判断要不要先弹用途说明 */
    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        private const val REQ_IMPORT = 4101
    }
}
