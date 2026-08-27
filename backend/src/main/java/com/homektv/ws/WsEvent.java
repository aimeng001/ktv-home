package com.homektv.ws;

/**
 * WebSocket 广播事件（详设§4.2）。type + payload 的 JSON 消息。
 *
 * WebSocket broadcast event (Detailed Design §4.2).
 * A JSON message consisting of type + payload.
 */
public record WsEvent(String type, Object payload) {

    // 事件类型常量（详设§4.2）
    // Event type constants (Detailed Design §4.2)
    public static final String SYNC_FULL = "sync_full";
    public static final String QUEUE_UPDATED = "queue_updated";
    public static final String NOW_PLAYING = "now_playing";
    public static final String PLAYER_STATE = "player_state";
    public static final String PLAYBACK_RESTARTED = "playback_restarted";
    public static final String PLAYBACK_SEEKED = "playback_seeked";
    public static final String PROGRESS = "progress";
    public static final String VOLUME_CHANGED = "volume_changed";
    public static final String VOCAL_CHANGED = "vocal_changed";
    public static final String EFFECT_PLAY = "effect_play";
    public static final String TOAST = "toast";
    public static final String PLAYER_ROLE = "player_role";

    /**
     * 创建 WsEvent 实例的静态工厂方法。
     *
     * Static factory method to create a WsEvent instance.
     *
     * @param type    事件类型 / event type
     * @param payload 事件负载 / event payload
     * @return 新的 WsEvent 实例 / a new WsEvent instance
     */
    public static WsEvent of(String type, Object payload) {
        return new WsEvent(type, payload);
    }
}
