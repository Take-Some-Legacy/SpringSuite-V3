package com.takesome.springsuite.openai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenAiClientTest {
    @Test
    void omitsTemperatureForGpt5Models() {
        assertThat(OpenAiClient.supportsTemperature("gpt-5")).isFalse();
        assertThat(OpenAiClient.supportsTemperature("gpt-5.5")).isFalse();
        assertThat(OpenAiClient.supportsTemperature("gpt-5-mini")).isFalse();
    }

    @Test
    void omitsTemperatureForReasoningModels() {
        assertThat(OpenAiClient.supportsTemperature("o1")).isFalse();
        assertThat(OpenAiClient.supportsTemperature("o3-mini")).isFalse();
        assertThat(OpenAiClient.supportsTemperature("o4-mini")).isFalse();
    }

    @Test
    void preservesTemperatureForCompatibleChatModels() {
        assertThat(OpenAiClient.supportsTemperature("gpt-4.1")).isTrue();
        assertThat(OpenAiClient.supportsTemperature("gpt-4o-mini")).isTrue();
        assertThat(OpenAiClient.supportsTemperature("custom-compatible-model")).isTrue();
    }
}
