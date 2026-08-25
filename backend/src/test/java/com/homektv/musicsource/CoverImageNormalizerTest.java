package com.homektv.musicsource;

import com.homektv.web.ApiException;
import com.homektv.testutil.FakeFfmpegProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoverImageNormalizerTest {
    @TempDir
    Path temp;

    private final CoverImageNormalizer normalizer = new CoverImageNormalizer("ffmpeg-command-not-needed");

    @Test
    void normalizesImageByActualBytesRegardlessOfResponseMime() throws Exception {
        BufferedImage source = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, Color.RED.getRGB());
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);

        byte[] normalized = normalizer.normalize(png.toByteArray());

        assertThat(normalized).startsWith((byte) 0xff, (byte) 0xd8);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(normalized));
        assertThat(decoded.getWidth()).isEqualTo(4);
        assertThat(decoded.getHeight()).isEqualTo(3);
    }

    @Test
    void flattensTransparentImagesToJpeg() throws Exception {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);

        byte[] normalized = normalizer.normalize(png.toByteArray());

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(normalized));
        Color background = new Color(decoded.getRGB(0, 0));
        assertThat(background.getRed()).isGreaterThan(245);
        assertThat(background.getGreen()).isGreaterThan(245);
        assertThat(background.getBlue()).isGreaterThan(245);
    }

    @Test
    void rejectsContentThatCannotBeDecodedOrConverted() {
        assertThatThrownBy(() -> normalizer.normalize("not an image".getBytes()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXTERNAL_COVER_INVALID");
    }

    @Test
    void usesFfmpegFallbackWhenImageIoCannotDecodeTheSource() throws Exception {
        BufferedImage fixture = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        fixture.setRGB(0, 0, Color.BLUE.getRGB());
        Path converted = temp.resolve("converted.jpg");
        ImageIO.write(fixture, "jpg", converted.toFile());
        CoverImageNormalizer fallback = new CoverImageNormalizer("fake-ffmpeg",
                command -> FakeFfmpegProcess.coverFallback(command, converted));

        byte[] normalized = fallback.normalize("unsupported upstream bytes".getBytes());

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(normalized));
        assertThat(decoded.getWidth()).isEqualTo(3);
        assertThat(decoded.getHeight()).isEqualTo(2);
    }
}
