package com.takesome.springsuite.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomField;
import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomForm;
import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomIngestResult;
import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomSnapshotRequest;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshotResult;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFocusContext;
import com.takesome.springsuite.logging.OperatorLogService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BrowserDomServiceTest {
    private BrowserDomProperties properties;
    private DesktopBridgeService bridgeService;
    private DesktopAgentService agentService;
    private BrowserDomService service;

    @BeforeEach
    void setUp() {
        properties = new BrowserDomProperties();
        properties.setRequireToken(false);
        bridgeService = mock(DesktopBridgeService.class);
        agentService = mock(DesktopAgentService.class);
        OperatorLogService logService = mock(OperatorLogService.class);
        service = new BrowserDomService(properties, bridgeService, agentService, logService);
    }

    @Test
    void recognizesFormWithoutForwardingFieldValues() {
        Instant now = Instant.now();
        DesktopSnapshot snapshot = new DesktopSnapshot(
                "snapshot-1",
                BrowserDomService.SOURCE,
                now,
                now,
                now.plusSeconds(30),
                false,
                DesktopFocusContext.empty(),
                Map.of()
        );
        when(bridgeService.ingest(any())).thenReturn(DesktopSnapshotResult.ok("ok", snapshot, List.of(), Map.of()));

        BrowserDomIngestResult result = service.ingest(request(now), "", "chrome-extension://test");

        assertThat(result.ok()).isTrue();
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bridgeService).ingest(captor.capture());
        Map<String, Object> ingest = captor.getValue();
        Map<String, Object> raw = map(ingest.get("snapshot"));
        Map<String, Object> form = map(raw.get("form"));
        List<?> fields = (List<?>) form.get("fields");
        Map<String, Object> field = map(fields.get(0));
        assertThat(field).doesNotContainKey("value");
        assertThat(field.get("valuePresent")).isEqualTo(true);
        assertThat(field.get("required")).isEqualTo(true);
        assertThat(form.get("action")).isEqualTo("https://example.test/submit");
        assertThat(form.get("method")).isEqualTo("post");
        verify(agentService).acceptExternalSnapshot(snapshot);
    }

    @Test
    void rejectsStaleSnapshotBeforeBridgeIngest() {
        BrowserDomIngestResult result = service.ingest(request(Instant.now().minusSeconds(60)), "", "chrome-extension://test");

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("browser_dom_snapshot_stale");
        verify(bridgeService, never()).ingest(any());
    }

    @Test
    void enforcesConfiguredToken() {
        properties.setRequireToken(true);
        properties.setToken("expected-token");

        BrowserDomIngestResult result = service.ingest(request(Instant.now()), "wrong-token", "chrome-extension://test");

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("browser_dom_unauthorized");
        verify(bridgeService, never()).ingest(any());
    }

    private BrowserDomSnapshotRequest request(Instant capturedAt) {
        BrowserDomField field = new BrowserDomField(
                "dom:#email",
                "Email",
                "email",
                "email",
                "textbox",
                "name@example.test",
                true,
                true,
                false,
                false,
                false,
                true,
                true,
                List.of(),
                Map.of("cssSelector", "#email", "bounds", Map.of("left", 10, "top", 20, "right", 210, "bottom", 50))
        );
        BrowserDomForm form = new BrowserDomForm(
                "dom:#account-form",
                "Account",
                "/submit",
                "post",
                true,
                List.of(field),
                List.of(),
                Map.of("cssSelector", "#account-form")
        );
        return new BrowserDomSnapshotRequest(
                "spring-suite.browser_dom_snapshot.v1",
                "page-1",
                capturedAt.toString(),
                "https://example.test/account",
                "Account",
                "en",
                "chromium-extension",
                "#email",
                List.of(form),
                Map.of()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
