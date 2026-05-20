CREATE TABLE devices (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    name              VARCHAR(128) NOT NULL,
    gw_id             VARCHAR(64)  NOT NULL UNIQUE,
    local_key         VARCHAR(64),
    ip                VARCHAR(64),
    protocol_version  VARCHAR(8)   NOT NULL DEFAULT '3.3',
    source            VARCHAR(16)  NOT NULL DEFAULT 'LOCAL',
    dps_schema_json   CLOB,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_devices_gw_id ON devices (gw_id);
