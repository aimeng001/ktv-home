package com.homektv.musicsource;

import com.homektv.library.AssetWriter;
import com.homektv.common.ApiException;
import com.homektv.common.OutboundUrlGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class ExternalCoverService {
    @FunctionalInterface
    interface HostAddressResolver {
        InetAddress[] resolve(String host) throws Exception;
    }

    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final Map<MusicProvider, Set<String>> ALLOWED_HOSTS = Map.of(
            MusicProvider.QQ, Set.of("y.gtimg.cn"),
            MusicProvider.NETEASE, Set.of("music.126.net"),
            MusicProvider.KUGOU, Set.of("kugou.com", "kugoucdn.com", "kgimg.com"),
            MusicProvider.KUWO, Set.of("kuwo.cn", "kuwoimg.com"),
            MusicProvider.ITUNES, Set.of("mzstatic.com")
    );
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final ExecutorService BODY_READ_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client;
    private final AssetWriter writer;
    private final ProviderCallGuard guard;
    private final CoverImageNormalizer imageNormalizer;
    private final HostAddressResolver hostAddressResolver;

    @Autowired
    public ExternalCoverService(AssetWriter writer, ProviderCallGuard guard, CoverImageNormalizer imageNormalizer) {
        this(writer, guard, imageNormalizer,
                OutboundUrlGuard.newNoRedirectHttpClient(DEFAULT_CONNECT_TIMEOUT), InetAddress::getAllByName);
    }

    ExternalCoverService(AssetWriter writer, ProviderCallGuard guard, CoverImageNormalizer imageNormalizer,
                         HttpClient client) {
        this(writer, guard, imageNormalizer, client, InetAddress::getAllByName);
    }

    ExternalCoverService(AssetWriter writer, ProviderCallGuard guard, CoverImageNormalizer imageNormalizer,
                         HttpClient client, HostAddressResolver hostAddressResolver) {
        this.writer = writer;
        this.guard = guard;
        this.imageNormalizer = imageNormalizer;
        this.client = Objects.requireNonNull(client, "client");
        this.hostAddressResolver = Objects.requireNonNull(hostAddressResolver, "hostAddressResolver");
    }

    public String download(MusicProvider provider, String coverUrl, String fingerprint, Duration timeout) {
        return downloadLimited(provider, coverUrl, fingerprint, timeout, false);
    }

    public String downloadArtistAvatar(MusicProvider provider, String avatarUrl, String artistKey, Duration timeout) {
        return downloadLimited(provider, avatarUrl, artistKey, timeout, true);
    }

    /**
     * Downloads and quality-validates an artist avatar from public/web fallback sources.
     * Relies on strict per-hop public IP resolution (anti-SSRF) instead of a fixed domain whitelist.
     */
    public String downloadFallbackArtistAvatar(String avatarUrl, String artistKey, Duration timeout) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            throw new ApiException("EXTERNAL_COVER_MISSING", "外部歌手没有可用头像");
        }
        if (AvatarImageQualityValidator.isKnownPlaceholderUrl(avatarUrl)) {
            throw new ApiException("EXTERNAL_COVER_PLACEHOLDER", "检测到平台默认占位图");
        }
        URI uri = validateUri(null, avatarUrl, true);
        byte[] bytes = guard.callFallback(() -> fetchBytesWithRedirects(
                null, uri, timeout, true));
        byte[] normalized = imageNormalizer.normalize(bytes);
        if (normalized != null && normalized.length >= 32) {
            AvatarImageQualityValidator.validateNormalizedBytes(normalized, true);
        }
        return writer.writeArtistCover(artistKey, normalized, "jpg");
    }

    private String downloadLimited(MusicProvider provider, String coverUrl, String fingerprint,
                                   Duration timeout, boolean artistAvatar) {
        if (coverUrl == null || coverUrl.isBlank()) throw new ApiException("EXTERNAL_COVER_MISSING", "外部歌曲没有可用封面");
        if (artistAvatar && AvatarImageQualityValidator.isKnownPlaceholderUrl(coverUrl)) {
            throw new ApiException("EXTERNAL_COVER_PLACEHOLDER", "检测到平台默认占位图");
        }
        URI uri = validateUri(provider, coverUrl, false);
        byte[] bytes = guard.callMedia(provider, () -> fetchBytesWithRedirects(
                provider, uri, timeout, false));
        byte[] normalized = imageNormalizer.normalize(bytes);
        if (artistAvatar && normalized != null && normalized.length >= 32) {
            AvatarImageQualityValidator.validateNormalizedBytes(normalized, false);
        }
        return artistAvatar
                ? writer.writeArtistCover(fingerprint, normalized, "jpg")
                : writer.writeCover(fingerprint, normalized, "jpg");
    }

    private URI validateUri(MusicProvider provider, String url, boolean allowAnyPublicHost) {
        Set<String> allow = allowAnyPublicHost ? Set.of() : ALLOWED_HOSTS.getOrDefault(provider, Set.of());
        return OutboundUrlGuard.requirePublicDestination(
                url,
                !allowAnyPublicHost,
                allow,
                hostAddressResolver::resolve,
                ignored -> invalid(),
                ignored -> new ApiException("EXTERNAL_COVER_DNS_FAILED", "封面地址无法解析"));
    }

    private byte[] fetchBytesWithRedirects(MusicProvider provider, URI initialUri,
                                           Duration timeout, boolean allowAnyPublicHost) {
        URI currentUri = initialUri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(currentUri)
                    .timeout(timeout)
                    .header("Accept", "image/*")
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .GET();
            String referer = refererFor(provider, currentUri);
            if (referer != null) builder.header("Referer", referer);
            HttpRequest request = builder.build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    try (InputStream ignored = response.body()) {
                        // Drain/close redirect body before following.
                    }
                    if (hop == MAX_REDIRECTS) {
                        throw new ProviderHttpException(provider != null ? provider : MusicProvider.QQ, status,
                                null, "图片重定向次数过多");
                    }
                    String location = response.headers().firstValue("Location").orElse("").trim();
                    if (location.isBlank()) throw invalid();
                    URI resolved = currentUri.resolve(location);
                    currentUri = validateUri(provider, resolved.toString(), allowAnyPublicHost);
                    continue;
                }
                try (InputStream input = response.body()) {
                    if (status < 200 || status >= 300) {
                        throw new ProviderHttpException(provider != null ? provider : MusicProvider.QQ, status,
                                MusicSourceHttp.parseRetryAfter(response.headers()),
                                "封面下载失败：HTTP " + status);
                    }
                    long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                    if (length > MAX_BYTES) throw new ApiException("EXTERNAL_COVER_TOO_LARGE", "封面超过 5 MB");
                    String contentType = response.headers().firstValue("Content-Type").orElse("").trim();
                    if (!isAllowedImageContentType(contentType)) {
                        throw new ApiException("EXTERNAL_COVER_INVALID", "封面响应类型无效");
                    }
                    byte[] bytes = readNBytesWithTimeout(input, MAX_BYTES + 1, timeout);
                    if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                        throw new ApiException("EXTERNAL_COVER_TOO_LARGE", "封面为空或超过 5 MB");
                    }
                    return bytes;
                }
            } catch (ProviderHttpException | ApiException ex) {
                throw ex;
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new MusicSourceException(provider != null ? provider : MusicProvider.QQ, "封面下载失败", ex);
            }
        }
        throw invalid();
    }

    private static byte[] readNBytesWithTimeout(InputStream input, int length, Duration timeout) throws IOException {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return input.readNBytes(length);
        }
        Future<byte[]> future = BODY_READ_EXECUTOR.submit(() -> input.readNBytes(length));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            try { input.close(); } catch (Exception ignored) {}
            throw new IOException("读取封面响应体超时 (" + timeout.toSeconds() + "s)", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioe) throw ioe;
            throw new IOException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("读取封面响应体被中断", e);
        }
    }

    private static String refererFor(MusicProvider provider, URI uri) {
        if (provider == MusicProvider.NETEASE) return "https://music.163.com/";
        if (provider == MusicProvider.QQ) return "https://y.qq.com/";
        if (provider == MusicProvider.KUGOU) return "https://www.kugou.com/";
        if (provider == MusicProvider.KUWO) return "https://www.kuwo.cn/";
        if (provider == MusicProvider.ITUNES) return "https://music.apple.com/";
        String host = uri.getHost();
        if (host != null && (host.endsWith("baidu.com") || host.endsWith("bdimg.com") || host.endsWith("bcebos.com"))) {
            return "https://image.baidu.com/";
        }
        return null;
    }

    private static boolean isAllowedImageContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) return true;
        int semicolon = contentType.indexOf(';');
        String mime = (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType)
                .trim().toLowerCase(java.util.Locale.ROOT);
        if (mime.isEmpty()) return true;
        if ("image/svg+xml".equals(mime)) return false;
        return mime.startsWith("image/")
                || "application/octet-stream".equals(mime)
                || "binary/octet-stream".equals(mime);
    }

    private static ApiException invalid() { return new ApiException("EXTERNAL_COVER_URL_INVALID", "封面地址不在平台白名单内"); }
}
