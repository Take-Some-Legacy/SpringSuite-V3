package com.takesome.springsuite.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomCommandAckRequest;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovedAction;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFocusContext;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormContext;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormField;
import com.takesome.springsuite.logging.OperatorLogService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BrowserDomCommandServiceTest {
    private BrowserDomProperties properties;
    private BrowserDomCommandService service;

    @BeforeEach
    void setUp() {
        properties = new BrowserDomProperties();
        service = new BrowserDomCommandService(properties, mock(OperatorLogService.class));
    }

    @Test
    void queuesPageBoundCommandAndAcknowledgesIt() {
        DesktopSnapshot snapshot = snapshot(false);
        var command = service.enqueue(snapshot, List.of(action("Kayla")));

        assertThat(command.pageId()).isEqualTo("page-1");
        assertThat(command.pageUrl()).isEqualTo("https://example.test/profile");
        assertThat(command.preserveExistingValues()).isTrue();
        assertThat(command.allowSubmit()).isFalse();
        assertThat(command.fields()).singleElement().satisfies(field -> {
            assertThat(field.selector()).isEqualTo("#name");
            assertThat(field.value()).isEqualTo("Kayla");
        });

        assertThat(service.next("page-1", "https://example.test/profile?token=drop#fragment")).contains(command);
        assertThat(service.next("another-page", "https://example.test/profile")).isEmpty();

        var ack = service.acknowledge(command.commandId(), new BrowserDomCommandAckRequest(
                "page-1",
                "https://example.test/profile?anything=drop",
                true,
                1,
                0,
                0,
                List.of(),
                Map.of("submitPerformed", false)
        ));
        assertThat(ack.ok()).isTrue();
        assertThat(ack.filledCount()).isEqualTo(1);
        assertThat(service.next("page-1", "https://example.test/profile")).isEmpty();
    }

    @Test
    void refusesToOverwriteExistingPageValue() {
        assertThatThrownBy(() -> service.enqueue(snapshot(true), List.of(action("Kayla"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No safe browser fields");
    }

    @Test
    void respectsWriteEnabledConfiguration() {
        properties.setWriteEnabled(false);

        assertThatThrownBy(() -> service.enqueue(snapshot(false), List.of(action("Kayla"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }

    private DesktopApprovedAction action(String value) {
        return new DesktopApprovedAction(
                "fill:dom:#name",
                "fill",
                "dom:#name",
                "Имя",
                value,
                true,
                false,
                false,
                "local profile",
                Map.of("cssSelector", "#name", "browserDom", true)
        );
    }

    private DesktopSnapshot snapshot(boolean valuePresent) {
        DesktopFormField field = new DesktopFormField(
                "dom:#name",
                "Имя",
                "name",
                "text",
                "",
                "Имя",
                true,
                true,
                false,
                List.of(),
                Map.of(
                        "cssSelector", "#name",
                        "visible", true,
                        "disabled", false,
                        "readOnly", false,
                        "valuePresent", valuePresent
                )
        );
        DesktopFormContext form = new DesktopFormContext(
                "dom:#profile",
                "Profile",
                "https://example.test/profile",
                "post",
                List.of(field),
                Map.of("browserDom", true)
        );
        DesktopFocusContext context = new DesktopFocusContext(
                "web",
                "chromium-extension",
                "Profile",
                "https://example.test/profile?secret=drop#fragment",
                "textbox",
                "Имя",
                "",
                "",
                "Имя",
                form,
                Map.of("pageId", "page-1")
        );
        Instant now = Instant.now();
        return new DesktopSnapshot(
                "snapshot-1",
                BrowserDomService.SOURCE,
                now,
                now,
                now.plusSeconds(30),
                false,
                context,
                Map.of()
        );
    }
}
