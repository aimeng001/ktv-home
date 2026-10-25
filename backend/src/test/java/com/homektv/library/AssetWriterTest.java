package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssetWriterTest {

    @TempDir
    Path tempDir;

    private Path dataRoot;
    private AssetWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        dataRoot = Files.createDirectory(tempDir.resolve("data"));
        AppProperties props = new AppProperties();
        props.setDataPath(dataRoot.toString());
        props.setSourceLibraryPath(tempDir.resolve("source").toString());
        props.setKtvLibraryPath(tempDir.resolve("managed").toString());
        props.setLibraryMode(LibraryMode.MANAGED);
        writer = new AssetWriter(props);
    }

    @Test
    void writesUtf8LyricsAndTreatsNullTextAsEmpty() throws Exception {
        assertThat(writer.writeLyric("song-1", "中文歌词"))
                .isEqualTo("lyrics/song-1.lrc");
        assertThat(Files.readString(dataRoot.resolve("lyrics/song-1.lrc")))
                .isEqualTo("中文歌词");

        assertThat(writer.writeLyric("song-2", null))
                .isEqualTo("lyrics/song-2.lrc");
        assertThat(Files.readAllBytes(dataRoot.resolve("lyrics/song-2.lrc")))
                .isEmpty();
    }

    @Test
    void acceptsMaximumLyricSizeAndRejectsOneByteOver() throws Exception {
        String maxSize = "x".repeat(AssetWriter.MAX_LYRIC_BYTES);
        writer.writeLyric("max", maxSize);
        assertThat(Files.size(dataRoot.resolve("lyrics/max.lrc")))
                .isEqualTo(AssetWriter.MAX_LYRIC_BYTES);

        assertApiCode("LYRIC_TOO_LARGE", () -> writer.writeLyric("too-large", maxSize + "x"));
        assertThat(Files.exists(dataRoot.resolve("lyrics/too-large.lrc"))).isFalse();
    }

    @Test
    void writesCoverAndArtistCoverWithSafeDeterministicNames() throws Exception {
        assertThat(writer.writeCover("song-1", new byte[]{1, 2, 3}, null))
                .isEqualTo("covers/song-1.jpg");
        assertThat(Files.readAllBytes(dataRoot.resolve("covers/song-1.jpg")))
                .containsExactly(1, 2, 3);

        String first = writer.writeArtistCover("../../artist/name", new byte[]{4, 5}, null);
        String second = writer.writeArtistCover("../../artist/name", new byte[]{6}, null);
        assertThat(first).isEqualTo(second).startsWith("artist-covers/").endsWith(".jpg");
        assertThat(first).doesNotContain("../", "/name");
        assertThat(Files.readAllBytes(dataRoot.resolve(first))).containsExactly(6);
        assertThat(writer.writeArtistCover("", new byte[]{7}, "webp"))
                .startsWith("artist-covers/").endsWith(".webp");
    }

    @Test
    void validatesImagePresenceAndMaximumSizeBeforeWriting() {
        assertApiCode("INVALID_IMAGE", () -> writer.writeCover("missing", null, "jpg"));
        assertApiCode("INVALID_IMAGE", () -> writer.writeCover("empty", new byte[0], "jpg"));
        assertApiCode("IMAGE_TOO_LARGE", () -> writer.writeCover("large",
                new byte[AssetWriter.MAX_IMAGE_BYTES + 1], "jpg"));
        assertThat(Files.exists(dataRoot.resolve("covers/large.jpg"))).isFalse();
    }

    @Test
    void artistCoverCapacityChecksBoundsAndExactRemainingBudget() throws Exception {
        assertThat(writer.hasArtistCoverCapacity(-1)).isFalse();
        assertThat(writer.hasArtistCoverCapacity(AssetWriter.MAX_ARTIST_COVER_CACHE_BYTES + 1)).isFalse();
        assertThat(writer.hasArtistCoverCapacity(AssetWriter.MAX_ARTIST_COVER_CACHE_BYTES)).isTrue();

        Path coverRoot = Files.createDirectory(dataRoot.resolve("artist-covers"));
        Files.write(coverRoot.resolve("existing.jpg"), new byte[]{1, 2, 3});
        Files.createDirectory(coverRoot.resolve("nested-empty"));
        assertThat(writer.hasArtistCoverCapacity(AssetWriter.MAX_ARTIST_COVER_CACHE_BYTES - 3)).isTrue();
        assertThat(writer.hasArtistCoverCapacity(AssetWriter.MAX_ARTIST_COVER_CACHE_BYTES - 2)).isFalse();
    }

    @Test
    void artistCoverCapacityRejectsANonDirectoryCacheRoot() throws Exception {
        Files.write(dataRoot.resolve("artist-covers"), new byte[]{1});
        assertThat(writer.hasArtistCoverCapacity(0)).isFalse();
    }

    @Test
    void readableCacheRequiresAnExistingRegularFileInsideDataRoot() throws Exception {
        Files.createDirectories(dataRoot.resolve("covers"));
        Files.write(dataRoot.resolve("covers/ok.jpg"), new byte[]{1});

        assertThat(writer.isReadableCache(null)).isFalse();
        assertThat(writer.isReadableCache("  ")).isFalse();
        assertThat(writer.readableCachePath("covers/ok.jpg"))
                .contains(dataRoot.resolve("covers/ok.jpg").toRealPath());
        assertThat(writer.isReadableCache("covers")).isFalse();
        assertThat(writer.isReadableCache("covers/missing.jpg")).isFalse();
        assertThat(writer.isReadableCache("../outside.txt")).isFalse();
        assertThat(writer.isReadableCache("invalid\u0000path")).isFalse();
    }

    @Test
    void deletesOnlyAReadableCacheFile() throws Exception {
        Path cached = dataRoot.resolve("lyrics/remove.lrc");
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "lyric");
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "keep");

        writer.deleteReadableCache("lyrics/remove.lrc");
        writer.deleteReadableCache("../outside.txt");

        assertThat(cached).doesNotExist();
        assertThat(outside).hasContent("keep");
    }

    @Test
    void servesOnlyRegularStandbyLogoFilesBelowStandbyDirectory() throws Exception {
        Path standby = Files.createDirectory(dataRoot.resolve("standby"));
        Path logo = standby.resolve("logo.png");
        Files.write(logo, new byte[]{1, 2});

        assertThat(writer.readableStandbyLogo(null)).isEmpty();
        assertThat(writer.readableStandbyLogo(" ")).isEmpty();
        assertThat(writer.readableStandbyLogo("standby/logo.png")).contains(logo.toRealPath());
        assertThat(writer.readableStandbyLogo("covers/logo.png")).isEmpty();
        assertThat(writer.readableStandbyLogo("standby/missing.png")).isEmpty();
        assertThat(writer.readableStandbyLogo("standby")).isEmpty();
        assertThat(writer.readableStandbyLogo("invalid\u0000path")).isEmpty();
    }

    @Test
    void writesPlaylistCoverAndStandbyLogoUsingApplicationOwnedFolders() throws Exception {
        assertThat(writer.writePlaylistCover(12L, new byte[]{1}, null))
                .startsWith("playlist-covers/12-").endsWith(".jpg");
        assertThat(writer.writeStandbyLogo(new byte[]{2}, null))
                .startsWith("standby/logo-").endsWith(".png");

        try (var playlistCovers = Files.list(dataRoot.resolve("playlist-covers"));
             var standbyLogos = Files.list(dataRoot.resolve("standby"))) {
            assertThat(playlistCovers.count()).isEqualTo(1);
            assertThat(standbyLogos.count()).isEqualTo(1);
        }
    }

    @Test
    void wrapsFilesystemWriteFailuresWithoutReportingSuccess() throws Exception {
        Files.write(dataRoot.resolve("covers"), new byte[]{1});
        assertThatThrownBy(() -> writer.writeCover("song", new byte[]{2}, "jpg"))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void rejectsCacheRootInsideExternalReadOnlySource() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("external-source"));
        Path nestedData = Files.createDirectory(source.resolve("app-data"));
        AppProperties props = new AppProperties();
        props.setDataPath(nestedData.toString());
        props.setSourceLibraryPath(source.toString());
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);

        assertApiCode("EXTERNAL_READ_ONLY", () -> new AssetWriter(props));
    }

    private static void assertApiCode(String code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo(code);
    }
}
