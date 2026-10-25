package com.homektv.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = MediaStreamLongResponseIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.resource-budget.media-stream-limit=4")
class MediaStreamLongResponseIntegrationTest {
    private static final byte[] EXPECTED_BODY = IntStream.range(0, 40)
            .mapToObj(value -> (byte) value)
            .collect(() -> new java.io.ByteArrayOutputStream(40),
                    java.io.ByteArrayOutputStream::write,
                    (left, right) -> left.writeBytes(right.toByteArray()))
            .toByteArray();

    @LocalServerPort
    private int port;

    @Autowired
    private RequestMappingHandlerAdapter handlerAdapter;

    @Test
    void embeddedMvcHandlerUsesTheConfiguredNoTimeoutValue() {
        assertThat(ReflectionTestUtils.getField(handlerAdapter, "asyncRequestTimeout"))
                .isEqualTo(0L);
    }

    @Test
    void rangeAndLivePipeStreamsFinishAfterTheContainerDefaultAsyncTimeout() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        CompletableFuture<HttpResponse<byte[]>> range = sendSlowStream(client, "/api/stream/test-range");
        CompletableFuture<HttpResponse<byte[]>> pipe = sendSlowStream(client, "/api/playback/stream/test-pipe");

        CompletableFuture.allOf(range, pipe).get(45, TimeUnit.SECONDS);

        assertThat(range.join().statusCode()).isEqualTo(200);
        assertThat(range.join().body()).containsExactly(EXPECTED_BODY);
        assertThat(pipe.join().statusCode()).isEqualTo(200);
        assertThat(pipe.join().body()).containsExactly(EXPECTED_BODY);
    }

    private CompletableFuture<HttpResponse<byte[]>> sendSlowStream(HttpClient client, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(45))
                .GET()
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    @Import({MediaStreamExecutorConfiguration.class, TranscodeAsyncWebConfig.class, SlowStreamController.class})
    static class TestApplication {
    }

    @RestController
    static class SlowStreamController {
        @GetMapping(value = "/api/stream/test-range", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        StreamingResponseBody rangeStream() {
            return slowResponse();
        }

        @GetMapping(value = "/api/playback/stream/test-pipe", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        StreamingResponseBody pipeStream() {
            return slowResponse();
        }

        private StreamingResponseBody slowResponse() {
            return output -> {
                for (byte value : EXPECTED_BODY) {
                    output.write(value);
                    output.flush();
                    try {
                        Thread.sleep(850);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("slow stream interrupted", interrupted);
                    }
                }
            };
        }
    }
}
