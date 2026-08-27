package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.queue.PlaybackService;
import com.homektv.queue.PlaybackTransitionResult;
import com.homektv.queue.PositionUpdateResult;
import com.homektv.queue.SnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KtvWebSocketHandlerTest {

    private WsBroadcaster broadcaster;
    private SnapshotService snapshotService;
    private PlaybackService playbackService;
    private TvOfflineWatcher offlineWatcher;
    private KtvWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        broadcaster = mock(WsBroadcaster.class);
        snapshotService = mock(SnapshotService.class);
        playbackService = mock(PlaybackService.class);
        offlineWatcher = mock(TvOfflineWatcher.class);
        handler = new KtvWebSocketHandler(broadcaster, snapshotService, playbackService,
                offlineWatcher, new ObjectMapper());
    }

    @Test
    void connectionRegistersTvAndSendsFullSnapshot() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("tv-1");
        when(session.getAttributes()).thenReturn(Map.of("client_type", "tv"));

        handler.afterConnectionEstablished(session);

        verify(broadcaster).register(session);
        verify(offlineWatcher).onTvConnected();
        verify(broadcaster).sendTo(eq(session), any(WsEvent.class));
    }

    @Test
    void progressMessageUsesQueueIdentityAndClampsOnlyTheBroadcastPayload() throws Exception {
        when(playbackService.updatePosition(21L, -4L))
                .thenReturn(PositionUpdateResult.accepted(new com.homektv.domain.PlayerState()));

        handler.handleTextMessage(mock(WebSocketSession.class), new TextMessage(
                "{\"type\":\"progress\",\"payload\":{\"queue_id\":21,\"position_ms\":-4}}"));

        verify(playbackService).updatePosition(21L, -4L);
        var event = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster).broadcast(event.capture());
        assertThat(event.getValue().type()).isEqualTo(WsEvent.PROGRESS);
        assertThat(event.getValue().payload()).isInstanceOf(Map.class);
        Map<?, ?> payload = (Map<?, ?>) event.getValue().payload();
        assertThat(payload.get("position_ms")).isEqualTo(0L);
        assertThat(payload.get("queue_id")).isEqualTo(21L);
    }

    @Test
    void staleProgressIsNotBroadcastAfterServiceRejectsIt() throws Exception {
        when(playbackService.updatePosition(21L, 500L))
                .thenReturn(PositionUpdateResult.rejected(new com.homektv.domain.PlayerState()));

        handler.handleTextMessage(mock(WebSocketSession.class), new TextMessage(
                "{\"type\":\"progress\",\"payload\":{\"queue_id\":21,\"position_ms\":500}}"));

        verify(broadcaster, org.mockito.Mockito.never()).broadcast(any(WsEvent.class));
    }

    @Test
    void playErrorMessageForwardsBothFileAndQueueIdentity() throws Exception {
        when(playbackService.onPlayError(7L, 8L))
                .thenReturn(PlaybackTransitionResult.accepted(new com.homektv.domain.PlayerState()));

        handler.handleTextMessage(mock(WebSocketSession.class), new TextMessage(
                "{\"type\":\"play_error\",\"payload\":{\"file_id\":7,\"queue_id\":8,\"message\":\"读取失败\"}}"));

        verify(playbackService).onPlayError(7L, 8L);
        var event = org.mockito.ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster, org.mockito.Mockito.times(2)).broadcast(event.capture());
        List<String> types = new ArrayList<>();
        for (WsEvent value : event.getAllValues()) types.add(value.type());
        assertThat(types).containsExactly(WsEvent.TOAST, WsEvent.NOW_PLAYING);
        assertThat(((Map<?, ?>) event.getAllValues().get(0).payload()).get("text"))
                .asString().contains("读取失败");
    }

    @Test
    void stalePlayErrorDoesNotBroadcastFeedback() throws Exception {
        when(playbackService.onPlayError(10L, 100L))
                .thenReturn(PlaybackTransitionResult.rejected(new com.homektv.domain.PlayerState()));

        handler.handleTextMessage(mock(WebSocketSession.class), new TextMessage(
                "{\"type\":\"play_error\",\"payload\":{\"file_id\":10,\"queue_id\":100,\"message\":\"旧歌曲读取失败\"}}"));

        verify(broadcaster, org.mockito.Mockito.never()).broadcast(any(WsEvent.class));
    }
}
