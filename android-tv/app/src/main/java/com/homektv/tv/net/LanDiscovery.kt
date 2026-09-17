package com.homektv.tv.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections
import java.io.Closeable

/**
 * 局域网服务发现器，按 mDNS、UDP 和子网扫描顺序查找 Home KTV 服务。
 *
 * Discovers Home KTV servers on the LAN using mDNS, UDP, and subnet scanning.
 */
class LanDiscovery(context: Context) : Closeable {
    /** Discovery stages reported to the UI. / 向界面报告的发现阶段。 */
    enum class Stage { MDNS, UDP, SUBNET }

    private val appContext = context.applicationContext
    private val scanner = LanScanner()

    override fun close() {
        scanner.close()
    }

    /**
     * Recovery path: stop at the first valid result from the highest-priority
     * discovery mechanism. The full setup scan still uses discoverAll().
     */
    suspend fun discoverPreferred(): List<DiscoveredServer> = discoverPreferred(expectedInstanceId = null)

    /**
     * Recovery discovery is identity-bound. Unrelated results from a higher
     * priority stage must not suppress the lower-priority stages.
     */
    suspend fun discoverPreferred(expectedInstanceId: String?): List<DiscoveredServer> {
        if (expectedInstanceId != null) {
            val expected = DiscoveryProtocol.normalizeInstanceId(expectedInstanceId) ?: return emptyList()
            val mdns = discoverMdns { }
            val mdnsMatches = DiscoveryIdentityPolicy.firstMatching(expected, mdns)
            if (mdnsMatches.isNotEmpty()) return mdnsMatches

            val udp = discoverUdp { }
            val udpMatches = DiscoveryIdentityPolicy.firstMatching(expected, udp)
            if (udpMatches.isNotEmpty()) return udpMatches

            val scanned = scanner.scanAll()
            return DiscoveryIdentityPolicy.firstMatching(expected, scanned)
        }
        val mdns = discoverMdns { }
        if (!DiscoveryFallbackPolicy.shouldRunFallback(mdns.size)) return mdns

        val udp = discoverUdp { }
        if (!DiscoveryFallbackPolicy.shouldRunFallback(udp.size)) return udp

        return scanner.scanAll()
    }

    /**
     * 执行全部发现策略并合并去重结果。
     *
     * Runs all discovery strategies and returns deduplicated server results.
     */
    suspend fun discoverAll(
        onStage: ((Stage) -> Unit)? = null,
        onProgress: ((Int, Int) -> Unit)? = null,
        onDiscovered: ((DiscoveredServer) -> Unit)? = null,
    ): List<DiscoveredServer> {
        val found = linkedMapOf<String, DiscoveredServer>()

        fun collect(server: DiscoveredServer) {
            val isNew = synchronized(found) {
                if (found.containsKey(server.hostPort)) false else {
                    found[server.hostPort] = server
                    true
                }
            }
            if (isNew) onDiscovered?.invoke(server)
        }

        onStage?.invoke(Stage.MDNS)
        discoverMdns(::collect)
        onStage?.invoke(Stage.UDP)
        discoverUdp(::collect)
        onStage?.invoke(Stage.SUBNET)
        scanner.scanAll(onProgress) { server ->
            collect(server)
        }
        return synchronized(found) { found.values.toList() }
    }

    private suspend fun discoverMdns(onFound: (DiscoveredServer) -> Unit): List<DiscoveredServer> =
        coroutineScope {
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifi?.createMulticastLock("home-ktv-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val stopped = CompletableDeferred<Unit>()
        val results = Collections.synchronizedMap(linkedMapOf<String, DiscoveredServer>())
        val scope = CoroutineScope(coroutineContext)
        lateinit var listener: NsdManager.DiscoveryListener
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { stopped.complete(Unit) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.startsWith(DiscoveryProtocol.SERVICE_TYPE)) return
                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val address = serviceInfo.host?.hostAddress ?: return
                        val formattedHost = if (':' in address) "[$address]" else address
                        val rawInstanceId = serviceInfo.attributes["instanceId"]
                            ?.toString(Charsets.UTF_8)
                        val instanceId = when {
                            rawInstanceId == null -> null
                            else -> DiscoveryProtocol.normalizeInstanceId(rawInstanceId) ?: return
                        }
                        val server = DiscoveredServer(
                            hostPort = "$formattedHost:${serviceInfo.port}",
                            name = serviceInfo.serviceName.trim().ifEmpty { address },
                            instanceId = instanceId,
                        )
                        scope.launch {
                            val validated = validateDiscovered(server) ?: return@launch
                            if (validated.hostPort == server.hostPort) {
                                val isNew = synchronized(results) {
                                    if (results.containsKey(server.hostPort)) false else {
                                        results[server.hostPort] = validated
                                        true
                                    }
                                }
                                if (isNew) onFound(validated)
                            }
                        }
                    }
                })
            }
        }
        try {
            nsd.discoverServices(DiscoveryProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(MDNS_TIMEOUT_MS) { stopped.await() }
            synchronized(results) { results.values.toList() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            if (multicastLock?.isHeld == true) multicastLock.release()
        }
    }

    private suspend fun discoverUdp(onFound: (DiscoveredServer) -> Unit): List<DiscoveredServer> =
        coroutineScope {
            val candidates = withContext(Dispatchers.IO) {
                val received = linkedMapOf<String, DiscoveredServer>()
                try {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        socket.soTimeout = UDP_TIMEOUT_MS.toInt()
                        val payload = DiscoveryProtocol.REQUEST.encodeToByteArray()
                        broadcastAddresses().forEach { address ->
                            socket.send(DatagramPacket(payload, payload.size, address, DiscoveryProtocol.UDP_PORT))
                        }
                        val buffer = ByteArray(1_024)
                        try {
                            while (true) {
                                val response = DatagramPacket(buffer, buffer.size)
                                socket.receive(response)
                                val server = DiscoveryProtocol.parseResponse(
                                    response.data.decodeToString(response.offset, response.offset + response.length),
                                    response.address.hostAddress ?: continue,
                                ) ?: continue
                                received[server.hostPort] = server
                            }
                        } catch (_: SocketTimeoutException) {
                            // The receive window elapsed; validate everything collected below.
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Network interfaces and broadcast permissions are optional.
                }
                received.values.toList()
            }
            candidates.map { server ->
                async(Dispatchers.IO) {
                    validateDiscovered(server)
                }
            }.awaitAll().filterNotNull().onEach(onFound)
        }

    /** Confirms an advertised identity against the endpoint's health response. */
    private suspend fun validateDiscovered(server: DiscoveredServer): DiscoveredServer? {
        val validated = scanner.discover(server.hostPort) ?: return null
        val advertisedId = server.instanceId?.let(DiscoveryProtocol::normalizeInstanceId)
        if (advertisedId != null && advertisedId != validated.instanceId) return null
        return server.copy(instanceId = validated.instanceId ?: advertisedId)
    }

    private fun broadcastAddresses(): Set<InetAddress> {
        val addresses = linkedSetOf(InetAddress.getByName("255.255.255.255"))
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                network.interfaceAddresses.mapNotNullTo(addresses) { it.broadcast }
            }
        }
        return addresses
    }

    companion object {
        private const val MDNS_TIMEOUT_MS = 2_500L
        private const val UDP_TIMEOUT_MS = 1_500L
    }
}
