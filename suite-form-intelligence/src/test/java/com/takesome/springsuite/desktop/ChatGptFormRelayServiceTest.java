package com.takesome.springsuite.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.takesome.springsuite.desktop.ChatGptFormRelayService.RelayResult;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFocusContext;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormContext;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormField;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatGptFormRelayServiceTest {
    @Test
    void publishesPrivacyFilteredFieldsAndAcceptsOrdinaryDrafts() {
        ChatGptFormRelayService service = new ChatGptFormRelayService();
        DesktopSnapshot snapshot = snapshot();

        Map<String, Object> published = service.publish("signature-1", snapshot, "ru-RU");

        assertThat(published)
                .containsEntry("available", true)
                .containsEntry("status", "waiting");
        assertThat((List<?>) published.get("fields")).hasSize(3);

        String relayId = String.valueOf(published.get("relayId"));
        Map<String, Object> result = service.submit(Map.of(
                "relayId", relayId,
                "fields", List.of(
                        Map.of("fieldId", "dom:#query", "value", "Nickelback San Quentin"),
                        Map.of("fieldId", "dom:#password", "value", "do-not-accept"),
                        Map.of("fieldId", "dom:#existing", "value", "overwrite")
                ),
                "summary", "Search query prepared."
        ));

        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("code", "relay_ready")
                .containsEntry("valueCount", 1);

        RelayResult ready = service.readyFor("signature-1").orElseThrow();
        assertThat(ready.profile()).containsOnly(Map.entry("dom:#query", "Nickelback San Quentin"));
    }

    @Test
    void rejectsUnknownRelayAndExpiredOrConsumedState() {
        ChatGptFormRelayService service = new ChatGptFormRelayService();
        service.publish("signature-1", snapshot(), "ru-RU");

        Map<String, Object> mismatch = service.submit(Map.of(
                "relayId", "other",
                "values", Map.of("dom:#query", "value")
        ));
        assertThat(mismatch).containsEntry("ok", false).containsEntry("code", "relay_mismatch");

        service.markConsumed("signature-1");
        assertThat(service.readyFor("signature-1")).isEmpty();
    }

    private DesktopSnapshot snapshot() {
        DesktopFormField query = new DesktopFormField(
                "dom:#query", "Поиск", "query", "search", "", "Что найти?",
                true, true, false, List.of(),
                Map.of("visible", true, "valuePresent", false, "contextPrompt", "Что вы хотите найти?")
        );
        DesktopFormField password = new DesktopFormField(
                "dom:#password", "Пароль", "password", "password", "", "Пароль",
                true, false, true, List.of(),
                Map.of("visible", true, "valuePresent", false)
        );
        DesktopFormField existing = new DesktopFormField(
                "dom:#existing", "Текущее", "existing", "text", "", "",
                false, false, false, List.of(),
                Map.of("visible", true, "valuePresent", true)
        );
        DesktopFocusContext context = new DesktopFocusContext(
                "browser", "chromium-extension", "Search", "https://example.test/search",
                "textbox", "query", "", "", "",
                new DesktopFormContext(
                        "dom:#search", "Search", "/search", "get",
                        List.of(query, password, existing), Map.of()
                ),
                Map.of("pageId", "page-1")
        );
        Instant now = Instant.now();
        return new DesktopSnapshot(
                "snapshot-1", "browser-dom-extension", now, now, now.plusSeconds(30), false, context, Map.of()
        );
    }
}
