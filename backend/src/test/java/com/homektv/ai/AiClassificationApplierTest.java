package com.homektv.ai;

import com.homektv.domain.Song;
import com.homektv.library.ArtistCreditService;
import com.homektv.library.ArtistDirectoryProjectionService;
import com.homektv.library.CatalogRevisionService;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiClassificationApplierTest {
    @Test
    void aiDoesNotOverwriteLockedTags() {
        Song song = songWithTags("人工分类");
        song.lockMetadata("tags");
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(song));
        when(repository.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());

        new AiClassificationApplier(repository, config).apply(1L, resultWithTags("AI分类"));

        assertThat(song.getTags()).containsExactly("人工分类");
    }

    @Test
    void aiStillMergesTagsWhenTheyAreNotLocked() {
        Song song = songWithTags("已有分类");
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(song));
        when(repository.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());

        new AiClassificationApplier(repository, config).apply(1L, resultWithTags("AI分类"));

        assertThat(song.getTags()).contains("已有分类", "AI分类", "00年代", "独唱");
    }

    @Test
    void aiIdentityEditRefreshesIndependentArtistCredits() {
        Song song = songWithTags("已有分类");
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(song));
        when(repository.findByFingerprint(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        when(repository.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());
        ArtistCreditService credits = mock(ArtistCreditService.class);

        new AiClassificationApplier(repository, config, credits).apply(1L,
                new AiSongClassification("新歌名", "单依纯_王子异", "国语", "00年代", List.of(), List.of(),
                        "全年龄", "合唱", List.of(), "test", 0.99, 0.99, 0.99, 0.99, 0.99,
                        "未知", java.util.Map.of()));

        verify(credits).replace(1L, "单依纯_王子异");
    }

    @Test
    void autoApplyUsesIdentityOnlyForProvisionalSongsWithConfidentEvidence() {
        Song song = songWithTags();
        song.setArtist("未知歌手");
        song.setStatus("unrecognized");
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(song));
        when(repository.findByFingerprint(any())).thenReturn(Optional.empty());
        when(repository.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());
        AiSongClassification result = classification("修正歌名", "新歌手", 0.99, 0.99, 0.99);

        boolean changed = new AiClassificationApplier(repository, config).applyAuto(1L, result);

        assertThat(changed).isTrue();
        assertThat(song.getTitle()).isEqualTo("修正歌名");
        assertThat(song.getArtist()).isEqualTo("新歌手");
        verify(repository).save(song);
    }

    @Test
    void autoApplyDoesNotReplaceIdentityOfAnEstablishedSong() {
        Song song = songWithTags();
        song.setStatus("ok");
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(song));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());

        boolean changed = new AiClassificationApplier(repository, config).applyAuto(1L,
                classification("unverified-title", "unverified-artist", 0.99, 0.99, 0.10));

        assertThat(changed).isFalse();
        assertThat(song.getTitle()).isEqualTo("歌名");
        assertThat(song.getArtist()).isEqualTo("歌手");
        verify(repository, never()).save(any(Song.class));
    }

    @Test
    void autoApplyDoesNotChangeIdentityWhenBothFieldsAreLocked() {
        Song song = songWithTags();
        song.setArtist("未知歌手");
        song.lockMetadata("title");
        song.lockMetadata("artist");
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(song));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());

        boolean changed = new AiClassificationApplier(repository, config).applyAuto(1L,
                classification("修正歌名", "新歌手", 0.99, 0.99, 0.10));

        assertThat(changed).isFalse();
        assertThat(song.getTitle()).isEqualTo("歌名");
        assertThat(song.getArtist()).isEqualTo("未知歌手");
        verify(repository, never()).save(any(Song.class));
    }

    @Test
    void reviewedApplyCanAcceptLowConfidenceIdentityAndReportsDuplicates() {
        Song song = songWithTags();
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(song));
        when(repository.findByFingerprint(any())).thenReturn(Optional.empty());
        when(repository.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());

        new AiClassificationApplier(repository, config).apply(1L,
                classification("审核通过歌名", "审核通过歌手", 0.20, 0.20, 0.20));

        assertThat(song.getTitle()).isEqualTo("审核通过歌名");
        assertThat(song.getArtist()).isEqualTo("审核通过歌手");

        Song duplicate = new Song();
        duplicate.setId(2L);
        when(repository.findByFingerprint(any())).thenReturn(Optional.of(duplicate));
        assertThatThrownBy(() -> new AiClassificationApplier(repository, config).apply(1L,
                classification("重复歌名", "重复歌手", 0.20, 0.20, 0.20)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("AI_IDENTITY_CONFLICT"));
    }

    @Test
    void autoApplyAcceptsIdentityThatCorrectsSwappedTitleAndArtist() {
        Song song = songWithTags();
        song.setTitle("歌手");
        song.setArtist("歌名");
        song.setStatus("ok");
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(song));
        when(repository.findByFingerprint(any())).thenReturn(Optional.empty());
        when(repository.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());

        boolean changed = new AiClassificationApplier(repository, config).applyAuto(1L,
                classification("歌名", "歌手", 0.99, 0.10, 0.99));

        assertThat(changed).isTrue();
        assertThat(song.getTitle()).isEqualTo("歌名");
        assertThat(song.getArtist()).isEqualTo("歌手");
        verify(repository).save(song);
    }

    @Test
    void autoApplyRejectsPartiallySwappedIdentity() {
        Song song = songWithTags();
        song.setStatus("ok");
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(song));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());

        boolean changed = new AiClassificationApplier(repository, config).applyAuto(1L,
                identity("歌手", "其他歌手", 0.99, 0.99));

        assertThat(changed).isFalse();
        assertThat(song.getTitle()).isEqualTo("歌名");
        assertThat(song.getArtist()).isEqualTo("歌手");
        verify(repository, never()).findByFingerprint(any());
        verify(repository, never()).save(any(Song.class));
    }

    @Test
    void reviewedIdentityMayResolveToTheSameSongFingerprint() {
        Song song = songWithTags();
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(song));
        when(repository.findByFingerprint(any())).thenReturn(Optional.of(song));
        when(repository.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());

        Song result = new AiClassificationApplier(repository, config).apply(1L,
                classification("歌名", "歌手", 0.99, 0.99, 0.99));

        assertThat(result).isSameAs(song);
        assertThat(song.getFingerprint()).isNotBlank();
        verify(repository).findByFingerprint(any());
        verify(repository).save(song);
    }

    @Test
    void autoApplyUsesConfiguredThresholdForLanguageVocalFormAndGender() {
        Song accepted = songWithTags();
        accepted.setStatus("ok");
        SongRepository acceptedRepository = mock(SongRepository.class);
        when(acceptedRepository.findById(1L)).thenReturn(Optional.of(accepted));
        when(acceptedRepository.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());
        AiClassificationApplier applier = new AiClassificationApplier(acceptedRepository, config);

        boolean acceptedChange = applier.applyAuto(1L,
                metadataClassification("国语", "独唱", "女歌手", 0.92, 0.92, 0.92));

        assertThat(acceptedChange).isTrue();
        assertThat(accepted.getLanguage()).isEqualTo("国语");
        assertThat(accepted.getVocalForm()).isEqualTo("独唱");
        assertThat(accepted.getArtistGender()).isEqualTo("女歌手");

        Song rejected = songWithTags();
        rejected.setStatus("ok");
        SongRepository rejectedRepository = mock(SongRepository.class);
        when(rejectedRepository.findById(1L)).thenReturn(Optional.of(rejected));
        AiClassificationApplier rejectingApplier = new AiClassificationApplier(rejectedRepository, config);

        boolean rejectedChange = rejectingApplier.applyAuto(1L,
                metadataClassification("国语", "独唱", "女歌手", 0.919, 0.919, 0.919));

        assertThat(rejectedChange).isFalse();
        assertThat(rejected.getLanguage()).isEqualTo("未知");
        assertThat(rejected.getVocalForm()).isEqualTo("未知");
        assertThat(rejected.getArtistGender()).isEqualTo("未知");
        verify(rejectedRepository, never()).save(any(Song.class));
    }

    @Test
    void normalizesUnsupportedValuesAndRemovesBlankDuplicateAndUnknownTags() {
        Song song = songWithTags("保留", " ", "未知", null);
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(song));
        when(repository.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());
        AiSongClassification result = new AiSongClassification(null, null, "火星语", "00年代",
                List.of(" 流行 ", "流行", " "), List.of("爱情", "爱情"), " ", " ",
                List.of(), "test", 0.99, 0.99, 0.99, 0.99, 0.99, "火星人", java.util.Map.of());

        new AiClassificationApplier(repository, config).apply(1L, result);

        assertThat(song.getAiLanguage()).isEqualTo("未知");
        assertThat(song.getAiEra()).isEqualTo("00年代");
        assertThat(song.getAiGenres()).containsExactly("流行");
        assertThat(song.getAiThemes()).containsExactly("爱情");
        assertThat(song.getAiAgeRange()).isEqualTo("未知");
        assertThat(song.getAiVocalForm()).isEqualTo("未知");
        assertThat(song.getTags()).containsExactly("保留", "流行", "爱情", "00年代")
                .doesNotContain("未知", " ", null);
        assertThat(song.getArtistGender()).isEqualTo("未知");
    }

    @Test
    void applyAutoRejectsMissingSongId() {
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.empty());
        AiConfigService config = mock(AiConfigService.class);

        assertThatThrownBy(() -> new AiClassificationApplier(repository, config)
                .applyAuto(1L, resultWithTags("AI分类")))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("SONG_NOT_FOUND"));
    }

    @Test
    void identityApplicationRequiresBothNonblankNamesAndConfidentEvidence() {
        List<AiSongClassification> rejected = List.of(
                identity(null, "新歌手", 0.99, 0.99),
                identity(" \t ", "新歌手", 0.99, 0.99),
                identity("新歌名", null, 0.99, 0.99),
                identity("新歌名", " \t ", 0.99, 0.99),
                identity("新歌名", "新歌手", 0.91, 0.99),
                identity("新歌名", "新歌手", 0.99, 0.91));

        for (AiSongClassification result : rejected) {
            Song song = songWithTags();
            song.setStatus("ok");
            SongRepository repository = mock(SongRepository.class);
            when(repository.findById(1L)).thenReturn(Optional.of(song));
            AiConfigService config = mock(AiConfigService.class);
            when(config.resolve()).thenReturn(defaultConfig());

            assertThat(new AiClassificationApplier(repository, config).applyAuto(1L, result)).isFalse();
            verify(repository, never()).save(any(Song.class));
        }
    }

    @Test
    void provisionalIdentityCanBeConfirmedByEitherUnknownArtistOrUnrecognizedStatus() {
        for (boolean unknownArtist : List.of(true, false)) {
            Song song = songWithTags();
            song.setArtist(unknownArtist ? "未知歌手" : "已知歌手");
            song.setStatus(unknownArtist ? "ok" : "unrecognized");
            SongRepository repository = mock(SongRepository.class);
            when(repository.findById(1L)).thenReturn(Optional.of(song));
            when(repository.findByFingerprint(any())).thenReturn(Optional.empty());
            when(repository.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
            AiConfigService config = mock(AiConfigService.class);
            when(config.resolve()).thenReturn(defaultConfig());

            assertThat(new AiClassificationApplier(repository, config)
                    .applyAuto(1L, identity("新歌名", "新歌手", 0.99, 0.99))).isTrue();
            assertThat(song.getTitle()).isEqualTo("新歌名");
            assertThat(song.getArtist()).isEqualTo("新歌手");
        }
    }

    @Test
    void autoApplyProtectsEachLockedIdentityAndClassificationFieldIndependently() {
        for (boolean lockTitle : List.of(true, false)) {
            Song song = songWithTags("人工标签");
            song.setArtist("未知歌手");
            song.lockMetadata("tags");
            song.lockMetadata("artistGender");
            song.lockMetadata("language");
            song.lockMetadata("vocalForm");
            song.lockMetadata(lockTitle ? "title" : "artist");
            SongRepository repository = mock(SongRepository.class);
            when(repository.findById(1L)).thenReturn(Optional.of(song));
            when(repository.findByFingerprint(any())).thenReturn(Optional.empty());
            when(repository.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
            AiConfigService config = mock(AiConfigService.class);
            when(config.resolve()).thenReturn(defaultConfig());

            assertThat(new AiClassificationApplier(repository, config).applyAuto(
                    1L, identity("新歌名", "新歌手", 0.99, 0.99))).isTrue();
            assertThat(song.getTitle()).isEqualTo(lockTitle ? "歌名" : "新歌名");
            assertThat(song.getArtist()).isEqualTo(lockTitle ? "新歌手" : "未知歌手");
            assertThat(song.getTags()).containsExactly("人工标签");
            assertThat(song.getArtistGender()).isEqualTo("未知");
            assertThat(song.getLanguage()).isEqualTo("未知");
            assertThat(song.getVocalForm()).isEqualTo("未知");
        }
    }

    @Test
    void autoApplyDoesNotReplaceArtistCreditsWhenIdentityKeepsTheSameArtist() {
        Song song = songWithTags();
        song.setStatus("unrecognized");
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(song));
        when(repository.findByFingerprint(any())).thenReturn(Optional.empty());
        when(repository.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());
        ArtistCreditService credits = mock(ArtistCreditService.class);

        assertThat(new AiClassificationApplier(repository, config, credits)
                .applyAuto(1L, identity("新歌名", "歌手", 0.99, 0.99))).isTrue();

        verify(credits, never()).replace(1L, "歌手");
    }

    @Test
    void reviewedApplyRefreshesCatalogRevisionAndDirectoryProjection() {
        Song song = songWithTags();
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(song));
        when(repository.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());
        CatalogRevisionService revision = mock(CatalogRevisionService.class);
        ArtistDirectoryProjectionService projection = mock(ArtistDirectoryProjectionService.class);
        AiClassificationApplier applier = new AiClassificationApplier(repository, config, null, projection);
        applier.setCatalogRevisionService(revision);

        applier.apply(1L, resultWithTags("AI分类"));

        verify(revision).bumpIfChanged(true);
        verify(projection).requestRefreshAfterCommit();
    }

    @Test
    void reviewedApplyMergesTagsWhenTheExistingArrayIsNull() {
        Song song = songWithTags();
        song.setTags(null);
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(song));
        when(repository.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());

        new AiClassificationApplier(repository, config).apply(1L, resultWithTags("AI分类"));

        assertThat(song.getTags()).contains("AI分类", "00年代", "独唱");
    }

    @Test
    void reviewedApplyRejectsMissingSongId() {
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new AiClassificationApplier(repository, mock(AiConfigService.class))
                .apply(1L, resultWithTags("AI分类")))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("SONG_NOT_FOUND"));
    }

    private static Song songWithTags(String... tags) {
        Song song = new Song();
        song.setId(1L);
        song.setTitle("歌名");
        song.setArtist("歌手");
        song.setMediaType("AUDIO");
        song.setFingerprint("fp-ai-test");
        song.setTags(tags);
        return song;
    }

    private static AiSongClassification resultWithTags(String tag) {
        return new AiSongClassification("国语", "00年代", List.of(tag), List.of(),
                "全年龄", "独唱", List.of(), "test", 0.99);
    }

    private static AiSongClassification classification(String title, String artist,
                                                        double identityConfidence, double metadataConfidence,
                                                        double confidence) {
        return new AiSongClassification(title, artist, null, null, List.of(), List.of(), null, null,
                List.of(), "test", confidence, identityConfidence, identityConfidence,
                metadataConfidence, metadataConfidence, "未知", java.util.Map.of());
    }

    private static AiSongClassification metadataClassification(String language, String vocalForm,
                                                                String artistGender, double confidence,
                                                                double languageConfidence, double vocalConfidence) {
        return new AiSongClassification(null, null, language, null, List.of(), List.of(), null, vocalForm,
                List.of(), "test", confidence, 0.0, 0.0, languageConfidence, vocalConfidence,
                artistGender, java.util.Map.of());
    }

    private static AiSongClassification identity(String title, String artist,
                                                 double titleConfidence, double artistConfidence) {
        return new AiSongClassification(title, artist, null, null, List.of(), List.of(), null, null,
                List.of(), "test", 0.99, titleConfidence, artistConfidence, 0.0, 0.0, null,
                java.util.Map.of());
    }

    private static AiConfigService.ResolvedConfig defaultConfig() {
        return new AiConfigService.ResolvedConfig(true, "https://ai.example.test", "model", "",
                30, 0.97, 0.92, AiConfigService.JsonMode.AUTO, 1, 1, "key");
    }
}
