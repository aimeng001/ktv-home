package com.homektv.musicsource;

import com.homektv.library.AssetWriter;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalCoverServiceTest {

    @Test
    void cacheIsWrittenOnlyAfterTheGuardedHttpOperationCompletes() throws Exception {
        AssetWriter writer = mock(AssetWriter.class);
        ProviderCallGuard guard = mock(ProviderCallGuard.class);
        CoverImageNormalizer normalizer = mock(CoverImageNormalizer.class);
        HttpClient client = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);

        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<InputStream>>any())).thenReturn(response);
        when(normalizer.normalize(any(byte[].class))).thenReturn(new byte[]{2});
        when(writer.writeArtistCover("artist-key", new byte[]{2}, "jpg"))
                .thenReturn("artist-covers/avatar.jpg");

        doAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(1);
            Object result = operation.get();
            verifyNoInteractions(writer);
            return result;
        }).when(guard).call(eq(MusicProvider.QQ), any());

        ExternalCoverService service = service(writer, guard, normalizer, client,
                ExternalCoverServiceTest::publicAddress);

        org.assertj.core.api.Assertions.assertThat(service.downloadArtistAvatar(
                MusicProvider.QQ, "https://y.gtimg.cn/avatar.jpg", "artist-key", Duration.ofSeconds(1)))
                .isEqualTo("artist-covers/avatar.jpg");

        verify(writer).writeArtistCover("artist-key", new byte[]{2}, "jpg");
    }

    @Test
    void rejectsMissingSchemeAndNonWhitelistedUrlsBeforeCallingNetwork() {
        AssetWriter writer = mock(AssetWriter.class);
        ProviderCallGuard guard = mock(ProviderCallGuard.class);
        CoverImageNormalizer normalizer = mock(CoverImageNormalizer.class);
        HttpClient client = mock(HttpClient.class);
        ExternalCoverService service = service(writer, guard, normalizer, client,
                ExternalCoverServiceTest::publicAddress);

        assertCode("EXTERNAL_COVER_MISSING", () -> service.download(MusicProvider.QQ, null, "key", Duration.ofSeconds(1)));
        assertCode("EXTERNAL_COVER_MISSING", () -> service.download(MusicProvider.QQ, " ", "key", Duration.ofSeconds(1)));
        assertCode("EXTERNAL_COVER_URL_INVALID", () -> service.download(
                MusicProvider.QQ, "http://y.gtimg.cn/cover.jpg", "key", Duration.ofSeconds(1)));
        assertCode("EXTERNAL_COVER_URL_INVALID", () -> service.download(
                MusicProvider.QQ, "https://evil.example/cover.jpg", "key", Duration.ofSeconds(1)));
        assertCode("EXTERNAL_COVER_URL_INVALID", () -> service.download(
                MusicProvider.QQ, "https:///cover.jpg", "key", Duration.ofSeconds(1)));
        assertCode("EXTERNAL_COVER_URL_INVALID", () -> service.download(
                MusicProvider.NETEASE, "https://y.gtimg.cn/cover.jpg", "key", Duration.ofSeconds(1)));

        verifyNoInteractions(guard, client, normalizer, writer);
    }

    @Test
    void rejectsLocalAndPrivateAddressesForAnOtherwiseAllowedProviderHost() throws Exception {
        byte[][] addresses = {
                {0, 0, 0, 0},
                {127, 0, 0, 1},
                {(byte) 169, (byte) 254, 1, 2},
                {10, 1, 2, 3},
                {(byte) 224, 0, 0, 1}
        };

        for (byte[] bytes : addresses) {
            InetAddress address = InetAddress.getByAddress(bytes);
            ExternalCoverService service = service(mock(AssetWriter.class), mock(ProviderCallGuard.class),
                    mock(CoverImageNormalizer.class), mock(HttpClient.class), host -> new InetAddress[]{address});

            assertCode("EXTERNAL_COVER_URL_INVALID", () -> service.download(
                    MusicProvider.QQ, "https://y.gtimg.cn/cover.jpg", "key", Duration.ofSeconds(1)));
        }
    }

    @Test
    void rejectsIpv6UnspecifiedLoopbackLinkLocalSiteLocalAndMulticastAddresses() throws Exception {
        byte[][] addresses = {
                new byte[16],
                hex("00000000000000000000000000000001"),
                hex("fe800000000000000000000000000001"),
                hex("fec00000000000000000000000000001"),
                hex("ff020000000000000000000000000001")
        };

        for (byte[] bytes : addresses) {
            InetAddress address = InetAddress.getByAddress(bytes);
            ExternalCoverService service = service(mock(AssetWriter.class), mock(ProviderCallGuard.class),
                    mock(CoverImageNormalizer.class), mock(HttpClient.class), host -> new InetAddress[]{address});

            assertCode("EXTERNAL_COVER_URL_INVALID", () -> service.download(
                    MusicProvider.QQ, "https://y.gtimg.cn/cover.jpg", "key", Duration.ofSeconds(1)));
        }
    }

    @Test
    void reportsDnsFailureWithoutSendingARequest() {
        HttpClient client = mock(HttpClient.class);
        ExternalCoverService service = service(mock(AssetWriter.class), mock(ProviderCallGuard.class),
                mock(CoverImageNormalizer.class), client, host -> { throw new UnknownHostException(host); });

        assertCode("EXTERNAL_COVER_DNS_FAILED", () -> service.download(
                MusicProvider.QQ, "https://y.gtimg.cn/cover.jpg", "key", Duration.ofSeconds(1)));
        verifyNoInteractions(client);
    }

    @Test
    void acceptsAnAllowedSubdomainAndWritesNormalizedCoverBytes() throws Exception {
        AssetWriter writer = mock(AssetWriter.class);
        ProviderCallGuard guard = guardedAction();
        CoverImageNormalizer normalizer = mock(CoverImageNormalizer.class);
        HttpClient client = mock(HttpClient.class);
        stubResponse(client, 200, Map.of(), new ByteArrayInputStream(new byte[]{4, 5}));
        when(normalizer.normalize(new byte[]{4, 5})).thenReturn(new byte[]{9});
        when(writer.writeCover("fingerprint", new byte[]{9}, "jpg")).thenReturn("covers/fingerprint.jpg");
        ExternalCoverService service = service(writer, guard, normalizer, client,
                ExternalCoverServiceTest::publicAddress);

        assertThat(service.download(MusicProvider.QQ, "https://img.y.gtimg.cn/cover.jpg",
                "fingerprint", Duration.ofSeconds(1))).isEqualTo("covers/fingerprint.jpg");

        verify(writer).writeCover("fingerprint", new byte[]{9}, "jpg");
    }

    @Test
    void rejectsOversizedDeclaredResponseBeforeReadingBody() throws Exception {
        TrackingInputStream body = new TrackingInputStream(new byte[]{1, 2, 3});
        ExternalCoverService service = responseService(200,
                Map.of("Content-Length", List.of("5242881")), body);

        assertCode("EXTERNAL_COVER_TOO_LARGE", () -> service.download(
                MusicProvider.QQ, "https://y.gtimg.cn/cover.jpg", "key", Duration.ofSeconds(1)));
        assertThat(body.bytesRead()).isZero();
        assertThat(body.closed.get()).isTrue();
    }

    @Test
    void rejectsEmptyAndOverLimitBodiesWhenContentLengthIsUnknown() throws Exception {
        ExternalCoverService empty = responseService(200, Map.of(), new ByteArrayInputStream(new byte[0]));
        assertCode("EXTERNAL_COVER_TOO_LARGE", () -> empty.download(
                MusicProvider.QQ, "https://y.gtimg.cn/cover.jpg", "key", Duration.ofSeconds(1)));

        TrackingInputStream oversizedBody = new TrackingInputStream(new byte[5 * 1024 * 1024 + 1]);
        ExternalCoverService oversized = responseService(200, Map.of(), oversizedBody);
        assertCode("EXTERNAL_COVER_TOO_LARGE", () -> oversized.download(
                MusicProvider.QQ, "https://y.gtimg.cn/cover.jpg", "key", Duration.ofSeconds(1)));
        assertThat(oversizedBody.bytesRead()).isEqualTo(5 * 1024 * 1024 + 1);
        assertThat(oversizedBody.closed.get()).isTrue();
    }

    @Test
    void preservesProviderHttpFailureWithoutNormalizingOrWritingItsBody() throws Exception {
        AssetWriter writer = mock(AssetWriter.class);
        CoverImageNormalizer normalizer = mock(CoverImageNormalizer.class);
        ExternalCoverService service = responseService(writer, normalizer, 503, Map.of(),
                new ByteArrayInputStream(new byte[]{1}));

        assertThatThrownBy(() -> service.download(
                MusicProvider.QQ, "https://y.gtimg.cn/cover.jpg", "key", Duration.ofSeconds(1)))
                .isInstanceOf(ProviderHttpException.class)
                .extracting(error -> ((ProviderHttpException) error).statusCode())
                .isEqualTo(503);
        verifyNoInteractions(writer, normalizer);
    }

    @Test
    void rejectsHttpResponsesBelowTheSuccessRange() throws Exception {
        ExternalCoverService service = responseService(199, Map.of(), new ByteArrayInputStream(new byte[]{1}));

        assertThatThrownBy(() -> service.download(
                MusicProvider.QQ, "https://y.gtimg.cn/cover.jpg", "key", Duration.ofSeconds(1)))
                .isInstanceOf(ProviderHttpException.class)
                .extracting(error -> ((ProviderHttpException) error).statusCode())
                .isEqualTo(199);
    }

    @Test
    void restoresInterruptStatusWhenTheHttpRequestIsInterrupted() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<InputStream>>any())).thenThrow(new InterruptedException("synthetic interrupt"));
        ExternalCoverService service = service(mock(AssetWriter.class), guardedAction(),
                mock(CoverImageNormalizer.class), client, ExternalCoverServiceTest::publicAddress);

        try {
            assertThatThrownBy(() -> service.download(
                    MusicProvider.QQ, "https://y.gtimg.cn/cover.jpg", "key", Duration.ofSeconds(1)))
                    .isInstanceOf(MusicSourceException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void wrapsHttpIoFailuresAsMusicSourceFailures() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<InputStream>>any())).thenThrow(new IOException("synthetic network failure"));
        ExternalCoverService service = service(mock(AssetWriter.class), guardedAction(),
                mock(CoverImageNormalizer.class), client, ExternalCoverServiceTest::publicAddress);

        assertThatThrownBy(() -> service.download(
                MusicProvider.QQ, "https://y.gtimg.cn/cover.jpg", "key", Duration.ofSeconds(1)))
                .isInstanceOf(MusicSourceException.class)
                .hasRootCauseInstanceOf(IOException.class);
    }

    private static ExternalCoverService responseService(int status, Map<String, List<String>> headers,
                                                        InputStream body) throws Exception {
        return responseService(mock(AssetWriter.class), mock(CoverImageNormalizer.class), status, headers, body);
    }

    private static ExternalCoverService responseService(AssetWriter writer, CoverImageNormalizer normalizer,
                                                        int status, Map<String, List<String>> headers,
                                                        InputStream body) throws Exception {
        HttpClient client = mock(HttpClient.class);
        stubResponse(client, status, headers, body);
        return service(writer, guardedAction(), normalizer, client, ExternalCoverServiceTest::publicAddress);
    }

    private static ExternalCoverService service(AssetWriter writer, ProviderCallGuard guard,
                                                CoverImageNormalizer normalizer, HttpClient client,
                                                ExternalCoverService.HostAddressResolver resolver) {
        return new ExternalCoverService(writer, guard, normalizer, client, resolver);
    }

    private static ProviderCallGuard guardedAction() {
        ProviderCallGuard guard = mock(ProviderCallGuard.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
                .when(guard).call(eq(MusicProvider.QQ), any());
        return guard;
    }

    private static void stubResponse(HttpClient client, int status, Map<String, List<String>> headers,
                                     InputStream body) throws Exception {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        when(response.body()).thenReturn(body);
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<InputStream>>any())).thenReturn(response);
    }

    private static InetAddress[] publicAddress(String host) throws Exception {
        return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8})};
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static void assertCode(String expectedCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(expectedCode);
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private final AtomicInteger bytesRead = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            int count = super.read(buffer, offset, length);
            if (count > 0) bytesRead.addAndGet(count);
            return count;
        }

        private int bytesRead() {
            return bytesRead.get();
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }
    }
}
