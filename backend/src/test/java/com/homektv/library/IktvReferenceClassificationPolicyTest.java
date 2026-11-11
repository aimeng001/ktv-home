package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IktvReferenceClassificationPolicyTest {
    @Test
    void allowsOnlyAuditedReferenceTagsAndMapsTheKnownVarietyAlias() {
        var reference = new IktvSongReferenceMatcher.ReferenceSong(
                1, "12345678", "歌名", "歌手", "MV", "国语", "流行", "热歌",
                List.of("抖音神曲", "梦之声", "未审计标签"), false);

        assertThat(IktvReferenceClassificationPolicy.categories(reference))
                .containsExactly("抖音神曲", "中国梦之声");
    }

    @Test
    void withholdsAllReferenceTagsForAConflictedTagIdentity() {
        var reference = new IktvSongReferenceMatcher.ReferenceSong(
                2, "12345679", "歌名", "歌手", "MV", "国语", "流行", "热歌",
                List.of("精选专区"), true);

        assertThat(IktvReferenceClassificationPolicy.categories(reference)).isEmpty();
    }

    @Test
    void doesNotInferClassificationFromLanguageTypeOrEdition() {
        var reference = new IktvSongReferenceMatcher.ReferenceSong(
                3, "12345680", "歌名", "歌手", "高清版", "粤语", "戏曲", "粤剧",
                List.of(), false);

        assertThat(IktvReferenceClassificationPolicy.categories(reference)).isEmpty();
    }

    @Test
    void classificationRelationIdentityIncludesReferenceSongId() {
        var oldReference = new IktvReferenceClassificationPolicy.CategoryAssignment(10, "抖音神曲");
        var newReference = new IktvReferenceClassificationPolicy.CategoryAssignment(11, "抖音神曲");

        assertThat(IktvReferenceClassificationPolicy.sameAssignments(
                List.of(oldReference), List.of(newReference))).isFalse();
        assertThat(IktvReferenceClassificationPolicy.sameAssignments(
                List.of(oldReference), List.of(oldReference))).isTrue();
        assertThat(IktvReferenceClassificationPolicy.sameAssignments(
                List.of(oldReference, newReference), List.of(newReference, oldReference))).isTrue();
    }
}
