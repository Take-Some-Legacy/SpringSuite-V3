package com.takesome.springsuite.ai;

import com.takesome.springsuite.core.ai.AiChatRequest;
import com.takesome.springsuite.core.ai.AiChatResponse;
import com.takesome.springsuite.core.ai.AiCredentialStatus;
import com.takesome.springsuite.core.ai.AiProviderDescriptor;
import com.takesome.springsuite.core.ai.AiService;
import com.takesome.springsuite.core.api.SuiteApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiController {
    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @GetMapping("/api/ai/providers")
    public SuiteApiResponse<List<AiProviderDescriptor>> providers() {
        return SuiteApiResponse.ok(aiService.providers());
    }

    @GetMapping("/api/ai/default-provider")
    public SuiteApiResponse<AiProviderDescriptor> defaultProvider() {
        return SuiteApiResponse.ok(aiService.defaultProvider());
    }

    @GetMapping("/api/ai/status")
    public SuiteApiResponse<AiCredentialStatus> status(@RequestParam(name = "provider", required = false, defaultValue = "") String provider) {
        return SuiteApiResponse.ok(aiService.status(provider));
    }

    @PostMapping("/api/ai/chat")
    public SuiteApiResponse<AiChatResponse> chat(@RequestBody(required = false) Map<String, Object> body) {
        AiChatRequest request = AiHttpRequestMapper.fromMap(body == null ? Map.of() : body);
        AiChatResponse response = aiService.chat(request);
        return response.ok()
                ? SuiteApiResponse.ok(response)
                : SuiteApiResponse.failed(response.errorCode(), response.errorMessage(), response);
    }
}
