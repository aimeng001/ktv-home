package com.homektv.library;

import com.homektv.domain.AudioLayout;
import com.homektv.media.MediaProbe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 媒体类型判定与指纹去重（P1.3，详设§9.3）。
 */
public final class MediaClassifier {

    public static final String KTV_VIDEO = "KTV_VIDEO";
    public static final String MV = "MV";
    public static final String AUDIO = "AUDIO";
    /** Temporary type used while Fast Index metadata is waiting for FFprobe. */
    public static final String PENDING_PROBE = "PENDING_PROBE";

    /** 时长分桶粒度（毫秒）：±2s 视为同一首，用 2000ms 桶 */
    private static final long DURATION_BUCKET_MS = 2000;

    private MediaClassifier() {}

    /**
     * 类型判定（详设§9.3）：
     * - ≥2 音轨且含视频 → KTV_VIDEO（原唱/伴奏双轨）
     * - 单音轨视频 → MV
     * - 纯音频（无视频） → AUDIO
     */
    public static String classify(MediaProbe probe) {
        if (probe.hasVideo()) {
            return probe.audioTracks() >= 2 ? KTV_VIDEO : MV;
        }
        return AUDIO;
    }

    /** 是否可切伴唱：含独立伴奏音轨（≥2 音轨） */
    public static boolean hasVocalTrack(MediaProbe probe) {
        return probe.audioTracks() >= 2;
    }

    /**
     * Whether the client can switch between original and accompaniment audio.
     * This is intentionally separate from {@link #hasVocalTrack(MediaProbe)}:
     * DUAL_CHANNEL has one audio stream, while DUAL_TRACK has independent tracks.
     */
    public static boolean supportsVocalSwitch(AudioLayout layout) {
        return layout == AudioLayout.DUAL_TRACK || layout == AudioLayout.DUAL_CHANNEL;
    }

    /**
     * 指纹：md5(lower(artist)|lower(title)|durationBucket)。
     * 时长按 2s 分桶，容忍不同文件源的轻微时长差异。
     */
    public static String fingerprint(String artist, String title, long durationMs) {
        long bucket = durationMs / DURATION_BUCKET_MS;
        String raw = safeLower(artist) + "|" + safeLower(title) + "|" + bucket;
        return md5(raw);
    }

    /**
     * A deterministic metadata-only identity for a provisional Fast Index row.
     * It is deliberately prefixed so it cannot collide with a media fingerprint
     * and is never presented as a content hash.
     */
    public static String fastIndexFingerprint(String path) {
        return "fast-index-" + md5(path == null ? "" : path);
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}
