package com.homektv.musicsource;

import com.fasterxml.jackson.core.JsonParser;
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
import java.util.Set;

/** Kuwo Music artist lookup adapter using the lightweight public artist search endpoint. */
@Component
public class KuwoArtistMetadataProvider implements ArtistMetadataProvider {
    private static final String DEFAULT_BASE_PIC = "https://img1.kuwo.cn/star/starheads/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private final MusicSourceHttp http;

    @Autowired
    public KuwoArtistMetadataProvider(ObjectMapper mapper, ProviderCallGuard guard) {
        this(new MusicSourceHttp(
                mapper.copy().enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES),
                MusicProvider.KUWO,
                Set.of("search.kuwo.cn", "artistpicserver.kuwo.cn"),
                guard));
    }

    KuwoArtistMetadataProvider(MusicSourceHttp http) {
        this.http = http;
    }

    @Override
    public MusicProvider provider() {
        return MusicProvider.KUWO;
    }

    @Override
    public List<ExternalArtist> search(String artistName, int limit, Duration timeout) {
        String lookup = ArtistAvatarMatcher.cleanLookupName(artistName);
        if (lookup.isBlank()) return List.of();
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client", "kt");
        query.put("all", lookup);
        query.put("pn", "0");
        query.put("rn", String.valueOf(Math.min(Math.max(limit, 1), 20)));
        query.put("ft", "artist");
        query.put("rformat", "json");
        query.put("encoding", "utf8");
        JsonNode root = http.get(
                "https://search.kuwo.cn/r.s?" + MusicSourceHttp.query(query),
                Map.of("Referer", "https://www.kuwo.cn/", "User-Agent", USER_AGENT),
                timeout);
        String basePic = normalizeBasePic(ProviderJson.text(root, "BASEPICPATH", 500));
        return parseArtists(root.path("abslist"), basePic, limit);
    }

    List<ExternalArtist> parseArtists(JsonNode abslist, String basePicPath, int limit) {
        List<ExternalArtist> result = new ArrayList<>();
        if (!abslist.isArray()) return result;
        String base = basePicPath == null || basePicPath.isBlank() ? DEFAULT_BASE_PIC : basePicPath;
        for (JsonNode artist : abslist) {
            String id = first(artist, "ARTISTID", "DC_TARGETID", "id");
            String name = cleanEntityText(first(artist, "ARTIST", "name"));
            if (id == null || name == null) continue;
            String picPath = first(artist, "hts_PICPATH", "PICPATH", "pic");
            String avatar = resolveAvatarUrl(base, picPath);
            String alias = cleanEntityText(first(artist, "AARTIST"));
            List<String> aliases = alias != null && !alias.equalsIgnoreCase(name) ? List.of(alias) : List.of();
            result.add(new ExternalArtist(provider(), id, name, aliases, avatar));
            if (result.size() >= limit) break;
        }
        return result;
    }

    private static String normalizeBasePic(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_BASE_PIC;
        String trimmed = raw.trim();
        if (trimmed.startsWith("http://")) trimmed = "https://" + trimmed.substring("http://".length());
        if (!trimmed.endsWith("/")) trimmed = trimmed + "/";
        return trimmed;
    }

    private static String resolveAvatarUrl(String basePicPath, String picPath) {
        if (picPath == null || picPath.isBlank()) return null;
        String cleaned = picPath.trim();
        String raw = cleaned.startsWith("http://") || cleaned.startsWith("https://")
                ? cleaned
                : basePicPath + cleaned;
        String avatar = ProviderJson.https(raw, "kuwo.cn", "kuwoimg.com");
        return AvatarImageQualityValidator.isKnownPlaceholderUrl(avatar) ? null : avatar;
    }

    private static String cleanEntityText(String value) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private static String first(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = ProviderJson.text(node, field, 2000);
            if (value != null) return value;
        }
        return null;
    }
}
