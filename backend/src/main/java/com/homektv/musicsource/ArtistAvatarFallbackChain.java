package com.homektv.musicsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.library.ArtistAvatarMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ordered public/web fallback chain executed only after primary Chinese music
 * platforms fail or miss:
 * 1) Deezer public artist API
 * 2) Wikipedia / Wikimedia Commons REST summary
 * 3) Baidu Baike public lemma card API
 * 4) Public web image search (Baidu Image / Bing Image)
 */
@Component
public class ArtistAvatarFallbackChain {
    private static final Logger log = LoggerFactory.getLogger(ArtistAvatarFallbackChain.class);
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final List<String> ARTIST_KEYWORDS = List.of(
            "歌手", "音乐", "乐队", "组合", "说唱", "唱作", "作词", "作曲", "唱片", "专辑", "歌曲",
            "singer", "musician", "band", "rapper", "songwriter", "vocalist", "composer", "musical artist");
    private static final Pattern BING_MURL = Pattern.compile("\"murl\"\\s*:\\s*\"(https?:\\\\?/\\\\?/[^\"]+)\"");
    private static final Pattern BING_MURL_ENCODED = Pattern.compile("&quot;murl&quot;:&quot;(https?://[^&]+)&quot;");

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final ProviderCallGuard guard;
    private final MusicSourceConfigService configService;
    private final int dailyLimit;
    private final Object quotaLock = new Object();
    private LocalDate windowDateUtc = LocalDate.now(ZoneOffset.UTC);
    private final AtomicInteger dailyCount = new AtomicInteger(0);

