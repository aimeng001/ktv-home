package com.homektv.tv.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        onFound: ((hostPort: String) -> Unit)? = null,
    ): List<String> =
        coroutineScope {
            val prefix = localSubnetPrefix() ?: return@coroutineScope emptyList()
            val targets = scanTargets(prefix)
            val total = targets.size
            val counter = java.util.concurrent.atomic.AtomicInteger(0)
            val found = ConcurrentHashMap.newKeySet<String>()
            for (batch in targets.chunked(MAX_CONCURRENT_PROBES)) {
                batch.map { hostPort ->
                    async(Dispatchers.IO) {
                        if (validate(hostPort) && found.add(hostPort)) {
                            onFound?.invoke(hostPort)
                        }
                        onProgress?.invoke(counter.incrementAndGet(), total)
                    }
                }.awaitAll()
            }
            targets.filter(found::contains)
        }

    internal fun scanTargets(prefix: String): List<String> = CANDIDATE_PORTS.flatMap { port ->
        (1..254).map { last -> "$prefix$last:$port" }
    }

    /**
     * 单地址探测：先确认服务身份，再确认数据库 readiness。
     * 健康端点可在数据库不可用时仍返回 UP，因此两者都必须成功才允许保存地址。
     */
    suspend fun validate(hostPort: String): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(PROBE_TIMEOUT_MS + 300) {
            try {
                validationSatisfied(VALIDATION_PATHS.associateWith { path -> probe(hostPort, path) })
            } catch (_: Exception) {
                false
            }
        } ?: false
    }

    private fun probe(hostPort: String, path: String): Boolean {
        val req = Request.Builder()
            .url("http://$hostPort$path")
            .get()
            .build()
        return client.newCall(req).execute().use { resp ->
            resp.isSuccessful && (ResponseBodyReader.readText(resp.body, 64L * 1024L)
                ?.contains("home-ktv") == true)
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
        internal val CANDIDATE_PORTS = listOf(8080, 80, 8000, 8081, 8090, 8888, 9000, 9090)
        internal val VALIDATION_PATHS = listOf("/api/health", "/api/ready")

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
