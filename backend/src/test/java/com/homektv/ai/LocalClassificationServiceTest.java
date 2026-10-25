package com.homektv.ai;

import com.homektv.domain.Song;
import com.homektv.domain.MediaImportRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalClassificationServiceTest {
    @Test
    void keepsLocalIdentityButDoesNotInventUnknownClassification() {
        Song song = new Song();
        song.setTitle("晴天");
        song.setArtist("周杰伦");
        song.setStatus("ok");
        song.setLanguage("未知");
        song.setVocalForm("未知");
        song.setMetadataProvenance("{\"title\":{\"source\":\"filename\"}}");

        AiSongClassification result = new LocalClassificationService().fromSong(song);

        assertThat(result.title()).isEqualTo("晴天");
        assertThat(result.artist()).isEqualTo("周杰伦");
        assertThat(result.language()).isEqualTo("未知");
        assertThat(result.vocalForm()).isEqualTo("未知");
        assertThat(result.languageConfidence()).isZero();
        assertThat(result.vocalFormConfidence()).isZero();
        assertThat(result.evidence()).containsEntry("source", "local_song_fields");
    }

    @Test
    void preservesKnownSongTagsAndUsesEvidenceForEachConfidenceField() {
        Song song = new Song();
        song.setTitle("  晴天  ");
        song.setArtist(" 周杰伦 ");
        song.setStatus("OK");
        song.setLanguage("国语");
        song.setVocalForm("合唱");
        song.setArtistGender("男");
        song.setAiEra("90年代");
        song.setAiGenres(new String[] { "流行", null, "   ", "摇滚" });
        song.setAiThemes(new String[] { "成长", "友情" });
        song.setAiAgeRange("全年龄");

        AiSongClassification result = new LocalClassificationService().fromSong(song);

        assertThat(result.title()).isEqualTo("晴天");
        assertThat(result.artist()).isEqualTo("周杰伦");
        assertThat(result.language()).isEqualTo("国语");
        assertThat(result.vocalForm()).isEqualTo("合唱");
        assertThat(result.artistGender()).isEqualTo("男");
        assertThat(result.era()).isEqualTo("90年代");
        assertThat(result.genres()).containsExactly("流行", "摇滚");
        assertThat(result.themes()).containsExactly("成长", "友情");
        assertThat(result.ageRange()).isEqualTo("全年龄");
        assertThat(result.confidence()).isEqualTo(0.86);
        assertThat(result.titleConfidence()).isEqualTo(0.86);
        assertThat(result.artistConfidence()).isEqualTo(0.86);
        assertThat(result.languageConfidence()).isEqualTo(0.86);
        assertThat(result.vocalFormConfidence()).isEqualTo(0.86);
        assertThat(result.evidence()).containsEntry("status", "OK");
    }

    @Test
    void unknownOrIncompleteSongIdentityGetsConservativeConfidenceAndEmptyLists() {
        LocalClassificationService service = new LocalClassificationService();
        for (String[] identity : new String[][] {
                { null, "周杰伦", "ok" },
                { "   ", "周杰伦", "ok" },
                { "晴天", "未知歌手", "ok" },
                { "晴天", "周杰伦", "pending" },
                { "未知", "周杰伦", "ok" }
        }) {
            Song song = new Song();
            song.setTitle(identity[0]);
            song.setArtist(identity[1]);
            song.setStatus(identity[2]);
            song.setLanguage(" ");
            song.setVocalForm(null);
            song.setArtistGender("未知");
            song.setAiGenres(null);
            song.setAiThemes(new String[] { null, "  " });

            AiSongClassification result = service.fromSong(song);

            assertThat(result.confidence()).isEqualTo(0.35);
            assertThat(result.language()).isEqualTo("未知");
            assertThat(result.vocalForm()).isEqualTo("未知");
            assertThat(result.artistGender()).isEqualTo("未知");
            assertThat(result.genres()).isEmpty();
            assertThat(result.themes()).isEmpty();
            assertThat(result.languageConfidence()).isZero();
            assertThat(result.vocalFormConfidence()).isZero();
        }

        Song nullFields = new Song();
        nullFields.setStatus(null);
        nullFields.setMetadataProvenance(null);
        AiSongClassification nullResult = service.fromSong(nullFields);
        assertThat(nullResult.title()).isEmpty();
        assertThat(nullResult.artist()).isEqualTo("未知歌手");
        assertThat(nullResult.evidence()).containsEntry("status", "").containsEntry("metadataProvenance", "");

        Song nullArtist = new Song();
        nullArtist.setTitle("晴天");
        nullArtist.setArtist(null);
        nullArtist.setStatus("ok");
        assertThat(service.fromSong(nullArtist).artist()).isEmpty();
    }

    @Test
    void nullLanguageAndBlankVocalOrGenderRemainUnclassified() {
        Song song = new Song();
        song.setTitle("晴天");
        song.setArtist("周杰伦");
        song.setStatus("ok");
        song.setLanguage(null);
        song.setVocalForm(" ");
        song.setArtistGender(null);

        AiSongClassification result = new LocalClassificationService().fromSong(song);

        assertThat(result.language()).isEqualTo("未知");
        assertThat(result.vocalForm()).isEqualTo("未知");
        assertThat(result.artistGender()).isEqualTo("未知");
        assertThat(result.languageConfidence()).isZero();
        assertThat(result.vocalFormConfidence()).isZero();
        assertThat(result.confidence()).isEqualTo(0.86);

        song.setArtistGender("   ");
        assertThat(new LocalClassificationService().fromSong(song).artistGender()).isEqualTo("未知");
    }

    @Test
    void importFallbackUsesOnlyParsedTitleAndArtistForConfidence() {
        LocalClassificationService service = new LocalClassificationService();
        MediaImportRecord complete = new MediaImportRecord();
        complete.setParsedTitle("  晴天 ");
        complete.setParsedArtist(" 周杰伦 ");
        complete.setSourceFilename("周杰伦-晴天.mkv");
        complete.setReason("filename parsed");

        AiSongClassification completeResult = service.fromImport(complete);
        assertThat(completeResult.title()).isEqualTo("晴天");
        assertThat(completeResult.artist()).isEqualTo("周杰伦");
        assertThat(completeResult.confidence()).isEqualTo(0.86);
        assertThat(completeResult.language()).isEqualTo("未知");
        assertThat(completeResult.evidence()).containsEntry("filename", "周杰伦-晴天.mkv")
                .containsEntry("reason", "filename parsed");

        for (String[] identity : new String[][] { { null, "周杰伦" }, { "晴天", "未知歌手" }, { "未知", "周杰伦" } }) {
            MediaImportRecord incomplete = new MediaImportRecord();
            incomplete.setParsedTitle(identity[0]);
            incomplete.setParsedArtist(identity[1]);
            AiSongClassification result = service.fromImport(incomplete);
            assertThat(result.confidence()).isEqualTo(0.35);
            assertThat(result.languageConfidence()).isZero();
            assertThat(result.vocalFormConfidence()).isZero();
            assertThat(result.genres()).isEmpty();
            assertThat(result.themes()).isEmpty();
        }

        MediaImportRecord nullFields = new MediaImportRecord();
        AiSongClassification nullResult = service.fromImport(nullFields);
        assertThat(nullResult.title()).isEmpty();
        assertThat(nullResult.artist()).isEmpty();
        assertThat(nullResult.evidence()).containsEntry("filename", "").containsEntry("reason", "");
    }
}
