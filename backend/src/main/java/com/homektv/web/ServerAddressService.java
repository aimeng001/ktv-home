package com.homektv.web;

import com.homektv.library.SettingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * 服务端展示地址探测（详设§8 ADM-03「展示地址：自动探测+可手填」）。
 * <p>
 * Server display address detection (Detail Design §8 ADM-03: auto-detect + manual override).
 *
 * 优先级：
 *   1. settings 里手填的 display_address（管理员在后台设置）
 *   2. 自动探测本机局域网 IPv4（site-local，非回环/虚拟）
 *   3. 兜底 localhost
 * 端口读取 server.port，支持非默认部署端口。
 * <p>
 * Priority:
 *   1. Manually configured display_address in settings (set by admin)
 *   2. Auto-detect the machine's LAN IPv4 (site-local, non-loopback, non-virtual)
 *   3. Fallback to localhost
 * Port is read from server.port, supporting non-default deployment ports.
 */
@Service
public class ServerAddressService {

    private final SettingService settingService;
    private final int serverPort;

    public ServerAddressService(SettingService settingService, @Value("${server.port:8080}") int serverPort) {
        this.settingService = settingService;
        this.serverPort = serverPort;
    }

    /**
     * 返回 host[:port]（不含协议），如 192.168.1.10:8080。
     * <p>
     * Returns host[:port] (without protocol), e.g. 192.168.1.10:8080.
     * @return the host and port string
     */
    public String hostPort() {
        // 1. 后台手填优先
        String manual = manualAddress();
        if (manual != null) return manual;
        // 2. 自动探测局域网地址
        String lan = detectLanIp();
        String host = lan != null ? lan : "localhost";
        return host + ":" + serverPort;
    }

    /**
     * 请求来自 TV 时优先使用请求实际访问的 host。容器端看到的网卡地址通常是
     * 172.x Docker 网段，而 Host 头保留了 TV 可达的宿主机地址。
     * <p>
     * When the request comes from a TV, prefer the actual host from the request.
     * The container's network interfaces usually show 172.x Docker subnet addresses,
     * while the Host header retains the host address reachable by the TV.
     * @param requestHost  the host from the request
     * @param requestPort  the port from the request
     * @return host[:port] string
     */
    public String hostPort(String requestHost, int requestPort) {
        String manual = manualAddress();
        if (manual != null) return manual;
        if (requestHost != null && !requestHost.isBlank()
                && !"localhost".equalsIgnoreCase(requestHost)
                && !requestHost.startsWith("127.")) {
            int port = requestPort > 0 ? requestPort : serverPort;
            return requestHost + ":" + port;
        }
        return hostPort();
    }

    /**
     * H5 点歌地址：http://host:port/m?room=default
     * <p>
     * H5 song-request URL: http://host:port/m?room=default
     * @param room  the room identifier
     * @return the full H5 URL
     */
    public String h5Url(String room) {
        return "http://" + hostPort() + "/m?room=" + room;
    }

    /**
     * H5 点歌地址，使用请求中的 host 和 port。
     * <p>
     * H5 song-request URL using the host and port from the request.
     * @param room         the room identifier
     * @param requestHost  the host from the request
     * @param requestPort  the port from the request
     * @return the full H5 URL
     */
    public String h5Url(String room, String requestHost, int requestPort) {
        return "http://" + hostPort(requestHost, requestPort) + "/m?room=" + room;
    }

    private String manualAddress() {
        var settings = settingService.getAll();
        Object manual = settings.get("display_address");
        if (!(manual instanceof String s) || s.isBlank()) {
            // Keep the original qr_address setting compatible with the newer
            // display_address name used by the admin UI.
            manual = settings.get("qr_address");
        }
        if (manual instanceof String s && !s.isBlank()) return normalize(s.trim());
        return null;
    }

    /** 补全端口、剥离协议/路径。 */
    private String normalize(String raw) {
        String s = raw;
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (!s.contains(":")) s = s + ":" + serverPort;
        return s;
    }

    /** 探测本机首个站点本地 IPv4（192.168/10/172.16-31）。 */
    private String detectLanIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
