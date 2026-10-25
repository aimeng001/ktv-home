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
                "-map", "0:v:0",
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

            assertThat(builder.build(source, song, null)).doesNotContain("-ss");
            assertThat(builder.build(source, song, 0.0)).doesNotContain("-ss");
            assertThat(builder.build(source, song, -1.0)).doesNotContain("-ss");
        } finally {
            Locale.setDefault(original);
        }
    }
}
