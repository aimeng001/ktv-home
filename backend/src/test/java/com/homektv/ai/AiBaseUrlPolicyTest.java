package com.homektv.ai;

import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiBaseUrlPolicyTest {
    @Test
    void allowsPublicHttpsEndpoint() {
        assertThatCode(() -> AiBaseUrlPolicy.requireSafe("https://8.8.8.8/v1", false))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPublicHttpEndpoint() {
        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("http://8.8.8.8/v1", false))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getCode())
                .isEqualTo("INVALID_AI_CONFIG");
    }

    @Test
    void rejectsPrivateEndpointUnlessExplicitlyAllowed() {
        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("http://127.0.0.1:11434/v1", false))
                .isInstanceOf(ApiException.class);

        assertThatCode(() -> AiBaseUrlPolicy.requireSafe("http://127.0.0.1:11434/v1", true))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsIpv4MappedLoopbackAddress() {
        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("http://[::ffff:127.0.0.1]:11434/v1", false))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsCredentialsQueryAndFragment() {
        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("https://user:secret@8.8.8.8/v1", false))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("https://8.8.8.8/v1?key=secret", false))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AiBaseUrlPolicy.requireSafe("https://8.8.8.8/v1#fragment", false))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void normalizesTrailingSlashWithoutExposingSecrets() {
        assertThat(AiBaseUrlPolicy.normalizeAndValidate(" https://8.8.8.8/v1/// ", false))
                .isEqualTo("https://8.8.8.8/v1");
    }
}
