package org.wirl

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Finds paired computers with Android NSD (mDNS / DNS-SD): service type `_wirl._tcp`, matched by the TXT record
 * `id=<deviceId>`. Blocking; call it from a background thread. Returns nothing if discovery fails, so the
 * caller falls back to the QR code's addresses.
 */
class NsdDiscovery(context: Context, private val searchMs: Long = 3_000) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    fun find(computer: PairedComputer): List<SyncEndpoint> {
        val manager = nsd ?: return emptyList()
        val found = LinkedBlockingQueue<NsdServiceInfo>()
        val started = CountDownLatch(1)
        var running = false
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                running = true
                started.countDown()
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = started.countDown()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                found.offer(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }
        try {
            manager.discoverServices(SyncProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            return emptyList()
        }
        try {
            started.await(searchMs, TimeUnit.MILLISECONDS)
            if (!running) return emptyList()
            val deadline = System.currentTimeMillis() + searchMs
            // Resolve one service at a time: older Android versions reject a second resolve while one is running.
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                val info = found.poll(left, TimeUnit.MILLISECONDS) ?: break
                val resolved = resolve(manager, info) ?: continue
                val id = resolved.attributes["id"]?.let { String(it, Charsets.UTF_8) }
                @Suppress("DEPRECATION")
                val host = resolved.host
                if (id.equals(computer.deviceId, ignoreCase = true) && host != null) {
                    return listOf(SyncEndpoint(host, resolved.port))
                }
            }
            return emptyList()
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(manager: NsdManager, info: NsdServiceInfo): NsdServiceInfo? {
        val done = CountDownLatch(1)
        var result: NsdServiceInfo? = null
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = done.countDown()
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                result = serviceInfo
                done.countDown()
            }
        }
        runCatching { manager.resolveService(info, listener) }.onFailure { return null }
        done.await(2_000, TimeUnit.MILLISECONDS)
        return result
    }
}
