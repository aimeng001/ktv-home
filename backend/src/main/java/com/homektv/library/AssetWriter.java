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
}
