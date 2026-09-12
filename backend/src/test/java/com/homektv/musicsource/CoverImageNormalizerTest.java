package com.homektv.musicsource;

import com.homektv.web.ApiException;
import com.homektv.testutil.FakeFfmpegProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoverImageNormalizerTest {
    @TempDir
    Path temp;

    private final CoverImageNormalizer normalizer = new CoverImageNormalizer("ffmpeg-command-not-needed");

    @Test
    void springCreatesComponentUsingConfiguredFfmpegPath() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(CoverImageNormalizer.class);
            context.refresh();

            assertThat(context.getBean(CoverImageNormalizer.class)).isNotNull();
        }
    }

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
    void rejectsOversizedInputBeforeImageDecodeOrFfmpegFallback() throws Exception {
        BufferedImage source = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);
        byte[] oversized = Arrays.copyOf(png.toByteArray(), CoverImageNormalizer.MAX_INPUT_BYTES + 1);

        assertThatThrownBy(() -> normalizer.normalize(oversized))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXTERNAL_COVER_INVALID");
    }

    @Test
    void waitsForFfmpegToExitAfterAConversionTimeout() {
        TimeoutProcess process = new TimeoutProcess();
        CoverImageNormalizer fallback = new CoverImageNormalizer("fake-ffmpeg", command -> process);

        assertThatThrownBy(() -> fallback.normalize("unsupported upstream bytes".getBytes()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("超时");

        assertThat(process.destroyed.get()).isTrue();
        assertThat(process.waitedAfterDestroy.get()).isTrue();
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

    private static final class TimeoutProcess extends Process {
        private final AtomicBoolean destroyed = new AtomicBoolean();
        private final AtomicBoolean waitedAfterDestroy = new AtomicBoolean();

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 143; }

        @Override public boolean waitFor(long timeout, TimeUnit unit) {
            if (destroyed.get()) {
                waitedAfterDestroy.set(true);
                return true;
            }
            return false;
        }

        @Override public int exitValue() {
            if (!destroyed.get()) throw new IllegalThreadStateException("process is still running");
            return 143;
        }

        @Override public void destroy() { destroyForcibly(); }
        @Override public Process destroyForcibly() { destroyed.set(true); return this; }
        @Override public boolean isAlive() { return !destroyed.get(); }
    }
}
