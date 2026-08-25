package com.homektv.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 统一控制指令请求（详设§4.3）。
 * action: order/shuffle/top/cancel/play/pause/stop/seek/next/restart/set_volume/mute/set_vocal/effect
 *
 * Unified control command request (detailed design §4.3).
 * action: order/shuffle/top/cancel/play/pause/stop/seek/next/restart/set_volume/mute/set_vocal/effect
 */
public record ControlRequest(
        String action,
        Map<String, Object> params,
        @JsonProperty("client_token") String clientToken
) {
    /**
     * 安全获取参数映射，避免空指针。
     *
     * Safely retrieve the parameter map, returning an empty map when null.
     * @return 非空的参数映射 / non-null parameter map
     */
    public Map<String, Object> safeParams() {
        return params == null ? Map.of() : params;
    }

    /**
     * 将指定参数转为 Long 类型，支持 Number 和数字字符串。
     *
     * Convert the specified parameter to Long, supporting Number and numeric string.
     * @param key 参数键 / parameter key
     * @return 参数值（Long），不存在则 null / parameter value as Long, or null if absent
     */
    public Long longParam(String key) {
        Object v = safeParams().get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) return Long.parseLong(s);
        return null;
    }

    /**
     * 将指定参数转为 int 类型，支持 Number 和数字字符串。
     *
     * Convert the specified parameter to int, supporting Number and numeric string.
     * @param key 参数键 / parameter key
     * @param def 默认值 / default value
     * @return 参数值（int），不存在则返回默认值 / parameter value as int, or default if absent
     */
    public int intParam(String key, int def) {
        Object v = safeParams().get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) return Integer.parseInt(s);
        return def;
    }

    /**
     * 将指定参数转为 boolean 类型。
     *
     * Convert the specified parameter to boolean.
     * @param key 参数键 / parameter key
     * @return 参数值（boolean），不存在则 false / parameter value as boolean, false if absent
     */
    public boolean boolParam(String key) {
        Object v = safeParams().get(key);
        if (v instanceof Boolean b) return b;
        return "true".equals(String.valueOf(v));
    }

    /**
     * 将指定参数转为 String 类型。
     *
     * Convert the specified parameter to String.
     * @param key 参数键 / parameter key
     * @return 参数值（String），不存在则 null / parameter value as String, or null if absent
     */
    public String strParam(String key) {
        Object v = safeParams().get(key);
        return v == null ? null : String.valueOf(v);
    }
}
