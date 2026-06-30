package com.takesome.springsuite.app;

import com.takesome.springsuite.config.ExternalSuiteConfigBootstrap;
import com.takesome.springsuite.config.SuiteConfigBootstrapResult;
import com.takesome.springsuite.logging.ConsoleAnsiBootstrap;
import com.takesome.springsuite.module.SuiteModuleBootstrap;
import com.takesome.springsuite.module.SuiteModuleBootstrapResult;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.io.DefaultResourceLoader;

@SpringBootApplication(scanBasePackages = "com.takesome.springsuite")
@ConfigurationPropertiesScan(basePackages = "com.takesome.springsuite")
public class SpringSuiteApplication {
    public static void main(String[] args) {
        SuiteModuleBootstrapResult modules = SuiteModuleBootstrap.bootstrap();
        SuiteConfigBootstrapResult config = ExternalSuiteConfigBootstrap.bootstrap();
        ConsoleAnsiBootstrap.installEarly(config.consoleAnsiEnabled(), config.consoleAnsiProbe());
        System.out.println("[SpringSuite] launch root: " + config.projectRoot());
        System.out.println("[SpringSuite] modules dir: " + modules.modulesDir()
                + " enabled=" + modules.enabled()
                + " jars=" + modules.moduleJars().size());
        System.out.println("[SpringSuite] config path: " + config.configPath()
                + " created=" + config.created()
                + " supplemented=" + config.supplemented());
        System.out.println("[SpringSuite] logs file: " + config.logFile());

        SpringApplication application = new SpringApplication(SpringSuiteApplication.class);
        application.setResourceLoader(new DefaultResourceLoader(modules.classLoader()));
        application.run(args);
    }
}