    @Autowired
    public ArtistAvatarFallbackChain(ObjectMapper mapper, ProviderCallGuard guard,
                                     MusicSourceConfigService configService,
                                     @Value("${app.artist-avatar.fallback-daily-limit:400}") int dailyLimit) {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                mapper, guard, configService, dailyLimit);
    }

    ArtistAvatarFallbackChain(HttpClient client, ObjectMapper mapper, ProviderCallGuard guard,
                              MusicSourceConfigService configService, int dailyLimit) {
        this.client = client;
        this.mapper = mapper;
        this.guard = guard;
        this.configService = configService;
        this.dailyLimit = Math.max(10, dailyLimit);
    }

    public boolean isEnabled() {
        return configService == null || configService.isAvatarFallbackEnabled();
    }

    public List<String> findCandidateUrls(String artistName, Duration timeout) {
        if (!isEnabled()) return List.of();
        String lookup = ArtistAvatarMatcher.cleanLookupName(artistName);
        if (lookup.isBlank() || !consumeDailyBudget()) return List.of();

        Duration safeTimeout = timeout == null ? Duration.ofSeconds(6) : timeout;
        Duration overseasTimeout = safeTimeout.compareTo(Duration.ofSeconds(4)) > 0
                ? Duration.ofSeconds(4) : safeTimeout;
        Set<String> urls = new LinkedHashSet<>();

        tryCollect(urls, () -> searchDeezer(artistName, lookup, overseasTimeout));
        if (urls.isEmpty()) {
            tryCollect(urls, () -> searchWikipedia(artistName, lookup, "zh", overseasTimeout));
        }
        if (urls.isEmpty()) {
            tryCollect(urls, () -> searchWikipedia(artistName, lookup, "en", overseasTimeout));
        }
        if (urls.isEmpty()) {
            tryCollect(urls, () -> searchBaiduBaike(artistName, lookup, safeTimeout));
        }
        if (urls.isEmpty() && lookup.length() >= 2) {
            tryCollect(urls, () -> searchBaiduImages(lookup, safeTimeout));
            if (urls.size() < 2) {
                tryCollect(urls, () -> searchBingImages(lookup, overseasTimeout));
            }
        }
        return urls.stream().limit(5).toList();
    }

    List<String> searchDeezer(String rawName, String lookup, Duration timeout) {
        String url = "https://api.deezer.com/search/artist?q=" + encode(lookup) + "&limit=5";
        JsonNode root = fetchJson(url, "https://www.deezer.com/", timeout);
        JsonNode data = root.path("data");
        if (!data.isArray()) return List.of();
        List<ExternalArtist> candidates = new ArrayList<>();
        for (JsonNode artist : data) {
            String id = ProviderJson.clean(artist.path("id").asText(null), 64);
            String name = ProviderJson.text(artist, "name", 200);
            String pic = firstText(artist, "picture_xl", "picture_big", "picture_medium", "picture");
            if (id == null || name == null || pic == null) continue;
            // Deezer returns /artist//500x500-000000-80-0-0.jpg when the artist has no portrait.
            if (pic.contains("/artist//") || AvatarImageQualityValidator.isKnownPlaceholderUrl(pic)) continue;
            candidates.add(new ExternalArtist(MusicProvider.QQ, id, name, List.of(), pic));
        }
        return ArtistAvatarMatcher.bestMatch(rawName, candidates)
                .map(match -> List.of(match.artist().avatarUrl()))
                .orElse(List.of());
    }

    List<String> searchWikipedia(String rawName, String lookup, String lang, Duration timeout) {
        String encodedTitle = URLEncoder.encode(lookup.replace(' ', '_'), StandardCharsets.UTF_8)
                .replace("+", "%20");
        String url = "https://" + lang + ".wikipedia.org/api/rest_v1/page/summary/" + encodedTitle;
        JsonNode root = fetchJson(url, "https://" + lang + ".wikipedia.org/", timeout);
        String title = ProviderJson.text(root, "title", 200);
        if (title == null) return List.of();
        String summaryContext = (ProviderJson.text(root, "description", 400) + " "
                + ProviderJson.text(root, "extract", 800)).toLowerCase(Locale.ROOT);
        if (!containsArtistKeyword(summaryContext)) return List.of();
        Optional<ArtistAvatarMatcher.Match> matched = ArtistAvatarMatcher.bestMatch(
                rawName,
                List.of(new ExternalArtist(MusicProvider.QQ, title, title, List.of(),
                        firstText(root.path("originalimage"), "source"))));
        if (matched.isPresent() && matched.get().artist().avatarUrl() != null) {
            return List.of(matched.get().artist().avatarUrl());
        }
        String thumb = firstText(root.path("thumbnail"), "source");
        if (thumb != null && ArtistAvatarMatcher.bestMatch(rawName,
                List.of(new ExternalArtist(MusicProvider.QQ, title, title, List.of(), thumb))).isPresent()) {
            return List.of(thumb);
        }
        return List.of();
    }

    List<String> searchBaiduBaike(String rawName, String lookup, Duration timeout) {
        String url = "https://baike.baidu.com/api/openapi/BaikeLemmaCardApi?scope=103&format=json&appid=379020&bk_key="
                + encode(lookup) + "&bk_length=600";
        JsonNode root = fetchJson(url, "https://baike.baidu.com/", timeout);
        String title = ProviderJson.text(root, "title", 200);
        String image = ProviderJson.text(root, "image", 2000);
        if (title == null || image == null || AvatarImageQualityValidator.isKnownPlaceholderUrl(image)) {
            return List.of();
        }
        String context = (ProviderJson.text(root, "desc", 300) + " "
                + ProviderJson.text(root, "abstract", 800) + " "
                + root.path("card").toString()).toLowerCase(Locale.ROOT);
        if (!containsArtistKeyword(context)) return List.of();
        boolean matched = ArtistAvatarMatcher.bestMatch(
                rawName, List.of(new ExternalArtist(MusicProvider.QQ, title, title, List.of(), image))).isPresent();
        return matched ? List.of(image) : List.of();
    }

    List<String> searchBaiduImages(String lookup, Duration timeout) {
        String url = "https://image.baidu.com/search/acjson?tn=resultjson_com&ipn=rj&ct=201326592&fp=result&ie=utf-8&oe=utf-8&word="
                + encode(lookup + " 歌手 头像") + "&pn=0&rn=6";
        JsonNode root = fetchJson(url, "https://image.baidu.com/", timeout);
        JsonNode data = root.path("data");
        if (!data.isArray()) return List.of();
        List<String> urls = new ArrayList<>();
        for (JsonNode item : data) {
            String candidate = firstText(item, "middleURL", "thumbURL");
            if (isUsableWebImageUrl(candidate)) {
                urls.add(candidate);
                if (urls.size() >= 3) break;
            }
        }
        return urls;
    }

    List<String> searchBingImages(String lookup, Duration timeout) {
        String url = "https://www.bing.com/images/async?q=" + encode(lookup + " 歌手 头像") + "&first=0&count=6";
        String html = fetchText(url, "https://www.bing.com/", timeout);
        if (html.isBlank()) return List.of();
        Set<String> urls = new LinkedHashSet<>();
        collectRegexUrls(html, BING_MURL, urls);
        collectRegexUrls(html, BING_MURL_ENCODED, urls);
        return urls.stream().limit(3).toList();
    }

    private void collectRegexUrls(String html, Pattern pattern, Set<String> out) {
        Matcher matcher = pattern.matcher(html);
        while (matcher.find() && out.size() < 4) {
            String raw = matcher.group(1).replace("\\/", "/").trim();
            if (isUsableWebImageUrl(raw)) out.add(raw);
        }
    }

    private JsonNode fetchJson(String url, String referer, Duration timeout) {
        String text = fetchText(url, referer, timeout);
        if (text.isBlank()) return mapper.createObjectNode();
        try {
            return mapper.readTree(text);
        } catch (Exception ex) {
            return mapper.createObjectNode();
        }
    }

    private String fetchText(String url, String referer, Duration timeout) {
        try {
            return guard.callFallback(() -> {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(timeout)
                        .header("User-Agent", BROWSER_UA)
                        .header("Referer", referer)
                        .header("Accept", "application/json,text/html,*/*")
                        .GET()
                        .build();
                try {
                    HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    try (InputStream body = response.body()) {
                        if (response.statusCode() < 200 || response.statusCode() >= 300) return "";
                        byte[] bytes = MusicSourceHttp.readAtMost(body, MAX_BODY_BYTES);
                        return new String(bytes, StandardCharsets.UTF_8);
                    }
                } catch (Exception ex) {
                    if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                    log.debug("fallback avatar lookup skipped for {}: {}", url, ex.getMessage());
                    return "";
                }
            });
        } catch (RuntimeException ex) {
            log.debug("fallback avatar lookup failed for {}: {}", url, ex.getMessage());
            return "";
        }
    }

    private boolean consumeDailyBudget() {
        synchronized (quotaLock) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (!today.equals(windowDateUtc)) {
                windowDateUtc = today;
                dailyCount.set(0);
            }
            if (dailyCount.get() >= dailyLimit) return false;
            dailyCount.incrementAndGet();
            return true;
        }
    }

    private static void tryCollect(Set<String> target, java.util.function.Supplier<List<String>> source) {
        try {
            List<String> found = source.get();
            if (found != null) {
                found.stream()
                        .filter(u -> u != null && !u.isBlank() && !AvatarImageQualityValidator.isKnownPlaceholderUrl(u))
                        .forEach(target::add);
            }
        } catch (RuntimeException ignored) {
            // Isolate individual fallback source errors so remaining sources can still succeed.
        }
    }

    private static boolean containsArtistKeyword(String context) {
        if (context == null || context.isBlank()) return false;
        for (String keyword : ARTIST_KEYWORDS) {
            if (context.contains(keyword)) return true;
        }
        return false;
    }

    private static boolean isUsableWebImageUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        if (lower.endsWith(".gif") || lower.endsWith(".svg")) return false;
        return !AvatarImageQualityValidator.isKnownPlaceholderUrl(url);
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode()) return null;
        for (String field : fields) {
            String value = ProviderJson.text(node, field, 2000);
            if (value != null) return value;
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
