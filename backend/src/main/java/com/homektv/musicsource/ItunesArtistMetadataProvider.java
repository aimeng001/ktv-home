package com.homektv.musicsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.library.ArtistAvatarMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apple iTunes / Apple Music artist portrait adapter.
 * Step 1 queries the official iTunes Search API (guarded by ITUNES search rate limiter);
 * Step 2 fetches only the best matching artist's Apple Music page via the static media channel
 * to extract the official og:image portrait on mzstatic.com.
 */
@Component
public class ItunesArtistMetadataProvider implements ArtistMetadataProvider {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final Pattern OG_IMAGE = Pattern.compile(
            "<meta[^>]+(?:property|name)=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_IMAGE_REVERSED = Pattern.compile(
            "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']og:image[\"']",
            Pattern.CASE_INSENSITIVE);

    private final MusicSourceHttp http;

    @Autowired
    public ItunesArtistMetadataProvider(ObjectMapper mapper, ProviderCallGuard guard) {
        this(new MusicSourceHttp(
                mapper,
                MusicProvider.ITUNES,
                Set.of("itunes.apple.com", "music.apple.com"),
                guard));
    }

    ItunesArtistMetadataProvider(MusicSourceHttp http) {
        this.http = http;
    }

    @Override
    public MusicProvider provider() {
        return MusicProvider.ITUNES;
    }

    @Override
    public List<ExternalArtist> search(String artistName, int limit, Duration timeout) {
        String lookup = ArtistAvatarMatcher.cleanLookupName(artistName);
        if (lookup.isBlank()) return List.of();
        Map<String, String> query = new LinkedHashMap<>();
        query.put("term", lookup);
        query.put("entity", "musicArtist");
        query.put("country", "cn");
        query.put("limit", String.valueOf(Math.min(Math.max(limit, 1), 10)));
        JsonNode root = http.get(
                "https://itunes.apple.com/search?" + MusicSourceHttp.query(query),
                Map.of("User-Agent", USER_AGENT),
                timeout);
        List<ArtistCandidate> candidates = parseSearchResults(root.path("results"), limit);
        if (candidates.isEmpty()) return List.of();

        List<ExternalArtist> baseMatches = candidates.stream()
                .map(c -> new ExternalArtist(provider(), c.id(), c.name(), List.of(), c.linkUrl()))
                .toList();
        Optional<ArtistAvatarMatcher.Match> matched = ArtistAvatarMatcher.bestMatch(artistName, baseMatches);
        if (matched.isEmpty()) return List.of();

        ExternalArtist target = matched.get().artist();
        String pageUrl = target.avatarUrl();
        if (pageUrl == null || pageUrl.isBlank()) return List.of();

        String html = http.getHtmlViaMediaChannel(
                pageUrl,
                Map.of("Referer", "https://music.apple.com/", "User-Agent", USER_AGENT),
                timeout);
        String avatarUrl = extractPortraitUrl(html);
        if (avatarUrl == null) return List.of();
        return List.of(new ExternalArtist(provider(), target.externalId(), target.displayName(), List.of(), avatarUrl));
    }

    List<ArtistCandidate> parseSearchResults(JsonNode results, int limit) {
        List<ArtistCandidate> out = new ArrayList<>();
        if (!results.isArray()) return out;
        for (JsonNode node : results) {
            String wrapperType = ProviderJson.text(node, "wrapperType", 64);
            if (wrapperType != null && !"artist".equalsIgnoreCase(wrapperType)) continue;
            String id = ProviderJson.clean(node.path("artistId").asText(null), 64);
            String name = ProviderJson.text(node, "artistName", 200);
            String linkUrl = ProviderJson.https(ProviderJson.text(node, "artistLinkUrl", 2000), "music.apple.com");
            if (id == null || name == null || linkUrl == null) continue;
            out.add(new ArtistCandidate(id, name, linkUrl));
            if (out.size() >= limit) break;
        }
        return out;
    }

    static String extractPortraitUrl(String html) {
        if (html == null || html.isBlank()) return null;
        String raw = matchUrl(OG_IMAGE, html);
        if (raw == null) raw = matchUrl(OG_IMAGE_REVERSED, html);
        if (raw == null) return null;
        String safe = ProviderJson.https(raw, "mzstatic.com");
        if (safe == null || AvatarImageQualityValidator.isKnownPlaceholderUrl(safe)) return null;
        // Skip generic Apple Music brand social card images when the artist has no portrait.
        String lower = safe.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("apple-music-logo") || lower.contains("/static-")) return null;
        return safe;
    }

    private static String matchUrl(Pattern pattern, String html) {
        Matcher matcher = pattern.matcher(html);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    record ArtistCandidate(String id, String name, String linkUrl) {}
}
