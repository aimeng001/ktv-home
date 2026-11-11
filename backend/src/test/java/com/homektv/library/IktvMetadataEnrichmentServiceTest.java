package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IktvMetadataEnrichmentServiceTest {
    @Test
    void exposesOnlyAuditedTagsFromAnAlreadyMatchedReferenceSong() {
        IktvMetadataEnrichmentService service = new IktvMetadataEnrichmentService();
        var matched = new IktvSongReferenceMatcher.ReferenceSong(
                1, "12345678", "歌曲", "歌手", "MV", "国语", "流行", "热歌",
                List.of("抖音神曲", "未审计标签"), false);

        assertThat(service.getCategoriesForMatchedReference(matched)).containsExactly("抖音神曲");
        assertThat(service.getCategoriesForMatchedReference(null)).isEmpty();
    }

    @Test
    void neverReturnsCategoriesForConflictedReferenceTags() {
        IktvMetadataEnrichmentService service = new IktvMetadataEnrichmentService();
        var conflicted = new IktvSongReferenceMatcher.ReferenceSong(
                2, "12345679", "歌曲", "歌手", "MV", "国语", "流行", "热歌",
                List.of("精选专区"), true);

        assertThat(service.getCategoriesForMatchedReference(conflicted)).isEmpty();
    }
}
