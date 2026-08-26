package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WsBroadcasterTest {

    @Test
    void tracksTvAndH5SessionsAndReturnsTypeOnUnregister() {
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper());
        WebSocketSession tv = session("tv-1", "tv");
        WebSocketSession h5 = session("h5-1", "h5");

        broadcaster.register(tv);
        broadcaster.register(h5);

        assertThat(broadcaster.sessionCount()).isEqualTo(2);
        assertThat(broadcaster.isTvOnline()).isTrue();
        assertThat(broadcaster.h5Count()).isEqualTo(1);
        assertThat(broadcaster.unregister(tv)).isEqualTo("tv");
        assertThat(broadcaster.isTvOnline()).isFalse();
        assertThat(broadcaster.h5Count()).isEqualTo(1);
    }

    @Test
    void failedBroadcastRemovesTheBrokenSession() throws Exception {
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper());
        WebSocketSession session = session("broken", "tv");
        doThrow(new IOException("connection closed")).when(session).sendMessage(any(TextMessage.class));
        broadcaster.register(session);

        broadcaster.broadcast(WsEvent.of(WsEvent.PLAYER_STATE, Map.of("state", "playing")));

        assertThat(broadcaster.sessionCount()).isZero();
        assertThat(broadcaster.isTvOnline()).isFalse();
        verify(session).sendMessage(any(TextMessage.class));
    }

    private static WebSocketSession session(String id, String type) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(Map.of("client_type", type));
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
