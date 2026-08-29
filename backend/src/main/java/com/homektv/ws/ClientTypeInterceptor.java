package com.homektv.ws;

import com.homektv.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
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

    private final AppProperties properties;

    public ClientTypeInterceptor() {
        this(new AppProperties());
    }

    public ClientTypeInterceptor(AppProperties properties) {
        this.properties = properties;
    }

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
     * @return 通过来源和可选 TV 凭据校验时返回 {@code true}
     */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!sameOriginIfBrowser(request, response)) {
            return false;
        }

        Map<String, String> parameters;
        try {
            parameters = queryParameters(request.getURI().getRawQuery());
        } catch (IllegalArgumentException ex) {
            reject(response);
            return false;
        }

        String clientType = parameters.get("client_type");
        if ("tv".equalsIgnoreCase(clientType)
                && !matchesConfiguredCredential(parameters.get("player_credential"))) {
            reject(response);
            return false;
        }

        for (String key : new String[]{"client_type", "client_token", "protocol_version", "platform"}) {
            String value = parameters.get(key);
            if (value != null) {
                attributes.put(key, value);
            }
        }
        return true;
    }

    private Map<String, String> queryParameters(String query) {
        Map<String, String> parameters = new HashMap<>();
        if (query == null || query.isBlank()) {
            return parameters;
        }
        for (String pair : query.split("&", -1)) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            parameters.putIfAbsent(key, value);
        }
        return parameters;
    }

    private boolean matchesConfiguredCredential(String supplied) {
        String expected = properties.getPlayerCredential();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        if (supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private boolean sameOriginIfBrowser(ServerHttpRequest request, ServerHttpResponse response) {
        String origin = request.getHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            URI originUri = URI.create(origin);
            URI requestUri = request.getURI();
            if (originUri.getScheme() == null || originUri.getHost() == null
                    || !originUri.getScheme().equalsIgnoreCase(requestUri.getScheme())
                    || !originUri.getHost().equalsIgnoreCase(requestUri.getHost())
                    || effectivePort(originUri) != effectivePort(requestUri)) {
                reject(response);
                return false;
            }
            return true;
        } catch (IllegalArgumentException ex) {
            reject(response);
            return false;
        }
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void reject(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
