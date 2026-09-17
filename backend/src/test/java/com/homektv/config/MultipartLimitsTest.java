package com.homektv.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultipartLimitsTest {

    @Test
    void multipartParserHasExplicitBoundedLimitsBeforeControllersReadBytes() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load(
                "application",
                new ClassPathResource("application.yml")
        );
        PropertySource<?> application = sources.get(0);

        assertThat(application.getProperty("spring.servlet.multipart.max-file-size"))
                .isEqualTo("10MB");
        assertThat(application.getProperty("spring.servlet.multipart.max-request-size"))
                .isEqualTo("12MB");
        assertThat(application.getProperty("spring.servlet.multipart.file-size-threshold"))
                .isEqualTo("0B");
    }
}
