package com.homektv.musicsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.homektv.library.ArtistAvatarMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** NetEase artist search adapter; prefers picUrl and rejects grey silhouette placeholders. */
@Component
public class NeteaseArtistMetadataProvider implements ArtistMetadataProvider {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private final ObjectMapper mapper;
    private final MusicSourceHttp http;

    @Autowired
    public NeteaseArtistMetadataProvider(ObjectMapper mapper, ProviderCallGuard guard) {
        this(mapper, new MusicSourceHttp(mapper, MusicProvider.NETEASE,
                Set.of("interfacepc.music.163.com", "music.163.com"), guard));
    }

    NeteaseArtistMetadataProvider(ObjectMapper mapper, MusicSourceHttp http) {
        this.mapper = mapper;
        this.http = http;
    }

    @Override
    public MusicProvider provider() { return MusicProvider.NETEASE; }

    @Override
    public List<ExternalArtist> search(String artistName, int limit, Duration timeout) {
        String lookup = ArtistAvatarMatcher.cleanLookupName(artistName);
        if (lookup.isBlank()) return List.of();
        ObjectNode data = mapper.createObjectNode();
        data.put("s", lookup);
        data.put("type", 100);
        data.put("limit", Math.min(Math.max(limit, 1), 20));
        data.put("offset", 0);
        data.put("total", true);
        ObjectNode header = data.putObject("header");
        header.put("os", "pc");
        header.put("appver", "3.1.0");
        header.put("requestId", String.valueOf(System.currentTimeMillis()));
        JsonNode root = http.form("https://interfacepc.music.163.com/eapi/cloudsearch/pc",
                Map.of("params", NeteaseCrypto.eapi("/api/cloudsearch/pc", compact(data))),
                Map.of("Referer", "https://music.163.com/", "User-Agent", USER_AGENT), timeout);
        return parseArtists(root.path("result").path("artists"), limit);
    }

    List<ExternalArtist> parseArtists(JsonNode artists, int limit) {
        List<ExternalArtist> result = new ArrayList<>();
        if (!artists.isArray()) return result;
        for (JsonNode artist : artists) {
            String id = ProviderJson.clean(artist.path("id").asText(null), 160);
            String name = ProviderJson.text(artist, "name", 200);
            if (id == null || name == null) continue;
            // Check img1v1Url first per contract, skipping known NetEase grey silhouette placeholders so picUrl is chosen.
            String avatar = firstValidAvatar(artist, "img1v1Url", "picUrl");
            List<String> aliases = ProviderJson.names(
                    artist.has("alias") ? artist.path("alias") : artist.path("aliases"), null);
            result.add(new ExternalArtist(provider(), id, name, aliases, avatar));
            if (result.size() >= limit) break;
        }
        return result;
    }

    private String compact(JsonNode node) {
        try { return mapper.writeValueAsString(node); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static String firstValidAvatar(JsonNode node, String... fields) {
        for (String field : fields) {
            String candidate = ProviderJson.https(
                    ProviderJson.text(node, field, 2000),
                    "music.126.net", "p1.music.126.net", "p2.music.126.net",
                    "p3.music.126.net", "p4.music.126.net");
            if (candidate != null && !AvatarImageQualityValidator.isKnownPlaceholderUrl(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
