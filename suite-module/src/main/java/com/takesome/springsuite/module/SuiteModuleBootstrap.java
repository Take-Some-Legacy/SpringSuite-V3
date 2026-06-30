package com.takesome.springsuite.module;

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
            List<Path> discoveredJars = config.enabled() ? ModuleJarScanner.scan(config.modulesDir(), config.recursive()) : List.of();
            List<SuiteModuleJarTrustReport> trustReports = discoveredJars.stream()
                    .map(jar -> ModuleJarTrustReporter.report(jar, config))
                    .toList();
            List<Path> loadableJars = trustReports.stream()
                    .filter(SuiteModuleJarTrustReport::loadAllowed)
                    .map(SuiteModuleJarTrustReport::path)
                    .toList();

            URLClassLoader moduleClassLoader = createModuleClassLoader(loadableJars);
            Thread.currentThread().setContextClassLoader(moduleClassLoader);
            ModuleBootstrapSystemProperties.publish(runtimeRoot, config, discoveredJars, loadableJars, trustReports);

            return new SuiteModuleBootstrapResult(runtimeRoot, config.modulesDir(), config.enabled(), config.recursive(), loadableJars, trustReports, moduleClassLoader);
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
