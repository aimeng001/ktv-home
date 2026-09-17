package com.homektv.musicsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class NeteaseMusicMetadataProvider implements MusicMetadataProvider {
    private final ObjectMapper mapper;
    private final MusicSourceHttp http;

    public NeteaseMusicMetadataProvider(ObjectMapper mapper, ProviderCallGuard guard) {
        this.mapper = mapper;
        this.http = new MusicSourceHttp(mapper, MusicProvider.NETEASE,
                Set.of("interfacepc.music.163.com", "music.163.com"), guard);
    }

    @Override public MusicProvider provider() { return MusicProvider.NETEASE; }

    @Override
    public List<ExternalTrack> search(String keyword, int limit, Duration timeout) {
        ObjectNode data = mapper.createObjectNode();
        data.put("s", keyword); data.put("type", 1); data.put("limit", Math.min(limit, 50));
        data.put("offset", 0); data.put("total", true);
        ObjectNode header = data.putObject("header");
        header.put("os", "pc"); header.put("appver", "3.1.0"); header.put("requestId", String.valueOf(System.currentTimeMillis()));
        String path = "/api/cloudsearch/pc";
        JsonNode root = http.form("https://interfacepc.music.163.com/eapi/cloudsearch/pc",
                Map.of("params", NeteaseCrypto.eapi(path, compact(data))), Map.of("Referer", "https://music.163.com/"), timeout);
        return parseSongs(root.path("result").path("songs"), limit);
    }

    @Override
    public ExternalTrack detail(String externalId, Duration timeout) {
        ObjectNode data = mapper.createObjectNode();
        data.put("c", "[{\"id\":" + numericId(externalId) + "}]"); data.put("csrf_token", "");
        NeteaseCrypto.WeapiPayload encrypted = NeteaseCrypto.weapi(compact(data));
        JsonNode root = http.form("https://music.163.com/weapi/v3/song/detail",
                Map.of("params", encrypted.params(), "encSecKey", encrypted.encSecKey()),
                Map.of("Referer", "https://music.163.com/"), timeout);
        List<ExternalTrack> tracks = parseSongs(root.path("songs"), 1);
        if (tracks.isEmpty()) throw new MusicSourceException(provider(), "歌曲详情不存在");
        return tracks.getFirst();
    }

    List<ExternalTrack> parseSongs(JsonNode songs, int limit) {
        List<ExternalTrack> result = new ArrayList<>();
        if (!songs.isArray()) return result;
        for (JsonNode song : songs) {
            JsonNode album = song.has("al") ? song.path("al") : song.path("album");
            JsonNode artists = song.has("ar") ? song.path("ar") : song.path("artists");
            String cover = ProviderJson.https(ProviderJson.text(album, "picUrl", 2000), "music.126.net", "p1.music.126.net", "p2.music.126.net", "p3.music.126.net", "p4.music.126.net");
            String release = null;
            long publish = song.path("publishTime").asLong(0);
            if (publish > 0) release = Instant.ofEpochMilli(publish).atZone(ZoneOffset.UTC).toLocalDate().toString();
            List<String> aliases = ProviderJson.names(song.has("alia") ? song.path("alia") : song.path("alias"), null);
            ExternalTrack track = ProviderJson.track(provider(), song.path("id").asText(), ProviderJson.text(song, "name", 500),
                    ProviderJson.names(artists, "name"), ProviderJson.text(album, "name", 500),
                    song.has("dt") ? song.path("dt").asInt() : (song.has("duration") ? song.path("duration").asInt() : null),
                    release, aliases, cover, song.path("fee").asInt(0) == 4 ? "UNAVAILABLE" : "AVAILABLE");
            if (track != null) result.add(track);
            if (result.size() >= limit) break;
        }
        return result;
    }

    private String compact(JsonNode node) {
        try { return mapper.writeValueAsString(node); } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static long numericId(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException ex) { throw new MusicSourceException(MusicProvider.NETEASE, "网易云歌曲 ID 无效"); }
    }
}
