package com.takesome.springsuite.openai;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/openai")
public class OpenAiController {
    private final OpenAiTokenProvider tokenProvider;
    private final OpenAiClient client;

    public OpenAiController(OpenAiTokenProvider tokenProvider, OpenAiClient client) {
        this.tokenProvider = tokenProvider;
        this.client = client;
    }

    @GetMapping("/status")
    public SuiteApiResponse<OpenAiCredentialStatus> status() {
        return SuiteApiResponse.ok(tokenProvider.status());
    }

    @PostMapping("/auth/refresh")
    public SuiteApiResponse<OpenAiCredentialStatus> refresh() {
        try {
            return SuiteApiResponse.ok("OpenAI application token refreshed", tokenProvider.refresh());
        } catch (RuntimeException ex) {
            return SuiteApiResponse.failed("openai_refresh_failed", safeMessage(ex), tokenProvider.status());
        }
    }

    @PostMapping("/responses")
    public SuiteApiResponse<OpenAiResponseResult> createResponse(@RequestBody OpenAiResponseRequest request) {
        OpenAiResponseResult result = client.createResponse(request);
        if (result.ok()) {
            return SuiteApiResponse.ok(result);
        }
        return SuiteApiResponse.failed("openai_response_failed", result.errorMessage(), result);
    }

    private String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown error";
        }
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
