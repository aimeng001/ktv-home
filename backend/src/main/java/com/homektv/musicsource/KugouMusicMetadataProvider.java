package com.homektv.musicsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class KugouMusicMetadataProvider implements MusicMetadataProvider {
    private static final String SALT = "OIlwieks28dk2k092lksi2UIkp";
    private final MusicSourceHttp http;
    private final MusicSourceConfigService configService;

    public KugouMusicMetadataProvider(ObjectMapper mapper, ProviderCallGuard guard, MusicSourceConfigService configService) {
        this.http = new MusicSourceHttp(mapper, MusicProvider.KUGOU,
                Set.of("gateway.kugou.com", "songsearch.kugou.com"), guard);
        this.configService = configService;
    }

    @Override public MusicProvider provider() { return MusicProvider.KUGOU; }

    @Override
    public List<ExternalTrack> search(String keyword, int limit, Duration timeout) {
        return doSearch(keyword, limit, timeout);
    }

    @Override
    public ExternalTrack detail(String externalId, Duration timeout) {
        for (ExternalTrack track : doSearch(externalId, 10, timeout)) {
            if (track.externalId().equalsIgnoreCase(externalId)) return track;
        }
        throw new MusicSourceException(provider(), "歌曲详情不存在");
    }

    private List<ExternalTrack> doSearch(String keyword, int limit, Duration timeout) {
        String mid = configService.getOrCreateKugouDeviceId();
        String now = String.valueOf(System.currentTimeMillis() / 1000);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dfid", "-"); params.put("mid", mid); params.put("uuid", "-");
        params.put("appid", "1005"); params.put("clientver", "20489"); params.put("clienttime", now);
        params.put("albumhide", "0"); params.put("iscorrection", "1"); params.put("keyword", keyword);
        params.put("nocollect", "0"); params.put("page", "1"); params.put("pagesize", String.valueOf(Math.min(limit, 50)));
        params.put("platform", "AndroidFilter");
        params.put("signature", androidSignature(params, ""));
        JsonNode root = http.get("https://gateway.kugou.com/v3/search/song?" + MusicSourceHttp.query(params),
                Map.of("x-router", "complexsearch.kugou.com", "dfid", "-", "mid", mid,
                        "clienttime", now, "User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"), timeout);
        if (root.path("error_code").asInt(0) == 152) return webSearch(keyword, limit, timeout);
        if (root.path("status").asInt(0) != 1 || root.path("error_code").asInt(0) != 0)
            throw new MusicSourceException(provider(), "酷狗搜索暂时不可用");
        JsonNode songs = root.path("data").path("info");
        if (!songs.isArray()) songs = root.path("data").path("lists");
        return parseSongs(songs, limit);
    }

    private List<ExternalTrack> webSearch(String keyword, int limit, Duration timeout) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("keyword", keyword); query.put("page", "1"); query.put("pagesize", String.valueOf(Math.min(limit, 50)));
        query.put("userid", "-1"); query.put("clientver", ""); query.put("platform", "WebFilter");
        query.put("filter", "2"); query.put("iscorrection", "1"); query.put("privilege_filter", "0");
        JsonNode root = http.get("https://songsearch.kugou.com/song_search_v2?" + MusicSourceHttp.query(query), Map.of(), timeout);
        if (root.path("status").asInt(0) != 1 || root.path("error_code").asInt(0) != 0)
            throw new MusicSourceException(provider(), "酷狗公开搜索暂时不可用");
        return parseSongs(root.path("data").path("lists"), limit);
    }

    List<ExternalTrack> parseSongs(JsonNode songs, int limit) {
        List<ExternalTrack> result = new ArrayList<>();
        if (!songs.isArray()) return result;
        for (JsonNode song : songs) {
            String id = first(song, "hash", "audio_id", "audioid", "FileHash");
            String title = first(song, "songname", "song_name", "name", "SongName");
            List<String> artists = ProviderJson.names(song.path("authors"), song.path("authors").isArray() ? "author_name" : null);
            if (artists.isEmpty()) artists = ProviderJson.splitArtists(first(song, "singername", "author_name", "singer_name", "SingerName"));
            String coverRaw = first(song, "sizable_cover", "album_sizable_cover", "img", "Image", "AlbumImage");
            if (coverRaw != null) coverRaw = coverRaw.replace("{size}", "400");
            String cover = ProviderJson.https(coverRaw, "kugou.com", "kugoucdn.com", "kgimg.com");
            Integer duration = null;
            if (song.has("duration")) duration = song.path("duration").asInt() * 1000;
            else if (song.has("Duration")) duration = song.path("Duration").asInt() * 1000;
            else if (song.has("timelength")) duration = song.path("timelength").asInt();
            ExternalTrack track = ProviderJson.track(provider(), id, title, artists,
                    first(song, "album_name", "albumname", "AlbumName"), duration,
                    first(song, "publish_date", "publish_time", "PublishDate"), List.of(), cover, "AVAILABLE");
            if (track != null) result.add(track);
            if (result.size() >= limit) break;
        }
        return result;
    }

    static String androidSignature(Map<String, ?> params, String body) {
        try {
            String joined = params.entrySet().stream().filter(entry -> !"signature".equals(entry.getKey()))
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue())).reduce("", String::concat);
            byte[] digest = MessageDigest.getInstance("MD5").digest((SALT + joined + (body == null ? "" : body) + SALT).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(32);
            for (byte value : digest) out.append(String.format("%02x", value & 0xff));
            return out.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("酷狗签名失败", ex);
        }
    }

    private static String first(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = ProviderJson.text(node, field, 500);
            if (value != null) return value;
        }
        return null;
    }
}
