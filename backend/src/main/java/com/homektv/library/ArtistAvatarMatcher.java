package com.homektv.library;

import com.homektv.musicsource.ExternalArtist;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Matches an external singer result to a local identity before downloading an avatar. */
public final class ArtistAvatarMatcher {
    private static final double VARIANT_CONFIDENCE = 0.97d;
    private static final double ALIAS_CONFIDENCE = 0.95d;
    private static final Pattern BRACKET_SEGMENT = Pattern.compile("[\\(（\\[【]([^\\)）\\]】]{1,80})[\\)）\\]】]");

    // High-frequency Traditional -> Simplified mappings for CJK singer names (strictly 1:1 indexed).
    private static final String TRAD_CHARS =
            "鄧學國榮傑華張劉陳楊許蘇蕭譚鳳龍麗輝慶偉葉齊鍾盧羅鄭謝關黃趙孫歐陽蔣馬馮藍韓韋莊嚴麥樂團組寶儀韻凱東雲風飛愛夢亞靜詩語萬與無雙兒溫澤濤淵漢紅經純維緣勝達遠進選銀銘鎮鐵門閃開陸隨隱電靈響駿鴻鶴鷹點齋倫吳呂顏潔義禮誠剛強軍濱藝聲粵臺灣來頭餘範龐龔鄒湯衛紀書喬連饒錢邊顧賴閻賀賈";
    private static final String SIMP_CHARS =
            "邓学国荣杰华张刘陈杨许苏萧谭凤龙丽辉庆伟叶齐钟卢罗郑谢关黄赵孙欧阳蒋马冯蓝韩韦庄严麦乐团组宝仪韵凯东云风飞爱梦亚静诗语万与无双儿温泽涛渊汉红经纯维缘胜达远进选银铭镇铁门闪开陆随隐电灵响骏鸿鹤鹰点斋伦吴吕颜洁义礼诚刚强军滨艺声粤台湾来头余范庞龚邹汤卫纪书乔连饶钱边顾赖阎贺贾";
    private static final Map<Character, Character> TRAD_TO_SIMP = buildTradMap();

    private ArtistAvatarMatcher() {}

    public static Optional<Match> bestMatch(String expectedName, Collection<ExternalArtist> candidates) {
        String expectedExact = ArtistCreditParser.key(expectedName);
        if (expectedExact.isBlank() || candidates == null) return Optional.empty();
        Set<String> expectedVariants = nameVariants(expectedName);

        Match best = null;
        for (ExternalArtist candidate : candidates) {
            if (candidate == null || candidate.displayName() == null) continue;
            double confidence = scoreCandidate(expectedExact, expectedVariants, candidate);
            if (confidence < 0.90d) continue;
            Match current = new Match(candidate, confidence);
            if (best == null || current.confidence() > best.confidence()) best = current;
        }
        return Optional.ofNullable(best);
    }

    /** Produces normalized search terms (e.g. stripping bracket tags) for external lookup queries. */
    public static String cleanLookupName(String artistName) {
        if (artistName == null) return "";
        String stripped = BRACKET_SEGMENT.matcher(artistName).replaceAll(" ").replaceAll("\\s+", " ").trim();
        return stripped.isBlank() ? artistName.trim() : stripped;
    }

    private static double scoreCandidate(String expectedExact, Set<String> expectedVariants, ExternalArtist candidate) {
        String candidateExact = ArtistCreditParser.key(candidate.displayName());
        if (expectedExact.equals(candidateExact)) return 1.0d;

        Set<String> candidateVariants = nameVariants(candidate.displayName());
        for (String variant : candidateVariants) {
            if (expectedVariants.contains(variant)) return VARIANT_CONFIDENCE;
        }

        if (candidate.aliases() != null) {
            for (String alias : candidate.aliases()) {
                if (alias == null || alias.isBlank()) continue;
                String aliasExact = ArtistCreditParser.key(alias);
                if (expectedExact.equals(aliasExact)) return ALIAS_CONFIDENCE;
                for (String aliasVariant : nameVariants(alias)) {
                    if (expectedVariants.contains(aliasVariant)) return ALIAS_CONFIDENCE;
                }
            }
        }
        return 0d;
    }

    static Set<String> nameVariants(String rawName) {
        Set<String> variants = new LinkedHashSet<>();
        if (rawName == null || rawName.isBlank()) return variants;
        addNormalized(variants, rawName);

        String withoutBrackets = BRACKET_SEGMENT.matcher(rawName).replaceAll(" ").trim();
        addNormalized(variants, withoutBrackets);

        Matcher matcher = BRACKET_SEGMENT.matcher(rawName);
        while (matcher.find()) {
            String inner = matcher.group(1);
            if (inner != null && !isNonNameDescriptor(inner)) {
                addNormalized(variants, inner);
            }
        }
        return variants;
    }

    private static void addNormalized(Set<String> out, String value) {
        if (value == null || value.isBlank()) return;
        String exact = ArtistCreditParser.key(value);
        if (!exact.isBlank()) out.add(exact);
        String simplified = toSimplified(value);
        String simpKey = ArtistCreditParser.key(simplified);
        if (!simpKey.isBlank()) out.add(simpKey);
        String alnum = simpKey.replaceAll("[·•.'’\\-_]", "");
        if (!alnum.isBlank()) out.add(alnum);
    }

    private static boolean isNonNameDescriptor(String text) {
        String key = ArtistCreditParser.key(toSimplified(text));
        return List.of("男", "女", "组合", "乐队", "群星", "singer", "band").contains(key);
    }

    static String toSimplified(String input) {
        if (input == null || input.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            sb.append(TRAD_TO_SIMP.getOrDefault(ch, ch));
        }
        return sb.toString();
    }

    private static Map<Character, Character> buildTradMap() {
        java.util.HashMap<Character, Character> map = new java.util.HashMap<>();
        int len = Math.min(TRAD_CHARS.length(), SIMP_CHARS.length());
        for (int i = 0; i < len; i++) {
            map.put(TRAD_CHARS.charAt(i), SIMP_CHARS.charAt(i));
        }
        return Map.copyOf(map);
    }

    public record Match(ExternalArtist artist, double confidence) {}
}
