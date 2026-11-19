package com.homektv.musicsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.homektv.library.ArtistAvatarMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** QQ Music singer lookup adapter with smartbox primary and musicu.fcg fallback. */
@Component
public class QqArtistMetadataProvider implements ArtistMetadataProvider {
    private static final String HOST = "https://c.y.qq.com";
    private static final String MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private final ObjectMapper mapper;
    private final MusicSourceHttp http;

    @Autowired
    public QqArtistMetadataProvider(ObjectMapper mapper, ProviderCallGuard guard) {
        this(mapper, new MusicSourceHttp(mapper, MusicProvider.QQ,
                Set.of("c.y.qq.com", "u.y.qq.com"), guard));
    }

    QqArtistMetadataProvider(ObjectMapper mapper, MusicSourceHttp http) {
        this.mapper = mapper;
        this.http = http;
    }

    @Override
    public MusicProvider provider() { return MusicProvider.QQ; }

    @Override
    public List<ExternalArtist> search(String artistName, int limit, Duration timeout) {
        String lookup = ArtistAvatarMatcher.cleanLookupName(artistName);
        if (lookup.isBlank()) return List.of();
        try {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("format", "json");
            query.put("key", lookup);
            query.put("inCharset", "utf-8");
            query.put("outCharset", "utf-8");
            JsonNode root = http.get(HOST + "/splcloud/fcgi-bin/smartbox_new.fcg?" + MusicSourceHttp.query(query),
                    Map.of("Referer", "https://y.qq.com/", "User-Agent", USER_AGENT), timeout);
            List<ExternalArtist> primary = parseArtists(root.path("data").path("singer").path("itemlist"), limit);
            if (!primary.isEmpty()) return primary;
        } catch (ProviderRateLimitedException | ProviderRateStateUnavailableException ex) {
            throw ex;
        } catch (MusicSourceException ignored) {
            // Fall back to musicu singer search endpoint when smartbox is empty or temporarily failing.
        }
        List<ExternalArtist> viaMusicu = List.of();
        try {
            viaMusicu = searchViaMusicu(lookup, limit, timeout);
        } catch (ProviderRateLimitedException | ProviderRateStateUnavailableException ex) {
            throw ex;
        } catch (MusicSourceException ignored) {
            // Fall through to client_search_cp song singer fallback.
        }
        if (!viaMusicu.isEmpty()) return viaMusicu;
        return searchFromSongSingers(lookup, limit, timeout);
    }

    private List<ExternalArtist> searchFromSongSingers(String lookup, int limit, Duration timeout) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("format", "json");
        query.put("outCharset", "utf-8");
        query.put("ct", 24);
        query.put("qqmusic_ver", 1298);
        query.put("remoteplace", "txt.yqq.song");
        query.put("platform", "yqq.json");
        query.put("aggr", 1);
        query.put("cr", 1);
        query.put("p", 1);
        query.put("n", 5);
        query.put("w", lookup);
        JsonNode root = http.get(HOST + "/soso/fcgi-bin/client_search_cp?" + MusicSourceHttp.query(query),
                Map.of("Referer", "https://y.qq.com/", "User-Agent", USER_AGENT), timeout);
        JsonNode songs = root.path("data").path("song").path("list");
        if (!songs.isArray()) return List.of();
        List<ExternalArtist> singers = new ArrayList<>();
        java.util.Set<String> seenIds = new java.util.LinkedHashSet<>();
        for (JsonNode song : songs) {
            JsonNode songSingers = song.path("singer");
            if (!songSingers.isArray()) continue;
            for (ExternalArtist parsed : parseArtists(songSingers, limit)) {
                if (seenIds.add(parsed.externalId())) {
                    singers.add(parsed);
                    if (singers.size() >= limit) return singers;
                }
            }
        }
        return singers;
    }

    private List<ExternalArtist> searchViaMusicu(String lookup, int limit, Duration timeout) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode comm = root.putObject("comm");
        comm.put("ct", 19);
        comm.put("cv", 1859);
        ObjectNode req = root.putObject("req");
        req.put("module", "music.search.SearchCgiService");
        req.put("method", "DoSearchForQQMusic");
        ObjectNode param = req.putObject("param");
        param.put("search_type", 9);
        param.put("query", lookup);
        param.put("page_num", 1);
        param.put("num_per_page", Math.min(Math.max(limit, 1), 20));
        try {
            String body = mapper.writeValueAsString(root);
            JsonNode response = http.postJson(MUSICU_URL, body,
                    Map.of("Referer", "https://y.qq.com/", "User-Agent", USER_AGENT), timeout);
            JsonNode list = response.path("req").path("data").path("body").path("singer").path("list");
            return parseArtists(list, limit);
        } catch (MusicSourceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MusicSourceException(MusicProvider.QQ, "QQ歌手查询序列化失败", ex);
        }
    }

    List<ExternalArtist> parseArtists(JsonNode artists, int limit) {
        List<ExternalArtist> result = new ArrayList<>();
        if (!artists.isArray()) return result;
        for (JsonNode artist : artists) {
            String id = first(artist, "singer_mid", "singerMid", "mid", "singerid", "singerID", "id");
            String name = first(artist, "singername", "singerName", "name");
            if (id == null || name == null) continue;
            String pic = first(artist, "singerPic", "pic", "singer_pic");
            String avatar = ProviderJson.https(pic, "y.gtimg.cn");
            if (avatar == null && !id.matches("\\d+")) {
                avatar = ProviderJson.https(
                        "https://y.gtimg.cn/music/photo_new/T001R300x300M000" + id + ".jpg",
                        "y.gtimg.cn");
            }
            if (AvatarImageQualityValidator.isKnownPlaceholderUrl(avatar)) {
                avatar = null;
            }
            result.add(new ExternalArtist(provider(), id, name, List.of(), avatar));
            if (result.size() >= limit) break;
        }
        return result;
    }

    private static String first(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = ProviderJson.text(node, field, 2000);
            if (value != null) return value;
        }
        return null;
    }
}
