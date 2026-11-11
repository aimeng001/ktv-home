package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
        CountDownLatch sendFailed = new CountDownLatch(1);
        doAnswer(invocation -> {
            sendFailed.countDown();
            throw new IOException("connection closed");
        }).when(session).sendMessage(any(TextMessage.class));
        broadcaster.register(session);

        broadcaster.broadcast(WsEvent.of(WsEvent.PLAYER_STATE, Map.of("state", "playing")));

        assertThat(sendFailed.await(2, TimeUnit.SECONDS)).isTrue();
        awaitSessionCount(broadcaster, 0);
        assertThat(broadcaster.sessionCount()).isZero();
        assertThat(broadcaster.isTvOnline()).isFalse();
        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    void failedBroadcastRemovesThePlayerLeaseAndPublishesDisconnectEvent() throws Exception {
        ActivePlayerRegistry registry = new ActivePlayerRegistry();
        AtomicReference<Object> published = new AtomicReference<>();
        CountDownLatch disconnected = new CountDownLatch(1);
        ApplicationEventPublisher publisher = event -> {
            published.set(event);
            disconnected.countDown();
        };
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper(), registry, publisher);
        WebSocketSession active = session("active", "tv");
        WebSocketSession standby = session("standby", "tv");
        doAnswer(invocation -> {
            throw new IOException("connection closed");
        }).when(active).sendMessage(any(TextMessage.class));
        broadcaster.register(active);
        broadcaster.register(standby);
        registry.register("active", new ActivePlayerRegistry.PlayerHello("a", "WINDOWS", 2));
        registry.register("standby", new ActivePlayerRegistry.PlayerHello("b", "ANDROID_TV", 2));

        broadcaster.broadcast(WsEvent.of(WsEvent.PLAYER_STATE, Map.of("state", "playing")));

        assertThat(disconnected.await(2, TimeUnit.SECONDS)).isTrue();
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

        CountDownLatch delivered = new CountDownLatch(2);
        doAnswer(invocation -> {
            delivered.countDown();
            return null;
        }).when(active).sendMessage(any(TextMessage.class));
        doAnswer(invocation -> {
            delivered.countDown();
            return null;
        }).when(h5).sendMessage(any(TextMessage.class));
        broadcaster.broadcastPlayback(WsEvent.of(WsEvent.PLAYER_STATE, Map.of("state", "playing")));

        assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
        verify(active).sendMessage(any(TextMessage.class));
        verify(h5).sendMessage(any(TextMessage.class));
        verify(standby, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void observerBroadcastReachesPhonesButNeverTargetsTvPlayers() throws Exception {
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper());
        WebSocketSession tv = session("tv", "tv");
        WebSocketSession h5 = session("h5", "h5");
        WebSocketSession controller = session("controller", "controller");
        CountDownLatch delivered = new CountDownLatch(2);
        doAnswer(invocation -> {
            delivered.countDown();
            return null;
        }).when(h5).sendMessage(any(TextMessage.class));
        doAnswer(invocation -> {
            delivered.countDown();
            return null;
        }).when(controller).sendMessage(any(TextMessage.class));
        broadcaster.register(tv);
        broadcaster.register(h5);
        broadcaster.register(controller);

        broadcaster.broadcastObservers(WsEvent.of(WsEvent.PLAYER_STATE, Map.of("tvOnline", true)));

        assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
        verify(h5).sendMessage(any(TextMessage.class));
        verify(controller).sendMessage(any(TextMessage.class));
        verify(tv, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void slowObserverCannotBlockTheActivePlayerFromTheSamePlaybackBroadcast() throws Exception {
        ActivePlayerRegistry registry = new ActivePlayerRegistry();
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper(), registry);
        WebSocketSession slowObserver = session("a", "h5");
        WebSocketSession activePlayer = session("b", "tv");
        CountDownLatch observerSendStarted = new CountDownLatch(1);
        CountDownLatch releaseObserver = new CountDownLatch(1);
        CountDownLatch playerReceived = new CountDownLatch(1);
        doAnswer(invocation -> {
            observerSendStarted.countDown();
            if (!releaseObserver.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the slow observer");
            }
            return null;
        }).when(slowObserver).sendMessage(any(TextMessage.class));
        doAnswer(invocation -> {
            playerReceived.countDown();
            return null;
        }).when(activePlayer).sendMessage(any(TextMessage.class));
        broadcaster.register(slowObserver);
        broadcaster.register(activePlayer);
        registry.register("b", new ActivePlayerRegistry.PlayerHello("active", "ANDROID_TV", 2));

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            var broadcast = caller.submit(() -> broadcaster.broadcastPlayback(
                    WsEvent.of(WsEvent.PLAYER_STATE, Map.of("state", "playing"))));
            assertThat(observerSendStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(playerReceived.await(250, TimeUnit.MILLISECONDS)).as(
                    "the active player must not wait for a slow H5 observer").isTrue();
            broadcast.get(2, TimeUnit.SECONDS);
        } finally {
            releaseObserver.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void slowObserverIsDisconnectedWhenItsBoundedPendingQueueOverflows() throws Exception {
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper());
        WebSocketSession slowObserver = session("slow-observer", "h5");
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            sendStarted.countDown();
            releaseSend.await(5, TimeUnit.SECONDS);
            return null;
        }).when(slowObserver).sendMessage(any(TextMessage.class));
        broadcaster.register(slowObserver);

        WsEvent event = WsEvent.of(WsEvent.PLAYER_STATE, Map.of("state", "playing"));
        try {
            broadcaster.broadcast(event);
            assertThat(sendStarted.await(2, TimeUnit.SECONDS)).isTrue();
            for (int index = 0; index < 129; index++) {
                broadcaster.broadcast(event);
            }
            awaitSessionCount(broadcaster, 0);
            assertThat(broadcaster.sessionCount()).isZero();
        } finally {
            releaseSend.countDown();
        }
    }

    @Test
    void blockedObserverSendExpiresAndReleasesItsSession() throws Exception {
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper(), new ActivePlayerRegistry(),
                event -> { }, 40);
        WebSocketSession slowObserver = session("timed-out-observer", "h5");
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            sendStarted.countDown();
            releaseSend.await(5, TimeUnit.SECONDS);
            return null;
        }).when(slowObserver).sendMessage(any(TextMessage.class));
        broadcaster.register(slowObserver);

        try {
            broadcaster.broadcast(WsEvent.of(WsEvent.PLAYER_STATE, Map.of("state", "playing")));
            assertThat(sendStarted.await(2, TimeUnit.SECONDS)).isTrue();
            awaitSessionCount(broadcaster, 0);
            assertThat(broadcaster.sessionCount()).isZero();
        } finally {
            releaseSend.countDown();
        }
    }

    private static void awaitSessionCount(WsBroadcaster broadcaster, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (broadcaster.sessionCount() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private static WebSocketSession session(String id, String type) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(Map.of("client_type", type));
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
