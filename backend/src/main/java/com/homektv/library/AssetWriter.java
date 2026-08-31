package com.homektv.library;

import com.homektv.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * 歌词/封面缓存落盘（P1.4）。写入 data 目录的 lyrics/ 与 covers/ 子目录，
 * 以歌曲指纹命名，返回相对 data 目录的路径存入 songs 表。
 */
@Component
public class AssetWriter {

    private static final Logger log = LoggerFactory.getLogger(AssetWriter.class);

    private final Path dataRoot;
    private final AppProperties props;

    public AssetWriter(AppProperties props) {
        this.props = props;
        this.dataRoot = Path.of(props.getDataPath());
        LibraryModePolicy.requireCacheOutsideExternalSource(props, dataRoot);
    }

    /** 写歌词缓存，返回相对路径 lyrics/{fingerprint}.lrc */
    public String writeLyric(String fingerprint, String lyricText) {
        String rel = "lyrics/" + fingerprint + ".lrc";
        write(rel, lyricText.getBytes(StandardCharsets.UTF_8));
        return rel;
    }

    /** 写封面缓存，返回相对路径 covers/{fingerprint}.{ext} */
    public String writeCover(String fingerprint, byte[] image, String ext) {
        String rel = "covers/" + fingerprint + "." + (ext == null ? "jpg" : ext);
        write(rel, image);
        return rel;
    }

    /** Write an artist avatar to an application-owned cache, keyed by artist identity. */
    public String writeArtistCover(String artistKey, byte[] image, String ext) {
        String rel = "artist-covers/" + digest(artistKey) + "." + (ext == null ? "jpg" : ext);
        write(rel, image);
        return rel;
    }

    /**
     * Checks an application-owned cache path without treating a stale database
     * path as a valid avatar. The path is relative to app.data-path and is
     * never allowed to escape that directory or the external-library boundary.
     */
    public boolean isReadableCache(String relativePath) {
        return readableCachePath(relativePath).isPresent();
    }

    /** Returns a verified real cache path for serving, or empty when unsafe/missing. */
    public Optional<Path> readableCachePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return Optional.empty();
        try {
            Path cacheRoot = dataRoot.toAbsolutePath().normalize();
            Path target = cacheRoot.resolve(relativePath).normalize();
            if (!target.startsWith(cacheRoot)) return Optional.empty();
            Path realRoot = cacheRoot.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realRoot)) return Optional.empty();
            LibraryModePolicy.requireCacheOutsideExternalSource(props, realTarget);
            return Files.isRegularFile(realTarget) && Files.isReadable(realTarget)
                    ? Optional.of(realTarget) : Optional.empty();
        } catch (RuntimeException failure) {
            log.debug("资源缓存路径不可读：{} - {}", relativePath, failure.getMessage());
            return Optional.empty();
        } catch (IOException failure) {
            log.debug("资源缓存路径不可读：{} - {}", relativePath, failure.getMessage());
            return Optional.empty();
        }
    }

    /** Deletes one verified application-owned cache file, if it still exists. */
    public void deleteReadableCache(String relativePath) {
        readableCachePath(relativePath).ifPresent(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException failure) {
                log.debug("资源缓存清理失败：{} - {}", relativePath, failure.getMessage());
            }
        });
    }

    /**
     * Checks and returns a verified real standby logo path within dataRoot/standby/.
     * Prevents directory traversal, arbitrary data file access, and symlink escape.
     */
    public Optional<Path> readableStandbyLogo(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return Optional.empty();
        try {
            Path cacheRoot = dataRoot.toAbsolutePath().normalize();
            Path standbyRoot = cacheRoot.resolve("standby").normalize();
            Path target = cacheRoot.resolve(relativePath).normalize();
            if (!target.startsWith(standbyRoot)) return Optional.empty();
            if (!Files.exists(standbyRoot)) return Optional.empty();

            Path realStandby = standbyRoot.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realStandby)) return Optional.empty();

            LibraryModePolicy.requireCacheOutsideExternalSource(props, realTarget);
            return Files.isRegularFile(realTarget) && Files.isReadable(realTarget)
                    ? Optional.of(realTarget) : Optional.empty();
        } catch (Exception failure) {
            log.debug("待机 Logo 路径不合法：{} - {}", relativePath, failure.getMessage());
            return Optional.empty();
        }
    }

    public String writePlaylistCover(Long playlistId, byte[] image, String ext) {
        String rel = "playlist-covers/" + playlistId + "-" + System.currentTimeMillis() + "." + (ext == null ? "jpg" : ext);
        write(rel, image);
        return rel;
    }

    public String writeStandbyLogo(byte[] image, String ext) {
        String rel = "standby/logo-" + System.currentTimeMillis() + "." + (ext == null ? "png" : ext);
        write(rel, image);
        return rel;
    }

    private void write(String relPath, byte[] data) {
        try {
            Path target = dataRoot.resolve(relPath);
            LibraryModePolicy.requireCacheOutsideExternalSource(props, target);
            Files.createDirectories(target.getParent());
            // Re-check the concrete target after parent directories exist. This catches
            // a symlink/junction in lyrics/, covers/, or another cache subdirectory.
            LibraryModePolicy.requireCacheOutsideExternalSource(props, target);
            Files.write(target, data);
        } catch (IOException e) {
            log.warn("资源落盘失败：{} - {}", relPath, e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) out.append(String.format("%02x", item & 0xff));
            return out.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("无法生成艺术家缓存键", failure);
        }
    }
}
