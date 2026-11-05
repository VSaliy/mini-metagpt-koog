CREATE TABLE workflow_execution (
    id                      UUID PRIMARY KEY,
    request                 TEXT                     NOT NULL,
    status                  VARCHAR(32)              NOT NULL,
    stage                   VARCHAR(32)              NOT NULL,
    max_correction_attempts INT                      NOT NULL,
    correction_attempts     INT                      NOT NULL,
    error_json              TEXT,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at              TIMESTAMP WITH TIME ZONE,
    finished_at             TIMESTAMP WITH TIME ZONE
);

CREATE TABLE artifact (
    id             UUID PRIMARY KEY,
    workflow_id    UUID                     NOT NULL REFERENCES workflow_execution (id),
    type           VARCHAR(32)              NOT NULL,
    schema_version VARCHAR(16)              NOT NULL,
    content_json   TEXT                     NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_artifact_workflow ON artifact (workflow_id, created_at);

CREATE TABLE agent_execution (
    id              UUID PRIMARY KEY,
    workflow_id     UUID                     NOT NULL REFERENCES workflow_execution (id),
    role            VARCHAR(32)              NOT NULL,
    stage           VARCHAR(32)              NOT NULL,
    attempt         INT                      NOT NULL,
    outcome         VARCHAR(32)              NOT NULL,
    artifact_id     UUID,
    error_json      TEXT,
    validation_json TEXT,
    input_tokens    INT,
    output_tokens   INT,
    total_tokens    INT,
    started_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    duration_ms     BIGINT                   NOT NULL
);
CREATE INDEX idx_agent_execution_workflow ON agent_execution (workflow_id, started_at);
