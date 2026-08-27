package com.roastcurve.shared.protocol

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android：用 NsdManager（网络服务发现）解析桥接器的 mDNS 广播。
 * 固件用 ESPmDNS 注册了主机名 `roastbridge`，服务类型 `_roastbridge._tcp`。
 */
actual suspend fun discoverBridge(timeoutMs: Long): BridgeInfo? = withContext(Dispatchers.Main) {
    val context = com.roastcurve.shared.AppDirs.androidContext as? Context
        ?: return@withContext null

    val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        ?: return@withContext null

    val result = CompletableDeferred<BridgeInfo?>()

    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onServiceFound(info: NsdServiceInfo) {
            // 服务类型匹配后再解析（resolve 拿到真实 host/IP）
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress
                    if (!host.isNullOrBlank() && !result.isCompleted) {
                        result.complete(BridgeInfo(host, resolved.serviceName))
                    }
                }
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
            })
        }
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            if (!result.isCompleted) result.complete(null)
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    try {
        nsd.discoverServices("_roastbridge._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    } catch (_: Exception) {
        return@withContext null
    }

    val found = withTimeoutOrNull(timeoutMs) { result.await() }
    runCatching { nsd.stopServiceDiscovery(listener) }
    found
}
