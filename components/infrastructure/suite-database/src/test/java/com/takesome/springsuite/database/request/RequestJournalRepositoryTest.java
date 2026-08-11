package com.takesome.springsuite.database.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class RequestJournalRepositoryTest {
    private EmbeddedDatabase database;
    private RequestJournalRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("db/migration/V1__create_http_request_journal.sql")
                .build();
        repository = new RequestJournalRepository(new NamedParameterJdbcTemplate(database));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsSearchesAndAggregatesCapturedRequests() {
        Instant started = Instant.parse("2026-07-20T01:00:00Z");
        RequestJournalRecord record = new RequestJournalRecord(
                "f3860f3f-c967-41ab-91cf-84ae0a9cd378",
                "client-correlation-7",
                started,
                started.plusMillis(47),
                "POST",
                "/api/ai/chat",
                "provider=openai",
                "http",
                "localhost",
                8090,
                "127.0.0.1",
                "operator",
                "JUnit",
                "application/json",
                "{\"content-type\":[\"application/json\"]}",
                "{\"message\":\"hello database\"}",
                28,
                false,
                200,
                "application/json",
                "{\"content-type\":[\"application/json\"]}",
                "{\"ok\":true,\"answer\":\"stored\"}",
                37,
                false,
                47,
                "",
                "",
                "POST /api/ai/chat hello database stored openai"
        );

        repository.insert(record);

        RequestJournalPage page = repository.search(new RequestJournalSearch(
                "hello database",
                "POST",
                "/api/ai",
                200,
                299,
                started.minusSeconds(1),
                started.plusSeconds(1),
                0,
                20
        ));

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(record.id());
            assertThat(item.requestUri()).isEqualTo("/api/ai/chat");
            assertThat(item.responseStatus()).isEqualTo(200);
            assertThat(item.requestPreview()).contains("hello database");
        });
        assertThat(repository.findById(record.id())).contains(record);
        assertThat(repository.count()).isEqualTo(1);

        RequestJournalStats stats = repository.stats(started.minusSeconds(1));
        assertThat(stats.total()).isEqualTo(1);
        assertThat(stats.last24Hours()).isEqualTo(1);
        assertThat(stats.successful()).isEqualTo(1);
        assertThat(stats.averageDurationMs()).isEqualTo(47.0);
    }
}
