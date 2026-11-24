package com.homektv.musicsource;

import com.homektv.domain.Song;
import com.homektv.library.ArtistAvatarMatcher;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class ExternalTrackMatcher {
    private static final Pattern DISPLAY_MARKER = Pattern.compile(
            "[（(]\\s*(?:mtv|mv|ktv|演|原唱)\\s*[)）]", Pattern.CASE_INSENSITIVE);
    private static final Pattern CATALOG_SUFFIX = Pattern.compile(
            "(?:[-_—–]+\\s*(?:国语|粤语|闽南语|英语|日语|韩语|纯音乐|其他|未知|流行(?:歌曲)?|摇滚|民谣|电子|舞曲|经典|影视原声|儿歌|戏曲|说唱|r&b|爵士|古典))+$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIVE_TAG = Pattern.compile("(?:[(（\\[【]\\s*(?:live|现场|演唱会)\\s*[)）\\]】]|\\blive版?\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DJ_REMIX_TAG = Pattern.compile("(?:[(（\\[【]\\s*(?:dj|remix|电音|舞曲)\\s*[)）\\]】]|\\bdj版\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCOMPANIMENT_TAG = Pattern.compile("(?:[(（\\[【]\\s*(?:伴奏|消音|纯音乐|instrumental)\\s*[)）\\]】])", Pattern.CASE_INSENSITIVE);

    public double score(Song song, ExternalTrack track) {
        double title = similarity(normalizeTitle(song.getTitle()), normalizeTitle(track.title()));
        String externalArtists = track.artists() == null ? "" : String.join(" / ", track.artists());
        double directArtist = similarity(normalize(song.getArtist()), normalize(externalArtists));
        double tokenArtist = similarity(normalizeSortedArtists(song.getArtist()), normalizeSortedArtists(externalArtists));
        double artist = Math.max(directArtist, tokenArtist);
        double duration = 0.5;
        if (song.getDurationMs() > 0 && track.durationMs() != null && track.durationMs() > 0) {
            int difference = Math.abs(song.getDurationMs() - track.durationMs());
            duration = Math.max(0, 1 - difference / 30_000.0);
        }
        double score = title * 0.55 + artist * 0.35 + duration * 0.10;
        if (hasVersionMismatch(song.getTitle(), track.title())) {
            score = Math.max(0.0, score - 0.12);
        }
        return Math.round(score * 1000) / 1000.0;
    }

    public String groupKey(ExternalTrack track) {
        int bucket = track.durationMs() == null ? -1 : (track.durationMs() + 15_000) / 30_000;
        String externalArtists = track.artists() == null ? "" : String.join(" ", track.artists());
        return normalizeTitle(track.title()) + "|" + normalize(externalArtists) + "|" + bucket;
    }

    private static boolean hasVersionMismatch(String localTitle, String externalTitle) {
        if (localTitle == null || externalTitle == null) return false;
        String a = ArtistAvatarMatcher.toSimplified(Normalizer.normalize(localTitle, Normalizer.Form.NFKC));
        String b = ArtistAvatarMatcher.toSimplified(Normalizer.normalize(externalTitle, Normalizer.Form.NFKC));
        return (LIVE_TAG.matcher(a).find() != LIVE_TAG.matcher(b).find())
                || (DJ_REMIX_TAG.matcher(a).find() != DJ_REMIX_TAG.matcher(b).find())
                || (ACCOMPANIMENT_TAG.matcher(a).find() != ACCOMPANIMENT_TAG.matcher(b).find());
    }

    private static String normalizeTitle(String value) {
        if (value == null) return "";
        String cleaned = DISPLAY_MARKER.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC)).replaceAll("");
        cleaned = CATALOG_SUFFIX.matcher(cleaned).replaceAll("");
        return normalize(cleaned);
    }

    private static String normalizeSortedArtists(String value) {
        if (value == null || value.isBlank()) return "";
        List<String> tokens = Arrays.stream(value.split("\\s*(?:[_&＆、/／,，;；+·•]|\\s+)\\s*"))
                .map(ExternalTrackMatcher::normalize)
                .filter(token -> !token.isEmpty())
                .distinct()
                .sorted()
                .toList();
        return String.join("", tokens);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String simplified = ArtistAvatarMatcher.toSimplified(Normalizer.normalize(value, Normalizer.Form.NFKC));
        return simplified.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s·•]+", "");
    }

    private static double similarity(String left, String right) {
        if (left.equals(right)) return left.isEmpty() ? 0 : 1;
        if (left.isEmpty() || right.isEmpty()) return 0;
        if (left.contains(right) || right.contains(left)) return (double) Math.min(left.length(), right.length()) / Math.max(left.length(), right.length());
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1]; current[0] = i;
            for (int j = 1; j <= right.length(); j++) current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            previous = current;
        }
        return Math.max(0, 1.0 - (double) previous[right.length()] / Math.max(left.length(), right.length()));
    }
}
