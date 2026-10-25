package com.homektv.discovery;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.test.util.ReflectionTestUtils;

import javax.jmdns.JmDNS;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    void disabledDiscoveryDoesNotResolveIdentityOrAllocateNetworkResources() {
        AppProperties properties = new AppProperties();
        properties.getDiscovery().setEnabled(false);
        ServerInstanceIdentityService identityService = mock(ServerInstanceIdentityService.class);
        LanDiscoveryService service = new LanDiscoveryService(properties, identityService);

        service.onWebServerReady(mock(WebServerInitializedEvent.class));
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

    private static void send(DatagramSocket client, int port, String payload) throws Exception {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        client.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
    }
}
