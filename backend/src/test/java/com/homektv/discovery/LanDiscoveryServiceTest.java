package com.homektv.discovery;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LanDiscoveryServiceTest {
    @Test
    void responseContainsActualHttpPortAndEscapedName() {
        String instanceId = "550e8400-e29b-41d4-a716-446655440000";
        String payload = new String(
                LanDiscoveryService.responsePayload(12345, "客厅\"KTV", instanceId),
                StandardCharsets.UTF_8);

        assertThat(payload).contains("\"service\":\"home-ktv\"");
        assertThat(payload).contains("\"protocolVersion\":1");
        assertThat(payload).contains("\"port\":12345");
        assertThat(payload).contains("\"instanceId\":\"" + instanceId + "\"");
        assertThat(payload).contains("客厅\\\"KTV");
    }

    @Test
    void responseEscapesJsonControlCharacters() throws Exception {
        String payload = new String(
                LanDiscoveryService.responsePayload(12345, "客厅\nKTV\t\u0001", "instance"),
                StandardCharsets.UTF_8);

        var parsed = new ObjectMapper().readTree(payload);
        assertThat(parsed.get("name").asText()).isEqualTo("客厅\nKTV\t\u0001");
    }
}
