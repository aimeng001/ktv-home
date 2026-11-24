package com.homektv.library;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses song metadata from media filenames.
 */
public final class FilenameParser {

    private static final String DEFAULT_RULE = "artist_title";
    private static final Pattern CATALOG_PREFIX = Pattern.compile("^\\s*([0-9]{5,10})\\s*[-._]\\s*");
    private static final Pattern LEADING_BRACKET_INDEX = Pattern.compile("^\\s*[（(【\\[]\\s*\\d{1,5}\\s*[)）】\\]]\\s*");
    private static final Pattern LEADING_NUMERIC_DOT = Pattern.compile("^\\s*\\d{1,5}\\s*[.、)】]\\s*");
    private static final Pattern LEADING_CATEGORY_BRACKET = Pattern.compile("^\\s*【[^】]+】\\s*");
    private static final Pattern LEADING_TECH_TAG = Pattern.compile("(?i)^\\s*\\[(?:\\d+|KTV|MTV|MV|LIVE|4K|1080P|HD|UHD|超清|高清|修复|无损|经典|新歌|儿歌|戏曲|民歌)[^\\]]*\\]\\s*");
    private static final Pattern TRAILING_SQUARE_TAG = Pattern.compile("(?i)\\s*\\[(?:KTV|MTV|MV|LIVE|伴奏|原唱|消音|卡拉OK|4K|1080P|HD|UHD|超清|高清|修复|无损)\\]\\s*$");
    private static final Pattern TRAILING_PAREN_TAG = Pattern.compile("(?i)\\s*\\((?:KTV|MTV|MV|LIVE|伴奏|原唱|消音|卡拉OK|Official Video|4K|1080P|HD|UHD|超清|高清|修复|无损)\\)\\s*$");
    private static final Pattern TRAILING_DASH_TAG = Pattern.compile("(?i)\\s*[-|]\\s*(KTV|MTV|MV|LIVE|伴奏|原唱|消音|卡拉OK|4K|1080P|HD|UHD)\\s*$");
    private static final Pattern TRACK_INDEX_SEGMENT = Pattern.compile("^\\d{1,8}$");

    private static final Map<String, String> LANGUAGE_ALIASES = Map.ofEntries(
            Map.entry("国语", "国语"),
            Map.entry("普通话", "国语"),
            Map.entry("mandarin", "国语"),
            Map.entry("zh", "国语"),
            Map.entry("粤语", "粤语"),
            Map.entry("广东话", "粤语"),
            Map.entry("cantonese", "粤语"),
            Map.entry("yue", "粤语"),
            Map.entry("闽南语", "闽南语"),
            Map.entry("闽南", "闽南语"),
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
            Map.entry("music", "纯音乐"),
            Map.entry("其他", "其他"),
            Map.entry("未知", "未知")
    );

    private static final Map<String, String> VOCAL_FORM_ALIASES = Map.of(
            "合唱", "合唱",
            "对唱", "对唱",
            "男女对唱", "对唱",
            "独唱", "独唱",
            "组合", "组合",
            "群星", "群星"
    );

    private FilenameParser() {}

    public static ParsedMeta parse(String filename) {
        return parse(filename, DEFAULT_RULE, List.of());
    }

    public static ParsedMeta parse(String filename, Collection<String> knownArtists) {
        return parse(filename, DEFAULT_RULE, prepareKnownArtists(knownArtists));
    }

    public static ParsedMeta parse(String filename, String rule) {
        return parse(filename, rule, List.of());
    }

