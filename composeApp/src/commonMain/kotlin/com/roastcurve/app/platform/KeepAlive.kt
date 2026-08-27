package com.roastcurve.app.platform

/**
 * 烘焙会话保活：真机连接期间调用 start，断开时调用 stop。
 * Android 实现为前台服务（常驻通知"烘焙进行中"）+ CPU/WiFi 锁；
 * 其他平台空实现。
 */
expect fun keepAliveStart()

expect fun keepAliveStop()
