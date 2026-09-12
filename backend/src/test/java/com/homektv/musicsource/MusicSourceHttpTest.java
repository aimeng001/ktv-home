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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    private static HttpResponse<InputStream> response(InputStream body, String contentLength) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        Map<String, List<String>> headers = contentLength == null
                ? Map.of()
                : Map.of("Content-Length", List.of(contentLength));
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        when(response.body()).thenReturn(body);
        return response;
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
