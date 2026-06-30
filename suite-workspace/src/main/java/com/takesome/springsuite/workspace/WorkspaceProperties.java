package com.takesome.springsuite.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.workspace")
public class WorkspaceProperties {
    private boolean enabled = true;
    private String activeProfile = "";
    private List<String> roots = new ArrayList<>(List.of("."));
    private boolean allowRead = true;
    private boolean allowWrite = true;
    private boolean allowDelete = false;
    private boolean createBackups = true;
    private int maxReadBytes = 65_536;
    private int maxSearchResults = 100;
    private int maxTreeItems = 500;
    private long maxFileSizeBytes = 2_097_152;
    private List<String> denySegments = new ArrayList<>(List.of(
            ".git", ".gradle", ".idea", "build", "out", "target", "node_modules", "__pycache__"
    ));
    private List<String> denyGlobs = new ArrayList<>(List.of(
            "**/cache/**", "**/*.ulog.ndjson", "**/profiler_report_*.zip", "**/target/**", "**/build/**"
    ));
    private Map<String, WorkspaceProfileProperties> profiles = new LinkedHashMap<>();
    private List<String> textExtensions = new ArrayList<>(List.of(
            ".java", ".kt", ".kts", ".gradle", ".xml", ".yml", ".yaml", ".json", ".md", ".txt",
            ".properties", ".toml", ".ini", ".bat", ".cmd", ".ps1", ".sh", ".py", ".rs", ".js", ".ts",
            ".css", ".html", ".gitignore"
    ));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getActiveProfile() { return activeProfile; }

    public void setActiveProfile(String value) { this.activeProfile = value == null ? "" : value.trim(); }

    public List<String> getRoots() {
        return roots;
    }

    public void setRoots(List<String> roots) {
        this.roots = roots == null ? new ArrayList<>() : new ArrayList<>(roots);
    }

    public boolean isAllowRead() {
        return allowRead;
    }

    public void setAllowRead(boolean allowRead) {
        this.allowRead = allowRead;
    }

    public boolean isAllowWrite() {
        return allowWrite;
    }

    public void setAllowWrite(boolean allowWrite) {
        this.allowWrite = allowWrite;
    }

    public boolean isAllowDelete() {
        return allowDelete;
    }

    public void setAllowDelete(boolean allowDelete) {
        this.allowDelete = allowDelete;
    }

    public boolean isCreateBackups() {
        return createBackups;
    }

    public void setCreateBackups(boolean createBackups) {
        this.createBackups = createBackups;
    }

    public int getMaxReadBytes() {
        return maxReadBytes;
    }

    public void setMaxReadBytes(int maxReadBytes) {
        this.maxReadBytes = Math.max(1024, maxReadBytes);
    }

    public int getMaxSearchResults() {
        return maxSearchResults;
    }

    public void setMaxSearchResults(int maxSearchResults) {
        this.maxSearchResults = Math.max(1, maxSearchResults);
    }

    public int getMaxTreeItems() {
        return maxTreeItems;
    }

    public void setMaxTreeItems(int maxTreeItems) {
        this.maxTreeItems = Math.max(1, maxTreeItems);
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = Math.max(1024, maxFileSizeBytes);
    }

    public List<String> getDenySegments() {
        return denySegments;
    }

    public void setDenySegments(List<String> denySegments) {
        this.denySegments = denySegments == null ? new ArrayList<>() : new ArrayList<>(denySegments);
    }

    public List<String> getDenyGlobs() { return denyGlobs; }

    public void setDenyGlobs(List<String> value) { this.denyGlobs = value == null ? new ArrayList<>() : new ArrayList<>(value); }

    public Map<String, WorkspaceProfileProperties> getProfiles() { return profiles; }

    public void setProfiles(Map<String, WorkspaceProfileProperties> value) { this.profiles = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value); }

    public List<String> getTextExtensions() {
        return textExtensions;
    }

    public void setTextExtensions(List<String> textExtensions) {
        this.textExtensions = textExtensions == null ? new ArrayList<>() : new ArrayList<>(textExtensions);
    }

    public WorkspaceProfileProperties activeProfileProperties() {
        if (activeProfile == null || activeProfile.isBlank()) { return null; }
        return profiles.get(activeProfile.trim());
    }

    public List<String> effectiveRoots() {
        WorkspaceProfileProperties profile = activeProfileProperties();
        return profile != null && !profile.getRoots().isEmpty() ? profile.getRoots() : roots;
    }

    public boolean effectiveAllowRead() {
        WorkspaceProfileProperties profile = activeProfileProperties();
        return profile != null && profile.getAllowRead() != null ? profile.getAllowRead() : allowRead;
    }

    public boolean effectiveAllowWrite() {
        WorkspaceProfileProperties profile = activeProfileProperties();
        return profile != null && profile.getAllowWrite() != null ? profile.getAllowWrite() : allowWrite;
    }

    public boolean effectiveAllowDelete() {
        WorkspaceProfileProperties profile = activeProfileProperties();
        return profile != null && profile.getAllowDelete() != null ? profile.getAllowDelete() : allowDelete;
    }

    public List<String> effectiveDenySegments() {
        LinkedHashSet<String> merged = new LinkedHashSet<>(denySegments);
        WorkspaceProfileProperties profile = activeProfileProperties();
        if (profile != null) { merged.addAll(profile.getDenySegments()); }
        return List.copyOf(merged);
    }

    public List<String> effectiveDenyGlobs() {
        LinkedHashSet<String> merged = new LinkedHashSet<>(denyGlobs);
        WorkspaceProfileProperties profile = activeProfileProperties();
        if (profile != null) { merged.addAll(profile.getDenyGlobs()); }
        return List.copyOf(merged);
    }

    public List<String> availableProfiles() {
        return profiles.keySet().stream().sorted().toList();
    }
}
