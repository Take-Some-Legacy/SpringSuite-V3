package com.takesome.springsuite.toolbelt.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.takesome.springsuite.toolbelt.ToolDescriptor;
import com.takesome.springsuite.toolbelt.search.ToolSearchEngine;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolbeltCatalogTest {
    private final ToolSearchEngine searchEngine = new ToolSearchEngine();

    @Test
    void keepsPreviousPublishedGenerationCallableDuringRefresh() {
        ToolbeltCatalog catalog = new ToolbeltCatalog();
        ToolDescriptor git = descriptor("path.git");
        ToolDescriptor java = descriptor("path.java");

        catalog.replace(Map.of(git.id(), git), List.of(), List.of("tools"), Instant.now());
        assertThat(catalog.find("tool_path_git", searchEngine)).contains(git);

        catalog.replace(Map.of(java.id(), java), List.of(), List.of("tools"), Instant.now());

        assertThat(catalog.listTools()).containsExactly(java);
        assertThat(catalog.find("tool_path_git", searchEngine)).contains(git);
        assertThat(catalog.find("tool_path_java", searchEngine)).contains(java);
    }

    @Test
    void boundsRetiredRegistryHistory() {
        ToolbeltCatalog catalog = new ToolbeltCatalog();
        for (int i = 0; i < 6; i++) {
            ToolDescriptor descriptor = descriptor("path.tool" + i);
            catalog.replace(Map.of(descriptor.id(), descriptor), List.of(), List.of(), Instant.now());
        }

        assertThat(catalog.find("tool_path_tool0", searchEngine)).isEmpty();
        assertThat(catalog.find("tool_path_tool1", searchEngine)).isPresent();
        assertThat(catalog.find("tool_path_tool5", searchEngine)).isPresent();
    }

    private ToolDescriptor descriptor(String id) {
        return new ToolDescriptor(
                id,
                id,
                id,
                "path",
                "external-cli",
                "",
                "",
                "C:/tools/" + id + ".exe",
                List.of("C:/tools/" + id + ".exe"),
                List.of(),
                List.of(),
                "",
                "host",
                "",
                "path",
                "",
                "",
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                "available",
                false,
                Map.of()
        );
    }
}
