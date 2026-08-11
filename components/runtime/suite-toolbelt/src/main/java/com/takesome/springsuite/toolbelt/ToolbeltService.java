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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        refresh();
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

    public ToolbeltSummary refresh() {
        if (!properties.isEnabled()) {
            catalog.clear(Instant.now());
            return summary();
        }

        ToolDiscoveryResult discovery = descriptorScanner.discover();
        catalog.replace(discovery.tools(), discovery.diagnostics(), discovery.resolvedRoots(), Instant.now());
        ToolbeltSummary summary = summary();
        logService.append(OperatorLogLevel.INFO, "toolbelt", "toolbelt scan complete", Map.of(
                "count", summary.count(),
                "available", summary.availableCount(),
                "unavailable", summary.unavailableCount(),
                "diagnostics", discovery.diagnostics().size()
        ));
        return summary;
    }

    public ToolRunResult run(ToolRunRequest request) {
        return processRunner.run(find(request.toolId()).orElse(null), request);
    }
}
