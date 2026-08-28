package com.takesome.springsuite.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import com.takesome.springsuite.workspace.fs.WorkspacePathPolicy;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class RepositoryDescriptorService {
    public static final String SCHEMA = "com.takesome.springsuite.repository.v1";
    public static final String CACHE_SCHEMA = "com.takesome.springsuite.repository-cache.v1";

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final WorkspaceProperties properties;
    private final WorkspacePathPolicy pathPolicy;
    private final OperatorLogService logService;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ScheduledExecutorService housekeepingExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "suite-repository-housekeeping");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    public RepositoryDescriptorService(
            WorkspaceProperties properties,
            WorkspacePathPolicy pathPolicy,
            OperatorLogService logService
    ) {
        this.properties = properties;
        this.pathPolicy = pathPolicy;
        this.logService = logService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureConfiguredRepositoryDescriptors() {
        if (!properties.isRepositoryDescriptorAutoCreate() && !properties.isRepositoryCacheEnabled()) {
            return;
        }
        housekeepingExecutor.schedule(
                this::runRepositoryHousekeeping,
                properties.getRepositoryHousekeepingDelay().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    @PreDestroy
    public void closeRepositoryHousekeeping() {
        housekeepingExecutor.shutdownNow();
    }

    private void runRepositoryHousekeeping() {
        long startedNanos = System.nanoTime();
        int created = 0;
        int updated = 0;
        int failed = 0;
        try {
            Set<Path> repositories = startupRepositoryRoots();
            if (properties.isRepositoryCacheEnabled() && properties.isRepositoryCacheRememberDiscovered()) {
                rememberAll(repositories, "startup-scan", false);
            }
            if (properties.isRepositoryDescriptorAutoCreate()) {
                for (Path repository : repositories) {
                    try {
                        RepositoryDescriptorResult result = ensureAtRoot(repository, false);
                        if (result.created()) {
                            created++;
                        }
                        if (result.updated()) {
                            updated++;
                        }
                    } catch (Exception ex) {
                        failed++;
                        logService.append(OperatorLogLevel.WARN, "workspace", "repository descriptor check failed", Map.of(
                                "repository", repository.toString(),
                                "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
                        ));
                    }
                }
            }
            logService.append(OperatorLogLevel.INFO, "workspace", "repository housekeeping complete", Map.of(
                    "repositories", repositories.size(),
                    "created", created,
                    "updated", updated,
                    "failed", failed,
                    "durationMs", (System.nanoTime() - startedNanos) / 1_000_000L
            ));
        } catch (Exception ex) {
            logService.append(OperatorLogLevel.ERROR, "workspace", "repository housekeeping failed", Map.of(
                    "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                    "durationMs", (System.nanoTime() - startedNanos) / 1_000_000L
            ));
        }
    }

    private Set<Path> startupRepositoryRoots() {
        LinkedHashSet<Path> repositories = new LinkedHashSet<>();
        for (Path cached : cachedRepositoryRoots()) {
            if (Files.exists(cached.resolve(".git"))) {
                repositories.add(cached);
            }
        }
        for (String configured : properties.getRepositoryCacheRoots()) {
            Path path = resolveConfiguredPath(configured);
            findRepositoryRoot(path).ifPresent(repositories::add);
        }
        return repositories.isEmpty() ? discoverRepositoryRoots() : repositories;
    }

    public RepositoryDescriptorResult read(String path) {
        Path repositoryRoot = repositoryRootFor(path);
        Path descriptorPath = descriptorPath(repositoryRoot);
        if (!Files.isRegularFile(descriptorPath)) {
            return new RepositoryDescriptorResult(false, repositoryRoot.toString(), descriptorPath.toString(), false, false, Map.of(), "repository descriptor is missing");
        }
        try {
            return new RepositoryDescriptorResult(true, repositoryRoot.toString(), descriptorPath.toString(), false, false, readMap(descriptorPath), "repository descriptor loaded");
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read repository descriptor: " + descriptorPath, ex);
        }
    }

    public RepositoryDescriptorResult ensure(String path, boolean overwrite) {
        Path repository = repositoryRootFor(path);
        RepositoryDescriptorResult result = ensureAtRoot(repository, overwrite);
        remember(repository.toString(), true, "manual-ensure");
        return result;
    }

    public RepositoryDescriptorResult remember(String path, boolean pinned, String source) {
        Path repository = repositoryRootFor(path);
        rememberRepository(repository, source == null || source.isBlank() ? "manual" : source, pinned);
        return read(repository.toString());
    }

    public RepositoryDescriptorCatalog forget(String path) {
        Path repository = repositoryRootFor(path);
        forgetRepository(repository);
        return repositories(repository.toString(), false, false);
    }

    public RepositoryDescriptorCatalog repositories(String path, boolean ensureMissing, boolean overwrite) {
        Optional<Path> current = resolveCurrentRepository(path);
        ArrayList<RepositoryDescriptorResult> results = new ArrayList<>();
        Set<Path> repositories = discoverRepositoryRoots();
        if (properties.isRepositoryCacheEnabled() && properties.isRepositoryCacheRememberDiscovered()) {
            rememberAll(repositories, "catalog-scan", false);
        }
        for (Path repository : repositories) {
            if (ensureMissing || overwrite) {
                results.add(ensureAtRoot(repository, overwrite));
                rememberRepository(repository, "catalog-ensure", false);
            } else {
                results.add(readOrMissing(repository));
            }
        }
        results.sort(Comparator.comparing(RepositoryDescriptorResult::repositoryRoot));
        List<String> cached = cachedRepositoryRoots().stream().map(Path::toString).sorted().toList();
        String currentRoot = current.map(Path::toString).orElse("");
        String currentDescriptor = current.map(this::descriptorPath).map(Path::toString).orElse("");
        return new RepositoryDescriptorCatalog(
                true,
                currentRoot,
                currentDescriptor,
                cachePath().toString(),
                results.size(),
                cached.size(),
                cached,
                results,
                current.isPresent() ? "repositories discovered; current repository resolved" : "repositories discovered; current repository not resolved"
        );
    }

    private RepositoryDescriptorResult readOrMissing(Path repository) {
        Path descriptorPath = descriptorPath(repository);
        if (!Files.isRegularFile(descriptorPath)) {
            return new RepositoryDescriptorResult(false, repository.toString(), descriptorPath.toString(), false, false, Map.of(), "repository descriptor is missing");
        }
        try {
            return new RepositoryDescriptorResult(true, repository.toString(), descriptorPath.toString(), false, false, readMap(descriptorPath), "repository descriptor loaded");
        } catch (IOException ex) {
            return new RepositoryDescriptorResult(false, repository.toString(), descriptorPath.toString(), false, false, Map.of(), ex.getMessage());
        }
    }

    private RepositoryDescriptorResult ensureAtRoot(Path repositoryRoot, boolean overwrite) {
        Path descriptorPath = descriptorPath(repositoryRoot);
        try {
            if (Files.isRegularFile(descriptorPath) && !overwrite) {
                return new RepositoryDescriptorResult(true, repositoryRoot.toString(), descriptorPath.toString(), false, false, readMap(descriptorPath), "repository descriptor already exists");
            }
            boolean existedBefore = Files.isRegularFile(descriptorPath);
            LinkedHashMap<String, Object> descriptor = descriptor(repositoryRoot, descriptorPath);
            Files.createDirectories(descriptorPath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(descriptorPath.toFile(), descriptor);
            return new RepositoryDescriptorResult(true, repositoryRoot.toString(), descriptorPath.toString(), !existedBefore, existedBefore && overwrite, descriptor, overwrite ? "repository descriptor updated" : "repository descriptor created");
        } catch (IOException ex) {
            throw new IllegalStateException("failed to write repository descriptor: " + descriptorPath, ex);
        }
    }

    private Path repositoryRootFor(String path) {
        Path start = pathPolicy.resolveSafe(path == null || path.isBlank() ? "." : path);
        if (Files.isRegularFile(start)) {
            start = start.getParent();
        }
        Path lookupStart = start;
        return findRepositoryRoot(lookupStart)
                .orElseThrow(() -> new IllegalArgumentException("no git repository found from path: " + pathPolicy.displayPath(lookupStart)));
    }

    private Optional<Path> resolveCurrentRepository(String path) {
        try {
            return Optional.of(repositoryRootFor(path == null || path.isBlank() ? "." : path));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Set<Path> discoverRepositoryRoots() {
        LinkedHashSet<Path> repositories = new LinkedHashSet<>();
        for (Path cached : cachedRepositoryRoots()) {
            if (Files.exists(cached.resolve(".git"))) {
                repositories.add(cached);
            }
        }
        for (String configured : properties.getRepositoryCacheRoots()) {
            Path path = resolveConfiguredPath(configured);
            findRepositoryRoot(path).ifPresent(repositories::add);
        }
        for (Path root : pathPolicy.allowedRoots()) {
            Path normalized = root.toAbsolutePath().normalize();
            findRepositoryRoot(normalized).ifPresent(repositories::add);
            if (Files.isDirectory(normalized)) {
                try (Stream<Path> stream = Files.find(
                        normalized,
                        properties.getRepositoryDescriptorScanDepth() <= 0 ? Integer.MAX_VALUE : properties.getRepositoryDescriptorScanDepth(),
                        (path, attributes) -> attributes.isDirectory() && Files.exists(path.resolve(".git"))
                )) {
                    stream.map(path -> path.toAbsolutePath().normalize()).forEach(repositories::add);
                } catch (IOException ex) {
                    logService.append(OperatorLogLevel.WARN, "workspace", "repository scan failed", Map.of(
                            "root", normalized.toString(),
                            "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
                    ));
                }
            }
        }
        return repositories;
    }

    private Optional<Path> findRepositoryRoot(Path start) {
        if (start == null) {
            return Optional.empty();
        }
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private Path resolveConfiguredPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return pathPolicy.runtimeRoot();
        }
        Path path = Path.of(raw.trim());
        return path.isAbsolute() ? path.normalize() : pathPolicy.runtimeRoot().resolve(path).normalize();
    }

    private Path descriptorPath(Path repositoryRoot) {
        return repositoryRoot.resolve(properties.getRepositoryDescriptorFile()).toAbsolutePath().normalize();
    }

    private Path cachePath() {
        Path configured = Path.of(properties.getRepositoryCacheFile());
        return configured.isAbsolute()
                ? configured.normalize()
                : pathPolicy.runtimeRoot().resolve(configured).toAbsolutePath().normalize();
    }

    private LinkedHashMap<String, Object> readMap(Path path) throws IOException {
        return mapper.readValue(path.toFile(), MAP_TYPE);
    }

    private LinkedHashMap<String, Object> readCache() {
        Path path = cachePath();
        if (!Files.isRegularFile(path)) {
            return newCache();
        }
        try {
            LinkedHashMap<String, Object> loaded = readMap(path);
            loaded.putIfAbsent("repositories", new ArrayList<>());
            return loaded;
        } catch (IOException ex) {
            logService.append(OperatorLogLevel.WARN, "workspace", "repository cache read failed", Map.of("path", path.toString(), "error", ex.getMessage()));
            return newCache();
        }
    }

    private LinkedHashMap<String, Object> newCache() {
        LinkedHashMap<String, Object> cache = new LinkedHashMap<>();
        cache.put("schema", CACHE_SCHEMA);
        cache.put("createdAt", Instant.now().toString());
        cache.put("updatedAt", Instant.now().toString());
        cache.put("repositories", new ArrayList<LinkedHashMap<String, Object>>());
        return cache;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cacheEntries(LinkedHashMap<String, Object> cache) {
        Object value = cache.get("repositories");
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) (List<?>) list;
        }
        ArrayList<Map<String, Object>> entries = new ArrayList<>();
        cache.put("repositories", entries);
        return entries;
    }

    private void writeCache(LinkedHashMap<String, Object> cache) {
        if (!properties.isRepositoryCacheEnabled()) {
            return;
        }
        Path path = cachePath();
        try {
            Files.createDirectories(path.getParent());
            cache.put("updatedAt", Instant.now().toString());
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), cache);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to write repository cache: " + path, ex);
        }
    }

    private List<Path> cachedRepositoryRoots() {
        if (!properties.isRepositoryCacheEnabled()) {
            return List.of();
        }
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        LinkedHashMap<String, Object> cache = readCache();
        for (Map<String, Object> entry : cacheEntries(cache)) {
            Object raw = entry.get("rootPath");
            if (raw != null && !raw.toString().isBlank()) {
                roots.add(Path.of(raw.toString()).toAbsolutePath().normalize());
            }
        }
        return List.copyOf(roots);
    }

    private void rememberAll(Set<Path> repositories, String source, boolean pinned) {
        for (Path repository : repositories) {
            rememberRepository(repository, source, pinned);
        }
    }

    private void rememberRepository(Path repository, String source, boolean pinned) {
        if (!properties.isRepositoryCacheEnabled()) {
            return;
        }
        Path normalized = repository.toAbsolutePath().normalize();
        LinkedHashMap<String, Object> cache = readCache();
        List<Map<String, Object>> entries = cacheEntries(cache);
        LinkedHashMap<String, Object> target = null;
        for (Map<String, Object> entry : entries) {
            Object raw = entry.get("rootPath");
            if (raw != null && normalized.equals(Path.of(raw.toString()).toAbsolutePath().normalize())) {
                target = new LinkedHashMap<>(entry);
                entries.remove(entry);
                break;
            }
        }
        Instant now = Instant.now();
        if (target == null) {
            target = new LinkedHashMap<>();
            target.put("rememberedAt", now.toString());
        }
        target.put("name", normalized.getFileName() == null ? normalized.toString() : normalized.getFileName().toString());
        target.put("rootPath", normalized.toString());
        target.put("gitDirectory", normalized.resolve(".git").toString());
        target.put("descriptorPath", descriptorPath(normalized).toString());
        target.put("lastSeenAt", now.toString());
        target.put("source", source == null || source.isBlank() ? "manual" : source);
        target.put("pinned", pinned || Boolean.TRUE.equals(target.get("pinned")));
        entries.add(target);
        writeCache(cache);
    }

    private void forgetRepository(Path repository) {
        if (!properties.isRepositoryCacheEnabled()) {
            return;
        }
        Path normalized = repository.toAbsolutePath().normalize();
        LinkedHashMap<String, Object> cache = readCache();
        List<Map<String, Object>> entries = cacheEntries(cache);
        entries.removeIf(entry -> {
            Object raw = entry.get("rootPath");
            return raw != null && normalized.equals(Path.of(raw.toString()).toAbsolutePath().normalize());
        });
        writeCache(cache);
    }

    private LinkedHashMap<String, Object> descriptor(Path repositoryRoot, Path descriptorPath) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("schema", SCHEMA);
        root.put("generatedAt", Instant.now().toString());
        root.put("repository", repository(repositoryRoot, descriptorPath));
        root.put("dataset", dataset());
        root.put("analysis", analysis());
        root.put("workspace", workspace(repositoryRoot));
        root.put("repositoryCache", repositoryCache());
        root.put("moduleExtraction", moduleExtraction());
        return root;
    }

    private LinkedHashMap<String, Object> repository(Path repositoryRoot, Path descriptorPath) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("name", repositoryRoot.getFileName() == null ? repositoryRoot.toString() : repositoryRoot.getFileName().toString());
        value.put("rootPath", repositoryRoot.toString());
        value.put("gitDirectory", repositoryRoot.resolve(".git").toString());
        value.put("descriptorPath", descriptorPath.toString());
        value.put("descriptorFile", properties.getRepositoryDescriptorFile());
        value.put("suiteVersion", System.getProperty("suite.version", "0.1.5"));
        return value;
    }

    private LinkedHashMap<String, Object> dataset() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("roots", List.of("src/main/java", "src/main/resources", "docs", "suite-*/src/main/java", "suite-*/src/main/resources"));
        value.put("examples", List.of("docs", "components/extensions/suite-dashboard-module/src/main/java", "components/extensions/suite-diagnostics-module/src/main/java", "components/extensions/suite-cloudflared-module/src/main/java"));
        value.put("analysisTargets", List.of("architecture", "module-boundaries", "package-structure", "public-api-surface", "runtime-configuration", "build-graph"));
        value.put("excludeGlobs", List.of(".git/**", ".gradle/**", ".idea/**", "build/**", "**/build/**", "out/**", "target/**", "node_modules/**", ".springsuite/cloudflared/**"));
        return value;
    }

    private LinkedHashMap<String, Object> analysis() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("primaryLanguage", "Java");
        value.put("buildTool", "Gradle");
        value.put("javaVersion", "17");
        value.put("compileCommand", "gradlew.bat compileJava");
        value.put("testCommand", "gradlew.bat test");
        value.put("moduleDeploymentCommand", "gradlew.bat deploySignedModules");
        value.put("preferredReportFormat", "markdown");
        return value;
    }

    private LinkedHashMap<String, Object> workspace(Path repositoryRoot) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("activeProfile", properties.getActiveProfile());
        value.put("safeRoots", pathPolicy.allowedRoots().stream().map(Path::toString).toList());
        value.put("repositoryRoot", repositoryRoot.toString());
        value.put("localRuntimeDirectory", ".springsuite");
        return value;
    }

    private LinkedHashMap<String, Object> repositoryCache() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("enabled", properties.isRepositoryCacheEnabled());
        value.put("cachePath", cachePath().toString());
        value.put("rememberDiscovered", properties.isRepositoryCacheRememberDiscovered());
        value.put("configuredRoots", properties.getRepositoryCacheRoots());
        return value;
    }

    private LinkedHashMap<String, Object> moduleExtraction() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("systemLayer", List.of("suite-core", "suite-config", "suite-logging", "suite-module", "suite-command", "suite-agent", "suite-workspace", "suite-toolbelt"));
        value.put("featureModules", List.of("suite-dashboard-module", "suite-diagnostics-module", "suite-cloudflared-module"));
        value.put("nextSteps", List.of("Add web-extension SPI for REST controllers in external signed SuiteModule jars", "Move suite-cloudflared-module from direct app dependency into signed runtime module deployment", "Keep cloudflared runtime cache under .springsuite/cloudflared"));
        return value;
    }
}
