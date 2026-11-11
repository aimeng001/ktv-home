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
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
    private static final long RETRY_INITIAL_DELAY_MILLIS = 1_000;
    private static final long RETRY_MAX_DELAY_MILLIS = 60_000;

    @FunctionalInterface
    interface UdpSocketBinder {
        DatagramSocket bind(int discoveryPort) throws IOException;
    }

    private static final Logger log = LoggerFactory.getLogger(LanDiscoveryService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AppProperties properties;
    private final ServerInstanceIdentityService identityService;
    private final com.homektv.library.SettingService settingService;
    private final UdpSocketBinder udpSocketBinder;
    private final Supplier<List<InetAddress>> siteLocalAddressSupplier;
    private final ScheduledExecutorService retrySchedulerOverride;
    private final Map<InetAddress, JmDNS> registrations = new LinkedHashMap<>();
    private volatile ExecutorService udpExecutor;
    private volatile DatagramSocket udpSocket;
    private volatile Future<?> udpListenerTask;
    private ScheduledExecutorService retryScheduler;
    private ScheduledFuture<?> retryFuture;
    private int retryAttempt;
    private int httpPort;
    private volatile String instanceId;
    private volatile String registeredName;
    private volatile boolean discoveryEnabled;
    private volatile String desiredName;

    /**
     * 构造局域网发现服务。
     *
     * Constructs a LAN discovery service.
     *
     * @param properties 应用配置属性 / application configuration properties
     */
    public LanDiscoveryService(AppProperties properties, ServerInstanceIdentityService identityService) {
        this(properties, identityService, null, LanDiscoveryService::bindDiscoverySocket,
                LanDiscoveryService::siteLocalAddresses, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LanDiscoveryService(AppProperties properties, ServerInstanceIdentityService identityService,
                               com.homektv.library.SettingService settingService) {
        this(properties, identityService, settingService, LanDiscoveryService::bindDiscoverySocket,
                LanDiscoveryService::siteLocalAddresses, null);
    }

    LanDiscoveryService(AppProperties properties, ServerInstanceIdentityService identityService,
                        com.homektv.library.SettingService settingService,
                        UdpSocketBinder udpSocketBinder,
                        Supplier<List<InetAddress>> siteLocalAddressSupplier,
                        ScheduledExecutorService retrySchedulerOverride) {
        this.properties = properties;
        this.identityService = identityService;
        this.settingService = settingService;
        this.udpSocketBinder = udpSocketBinder;
        this.siteLocalAddressSupplier = siteLocalAddressSupplier;
        this.retrySchedulerOverride = retrySchedulerOverride;
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
        if (httpPort == 0) httpPort = event.getWebServer().getPort();
        reconcileDiscovery(isDiscoveryEnabled(), discoveryName());
    }

    @EventListener
    public synchronized void onDiscoverySettingsChanged(DiscoverySettingsChangedEvent event) {
        reconcileDiscovery(event.enabled(), event.name());
    }

    private boolean isDiscoveryEnabled() {
        return settingService == null
                ? properties.getDiscovery().isEnabled()
                : settingService.isDiscoveryEnabled(properties.getDiscovery().isEnabled());
    }

    private String discoveryName() {
        return settingService == null
                ? properties.getDiscovery().getInstanceName()
                : settingService.getDiscoveryName(properties.getDiscovery().getInstanceName());
    }

    private void reconcileDiscovery(boolean enabled, String name) {
        if (httpPort <= 0) return;
        if (!enabled) {
            stopDiscovery();
            return;
        }
        if (instanceId == null) instanceId = identityService.getOrCreate();
        if (!Objects.equals(desiredName, name)) {
            desiredName = name;
            unregisterMdns();
            cancelRetryAndReleaseScheduler();
        }
        discoveryEnabled = true;
        boolean mdnsReady = registerMdns(name);
        boolean udpReady = startUdpResponder();
        if (mdnsReady && udpReady) cancelRetryAndReleaseScheduler();
        else scheduleRetry();
    }

    // 在每个站点本地地址上注册 mDNS 服务
    // Register mDNS service on each site-local address
    private boolean registerMdns(String name) {
        if (!Objects.equals(registeredName, name)) {
            unregisterMdns();
            registeredName = name;
        }
        List<InetAddress> addresses = siteLocalAddressSupplier.get();
        Set<InetAddress> activeAddresses = new LinkedHashSet<>(addresses);
        for (InetAddress registeredAddress : List.copyOf(registrations.keySet())) {
            if (!activeAddresses.contains(registeredAddress)) {
                closeRegistration(registrations.remove(registeredAddress));
            }
        }
        for (InetAddress address : activeAddresses) {
            if (registrations.containsKey(address)) continue;
            JmDNS jmDNS = null;
            try {
                jmDNS = JmDNS.create(address, "home-ktv");
                Map<String, String> txt = Map.of(
                        "service", "home-ktv",
                        "protocol", "1",
                        "instanceId", instanceId,
                        "api", "/api"
                );
                ServiceInfo info = ServiceInfo.create(
                        SERVICE_TYPE,
                        name,
                        httpPort,
                        0,
                        0,
                        txt
                );
                jmDNS.registerService(info);
                registrations.put(address, jmDNS);
                log.info("mDNS discovery published on {}:{}", address.getHostAddress(), httpPort);
            } catch (IOException e) {
                if (jmDNS != null) closeRegistration(jmDNS);
                log.warn("mDNS discovery unavailable on {}: {}", address, e.getMessage());
            }
        }
        return !activeAddresses.isEmpty() && registrations.keySet().containsAll(activeAddresses);
    }

    private void unregisterMdns() {
        for (JmDNS jmDNS : new HashSet<>(registrations.values())) closeRegistration(jmDNS);
        registrations.clear();
        registeredName = null;
    }

    private void closeRegistration(JmDNS jmDNS) {
        jmDNS.unregisterAllServices();
        try {
            jmDNS.close();
        } catch (IOException e) {
            log.debug("Unable to close mDNS discovery: {}", e.getMessage());
        }
    }

    // 启动 UDP 广播响应器，监听发现请求并返回服务信息
    // Start UDP broadcast responder that listens for discovery requests and returns service info
    private boolean startUdpResponder() {
        Future<?> currentListener = udpListenerTask;
        DatagramSocket currentSocket = udpSocket;
        if (currentSocket != null && !currentSocket.isClosed()
                && currentListener != null && !currentListener.isDone()) return true;
        stopUdpResponder();
        int discoveryPort = properties.getDiscovery().getUdpPort();
        DatagramSocket socket;
        try {
            socket = udpSocketBinder.bind(discoveryPort);
        } catch (IOException e) {
            log.warn("UDP discovery unavailable: {}", e.getMessage());
            return false;
        }
        udpSocket = socket;
        udpExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "home-ktv-udp-discovery");
            thread.setDaemon(true);
            return thread;
        });
        try {
            udpListenerTask = udpExecutor.submit(() -> listenForDiscovery(socket));
        } catch (RejectedExecutionException e) {
            log.warn("UDP discovery listener could not start: {}", e.getMessage());
            stopUdpResponder();
            return false;
        }
        log.info("UDP discovery listening on 0.0.0.0:{}", socket.getLocalPort());
        return true;
    }

    private static DatagramSocket bindDiscoverySocket(int discoveryPort) throws IOException {
        DatagramSocket socket = new DatagramSocket(null);
        try {
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(discoveryPort));
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private void listenForDiscovery(DatagramSocket socket) {
        boolean failed = false;
        try {
            byte[] buffer = new byte[512];
            while (!socket.isClosed()) {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                socket.receive(request);
                String message = new String(request.getData(), request.getOffset(), request.getLength(), StandardCharsets.UTF_8);
                if (!DISCOVERY_REQUEST.equals(message.trim()) || !isDiscoveryEnabled()) continue;
                byte[] response = responsePayload(httpPort, discoveryName(), instanceId);
                socket.send(new DatagramPacket(response, response.length, request.getAddress(), request.getPort()));
            }
        } catch (SocketException e) {
            if (!socket.isClosed()) {
                failed = true;
                log.warn("UDP discovery stopped: {}", e.getMessage());
            }
        } catch (IOException e) {
            if (!socket.isClosed()) {
                failed = true;
                log.warn("UDP discovery unavailable: {}", e.getMessage());
            }
        } catch (RuntimeException e) {
            if (!socket.isClosed()) {
                failed = true;
                log.warn("UDP discovery listener stopped unexpectedly", e);
            }
            throw e;
        } finally {
            if (!socket.isClosed()) {
                failed = true;
                socket.close();
            }
            udpListenerStopped(socket, failed);
        }
    }

    private synchronized void udpListenerStopped(DatagramSocket socket, boolean failed) {
        if (udpSocket != socket) return;
        udpSocket = null;
        udpListenerTask = null;
        ExecutorService executor = udpExecutor;
        udpExecutor = null;
        if (executor != null) executor.shutdown();
        if (failed && discoveryEnabled) scheduleRetry();
    }

    private void scheduleRetry() {
        if (!discoveryEnabled || httpPort <= 0 || (retryFuture != null && !retryFuture.isDone())) return;
        long delay = Math.min(RETRY_INITIAL_DELAY_MILLIS << Math.min(retryAttempt, 6), RETRY_MAX_DELAY_MILLIS);
        retryAttempt++;
        if (retryScheduler == null || retryScheduler.isShutdown()) {
            retryScheduler = retrySchedulerOverride != null
                    ? retrySchedulerOverride
                    : Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread thread = new Thread(r, "home-ktv-discovery-retry");
                        thread.setDaemon(true);
                        return thread;
                    });
        }
        try {
            retryFuture = retryScheduler.schedule(this::retryDiscovery, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            retryFuture = null;
            log.warn("LAN discovery retry could not be scheduled: {}", e.getMessage());
        }
    }

    private synchronized void retryDiscovery() {
        retryFuture = null;
        if (discoveryEnabled) reconcileDiscovery(true, desiredName);
    }

    private void cancelRetryAndReleaseScheduler() {
        ScheduledFuture<?> future = retryFuture;
        retryFuture = null;
        if (future != null) future.cancel(false);
        retryAttempt = 0;
        if (retryScheduler != null && retryScheduler != retrySchedulerOverride) {
            retryScheduler.shutdownNow();
            retryScheduler = null;
        }
    }

    private void stopUdpResponder() {
        DatagramSocket socket = udpSocket;
        udpSocket = null;
        if (socket != null) socket.close();
        Future<?> listenerTask = udpListenerTask;
        udpListenerTask = null;
        if (listenerTask != null) listenerTask.cancel(true);
        ExecutorService executor = udpExecutor;
        udpExecutor = null;
        if (executor != null) executor.shutdownNow();
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
    private static List<InetAddress> siteLocalAddresses() {
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
        stopDiscovery();
    }

    private void stopDiscovery() {
        discoveryEnabled = false;
        desiredName = null;
        cancelRetryAndReleaseScheduler();
        stopUdpResponder();
        unregisterMdns();
    }
}
