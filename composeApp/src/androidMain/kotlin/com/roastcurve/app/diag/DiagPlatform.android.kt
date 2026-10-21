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

import android.content.Context
import android.os.Build

actual fun packTextZip(files: List<Pair<String, String>>): ByteArray? = try {
    val bos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(bos).use { zos ->
        files.forEach { (name, content) ->
            zos.putNextEntry(java.util.zip.ZipEntry(name))
            zos.write(content.toByteArray())
            zos.closeEntry()
        }
    }
    bos.toByteArray()
} catch (_: Exception) {
    null
}

actual fun diagDeviceInfo(): String {
    val ctx = com.roastcurve.shared.AppDirs.androidContext as? Context
    val appVer = try {
        ctx?.packageManager?.getPackageInfo(ctx.packageName, 0)?.let {
            val code = if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
            "${it.versionName} ($code)"
        } ?: "?"
    } catch (_: Exception) {
        "?"
    }
    return buildString {
        appendLine("app_version = $appVer")
        appendLine("android = ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("model = ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("brand = ${Build.BRAND} / ${Build.DEVICE}")
        appendLine("locale = ${java.util.Locale.getDefault()}")
    }
}
