package com.homektv.musicsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class QqMusicMetadataProvider implements MusicMetadataProvider {
    private static final String HOST = "https://c.y.qq.com";
    private final MusicSourceHttp http;

    public QqMusicMetadataProvider(ObjectMapper mapper, ProviderCallGuard guard) {
        this.http = new MusicSourceHttp(mapper, MusicProvider.QQ, Set.of("c.y.qq.com"), guard);
    }

    @Override public MusicProvider provider() { return MusicProvider.QQ; }

    @Override
    public List<ExternalTrack> search(String keyword, int limit, Duration timeout) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("format", "json"); query.put("outCharset", "utf-8"); query.put("ct", 24);
        query.put("qqmusic_ver", 1298); query.put("remoteplace", "txt.yqq.song");
        query.put("platform", "yqq.json"); query.put("aggr", 1); query.put("cr", 1);
        query.put("p", 1); query.put("n", Math.min(limit, 50)); query.put("w", keyword);
        JsonNode root = http.get(HOST + "/soso/fcgi-bin/client_search_cp?" + MusicSourceHttp.query(query), Map.of(), timeout);
        return parseSongs(root.path("data").path("song").path("list"), limit);
    }

    @Override
    public ExternalTrack detail(String externalId, Duration timeout) {
        String url = HOST + "/v8/fcg-bin/fcg_play_single_song.fcg?format=json&platform=yqq&songmid="
                + java.net.URLEncoder.encode(externalId, java.nio.charset.StandardCharsets.UTF_8);
        JsonNode root = http.get(url, Map.of(), timeout);
        List<ExternalTrack> tracks = parseSongs(root.path("data"), 1);
        if (tracks.isEmpty()) throw new MusicSourceException(provider(), "歌曲详情不存在");
        return tracks.getFirst();
    }

    List<ExternalTrack> parseSongs(JsonNode songs, int limit) {
        List<ExternalTrack> result = new ArrayList<>();
        if (!songs.isArray()) return result;
        for (JsonNode song : songs) {
            String id = first(song, "songmid", "mid");
            String title = first(song, "songname", "name", "title");
            JsonNode singers = song.has("singer") ? song.path("singer") : song.path("singername");
            List<String> artists = singers.isArray() ? ProviderJson.names(singers, "name") : ProviderJson.splitArtists(singers.asText(null));
            JsonNode album = song.path("album");
            String albumName = first(song, "albumname");
            if (albumName == null && album.isObject()) albumName = ProviderJson.text(album, "name", 500);
            String albumMid = first(song, "albummid");
            if (albumMid == null && album.isObject()) albumMid = ProviderJson.text(album, "mid", 160);
            String cover = albumMid == null ? null : ProviderJson.https(
                    "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albumMid + ".jpg", "y.gtimg.cn");
            Integer duration = song.has("interval") ? song.path("interval").asInt() * 1000
                    : (song.has("duration") ? song.path("duration").asInt() * 1000 : null);
            String release = null;
            long pubtime = song.path("pubtime").asLong(0);
            if (pubtime > 0) release = Instant.ofEpochSecond(pubtime).atZone(ZoneOffset.UTC).toLocalDate().toString();
            ExternalTrack track = ProviderJson.track(provider(), id, title, artists, albumName, duration, release,
                    ProviderJson.names(song.path("grp"), "title"), cover, "AVAILABLE");
            if (track != null) result.add(track);
            if (result.size() >= limit) break;
        }
        return result;
    }

    private static String first(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = ProviderJson.text(node, field, 500);
            if (value != null) return value;
        }
        return null;
    }
}
