package com.homektv.diagnostics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    @Test
    void redactsSecretsFromUrlsHeadersJsonAndExceptionText() {
        String knownToken = "github_pat_" + "a".repeat(40);
        String input = """
                GET https://user:pass@example.test/cb?api_key=url-secret&client_token=device-secret
                Authorization: Bearer header-secret
                Cookie: session=cookie-secret
                {"password":"json-secret","access_token":"access-secret"}
                java.io.IOException: request failed for https://host/x?token=exception-secret
                """ + knownToken;

        String result = SecretRedactor.redact(input);

        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("user:pass@", "url-secret", "device-secret", "header-secret",
                "cookie-secret", "json-secret", "access-secret", "exception-secret",
                knownToken);
    }
}
