package com.homektv.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QrControllerTest {

    @Test
    void createsPngAtClampedAndInRangeSizesUsingTheRequestAddress() throws Exception {
        ServerAddressService addresses = mock(ServerAddressService.class);
        when(addresses.h5Url("default", "tv.local", 8081)).thenReturn("http://tv.local:8081/m?room=default");
        when(addresses.h5Url("living", "tv.local", 8081)).thenReturn("http://tv.local:8081/m?room=living");
        QrController controller = new QrController(addresses);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getServerName()).thenReturn("tv.local");
        when(request.getServerPort()).thenReturn(8081);

        assertQrSize(controller.qr("default", 1, request).getBody(), 120);
        assertQrSize(controller.qr("default", 480, request).getBody(), 480);
        assertQrSize(controller.qr("living", 2_000, request).getBody(), 1_080);

        verify(addresses, times(2)).h5Url("default", "tv.local", 8081);
        verify(addresses).h5Url("living", "tv.local", 8081);
    }

    private static void assertQrSize(byte[] png, int expectedPixels) throws Exception {
        assertThat(png).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(expectedPixels);
        assertThat(image.getHeight()).isEqualTo(expectedPixels);
    }
}
