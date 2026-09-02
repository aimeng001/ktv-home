package com.homektv.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.QueueItem;
import com.homektv.queue.PlaybackService;
import com.homektv.queue.QueueService;
import com.homektv.queue.RoomHostService;
import com.homektv.queue.SnapshotService;
import com.homektv.queue.UserService;
import com.homektv.repo.QueueItemRepository;
import com.homektv.web.dto.ControlRequest;
import com.homektv.web.dto.QueueSnapshot;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlControllerTest {

    @Test
    void anyUserCanShuffleWaitingQueue() {
        AtomicBoolean shuffled = new AtomicBoolean();
        AtomicReference<WsEvent> broadcast = new AtomicReference<>();
        QueueSnapshot snapshot = new QueueSnapshot(null, List.of(), "idle", 60, false,
                "accompaniment", true, 1);
        QueueService queueService = new QueueService(null, null, null, null) {
            @Override public List<QueueItem> shuffleWaiting() {
                shuffled.set(true);
                return List.of();
            }
        };
        SnapshotService snapshotService = new SnapshotService(null, null, null, null, null) {
            @Override public QueueSnapshot snapshot() { return snapshot; }
        };
        UserService userService = new UserService(null) {
            @Override public Long resolveUserId(String clientToken) { return 42L; }
        };
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper()) {
            @Override public void broadcastPlayback(WsEvent event) { broadcast.set(event); }
        };
        PlaybackService playbackService = new PlaybackService(null, null, null, null, null);
        ControlController controller = new ControlController(
                queueService, playbackService, snapshotService, userService, null, broadcaster, null);

        QueueSnapshot result = controller.control(
                new ControlRequest("shuffle", Map.of(), "guest-token"));

        assertThat(shuffled).isTrue();
        assertThat(broadcast.get().type()).isEqualTo(WsEvent.QUEUE_UPDATED);
        assertThat(result).isSameAs(snapshot);
    }

    @Test
    void restartBroadcastsDedicatedEventForTvSeek() {
        AtomicReference<WsEvent> broadcast = new AtomicReference<>();
        QueueSnapshot snapshot = new QueueSnapshot(null, List.of(), "playing", 60, false,
                "accompaniment", true, 1);
        PlaybackService playbackService = new PlaybackService(null, null, null, null, null) {
            @Override public com.homektv.domain.PlayerState restart() { return null; }
        };
        SnapshotService snapshotService = new SnapshotService(null, null, null, null, null) {
            @Override public QueueSnapshot snapshot() { return snapshot; }
        };
        UserService userService = new UserService(null) {
            @Override public Long resolveUserId(String clientToken) { return 42L; }
        };
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper()) {
            @Override public void broadcastPlayback(WsEvent event) { broadcast.set(event); }
        };
        ControlController controller = new ControlController(
                null, playbackService, snapshotService, userService, null, broadcaster, null);

        controller.control(new ControlRequest("restart", Map.of(), "guest-token"));

        assertThat(broadcast.get().type()).isEqualTo(WsEvent.PLAYBACK_RESTARTED);
    }

    @Test
    void stopUsesTheSharedPlayerStateEvent() {
        AtomicReference<WsEvent> broadcast = new AtomicReference<>();
        AtomicBoolean stopped = new AtomicBoolean();
        QueueSnapshot snapshot = new QueueSnapshot(null, List.of(), "idle", 60, false,
                "accompaniment", true, 1);
        PlaybackService playbackService = new PlaybackService(null, null, null, null, null) {
            @Override public com.homektv.domain.PlayerState stop() {
                stopped.set(true);
                return null;
            }
        };
        SnapshotService snapshotService = new SnapshotService(null, null, null, null, null) {
            @Override public QueueSnapshot snapshot() { return snapshot; }
        };
        UserService userService = new UserService(null) {
            @Override public Long resolveUserId(String clientToken) { return 42L; }
        };
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper()) {
            @Override public void broadcastPlayback(WsEvent event) { broadcast.set(event); }
        };
        ControlController controller = new ControlController(
                null, playbackService, snapshotService, userService, null, broadcaster, null);

        controller.control(new ControlRequest("stop", Map.of(), "guest-token"));

        assertThat(stopped).isTrue();
        assertThat(broadcast.get().type()).isEqualTo(WsEvent.PLAYER_STATE);
    }

    @Test
    void seekUsesAPlatformNeutralPositionEvent() {
        AtomicReference<WsEvent> broadcast = new AtomicReference<>();
        AtomicLong position = new AtomicLong();
        QueueSnapshot snapshot = new QueueSnapshot(null, List.of(), "playing", 60, false,
                "accompaniment", true, 1);
        PlaybackService playbackService = new PlaybackService(null, null, null, null, null) {
            @Override public com.homektv.domain.PlayerState seek(long positionMs) {
                position.set(positionMs);
                return null;
            }
        };
        SnapshotService snapshotService = new SnapshotService(null, null, null, null, null) {
            @Override public QueueSnapshot snapshot() { return snapshot; }
        };
        UserService userService = new UserService(null) {
            @Override public Long resolveUserId(String clientToken) { return 42L; }
        };
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper()) {
            @Override public void broadcastPlayback(WsEvent event) { broadcast.set(event); }
        };
        ControlController controller = new ControlController(
                null, playbackService, snapshotService, userService, null, broadcaster, null);

        controller.control(new ControlRequest("seek", Map.of("position_ms", 12_345L), "guest-token"));

        assertThat(position).hasValue(12_345L);
        assertThat(broadcast.get().type()).isEqualTo(WsEvent.PLAYBACK_SEEKED);
    }

    @Test
    void cancel_asRoomHost_allowsDeletingOtherUsersSong() {
        AtomicBoolean cancelled = new AtomicBoolean();
        QueueSnapshot snapshot = new QueueSnapshot(null, List.of(), "idle", 60, false, "accompaniment", true, 1);
        QueueService queueService = new QueueService(null, null, null, null) {
            @Override public void cancel(Long queueId) { cancelled.set(true); }
        };
        SnapshotService snapshotService = new SnapshotService(null, null, null, null, null) {
            @Override public QueueSnapshot snapshot() { return snapshot; }
        };
        UserService userService = new UserService(null) {
            @Override public Long resolveUserId(String clientToken) { return 99L; }
        };
        QueueItem item = new QueueItem();
        item.setId(10L);
        item.setOrderedBy(42L); // ordered by user 42, host is user 99
        item.setStatus(QueueService.WAITING);

        QueueItemRepository queueRepo = (QueueItemRepository) Proxy.newProxyInstance(
                QueueItemRepository.class.getClassLoader(),
                new Class<?>[]{QueueItemRepository.class},
                (proxy, method, args) -> "findById".equals(method.getName()) ? Optional.of(item) : null);

        RoomHostService roomHostService = new RoomHostService(null, null) {
            @Override public boolean isHost(String clientToken) { return "host-token".equals(clientToken); }
        };

        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper()) {
            @Override public void broadcastPlayback(WsEvent event) {}
        };

        ControlController controller = new ControlController(
                queueService, null, snapshotService, userService, queueRepo, broadcaster, roomHostService);

        QueueSnapshot result = controller.control(new ControlRequest("cancel", Map.of("queue_id", 10L), "host-token"));

        assertThat(cancelled).isTrue();
        assertThat(result).isSameAs(snapshot);
    }

    @Test
    void cancel_asNonHost_rejectsDeletingOtherUsersSong() {
        UserService userService = new UserService(null) {
            @Override public Long resolveUserId(String clientToken) { return 99L; }
        };
        QueueItem item = new QueueItem();
        item.setId(10L);
        item.setOrderedBy(42L); // ordered by user 42, requester is user 99
        item.setStatus(QueueService.WAITING);

        QueueItemRepository queueRepo = (QueueItemRepository) Proxy.newProxyInstance(
                QueueItemRepository.class.getClassLoader(),
                new Class<?>[]{QueueItemRepository.class},
                (proxy, method, args) -> "findById".equals(method.getName()) ? Optional.of(item) : null);

        RoomHostService roomHostService = new RoomHostService(null, null) {
            @Override public boolean isHost(String clientToken) { return false; }
        };

        ControlController controller = new ControlController(
                null, null, null, userService, queueRepo, null, roomHostService);

        assertThatThrownBy(() -> controller.control(new ControlRequest("cancel", Map.of("queue_id", 10L), "guest-token")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("只能删除自己点的歌曲");
    }
}
