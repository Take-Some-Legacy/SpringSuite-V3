package com.takesome.springsuite.toolbelt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import com.takesome.springsuite.toolbelt.discovery.ToolDescriptorScanner;
import com.takesome.springsuite.toolbelt.discovery.ToolDiscoveryResult;
import com.takesome.springsuite.toolbelt.execution.ToolProcessRunner;
import com.takesome.springsuite.toolbelt.inventory.ToolbeltInventoryFactory;
import com.takesome.springsuite.toolbelt.search.ToolSearchEngine;
import com.takesome.springsuite.toolbelt.state.ToolbeltCatalog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

@Service
public class ToolbeltService {
    private final ToolbeltProperties properties;
    private final OperatorLogService logService;
    private final ToolbeltCatalog catalog = new ToolbeltCatalog();
    private final ToolSearchEngine searchEngine = new ToolSearchEngine();
    private final ToolbeltInventoryFactory inventoryFactory = new ToolbeltInventoryFactory(searchEngine);
    private final ToolDescriptorScanner descriptorScanner;
    private final ToolProcessRunner processRunner;
    private final ExecutorService warmupExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "suite-toolbelt-warmup");
        thread.setDaemon(true);
        return thread;
    });

    public ToolbeltService(
            ToolbeltProperties properties,
            OperatorLogService logService,
            ObjectMapper objectMapper,
            DescriptorToolRuntime descriptorRuntime
    ) {
        this.properties = properties;
        this.logService = logService;
        this.descriptorScanner = new ToolDescriptorScanner(properties, logService, objectMapper, descriptorRuntime);
        this.processRunner = new ToolProcessRunner(properties, descriptorRuntime);
    }

    @PostConstruct
    public void init() {
        // Do not hold Spring startup on filesystem/PATH discovery or validation probes.
        // Until the first atomic publish completes MCP advertises only its built-in routes.
        catalog.clear(Instant.now());
        warmupExecutor.submit(() -> {
            try {
                refresh();
            } catch (Exception ex) {
                logService.append(OperatorLogLevel.ERROR, "toolbelt", "toolbelt asynchronous warmup failed", Map.of(
                        "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
                ));
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        warmupExecutor.shutdownNow();
    }

    public ToolbeltSummary summary() {
        return inventoryFactory.summary(properties.isEnabled(), catalog.scannedAt(), catalog.listTools());
    }

    public ToolInventory inventory() {
        return inventoryFactory.inventory(
                properties.isEnabled(),
                catalog.scannedAt(),
                catalog.listTools(),
                catalog.resolvedRoots(),
                catalog.diagnostics()
        );
    }

    public List<ToolDescriptor> listTools() {
        return catalog.listTools();
    }

    public List<ToolIndexEntry> index() {
        return searchEngine.index(listTools());
    }

    public List<ToolDescriptor> search(String query, int limit, String source, String kind, Boolean available, String tag) {
        return searchEngine.search(query, limit, source, kind, available, tag, listTools());
    }

    public Optional<ToolDescriptor> find(String idOrName) {
        return catalog.find(idOrName, searchEngine);
    }

    public synchronized ToolbeltSummary refresh() {
        if (!properties.isEnabled()) {
            catalog.clear(Instant.now());
            return summary();
        }

        ToolDiscoveryResult discovery = descriptorScanner.discover();
        LinkedHashMap<String, ToolDescriptor> validated = new LinkedHashMap<>();
        ArrayList<String> diagnostics = new ArrayList<>(discovery.diagnostics());
        for (ToolDescriptor descriptor : discovery.tools().values()) {
            String validationFailure = properties.isValidateBeforePublish() ? processRunner.validate(descriptor) : "";
            if (validationFailure.isBlank()) {
                validated.put(descriptor.id(), descriptor);
            } else {
                validated.put(descriptor.id(), withAvailability(descriptor, false, validationFailure));
                diagnostics.add("tool validation failed: " + descriptor.id() + " -> " + validationFailure);
            }
        }

        // The catalog swap is the publication boundary. Readers never observe a half-built registry.
        catalog.replace(validated, diagnostics, discovery.resolvedRoots(), Instant.now());
        ToolbeltSummary summary = summary();
        logService.append(OperatorLogLevel.INFO, "toolbelt", "toolbelt scan complete", Map.of(
                "generation", catalog.generation(),
                "count", summary.count(),
                "available", summary.availableCount(),
                "unavailable", summary.unavailableCount(),
                "diagnostics", diagnostics.size()
        ));
        return summary;
    }

    public ToolRunResult run(ToolRunRequest request) {
        return processRunner.run(find(request.toolId()).orElse(null), request);
    }

    private ToolDescriptor withAvailability(ToolDescriptor descriptor, boolean available, String message) {
        return new ToolDescriptor(
                descriptor.id(),
                descriptor.name(),
                descriptor.title(),
                descriptor.source(),
                descriptor.kind(),
                descriptor.description(),
                descriptor.descriptorPath(),
                descriptor.executable(),
                descriptor.commandTemplate(),
                descriptor.safeCommandIds(),
                descriptor.tags(),
                descriptor.schema(),
                descriptor.owner(),
                descriptor.maturity(),
                descriptor.sourceType(),
                descriptor.root(),
                descriptor.packageRoot(),
                descriptor.sourceRoot(),
                descriptor.cargoManifest(),
                descriptor.installPath(),
                descriptor.defaultArgs(),
                descriptor.validationArgs(),
                descriptor.capabilities(),
                descriptor.formats(),
                descriptor.contentKinds(),
                available,
                message,
                descriptor.alwaysWrite(),
                descriptor.raw()
        );
    }
}
