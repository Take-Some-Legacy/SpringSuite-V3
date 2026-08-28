package com.takesome.springsuite.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.takesome.springsuite.workspace.fs.WorkspacePathPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSearchEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void prunesDeniedDirectoriesBeforeScanningFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve("build/deep/generated"));
        Files.writeString(tempDir.resolve("src/visible.txt"), "ordinary content\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("build/deep/generated/poison.txt"), "ForwardOpaque\n", StandardCharsets.UTF_8);

        WorkspaceSearchResult result = engine(properties()).search("ForwardOpaque", tempDir.toString(), 200, false, false);

        assertThat(result.count()).isZero();
        assertThat(result.matches()).isEmpty();
    }

    @Test
    void enforcesConfiguredMatchBoundAndMarksResultTruncated() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "ForwardOpaque A\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("b.txt"), "ForwardOpaque B\n", StandardCharsets.UTF_8);
        WorkspaceProperties properties = properties();
        properties.setMaxSearchResults(1);

        WorkspaceSearchResult result = engine(properties).search("ForwardOpaque", tempDir.toString(), 0, false, false);

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.truncated()).isTrue();
        assertThat(result.matches()).hasSize(1);
    }

    @Test
    void enforcesFilesystemWorkBudgetEvenWhenQueryDoesNotMatch() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "alpha\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("b.txt"), "beta\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("c.txt"), "gamma\n", StandardCharsets.UTF_8);
        WorkspaceProperties properties = properties();
        properties.setMaxSearchFiles(1);

        WorkspaceSearchResult result = engine(properties).search("ForwardOpaque", tempDir.toString(), 0, false, false);

        assertThat(result.count()).isZero();
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void productionDefaultsAreBounded() {
        WorkspaceProperties properties = new WorkspaceProperties();

        assertThat(properties.getMaxReadBytes()).isEqualTo(2 * 1024 * 1024);
        assertThat(properties.getMaxSearchResults()).isEqualTo(200);
        assertThat(properties.getMaxTreeItems()).isEqualTo(2000);
        assertThat(properties.getMaxFileSizeBytes()).isEqualTo(8L * 1024L * 1024L);
        assertThat(properties.getMaxSearchFiles()).isEqualTo(25_000);
        assertThat(properties.getMaxSearchBytes()).isEqualTo(256L * 1024L * 1024L);
        assertThat(properties.getMaxSearchDuration()).isEqualTo(Duration.ofSeconds(8));
        assertThat(properties.getRepositoryHousekeepingDelay()).isEqualTo(Duration.ofSeconds(30));
    }

    private WorkspaceProperties properties() {
        WorkspaceProperties properties = new WorkspaceProperties();
        properties.setRoots(java.util.List.of(tempDir.toString()));
        properties.setMaxReadBytes(2 * 1024 * 1024);
        properties.setMaxSearchResults(200);
        properties.setMaxTreeItems(2000);
        properties.setMaxFileSizeBytes(8L * 1024L * 1024L);
        properties.setMaxSearchFiles(25_000);
        properties.setMaxSearchBytes(256L * 1024L * 1024L);
        properties.setMaxSearchDuration(Duration.ofSeconds(8));
        return properties;
    }

    private WorkspaceSearchEngine engine(WorkspaceProperties properties) {
        WorkspacePathPolicy pathPolicy = new WorkspacePathPolicy(properties);
        WorkspaceTextFilePolicy textFilePolicy = new WorkspaceTextFilePolicy(properties, pathPolicy);
        return new WorkspaceSearchEngine(properties, pathPolicy, textFilePolicy);
    }
}
