package com.homektv.musicsource;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

class MusicSourceHttpTest {

    @Test
    void cappedReaderReturnsBodyAtTheLimit() throws IOException {
        byte[] body = new byte[MusicSourceHttp.MAX_BODY_BYTES];

        assertThat(MusicSourceHttp.readAtMost(new ByteArrayInputStream(body), MusicSourceHttp.MAX_BODY_BYTES))
                .hasSize(MusicSourceHttp.MAX_BODY_BYTES);
    }

    @Test
    void cappedReaderRejectsChunkedBodyAfterTheLimit() {
        byte[] body = new byte[MusicSourceHttp.MAX_BODY_BYTES + 1];

        assertThatThrownBy(() -> MusicSourceHttp.readAtMost(
                new ByteArrayInputStream(body), MusicSourceHttp.MAX_BODY_BYTES))
                .isInstanceOf(IOException.class)
                .hasMessage("response body exceeds configured limit");
    }

    @Test
    void declaredOversizedBodyIsRejectedAndClosedBeforeReading() throws Exception {
        HttpClient client = mock(HttpClient.class);
        TrackingInputStream body = new TrackingInputStream(new byte[0]);
        HttpResponse<InputStream> response = response(body, String.valueOf(MusicSourceHttp.MAX_BODY_BYTES + 1));
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<InputStream>>any())).thenReturn(response);
        MusicSourceHttp http = new MusicSourceHttp(
                new ObjectMapper(), MusicProvider.QQ, Set.of("music.example"), client);

        assertThatThrownBy(() -> http.get(
                "https://music.example/metadata", Map.of(), Duration.ofSeconds(1)))
                .isInstanceOf(MusicSourceException.class)
                .hasMessage("上游响应过大");
        assertThat(body.closed.get()).isTrue();
    }

    @Test
    void chunkedOversizedBodyIsRejectedAndClosed() throws Exception {
        HttpClient client = mock(HttpClient.class);
        TrackingInputStream body = new TrackingInputStream(new byte[MusicSourceHttp.MAX_BODY_BYTES + 1]);
        HttpResponse<InputStream> response = response(body, null);
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<InputStream>>any())).thenReturn(response);
        MusicSourceHttp http = new MusicSourceHttp(
                new ObjectMapper(), MusicProvider.QQ, Set.of("music.example"), client);

        assertThatThrownBy(() -> http.get(
                "https://music.example/metadata", Map.of(), Duration.ofSeconds(1)))
                .isInstanceOf(MusicSourceException.class)
                .hasMessage("上游响应过大");
        assertThat(body.closed.get()).isTrue();
    }

    @Test
    void appliesTheProviderGateToEveryActualHttpRequest() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<InputStream> first = response(new ByteArrayInputStream("{}".getBytes()), null);
        HttpResponse<InputStream> second = response(new ByteArrayInputStream("{}".getBytes()), null);
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(first, second);
        ProviderCallGuard guard = mock(ProviderCallGuard.class);
        when(guard.call(eq(MusicProvider.QQ), any())).thenAnswer(invocation -> {
            Supplier<JsonNode> request = invocation.getArgument(1);
            return request.get();
        });
        MusicSourceHttp http = new MusicSourceHttp(
                new ObjectMapper(), MusicProvider.QQ, Set.of("music.example"), client, guard);

        http.get("https://music.example/one", Map.of(), Duration.ofSeconds(1));
        http.get("https://music.example/two", Map.of(), Duration.ofSeconds(1));

        verify(guard, times(2)).call(eq(MusicProvider.QQ), any());
        verify(client, times(2)).send(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<InputStream>>any());
    }

    @Test
    void ungatedFormAndGetRequestsUseAllowlistedHttpsAndSafeHeaders() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<InputStream> first = response(new ByteArrayInputStream("{}".getBytes()), null);
        HttpResponse<InputStream> second = response(new ByteArrayInputStream("{}".getBytes()), null);
        Map<String, String> formFields = new LinkedHashMap<>();
        formFields.put("q", "晴天 &+");
        formFields.put("page", "2");
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(first, second);
        MusicSourceHttp http = new MusicSourceHttp(
                new ObjectMapper(), MusicProvider.QQ, Set.of("music.example"), client);

        http.form("https://music.example/search", formFields, Map.of(), Duration.ofSeconds(2));
        http.get("https://music.example/metadata", Map.of("uSeR-aGeNt", "custom-agent"),
                Duration.ofSeconds(3));

        org.mockito.ArgumentCaptor<HttpRequest> requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(requests.capture(), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<InputStream>>any());
        HttpRequest form = requests.getAllValues().get(0);
        assertThat(form.method()).isEqualTo("POST");
        assertThat(form.headers().firstValue("Content-Type"))
                .contains("application/x-www-form-urlencoded");
        assertThat(form.headers().firstValue("Accept")).contains("application/json");
        assertThat(form.headers().firstValue("User-Agent"))
                .contains("HomeKTV/0.1 metadata-only");
        assertThat(form.timeout()).contains(Duration.ofSeconds(2));
        assertThat(MusicSourceHttp.query(formFields))
                .isEqualTo("q=%E6%99%B4%E5%A4%A9+%26%2B&page=2");
        HttpRequest get = requests.getAllValues().get(1);
        assertThat(get.method()).isEqualTo("GET");
        assertThat(get.headers().firstValue("User-Agent")).contains("custom-agent");
        assertThat(get.timeout()).contains(Duration.ofSeconds(3));
    }

    @Test
    void nonSuccessResponsePreservesStatusRetryAfterAndClosesTheBody() throws Exception {
        HttpClient client = mock(HttpClient.class);
        TrackingInputStream body = new TrackingInputStream(new byte[0]);
        HttpResponse<InputStream> rateLimited = response(429, body, Map.of("Retry-After", List.of("15")));
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(rateLimited);
        MusicSourceHttp http = new MusicSourceHttp(
                new ObjectMapper(), MusicProvider.QQ, Set.of("music.example"), client);
        Instant before = Instant.now();

        assertThatThrownBy(() -> http.get(
                "https://music.example/metadata", Map.of(), Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(ProviderHttpException.class, failure -> {
                    assertThat(failure.statusCode()).isEqualTo(429);
                    assertThat(failure.retryAfter()).isAfterOrEqualTo(before);
                });
        assertThat(body.closed.get()).isTrue();
    }

    @Test
    void retryAfterParsesSecondsHttpDatesAndInvalidOrBlankValues() {
        assertThat(MusicSourceHttp.parseRetryAfter(headers(Map.of()))).isNull();
        assertThat(MusicSourceHttp.parseRetryAfter(headers(Map.of("Retry-After", List.of(""))))).isNull();
        assertThat(MusicSourceHttp.parseRetryAfter(headers(Map.of("Retry-After", List.of("-4")))))
                .isBetween(Instant.now().minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(MusicSourceHttp.parseRetryAfter(headers(Map.of(
                "Retry-After", List.of("Wed, 21 Oct 2015 07:28:00 GMT")))))
                .isEqualTo(Instant.parse("2015-10-21T07:28:00Z"));
        assertThat(MusicSourceHttp.parseRetryAfter(headers(Map.of("Retry-After", List.of("not-a-date")))))
                .isNull();
    }

    @Test
    void interruptedHttpCallRestoresInterruptAndOtherTransportFailuresAreWrapped() throws Exception {
        HttpClient interruptedClient = mock(HttpClient.class);
        when(interruptedClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<InputStream>>any()))
                .thenThrow(new InterruptedException("cancelled"));
        MusicSourceHttp interruptedHttp = new MusicSourceHttp(
                new ObjectMapper(), MusicProvider.QQ, Set.of("music.example"), interruptedClient);
        try {
            assertThatThrownBy(() -> interruptedHttp.get(
                    "https://music.example/metadata", Map.of(), Duration.ofSeconds(1)))
                    .isInstanceOf(MusicSourceException.class)
                    .hasMessage("平台请求失败");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        HttpClient failedClient = mock(HttpClient.class);
        when(failedClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<InputStream>>any()))
                .thenThrow(new IOException("connection reset"));
        MusicSourceHttp failedHttp = new MusicSourceHttp(
                new ObjectMapper(), MusicProvider.QQ, Set.of("music.example"), failedClient);
        assertThatThrownBy(() -> failedHttp.get(
                "https://music.example/metadata", Map.of(), Duration.ofSeconds(1)))
                .isInstanceOf(MusicSourceException.class)
                .hasMessage("平台请求失败");
    }

    @Test
    void rejectsNonHttpsAndUnlistedHostsBeforeSending() throws Exception {
        HttpClient client = mock(HttpClient.class);
        MusicSourceHttp http = new MusicSourceHttp(
                new ObjectMapper(), MusicProvider.QQ, Set.of("music.example"), client);

        assertThatThrownBy(() -> http.get("http://music.example/metadata", Map.of(), Duration.ofSeconds(1)))
                .isInstanceOf(MusicSourceException.class)
                .hasMessage("请求地址不在平台白名单内");
        assertThatThrownBy(() -> http.get("https://attacker.example/metadata", Map.of(), Duration.ofSeconds(1)))
                .isInstanceOf(MusicSourceException.class)
                .hasMessage("请求地址不在平台白名单内");
        verify(client, never()).send(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<InputStream>>any());
    }

    @Test
    void cappedReaderSkipsZeroLengthReadsAndRejectsNegativeLimits() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        InputStream intermittent = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("read(byte[], offset, length) should be used");
            }

            @Override
            public int read(byte[] target, int offset, int length) {
                if (reads.getAndIncrement() == 0) return 0;
                if (reads.get() == 2) {
                    target[offset] = 'x';
                    return 1;
                }
                return -1;
            }
        };

        assertThat(new String(MusicSourceHttp.readAtMost(intermittent, 1)))
                .isEqualTo("x");
        assertThatThrownBy(() -> MusicSourceHttp.readAtMost(new ByteArrayInputStream(new byte[0]), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must not be negative");
    }

    private static HttpResponse<InputStream> response(InputStream body, String contentLength) {
        Map<String, List<String>> headers = contentLength == null
                ? Map.of()
                : Map.of("Content-Length", List.of(contentLength));
        return response(200, body, headers);
    }

    private static HttpResponse<InputStream> response(int status, InputStream body,
                                                      Map<String, List<String>> headers) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        when(response.body()).thenReturn(body);
        return response;
    }

    private static HttpHeaders headers(Map<String, List<String>> values) {
        return HttpHeaders.of(values, (name, value) -> true);
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private final AtomicBoolean closed = new AtomicBoolean();

        private TrackingInputStream(byte[] data) {
            super(data);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
