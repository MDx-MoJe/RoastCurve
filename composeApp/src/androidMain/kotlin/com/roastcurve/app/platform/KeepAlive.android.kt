package com.roastcurve.app.platform

import com.roastcurve.shared.l10n.L10n
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.roastcurve.shared.AppDirs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 烘焙前台服务：真机会话期间常驻通知"烘焙进行中 · X 分钟"，
 * 抬高进程优先级防止部分系统锁屏杀后台；同时持有
 * CPU 唤醒锁 + WiFi 低延迟锁，保证轮询网络不被休眠掐断。
 *
 * 服务不碰会话数据，只负责保活——采集与落盘仍由界面层协程驱动。
 */
class RoastKeepService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiLock? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var startedAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startedAtMs = System.currentTimeMillis()
        postNotification()
        acquireLocks()
        // 每 30 秒刷新通知里的已进行时长
        scope.launch {
            while (isActive) {
                delay(30_000)
                postNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY   // 被系统回收后尽量拉起
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        // 4 小时兜底超时，防异常路径泄漏
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "roast:session").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        if (wm != null) {
            val mode = if (Build.VERSION.SDK_INT >= 29)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(mode, "roast:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun postNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, L10n.get("app.s2"), NotificationManager.IMPORTANCE_LOW).apply {
                    description = L10n.get("app.s3")
                    setShowBadge(false)
                }
            )
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val minutes = (System.currentTimeMillis() - startedAtMs) / 60_000
        val text = if (minutes <= 0) L10n.get("app.s4") else L10n.get("app.s5")
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n: Notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle(L10n.get("app.s6"))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFY_ID, n)
        }
    }

    companion object {
        private const val CHANNEL_ID = "roast_session"
        private const val NOTIFY_ID = 0x8EAC   // roast

        /** MainActivity 读它拦返回键；服务存活期间返回键不退出应用 */
        @Volatile
        var isRunning = false
            private set
    }
}

/** 启动前台服务（连接成功时调用） */
actual fun keepAliveStart() {
    val ctx = AppDirs.androidContext as? android.content.Context ?: return
    val intent = Intent(ctx, RoastKeepService::class.java)
    ctx.startForegroundService(intent)
}

/** 停止保活（断开连接时调用） */
actual fun keepAliveStop() {
    val ctx = AppDirs.androidContext as? android.content.Context ?: return
    ctx.stopService(Intent(ctx, RoastKeepService::class.java))
}
