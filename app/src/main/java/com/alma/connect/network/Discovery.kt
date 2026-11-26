package com.alma.connect.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

class Discovery(ctx: Context) {
    private val m = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val l = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(s: NsdServiceInfo) {
            m.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val h = i.host?.hostAddress ?: return
                    Hub.found.value = Hub.found.value.filter { it.host != h } + Found(i.serviceName, h)
                }
            })
        }
        override fun onServiceLost(s: NsdServiceInfo) { Hub.found.value = Hub.found.value.filter { it.name != s.serviceName } }
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, e: Int) {}
        override fun onStopDiscoveryFailed(t: String, e: Int) {}
    }
    fun start() { Hub.found.value = emptyList(); m.discoverServices(Hub.TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    fun stop() { runCatching { m.stopServiceDiscovery(l) } }
}
