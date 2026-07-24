package com.takesome.springsuite.module;

import com.takesome.springsuite.core.mode.SuiteOperatorMode;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SuiteModuleBootstrap {
    private SuiteModuleBootstrap() {
    }

    public static SuiteModuleBootstrapResult bootstrap() {
        SuiteCryptoProviderBootstrap.installBouncyCastleProvider();
        Path runtimeRoot = ModuleBootstrapPaths.resolveRuntimeRoot();
        ModuleBootstrapConfig config = ModuleBootstrapConfigReader.read(runtimeRoot);

        try {
            Files.createDirectories(config.modulesDir());
            boolean effectiveEnabled = config.enabled() || SuiteOperatorMode.isElevated();
            boolean effectiveRecursive = config.recursive() || SuiteOperatorMode.isElevated();
            List<Path> discoveredJars = effectiveEnabled ? ModuleJarScanner.scan(config.modulesDir(), effectiveRecursive) : List.of();
            if (effectiveEnabled) {
                ModuleJarUniqueness.requireUnique(discoveredJars);
            }
            List<SuiteModuleJarTrustReport> trustReports = discoveredJars.stream()
                    .map(jar -> ModuleJarTrustReporter.report(jar, config))
                    .toList();
            List<Path> loadableJars = SuiteOperatorMode.isElevated()
                    ? discoveredJars
                    : trustReports.stream()
                            .filter(SuiteModuleJarTrustReport::loadAllowed)
                            .map(SuiteModuleJarTrustReport::path)
                            .toList();

            URLClassLoader moduleClassLoader = createModuleClassLoader(loadableJars);
            Thread.currentThread().setContextClassLoader(moduleClassLoader);
            ModuleBootstrapSystemProperties.publish(runtimeRoot, config, discoveredJars, loadableJars, trustReports);

            return new SuiteModuleBootstrapResult(runtimeRoot, config.modulesDir(), effectiveEnabled, effectiveRecursive, loadableJars, trustReports, moduleClassLoader);
        } catch (IOException ex) {
            throw new IllegalStateException("SpringSuite module bootstrap failed: " + config.modulesDir(), ex);
        }
    }

    private static URLClassLoader createModuleClassLoader(List<Path> loadableJars) throws IOException {
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        if (parent == null) {
            parent = SuiteModuleBootstrap.class.getClassLoader();
        }
        URL[] urls = new URL[loadableJars.size()];
        for (int i = 0; i < loadableJars.size(); i++) {
            urls[i] = loadableJars.get(i).toUri().toURL();
        }
        return new URLClassLoader(urls, parent);
    }
}
