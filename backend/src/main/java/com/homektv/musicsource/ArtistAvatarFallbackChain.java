package com.homektv.musicsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.common.OutboundUrlGuard;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ordered public/web fallback chain executed only after enabled music platforms
 * fail or miss, obeying per-provider user selection and consecutive-timeout circuit breaking:
 * 1) Baidu Baike public lemma card API
 * 2) Bing Image search (cn.bing.com primary, www.bing.com fallback)
 * 3) Baidu Image search
 * 4) Deezer public artist API (overseas)
 * 5) Wikipedia REST summary zh / en (overseas)
 */
@Component
public class ArtistAvatarFallbackChain {
    private static final Logger log = LoggerFactory.getLogger(ArtistAvatarFallbackChain.class);
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int CIRCUIT_BREAKER_THRESHOLD = 3;
    private static final Duration CIRCUIT_OPEN_DURATION = Duration.ofMinutes(30);
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final List<String> ARTIST_KEYWORDS = List.of(
            "歌手", "音乐", "乐队", "组合", "说唱", "唱作", "作词", "作曲", "唱片", "专辑", "歌曲",
            "singer", "musician", "band", "rapper", "songwriter", "vocalist", "composer", "musical artist");
    private static final Pattern BING_MURL = Pattern.compile("\"murl\"\\s*:\\s*\"(https?:\\\\?/\\\\?/[^\"]+)\"");
    private static final Pattern BING_MURL_ENCODED = Pattern.compile("&quot;murl&quot;:&quot;(https?://[^&]+)&quot;");
    private static final List<AvatarFallbackProvider> SEARCH_ORDER = List.of(
            AvatarFallbackProvider.BAIDU_BAIKE,
            AvatarFallbackProvider.BING_IMAGES,
            AvatarFallbackProvider.BAIDU_IMAGES,
            AvatarFallbackProvider.DEEZER,
            AvatarFallbackProvider.WIKIPEDIA_ZH,
            AvatarFallbackProvider.WIKIPEDIA_EN
    );
    private static final Set<String> ALLOWED_FALLBACK_HOSTS = Set.of(
            "api.deezer.com",
            "zh.wikipedia.org",
            "en.wikipedia.org",
            "baike.baidu.com",
            "image.baidu.com",
            "cn.bing.com",
            "www.bing.com"
    );

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final ProviderCallGuard guard;
    private final MusicSourceConfigService configService;
    private final int dailyLimit;
    private final Object quotaLock = new Object();
    private LocalDate windowDateUtc = LocalDate.now(ZoneOffset.UTC);
    private final AtomicInteger dailyCount = new AtomicInteger(0);
    private final Map<AvatarFallbackProvider, FallbackRuntimeState> states = new EnumMap<>(AvatarFallbackProvider.class);

    @Autowired
    public ArtistAvatarFallbackChain(ObjectMapper mapper, ProviderCallGuard guard,
                                     MusicSourceConfigService configService,
                                     @Value("${app.artist-avatar.fallback-daily-limit:2000}") int dailyLimit) {
        this(OutboundUrlGuard.newNoRedirectHttpClient(Duration.ofSeconds(3)),
                mapper, guard, configService, dailyLimit);
    }

    ArtistAvatarFallbackChain(HttpClient client, ObjectMapper mapper, ProviderCallGuard guard,
                              MusicSourceConfigService configService, int dailyLimit) {
        this.client = client;
        this.mapper = mapper;
        this.guard = guard;
        this.configService = configService;
        this.dailyLimit = Math.max(10, dailyLimit);
        for (AvatarFallbackProvider provider : AvatarFallbackProvider.values()) {
            states.put(provider, new FallbackRuntimeState());
        }
    }

    public boolean isEnabled() {
        if (configService == null) return true;
        MusicSourceConfig config = configService.getConfig();
        if (config == null) return configService.isAvatarFallbackEnabled();
        return config.fallbackProviders() != null && !config.fallbackProviders().isEmpty();
    }

    public boolean hasAvailableProvider() {
        Set<AvatarFallbackProvider> enabled = configuredProviders();
        if (enabled.isEmpty()) return false;
        Instant now = Instant.now();
        for (AvatarFallbackProvider provider : enabled) {
            if (!states.get(provider).isCircuitOpen(now)) return true;
        }
        return false;
    }

    public void resetCircuitBreakers() {
        for (FallbackRuntimeState state : states.values()) {
            state.resetCircuit();
        }
    }

