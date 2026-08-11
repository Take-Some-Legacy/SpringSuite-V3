package com.takesome.springsuite.module;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SuiteModuleDependencyResolver {
    private static final String CURRENT_SUITE_API_VERSION = SuiteModuleManifest.CURRENT_SUITE_API_VERSION;

    private SuiteModuleDependencyResolver() {
    }

    static Map<SuiteModule, SuiteModuleCompatibilityReport> resolve(List<SuiteModule> modules) {
        IdentityHashMap<SuiteModule, SuiteModuleCompatibilityReport> reports = new IdentityHashMap<>();
        LinkedHashMap<String, List<SuiteModule>> byId = new LinkedHashMap<>();
        LinkedHashMap<String, SuiteModuleManifest> manifestsById = new LinkedHashMap<>();

        for (SuiteModule module : modules) {
            SuiteModuleManifest manifest = module.manifest();
            byId.computeIfAbsent(manifest.id(), ignored -> new ArrayList<>()).add(module);
            manifestsById.putIfAbsent(manifest.id(), manifest);
        }

        Set<String> cycleIds = cycleIds(manifestsById);

        for (SuiteModule module : modules) {
            SuiteModuleManifest manifest = module.manifest();
            ArrayList<String> problems = new ArrayList<>();
            ArrayList<String> missing = new ArrayList<>();
            SuiteModuleActivationStatus status = SuiteModuleActivationStatus.ACTIVE;

            List<SuiteModuleDependency> dependencies = manifest.dependencies().stream()
                    .map(raw -> SuiteModuleDependency.parse(raw, false))
                    .toList();
            List<SuiteModuleDependency> optionalDependencies = manifest.optionalDependencies().stream()
                    .map(raw -> SuiteModuleDependency.parse(raw, true))
                    .toList();

            if (manifest.id().isBlank() || manifest.id().equals("unknown")) {
                status = SuiteModuleActivationStatus.DISABLED_INVALID_MANIFEST;
                problems.add("manifest id is missing");
            }

            if (byId.getOrDefault(manifest.id(), List.of()).size() > 1) {
                status = SuiteModuleActivationStatus.DISABLED_DUPLICATE_ID;
                problems.add("duplicate module id: " + manifest.id());
            }

            if (!isSuiteApiCompatible(manifest.suiteApiVersion())) {
                status = firstFailure(status, SuiteModuleActivationStatus.DISABLED_INCOMPATIBLE_SUITE_API);
                problems.add("suite API version " + manifest.suiteApiVersion() + " is not compatible with runtime API " + CURRENT_SUITE_API_VERSION);
            }

            if (!isIsolationSupported(manifest.isolationPolicy())) {
                status = firstFailure(status, SuiteModuleActivationStatus.DISABLED_UNSUPPORTED_ISOLATION);
                problems.add("isolation policy " + manifest.isolationPolicy() + " is not supported by this runtime");
            }

            for (SuiteModuleDependency dependency : dependencies) {
                SuiteModuleManifest dependencyManifest = manifestsById.get(dependency.moduleId());
                if (dependencyManifest == null) {
                    status = firstFailure(status, SuiteModuleActivationStatus.DISABLED_MISSING_DEPENDENCY);
                    missing.add(dependency.moduleId());
                    problems.add("missing dependency: " + dependency.raw());
                    continue;
                }
                if (dependency.hasVersionConstraint()
                        && !SuiteVersion.parse(dependencyManifest.version()).satisfies(dependency.operator(), dependency.version())) {
                    status = firstFailure(status, SuiteModuleActivationStatus.DISABLED_DEPENDENCY_VERSION_MISMATCH);
                    problems.add("dependency version mismatch: " + dependency.raw()
                            + ", actual=" + dependencyManifest.version());
                }
            }

            if (cycleIds.contains(manifest.id())) {
                status = firstFailure(status, SuiteModuleActivationStatus.DISABLED_DEPENDENCY_CYCLE);
                problems.add("dependency cycle includes module: " + manifest.id());
            }

            boolean active = status == SuiteModuleActivationStatus.ACTIVE;
            reports.put(module, new SuiteModuleCompatibilityReport(
                    manifest.id(),
                    active,
                    status,
                    problems,
                    dependencies,
                    optionalDependencies,
                    manifest.suiteApiVersion(),
                    manifest.isolationPolicy()
            ));
        }

        return reports;
    }

    private static SuiteModuleActivationStatus firstFailure(SuiteModuleActivationStatus current, SuiteModuleActivationStatus next) {
        return current == SuiteModuleActivationStatus.ACTIVE ? next : current;
    }

    private static boolean isSuiteApiCompatible(String apiVersion) {
        return CURRENT_SUITE_API_VERSION.equals(apiVersion);
    }

    private static boolean isIsolationSupported(SuiteModuleIsolationPolicy policy) {
        return policy == SuiteModuleIsolationPolicy.SHARED_CLASSPATH;
    }

    private static Set<String> cycleIds(Map<String, SuiteModuleManifest> manifestsById) {
        LinkedHashSet<String> cycles = new LinkedHashSet<>();
        LinkedHashSet<String> visiting = new LinkedHashSet<>();
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        for (String id : manifestsById.keySet()) {
            detectCycle(id, manifestsById, visiting, visited, cycles);
        }
        return cycles;
    }

    private static void detectCycle(
            String id,
            Map<String, SuiteModuleManifest> manifestsById,
            LinkedHashSet<String> visiting,
            LinkedHashSet<String> visited,
            LinkedHashSet<String> cycles
    ) {
        if (visited.contains(id)) {
            return;
        }
        if (visiting.contains(id)) {
            cycles.addAll(visiting);
            cycles.add(id);
            return;
        }
        SuiteModuleManifest manifest = manifestsById.get(id);
        if (manifest == null) {
            return;
        }
        visiting.add(id);
        for (String raw : manifest.dependencies()) {
            SuiteModuleDependency dependency = SuiteModuleDependency.parse(raw, false);
            if (manifestsById.containsKey(dependency.moduleId())) {
                detectCycle(dependency.moduleId(), manifestsById, visiting, visited, cycles);
            }
        }
        visiting.remove(id);
        visited.add(id);
    }
}
