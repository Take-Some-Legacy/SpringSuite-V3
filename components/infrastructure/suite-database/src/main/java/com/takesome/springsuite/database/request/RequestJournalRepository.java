package com.takesome.springsuite.database.request;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "suite.database", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RequestJournalRepository {
    private static final String INSERT_SQL = """
            INSERT INTO suite_http_request (
                id, correlation_id, started_at, completed_at, method, request_uri, query_string,
                scheme, host, server_port, remote_address, remote_user, user_agent,
                request_content_type, request_headers, request_body, request_size_bytes, request_body_truncated,
                response_status, response_content_type, response_headers, response_body, response_size_bytes,
                response_body_truncated, duration_ms, exception_type, exception_message, search_document
            ) VALUES (
                :id, :correlationId, :startedAt, :completedAt, :method, :requestUri, :queryString,
                :scheme, :host, :serverPort, :remoteAddress, :remoteUser, :userAgent,
                :requestContentType, :requestHeaders, :requestBody, :requestSizeBytes, :requestBodyTruncated,
                :responseStatus, :responseContentType, :responseHeaders, :responseBody, :responseSizeBytes,
                :responseBodyTruncated, :durationMs, :exceptionType, :exceptionMessage, :searchDocument
            )
            """;

    private static final String SUMMARY_COLUMNS = """
            id, correlation_id, started_at, method, request_uri, query_string, response_status,
            duration_ms, request_size_bytes, response_size_bytes, remote_address, user_agent,
            exception_type, request_body_truncated, response_body_truncated, request_body, response_body
            """;

    private static final String DETAIL_COLUMNS = """
            id, correlation_id, started_at, completed_at, method, request_uri, query_string,
            scheme, host, server_port, remote_address, remote_user, user_agent,
            request_content_type, request_headers, request_body, request_size_bytes, request_body_truncated,
            response_status, response_content_type, response_headers, response_body, response_size_bytes,
            response_body_truncated, duration_ms, exception_type, exception_message, search_document
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RequestJournalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(RequestJournalRecord record) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", record.id())
                .addValue("correlationId", record.correlationId())
                .addValue("startedAt", timestamp(record.startedAt()))
                .addValue("completedAt", timestamp(record.completedAt()))
                .addValue("method", record.method())
                .addValue("requestUri", record.requestUri())
                .addValue("queryString", record.queryString())
                .addValue("scheme", record.scheme())
                .addValue("host", record.host())
                .addValue("serverPort", record.serverPort())
                .addValue("remoteAddress", record.remoteAddress())
                .addValue("remoteUser", record.remoteUser())
                .addValue("userAgent", record.userAgent())
                .addValue("requestContentType", record.requestContentType())
                .addValue("requestHeaders", record.requestHeaders())
                .addValue("requestBody", record.requestBody())
                .addValue("requestSizeBytes", record.requestSizeBytes())
                .addValue("requestBodyTruncated", record.requestBodyTruncated())
                .addValue("responseStatus", record.responseStatus())
                .addValue("responseContentType", record.responseContentType())
                .addValue("responseHeaders", record.responseHeaders())
                .addValue("responseBody", record.responseBody())
                .addValue("responseSizeBytes", record.responseSizeBytes())
                .addValue("responseBodyTruncated", record.responseBodyTruncated())
                .addValue("durationMs", record.durationMs())
                .addValue("exceptionType", record.exceptionType())
                .addValue("exceptionMessage", record.exceptionMessage())
                .addValue("searchDocument", record.searchDocument());
        jdbc.update(INSERT_SQL, parameters);
    }

    public RequestJournalPage search(RequestJournalSearch search) {
        QueryParts parts = where(search);
        Long totalValue = jdbc.queryForObject("SELECT COUNT(*) FROM suite_http_request" + parts.where(), parts.parameters(), Long.class);
        long total = totalValue == null ? 0L : totalValue;
        long offsetLong = (long) search.page() * search.size();
        int offset = offsetLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) offsetLong;
        Map<String, Object> pageParameters = new HashMap<>(parts.parameters());
        pageParameters.put("limit", search.size());
        pageParameters.put("offset", offset);
        String sql = "SELECT " + SUMMARY_COLUMNS + " FROM suite_http_request"
                + parts.where() + " ORDER BY started_at DESC LIMIT :limit OFFSET :offset";
        List<RequestJournalSummary> items = jdbc.query(sql, pageParameters, this::mapSummary);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / search.size());
        return new RequestJournalPage(search.page(), search.size(), total, totalPages, items);
    }

    public Optional<RequestJournalRecord> findById(String id) {
        List<RequestJournalRecord> records = jdbc.query(
                "SELECT " + DETAIL_COLUMNS + " FROM suite_http_request WHERE id = :id",
                Map.of("id", id),
                this::mapRecord
        );
        return records.stream().findFirst();
    }

    public RequestJournalStats stats(Instant since) {
        String sql = """
                SELECT
                    COUNT(*) AS total,
                    SUM(CASE WHEN started_at >= :since THEN 1 ELSE 0 END) AS last_24_hours,
                    SUM(CASE WHEN response_status BETWEEN 200 AND 299 THEN 1 ELSE 0 END) AS successful,
                    SUM(CASE WHEN response_status BETWEEN 300 AND 399 THEN 1 ELSE 0 END) AS redirects,
                    SUM(CASE WHEN response_status BETWEEN 400 AND 499 THEN 1 ELSE 0 END) AS client_errors,
                    SUM(CASE WHEN response_status >= 500 THEN 1 ELSE 0 END) AS server_errors,
                    COALESCE(AVG(duration_ms), 0) AS average_duration_ms,
                    MAX(started_at) AS last_recorded_at
                FROM suite_http_request
                """;
        return jdbc.queryForObject(sql, Map.of("since", timestamp(since)), (rs, rowNum) -> new RequestJournalStats(
                rs.getLong("total"),
                rs.getLong("last_24_hours"),
                rs.getLong("successful"),
                rs.getLong("redirects"),
                rs.getLong("client_errors"),
                rs.getLong("server_errors"),
                rs.getDouble("average_duration_ms"),
                instant(rs.getTimestamp("last_recorded_at"))
        ));
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM suite_http_request", Map.of(), Long.class);
        return count == null ? 0L : count;
    }

    private QueryParts where(RequestJournalSearch search) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        Map<String, Object> parameters = new HashMap<>();
        if (!blank(search.query())) {
            where.append(" AND LOWER(search_document) LIKE :query");
            parameters.put("query", "%" + search.query().trim().toLowerCase() + "%");
        }
        if (!blank(search.method())) {
            where.append(" AND method = :method");
            parameters.put("method", search.method().trim().toUpperCase());
        }
        if (!blank(search.path())) {
            where.append(" AND LOWER(request_uri) LIKE :path");
            parameters.put("path", "%" + search.path().trim().toLowerCase() + "%");
        }
        if (search.statusFrom() != null) {
            where.append(" AND response_status >= :statusFrom");
            parameters.put("statusFrom", search.statusFrom());
        }
        if (search.statusTo() != null) {
            where.append(" AND response_status <= :statusTo");
            parameters.put("statusTo", search.statusTo());
        }
        if (search.from() != null) {
            where.append(" AND started_at >= :from");
            parameters.put("from", timestamp(search.from()));
        }
        if (search.to() != null) {
            where.append(" AND started_at <= :to");
            parameters.put("to", timestamp(search.to()));
        }
        return new QueryParts(where.toString(), parameters);
    }

    private RequestJournalSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new RequestJournalSummary(
                rs.getString("id"),
                rs.getString("correlation_id"),
                instant(rs.getTimestamp("started_at")),
                rs.getString("method"),
                rs.getString("request_uri"),
                rs.getString("query_string"),
                rs.getInt("response_status"),
                rs.getLong("duration_ms"),
                rs.getLong("request_size_bytes"),
                rs.getLong("response_size_bytes"),
                rs.getString("remote_address"),
                rs.getString("user_agent"),
                rs.getString("exception_type"),
                rs.getBoolean("request_body_truncated"),
                rs.getBoolean("response_body_truncated"),
                preview(rs.getString("request_body")),
                preview(rs.getString("response_body"))
        );
    }

    private RequestJournalRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new RequestJournalRecord(
                rs.getString("id"), rs.getString("correlation_id"),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("completed_at")),
                rs.getString("method"), rs.getString("request_uri"), rs.getString("query_string"),
                rs.getString("scheme"), rs.getString("host"), rs.getInt("server_port"),
                rs.getString("remote_address"), rs.getString("remote_user"), rs.getString("user_agent"),
                rs.getString("request_content_type"), rs.getString("request_headers"), rs.getString("request_body"),
                rs.getLong("request_size_bytes"), rs.getBoolean("request_body_truncated"),
                rs.getInt("response_status"), rs.getString("response_content_type"), rs.getString("response_headers"),
                rs.getString("response_body"), rs.getLong("response_size_bytes"), rs.getBoolean("response_body_truncated"),
                rs.getLong("duration_ms"), rs.getString("exception_type"), rs.getString("exception_message"),
                rs.getString("search_document")
        );
    }

    private static String preview(String value) {
        if (value == null || value.isBlank()) return "";
        String singleLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= 280 ? singleLine : singleLine.substring(0, 280) + "...";
    }

    private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private record QueryParts(String where, Map<String, Object> parameters) { }
}
