package com.homektv.ws;

import org.junit.jupiter.api.Test;
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
}
