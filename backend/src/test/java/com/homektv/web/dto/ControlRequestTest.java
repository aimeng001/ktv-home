package com.homektv.web.dto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlRequestTest {

    @Test
    void treatsNullParamsAsEmptyAndUsesTheRequestedFallbacks() {
        ControlRequest request = new ControlRequest("seek", null, null);

        assertThat(request.safeParams()).isEmpty();
        assertThat(request.longParam("position")).isNull();
        assertThat(request.intParam("volume", 35)).isEqualTo(35);
        assertThat(request.boolParam("muted")).isFalse();
        assertThat(request.strParam("mode")).isNull();
    }

    @Test
    void convertsNumberAndNumericStringParams() {
        ControlRequest request = new ControlRequest("seek", Map.of(
                "asLong", 12,
                "asLongText", "9223372036854775806",
                "asInt", 12.9d,
                "asIntText", "42",
                "enabled", true,
                "enabledText", "true",
                "name", 17), null);

        assertThat(request.longParam("asLong")).isEqualTo(12L);
        assertThat(request.longParam("asLongText")).isEqualTo(9_223_372_036_854_775_806L);
        assertThat(request.intParam("asInt", -1)).isEqualTo(12);
        assertThat(request.intParam("asIntText", -1)).isEqualTo(42);
        assertThat(request.boolParam("enabled")).isTrue();
        assertThat(request.boolParam("enabledText")).isTrue();
        assertThat(request.strParam("name")).isEqualTo("17");
    }

    @Test
    void returnsNullOrFallbackForBlankAndUnsupportedValues() {
        ControlRequest request = new ControlRequest("seek", Map.of(
                "longBlank", "  ",
                "intBlank", "",
                "longUnsupported", true,
                "intUnsupported", true,
                "boolUpper", "TRUE",
                "boolUnsupported", 1), null);

        assertThat(request.longParam("longBlank")).isNull();
        assertThat(request.longParam("longUnsupported")).isNull();
        assertThat(request.longParam("missing")).isNull();
        assertThat(request.intParam("intBlank", 7)).isEqualTo(7);
        assertThat(request.intParam("intUnsupported", 8)).isEqualTo(8);
        assertThat(request.intParam("missing", 9)).isEqualTo(9);
        assertThat(request.boolParam("boolUpper")).isFalse();
        assertThat(request.boolParam("boolUnsupported")).isFalse();
    }

    @Test
    void rejectsMalformedNumericStringsInsteadOfSilentlyChangingTheCommand() {
        ControlRequest request = new ControlRequest("seek", Map.of(
                "long", "12x",
                "int", "1.5"), null);

        assertThatThrownBy(() -> request.longParam("long"))
                .isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> request.intParam("int", 0))
                .isInstanceOf(NumberFormatException.class);
    }
}
