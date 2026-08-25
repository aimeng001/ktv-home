package com.homektv.library;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文件名规则兜底解析（P1.2，详设§9.3）。
 *
 * <p>标准格式为「歌手-歌名-语种-分类」。解析标准格式时先从右侧确认
 * 语种和分类，再在剩余部分中匹配歌手库；这样歌手或歌名中的连字符不会
 * 被误当成固定的第一个分隔符。</p>
 *
 * <p>标签缺失时仍保留 Home KTV 原有的「歌手 - 歌名」「歌名 - 歌手」
 * 兼容规则。解析只处理字符串，不会修改或重命名源文件。</p>
 */
public final class FilenameParser {

    private static final String DEFAULT_RULE = "artist_title";

    private static final Map<String, String> LANGUAGE_ALIASES = Map.ofEntries(
            Map.entry("国语", "国语"),
            Map.entry("中文", "国语"),
            Map.entry("普通话", "国语"),
            Map.entry("mandarin", "国语"),
            Map.entry("zh-cn", "国语"),
            Map.entry("zh_cn", "国语"),
            Map.entry("粤语", "粤语"),
            Map.entry("cantonese", "粤语"),
            Map.entry("yue", "粤语"),
            Map.entry("zh-hk", "粤语"),
            Map.entry("zh_hk", "粤语"),
            Map.entry("闽南语", "闽南语"),
            Map.entry("台语", "闽南语"),
            Map.entry("hokkien", "闽南语"),
            Map.entry("nan", "闽南语"),
            Map.entry("英语", "英语"),
            Map.entry("英文", "英语"),
            Map.entry("english", "英语"),
            Map.entry("en", "英语"),
            Map.entry("日语", "日语"),
            Map.entry("日文", "日语"),
            Map.entry("japanese", "日语"),
            Map.entry("ja", "日语"),
            Map.entry("韩语", "韩语"),
            Map.entry("韩文", "韩语"),
            Map.entry("korean", "韩语"),
            Map.entry("ko", "韩语"),
            Map.entry("纯音乐", "纯音乐"),
            Map.entry("instrumental", "纯音乐"),
            Map.entry("music", "纯音乐")
    );

    private FilenameParser() {}

    /**
     * 使用当前数据库歌手库为空的安全默认值解析文件名。
     * 多段且无法可靠确认歌手的标准名称会进入 NEEDS_REVIEW。
     */
    public static ParsedMeta parse(String filename) {
        return parse(filename, DEFAULT_RULE, List.of());
    }

    /**
     * 使用已有歌手名称解析标准文件名。歌手名称完全匹配时，优先选择最长匹配。
     */
    public static ParsedMeta parse(String filename, Collection<String> knownArtists) {
        return parse(filename, DEFAULT_RULE, prepareKnownArtists(knownArtists));
    }

    /**
     * 保留原有手工重解析入口。
     */
    public static ParsedMeta parse(String filename, String rule) {
        return parse(filename, rule, List.of());
    }

    /**
     * 按规则解析文件名，并可复用已有歌手库。
     */
    public static ParsedMeta parse(String filename, String rule, Collection<String> knownArtists) {
        return parse(filename, rule, prepareKnownArtists(knownArtists));
    }

    /**
     * Prepare the normalized artist lookup once for a scan. Reusing this index
     * avoids rebuilding a map for every media filename in a large library.
     */
    static ArtistIndex prepareKnownArtists(Collection<String> knownArtists) {
        Map<String, String> normalizedArtists = new HashMap<>();
        if (knownArtists != null) {
            for (String artist : knownArtists) {
                if (artist == null || artist.isBlank()) continue;
                String cleaned = artist.trim();
                String normalized = normalizeArtist(cleaned);
                if (!normalized.isBlank()) normalizedArtists.putIfAbsent(normalized, cleaned);
            }
        }
        return new ArtistIndex(Map.copyOf(normalizedArtists));
    }

    static ParsedMeta parse(String filename, ArtistIndex artistIndex) {
        return parse(filename, DEFAULT_RULE, artistIndex);
    }

    static ParsedMeta parse(String filename, String rule, ArtistIndex artistIndex) {
        String base = stripExtension(filename).trim();
        if (base.isBlank()) return ParsedMeta.unrecognized(base);

        // Strip catalogue numbers and transport/version markers before identifying fields.
        base = base.replaceFirst("^\\s*\\d{1,5}\\s*[-._)】]\\s*", "");
        base = base.replaceAll("\\s*\\[(?:KTV|MTV|MV|LIVE|伴奏|原唱|消音|卡拉OK)\\]\\s*$", "");
        base = base.replaceAll("\\s*\\((?:KTV|MTV|MV|LIVE|伴奏|原唱|消音|卡拉OK|Official Video)\\)\\s*$", "");
        base = base.replaceAll("(?i)\\s*[-|]\\s*(KTV|MTV|MV|LIVE|伴奏|原唱|消音|卡拉OK)\\s*$", "");

        String normalized = base
                .replace('－', '-')
                .replace('—', '-')
                .replace('_', '-')
                .replace('–', '-')
                .replace('｜', '|')
                .replace('|', '-')
                .replace('/', '-')
                .replace('\\', '-');
        List<String> parts = splitParts(normalized);

        MetadataSuffix suffix = findMetadataSuffix(parts);
        if (suffix != null) {
            return parseStandard(parts, suffix, artistIndex, base.trim());
        }
        return parseLegacy(parts, rule, artistIndex, base.trim());
    }

