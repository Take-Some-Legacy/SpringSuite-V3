package com.takesome.springsuite.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.takesome.springsuite.core.ai.AiChatRequest;
import com.takesome.springsuite.core.ai.AiChatResponse;
import com.takesome.springsuite.core.ai.AiCredentialStatus;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleChatProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void probesModelsAndChatsWithOllamaCompatibleEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> respond(exchange, 200, """
                {"object":"list","data":[{"id":"llama3.2:latest","object":"model"}]}
                """));
        server.createContext("/v1/chat/completions", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(requestBody).contains("\"model\":\"llama3.2\"");
            assertThat(requestBody).contains("Hello Ollama");
            respond(exchange, 200, """
                    {
                      "id":"chatcmpl-local",
                      "object":"chat.completion",
                      "model":"llama3.2:latest",
                      "choices":[{"index":0,"message":{"role":"assistant","content":"Hello from local inference"},"finish_reason":"stop"}],
                      "usage":{"prompt_tokens":4,"completion_tokens":5,"total_tokens":9}
                    }
                    """);
        });
        server.start();

        OpenAiCompatibleChatProvider provider = provider("llama3.2");

        AiCredentialStatus status = provider.status();
        assertThat(status.available()).isTrue();
        assertThat(status.metadata().get("availableModels")).isEqualTo(List.of("llama3.2:latest"));

        AiChatResponse response = provider.chat(AiChatRequest.prompt("ollama", "", "Hello Ollama"));
        assertThat(response.ok()).isTrue();
        assertThat(response.outputText()).isEqualTo("Hello from local inference");
        assertThat(response.model()).isEqualTo("llama3.2:latest");
        assertThat(response.usage().totalTokens()).isEqualTo(9);
    }

    @Test
    void reportsMissingConfiguredModel() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> respond(exchange, 200, """
                {"object":"list","data":[{"id":"gemma3:latest","object":"model"}]}
                """));
        server.start();

        AiCredentialStatus status = provider("llama3.2").status();

        assertThat(status.available()).isFalse();
        assertThat(status.message()).contains("llama3.2").contains("not installed");
    }

    private OpenAiCompatibleChatProvider provider(String model) {
        AiProperties.Provider config = new AiProperties.Provider();
        config.setName("Ollama Local");
        config.setVendor("Ollama");
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.setChatEndpoint("/chat/completions");
        config.setRequiresAuth(false);
        config.setDefaultModel(model);
        config.setRequestTimeout(Duration.ofSeconds(5));
        AiProperties.Probe probe = new AiProperties.Probe();
        probe.setEnabled(true);
        probe.setEndpoint("/models");
        probe.setTimeout(Duration.ofSeconds(2));
        probe.setCacheTtl(Duration.ofSeconds(1));
        probe.setRequireDefaultModel(true);
        config.setProbe(probe);
        return new OpenAiCompatibleChatProvider("ollama", config, new ObjectMapper(), mock(AiAuditService.class));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