    public static ParsedMeta parse(String filename, String rule, Collection<String> knownArtists) {
        return parse(filename, rule, prepareKnownArtists(knownArtists));
    }

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
        String safeName = filename == null ? "" : filename;
        Matcher catalogPrefix = CATALOG_PREFIX.matcher(safeName);
        String catalogNumber = "";
        String parseFilename = safeName;
        boolean found = catalogPrefix.find();
        if (found) {
            String candidate = catalogPrefix.group(1);
            String remaining = safeName.substring(catalogPrefix.end());
            String remainingBase = stripExtension(remaining).trim();
            // 多段守卫：只有当剥离前缀后，剩余部分仍包含有效分隔符（即能至少解析出两段以上）时，才剥离前缀
            if (remainingBase.contains("-") || remainingBase.contains("_") || remainingBase.contains("——")) {
                if (isTrustedCatalogPrefix(candidate)) catalogNumber = candidate;
                parseFilename = remaining;
            }
        }
        return parseWithoutCatalog(parseFilename, rule, artistIndex).withCatalogNumber(catalogNumber);
    }

    private static boolean isTrustedCatalogPrefix(String candidate) {
        if (candidate == null) return false;
        if (candidate.length() >= 5 && candidate.length() <= 7) {
            return true;
        }
        if (candidate.length() != 8) return false;
        int year = Integer.parseInt(candidate.substring(0, 4));
        if (year < 1900 || year > 2100) return true;
        try {
            LocalDate.of(year, Integer.parseInt(candidate.substring(4, 6)),
                    Integer.parseInt(candidate.substring(6, 8)));
            return false;
        } catch (DateTimeException invalidDate) {
            return true;
        }
    }

    private static ParsedMeta parseWithoutCatalog(String filename, String rule, ArtistIndex artistIndex) {
        String base = stripExtension(filename).trim();
        if (base.isBlank()) return ParsedMeta.unrecognized(base);

        // Strip catalogue numbers, category brackets, and transport/version markers before identifying fields.
        base = LEADING_BRACKET_INDEX.matcher(base).replaceFirst("");
        base = LEADING_NUMERIC_DOT.matcher(base).replaceFirst("");
        base = LEADING_CATEGORY_BRACKET.matcher(base).replaceFirst("");
        base = LEADING_TECH_TAG.matcher(base).replaceFirst("");
        base = TRAILING_SQUARE_TAG.matcher(base).replaceAll("");
        base = TRAILING_PAREN_TAG.matcher(base).replaceAll("");
        base = TRAILING_DASH_TAG.matcher(base).replaceAll("");

        String dashSeparated = normalizeDashSeparators(base);
        List<String> dashParts = splitParts(dashSeparated);
        MetadataSuffix dashSuffix = findMetadataSuffix(dashParts);
        if (dashSuffix != null) {
            ParsedMeta standard = parseStandard(dashParts, dashSuffix, artistIndex, base.trim());
            if (standard.recognized() || hasCollaborativeArtistMarker(dashParts) || standard.needsReview()) {
                return standard;
            }
        }

        String normalized = dashSeparated.replace('_', '-');
        List<String> parts = splitParts(normalized);

        MetadataSuffix suffix = findMetadataSuffix(parts);
        if (suffix != null) {
            return parseStandard(parts, suffix, artistIndex, base.trim());
        }
        return parseLegacy(parts, rule, artistIndex, base.trim());
    }

    private static String normalizeDashSeparators(String value) {
        String s = value;
        if (s.contains("——")) s = s.replace("——", "\uE000");
        s = s
                .replace('－', '-')
                .replace('–', '-')
                .replace('—', '-')
                .replace('｜', '|')
                .replace('|', '-')
                .replace('/', '-')
                .replace('\\', '-');
        return s.replace("\uE000", "——");
    }

    private static boolean hasCollaborativeArtistMarker(List<String> parts) {
        return !parts.isEmpty() && parts.get(0).indexOf('_') >= 0;
    }

    private static ParsedMeta parseStandard(List<String> parts, MetadataSuffix suffix,
                                             ArtistIndex artistIndex, String fallbackTitle) {
        List<String> identity = new ArrayList<>(parts.subList(0, suffix.languageIndex()));
        if (identity.stream().anyMatch(String::isBlank)) {
            return ParsedMeta.unrecognized(fallbackTitle);
        }

        // 剥离可能存在的多余前置语种（例如："国语-苏晨-坚强-国语-流行"）
        if (identity.size() >= 3 && canonicalLanguage(identity.get(0)) != null) {
            identity.remove(0);
        }

        // 剥离可能存在的曲目序号前缀（例如："01-周杰伦-晴天-国语-流行"，此时 identity 有 3 段以上）
        if (identity.size() >= 3 && TRACK_INDEX_SEGMENT.matcher(identity.get(0)).matches()) {
            identity.remove(0);
        }

        // 提取可能存在的演唱形式（例如："张庭 钟丽缇 王祖蓝-爱上幼儿园-合唱-国语-流行"）
        String vocalForm = "";
        if (identity.size() >= 3) {
            String candidateVocal = VOCAL_FORM_ALIASES.get(identity.get(identity.size() - 1));
            if (candidateVocal != null) {
                vocalForm = candidateVocal;
                identity.remove(identity.size() - 1);
            }
        }

        ArtistMatch existingArtist = longestExistingArtist(identity, artistIndex);
        if (existingArtist != null && existingArtist.partCount() < identity.size()) {
            String title = join(identity, existingArtist.partCount());
            return ParsedMeta.of(title, existingArtist.name(), suffix.language(), suffix.category(), vocalForm);
        }

        if (identity.size() == 2) {
            return ParsedMeta.of(identity.get(1), identity.get(0), suffix.language(), suffix.category(), vocalForm);
        }
        return ParsedMeta.unrecognized(fallbackTitle);
    }

    private static ParsedMeta parseLegacy(List<String> parts, String rule,
                                          ArtistIndex artistIndex, String fallbackTitle) {
        if (parts.size() < 2 || parts.stream().anyMatch(String::isBlank)) {
            return ParsedMeta.unrecognized(fallbackTitle);
        }

        List<String> effectiveParts = new ArrayList<>(parts);
        if (effectiveParts.size() >= 3 && effectiveParts.get(0).matches("^\\d{1,8}$")) {
            effectiveParts.remove(0);
        }

        String left = effectiveParts.get(0);
        String right = join(effectiveParts, 1);
        if (left.matches("(?i)track|track\\s*\\d*") && right.matches("\\d{2,}")) {
            return ParsedMeta.unrecognized(fallbackTitle);
        }

        ArtistMatch existingArtist = longestExistingArtist(effectiveParts, artistIndex);
        if (existingArtist != null && existingArtist.partCount() < effectiveParts.size()
                && !"title_artist".equals(rule)) {
            return ParsedMeta.of(join(effectiveParts, existingArtist.partCount()), existingArtist.name());
        }

        return "title_artist".equals(rule)
                ? ParsedMeta.of(left, right)
                : ParsedMeta.of(right, left);
    }

    private static MetadataSuffix findMetadataSuffix(List<String> parts) {
        for (int languageIndex = parts.size() - 2; languageIndex >= 1; languageIndex--) {
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
        for (String item : values) {
            String cleaned = cleanPart(item);
            if (!cleaned.isBlank()) {
                result.add(cleaned);
            }
        }
        return result;
    }

    private static String join(List<String> parts, int from) {
        return join(parts, from, parts.size());
    }

    private static String join(List<String> parts, int from, int to) {
        return String.join("-", parts.subList(from, to)).trim();
    }

    private static String cleanPart(String value) {
        String cleaned = value.replaceFirst("^\\s*【[^】]+】\\s*", "");
        cleaned = cleaned.replaceAll("(?i)\\s*\\[(?:4K|1080P|HD|UHD|超清|高清|修复|无损|Live版|Live|Official Video)[^\\]]*\\]\\s*$", "");
        return cleaned.trim();
    }

    static String stripExtension(String filename) {
        if (filename == null) return "";
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String name = slash >= 0 ? filename.substring(slash + 1) : filename;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static String inferDeterministicLanguage(String title, String artist) {
        String text = (title == null ? "" : title) + " " + (artist == null ? "" : artist);
        // 1. 日语假名检测：平假名 \u3040-\u309F，片假名 \u30A0-\u30FF
        if (text.chars().anyMatch(c -> (c >= 0x3040 && c <= 0x30FF))) {
            return "日语";
        }
        // 2. 韩文音节检测：\uAC00-\uD7AF, 字母 \u1100-\u11FF
        if (text.chars().anyMatch(c -> (c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x1100 && c <= 0x11FF))) {
            return "韩语";
        }
        // 3. 若有英文字母且全为 ASCII/拉丁字符（无任何中日韩汉字），确定性推导为英语
        if (text.chars().anyMatch(Character::isLetter)
                && text.chars().allMatch(c -> c < 128 || Character.isWhitespace(c))) {
            return "英语";
        }
        return "";
    }

    public static String inferLanguage(String title, String artist) {
        String text = (title == null ? "" : title) + " " + (artist == null ? "" : artist);
        // 1. 日语假名检测：平假名 \u3040-\u309F，片假名 \u30A0-\u30FF
        if (text.chars().anyMatch(c -> (c >= 0x3040 && c <= 0x30FF))) {
            return "日语";
        }
        // 2. 韩文音节检测：\uAC00-\uD7AF, 字母 \u1100-\u11FF
        if (text.chars().anyMatch(c -> (c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x1100 && c <= 0x11FF))) {
            return "韩语";
        }
        // 3. 中文字符检测：\u4E00-\u9FA5
        if (text.chars().anyMatch(c -> c >= 0x4E00 && c <= 0x9FA5)) {
            return "国语";
        }
        // 4. 若有英文字母且全为 ASCII/拉丁字符，推导为英语
        if (text.chars().anyMatch(Character::isLetter) && text.chars().allMatch(c -> c < 128 || Character.isWhitespace(c))) {
            return "英语";
        }
        return "国语";
    }

    public static String inferCategory(String title, String artist) {
        String text = (title == null ? "" : title) + " " + (artist == null ? "" : artist);
        if (text.contains("儿歌") || text.contains("少儿") || text.contains("童谣")) {
            return "儿歌";
        }
        if (text.contains("戏曲") || text.contains("京剧") || text.contains("豫剧") ||
            text.contains("越剧") || text.contains("黄梅戏") || text.contains("评剧")) {
            return "戏曲";
        }
        if (text.contains("军旅") || text.contains("红歌") || text.contains("革命")) {
            return "红歌";
        }
        if (text.contains("抖音") || text.contains("神曲")) {
            return "抖音";
        }
        return "";
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
