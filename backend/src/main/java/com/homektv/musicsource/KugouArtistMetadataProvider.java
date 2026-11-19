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

/** Kugou singer lookup adapter using mobile singer search with song-author fallback. */
@Component
public class KugouArtistMetadataProvider implements ArtistMetadataProvider {
    private static final String MOBILE_UA = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi";
    private final MusicSourceHttp http;
    private final MusicSourceConfigService configService;

    @Autowired
    public KugouArtistMetadataProvider(ObjectMapper mapper, ProviderCallGuard guard,
                                       MusicSourceConfigService configService) {
        this(new MusicSourceHttp(mapper, MusicProvider.KUGOU,
                Set.of("mobilecdnbj.kugou.com", "mobilecdn.kugou.com", "gateway.kugou.com"), guard),
                configService);
    }

    KugouArtistMetadataProvider(MusicSourceHttp http, MusicSourceConfigService configService) {
        this.http = http;
        this.configService = configService;
    }

    @Override
    public MusicProvider provider() {
        return MusicProvider.KUGOU;
    }

    @Override
    public List<ExternalArtist> search(String artistName, int limit, Duration timeout) {
        String lookup = ArtistAvatarMatcher.cleanLookupName(artistName);
        if (lookup.isBlank()) return List.of();
        try {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("format", "json");
            query.put("keyword", lookup);
            query.put("page", "1");
            query.put("pagesize", String.valueOf(Math.min(Math.max(limit, 1), 20)));
            JsonNode root = http.get("https://gateway.kugou.com/api/v3/search/singer?" + MusicSourceHttp.query(query),
                    Map.of("x-router", "mobilecdn.kugou.com", "User-Agent", MOBILE_UA), timeout);
            JsonNode data = root.path("data");
            JsonNode singers = data.isArray() ? data : data.path("info");
            List<ExternalArtist> primary = parseSingers(singers, limit);
            if (!primary.isEmpty()) {
                return enrichMissingAvatar(artistName, primary, timeout);
            }
        } catch (ProviderRateLimitedException | ProviderRateStateUnavailableException ex) {
            throw ex;
        } catch (MusicSourceException ignored) {
            // Fall back to Kugou gateway song search authors when v3 singer endpoint misses or throttles.
        }
        return searchFromSongAuthors(lookup, limit, timeout);
    }

    private List<ExternalArtist> enrichMissingAvatar(String artistName, List<ExternalArtist> candidates, Duration timeout) {
        Optional<ArtistAvatarMatcher.Match> matched = ArtistAvatarMatcher.bestMatch(artistName, candidates);
        if (matched.isEmpty()) return candidates;
        ExternalArtist target = matched.get().artist();
        if (target.avatarUrl() != null && !target.avatarUrl().isBlank()) return candidates;
        String singerId = target.externalId();
        if (singerId == null || singerId.isBlank()) return candidates;
        try {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("format", "json");
            query.put("singerid", singerId);
            JsonNode root = http.get("https://gateway.kugou.com/api/v3/singer/info?" + MusicSourceHttp.query(query),
                    Map.of("x-router", "mobilecdn.kugou.com", "User-Agent", MOBILE_UA), timeout);
            String avatar = normalizeAvatarUrl(first(root.path("data"), "imgurl", "sizable_avatar", "avatar"));
            if (avatar == null) return candidates;
            List<ExternalArtist> enriched = new ArrayList<>(candidates.size());
            for (ExternalArtist item : candidates) {
                if (singerId.equals(item.externalId())) {
                    enriched.add(new ExternalArtist(item.provider(), item.externalId(),
                            item.displayName(), item.aliases(), avatar));
                } else {
                    enriched.add(item);
                }
            }
            return enriched;
        } catch (ProviderRateLimitedException | ProviderRateStateUnavailableException ex) {
            throw ex;
        } catch (MusicSourceException ignored) {
            return candidates;
        }
    }

