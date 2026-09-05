CREATE TABLE queuebox_capture_state (
    identity_name VARCHAR(63) PRIMARY KEY,
    state_id VARCHAR(36) NOT NULL,
    config_fingerprint VARCHAR(64) NOT NULL
);
