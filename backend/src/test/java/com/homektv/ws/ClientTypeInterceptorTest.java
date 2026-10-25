package com.homektv.ws;

import org.junit.jupiter.api.Test;
import com.homektv.config.AppProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

        MockHttpServletRequest plaintextQuery = new MockHttpServletRequest("GET", "/ws");
        plaintextQuery.setQueryString("client_type=tv&client_token=tv-1&player_credential=player-secret");
        boolean plaintextQueryAccepted = new ClientTypeInterceptor(properties).beforeHandshake(
                new ServletServerHttpRequest(plaintextQuery),
                mock(org.springframework.http.server.ServerHttpResponse.class),
                mock(org.springframework.web.socket.WebSocketHandler.class),
                new HashMap<>());

        MockHttpServletRequest insecureHeader = new MockHttpServletRequest("GET", "/ws");
        insecureHeader.setQueryString("client_type=tv&client_token=tv-1");
        insecureHeader.addHeader("X-Player-Credential", "player-secret");
        boolean insecureHeaderAccepted = new ClientTypeInterceptor(properties).beforeHandshake(
                new ServletServerHttpRequest(insecureHeader),
                mock(org.springframework.http.server.ServerHttpResponse.class),
                mock(org.springframework.web.socket.WebSocketHandler.class),
                new HashMap<>());

        MockHttpServletRequest valid = new MockHttpServletRequest("GET", "/ws");
        valid.setScheme("https");
        valid.setSecure(true);
        valid.setServerPort(443);
        valid.setQueryString("client_type=tv&client_token=tv-1");
        valid.addHeader("X-Player-Credential", "player-secret");
        boolean validAccepted = new ClientTypeInterceptor(properties).beforeHandshake(
                new ServletServerHttpRequest(valid),
                mock(org.springframework.http.server.ServerHttpResponse.class),
                mock(org.springframework.web.socket.WebSocketHandler.class),
                new HashMap<>());

        assertThat(missingAccepted).isFalse();
        assertThat(plaintextQueryAccepted).isFalse();
        assertThat(insecureHeaderAccepted).isFalse();
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

    @Test
    void rejectsOversizedQueryHeaderAndClientToken() {
        HandshakeResult oversizedQuery = handshake(
                "http://localhost/ws?x=" + "x".repeat(4097), null, null, new AppProperties());
        HandshakeResult oversizedHeader = handshake(
                "http://localhost/ws?client_type=h5&client_token=legacy",
                null,
                "x".repeat(257),
                new AppProperties());
        HandshakeResult oversizedToken = handshake(
                "http://localhost/ws?client_type=h5&client_token=" + "x".repeat(257),
                null,
                null,
                new AppProperties());

        assertRejected(oversizedQuery);
        assertRejected(oversizedHeader);
        assertRejected(oversizedToken);
    }

    @Test
    void acceptsBlankOptionalMetadataAndIgnoresMalformedQueryPairs() {
        HandshakeResult result = handshake(
                "http://localhost/ws?client_type=%20%20&client_token=legacy"
                        + "&protocol_version=%20&platform=%20&device_mode=%20&broken&=ignored",
                null,
                null,
                new AppProperties());

        assertThat(result.accepted()).isTrue();
        assertThat(result.attributes())
                .containsEntry("client_type", "h5")
                .containsEntry("protocol_version", " ")
                .containsEntry("platform", " ")
                .containsEntry("device_mode", " ");

        HandshakeResult emptyQuery = handshake("http://localhost/ws?", null, null, new AppProperties());
        assertThat(emptyQuery.accepted()).isTrue();
        assertThat(emptyQuery.attributes()).containsEntry("client_type", "h5");
    }

    @Test
    void rejectsOversizedOptionalPlatformAndDeviceMode() {
        HandshakeResult oversizedPlatform = handshake(
                "http://localhost/ws?client_type=h5&platform=" + "x".repeat(257),
                null,
                null,
                new AppProperties());
        HandshakeResult oversizedMode = handshake(
                "http://localhost/ws?client_type=h5&device_mode=" + "x".repeat(257),
                null,
                null,
                new AppProperties());

        assertRejected(oversizedPlatform);
        assertRejected(oversizedMode);
    }

    @Test
    void handlesConfiguredCredentialBlankMissingAndIncorrectValues() {
        AppProperties blankCredential = new AppProperties();
        blankCredential.setPlayerCredential(" \t ");
        HandshakeResult optionalCredential = handshake(
                "http://localhost/ws?client_type=tv&client_token=tv-1",
                null,
                null,
                blankCredential);
        AppProperties nullCredential = new AppProperties();
        nullCredential.setPlayerCredential(null);
        HandshakeResult nullConfiguredCredential = handshake(
                "http://localhost/ws?client_type=tv&client_token=tv-1",
                null,
                null,
                nullCredential);

        AppProperties configured = new AppProperties();
        configured.setPlayerCredential("player-secret");
        HandshakeResult missingHeader = handshake(
                "https://localhost/ws?client_type=tv&client_token=tv-1",
                null,
                null,
                configured);
        HandshakeResult wrongHeader = handshake(
                "https://localhost/ws?client_type=tv&client_token=tv-1",
                null,
                "wrong-secret",
                configured);

        assertThat(optionalCredential.accepted()).isTrue();
        assertThat(nullConfiguredCredential.accepted()).isTrue();
        assertRejected(missingHeader);
        assertRejected(wrongHeader);
    }

    @Test
    void validatesBrowserOriginSchemeHostAndEffectivePort() {
        AppProperties properties = new AppProperties();
        assertAccepted(handshake(
                "http://example.test/ws?client_type=h5", "http://EXAMPLE.test", null, properties));
        assertAccepted(handshake(
                "https://example.test/ws?client_type=h5", "https://example.test", null, properties));
        assertAccepted(handshake(
                "https://example.test:8443/ws?client_type=h5",
                "https://EXAMPLE.test:8443",
                null,
                properties));

        assertRejected(handshake(
                "https://example.test/ws?client_type=h5", "http://example.test", null, properties));
        assertRejected(handshake(
                "http://example.test/ws?client_type=h5", "http://evil.test", null, properties));
        assertRejected(handshake(
                "http://example.test:8080/ws?client_type=h5",
                "http://example.test:8081",
                null,
                properties));
    }

    @Test
    void rejectsMalformedOrIncompleteBrowserOriginsAndAllowsBlankOrigin() {
        AppProperties properties = new AppProperties();
        assertAccepted(handshake("http://localhost/ws?client_type=h5", " ", null, properties));
        assertRejected(handshake("http://localhost/ws?client_type=h5", "//localhost", null, properties));
        assertRejected(handshake("http://localhost/ws?client_type=h5", "https:", null, properties));
        assertRejected(handshake("http://localhost/ws?client_type=h5", "http:///ws", null, properties));
        assertRejected(handshake("http://localhost/ws?client_type=h5", "http://%zz", null, properties));
    }

    private static HandshakeResult handshake(
            String rawUri, String origin, String playerCredential, AppProperties properties) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        if (origin != null) headers.set("Origin", origin);
        if (playerCredential != null) headers.set("X-Player-Credential", playerCredential);
        when(request.getURI()).thenReturn(URI.create(rawUri));
        when(request.getHeaders()).thenReturn(headers);

        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = new ClientTypeInterceptor(properties).beforeHandshake(
                request, response, mock(WebSocketHandler.class), attributes);
        return new HandshakeResult(accepted, attributes, response);
    }

    private static void assertAccepted(HandshakeResult result) {
        assertThat(result.accepted()).isTrue();
        verifyNoInteractions(result.response());
    }

    private static void assertRejected(HandshakeResult result) {
        assertThat(result.accepted()).isFalse();
        verify(result.response()).setStatusCode(HttpStatus.FORBIDDEN);
    }

    private record HandshakeResult(
            boolean accepted, Map<String, Object> attributes, ServerHttpResponse response) {
    }
}
