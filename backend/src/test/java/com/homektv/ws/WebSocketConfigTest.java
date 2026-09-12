package com.homektv.ws;

import com.homektv.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class WebSocketConfigTest {

    @Test
    void configuresTheContainerToRejectOversizedTextBeforeHandlerAssembly() {
        WebSocketConfig config = new WebSocketConfig(mock(KtvWebSocketHandler.class), new AppProperties());

        ServletServerContainerFactoryBean container = config.webSocketContainer();

        assertThat(container.getMaxTextMessageBufferSize()).isEqualTo(1_048_576);
    }

    @Test
    void mockApplicationContextDoesNotRequireAServletWebSocketContainer() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(KtvWebSocketHandler.class, () -> mock(KtvWebSocketHandler.class));
            context.registerBean(AppProperties.class, AppProperties::new);
            context.register(WebSocketConfig.class);

            assertThatCode(context::refresh).doesNotThrowAnyException();
        }
    }
}
