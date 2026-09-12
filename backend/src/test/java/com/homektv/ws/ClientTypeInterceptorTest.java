package com.homektv.ws;

import org.junit.jupiter.api.Test;
import com.homektv.config.AppProperties;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ClientTypeInterceptorTest {

    @Test
    void decodesStablePlayerIdentityAndV2Capabilities() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws");
        servletRequest.setQueryString(
                "client_type=tv&client_token=win%20player%2F1&protocol_version=2&platform=WINDOWS");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = new ClientTypeInterceptor().beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                mock(org.springframework.http.server.ServerHttpResponse.class),
                mock(org.springframework.web.socket.WebSocketHandler.class),
                attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry("client_type", "tv");
        assertThat(attributes).containsEntry("client_token", "win player/1");
        assertThat(attributes).containsEntry("protocol_version", "2");
        assertThat(attributes).containsEntry("platform", "WINDOWS");
    }

    @Test
    void rejectsAHandshakesFromAnUnrelatedBrowserOrigin() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws");
        servletRequest.addHeader("Origin", "http://evil.example");

        boolean accepted = new ClientTypeInterceptor().beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                mock(org.springframework.http.server.ServerHttpResponse.class),
                mock(org.springframework.web.socket.WebSocketHandler.class),
                new HashMap<>());

        assertThat(accepted).isFalse();
    }

    @Test
    void requiresTheConfiguredPlayerCredentialForTvHandshakes() {
        AppProperties properties = new AppProperties();
        properties.setPlayerCredential("player-secret");

        MockHttpServletRequest missing = new MockHttpServletRequest("GET", "/ws");
        missing.setQueryString("client_type=tv&client_token=tv-1");
        boolean missingAccepted = new ClientTypeInterceptor(properties).beforeHandshake(
                new ServletServerHttpRequest(missing),
                mock(org.springframework.http.server.ServerHttpResponse.class),
                mock(org.springframework.web.socket.WebSocketHandler.class),
                new HashMap<>());

        MockHttpServletRequest valid = new MockHttpServletRequest("GET", "/ws");
        valid.setQueryString("client_type=tv&client_token=tv-1&player_credential=player-secret");
        boolean validAccepted = new ClientTypeInterceptor(properties).beforeHandshake(
                new ServletServerHttpRequest(valid),
                mock(org.springframework.http.server.ServerHttpResponse.class),
                mock(org.springframework.web.socket.WebSocketHandler.class),
                new HashMap<>());

        assertThat(missingAccepted).isFalse();
        assertThat(validAccepted).isTrue();
    }

    @Test
    void preservesControllerPlatformAndDeviceModeMetadata() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws");
        servletRequest.setQueryString(
                "client_type=controller&client_token=android-1&protocol_version=2" +
                        "&platform=ANDROID_TABLET&device_mode=CONTROLLER");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = new ClientTypeInterceptor().beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                mock(org.springframework.http.server.ServerHttpResponse.class),
                mock(org.springframework.web.socket.WebSocketHandler.class),
                attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry("client_type", "controller");
        assertThat(attributes).containsEntry("platform", "ANDROID_TABLET");
        assertThat(attributes).containsEntry("device_mode", "CONTROLLER");
    }

    @Test
    void defaultsMissingClientTypeToH5ForLegacyClients() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws");
        servletRequest.setQueryString("client_token=legacy-1");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = new ClientTypeInterceptor().beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                mock(org.springframework.http.server.ServerHttpResponse.class),
                mock(org.springframework.web.socket.WebSocketHandler.class),
                attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry("client_type", "h5");
    }

    @Test
    void rejectsUnknownRolesAndOversizedHandshakeMetadata() {
        MockHttpServletRequest unknownRole = new MockHttpServletRequest("GET", "/ws");
        unknownRole.setQueryString("client_type=phone");
        boolean unknownAccepted = new ClientTypeInterceptor().beforeHandshake(
                new ServletServerHttpRequest(unknownRole),
                mock(org.springframework.http.server.ServerHttpResponse.class),
                mock(org.springframework.web.socket.WebSocketHandler.class),
                new HashMap<>());

        MockHttpServletRequest oversized = new MockHttpServletRequest("GET", "/ws");
        oversized.setQueryString("client_type=h5&protocol_version=" + "x".repeat(257));
        boolean oversizedAccepted = new ClientTypeInterceptor().beforeHandshake(
                new ServletServerHttpRequest(oversized),
                mock(org.springframework.http.server.ServerHttpResponse.class),
                mock(org.springframework.web.socket.WebSocketHandler.class),
                new HashMap<>());

        assertThat(unknownAccepted).isFalse();
        assertThat(oversizedAccepted).isFalse();
    }

    @Test
    void rejectsInvalidPlatformAndDeviceModeValues() {
        MockHttpServletRequest invalidPlatform = new MockHttpServletRequest("GET", "/ws");
        invalidPlatform.setQueryString("client_type=controller&platform=UNKNOWN");
        boolean platformAccepted = new ClientTypeInterceptor().beforeHandshake(
                new ServletServerHttpRequest(invalidPlatform),
                mock(org.springframework.http.server.ServerHttpResponse.class),
                mock(org.springframework.web.socket.WebSocketHandler.class),
                new HashMap<>());

        MockHttpServletRequest invalidMode = new MockHttpServletRequest("GET", "/ws");
        invalidMode.setQueryString("client_type=controller&device_mode=UNKNOWN");
        boolean modeAccepted = new ClientTypeInterceptor().beforeHandshake(
                new ServletServerHttpRequest(invalidMode),
                mock(org.springframework.http.server.ServerHttpResponse.class),
                mock(org.springframework.web.socket.WebSocketHandler.class),
                new HashMap<>());

        assertThat(platformAccepted).isFalse();
        assertThat(modeAccepted).isFalse();
    }
}
