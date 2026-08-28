package com.roastcurve.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.roastcurve.app.App
import com.roastcurve.app.platform.registerForExit
import com.roastcurve.app.platform.RoastKeepService
import com.roastcurve.shared.AppDirs
import com.roastcurve.shared.BackPressHook
import com.roastcurve.shared.BackupBridge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerForExit()   // 供 Compose 层「不同意隐私政策→退出」使用
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
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        // 蓝牙权限（BLE 透传需要；拒绝不影响 WiFi 链路）
        val blePerms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                blePerms.add(Manifest.permission.BLUETOOTH_SCAN)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                blePerms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                blePerms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (blePerms.isNotEmpty()) {
            requestPermissions(blePerms.toTypedArray(), 1002)
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

    /** 文件选择器结果：读出字节与文件名交给调用方（备份导入 / Artisan 导入） */
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
        }
    }

    companion object {
        private const val REQ_IMPORT = 4101
    }
}
