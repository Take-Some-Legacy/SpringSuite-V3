package com.takesome.springsuite.database;

import com.takesome.springsuite.database.request.RequestJournalRepository;
import javax.sql.DataSource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component("suiteDatabase")
@ConditionalOnProperty(prefix = "suite.database", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DatabaseHealthIndicator implements HealthIndicator {
    private final DataSource dataSource;
    private final RequestJournalRepository requestJournalRepository;

    public DatabaseHealthIndicator(DataSource dataSource, RequestJournalRepository requestJournalRepository) {
        this.dataSource = dataSource;
        this.requestJournalRepository = requestJournalRepository;
    }

    @Override
    public Health health() {
        try (var connection = dataSource.getConnection()) {
            return Health.up()
                    .withDetail("database", connection.getMetaData().getDatabaseProductName())
                    .withDetail("databaseVersion", connection.getMetaData().getDatabaseProductVersion())
                    .withDetail("driver", connection.getMetaData().getDriverName())
                    .withDetail("requestJournalRecords", requestJournalRepository.count())
                    .build();
        } catch (Exception ex) {
            return Health.down(ex).build();
        }
    }
}
