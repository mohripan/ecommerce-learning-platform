-- akka-persistence-jdbc journal schema for PostgreSQL
-- Run once during database initialisation.
-- In production use a migration tool (Flyway/Liquibase) to manage schema versions.

-- ── Event journal ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS event_journal (
    ordering        BIGSERIAL                NOT NULL,
    persistence_id  VARCHAR(255)             NOT NULL,
    sequence_number BIGINT                   NOT NULL,
    deleted         BOOLEAN                  NOT NULL DEFAULT FALSE,
    writer          VARCHAR(255)             NOT NULL,
    write_timestamp BIGINT                   NOT NULL,
    adapter_manifest VARCHAR(255),
    event_ser_id    INT                      NOT NULL,
    event_ser_manifest VARCHAR(255)          NOT NULL,
    event_payload   BYTEA                    NOT NULL,
    meta_ser_id     INT,
    meta_ser_manifest VARCHAR(255),
    meta_payload    BYTEA,

    PRIMARY KEY (persistence_id, sequence_number)
);

CREATE UNIQUE INDEX IF NOT EXISTS event_journal_ordering_idx
    ON event_journal (ordering);

-- ── Snapshot store ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS snapshot (
    persistence_id  VARCHAR(255)             NOT NULL,
    sequence_number BIGINT                   NOT NULL,
    created         BIGINT                   NOT NULL,
    snapshot_ser_id INT                      NOT NULL,
    snapshot_ser_manifest VARCHAR(255)       NOT NULL,
    snapshot_payload BYTEA                   NOT NULL,
    meta_ser_id     INT,
    meta_ser_manifest VARCHAR(255),
    meta_payload    BYTEA,

    PRIMARY KEY (persistence_id, sequence_number)
);

-- ── Read-side projection: current inventory levels ───────────────────────────
CREATE TABLE IF NOT EXISTS inventory_view (
    product_id      VARCHAR(255)             NOT NULL PRIMARY KEY,
    available_stock INT                      NOT NULL DEFAULT 0,
    reserved_stock  INT                      NOT NULL DEFAULT 0,
    total_stock     INT                      NOT NULL GENERATED ALWAYS AS (available_stock + reserved_stock) STORED,
    updated_at      TIMESTAMPTZ              NOT NULL DEFAULT now()
);
