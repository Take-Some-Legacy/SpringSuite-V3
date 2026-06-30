package com.takesome.springsuite.module;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandRegistry;
import com.takesome.springsuite.command.SuiteCommand;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;

@Service
public class SuiteModuleRegistry {
    private final CommandRegistry commandRegistry;
    private final OperatorLogService logService;
    private final Object lock = new Object();
    private final LinkedHashMap<String, LoadedModule> loadedModules = new LinkedHashMap<>();

    public SuiteModuleRegistry(CommandRegistry commandRegistry, OperatorLogService logService) {
        this.commandRegistry = commandRegistry;
        this.logService = logService;
    }

    @PostConstruct
    public void start() {
        loadModules();
    }

    public SuiteModuleSummary summary() {
        List<RegisteredSuiteModule> modules = modules();
        int active = (int) modules.stream().filter(RegisteredSuiteModule::enabled).count();
        int commands = modules.stream()
                .filter(RegisteredSuiteModule::enabled)
                .mapToInt(module -> module.commands().size())
                .sum();
        int capabilities = modules.stream()
                .filter(RegisteredSuiteModule::enabled)
                .mapToInt(module -> module.capabilities().size())
                .sum();
        LinkedHashMap<String, String> versions = new LinkedHashMap<>();
        for (RegisteredSuiteModule module : modules) {
            versions.put(module.manifest().id(), module.manifest().version());
        }
        return new SuiteModuleSummary(
                Boolean.parseBoolean(System.getProperty("suite.modules.enabled", "true")),
                modules.size(),
                active,
                modules.size() - active,
                commands,
                capabilities,
                versions
        );
    }

    public List<RegisteredSuiteModule> modules() {
        synchronized (lock) {
            return loadedModules.values().stream()
                    .map(LoadedModule::registered)
                    .sorted(Comparator.comparing(module -> module.manifest().id()))
                    .toList();
        }
    }

    public Optional<RegisteredSuiteModule> find(String moduleId) {
        synchronized (lock) {
            LoadedModule loaded = loadedModules.get(moduleId);
            return loaded == null ? Optional.empty() : Optional.of(loaded.registered());
        }
    }

    private void loadModules() {
        boolean enabled = Boolean.parseBoolean(System.getProperty("suite.modules.enabled", "true"));
        if (!enabled) {
            logService.append(OperatorLogLevel.INFO, "modules", "external module loading disabled");
            return;
        }

        ArrayList<SuiteModule> discovered = new ArrayList<>();
        ServiceLoader<SuiteModule> loader = ServiceLoader.load(SuiteModule.class);
        for (SuiteModule module : loader) {
            discovered.add(module);
        }

        Map<SuiteModule, SuiteModuleCompatibilityReport> reports = SuiteModuleDependencyResolver.resolve(discovered);

        LinkedHashMap<String, LoadedModule> next = new LinkedHashMap<>();
        for (SuiteModule module : discovered) {
            SuiteModuleManifest manifest = module.manifest();
            SuiteModuleCompatibilityReport report = reports.get(module);
            List<String> missingDependencies = report.dependencies().stream()
                    .filter(dependency -> report.problems().stream().anyMatch(problem -> problem.contains(dependency.moduleId())))
                    .map(SuiteModuleDependency::moduleId)
                    .toList();
            List<CommandDescriptor> commandDescriptors = module.commands().stream()
                    .map(SuiteCommand::descriptor)
                    .toList();
            RegisteredSuiteModule registered = new RegisteredSuiteModule(
                    manifest,
                    module.getClass().getName(),
                    report.active(),
                    report.status(),
                    report.problems(),
                    missingDependencies,
                    module.configFiles(),
                    commandDescriptors,
                    module.capabilities(),
                    module.lifecycleHooks().size(),
                    report.suiteApiVersion(),
                    report.isolationPolicy()
            );
            next.put(manifest.id(), new LoadedModule(module, registered));
        }

        synchronized (lock) {
            loadedModules.clear();
            loadedModules.putAll(next);
        }

        for (LoadedModule loaded : next.values()) {
            runLifecycle(loaded, SuiteModuleLifecyclePhase.LOADED);
            if (!loaded.registered().enabled()) {
                logService.append(OperatorLogLevel.WARN, "modules", "module disabled by compatibility resolver", Map.of(
                        "module", loaded.registered().manifest().id(),
                        "status", loaded.registered().activationStatus().name(),
                        "problems", loaded.registered().activationProblems()
                ));
                continue;
            }
            for (SuiteCommand command : loaded.module().commands()) {
                commandRegistry.register(command);
            }
            runLifecycle(loaded, SuiteModuleLifecyclePhase.COMMANDS_REGISTERED);
        }

        SuiteModuleSummary summary = summary();
        logService.append(OperatorLogLevel.INFO, "modules", "module registry loaded", Map.of(
                "discovered", summary.discoveredCount(),
                "active", summary.activeCount(),
                "commands", summary.commandCount(),
                "capabilities", summary.capabilityCount(),
                "jars", System.getProperty("suite.modules.count", "0")
        ));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() {
        snapshot().forEach(module -> runLifecycle(module, SuiteModuleLifecyclePhase.APPLICATION_READY));
    }

    @EventListener(ContextClosedEvent.class)
    public void shutdown() {
        snapshot().forEach(module -> runLifecycle(module, SuiteModuleLifecyclePhase.SHUTDOWN));
    }

    private List<LoadedModule> snapshot() {
        synchronized (lock) {
            return List.copyOf(loadedModules.values());
        }
    }

    private void runLifecycle(LoadedModule loaded, SuiteModuleLifecyclePhase phase) {
        SuiteModuleLifecycleContext context = new SuiteModuleLifecycleContext(phase, loaded.registered().manifest(), Instant.now());
        for (SuiteModuleLifecycleHook hook : loaded.module().lifecycleHooks()) {
            try {
                hook.onModuleLifecycle(context);
            } catch (Exception ex) {
                logService.append(OperatorLogLevel.WARN, "modules", "module lifecycle hook failed", Map.of(
                        "module", loaded.registered().manifest().id(),
                        "phase", phase.name(),
                        "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
                ));
            }
        }
    }

    private record LoadedModule(SuiteModule module, RegisteredSuiteModule registered) {
    }
}
