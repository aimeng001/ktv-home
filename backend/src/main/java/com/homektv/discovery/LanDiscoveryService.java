package com.homektv.discovery;

import com.homektv.config.AppProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 局域网发现服务，通过 mDNS 注册和 UDP 广播响应使客户端能在局域网内自动发现服务。
 *
 * LAN discovery service that enables clients to automatically discover the service
 * within the local network via mDNS registration and UDP broadcast response.
 */
@Component
public class LanDiscoveryService {
    public static final String SERVICE_TYPE = "_home-ktv._tcp.local.";
    public static final String DISCOVERY_REQUEST = "HOME_KTV_DISCOVER_V1";

    private static final Logger log = LoggerFactory.getLogger(LanDiscoveryService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AppProperties properties;
    private final ServerInstanceIdentityService identityService;
    private final List<JmDNS> registrations = new ArrayList<>();
    private ExecutorService udpExecutor;
    private DatagramSocket udpSocket;
    private int httpPort;
    private volatile String instanceId;

    /**
     * 构造局域网发现服务。
     *
     * Constructs a LAN discovery service.
     *
     * @param properties 应用配置属性 / application configuration properties
     */
    public LanDiscoveryService(AppProperties properties, ServerInstanceIdentityService identityService) {
        this.properties = properties;
        this.identityService = identityService;
    }

    /**
     * Web 服务器就绪后启动 mDNS 注册和 UDP 响应器。
     *
     * Starts mDNS registration and UDP responder after the web server is ready.
     *
     * @param event Web 服务器初始化完成事件 / web server initialized event
     */
    @EventListener
    public synchronized void onWebServerReady(WebServerInitializedEvent event) {
        if (!properties.getDiscovery().isEnabled() || httpPort != 0) return;
        httpPort = event.getWebServer().getPort();
        instanceId = identityService.getOrCreate();
        registerMdns();
        startUdpResponder();
    }

    // 在每个站点本地地址上注册 mDNS 服务
    // Register mDNS service on each site-local address
    private void registerMdns() {
        for (InetAddress address : siteLocalAddresses()) {
            try {
                JmDNS jmDNS = JmDNS.create(address, "home-ktv");
                Map<String, String> txt = Map.of(
                        "service", "home-ktv",
                        "protocol", "1",
                        "instanceId", instanceId,
                        "api", "/api"
                );
                ServiceInfo info = ServiceInfo.create(
                        SERVICE_TYPE,
                        properties.getDiscovery().getInstanceName(),
                        httpPort,
                        0,
                        0,
                        txt
                );
                jmDNS.registerService(info);
                registrations.add(jmDNS);
                log.info("mDNS discovery published on {}:{}", address.getHostAddress(), httpPort);
            } catch (IOException e) {
                log.warn("mDNS discovery unavailable on {}: {}", address, e.getMessage());
            }
        }
    }

    // 启动 UDP 广播响应器，监听发现请求并返回服务信息
    // Start UDP broadcast responder that listens for discovery requests and returns service info
    private void startUdpResponder() {
        int discoveryPort = properties.getDiscovery().getUdpPort();
        udpExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "home-ktv-udp-discovery");
            thread.setDaemon(true);
            return thread;
        });
        udpExecutor.execute(() -> {
            try (DatagramSocket socket = new DatagramSocket(null)) {
                udpSocket = socket;
                socket.setReuseAddress(true);
                socket.setBroadcast(true);
                socket.bind(new InetSocketAddress(discoveryPort));
                log.info("UDP discovery listening on 0.0.0.0:{}", discoveryPort);
                byte[] buffer = new byte[512];
                while (!socket.isClosed()) {
                    DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                    socket.receive(request);
                    String message = new String(request.getData(), request.getOffset(), request.getLength(), StandardCharsets.UTF_8);
                    if (!DISCOVERY_REQUEST.equals(message.trim())) continue;
                    byte[] response = responsePayload(
                            httpPort, properties.getDiscovery().getInstanceName(), instanceId);
                    socket.send(new DatagramPacket(response, response.length, request.getAddress(), request.getPort()));
                }
            } catch (SocketException e) {
                if (udpSocket != null && !udpSocket.isClosed()) log.warn("UDP discovery stopped: {}", e.getMessage());
            } catch (IOException e) {
                log.warn("UDP discovery unavailable: {}", e.getMessage());
            }
        });
    }

    /**
     * 构建 UDP 发现响应 JSON 负载。
     *
     * Builds the UDP discovery response JSON payload.
     *
     * @param port HTTP 服务端口 / HTTP service port
     * @param name 实例名称 / instance name
     * @return JSON 格式的响应字节数组 / JSON response as byte array
     */
    static byte[] responsePayload(int port, String name, String instanceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("service", "home-ktv");
        payload.put("protocolVersion", 1);
        payload.put("name", name == null ? "" : name);
        payload.put("port", port);
        payload.put("instanceId", instanceId == null ? "" : instanceId);
        try {
            return JSON.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法构建发现响应", e);
        }
    }

    // 枚举所有网络接口，收集站点本地 IPv4 地址
    // Enumerate all network interfaces and collect site-local IPv4 addresses
    private List<InetAddress> siteLocalAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
                Enumeration<InetAddress> values = network.getInetAddresses();
                while (values.hasMoreElements()) {
                    InetAddress address = values.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) addresses.add(address);
                }
            }
        } catch (SocketException e) {
            log.warn("Cannot enumerate network interfaces: {}", e.getMessage());
        }
        return addresses;
    }

    /**
     * 关闭服务，释放 UDP 和 mDNS 资源。
     *
     * Shuts down the service and releases UDP and mDNS resources.
     */
    @PreDestroy
    public synchronized void close() {
        if (udpSocket != null) udpSocket.close();
        if (udpExecutor != null) udpExecutor.shutdownNow();
        registrations.forEach(jmDNS -> {
            try {
                jmDNS.unregisterAllServices();
                jmDNS.close();
            } catch (IOException ignored) {
            }
        });
        registrations.clear();
    }
}
