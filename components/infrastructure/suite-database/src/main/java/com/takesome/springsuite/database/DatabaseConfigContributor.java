package com.takesome.springsuite.database;

import com.takesome.springsuite.config.SuiteConfigContributor;
import com.takesome.springsuite.config.SuiteConfigFile;
import java.util.List;

public final class DatabaseConfigContributor implements SuiteConfigContributor {
    @Override
    public List<SuiteConfigFile> configFiles() {
        return List.of(new SuiteConfigFile(
                "suite-database",
                "suite-database.yml",
                "suite-database-default.yml",
                150
        ));
    }
}