    private static ParsedMeta parseStandard(List<String> parts, MetadataSuffix suffix,
                                             ArtistIndex artistIndex, String fallbackTitle) {
        List<String> identity = parts.subList(0, suffix.languageIndex());
        if (identity.stream().anyMatch(String::isBlank)) {
            return ParsedMeta.unrecognized(fallbackTitle);
        }

        ArtistMatch existingArtist = longestExistingArtist(identity, artistIndex);
        if (existingArtist != null) {
            String title = join(identity, existingArtist.partCount());
            return ParsedMeta.of(title, existingArtist.name(), suffix.language(), suffix.category());
        }

        // 两段剩余部分只有一个身份分隔点，兼容标准的「歌手-歌名」形式。
        // 三段以上若没有歌手库证据，不能安全猜测歌手/歌名边界。
        if (identity.size() == 2) {
            return ParsedMeta.of(identity.get(1), identity.get(0), suffix.language(), suffix.category());
        }
        return ParsedMeta.unrecognized(fallbackTitle);
    }

    private static ParsedMeta parseLegacy(List<String> parts, String rule,
                                          ArtistIndex artistIndex, String fallbackTitle) {
        if (parts.size() < 2 || parts.stream().anyMatch(String::isBlank)) {
            return ParsedMeta.unrecognized(fallbackTitle);
        }

        String left = parts.get(0);
        String right = join(parts, 1);
        if (left.matches("(?i)track|track\\s*\\d*") && right.matches("\\d{2,}")) {
            return ParsedMeta.unrecognized(fallbackTitle);
        }

        // 对旧格式中的多段歌名也尝试复用歌手库；未命中时继续保留原来的首分隔符行为。
        ArtistMatch existingArtist = longestExistingArtist(parts, artistIndex);
        if (existingArtist != null && existingArtist.partCount() < parts.size()
                && !"title_artist".equals(rule)) {
            return ParsedMeta.of(join(parts, existingArtist.partCount()), existingArtist.name());
        }

        return "title_artist".equals(rule)
                ? ParsedMeta.of(left, right)
                : ParsedMeta.of(right, left);
    }

    private static MetadataSuffix findMetadataSuffix(List<String> parts) {
        // 从右向左找最近的「已知语种-非空分类」组合。
        for (int languageIndex = parts.size() - 2; languageIndex >= 2; languageIndex--) {
            String language = canonicalLanguage(parts.get(languageIndex));
            String category = parts.get(languageIndex + 1);
            if (language != null && !category.isBlank()) {
                return new MetadataSuffix(languageIndex, language, category);
            }
        }
        return null;
    }

    private static ArtistMatch longestExistingArtist(List<String> parts, ArtistIndex artistIndex) {
        if (artistIndex == null || artistIndex.normalizedArtists().isEmpty()) return null;

        ArtistMatch best = null;
        for (int partCount = 1; partCount < parts.size(); partCount++) {
            String candidate = join(parts, 0, partCount);
            String matched = artistIndex.normalizedArtists().get(normalizeArtist(candidate));
            if (matched == null) continue;
            if (best == null || normalizeArtist(matched).length() > normalizeArtist(best.name()).length()) {
                best = new ArtistMatch(matched, partCount);
            }
        }
        return best;
    }

    private static String canonicalLanguage(String value) {
        if (value == null) return null;
        return LANGUAGE_ALIASES.get(value.trim().toLowerCase(Locale.ROOT));
    }

    private static String normalizeArtist(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static List<String> splitParts(String value) {
        String[] values = value.split("\\s*-\\s*", -1);
        List<String> result = new ArrayList<>(values.length);
        for (String item : values) result.add(cleanPart(item));
        return result;
    }

    private static String join(List<String> parts, int from) {
        return join(parts, from, parts.size());
    }

    private static String join(List<String> parts, int from, int to) {
        return String.join("-", parts.subList(from, to)).trim();
    }

    private static String cleanPart(String value) {
        return value.replaceAll("^\\s*[【\\[][^】\\]]+[】\\]]\\s*", "").trim();
    }

    static String stripExtension(String filename) {
        if (filename == null) return "";
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String name = slash >= 0 ? filename.substring(slash + 1) : filename;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private record MetadataSuffix(int languageIndex, String language, String category) {}

    private record ArtistMatch(String name, int partCount) {}

    static final class ArtistIndex {
        private final Map<String, String> normalizedArtists;

        private ArtistIndex(Map<String, String> normalizedArtists) {
            this.normalizedArtists = normalizedArtists;
        }

        Map<String, String> normalizedArtists() {
            return normalizedArtists;
        }
    }
}
