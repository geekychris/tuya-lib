-- Single-row table holding Tuya Cloud OpenAPI credentials. We pin id=1 so the
-- upsert pattern in code is straightforward.
CREATE TABLE cloud_config (
    id              BIGINT PRIMARY KEY,
    access_id       VARCHAR(64),
    access_secret   VARCHAR(128),
    region          VARCHAR(8)  NOT NULL DEFAULT 'us',
    updated_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO cloud_config (id, access_id, access_secret, region) VALUES (1, NULL, NULL, 'us');
