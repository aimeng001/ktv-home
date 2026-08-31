package com.homektv.musicsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** NetEase artist search adapter; it never treats a track cover as an avatar. */
@Component
public class NeteaseArtistMetadataProvider implements ArtistMetadataProvider {
    private final ObjectMapper mapper;
    private final MusicSourceHttp http;
    private final ProviderCallGuard guard;

    public NeteaseArtistMetadataProvider(ObjectMapper mapper, ProviderCallGuard guard) {
        this.mapper = mapper;
        this.http = new MusicSourceHttp(mapper, MusicProvider.NETEASE,
                Set.of("interfacepc.music.163.com", "music.163.com"));
        this.guard = guard;
    }

    @Override
    public MusicProvider provider() { return MusicProvider.NETEASE; }

    @Override
    public List<ExternalArtist> search(String artistName, int limit, Duration timeout) {
        return guard.call(provider(), () -> {
            ObjectNode data = mapper.createObjectNode();
            data.put("s", artistName);
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
                    Map.of("Referer", "https://music.163.com/"), timeout);
            return parseArtists(root.path("result").path("artists"), limit);
        });
    }

    List<ExternalArtist> parseArtists(JsonNode artists, int limit) {
        List<ExternalArtist> result = new ArrayList<>();
        if (!artists.isArray()) return result;
        for (JsonNode artist : artists) {
            String id = ProviderJson.clean(artist.path("id").asText(null), 160);
            String name = ProviderJson.text(artist, "name", 200);
            if (id == null || name == null) continue;
            String avatar = ProviderJson.https(
                    first(artist, "img1v1Url", "picUrl"),
                    "music.126.net", "p1.music.126.net", "p2.music.126.net",
                    "p3.music.126.net", "p4.music.126.net");
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

    private static String first(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = ProviderJson.text(node, field, 2000);
            if (value != null) return value;
        }
        return null;
    }
}
