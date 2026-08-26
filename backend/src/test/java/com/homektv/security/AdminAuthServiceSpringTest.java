package com.homektv.security;

import com.homektv.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthServiceSpringTest {
    @Test
    void springCanConstructServiceWithItsClockTestSeam() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AppProperties.class);
            context.registerBean(AdminAuthService.class);
            context.refresh();

            assertThat(context.getBean(AdminAuthService.class)).isNotNull();
        }
    }
}
