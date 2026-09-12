package com.homektv.musicsource;

import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoverImageResourceBudgetTest {

    @Test
    void source_above_the_target_pixel_budget_is_downsampled_before_encoding() throws Exception {
        BufferedImage source = new BufferedImage(2_049, 2_049, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);

        byte[] normalized = new CoverImageNormalizer("unused", command -> {
            throw new AssertionError("ffmpeg fallback must not run");
        }).normalize(png.toByteArray());

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(normalized));
        assertThat(decoded.getWidth()).isLessThanOrEqualTo(2_048);
        assertThat(decoded.getHeight()).isLessThanOrEqualTo(2_048);
    }

    @Test
    void concurrent_decode_without_a_permit_returns_a_visible_busy_error() throws Exception {
        CoverImageNormalizer normalizer = new CoverImageNormalizer("unused", command -> {
            throw new AssertionError("ffmpeg fallback must not run");
        }, new Semaphore(0));

        assertThatThrownBy(() -> normalizer.normalize(tinyPng()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "IMAGE_BUSY");
    }

    @Test
    void source_dimension_above_the_decoder_guard_is_rejected_before_encoding() throws Exception {
        BufferedImage source = new BufferedImage(8_193, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);

        assertThatThrownBy(() -> new CoverImageNormalizer("unused", command -> {
            throw new AssertionError("ffmpeg fallback must not run");
        }).normalize(png.toByteArray()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "EXTERNAL_COVER_INVALID");
    }

    private static byte[] tinyPng() throws Exception {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);
        return png.toByteArray();
    }
}