    private List<ExternalArtist> searchFromSongAuthors(String lookup, int limit, Duration timeout) {
        String mid = configService.getOrCreateKugouDeviceId();
        String now = String.valueOf(System.currentTimeMillis() / 1000);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dfid", "-");
        params.put("mid", mid);
        params.put("uuid", "-");
        params.put("appid", "1005");
        params.put("clientver", "20489");
        params.put("clienttime", now);
        params.put("albumhide", "0");
        params.put("iscorrection", "1");
        params.put("keyword", lookup);
        params.put("nocollect", "0");
        params.put("page", "1");
        params.put("pagesize", "10");
        params.put("platform", "AndroidFilter");
        params.put("signature", KugouMusicMetadataProvider.androidSignature(params, ""));
        JsonNode root = http.get("https://gateway.kugou.com/v3/search/song?" + MusicSourceHttp.query(params),
                Map.of("x-router", "complexsearch.kugou.com", "dfid", "-", "mid", mid,
                        "clienttime", now, "User-Agent", MOBILE_UA), timeout);
        if (root.path("status").asInt(0) != 1 || root.path("error_code").asInt(0) != 0) {
            return List.of();
        }
        JsonNode songs = root.path("data").path("info");
        if (!songs.isArray()) songs = root.path("data").path("lists");
        return parseAuthorsFromSongs(songs, limit);
    }

    List<ExternalArtist> parseSingers(JsonNode singers, int limit) {
        List<ExternalArtist> result = new ArrayList<>();
        if (!singers.isArray()) return result;
        for (JsonNode singer : singers) {
            String id = first(singer, "singerid", "AuthorId", "id");
            String name = first(singer, "singername", "author_name", "name");
            if (id == null || name == null) continue;
            String avatar = normalizeAvatarUrl(first(singer, "imgurl", "sizable_avatar", "avatar"));
            result.add(new ExternalArtist(provider(), id, name, List.of(), avatar));
            if (result.size() >= limit) break;
        }
        return result;
    }

    List<ExternalArtist> parseAuthorsFromSongs(JsonNode songs, int limit) {
        List<ExternalArtist> result = new ArrayList<>();
        if (!songs.isArray()) return result;
        for (JsonNode song : songs) {
            String songImage = normalizeAvatarUrl(first(song, "Image", "img", "sizable_cover"));
            if (songImage == null) {
                songImage = normalizeAvatarUrl(first(song.path("trans_param"), "union_cover"));
            }
            JsonNode authors = song.path("authors");
            if (authors.isArray()) {
                for (JsonNode author : authors) {
                    String id = first(author, "author_id", "singerid", "id");
                    String name = first(author, "author_name", "singername", "name");
                    if (id == null || name == null) continue;
                    String avatar = normalizeAvatarUrl(first(author, "sizable_avatar", "avatar"));
                    if (avatar != null) {
                        result.add(new ExternalArtist(provider(), id, name, List.of(), avatar));
                    }
                    if (result.size() >= limit) return result;
                }
            }
            JsonNode singers = song.path("Singers");
            if (singers.isArray()) {
                for (JsonNode singer : singers) {
                    if (addSingerWithFallbackImage(singer, songImage, result) && result.size() >= limit) {
                        return result;
                    }
                }
            } else if (singers.isObject()) {
                if (addSingerWithFallbackImage(singers, songImage, result) && result.size() >= limit) {
                    return result;
                }
            }
        }
        return result;
    }

    private boolean addSingerWithFallbackImage(JsonNode singer, String fallbackImage, List<ExternalArtist> result) {
        String id = first(singer, "id", "singerid", "author_id");
        String name = first(singer, "name", "singername", "author_name");
        if (id == null || name == null) return false;
        String avatar = normalizeAvatarUrl(first(singer, "sizable_avatar", "avatar", "imgurl"));
        if (avatar == null) avatar = fallbackImage;
        if (avatar == null) return false;
        result.add(new ExternalArtist(provider(), id, name, List.of(), avatar));
        return true;
    }

    private static String normalizeAvatarUrl(String rawAvatar) {
        if (rawAvatar == null || rawAvatar.isBlank()) return null;
        String normalized = rawAvatar.replace("{size}", "400");
        String avatar = ProviderJson.https(normalized, "kugou.com", "kugoucdn.com", "kgimg.com");
        return AvatarImageQualityValidator.isKnownPlaceholderUrl(avatar) ? null : avatar;
    }

    private static String first(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = ProviderJson.text(node, field, 2000);
            if (value != null) return value;
        }
        return null;
    }
}
