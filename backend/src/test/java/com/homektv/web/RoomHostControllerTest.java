package com.homektv.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.queue.RoomHostService;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RoomHostControllerTest {
    @Test
    void claimBroadcastsTheAuthoritativeHostStatus() {
        Map<String, Object> status = Map.of(
                "claimed", true,
                "hostUserId", 7L,
                "hostNickname", "小明",
                "revision", 4L,
                "isHost", true);
        AtomicReference<WsEvent> event = new AtomicReference<>();
        RoomHostController controller = controller(status, event);

        assertThat(controller.claim(new RoomHostController.HostRequest("token"))).isSameAs(status);
        assertThat(event.get().type()).isEqualTo(WsEvent.ROOM_HOST_CHANGED);
        @SuppressWarnings("unchecked")
        Map<String, Object> broadcastPayload = (Map<String, Object>) event.get().payload();
        assertThat(broadcastPayload.get("hostUserId")).isEqualTo(7L);
        assertThat(broadcastPayload).doesNotContainKey("isHost");
    }

    @Test
    void releaseBroadcastsTheAuthoritativeHostStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("claimed", false);
        status.put("hostUserId", null);
        status.put("hostNickname", null);
        status.put("revision", 5L);
        status.put("isHost", false);
        AtomicReference<WsEvent> event = new AtomicReference<>();
        RoomHostController controller = controller(status, event);

        assertThat(controller.release(new RoomHostController.HostRequest("token"))).isSameAs(status);
        assertThat(event.get().type()).isEqualTo(WsEvent.ROOM_HOST_CHANGED);
        @SuppressWarnings("unchecked")
        Map<String, Object> broadcastPayload = (Map<String, Object>) event.get().payload();
        assertThat(broadcastPayload.get("claimed")).isEqualTo(false);
        assertThat(broadcastPayload).doesNotContainKey("isHost");
    }

    private static RoomHostController controller(
            Map<String, Object> status,
            AtomicReference<WsEvent> event) {
        RoomHostService service = new RoomHostService(null, null) {
            @Override public Map<String, Object> claim(String token) { return status; }
            @Override public Map<String, Object> release(String token) { return status; }
        };
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper()) {
            @Override public void broadcast(WsEvent value) { event.set(value); }
        };
        return new RoomHostController(service, broadcaster);
    }
}