    public List<String> findCandidateUrls(String artistName, Duration timeout) {
        if (!isEnabled()) return List.of();
        String lookup = ArtistAvatarMatcher.cleanLookupName(artistName);
        if (lookup.isBlank()) return List.of();

        Set<AvatarFallbackProvider> enabled = configuredProviders();
        if (enabled.isEmpty()) return List.of();

        Instant now = Instant.now();
        List<AvatarFallbackProvider> runnable = new ArrayList<>();
        Instant earliestRecovery = null;
        for (AvatarFallbackProvider provider : SEARCH_ORDER) {
            if (!enabled.contains(provider)) continue;
            Instant openUntil = states.get(provider).circuitOpenUntil();
            if (openUntil != null && openUntil.isAfter(now)) {
                earliestRecovery = earliestRecovery == null || openUntil.isBefore(earliestRecovery)
                        ? openUntil : earliestRecovery;
                continue;
            }
            runnable.add(provider);
        }
        if (runnable.isEmpty() && earliestRecovery != null) {
            throw new ProviderRateLimitedException(null, "已选兜底图源均处于超时熔断冷却中", earliestRecovery);
        }

        if (!consumeDailyBudget()) {
            Instant nextWindow = windowDateUtc.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(300);
            throw new ProviderRateLimitedException(null, "兜底图源今日配额已用完，等待次日刷新", nextWindow);
        }

        Duration safeTimeout = timeout == null ? Duration.ofSeconds(6) : timeout;
        Duration overseasTimeout = safeTimeout.compareTo(Duration.ofSeconds(4)) > 0
                ? Duration.ofSeconds(4) : safeTimeout;
        Set<String> urls = new LinkedHashSet<>();

        for (AvatarFallbackProvider provider : runnable) {
            if (!urls.isEmpty()) break;
            switch (provider) {
                case BAIDU_BAIKE -> tryCollect(urls, () -> searchBaiduBaike(artistName, lookup, safeTimeout));
                case BING_IMAGES -> {
                    if (lookup.length() >= 2) tryCollect(urls, () -> searchBingImages(lookup, overseasTimeout));
                }
                case BAIDU_IMAGES -> {
                    if (lookup.length() >= 2) tryCollect(urls, () -> searchBaiduImages(lookup, safeTimeout));
                }
                case DEEZER -> tryCollect(urls, () -> searchDeezer(artistName, lookup, overseasTimeout));
                case WIKIPEDIA_ZH -> tryCollect(urls, () -> searchWikipedia(artistName, lookup, "zh", overseasTimeout));
                case WIKIPEDIA_EN -> tryCollect(urls, () -> searchWikipedia(artistName, lookup, "en", overseasTimeout));
            }
        }
        return urls.stream().limit(5).toList();
    }

    public List<Map<String, Object>> statuses(int effectiveDailyLimit) {
        Instant now = Instant.now();
        int limit = effectiveDailyLimit > 0 ? effectiveDailyLimit : effectiveDailyLimit();
        int totalUsed = dailyCount();
        List<Map<String, Object>> out = new ArrayList<>();
        for (AvatarFallbackProvider provider : AvatarFallbackProvider.values()) {
            out.add(states.get(provider).toView(provider, limit, totalUsed, now));
        }
        return out;
    }

