package com.takesome.springsuite.module;

import com.takesome.springsuite.config.SuiteConfigContributor;
import com.takesome.springsuite.config.SuiteConfigFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

public class ModuleConfigContributor implements SuiteConfigContributor {
    @Override
    public List<SuiteConfigFile> configFiles() {
        ArrayList<SuiteConfigFile> files = new ArrayList<>();
        files.add(new SuiteConfigFile(
                "suite-module",
                "suite-modules.yml",
                "suite-modules-default.yml",
                50
        ));

        ServiceLoader<SuiteModule> loader = ServiceLoader.load(SuiteModule.class);
        for (SuiteModule module : loader) {
            files.addAll(module.configFiles());
        }

        return files.stream()
                .sorted(Comparator.comparingInt(SuiteConfigFile::order).thenComparing(SuiteConfigFile::fileName))
                .toList();
    }
}
