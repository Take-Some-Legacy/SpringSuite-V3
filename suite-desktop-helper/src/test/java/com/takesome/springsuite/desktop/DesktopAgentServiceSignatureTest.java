package com.takesome.springsuite.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFocusContext;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormContext;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DesktopAgentServiceSignatureTest {
    @Test
    void ignoresFocusValueGeometryAndWindowTitleChanges() {
        DesktopFocusContext initial = context(
                "Первый заголовок",
                field("dom:#phone", false, false, Map.of("bounds", Map.of("left", 10, "top", 20)))
        );
        DesktopFocusContext refreshed = context(
                "Изменившийся заголовок",
                field("dom:#phone", true, true, Map.of("bounds", Map.of("left", 800, "top", 600)))
        );

        assertThat(DesktopAgentService.stableFormSignature(refreshed))
                .isEqualTo(DesktopAgentService.stableFormSignature(initial));
    }

    @Test
    void changesIdentityWhenTheFormSchemaChanges() {
        DesktopFocusContext initial = context(
                "Анкета",
                field("dom:#phone", true, false, Map.of())
        );
        DesktopFocusContext changed = context(
                "Анкета",
                field("dom:#email", true, false, Map.of())
        );

        assertThat(DesktopAgentService.stableFormSignature(changed))
                .isNotEqualTo(DesktopAgentService.stableFormSignature(initial));
    }

    private DesktopFocusContext context(String windowTitle, DesktopFormField field) {
        return new DesktopFocusContext(
                "browser",
                "chromium-extension",
                windowTitle,
                "https://example.test/form",
                "textbox",
                field.id(),
                "",
                "",
                "",
                new DesktopFormContext(
                        "dom:#form",
                        "Application",
                        "/submit",
                        "post",
                        List.of(field),
                        Map.of()
                ),
                Map.of()
        );
    }

    private DesktopFormField field(
            String id,
            boolean focused,
            boolean valuePresent,
            Map<String, Object> metadata
    ) {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>(metadata);
        values.put("valuePresent", valuePresent);
        return new DesktopFormField(
                id,
                "Телефон",
                id.substring(id.indexOf('#') + 1),
                "text",
                "",
                "Номер телефона",
                true,
                focused,
                false,
                List.of(),
                values
        );
    }
}
