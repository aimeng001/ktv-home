package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void separatesNativeControllersFromBrowserH5WithoutTreatingThemAsTv() {
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper());
        WebSocketSession controller = session("controller-1", "controller");

        broadcaster.register(controller);

        assertThat(broadcaster.h5Count()).isZero();
        assertThat(broadcaster.controllerCount()).isEqualTo(1);
        assertThat(broadcaster.connectedPhonesCount()).isEqualTo(1);
        assertThat(broadcaster.isTvOnline()).isFalse();
        assertThat(broadcaster.unregister(controller)).isEqualTo("controller");
        assertThat(broadcaster.h5Count()).isZero();
        assertThat(broadcaster.controllerCount()).isZero();
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

    @Test
    void failedBroadcastRemovesThePlayerLeaseAndPublishesDisconnectEvent() throws Exception {
        ActivePlayerRegistry registry = new ActivePlayerRegistry();
        AtomicReference<Object> published = new AtomicReference<>();
        ApplicationEventPublisher publisher = published::set;
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper(), registry, publisher);
        WebSocketSession active = session("active", "tv");
        WebSocketSession standby = session("standby", "tv");
        doThrow(new IOException("connection closed")).when(active)
                .sendMessage(any(TextMessage.class));
        broadcaster.register(active);
        broadcaster.register(standby);
        registry.register("active", new ActivePlayerRegistry.PlayerHello("a", "WINDOWS", 2));
        registry.register("standby", new ActivePlayerRegistry.PlayerHello("b", "ANDROID_TV", 2));

        broadcaster.broadcast(WsEvent.of(WsEvent.PLAYER_STATE, Map.of("state", "playing")));

        assertThat(registry.activeSessionId()).hasValue("standby");
        assertThat(published.get()).isInstanceOf(WsSessionDisconnectedEvent.class);
        WsSessionDisconnectedEvent event = (WsSessionDisconnectedEvent) published.get();
        assertThat(event.sessionId()).isEqualTo("active");
        assertThat(event.playerRemoval().promotion()).isPresent();
    }

    @Test
    void playbackBroadcastReachesH5AndActivePlayerButNotStandby() throws Exception {
        ActivePlayerRegistry registry = new ActivePlayerRegistry();
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper(), registry);
        WebSocketSession active = session("active", "tv");
        WebSocketSession standby = session("standby", "tv");
        WebSocketSession h5 = session("h5", "h5");
        broadcaster.register(active);
        broadcaster.register(standby);
        broadcaster.register(h5);
        registry.register("active", new ActivePlayerRegistry.PlayerHello("a", "WINDOWS", 2));
        registry.register("standby", new ActivePlayerRegistry.PlayerHello("b", "ANDROID_TV", 2));

        broadcaster.broadcastPlayback(WsEvent.of(WsEvent.PLAYER_STATE, Map.of("state", "playing")));

        verify(active).sendMessage(any(TextMessage.class));
        verify(h5).sendMessage(any(TextMessage.class));
        verify(standby, never()).sendMessage(any(TextMessage.class));
    }

    private static WebSocketSession session(String id, String type) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(Map.of("client_type", type));
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
