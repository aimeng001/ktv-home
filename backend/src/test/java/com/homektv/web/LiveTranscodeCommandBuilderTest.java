package com.homektv.web;

import com.homektv.domain.SongFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class LiveTranscodeCommandBuilderTest {

    @TempDir Path temp;

    private final LiveTranscodeCommandBuilder builder = new LiveTranscodeCommandBuilder("ffmpeg-test");

    @Test
    void mapsEveryAudioTrackAndKeepsTheOutputOnStdoutWithExplicitStereo() {
        File source = temp.resolve("dual-track.mkv").toFile();
        SongFile song = new SongFile();
        song.setAudioTracks(3);

        List<String> command = builder.build(source, song, null);

        assertThat(command).containsSubsequence(
                "-map", "0:V:0?",
                "-map", "0:a:0",
                "-map", "0:a:1",
                "-map", "0:a:2",
                "-ac", "2",
                "-f", "mp4",
                "-movflags", "frag_keyframe+empty_moov+default_base_moof",
                "pipe:1");
        assertThat(command).doesNotContain("0:a?");
        assertThat(command.get(command.size() - 1)).isEqualTo("pipe:1");
    }

    @Test
    void usesOptionalAudioMappingForZeroOrOneTrack() {
        File source = temp.resolve("single-track.mkv").toFile();
        SongFile song = new SongFile();

        assertThat(builder.build(source, song, null)).contains("0:a?");

        song.setAudioTracks(0);
        assertThat(builder.build(source, song, null)).contains("0:a?");
    }

    @Test
    void addsPositiveSeekInUsLocaleAndOmitsNonPositiveOrMissingSeek() {
        File source = temp.resolve("seek.mkv").toFile();
        SongFile song = new SongFile();
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            List<String> seekCommand = builder.build(source, song, 12.3456);
            assertThat(seekCommand).containsSubsequence("-ss", "12.346", "-i", source.getAbsolutePath());
            assertThat(seekCommand).doesNotContain("-noaccurate_seek");

            List<String> copySeekCommand = builder.build(source, song, 12.3456, 0, true);
            assertThat(copySeekCommand).containsSubsequence("-noaccurate_seek", "-ss", "12.346", "-i", source.getAbsolutePath());

            assertThat(builder.build(source, song, null)).doesNotContain("-ss");
            assertThat(builder.build(source, song, 0.0)).doesNotContain("-ss");
            assertThat(builder.build(source, song, -1.0)).doesNotContain("-ss");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void selectsSingleAudioTrackAndSupportsVideoStreamCopyForBrowserCompatibleMp4() {
        File source = temp.resolve("dual-track.mp4").toFile();
        SongFile song = new SongFile();
        song.setAudioTracks(2);

        List<String> copyCommand = builder.build(source, song, 18.5, 1, true);
        assertThat(copyCommand).containsSubsequence("-ss", "18.500", "-i", source.getAbsolutePath());
        assertThat(copyCommand).containsSubsequence("-map", "0:V:0?", "-map", "0:a:1");
        assertThat(copyCommand).containsSubsequence("-c:v", "copy", "-c:a", "aac");
        assertThat(copyCommand).doesNotContain("0:a:0");
        assertThat(copyCommand).doesNotContain("libx264");

        List<String> transcodeCommand = builder.build(source, song, null, 0, false);
        assertThat(transcodeCommand).containsSubsequence("-map", "0:V:0?", "-map", "0:a:0");
        assertThat(transcodeCommand).containsSubsequence("-c:v", "libx264");
    }

    @Test
    void mergesDualAudioTracksIntoStereoWhenMergeTracksIsTrue() {
        File source = temp.resolve("dual-track.mkv").toFile();
        SongFile song = new SongFile();
        song.setAudioTracks(2);

        List<String> command = builder.build(source, song, null, null, false, true);

        assertThat(command).contains("-filter_complex");
        assertThat(command).anyMatch(arg -> arg.contains("amerge=inputs=2"));
        assertThat(command).containsSubsequence("-map", "0:V:0?", "-map", "[aout]");
        assertThat(command).doesNotContain("-map 0:a:0", "-map 0:a:1");

        SongFile threeTrack = new SongFile();
        threeTrack.setAudioTracks(3);
        threeTrack.setOriginalTrackIndex(2);
        threeTrack.setAccompanimentTrackIndex(0);
        List<String> threeTrackCmd = builder.build(source, threeTrack, null, null, false, true);
        assertThat(threeTrackCmd).anyMatch(arg -> arg.contains("[0:a:2]") && arg.contains("[0:a:0]"));
    }

    @Test
    void fallsBackToDefaultMultiTrackMappingWhenMergeTracksIsFalseOrSingleTrack() {
        File source = temp.resolve("dual-track.mkv").toFile();
        SongFile song = new SongFile();
        song.setAudioTracks(2);

        List<String> command = builder.build(source, song, null, null, false, false);
        assertThat(command).doesNotContain("-filter_complex");
        assertThat(command).containsSubsequence("-map", "0:a:0", "-map", "0:a:1");

        SongFile single = new SongFile();
        single.setAudioTracks(1);
        List<String> singleCommand = builder.build(source, single, null, null, false, true);
        assertThat(singleCommand).doesNotContain("-filter_complex");
        assertThat(singleCommand).contains("0:a?");
    }

    @Test
    void copiesAudioWhenCopyAudioIsTrueAndNotMerging() {
        File source = temp.resolve("single-track-aac.mkv").toFile();
        SongFile song = new SongFile();
        song.setAudioTracks(1);

        List<String> command = builder.build(source, song, null, null, true, true, false);

        assertThat(command).containsSubsequence("-c:v", "copy", "-c:a", "copy");
        assertThat(command).containsSubsequence("-avoid_negative_ts", "make_zero");
        assertThat(command).doesNotContain("-ar", "-b:a", "192k");
    }

    @Test
    void addsNoaccurateSeekBeforeInputWhenStartSecondsIsPositive() {
        File source = temp.resolve("seek-keyframe.mkv").toFile();
        SongFile song = new SongFile();

        List<String> command = builder.build(source, song, 15.0, null, true, true, false);

        assertThat(command).containsSubsequence("-noaccurate_seek", "-ss", "15.000", "-i", source.getAbsolutePath());
    }

    @Test
    void forcesAudioTranscodeWhenMergeTracksIsTrueEvenIfCopyAudioRequested() {
        File source = temp.resolve("dual-track-merge.mkv").toFile();
        SongFile song = new SongFile();
        song.setAudioTracks(2);

        List<String> command = builder.build(source, song, null, null, true, true, true);

        assertThat(command).contains("-filter_complex");
        assertThat(command).containsSubsequence("-c:v", "copy", "-c:a", "aac");
        assertThat(command).doesNotContain("-c:a copy");
    }
}
