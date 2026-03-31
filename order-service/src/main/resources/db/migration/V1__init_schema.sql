-- ============================================================
-- Axon Framework — required infrastructure tables
-- ============================================================

-- Event store: all domain events
CREATE TABLE IF NOT EXISTS domain_event_entry (
    global_index          BIGSERIAL    NOT NULL,
    aggregate_identifier  VARCHAR(255) NOT NULL,
    sequence_number       BIGINT       NOT NULL,
    type                  VARCHAR(255),
    event_identifier      VARCHAR(255) NOT NULL UNIQUE,
    meta_data             BYTEA,
    payload               BYTEA        NOT NULL,
    payload_revision      VARCHAR(255),
    payload_type          VARCHAR(255) NOT NULL,
    time_stamp            VARCHAR(255) NOT NULL,
    PRIMARY KEY (global_index),
    UNIQUE (aggregate_identifier, sequence_number)
);

CREATE INDEX IF NOT EXISTS idx_domain_event_entry_aggregate
    ON domain_event_entry (aggregate_identifier, sequence_number);

-- Event store: aggregate snapshots
CREATE TABLE IF NOT EXISTS snapshot_event_entry (
    aggregate_identifier  VARCHAR(255) NOT NULL,
    sequence_number       BIGINT       NOT NULL,
    type                  VARCHAR(255) NOT NULL,
    event_identifier      VARCHAR(255) NOT NULL UNIQUE,
    meta_data             BYTEA,
    payload               BYTEA        NOT NULL,
    payload_revision      VARCHAR(255),
    payload_type          VARCHAR(255) NOT NULL,
    time_stamp            VARCHAR(255) NOT NULL,
    PRIMARY KEY (aggregate_identifier, sequence_number)
);

-- Tracking event processors: checkpoint tokens
CREATE TABLE IF NOT EXISTS token_entry (
    processor_name  VARCHAR(255) NOT NULL,
    segment         INTEGER      NOT NULL,
    token           BYTEA,
    token_type      VARCHAR(255),
    owner           VARCHAR(255),
    timestamp       VARCHAR(255) NOT NULL,
    PRIMARY KEY (processor_name, segment)
);

-- Saga instances
CREATE TABLE IF NOT EXISTS saga_entry (
    saga_id     VARCHAR(255) NOT NULL,
    revision    VARCHAR(255),
    saga_type   VARCHAR(255),
    serialized_saga BYTEA,
    PRIMARY KEY (saga_id)
);

-- Saga association values (used to route events to the right saga instance)
CREATE TABLE IF NOT EXISTS association_value_entry (
    id              BIGSERIAL    NOT NULL,
    association_key VARCHAR(255) NOT NULL,
    association_value VARCHAR(255),
    saga_id         VARCHAR(255),
    saga_type       VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_association_value_entry_saga
    ON association_value_entry (saga_type, association_key, association_value);

-- ============================================================
-- Order Service — read-model / projection table
-- ============================================================

CREATE TABLE IF NOT EXISTS order_summary (
    order_id         VARCHAR(255)   NOT NULL,
    customer_id      VARCHAR(255)   NOT NULL,
    status           VARCHAR(50)    NOT NULL,
    total_amount     NUMERIC(19, 4) NOT NULL,
    shipping_address VARCHAR(1000)  NOT NULL,
    items_json       TEXT,
    tracking_number  VARCHAR(255),
    carrier          VARCHAR(255),
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ,
    PRIMARY KEY (order_id)
);

CREATE INDEX IF NOT EXISTS idx_order_summary_customer ON order_summary (customer_id);
CREATE INDEX IF NOT EXISTS idx_order_summary_status   ON order_summary (status);
CREATE INDEX IF NOT EXISTS idx_order_summary_created  ON order_summary (created_at DESC);
