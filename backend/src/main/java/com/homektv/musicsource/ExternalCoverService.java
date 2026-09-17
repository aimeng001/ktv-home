package com.homektv.musicsource;

import com.homektv.library.AssetWriter;
import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

@Service
public class ExternalCoverService {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Map<MusicProvider, Set<String>> ALLOWED_HOSTS = Map.of(
            MusicProvider.QQ, Set.of("y.gtimg.cn"),
            MusicProvider.NETEASE, Set.of("music.126.net"),
            MusicProvider.KUGOU, Set.of("kugou.com", "kugoucdn.com", "kgimg.com")
    );
    private final HttpClient client;
    private final AssetWriter writer;
    private final ProviderCallGuard guard;
    private final CoverImageNormalizer imageNormalizer;

    @Autowired
    public ExternalCoverService(AssetWriter writer, ProviderCallGuard guard, CoverImageNormalizer imageNormalizer) {
        this(writer, guard, imageNormalizer,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
    }

    ExternalCoverService(AssetWriter writer, ProviderCallGuard guard, CoverImageNormalizer imageNormalizer,
                         HttpClient client) {
        this.writer = writer;
        this.guard = guard;
        this.imageNormalizer = imageNormalizer;
        this.client = Objects.requireNonNull(client, "client");
    }

    public String download(MusicProvider provider, String coverUrl, String fingerprint, Duration timeout) {
        return downloadLimited(provider, coverUrl, fingerprint, timeout, false);
    }

    public String downloadArtistAvatar(MusicProvider provider, String avatarUrl, String artistKey, Duration timeout) {
        return downloadLimited(provider, avatarUrl, artistKey, timeout, true);
    }

    private String downloadLimited(MusicProvider provider, String coverUrl, String fingerprint,
                                   Duration timeout, boolean artistAvatar) {
        if (coverUrl == null || coverUrl.isBlank()) throw new ApiException("EXTERNAL_COVER_MISSING", "外部歌曲没有可用封面");
        URI uri;
        try { uri = URI.create(coverUrl); } catch (RuntimeException ex) { throw invalid(); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !allowed(provider, uri.getHost())) throw invalid();
        rejectPrivateAddress(uri.getHost());
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "image/*")
                .header("User-Agent", "HomeKTV/0.1 cover-cache").GET().build();
        byte[] bytes = guard.call(provider, () -> fetchBytes(provider, request));
        byte[] normalized = imageNormalizer.normalize(bytes);
        return artistAvatar
                ? writer.writeArtistCover(fingerprint, normalized, "jpg")
                : writer.writeCover(fingerprint, normalized, "jpg");
    }

    private byte[] fetchBytes(MusicProvider provider, HttpRequest request) {
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw new ProviderHttpException(provider, response.statusCode(),
                            MusicSourceHttp.parseRetryAfter(response.headers()),
                            "封面下载失败：HTTP " + response.statusCode());
                long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (length > MAX_BYTES) throw new ApiException("EXTERNAL_COVER_TOO_LARGE", "封面超过 5 MB");
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length == 0 || bytes.length > MAX_BYTES)
                    throw new ApiException("EXTERNAL_COVER_TOO_LARGE", "封面为空或超过 5 MB");
                return bytes;
            }
        } catch (ProviderHttpException ex) {
            throw ex;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new MusicSourceException(provider, "封面下载失败", ex);
        }
    }

    private static boolean allowed(MusicProvider provider, String host) {
        if (host == null) return false;
        return ALLOWED_HOSTS.getOrDefault(provider, Set.of()).stream()
                .anyMatch(allowed -> host.equalsIgnoreCase(allowed) || host.toLowerCase().endsWith("." + allowed));
    }

    private static void rejectPrivateAddress(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) throw invalid();
            }
        } catch (ApiException ex) { throw ex; }
        catch (Exception ex) { throw new ApiException("EXTERNAL_COVER_DNS_FAILED", "封面地址无法解析"); }
    }

    private static ApiException invalid() { return new ApiException("EXTERNAL_COVER_URL_INVALID", "封面地址不在平台白名单内"); }
}
