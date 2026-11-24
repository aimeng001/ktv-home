package com.homektv.musicsource;

import com.homektv.common.ApiException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarImageQualityValidatorTest {

    @Test
    void detectsKnownPlaceholderUrls() {
        assertThat(AvatarImageQualityValidator.isKnownPlaceholderUrl(
                "https://p1.music.126.net/6y-UleORITEDbvrOLV0Q8A==/5639395138885805.jpg")).isTrue();
        assertThat(AvatarImageQualityValidator.isKnownPlaceholderUrl(
                "https://p2.music.126.net/real-artist-hash/109951169999999999.jpg")).isFalse();
    }

    @Test
    void rejectsSolidGreyPlaceholderAndTinyOrBannerFallbackImages() throws Exception {
        BufferedImage greyCard = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = greyCard.createGraphics();
        g.setColor(new Color(225, 225, 225));
        g.fillRect(0, 0, 300, 300);
        g.setColor(new Color(195, 195, 195));
        g.fillOval(110, 90, 80, 80);
        g.dispose();

        assertThatThrownBy(() -> AvatarImageQualityValidator.validateNormalizedBytes(toJpeg(greyCard), false))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "AVATAR_IMAGE_REJECTED");

        BufferedImage tiny = portraitImage(80, 80);
        assertThatThrownBy(() -> AvatarImageQualityValidator.validateNormalizedBytes(toJpeg(tiny), true))
                .isInstanceOf(ApiException.class);

        BufferedImage banner = portraitImage(480, 120);
        assertThatThrownBy(() -> AvatarImageQualityValidator.validateNormalizedBytes(toJpeg(banner), true))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void acceptsRealisticPortraitImage() throws Exception {
        BufferedImage portrait = portraitImage(300, 300);

        assertThatCode(() -> AvatarImageQualityValidator.validateNormalizedBytes(toJpeg(portrait), true))
                .doesNotThrowAnyException();
    }

    private static BufferedImage portraitImage(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(32, 48, 86));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(228, 178, 146));
        g.fillOval(width / 4, height / 6, width / 2, height / 2);
        g.setColor(new Color(20, 20, 24));
        g.fillRect(0, height * 2 / 3, width, height / 3);
        g.dispose();
        return img;
    }

    private static byte[] toJpeg(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
