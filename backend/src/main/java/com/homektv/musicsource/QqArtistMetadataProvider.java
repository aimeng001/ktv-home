package com.homektv.musicsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** QQ Music singer lookup adapter using its public suggestion endpoint. */
@Component
public class QqArtistMetadataProvider implements ArtistMetadataProvider {
    private static final String HOST = "https://c.y.qq.com";
    private final MusicSourceHttp http;

    public QqArtistMetadataProvider(ObjectMapper mapper, ProviderCallGuard guard) {
        this.http = new MusicSourceHttp(mapper, MusicProvider.QQ, Set.of("c.y.qq.com"), guard);
    }

    @Override
    public MusicProvider provider() { return MusicProvider.QQ; }

    @Override
    public List<ExternalArtist> search(String artistName, int limit, Duration timeout) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("format", "json");
        query.put("key", artistName);
        query.put("inCharset", "utf-8");
        query.put("outCharset", "utf-8");
        JsonNode root = http.get(HOST + "/splcloud/fcgi-bin/smartbox_new.fcg?" + MusicSourceHttp.query(query),
                Map.of(), timeout);
        return parseArtists(root.path("data").path("singer").path("itemlist"), limit);
    }

    List<ExternalArtist> parseArtists(JsonNode artists, int limit) {
        List<ExternalArtist> result = new ArrayList<>();
        if (!artists.isArray()) return result;
        for (JsonNode artist : artists) {
            String id = first(artist, "singer_mid", "mid", "singerid", "id");
            String name = first(artist, "singername", "name");
            if (id == null || name == null) continue;
            String avatar = ProviderJson.https(
                    "https://y.gtimg.cn/music/photo_new/T001R300x300M000" + id + ".jpg",
                    "y.gtimg.cn");
            result.add(new ExternalArtist(provider(), id, name, List.of(), avatar));
            if (result.size() >= limit) break;
        }
        return result;
    }

    private static String first(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = ProviderJson.text(node, field, 200);
            if (value != null) return value;
        }
        return null;
    }
}
