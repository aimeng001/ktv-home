package com.homektv.playback;

import com.homektv.domain.SongFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Cheap source identity for cache invalidation; it never reads the full NAS file. */
public final class SourceFingerprint {
    private SourceFingerprint() {}

    public static String from(SongFile source, Path realPath, long size, long modifiedMillis) {
        String identity = source.getFileIdentity() == null ? "" : source.getFileIdentity();
        String raw = realPath.toAbsolutePath().normalize() + "|" + size + "|" + modifiedMillis + "|" + identity;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
