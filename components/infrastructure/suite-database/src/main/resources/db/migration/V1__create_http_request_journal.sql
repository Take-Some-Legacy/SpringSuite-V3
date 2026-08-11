CREATE TABLE IF NOT EXISTS suite_http_request (
    id VARCHAR(36) PRIMARY KEY,
    correlation_id VARCHAR(128) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NOT NULL,
    method VARCHAR(16) NOT NULL,
    request_uri VARCHAR(2048) NOT NULL,
    query_string VARCHAR(16384) NOT NULL,
    scheme VARCHAR(16) NOT NULL,
    host VARCHAR(512) NOT NULL,
    server_port INTEGER NOT NULL,
    remote_address VARCHAR(128) NOT NULL,
    remote_user VARCHAR(512) NOT NULL,
    user_agent VARCHAR(4096) NOT NULL,
    request_content_type VARCHAR(512) NOT NULL,
    request_headers VARCHAR(1048576) NOT NULL,
    request_body VARCHAR(1048576) NOT NULL,
    request_size_bytes BIGINT NOT NULL,
    request_body_truncated BOOLEAN NOT NULL,
    response_status INTEGER NOT NULL,
    response_content_type VARCHAR(512) NOT NULL,
    response_headers VARCHAR(1048576) NOT NULL,
    response_body VARCHAR(1048576) NOT NULL,
    response_size_bytes BIGINT NOT NULL,
    response_body_truncated BOOLEAN NOT NULL,
    duration_ms BIGINT NOT NULL,
    exception_type VARCHAR(1024) NOT NULL,
    exception_message VARCHAR(32768) NOT NULL,
    search_document VARCHAR(2097152) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_suite_http_request_started_at ON suite_http_request (started_at);
CREATE INDEX IF NOT EXISTS idx_suite_http_request_method_started ON suite_http_request (method, started_at);
CREATE INDEX IF NOT EXISTS idx_suite_http_request_status_started ON suite_http_request (response_status, started_at);
CREATE INDEX IF NOT EXISTS idx_suite_http_request_uri ON suite_http_request (request_uri);
