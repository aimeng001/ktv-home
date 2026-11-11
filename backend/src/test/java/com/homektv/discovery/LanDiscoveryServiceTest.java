package com.homektv.discovery;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.test.util.ReflectionTestUtils;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.BindException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LanDiscoveryServiceTest {
    @Test
    void responseContainsActualHttpPortAndEscapedName() {
        String instanceId = "550e8400-e29b-41d4-a716-446655440000";
        String payload = new String(
                LanDiscoveryService.responsePayload(12345, "客厅\"KTV", instanceId),
                StandardCharsets.UTF_8);

        assertThat(payload).contains("\"service\":\"home-ktv\"");
        assertThat(payload).contains("\"protocolVersion\":1");
        assertThat(payload).contains("\"port\":12345");
        assertThat(payload).contains("\"instanceId\":\"" + instanceId + "\"");
        assertThat(payload).contains("客厅\\\"KTV");
    }

    @Test
    void responseEscapesJsonControlCharacters() throws Exception {
        String payload = new String(
                LanDiscoveryService.responsePayload(12345, "客厅\nKTV\t\u0001", "instance"),
                StandardCharsets.UTF_8);

        var parsed = new ObjectMapper().readTree(payload);
        assertThat(parsed.get("name").asText()).isEqualTo("客厅\nKTV\t\u0001");
    }

    @Test
    void responseUsesEmptyValuesForMissingOptionalIdentityAndName() throws Exception {
        String payload = new String(
                LanDiscoveryService.responsePayload(8080, null, null),
                StandardCharsets.UTF_8);

        var parsed = new ObjectMapper().readTree(payload);
        assertThat(parsed.get("name").asText()).isEmpty();
        assertThat(parsed.get("instanceId").asText()).isEmpty();
        assertThat(parsed.get("port").asInt()).isEqualTo(8080);
    }

    @Test
    void disabledDiscoveryRetainsHttpPortWithoutResolvingIdentityOrAllocatingNetworkResources() {
        AppProperties properties = new AppProperties();
        properties.getDiscovery().setEnabled(false);
        ServerInstanceIdentityService identityService = mock(ServerInstanceIdentityService.class);
        LanDiscoveryService service = new LanDiscoveryService(properties, identityService);
        WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
        WebServer webServer = mock(WebServer.class);
        when(event.getWebServer()).thenReturn(webServer);
        when(webServer.getPort()).thenReturn(12345);

        service.onWebServerReady(event);
        assertThat(ReflectionTestUtils.getField(service, "httpPort")).isEqualTo(12345);
        service.close();
        service.close();

        verifyNoInteractions(identityService);
    }

    @Test
    void enabledDiscoveryAnswersOnlyValidLocalUdpRequestsAndStartsOnce() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getDiscovery().setEnabled(true);
        properties.getDiscovery().setUdpPort(0);
        properties.getDiscovery().setInstanceName("test-home");
        ServerInstanceIdentityService identityService = mock(ServerInstanceIdentityService.class);
        when(identityService.getOrCreate()).thenReturn("local-test-instance");
        JmDNS registration = mock(JmDNS.class);
        WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
        WebServer webServer = mock(WebServer.class);
        when(event.getWebServer()).thenReturn(webServer);
        when(webServer.getPort()).thenReturn(12345);
        LanDiscoveryService service = new LanDiscoveryService(properties, identityService);

        try (var mdns = mockStatic(JmDNS.class)) {
            mdns.when(() -> JmDNS.create(any(InetAddress.class), anyString())).thenReturn(registration);
            try {
                service.onWebServerReady(event);
                service.onWebServerReady(event);

                DatagramSocket listener = awaitBoundSocket(service);
                try (DatagramSocket client = new DatagramSocket()) {
                    client.setSoTimeout(150);
                    send(client, listener.getLocalPort(), "not-a-discovery-request");
                    byte[] buffer = new byte[512];
                    DatagramPacket ignoredResponse = new DatagramPacket(buffer, buffer.length);
                    assertThatThrownBy(() -> client.receive(ignoredResponse))
                            .isInstanceOf(SocketTimeoutException.class);

                    client.setSoTimeout(2_000);
                    send(client, listener.getLocalPort(), LanDiscoveryService.DISCOVERY_REQUEST);
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    client.receive(response);
                    var payload = new ObjectMapper().readTree(
                            new String(response.getData(), response.getOffset(), response.getLength(),
                                    StandardCharsets.UTF_8));
                    assertThat(payload.get("name").asText()).isEqualTo("test-home");
                    assertThat(payload.get("port").asInt()).isEqualTo(12345);
                    assertThat(payload.get("instanceId").asText()).isEqualTo("local-test-instance");
                }

                service.close();
                verify(identityService, times(1)).getOrCreate();
            } finally {
                service.close();
            }
        }
    }

    @Test
    void udpBindFailureRetriesAndResumesResponsesWithoutConfigurationChange() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getDiscovery().setEnabled(true);
        properties.getDiscovery().setUdpPort(0);
        properties.getDiscovery().setInstanceName("retry-home");
        ServerInstanceIdentityService identityService = mock(ServerInstanceIdentityService.class);
        when(identityService.getOrCreate()).thenReturn("retry-instance");

        ScheduledExecutorService retryScheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> retryFuture = mock(ScheduledFuture.class);
        AtomicReference<Runnable> scheduledRetry = new AtomicReference<>();
        when(retryScheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    scheduledRetry.set(invocation.getArgument(0));
                    return retryFuture;
                });

        AtomicInteger bindAttempts = new AtomicInteger();
        LanDiscoveryService.UdpSocketBinder binder = port -> {
            if (bindAttempts.getAndIncrement() == 0) throw new BindException("simulated bind conflict");
            return new DatagramSocket(0);
        };
        InetAddress localAddress = InetAddress.getByName("192.168.1.25");
        JmDNS registration = mock(JmDNS.class);
        WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
        WebServer webServer = mock(WebServer.class);
        when(event.getWebServer()).thenReturn(webServer);
        when(webServer.getPort()).thenReturn(12345);
        LanDiscoveryService service = new LanDiscoveryService(
                properties, identityService, null, binder, () -> List.of(localAddress), retryScheduler);

        try (var mdns = mockStatic(JmDNS.class)) {
            mdns.when(() -> JmDNS.create(localAddress, "home-ktv")).thenReturn(registration);
            try {
                service.onWebServerReady(event);

                assertThat(ReflectionTestUtils.getField(service, "udpSocket")).isNull();
                assertThat(scheduledRetry.get()).as("failed bind must schedule recovery").isNotNull();
                scheduledRetry.getAndSet(null).run();

                DatagramSocket listener = awaitBoundSocket(service);
                try (DatagramSocket client = new DatagramSocket()) {
                    client.setSoTimeout(2_000);
                    send(client, listener.getLocalPort(), LanDiscoveryService.DISCOVERY_REQUEST);
                    byte[] buffer = new byte[512];
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    client.receive(response);
                    var payload = new ObjectMapper().readTree(
                            new String(response.getData(), response.getOffset(), response.getLength(),
                                    StandardCharsets.UTF_8));
                    assertThat(payload.get("name").asText()).isEqualTo("retry-home");
                    assertThat(payload.get("port").asInt()).isEqualTo(12345);
                    assertThat(payload.get("instanceId").asText()).isEqualTo("retry-instance");
                }
                assertThat(bindAttempts).hasValue(2);
            } finally {
                service.close();
            }
        }
    }

    @Test
    void mdnsRetriesOnlyTheInterfaceThatFailedAndKeepsHealthyRegistrations() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getDiscovery().setEnabled(true);
        properties.getDiscovery().setUdpPort(0);
        properties.getDiscovery().setInstanceName("multi-home");
        ServerInstanceIdentityService identityService = mock(ServerInstanceIdentityService.class);
        when(identityService.getOrCreate()).thenReturn("multi-instance");

        ScheduledExecutorService retryScheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> retryFuture = mock(ScheduledFuture.class);
        AtomicReference<Runnable> scheduledRetry = new AtomicReference<>();
        when(retryScheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    scheduledRetry.set(invocation.getArgument(0));
                    return retryFuture;
                });

        InetAddress firstAddress = InetAddress.getByName("192.168.1.31");
        InetAddress secondAddress = InetAddress.getByName("192.168.1.32");
        JmDNS firstRegistration = mock(JmDNS.class);
        JmDNS secondRegistration = mock(JmDNS.class);
        AtomicInteger firstAddressAttempts = new AtomicInteger();
        LanDiscoveryService service = new LanDiscoveryService(
                properties,
                identityService,
                null,
                port -> new DatagramSocket(0),
                () -> List.of(firstAddress, secondAddress),
                retryScheduler);

        try (var mdns = mockStatic(JmDNS.class)) {
            mdns.when(() -> JmDNS.create(any(InetAddress.class), anyString())).thenAnswer(invocation -> {
                InetAddress address = invocation.getArgument(0);
                if (address.equals(firstAddress) && firstAddressAttempts.getAndIncrement() == 0) {
                    throw new IOException("simulated interface startup failure");
                }
                return address.equals(firstAddress) ? firstRegistration : secondRegistration;
            });
            try {
                service.onWebServerReady(webServerEvent(12346));

                assertThat(scheduledRetry.get()).as("partial mDNS failure must schedule recovery").isNotNull();
                DatagramSocket udpSocket = (DatagramSocket) ReflectionTestUtils.getField(service, "udpSocket");
                scheduledRetry.getAndSet(null).run();

                assertThat(firstAddressAttempts).hasValue(2);
                verify(firstRegistration, times(1)).registerService(any(ServiceInfo.class));
                verify(secondRegistration, times(1)).registerService(any(ServiceInfo.class));
                assertThat(ReflectionTestUtils.getField(service, "udpSocket")).isSameAs(udpSocket);
            } finally {
                service.close();
            }
        }
    }

    @Test
    void unexpectedUdpListenerFailureClosesOldSocketAndRestartsResponder() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getDiscovery().setEnabled(true);
        properties.getDiscovery().setUdpPort(0);
        properties.getDiscovery().setInstanceName("listener-retry-home");
        ServerInstanceIdentityService identityService = mock(ServerInstanceIdentityService.class);
        when(identityService.getOrCreate()).thenReturn("listener-retry-instance");

        ScheduledExecutorService retryScheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> retryFuture = mock(ScheduledFuture.class);
        AtomicReference<Runnable> scheduledRetry = new AtomicReference<>();
        when(retryScheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    scheduledRetry.set(invocation.getArgument(0));
                    return retryFuture;
                });

        InetAddress localAddress = InetAddress.getByName("192.168.1.41");
        JmDNS registration = mock(JmDNS.class);
        AtomicInteger bindAttempts = new AtomicInteger();
        AtomicReference<DatagramSocket> failedSocket = new AtomicReference<>();
        LanDiscoveryService.UdpSocketBinder binder = port -> {
            if (bindAttempts.getAndIncrement() == 0) {
                DatagramSocket socket = new FailingReceiveSocket();
                failedSocket.set(socket);
                return socket;
            }
            return new DatagramSocket(0);
        };
        LanDiscoveryService service = new LanDiscoveryService(
                properties, identityService, null, binder, () -> List.of(localAddress), retryScheduler);

        try (var mdns = mockStatic(JmDNS.class)) {
            mdns.when(() -> JmDNS.create(localAddress, "home-ktv")).thenReturn(registration);
            try {
                service.onWebServerReady(webServerEvent(12347));

                Runnable retry = awaitScheduledRetry(scheduledRetry);
                assertThat(failedSocket.get().isClosed()).isTrue();
                scheduledRetry.compareAndSet(retry, null);
                retry.run();

                DatagramSocket activeSocket = awaitBoundSocket(service);
                assertThat(activeSocket).isNotSameAs(failedSocket.get());
                try (DatagramSocket client = new DatagramSocket()) {
                    client.setSoTimeout(2_000);
                    send(client, activeSocket.getLocalPort(), LanDiscoveryService.DISCOVERY_REQUEST);
                    byte[] buffer = new byte[512];
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    client.receive(response);
                    var payload = new ObjectMapper().readTree(
                            new String(response.getData(), response.getOffset(), response.getLength(),
                                    StandardCharsets.UTF_8));
                    assertThat(payload.get("name").asText()).isEqualTo("listener-retry-home");
                }
                assertThat(bindAttempts).hasValue(2);
            } finally {
                service.close();
            }
        }
    }

    @Test
    void disablingDiscoveryCancelsPendingRetryAndPreventsRestart() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getDiscovery().setEnabled(true);
        properties.getDiscovery().setUdpPort(0);
        ServerInstanceIdentityService identityService = mock(ServerInstanceIdentityService.class);
        when(identityService.getOrCreate()).thenReturn("disabled-retry-instance");
        ScheduledExecutorService retryScheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> retryFuture = mock(ScheduledFuture.class);
        AtomicReference<Runnable> scheduledRetry = new AtomicReference<>();
        when(retryScheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    scheduledRetry.set(invocation.getArgument(0));
                    return retryFuture;
                });
        AtomicInteger bindAttempts = new AtomicInteger();
        LanDiscoveryService service = new LanDiscoveryService(
                properties,
                identityService,
                null,
                port -> {
                    bindAttempts.incrementAndGet();
                    throw new BindException("simulated persistent bind conflict");
                },
                List::of,
                retryScheduler);

        service.onWebServerReady(webServerEvent(12348));
        Runnable retry = scheduledRetry.get();
        assertThat(retry).isNotNull();

        service.onDiscoverySettingsChanged(new DiscoverySettingsChangedEvent(false, "家庭KTV"));
        verify(retryFuture).cancel(false);
        retry.run();

        assertThat(bindAttempts).hasValue(1);
        service.close();
    }

    @Test
    void dynamicDiscoveryName_overridesInstanceNameAndDisabledSettingDropsPackets() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getDiscovery().setEnabled(true);
        properties.getDiscovery().setUdpPort(0);
        properties.getDiscovery().setInstanceName("fallback-home");
        ServerInstanceIdentityService identityService = mock(ServerInstanceIdentityService.class);
        when(identityService.getOrCreate()).thenReturn("instance-dynamic");
        com.homektv.library.SettingService settingService = mock(com.homektv.library.SettingService.class);
        when(settingService.getDiscoveryName("fallback-home")).thenReturn("VIP包房");
        when(settingService.isDiscoveryEnabled(true)).thenReturn(true);

        WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
        WebServer webServer = mock(WebServer.class);
        when(event.getWebServer()).thenReturn(webServer);
        when(webServer.getPort()).thenReturn(54001);

        LanDiscoveryService service = new LanDiscoveryService(properties, identityService, settingService);
        JmDNS registration = mock(JmDNS.class);

        try (var mdns = mockStatic(JmDNS.class)) {
            mdns.when(() -> JmDNS.create(any(InetAddress.class), anyString())).thenReturn(registration);
            try {
                service.onWebServerReady(event);
                var serviceInfo = org.mockito.ArgumentCaptor.forClass(ServiceInfo.class);
                verify(registration, atLeastOnce()).registerService(serviceInfo.capture());
                assertThat(serviceInfo.getAllValues())
                        .anySatisfy(info -> assertThat(info.getName()).isEqualTo("VIP包房"));
                DatagramSocket listener = awaitBoundSocket(service);
                try (DatagramSocket client = new DatagramSocket()) {
                    client.setSoTimeout(2_000);
                    send(client, listener.getLocalPort(), LanDiscoveryService.DISCOVERY_REQUEST);
                    byte[] buffer = new byte[512];
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    client.receive(response);
                    var payload = new ObjectMapper().readTree(
                            new String(response.getData(), response.getOffset(), response.getLength(),
                                    StandardCharsets.UTF_8));
                    assertThat(payload.get("name").asText()).isEqualTo("VIP包房");

                    when(settingService.isDiscoveryEnabled(true)).thenReturn(false);
                    client.setSoTimeout(150);
                    send(client, listener.getLocalPort(), LanDiscoveryService.DISCOVERY_REQUEST);
                    assertThatThrownBy(() -> client.receive(response))
                            .isInstanceOf(SocketTimeoutException.class);
                }
            } finally {
                service.close();
            }
        }
    }

    @Test
    void discoverySettingsChangesStartStopRenameAndRestartResources() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getDiscovery().setEnabled(false);
        properties.getDiscovery().setUdpPort(0);
        properties.getDiscovery().setInstanceName("fallback-home");
        ServerInstanceIdentityService identityService = mock(ServerInstanceIdentityService.class);
        when(identityService.getOrCreate()).thenReturn("instance-lifecycle");
        com.homektv.library.SettingService settingService = mock(com.homektv.library.SettingService.class);
        when(settingService.isDiscoveryEnabled(false)).thenReturn(false);
        when(settingService.getDiscoveryName("fallback-home")).thenReturn("VIP包房一");
        WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
        WebServer webServer = mock(WebServer.class);
        when(event.getWebServer()).thenReturn(webServer);
        when(webServer.getPort()).thenReturn(54002);
        JmDNS registration = mock(JmDNS.class);
        LanDiscoveryService service = new LanDiscoveryService(properties, identityService, settingService);

        try (var mdns = mockStatic(JmDNS.class)) {
            mdns.when(() -> JmDNS.create(any(InetAddress.class), anyString())).thenReturn(registration);
            try {
                service.onWebServerReady(event);
                assertThat(ReflectionTestUtils.getField(service, "httpPort")).isEqualTo(54002);
                verifyNoInteractions(identityService);

                service.onDiscoverySettingsChanged(new DiscoverySettingsChangedEvent(true, "VIP包房一"));
                DatagramSocket firstSocket = awaitBoundSocket(service);
                assertThat(firstSocket.isClosed()).isFalse();
                assertThat(registeredNames(registration)).contains("VIP包房一");

                service.onDiscoverySettingsChanged(new DiscoverySettingsChangedEvent(true, "VIP包房一"));
                assertThat(ReflectionTestUtils.getField(service, "udpSocket")).isSameAs(firstSocket);

                service.onDiscoverySettingsChanged(new DiscoverySettingsChangedEvent(true, "VIP包房二"));
                assertThat(ReflectionTestUtils.getField(service, "udpSocket")).isSameAs(firstSocket);
                assertThat(registeredNames(registration)).contains("VIP包房二");

                service.onDiscoverySettingsChanged(new DiscoverySettingsChangedEvent(false, "VIP包房二"));
                assertThat(firstSocket.isClosed()).isTrue();
                verify(registration, atLeastOnce()).unregisterAllServices();
                verify(registration, atLeastOnce()).close();

                when(settingService.getDiscoveryName("fallback-home")).thenReturn("VIP包房二");
                service.onDiscoverySettingsChanged(new DiscoverySettingsChangedEvent(true, "VIP包房二"));
                DatagramSocket restartedSocket = awaitBoundSocket(service);
                assertThat(restartedSocket).isNotSameAs(firstSocket);
                assertThat(restartedSocket.isClosed()).isFalse();
                verify(identityService, times(1)).getOrCreate();
            } finally {
                service.close();
            }
        }
    }

    private static DatagramSocket awaitBoundSocket(LanDiscoveryService service) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        DatagramSocket socket;
        do {
            socket = (DatagramSocket) ReflectionTestUtils.getField(service, "udpSocket");
            if (socket != null && socket.isBound()) return socket;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("UDP discovery socket did not bind to an ephemeral local port");
    }

    private static WebServerInitializedEvent webServerEvent(int port) {
        WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
        WebServer webServer = mock(WebServer.class);
        when(event.getWebServer()).thenReturn(webServer);
        when(webServer.getPort()).thenReturn(port);
        return event;
    }

    private static Runnable awaitScheduledRetry(AtomicReference<Runnable> scheduledRetry) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        Runnable retry;
        while ((retry = scheduledRetry.get()) == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(retry).as("failed UDP listener must schedule recovery").isNotNull();
        return retry;
    }

    private static final class FailingReceiveSocket extends DatagramSocket {
        private FailingReceiveSocket() throws SocketException {
            super((InetSocketAddress) null);
            setReuseAddress(true);
            bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        }

        @Override
        public void receive(DatagramPacket packet) throws IOException {
            throw new SocketException("simulated listener failure");
        }
    }

    private static java.util.List<String> registeredNames(JmDNS registration) throws Exception {
        var serviceInfo = org.mockito.ArgumentCaptor.forClass(ServiceInfo.class);
        verify(registration, atLeastOnce()).registerService(serviceInfo.capture());
        return serviceInfo.getAllValues().stream().map(ServiceInfo::getName).toList();
    }

    private static void send(DatagramSocket client, int port, String payload) throws Exception {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        client.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
    }
}