    public static List<Map<String, Object>> defaultStatuses(int effectiveDailyLimit) {
        int limit = Math.max(10, effectiveDailyLimit);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AvatarFallbackProvider provider : AvatarFallbackProvider.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider", provider.name());
            row.put("displayName", provider.displayName());
            row.put("overseas", provider.overseas());
            row.put("healthy", false);
            row.put("lastSuccessAt", null);
            row.put("lastErrorAt", null);
            row.put("lastError", null);
            row.put("cooldownUntil", null);
            row.put("requestCount", 0);
            row.put("successCount", 0);
            row.put("totalDailyUsed", 0);
            row.put("dailyLimit", limit);
            out.add(row);
        }
        return out;
    }

    public List<FallbackTestResult> test(Collection<AvatarFallbackProvider> requested, Duration timeout) {
        Set<AvatarFallbackProvider> targets = requested == null || requested.isEmpty()
                ? configuredProviders()
                : new LinkedHashSet<>(requested);
        if (targets.isEmpty()) {
            targets = EnumSet.allOf(AvatarFallbackProvider.class);
        }
        Duration safeTimeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        List<FallbackTestResult> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<FallbackTestResult>> futures = targets.stream()
                    .map(provider -> CompletableFuture.supplyAsync(() -> testOne(provider, safeTimeout), executor))
                    .toList();
            for (CompletableFuture<FallbackTestResult> future : futures) {
                results.add(future.join());
            }
        }
        return results;
    }

    private FallbackTestResult testOne(AvatarFallbackProvider provider, Duration timeout) {
        states.get(provider).resetCircuit();
        long started = System.nanoTime();
        try {
            List<String> found = switch (provider) {
                case BAIDU_BAIKE -> searchBaiduBaike("周杰伦", "周杰伦", timeout);
                case BING_IMAGES -> searchBingImages("周杰伦", timeout);
                case BAIDU_IMAGES -> searchBaiduImages("周杰伦", timeout);
                case DEEZER -> searchDeezer("Adele", "Adele", timeout);
                case WIKIPEDIA_ZH -> searchWikipedia("陈奕迅", "陈奕迅", "zh", timeout);
                case WIKIPEDIA_EN -> searchWikipedia("Adele", "Adele", "en", timeout);
            };
            long latency = (System.nanoTime() - started) / 1_000_000;
            String lastError = states.get(provider).lastError();
            boolean healthy = !found.isEmpty() || lastError == null;
            return new FallbackTestResult(provider, provider.displayName(), healthy, latency,
                    healthy ? null : lastError);
        } catch (RuntimeException ex) {
            long latency = (System.nanoTime() - started) / 1_000_000;
            String msg = ProviderJson.clean(ex.getMessage(), 300);
            return new FallbackTestResult(provider, provider.displayName(), false, latency,
                    msg == null ? "兜底平台测试失败" : msg);
        }
    }

    List<String> searchDeezer(String rawName, String lookup, Duration timeout) {
        String url = "https://api.deezer.com/search/artist?q=" + encode(lookup) + "&limit=5";
        JsonNode root = fetchJson(AvatarFallbackProvider.DEEZER, url, "https://www.deezer.com/", timeout);
        JsonNode data = root.path("data");
        if (!data.isArray()) return List.of();
        List<ExternalArtist> candidates = new ArrayList<>();
        for (JsonNode artist : data) {
            String id = ProviderJson.clean(artist.path("id").asText(null), 64);
            String name = ProviderJson.text(artist, "name", 200);
            String pic = firstText(artist, "picture_xl", "picture_big", "picture_medium", "picture");
            if (id == null || name == null || pic == null) continue;
            if (pic.contains("/artist//") || AvatarImageQualityValidator.isKnownPlaceholderUrl(pic)) continue;
            candidates.add(new ExternalArtist(MusicProvider.QQ, id, name, List.of(), pic));
        }
        List<String> matched = ArtistAvatarMatcher.bestMatch(rawName, candidates)
                .map(match -> List.of(match.artist().avatarUrl()))
                .orElse(List.of());
        if (!matched.isEmpty()) states.get(AvatarFallbackProvider.DEEZER).recordHit();
        return matched;
    }

    List<String> searchWikipedia(String rawName, String lookup, String lang, Duration timeout) {
        AvatarFallbackProvider provider = "zh".equalsIgnoreCase(lang)
                ? AvatarFallbackProvider.WIKIPEDIA_ZH : AvatarFallbackProvider.WIKIPEDIA_EN;
        String encodedTitle = URLEncoder.encode(lookup.replace(' ', '_'), StandardCharsets.UTF_8)
                .replace("+", "%20");
        String url = "https://" + lang + ".wikipedia.org/api/rest_v1/page/summary/" + encodedTitle;
        JsonNode root = fetchJson(provider, url, "https://" + lang + ".wikipedia.org/", timeout);
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
            states.get(provider).recordHit();
            return List.of(matched.get().artist().avatarUrl());
        }
        String thumb = firstText(root.path("thumbnail"), "source");
        if (thumb != null && ArtistAvatarMatcher.bestMatch(rawName,
                List.of(new ExternalArtist(MusicProvider.QQ, title, title, List.of(), thumb))).isPresent()) {
            states.get(provider).recordHit();
            return List.of(thumb);
        }
        return List.of();
    }

    List<String> searchBaiduBaike(String rawName, String lookup, Duration timeout) {
        String url = "https://baike.baidu.com/api/openapi/BaikeLemmaCardApi?scope=103&format=json&appid=379020&bk_key="
                + encode(lookup) + "&bk_length=600";
        JsonNode root = fetchJson(AvatarFallbackProvider.BAIDU_BAIKE, url, "https://baike.baidu.com/", timeout);
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
        if (matched) {
            states.get(AvatarFallbackProvider.BAIDU_BAIKE).recordHit();
            return List.of(image);
        }
        return List.of();
    }

    List<String> searchBaiduImages(String lookup, Duration timeout) {
        String url = "https://image.baidu.com/search/acjson?tn=resultjson_com&ipn=rj&ct=201326592&fp=result&ie=utf-8&oe=utf-8&word="
                + encode(lookup + " 歌手 头像") + "&pn=0&rn=6";
        JsonNode root = fetchJson(AvatarFallbackProvider.BAIDU_IMAGES, url, "https://image.baidu.com/", timeout);
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
        if (!urls.isEmpty()) states.get(AvatarFallbackProvider.BAIDU_IMAGES).recordHit();
        return urls;
    }

    List<String> searchBingImages(String lookup, Duration timeout) {
        Set<String> urls = new LinkedHashSet<>();
        String cnUrl = "https://cn.bing.com/images/async?q=" + encode(lookup + " 歌手 头像") + "&first=0&count=6";
        String html = fetchText(AvatarFallbackProvider.BING_IMAGES, cnUrl, "https://cn.bing.com/", timeout);
        collectRegexUrls(html, BING_MURL, urls);
        collectRegexUrls(html, BING_MURL_ENCODED, urls);
        if (urls.isEmpty() && (html == null || html.isBlank())) {
            String wwwUrl = "https://www.bing.com/images/async?q=" + encode(lookup + " 歌手 头像") + "&first=0&count=6";
            String fallbackHtml = fetchText(AvatarFallbackProvider.BING_IMAGES, wwwUrl, "https://www.bing.com/", timeout);
            collectRegexUrls(fallbackHtml, BING_MURL, urls);
            collectRegexUrls(fallbackHtml, BING_MURL_ENCODED, urls);
        }
        if (!urls.isEmpty()) states.get(AvatarFallbackProvider.BING_IMAGES).recordHit();
        return urls.stream().limit(3).toList();
    }

    private Set<AvatarFallbackProvider> configuredProviders() {
        if (configService == null) {
            return EnumSet.allOf(AvatarFallbackProvider.class);
        }
        MusicSourceConfig config = configService.getConfig();
        if (config == null) {
            return configService.isAvatarFallbackEnabled()
                    ? EnumSet.allOf(AvatarFallbackProvider.class)
                    : Set.of();
        }
        return config.fallbackProviders() == null ? Set.of() : config.fallbackProviders();
    }

    private void collectRegexUrls(String html, Pattern pattern, Set<String> out) {
        if (html == null || html.isBlank()) return;
        Matcher matcher = pattern.matcher(html);
        while (matcher.find() && out.size() < 4) {
            String raw = matcher.group(1).replace("\\/", "/").trim();
            if (isUsableWebImageUrl(raw)) out.add(raw);
        }
    }

    private JsonNode fetchJson(AvatarFallbackProvider provider, String url, String referer, Duration timeout) {
        String text = fetchText(provider, url, referer, timeout);
        if (text.isBlank()) return mapper.createObjectNode();
        try {
            return mapper.readTree(text);
        } catch (Exception ex) {
            return mapper.createObjectNode();
        }
    }

    private String fetchText(AvatarFallbackProvider provider, String url, String referer, Duration timeout) {
        FallbackRuntimeState runtimeState = states.get(provider);
        runtimeState.recordAttempt();
        try {
            URI safeUri = OutboundUrlGuard.requireHttpsAllowedHost(url, ALLOWED_FALLBACK_HOSTS,
                    IllegalArgumentException::new);
            return guard.callFallback(provider, () -> {
                HttpRequest request = HttpRequest.newBuilder(safeUri)
                        .timeout(timeout)
                        .header("User-Agent", BROWSER_UA)
                        .header("Referer", referer)
                        .header("Accept", "application/json,text/html,*/*")
                        .GET()
                        .build();
                try {
                    HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    try (InputStream body = response.body()) {
                        int status = response.statusCode();
                        if (status < 200 || status >= 300) {
                            runtimeState.recordFailure("HTTP " + status, false);
                            return "";
                        }
                        byte[] bytes = MusicSourceHttp.readAtMostWithTimeout(body, MAX_BODY_BYTES, timeout);
                        runtimeState.recordReachable();
                        return new String(bytes, StandardCharsets.UTF_8);
                    }
                } catch (Exception ex) {
                    if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                    runtimeState.recordFailure(ex.getMessage() == null ? "连接或读取超时" : ex.getMessage(), true);
                    log.debug("fallback avatar lookup skipped for {} ({}): {}", provider, url, ex.getMessage());
                    return "";
                }
            });
        } catch (RuntimeException ex) {
            runtimeState.recordFailure(ex.getMessage() == null ? "兜底请求异常" : ex.getMessage(), false);
            log.debug("fallback avatar lookup failed for {} ({}): {}", provider, url, ex.getMessage());
            return "";
        }
    }

    private int effectiveDailyLimit() {
        if (configService == null) return dailyLimit;
        MusicSourceConfig config = configService.getConfig();
        return config != null && config.fallbackDailyLimit() > 0 ? config.fallbackDailyLimit() : dailyLimit;
    }

    private int dailyCount() {
        synchronized (quotaLock) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (!today.equals(windowDateUtc)) {
                windowDateUtc = today;
                dailyCount.set(0);
            }
            return dailyCount.get();
        }
    }

    private boolean consumeDailyBudget() {
        int limit = effectiveDailyLimit();
        synchronized (quotaLock) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (!today.equals(windowDateUtc)) {
                windowDateUtc = today;
                dailyCount.set(0);
            }
            if (dailyCount.get() >= limit) return false;
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

    public record FallbackTestResult(AvatarFallbackProvider provider, String displayName, boolean healthy,
                                     long latencyMs, String error) {}

    private static final class FallbackRuntimeState {
        private LocalDate windowDateUtc = LocalDate.now(ZoneOffset.UTC);
        private int requestCount = 0;
        private int successCount = 0;
        private int consecutiveTimeouts = 0;
        private boolean healthy = false;
        private Instant lastSuccessAt;
        private Instant lastErrorAt;
        private String lastError;
        private Instant circuitOpenUntil;

        synchronized void recordAttempt() {
            rollWindowIfNeeded();
            requestCount++;
        }

        synchronized void recordReachable() {
            consecutiveTimeouts = 0;
            healthy = true;
            lastSuccessAt = Instant.now();
            lastError = null;
            circuitOpenUntil = null;
        }

        synchronized void recordHit() {
            rollWindowIfNeeded();
            successCount++;
        }

        synchronized void recordFailure(String message, boolean transportTimeout) {
            healthy = false;
            lastErrorAt = Instant.now();
            lastError = ProviderJson.clean(message, 250);
            if (transportTimeout) {
                consecutiveTimeouts++;
                if (consecutiveTimeouts >= CIRCUIT_BREAKER_THRESHOLD) {
                    circuitOpenUntil = Instant.now().plus(CIRCUIT_OPEN_DURATION);
                    lastError = "连续超时自动熔断 30 分钟";
                }
            }
        }

        synchronized void resetCircuit() {
            consecutiveTimeouts = 0;
            circuitOpenUntil = null;
            if ("连续超时自动熔断 30 分钟".equals(lastError)) {
                lastError = null;
            }
        }

        synchronized boolean isCircuitOpen(Instant now) {
            return circuitOpenUntil != null && circuitOpenUntil.isAfter(now);
        }

        synchronized Instant circuitOpenUntil() {
            return circuitOpenUntil;
        }

        synchronized String lastError() {
            return lastError;
        }

        synchronized Map<String, Object> toView(AvatarFallbackProvider provider, int dailyLimit, int totalDailyUsed, Instant now) {
            rollWindowIfNeeded();
            boolean open = circuitOpenUntil != null && circuitOpenUntil.isAfter(now);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider", provider.name());
            row.put("displayName", provider.displayName());
            row.put("overseas", provider.overseas());
            row.put("healthy", healthy && !open);
            row.put("lastSuccessAt", toOffset(lastSuccessAt));
            row.put("lastErrorAt", toOffset(lastErrorAt));
            row.put("lastError", lastError);
            row.put("cooldownUntil", open ? toOffset(circuitOpenUntil) : null);
            row.put("requestCount", requestCount);
            row.put("successCount", successCount);
            row.put("totalDailyUsed", totalDailyUsed);
            row.put("dailyLimit", dailyLimit);
            return row;
        }

        private void rollWindowIfNeeded() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (!today.equals(windowDateUtc)) {
                windowDateUtc = today;
                requestCount = 0;
                successCount = 0;
            }
        }

        private static OffsetDateTime toOffset(Instant instant) {
            return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        }
    }
}
