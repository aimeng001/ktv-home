package com.homektv.tv.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 局域网服务端自动扫描（替代手输 IP）。
 *
 * 详设§12.1 原本约定「不做组播自动发现，避免路由器组播策略坑」。
 * 这里按用户要求改为**主动 HTTP 子网探测**：不依赖组播/mDNS，
 * 对本机所在 /24 子网的常用部署端口逐个 GET /api/health，命中 service=home-ktv 即认定为服务端。
 * 优点：不受路由器组播/AP 隔离策略影响；缺点：仅覆盖 /24（家用够用）。
 *
 * Advantage: independent of multicast and AP-isolation policies. Limitation:
 * it only covers a /24 subnet, which is sufficient for typical home networks.
 */
class LanScanner {

    private val client = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_TIMEOUT_MS + 200, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun close() {
        client.closeResources()
    }


    /** 扫描本机所在 /24 网段的所有候选端口，并逐台报告命中。 */
    suspend fun scanAll(
        onProgress: ((scanned: Int, total: Int) -> Unit)? = null,
        onFound: ((server: DiscoveredServer) -> Unit)? = null,
    ): List<DiscoveredServer> =
        coroutineScope {
            val prefix = localSubnetPrefix()
            val targets = scanTargets(prefix)
            if (targets.isEmpty()) return@coroutineScope emptyList()
            val total = targets.size
            val counter = java.util.concurrent.atomic.AtomicInteger(0)
            val found = ConcurrentHashMap<String, DiscoveredServer>()
            for (batch in targets.chunked(MAX_CONCURRENT_PROBES)) {
                batch.map { hostPort ->
                    async(Dispatchers.IO) {
                        val server = discover(hostPort)
                        if (server != null && found.putIfAbsent(server.hostPort, server) == null) {
                            onFound?.invoke(server)
                        }
                        onProgress?.invoke(counter.incrementAndGet(), total)
                    }
                }.awaitAll()
            }
            targets.mapNotNull(found::get)
        }

    internal fun scanTargets(prefix: String?, presetTargets: List<String> = PRESET_TARGETS): List<String> {
        val subnetTargets = if (prefix.isNullOrBlank()) {
            emptyList()
        } else {
            CANDIDATE_PORTS.flatMap { port ->
                (1..254).map { last -> "$prefix$last:$port" }
            }
        }
        return (presetTargets + subnetTargets).distinct()
    }

    /**
     * 单地址探测：先确认服务身份，再确认数据库 readiness。
     * 健康端点可在数据库不可用时仍返回 UP，因此两者都必须成功才允许保存地址。
     */
    suspend fun validate(hostPort: String): Boolean = discover(hostPort) != null

    /** Returns the validated server identity exposed by the health endpoint. */
    suspend fun discover(hostPort: String): DiscoveredServer? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(PROBE_TIMEOUT_MS + 300) {
            try {
                val health = probeBody(hostPort, "/api/health") ?: return@withTimeoutOrNull null
                val ready = probeBody(hostPort, "/api/ready") ?: return@withTimeoutOrNull null
                parseDiscoveredServer(hostPort, health, ready)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Validates readiness and, when supplied, the stable server identity. A
     * healthy HTTP response from a different Home KTV instance is not enough
     * to replace the current session.
     */
    suspend fun validate(hostPort: String, expectedInstanceId: String?): Boolean {
        if (expectedInstanceId == null) return validate(hostPort)
        val expected = DiscoveryProtocol.normalizeInstanceId(expectedInstanceId) ?: return false
        return discover(hostPort)?.instanceId == expected
    }

    private fun probeBody(hostPort: String, path: String): String? {
        val req = Request.Builder()
            .url("http://$hostPort$path")
            .get()
            .build()
        return client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) ResponseBodyReader.readText(resp.body, 64L * 1024L) else null
        }
    }

    internal fun validationSatisfied(results: Map<String, Boolean>): Boolean =
        VALIDATION_PATHS.all { path -> results[path] == true }

