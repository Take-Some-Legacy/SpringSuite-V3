package com.takesome.springsuite.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.core.ai.AiChatRequest;
import com.takesome.springsuite.core.ai.AiChatResponse;
import com.takesome.springsuite.core.ai.AiService;
import com.takesome.springsuite.core.ai.AiUsage;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFieldPlan;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFocusContext;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormContext;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormField;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormFillPlan;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormFillRequest;
import com.takesome.springsuite.logging.OperatorLogService;
import com.takesome.springsuite.observability.SuiteTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.takesome.springsuite.toolbelt.ToolbeltService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DesktopHelperAiFillTest {
    @Test
    void generatesContextualDraftButRejectsPersonalIdentityFields() {
        DesktopHelperProperties properties = new DesktopHelperProperties();
        AiService aiService = mock(AiService.class);
        when(aiService.chat(any())).thenReturn(new AiChatResponse(
                true,
                "test-provider",
                "test-model",
                "response-1",
                """
                        {
                          "fields": [
                            {"fieldId":"dom:#motivation","value":"Хочу применять инженерный опыт в сложных проектах."},
                            {"fieldId":"dom:#comment","value":"Не должен быть принят для несфокусированного поля."},
                            {"fieldId":"dom:#email","value":"invented@example.test"}
                          ]
                        }
                        """,
                List.of(),
                AiUsage.empty(),
                "",
                "",
                Map.of()
        ));

        DesktopHelperService service = new DesktopHelperService(
                properties,
                aiService,
                mock(ToolbeltService.class),
                new ObjectMapper(),
                mock(OperatorLogService.class),
                new SuiteTelemetry(new SimpleMeterRegistry())
        );

        DesktopFormField motivation = new DesktopFormField(
                "dom:#motivation",
                "",
                "motivation",
                "textarea",
                "",
                "",
                true,
                true,
                false,
                List.of(),
                Map.of("contextPrompt", "Почему вы хотите присоединиться к проекту?")
        );
        DesktopFormField comment = new DesktopFormField(
                "dom:#comment",
                "Комментарий",
                "comment",
                "textarea",
                "",
                "Добавьте комментарий",
                false,
                false,
                false,
                List.of(),
                Map.of()
        );
        DesktopFormField email = new DesktopFormField(
                "dom:#email",
                "Email",
                "email",
                "email",
                "",
                "name@example.test",
                true,
                false,
                false,
                List.of(),
                Map.of()
        );
        DesktopFocusContext context = new DesktopFocusContext(
                "browser",
                "chromium-extension",
                "Анкета",
                "https://example.test/apply",
                "textbox",
                "motivation",
                "",
                "",
                "",
                new DesktopFormContext(
                        "dom:#form",
                        "Application",
                        "/apply",
                        "post",
                        List.of(motivation, comment, email),
                        Map.of()
                ),
                Map.of()
        );

        DesktopFormFillPlan plan = service.planFormFillWithAi(new DesktopFormFillRequest(
                context,
                "Заполни форму",
                "ru-RU",
                Map.of(),
                Map.of(),
                false
        ));

        ArgumentCaptor<AiChatRequest> requestCaptor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiService).chat(requestCaptor.capture());
        assertThat(requestCaptor.getValue().providerId()).isEqualTo("openai");
        assertThat(requestCaptor.getValue().model()).isEqualTo("gpt-5.6");

        DesktopFieldPlan motivationPlan = plan.fields().stream()
                .filter(field -> field.fieldId().equals("dom:#motivation"))
                .findFirst()
                .orElseThrow();
        DesktopFieldPlan commentPlan = plan.fields().stream()
                .filter(field -> field.fieldId().equals("dom:#comment"))
                .findFirst()
                .orElseThrow();
        DesktopFieldPlan emailPlan = plan.fields().stream()
                .filter(field -> field.fieldId().equals("dom:#email"))
                .findFirst()
                .orElseThrow();

        assertThat(motivationPlan.action()).isEqualTo("fill");
        assertThat(motivationPlan.value()).isEqualTo("Хочу применять инженерный опыт в сложных проектах.");
        assertThat(motivationPlan.needsUserReview()).isFalse();

        assertThat(commentPlan.action()).isEqualTo("leave");
        assertThat(commentPlan.value()).isBlank();

        assertThat(emailPlan.action()).isEqualTo("ask");
        assertThat(emailPlan.value()).isBlank();
        assertThat(plan.metadata()).containsEntry("fillSource", "ai");
        assertThat(plan.metadata()).containsEntry("aiGeneratedValueCount", 1);
    }
    @Test
    void reportsQuotaFailureExplicitlyForGpt56() {
        DesktopHelperProperties properties = new DesktopHelperProperties();
        AiService aiService = mock(AiService.class);
        when(aiService.chat(any())).thenReturn(AiChatResponse.failed(
                "openai",
                "gpt-5.6",
                "insufficient_quota",
                "You exceeded your current quota, please check your plan and billing details."
        ));

        DesktopHelperService service = new DesktopHelperService(
                properties,
                aiService,
                mock(ToolbeltService.class),
                new ObjectMapper(),
                mock(OperatorLogService.class),
                new SuiteTelemetry(new SimpleMeterRegistry())
        );

        DesktopFormField field = new DesktopFormField(
                "dom:#query",
                "Запрос",
                "query",
                "search",
                "",
                "Что найти?",
                true,
                true,
                false,
                List.of(),
                Map.of("contextPrompt", "Что вы хотите найти?")
        );
        DesktopFocusContext context = new DesktopFocusContext(
                "browser",
                "chromium-extension",
                "Поиск",
                "https://example.test/search",
                "textbox",
                "query",
                "",
                "",
                "",
                new DesktopFormContext(
                        "dom:#search",
                        "Search",
                        "/search",
                        "get",
                        List.of(field),
                        Map.of()
                ),
                Map.of()
        );

        DesktopFormFillPlan plan = service.planFormFillWithAi(new DesktopFormFillRequest(
                context,
                "Заполни поле",
                "ru-RU",
                Map.of(),
                Map.of(),
                false
        ));

        assertThat(plan.warnings())
                .anyMatch(message -> message.contains("Квота OpenAI API исчерпана"));
        assertThat(plan.metadata())
                .containsEntry("aiProvider", "openai")
                .containsEntry("aiModel", "gpt-5.6");
        verify(aiService).chat(any());
    }

}
