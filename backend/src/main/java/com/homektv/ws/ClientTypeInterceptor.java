package com.homektv.ws;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WS 握手拦截器（P2.13）：从查询参数 client_type=tv|h5、client_token 存入会话属性，
 * 供 TV 在线检测与用户标识使用。
 *
 * WebSocket handshake interceptor (P2.13): extracts query parameters
 * {@code client_type=tv|h5} and {@code client_token} into session attributes
 * for TV online detection and user identification.
 */
public class ClientTypeInterceptor implements HandshakeInterceptor {

    /**
     * 在 WebSocket 握手前解析查询参数，将 client_type 和 client_token 存入会话属性。
     *
     * Parses query parameters before the WebSocket handshake and stores
     * {@code client_type} and {@code client_token} in session attributes.
     *
     * @param request  HTTP 握手请求
     * @param response HTTP 握手响应
     * @param wsHandler WebSocket 处理器
     * @param attributes 会话属性映射
     * @return 始终返回 {@code true}，允许握手继续
     */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String query = request.getURI().getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String k = pair.substring(0, eq);
                    String v = pair.substring(eq + 1);
                    if ("client_type".equals(k) || "client_token".equals(k)
                            || "protocol_version".equals(k) || "platform".equals(k)) {
                        attributes.put(k, v);
                    }
                }
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
