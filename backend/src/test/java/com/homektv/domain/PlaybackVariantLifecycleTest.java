package com.homektv.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackVariantLifecycleTest {

    @Test
    void prePersistInitializesMissingCreationTimeAndPreservesAnExistingOne() throws Exception {
        PlaybackVariant variant = new PlaybackVariant(42L, "source-v1", PlaybackVariantProfile.H264_AAC_MP4_V1);

        variant.onCreate();

        OffsetDateTime createdAt = field(variant, "createdAt");
        assertThat(createdAt).isNotNull();
        assertThat(field(variant, "updatedAt")).isEqualTo(createdAt);

        OffsetDateTime importedCreationTime = OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        setField(variant, "createdAt", importedCreationTime);
        variant.onCreate();

        assertThat(field(variant, "createdAt")).isEqualTo(importedCreationTime);
        assertThat(field(variant, "updatedAt")).isAfter(importedCreationTime);
    }

    @Test
    void preUpdateRefreshesTheModificationTime() throws Exception {
        PlaybackVariant variant = new PlaybackVariant(42L, "source-v1", PlaybackVariantProfile.H264_AAC_MP4_V1);
        OffsetDateTime oldUpdateTime = OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        setField(variant, "updatedAt", oldUpdateTime);

        variant.onUpdate();

        assertThat(field(variant, "updatedAt")).isAfter(oldUpdateTime);
    }

    private static OffsetDateTime field(PlaybackVariant variant, String name) throws Exception {
        Field field = PlaybackVariant.class.getDeclaredField(name);
        field.setAccessible(true);
        return (OffsetDateTime) field.get(variant);
    }

    private static void setField(PlaybackVariant variant, String name, OffsetDateTime value) throws Exception {
        Field field = PlaybackVariant.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(variant, value);
    }
}
