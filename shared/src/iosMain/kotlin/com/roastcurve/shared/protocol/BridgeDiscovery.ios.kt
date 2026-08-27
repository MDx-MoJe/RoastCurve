package com.roastcurve.shared.protocol

/** iOS：桥接器 mDNS 发现待接入（需 Network 框架 NWBrowser / Bonjour） */
actual suspend fun discoverBridge(timeoutMs: Long): BridgeInfo? = null
