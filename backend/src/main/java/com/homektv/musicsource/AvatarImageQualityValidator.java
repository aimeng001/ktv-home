package com.homektv.musicsource;

import com.homektv.web.ApiException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Validates downloaded artist avatar images against minimum dimensions,
 * portrait-compatible aspect ratios, and known placeholder silhouettes.
 */
public final class AvatarImageQualityValidator {
    private static final int MIN_PRIMARY_DIMENSION = 64;
    private static final int MIN_FALLBACK_DIMENSION = 120;
    private static final double MIN_ASPECT_RATIO = 0.5d;
    private static final double MAX_ASPECT_RATIO = 2.0d;
    private static final List<String> PLACEHOLDER_URL_FRAGMENTS = List.of(
            "5639395138885805",
            "6093165149983392",
            "109951165611432028",
            "default_avatar",
            "default-avatar",
            "default_singer",
            "no_pic",
            "nopic"
    );

    private AvatarImageQualityValidator() {}

    public static boolean isKnownPlaceholderUrl(String url) {
        if (url == null || url.isBlank()) return true;
        String lower = url.toLowerCase(Locale.ROOT);
        for (String fragment : PLACEHOLDER_URL_FRAGMENTS) {
            if (lower.contains(fragment)) return true;
        }
        return false;
    }

    public static void validateNormalizedBytes(byte[] jpegBytes, boolean strictFallbackRules) {
        if (jpegBytes == null || jpegBytes.length == 0) {
            throw rejected("头像图片为空");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(jpegBytes));
        } catch (IOException ex) {
            throw rejected("头像图片无法解码");
        }
        validateBufferedImage(image, strictFallbackRules);
    }

    public static void validateBufferedImage(BufferedImage image, boolean strictFallbackRules) {
        if (image == null) {
            throw rejected("头像图片格式无效");
        }
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            int minDimension = strictFallbackRules ? MIN_FALLBACK_DIMENSION : MIN_PRIMARY_DIMENSION;
            if (width < minDimension || height < minDimension) {
                throw rejected("头像分辨率过低");
            }
            if (strictFallbackRules) {
                double ratio = (double) width / height;
                if (ratio < MIN_ASPECT_RATIO || ratio > MAX_ASPECT_RATIO) {
                    throw rejected("头像宽高比不符合人像要求");
                }
            }
            if (isNearlyUniformPlaceholder(image)) {
                throw rejected("检测到默认空白或单色剪影占位图");
            }
        } finally {
            image.flush();
        }
    }

    public static boolean isUsableCachedAvatarFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) return false;
        try {
            byte[] bytes = Files.readAllBytes(file);
            validateNormalizedBytes(bytes, false);
            return true;
        } catch (ApiException | IOException ex) {
            return false;
        }
    }

    static boolean isNearlyUniformPlaceholder(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) return true;
        int grid = 16;
        int total = grid * grid;
        int[] lums = new int[total];
        int[] buckets = new int[16];
        double lumSum = 0d;
        int minLum = 255;
        int maxLum = 0;
        int maxChroma = 0;
        int index = 0;

        for (int gy = 0; gy < grid; gy++) {
            int y = Math.min(height - 1, (gy * height) / grid + Math.max(0, height / (grid * 2)));
            for (int gx = 0; gx < grid; gx++) {
                int x = Math.min(width - 1, (gx * width) / grid + Math.max(0, width / (grid * 2)));
                int rgb = image.getRGB(x, y);
                int r = (rgb >>> 16) & 0xff;
                int g = (rgb >>> 8) & 0xff;
                int b = rgb & 0xff;
                int lum = (r * 299 + g * 587 + b * 114) / 1000;
                int chroma = Math.max(Math.abs(r - g), Math.max(Math.abs(g - b), Math.abs(r - b)));
                lums[index++] = lum;
                lumSum += lum;
                if (lum < minLum) minLum = lum;
                if (lum > maxLum) maxLum = lum;
                if (chroma > maxChroma) maxChroma = chroma;
                buckets[Math.min(15, lum / 16)]++;
            }
        }

        double mean = lumSum / total;
        double varSum = 0d;
        for (int lum : lums) {
            double diff = lum - mean;
            varSum += diff * diff;
        }
        double stdDev = Math.sqrt(varSum / total);
        if (stdDev < 8.0d) return true;

        int maxBucket = 0;
        for (int count : buckets) {
            if (count > maxBucket) maxBucket = count;
        }
        double dominantBucketRatio = (double) maxBucket / total;
        int lumRange = maxLum - minLum;
        return maxChroma <= 8 && dominantBucketRatio >= 0.72d && lumRange <= 65;
    }

    private static ApiException rejected(String message) {
        return new ApiException("AVATAR_IMAGE_REJECTED", message);
    }
}
