package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibraryModePolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void managedModeKeepsTheManagedRootAndAllowsManagedOperations() {
        Path source = tempDir.resolve("source");
        Path library = tempDir.resolve("library");
        AppProperties props = properties(LibraryMode.MANAGED, source, library);

        assertThat(LibraryModePolicy.isExternalReadOnly(null)).isFalse();
        assertThat(LibraryModePolicy.isExternalReadOnly(props)).isFalse();
        assertThat(LibraryModePolicy.activeLibraryRoot(props)).isEqualTo(library);
        assertThatCode(() -> LibraryModePolicy.requireManaged(props, "导入"))
                .doesNotThrowAnyException();
        assertThatCode(() -> LibraryModePolicy.requireCacheOutsideExternalSource(
                props, source.resolve("cache")))
                .doesNotThrowAnyException();
        assertThatCode(() -> LibraryModePolicy.requireExternalPathInsideSource(
                props, source.resolve("outside.mp4")))
                .doesNotThrowAnyException();
    }

    @Test
    void externalModeRejectsManagedWritesAndCacheWithinSource() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("source"));
        AppProperties props = properties(LibraryMode.EXTERNAL_READ_ONLY, source,
                tempDir.resolve("library"));

        assertThat(LibraryModePolicy.isExternalReadOnly(props)).isTrue();
        assertThat(LibraryModePolicy.activeLibraryRoot(props)).isEqualTo(source);
        assertThatThrownBy(() -> LibraryModePolicy.requireManaged(props, "删除"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(LibraryModePolicy.EXTERNAL_READ_ONLY_CODE);
        assertThatThrownBy(() -> LibraryModePolicy.requireCacheOutsideExternalSource(
                props, source.resolve("cache")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("缓存目录");
        assertThatCode(() -> LibraryModePolicy.requireCacheOutsideExternalSource(
                props, tempDir.resolve("cache")))
                .doesNotThrowAnyException();
        assertThatCode(() -> LibraryModePolicy.requireExternalPathInsideSource(props, source.resolve("song.mp4")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> LibraryModePolicy.requireExternalPathInsideSource(
                props, tempDir.resolve("outside.mp4")))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(LibraryModePolicy.EXTERNAL_READ_ONLY_CODE);
    }

    @Test
    void readablePathMustBeARegularFileBelowTheActiveLibrary() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("library"));
        Path media = Files.write(library.resolve("song.mp4"), new byte[]{1, 2, 3});
        AppProperties props = properties(LibraryMode.MANAGED, tempDir.resolve("source"), library);

        assertThat(LibraryModePolicy.requireReadablePathInsideActiveLibrary(props, media))
                .isEqualTo(media.toRealPath());
        assertThatThrownBy(() -> LibraryModePolicy.requireReadablePathInsideActiveLibrary(props, library))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不是普通文件");
    }

    @Test
    void sourceAndActiveLibraryPathsRejectNullAndEscapingCandidates() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("source"));
        Path library = Files.createDirectory(tempDir.resolve("library"));
        AppProperties props = properties(LibraryMode.EXTERNAL_READ_ONLY, source, library);
        Path media = Files.write(source.resolve("song.mp4"), new byte[]{4, 5});

        assertThat(LibraryModePolicy.requireReadablePathInsideSource(props, media))
                .isEqualTo(media.toRealPath());
        assertThatThrownBy(() -> LibraryModePolicy.requireReadablePathInsideSource(props, source))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo("FILE_NOT_FOUND");
        assertThat(LibraryModePolicy.requirePathInsideSource(props, source.resolve("future.mp4")))
                .isEqualTo(source.toRealPath().resolve("future.mp4"));
        assertThat(LibraryModePolicy.requirePathInsideSource(
                props, source.resolve("new-folder").resolve("future.mp4")))
                .isEqualTo(source.toRealPath().resolve("new-folder").resolve("future.mp4"));
        assertThatThrownBy(() -> LibraryModePolicy.requirePathInsideActiveLibrary(props, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> LibraryModePolicy.requirePathInsideSource(props, tempDir.resolve("outside.mp4")))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo("FILE_PATH_OUTSIDE_SOURCE");
        assertThatThrownBy(() -> LibraryModePolicy.requirePathInsideActiveLibrary(props, library.resolve("..")))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo("FILE_PATH_OUTSIDE_LIBRARY");
    }

    @Test
    void danglingSymbolicLinkCannotBypassRealPathResolution() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("library"));
        Path link = Files.createSymbolicLink(library.resolve("dangling.mp4"),
                tempDir.resolve("missing-target.mp4"));
        AppProperties props = properties(LibraryMode.MANAGED, tempDir.resolve("source"), library);

        assertThatThrownBy(() -> LibraryModePolicy.requirePathInsideActiveLibrary(props, link))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("无法解析文件路径");
    }

    @Test
    void symlinkWithinLibraryResolvesToTargetAndExternalTargetIsRejected() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("library"));
        Path media = Files.write(library.resolve("song.mp4"), new byte[]{7, 8});
        Path alias = Files.createSymbolicLink(library.resolve("alias.mp4"), media);
        AppProperties props = properties(LibraryMode.MANAGED, tempDir.resolve("source"), library);

        assertThat(LibraryModePolicy.requireReadablePathInsideActiveLibrary(props, alias))
                .isEqualTo(media.toRealPath());

        Path external = Files.write(tempDir.resolve("outside.mp4"), new byte[]{9});
        Path escape = Files.createSymbolicLink(library.resolve("escape.mp4"), external);
        assertThatThrownBy(() -> LibraryModePolicy.requireReadablePathInsideActiveLibrary(props, escape))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo("FILE_PATH_OUTSIDE_LIBRARY");
    }

    private static AppProperties properties(LibraryMode mode, Path source, Path library) {
        AppProperties props = new AppProperties();
        props.setLibraryMode(mode);
        props.setSourceLibraryPath(source.toString());
        props.setKtvLibraryPath(library.toString());
        return props;
    }
}