    /**
     * 取本机 IPv4 的 /24 前缀（如 192.168.1.）。
     * 优先物理 Wi-Fi / 以太网接口，排除虚拟 VPN / Docker 接口。
     */
    private fun localSubnetPrefix(): String? {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = mutableListOf<LanInterfaceCandidate>()
            for (iface in ifaces) {
                val siteLocalIpv4 = mutableListOf<String>()
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        addr.hostAddress?.let { siteLocalIpv4.add(it) }
                    }
                }
                candidates.add(
                    LanInterfaceCandidate(
                        name = iface.name,
                        isUp = iface.isUp,
                        isLoopback = iface.isLoopback,
                        isVirtual = iface.isVirtual,
                        siteLocalIpv4 = siteLocalIpv4,
                    )
                )
            }
            selectSubnetPrefix(candidates)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 300L
        private const val MAX_CONCURRENT_PROBES = 64
        internal val CANDIDATE_PORTS = listOf(8080, 80, 8000, 8081, 8090, 8888, 9000, 9090, 54001)
        internal val PRESET_TARGETS = listOf("192.168.31.18:54001")
        internal val VALIDATION_PATHS = listOf("/api/health", "/api/ready")

        /**
         * Parses both successful probe bodies and preserves the stable instance ID
         * for subnet-discovered servers. An explicitly present but malformed ID is
         * rejected instead of being silently downgraded to an unknown server.
         */
        internal fun parseDiscoveredServer(
            hostPort: String,
            healthPayload: String,
            readyPayload: String,
        ): DiscoveredServer? = runCatching {
            val health = Json.parseToJsonElement(healthPayload).jsonObject
            val ready = Json.parseToJsonElement(readyPayload).jsonObject
            if (health["service"]?.jsonPrimitive?.contentOrNull != "home-ktv") return null
            if (ready["service"]?.jsonPrimitive?.contentOrNull != "home-ktv") return null

            val hasInstanceId = health.containsKey("instanceId")
            val instanceId = health["instanceId"]?.jsonPrimitive?.contentOrNull
                ?.let(DiscoveryProtocol::normalizeInstanceId)
            if (hasInstanceId && instanceId == null) return null

            val name = health["name"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: hostPort
            DiscoveredServer(hostPort = hostPort, name = name, instanceId = instanceId)
        }.getOrNull()

        internal fun identityValidationSatisfied(healthPayload: String?, expectedInstanceId: String): Boolean {
            val expected = DiscoveryProtocol.normalizeInstanceId(expectedInstanceId) ?: return false
            val root = runCatching { Json.parseToJsonElement(healthPayload.orEmpty()).jsonObject }.getOrNull()
                ?: return false
            val service = root["service"]?.jsonPrimitive?.contentOrNull
            val actual = root["instanceId"]?.jsonPrimitive?.contentOrNull
                ?.let(DiscoveryProtocol::normalizeInstanceId)
            return service == "home-ktv" && actual == expected
        }

        private val VIRTUAL_NAME_PREFIXES = listOf(
            "tun", "tap", "ppp", "p2p", "docker", "veth", "virbr", "dummy",
            "wg", "tailscale", "clash", "zt"
        )

        internal fun selectSubnetPrefix(candidates: List<LanInterfaceCandidate>): String? {
            val scored = candidates
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .filter { candidate ->
                    val lower = candidate.name.lowercase()
                    VIRTUAL_NAME_PREFIXES.none { prefix -> lower.startsWith(prefix) }
                }
                .mapNotNull { candidate ->
                    val validIp = candidate.siteLocalIpv4.firstOrNull() ?: return@mapNotNull null
                    val lower = candidate.name.lowercase()
                    var score = 0
                    if (lower.startsWith("wlan") || lower.startsWith("wifi")) {
                        score += 200
                    } else if (lower.startsWith("eth") || lower.startsWith("en")) {
                        score += 150
                    } else {
                        score += 50
                    }

                    if (validIp.startsWith("192.168.")) {
                        score += 30
                    } else if (validIp.startsWith("172.")) {
                        score += 15
                    } else if (validIp.startsWith("10.")) {
                        score += 5
                    }
                    Pair(validIp, score)
                }
                .sortedByDescending { it.second }

            val bestIp = scored.firstOrNull()?.first ?: return null
            val dot = bestIp.lastIndexOf('.')
            return if (dot > 0) bestIp.substring(0, dot + 1) else null
        }
    }
}

data class LanInterfaceCandidate(
    val name: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val isVirtual: Boolean,
    val siteLocalIpv4: List<String>,
)
