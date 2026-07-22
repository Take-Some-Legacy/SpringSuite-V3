package com.takesome.springsuite.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.core.ai.AiService;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFocusContext;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormContext;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormField;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormFillPlan;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormFillRequest;
import com.takesome.springsuite.logging.OperatorLogService;
import com.takesome.springsuite.observability.SuiteTelemetry;
import com.takesome.springsuite.toolbelt.ToolbeltService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DesktopFormPlanCacheTest {
    @Test
    void reusesOnlyDeterministicLocalPlanWithinShortTtl() {
        DesktopHelperProperties properties = new DesktopHelperProperties();
        properties.setAiEnrichmentEnabled(false);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DesktopHelperService service = new DesktopHelperService(
                properties,
                mock(AiService.class),
                mock(ToolbeltService.class),
                new ObjectMapper(),
                mock(OperatorLogService.class),
                new SuiteTelemetry(registry)
        );
        DesktopFormFillRequest request = request();

        DesktopFormFillPlan first = service.planFormFill(request);
        DesktopFormFillPlan second = service.planFormFill(request);

        assertThat(second).isSameAs(first);
        Counter hit = registry.find("springsuite.events")
                .tags("subsystem", "form_intelligence", "event", "plan_cache", "outcome", "hit")
                .counter();
        assertThat(hit).isNotNull();
        assertThat(hit.count()).isEqualTo(1.0);
    }

    private DesktopFormFillRequest request() {
        DesktopFormField field = new DesktopFormField(
                "dom:#query", "Поиск", "query", "search", "", "Что найти?",
                true, true, false, List.of(),
                Map.of("visible", true, "valuePresent", false)
        );
        DesktopFocusContext context = new DesktopFocusContext(
                "browser", "chromium-extension", "Search", "https://example.test/search",
                "textbox", "query", "", "", "",
                new DesktopFormContext(
                        "dom:#search", "Search", "/search", "get", List.of(field), Map.of()
                ),
                Map.of(SuiteTelemetry.CORRELATION_ID, "test-correlation")
        );
        return new DesktopFormFillRequest(
                context,
                "Заполни поиск",
                "ru-RU",
                Map.of("query", "Nickelback San Quentin"),
                Map.of(),
                false
        );
    }
}
