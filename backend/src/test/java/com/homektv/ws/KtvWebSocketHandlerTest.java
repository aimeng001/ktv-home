package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.queue.PlaybackService;
import com.homektv.queue.PlaybackTransitionResult;
import com.homektv.queue.FinishResult;
import com.homektv.queue.PositionUpdateResult;
import com.homektv.queue.SnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KtvWebSocketHandlerTest {

    private WsBroadcaster broadcaster;
    private SnapshotService snapshotService;
    private PlaybackService playbackService;
    private TvOfflineWatcher offlineWatcher;
    private ActivePlayerRegistry playerRegistry;
    private KtvWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        broadcaster = mock(WsBroadcaster.class);
        snapshotService = mock(SnapshotService.class);
        playbackService = mock(PlaybackService.class);
        offlineWatcher = mock(TvOfflineWatcher.class);
        playerRegistry = new ActivePlayerRegistry();
        handler = new KtvWebSocketHandler(broadcaster, snapshotService, playbackService,
                offlineWatcher, playerRegistry, new ObjectMapper());
    }

    @Test
    void connectionRegistersTvAndSendsFullSnapshot() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("tv-1");
        when(session.getAttributes()).thenReturn(Map.of("client_type", "tv"));

        handler.afterConnectionEstablished(session);

        verify(broadcaster).register(session);
        verify(offlineWatcher).onTvConnected();
        var events = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster, org.mockito.Mockito.times(2)).sendTo(eq(session), events.capture());
        assertThat(events.getAllValues()).extracting(WsEvent::type)
                .containsExactly(WsEvent.PLAYER_ROLE, WsEvent.SYNC_FULL);
    }

    @Test
    void progressMessageUsesQueueIdentityAndClampsOnlyTheBroadcastPayload() throws Exception {
        when(playbackService.updatePosition(21L, -4L))
                .thenReturn(PositionUpdateResult.accepted(new com.homektv.domain.PlayerState()));

        handler.handleTextMessage(activeLegacySession(), new TextMessage(
                "{\"type\":\"progress\",\"payload\":{\"queue_id\":21,\"position_ms\":-4}}"));

        verify(playbackService).updatePosition(21L, -4L);
        var event = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster).broadcastPlayback(event.capture());
        assertThat(event.getValue().type()).isEqualTo(WsEvent.PROGRESS);
        assertThat(event.getValue().payload()).isInstanceOf(Map.class);
        Map<?, ?> payload = (Map<?, ?>) event.getValue().payload();
        assertThat(payload.get("position_ms")).isEqualTo(0L);
        assertThat(payload.get("queue_id")).isEqualTo(21L);
    }

    @Test
    void rejectsAnOversizedMessageBeforeJsonParsingOrPlaybackDispatch() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("oversized");
        when(session.getAttributes()).thenReturn(Map.of("client_type", "h5"));
        when(session.isOpen()).thenReturn(true);

        handler.handleTextMessage(session, new TextMessage("x".repeat(1_048_577)));

        verify(session).close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(playbackService);
    }

    @Test
    void staleProgressIsNotBroadcastAfterServiceRejectsIt() throws Exception {
        when(playbackService.updatePosition(21L, 500L))
                .thenReturn(PositionUpdateResult.rejected(new com.homektv.domain.PlayerState()));

        handler.handleTextMessage(activeLegacySession(), new TextMessage(
                "{\"type\":\"progress\",\"payload\":{\"queue_id\":21,\"position_ms\":500}}"));

        verify(broadcaster, org.mockito.Mockito.never()).broadcastPlayback(any(WsEvent.class));
    }

    @Test
    void finishedReportIsAcknowledgedAndBroadcastsHistoryChangeOnlyWhenApplied() throws Exception {
        WebSocketSession active = playerSession("active", "active-token", 2);
        handler.afterConnectionEstablished(active);
        clearInvocations(broadcaster);
        when(playbackService.onFinished(21L))
                .thenReturn(FinishResult.applied(new com.homektv.domain.PlayerState(), 21L));

        handler.handleTextMessage(active, new TextMessage(
                "{\"type\":\"finished\",\"payload\":{\"queue_id\":21,\"generation\":1}}"));

        verify(playbackService).onFinished(21L);
        var ack = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster).sendTo(eq(active), ack.capture());
        assertThat(ack.getValue().type()).isEqualTo(WsEvent.PLAYBACK_REPORT_ACK);
        assertThat(((Map<?, ?>) ack.getValue().payload()).get("report_type")).isEqualTo("finished");
        assertThat(((Map<?, ?>) ack.getValue().payload()).get("status")).isEqualTo("APPLIED");
        var broadcasts = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster, org.mockito.Mockito.times(2)).broadcastPlayback(broadcasts.capture());
        assertThat(broadcasts.getAllValues()).extracting(WsEvent::type)
                .containsExactly(WsEvent.NOW_PLAYING, WsEvent.HISTORY_UPDATED);
    }

    @Test
    void playErrorMessageForwardsBothFileAndQueueIdentity() throws Exception {
        when(playbackService.onPlayError(7L, 8L))
                .thenReturn(PlaybackTransitionResult.accepted(new com.homektv.domain.PlayerState()));

        WebSocketSession active = activeLegacySession();
        handler.handleTextMessage(active, new TextMessage(
                "{\"type\":\"play_error\",\"payload\":{\"file_id\":7,\"queue_id\":8,\"message\":\"读取失败\"}}"));

        verify(playbackService).onPlayError(7L, 8L);
        var ack = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster).sendTo(eq(active), ack.capture());
        assertThat(ack.getValue().type()).isEqualTo(WsEvent.PLAYBACK_REPORT_ACK);
        assertThat(((Map<?, ?>) ack.getValue().payload()).get("queue_id")).isEqualTo(8L);
        assertThat(((Map<?, ?>) ack.getValue().payload()).get("report_type")).isEqualTo("play_error");
        assertThat(((Map<?, ?>) ack.getValue().payload()).get("status")).isEqualTo("APPLIED");
        var toast = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster).broadcast(toast.capture());
        var playback = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster).broadcastPlayback(playback.capture());
        assertThat(toast.getValue().type()).isEqualTo(WsEvent.TOAST);
        assertThat(playback.getValue().type()).isEqualTo(WsEvent.NOW_PLAYING);
        assertThat(((Map<?, ?>) toast.getValue().payload()).get("text"))
                .asString().contains("读取失败");
    }

    @Test
    void playErrorToastBoundsUntrustedReasonBeforeBroadcast() throws Exception {
        when(playbackService.onPlayError(7L, 8L))
                .thenReturn(PlaybackTransitionResult.accepted(new com.homektv.domain.PlayerState()));

        String reason = "异常原因".repeat(20_000);
        handler.handleTextMessage(activeLegacySession(), new TextMessage(
                new ObjectMapper().writeValueAsString(Map.of(
                        "type", "play_error",
                        "payload", Map.of("file_id", 7, "queue_id", 8, "message", reason)))));

        var toast = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster).broadcast(toast.capture());
        String text = (String) ((Map<?, ?>) toast.getValue().payload()).get("text");
        assertThat(text.getBytes(StandardCharsets.UTF_8).length).isLessThan(16 * 1024);
    }

    @Test
    void stalePlayErrorDoesNotBroadcastFeedback() throws Exception {
        when(playbackService.onPlayError(10L, 100L))
                .thenReturn(PlaybackTransitionResult.rejected(new com.homektv.domain.PlayerState()));

        WebSocketSession active = activeLegacySession();
        handler.handleTextMessage(active, new TextMessage(
                "{\"type\":\"play_error\",\"payload\":{\"file_id\":10,\"queue_id\":100,\"message\":\"旧歌曲读取失败\"}}"));

        verify(broadcaster, org.mockito.Mockito.never()).broadcast(any(WsEvent.class));
        var ack = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster).sendTo(eq(active), ack.capture());
        assertThat(((Map<?, ?>) ack.getValue().payload()).get("queue_id")).isEqualTo(100L);
        assertThat(((Map<?, ?>) ack.getValue().payload()).get("status")).isEqualTo("STALE");
    }

    @Test
    void standbyPlayerCannotReportProgress() throws Exception {
        WebSocketSession active = playerSession("active", "active-token", 2);
        WebSocketSession standby = playerSession("standby", "standby-token", 2);
        handler.afterConnectionEstablished(active);
        handler.afterConnectionEstablished(standby);

        handler.handleTextMessage(standby, new TextMessage(
                "{\"type\":\"progress\",\"payload\":{\"queue_id\":21,\"position_ms\":500,\"generation\":0}}"));

        verify(playbackService, never()).updatePosition(any(), any(Long.class));
    }

    @Test
    void protocolV2PlayerMustIncludeQueueIdentityInPlaybackReports() throws Exception {
        WebSocketSession active = playerSession("active", "active-token", 2);
        handler.afterConnectionEstablished(active);

        handler.handleTextMessage(active, new TextMessage(
                "{\"type\":\"progress\",\"payload\":{\"position_ms\":500,\"generation\":1}}"));

        verify(playbackService, never()).updatePosition(any(), any(Long.class));
    }

    @Test
    void legacyPlayerMustIncludeQueueIdentityInPlaybackReports() throws Exception {
        WebSocketSession active = activeLegacySession();

        handler.handleTextMessage(active, new TextMessage(
                "{\"type\":\"progress\",\"payload\":{\"position_ms\":500}}"));

        verify(playbackService, never()).updatePosition(any(), any(Long.class));
    }

    @Test
    void finishedReportWithoutQueueIdentityIsRejectedBeforePlaybackMutation() throws Exception {
        WebSocketSession active = activeLegacySession();

        handler.handleTextMessage(active, new TextMessage(
                "{\"type\":\"finished\",\"payload\":{}}"));

        verify(playbackService, never()).onFinished(any());
        verify(broadcaster, never()).sendTo(eq(active), any(WsEvent.class));
    }

    @Test
    void everyPlaybackReportRejectsNonPositiveOrNonIntegralQueueIdentityBeforeDispatch() throws Exception {
        WebSocketSession active = activeLegacySession();
        List<String> invalidQueueIds = List.of("null", "0", "-1", "21.5", "\"21\"");

        for (String queueId : invalidQueueIds) {
            handler.handleTextMessage(active, new TextMessage("{\"type\":\"progress\",\"payload\":{"
                    + "\"queue_id\":" + queueId + ",\"position_ms\":500}}"));
            handler.handleTextMessage(active, new TextMessage("{\"type\":\"finished\",\"payload\":{"
                    + "\"queue_id\":" + queueId + "}}"));
            handler.handleTextMessage(active, new TextMessage("{\"type\":\"play_error\",\"payload\":{"
                    + "\"queue_id\":" + queueId + "}}"));
        }

        verifyNoInteractions(playbackService);
        verifyNoInteractions(broadcaster);
    }

    @Test
    void activeDisconnectPromotesStandbyAndDoesNotStartOfflineCleanup() throws Exception {
        WebSocketSession active = playerSession("active", "active-token", 2);
        WebSocketSession standby = playerSession("standby", "standby-token", 2);
        when(broadcaster.unregister(active)).thenReturn("tv");
        handler.afterConnectionEstablished(active);
        handler.afterConnectionEstablished(standby);

        handler.afterConnectionClosed(active, org.springframework.web.socket.CloseStatus.NORMAL);

        var events = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster, org.mockito.Mockito.times(2)).sendTo(eq("standby"), events.capture());
        assertThat(events.getAllValues()).extracting(WsEvent::type)
                .containsExactly(WsEvent.PLAYER_ROLE, WsEvent.SYNC_FULL);
        verify(offlineWatcher, never()).onTvDisconnected();
    }

    @Test
    void transportCloseStillUnregistersPlayerAfterBroadcasterAlreadyRemovedSession() throws Exception {
        WebSocketSession active = playerSession("active", "active-token", 1);
        WebSocketSession standby = playerSession("standby", "standby-token", 2);
        handler.afterConnectionEstablished(active);
        handler.afterConnectionEstablished(standby);

        // A failed send may remove the broadcaster entry before Spring invokes
        // the normal close callback. The player lease must still be removed.
        handler.afterConnectionClosed(active, org.springframework.web.socket.CloseStatus.SERVER_ERROR);

        var events = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster, org.mockito.Mockito.times(2)).sendTo(eq("standby"), events.capture());
        assertThat(events.getAllValues()).extracting(WsEvent::type)
                .containsExactly(WsEvent.PLAYER_ROLE, WsEvent.SYNC_FULL);
        verify(offlineWatcher, never()).onTvDisconnected();
    }

    @Test
    void broadcasterDisconnectEventPromotesStandbyAndSendsFreshSnapshot() throws Exception {
        WebSocketSession active = playerSession("active", "active-token", 2);
        WebSocketSession standby = playerSession("standby", "standby-token", 2);
        handler.afterConnectionEstablished(active);
        handler.afterConnectionEstablished(standby);
        clearInvocations(broadcaster);

        ActivePlayerRegistry.Unregistration removal = playerRegistry.unregisterPlayer("active");
        handler.onSessionDisconnected(new WsSessionDisconnectedEvent("active", "tv", removal));

        var events = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster, org.mockito.Mockito.times(2)).sendTo(eq("standby"), events.capture());
        assertThat(events.getAllValues()).extracting(WsEvent::type)
                .containsExactly(WsEvent.PLAYER_ROLE, WsEvent.SYNC_FULL);
        verify(offlineWatcher, never()).onTvDisconnected();
    }

    @Test
    void activeV2HeartbeatRenewsItsLeaseAndReturnsCurrentGeneration() throws Exception {
        WebSocketSession active = playerSession("active", "active-token", 2);
        handler.afterConnectionEstablished(active);
        clearInvocations(broadcaster);

        handler.handleTextMessage(active, new TextMessage(
                "{\"type\":\"ping\",\"payload\":{\"generation\":1}}"));

        var event = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster).sendTo(eq(active), event.capture());
        assertThat(event.getValue().type()).isEqualTo("pong");
        Map<?, ?> payload = (Map<?, ?>) event.getValue().payload();
        assertThat(payload.get("role")).isEqualTo("ACTIVE");
        assertThat(payload.get("generation")).isEqualTo(1L);
    }

    @Test
    void protocolV2HeartbeatWithoutGenerationDoesNotRenewOrReply() throws Exception {
        WebSocketSession active = playerSession("active", "active-token", 2);
        handler.afterConnectionEstablished(active);
        clearInvocations(broadcaster);

        handler.handleTextMessage(active, new TextMessage("{\"type\":\"ping\",\"payload\":{}}"));

        verify(broadcaster, never()).sendTo(eq(active), any(WsEvent.class));
        assertThat(playerRegistry.activeSessionId()).contains("active");
    }

    @Test
    void nonTvConnectionReceivesSnapshotAndNonTvMessagesCannotMutatePlayback() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("h5-1");
        when(session.getAttributes()).thenReturn(Map.of("client_type", "h5"));

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ping\"}"));
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"progress\",\"payload\":{\"queue_id\":21,\"position_ms\":500}}"));

        var events = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster, org.mockito.Mockito.times(2)).sendTo(eq(session), events.capture());
        assertThat(events.getAllValues()).extracting(WsEvent::type)
                .containsExactly(WsEvent.SYNC_FULL, "pong");
        verifyNoInteractions(playbackService);
        verifyNoInteractions(offlineWatcher);
    }

    @Test
    void missingClientTypeIsTreatedAsNonTvForConnectionAndHeartbeat() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("untyped-1");
        when(session.getAttributes()).thenReturn(Map.of("platform", "BROWSER"));

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ping\"}"));

        var events = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster, org.mockito.Mockito.times(2)).sendTo(eq(session), events.capture());
        assertThat(events.getAllValues()).extracting(WsEvent::type)
                .containsExactly(WsEvent.SYNC_FULL, "pong");
        verifyNoInteractions(offlineWatcher);
    }

    @Test
    void nonTvDisconnectDoesNotNotifyTheTvOfflineWatcher() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("h5-close");
        when(session.getAttributes()).thenReturn(Map.of("client_type", "h5"));
        handler.afterConnectionEstablished(session);
        clearInvocations(offlineWatcher);

        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);

        verifyNoInteractions(offlineWatcher);
    }

    @Test
    void closedSessionIsNotClosedAgainWhenItsMessageExceedsTheByteLimit() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("already-closed");
        when(session.getAttributes()).thenReturn(Map.of("client_type", "h5"));
        when(session.isOpen()).thenReturn(false);

        handler.handleTextMessage(session, new TextMessage("x".repeat(1_048_577)));

        verify(session, never()).close(any(org.springframework.web.socket.CloseStatus.class));
        verifyNoInteractions(playbackService);
    }

    @Test
    void unknownMessageTypeIsIgnoredAndAZeroQueueIdentityIsRejected() throws Exception {
        WebSocketSession active = activeLegacySession();

        handler.handleTextMessage(active, new TextMessage("{\"type\":\"future_event\",\"payload\":{}}"));
        handler.handleTextMessage(active, new TextMessage(
                "{\"type\":\"progress\",\"payload\":{\"queue_id\":0,\"position_ms\":500}}"));

        verifyNoInteractions(playbackService);
        verifyNoInteractions(broadcaster);
    }

    @Test
    void finishedReportThatWasAlreadyAppliedOnlyReturnsItsAcknowledgement() throws Exception {
        WebSocketSession active = activeLegacySession();
        when(playbackService.onFinished(21L))
                .thenReturn(FinishResult.alreadyApplied(new com.homektv.domain.PlayerState(), 21L));

        handler.handleTextMessage(active, new TextMessage(
                "{\"type\":\"finished\",\"payload\":{\"queue_id\":21}}"));

        var ack = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster).sendTo(eq(active), ack.capture());
        assertThat(((Map<?, ?>) ack.getValue().payload()).get("status")).isEqualTo("ALREADY_APPLIED");
        verify(broadcaster, never()).broadcastPlayback(any(WsEvent.class));
    }

    @Test
    void playErrorWithoutAFileIdStillReportsTheQueueItemAndUsesTheDefaultReason() throws Exception {
        WebSocketSession active = playerSession("active", "active-token", 2);
        handler.afterConnectionEstablished(active);
        clearInvocations(broadcaster);
        when(playbackService.onPlayError(null, 8L))
                .thenReturn(PlaybackTransitionResult.accepted(new com.homektv.domain.PlayerState()));

        handler.handleTextMessage(active, new TextMessage(
                "{\"type\":\"play_error\",\"payload\":{\"queue_id\":8,\"generation\":1}}"));

        verify(playbackService).onPlayError(null, 8L);
        var toast = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster).broadcast(toast.capture());
        assertThat(((Map<?, ?>) toast.getValue().payload()).get("text"))
                .asString().contains("媒体读取失败");
        var ack = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster).sendTo(eq(active), ack.capture());
        assertThat(((Map<?, ?>) ack.getValue().payload()).get("queue_id")).isEqualTo(8L);
    }

    @Test
    void disconnectingTheLastTvTriggersOfflineOnlyOnce() throws Exception {
        WebSocketSession active = playerSession("only-tv", "only-token", 1);
        handler.afterConnectionEstablished(active);
        clearInvocations(offlineWatcher);

        handler.afterConnectionClosed(active, org.springframework.web.socket.CloseStatus.NORMAL);
        handler.afterConnectionClosed(active, org.springframework.web.socket.CloseStatus.NORMAL);

        verify(offlineWatcher).onTvDisconnected();
    }

    @Test
    void closingAStandbyTvDoesNotMarkTheActiveTvOffline() throws Exception {
        WebSocketSession active = playerSession("active", "active-token", 2);
        WebSocketSession standby = playerSession("standby", "standby-token", 2);
        handler.afterConnectionEstablished(active);
        handler.afterConnectionEstablished(standby);

        handler.afterConnectionClosed(standby, org.springframework.web.socket.CloseStatus.NORMAL);

        verify(offlineWatcher, never()).onTvDisconnected();
        assertThat(playerRegistry.activeSessionId()).contains("active");
    }

    @Test
    void blankConnectionAttributesFallBackAndInvalidProtocolVersionUsesLegacyRules() throws Exception {
        WebSocketSession first = mock(WebSocketSession.class);
        when(first.getId()).thenReturn("first-tv");
        when(first.getAttributes()).thenReturn(Map.of(
                "client_type", "tv", "client_token", " ", "platform", " ", "protocol_version", "invalid"));
        WebSocketSession second = mock(WebSocketSession.class);
        when(second.getId()).thenReturn("second-tv");
        when(second.getAttributes()).thenReturn(Map.of(
                "client_type", "tv", "client_token", " ", "platform", " ", "protocol_version", "invalid"));

        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(second);

        assertThat(playerRegistry.activeSessionId()).contains("first-tv");
        assertThat(playerRegistry.authorizesUpstream("first-tv", null)).isTrue();
        assertThat(playerRegistry.authorizesUpstream("second-tv", null)).isFalse();
    }

    @Test
    void expiredActiveTvWithoutStandbyDisconnectsAndNotifiesOffline() throws Exception {
        MutableClock clock = installShortLeaseRegistry();
        WebSocketSession active = playerSession("active", "active-token", 2);
        handler.afterConnectionEstablished(active);
        clearInvocations(broadcaster);
        clearInvocations(offlineWatcher);
        clock.advance(Duration.ofSeconds(2));

        handler.handleTextMessage(active, new TextMessage("{\"type\":\"ping\",\"payload\":{\"generation\":1}}"));

        verify(broadcaster).disconnect("active");
        verify(offlineWatcher).onTvDisconnected();
        assertThat(playerRegistry.activeSessionId()).isEmpty();
        verify(broadcaster, never()).sendTo(eq(active), any(WsEvent.class));
    }

    @Test
    void expiredActiveTvPromotesStandbyAndSendsItsFreshSnapshot() throws Exception {
        MutableClock clock = installShortLeaseRegistry();
        WebSocketSession active = playerSession("active", "active-token", 2);
        WebSocketSession standby = playerSession("standby", "standby-token", 2);
        handler.afterConnectionEstablished(active);
        handler.afterConnectionEstablished(standby);
        clearInvocations(broadcaster);
        clearInvocations(offlineWatcher);
        clock.advance(Duration.ofSeconds(2));

        handler.handleTextMessage(active, new TextMessage("{\"type\":\"ping\",\"payload\":{\"generation\":1}}"));

        verify(broadcaster).disconnect("active");
        var events = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster, org.mockito.Mockito.times(2)).sendTo(eq("standby"), events.capture());
        assertThat(events.getAllValues()).extracting(WsEvent::type)
                .containsExactly(WsEvent.PLAYER_ROLE, WsEvent.SYNC_FULL);
        verify(offlineWatcher).onTvConnected();
        verify(offlineWatcher, never()).onTvDisconnected();
        assertThat(playerRegistry.activeSessionId()).contains("standby");
    }

    private WebSocketSession playerSession(String id, String token, int protocolVersion) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(Map.of(
                "client_type", "tv",
                "client_token", token,
                "protocol_version", String.valueOf(protocolVersion),
                "platform", "WINDOWS"));
        return session;
    }

    private WebSocketSession activeLegacySession() {
        String id = "legacy-" + System.nanoTime();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(Map.of("client_type", "tv"));
        playerRegistry.register(id,
                new ActivePlayerRegistry.PlayerHello(id, "LEGACY", 1));
        return session;
    }

    private MutableClock installShortLeaseRegistry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-23T00:00:00Z"), ZoneId.of("UTC"));
        playerRegistry = new ActivePlayerRegistry(clock, Duration.ofSeconds(1));
        handler = new KtvWebSocketHandler(broadcaster, snapshotService, playbackService,
                offlineWatcher, playerRegistry, new ObjectMapper());
        return clock;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(instant, newZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
