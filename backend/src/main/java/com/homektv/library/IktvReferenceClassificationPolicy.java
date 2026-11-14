package com.homektv.library;

import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact allowlist for reference-provided categories; no language/type inference. */
public final class IktvReferenceClassificationPolicy {
    private static final Set<String> AUDITED_TAGS = Set.of(
            "70后歌曲", "80后歌曲", "90后歌曲", "中国好声音", "中国好歌曲", "中国最强音", "二人转",
            "京剧", "佛歌", "儿歌", "全能星战", "合唱", "喜庆", "女生KTV推荐", "小品", "影视",
            "怀旧", "戏曲", "我为歌狂", "我是歌手", "抖音神曲", "新歌", "无损DISCO", "无损音乐",
            "昆剧", "最美和声", "梦之声", "民歌", "沪剧", "热歌", "男女经典对唱", "百度经典排行",
            "秦腔", "粤剧", "精选专区", "网络歌曲推荐", "舞曲", "花鼓戏豫剧", "评剧", "豫剧",
            "越剧", "迪高", "锡剧", "闽剧", "革命", "高清HD歌曲", "黄梅戏");
    private static final Map<String, String> EXPLICIT_UI_LABELS = Map.of("梦之声", "中国梦之声");

    private IktvReferenceClassificationPolicy() {}

    public static List<String> categories(IktvSongReferenceMatcher.ReferenceSong reference) {
        return assignments(reference).stream().map(CategoryAssignment::category).toList();
    }

    public static List<CategoryAssignment> assignments(
            IktvSongReferenceMatcher.ReferenceSong reference) {
        if (reference == null || reference.specialTagConflict()) return List.of();
        Set<String> categories = new LinkedHashSet<>();
        for (String tag : reference.specialTags()) {
            if (AUDITED_TAGS.contains(tag)) categories.add(EXPLICIT_UI_LABELS.getOrDefault(tag, tag));
        }
        return categories.stream().map(category -> new CategoryAssignment(
                reference.referenceSongId(), category)).toList();
    }

    static boolean sameAssignments(Collection<CategoryAssignment> existing,
                                   Collection<CategoryAssignment> desired) {
        return existing.size() == desired.size()
                && new HashSet<>(existing).equals(new HashSet<>(desired));
    }

    public record CategoryAssignment(long referenceSongId, String category) {}
}
