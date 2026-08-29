package com.homektv.web;

import com.homektv.library.SettingService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServerAddressServiceTest {

    @Test
    void usesReachableRequestHostInsteadOfContainerInterface() {
        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of());
        ServerAddressService service = new ServerAddressService(settings, 8080);

        assertEquals("http://192.168.1.10:8080/m?room=default",
                service.h5Url("default", "192.168.1.10", 8080));
    }

    @Test
    void manualDisplayAddressStillHasHighestPriority() {
        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of("display_address", "http://ktv.home:9090/m"));
        ServerAddressService service = new ServerAddressService(settings, 8080);

        assertEquals("http://ktv.home:9090/m?room=room-a",
                service.h5Url("room-a", "192.168.1.10", 8080));
    }

    @Test
    void legacyQrAddressRemainsTheManualAddressWhenDisplayAddressIsBlank() {
        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of("display_address", "", "qr_address", "192.168.1.20:9091"));
        ServerAddressService service = new ServerAddressService(settings, 8080);

        assertEquals("http://192.168.1.20:9091/m?room=room-a",
                service.h5Url("room-a", "192.168.1.10", 8080));
    }
}
