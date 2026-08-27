package com.roastcurve.shared.storage

/** 应用存储根目录（平台实现） */
expect fun appStorageDir(): String

internal expect fun ensureDir(path: String)

internal expect fun writeFile(path: String, content: String)

internal expect fun readFile(path: String): String

internal expect fun deleteFile(path: String)

internal expect fun listFiles(dir: String): List<String>