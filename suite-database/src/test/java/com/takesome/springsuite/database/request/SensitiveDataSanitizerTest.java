package com.takesome.springsuite.database.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SensitiveDataSanitizerTest {
    private final SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(new ObjectMapper(), true, 16_384);

    @Test
    void redactsJsonCredentialsWithoutRemovingNormalRequestData() {
        String input = """
                {"model":"gpt-test","api_key":"sk-super-secret-value","nested":{"access_token":"abc123","prompt":"hello"}}
                """;

        String result = sanitizer.body(
                input.getBytes(StandardCharsets.UTF_8),
                "application/json",
                "UTF-8",
                false,
                input.length()
        );

        assertThat(result).contains("gpt-test", "hello", "<redacted>");
        assertThat(result).doesNotContain("sk-super-secret-value", "abc123");
    }

    @Test
    void redactsSensitiveQueryParameters() {
        String result = sanitizer.queryString("provider=openai&access_token=secret-value&query=hello");

        assertThat(result).contains("provider=openai", "access_token=<redacted>", "query=hello");
        assertThat(result).doesNotContain("secret-value");
    }

    @Test
    void marksTruncatedBodies() {
        String result = sanitizer.body(
                "partial".getBytes(StandardCharsets.UTF_8),
                "text/plain",
                "UTF-8",
                true,
                100
        );

        assertThat(result).contains("partial", "<truncated", "total=100 bytes");
    }
}
