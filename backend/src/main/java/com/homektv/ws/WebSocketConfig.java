package com.homektv.ws;

import com.homektv.config.AppProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 注册（详设§4.1）。端点 /ws；浏览器来源由握手拦截器校验。
 *
 * WebSocket registration (detailed design §4.1). Endpoint /ws; browser origins are checked by the handshake interceptor.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final KtvWebSocketHandler handler;
    private final AppProperties properties;

    public WebSocketConfig(KtvWebSocketHandler handler, AppProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    /**
     * 注册 WebSocket 处理器到 /ws 端点并添加客户端类型拦截器。
     *
     * Registers the WebSocket handler at the /ws endpoint and adds the client-type interceptor.
     *
     * @param registry WebSocket 处理器注册表 / WebSocket handler registry
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
                .addInterceptors(new ClientTypeInterceptor(properties));
    }
}
