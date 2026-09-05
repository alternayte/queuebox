-- Stop all old workers before applying this claim contract.
ALTER TABLE inbox ADD COLUMN claim_token UUID NULL;
ALTER TABLE inbox ADD COLUMN lease_expires_at TIMESTAMPTZ NULL;
ALTER TABLE outbox ADD COLUMN claim_token UUID NULL;
ALTER TABLE outbox ADD COLUMN lease_expires_at TIMESTAMPTZ NULL;
ALTER TABLE inbox ADD COLUMN consumption VARCHAR(4) NOT NULL DEFAULT 'push' CHECK (consumption IN ('push', 'pull'));
ALTER TABLE inbox ADD COLUMN scheduled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE inbox ADD COLUMN attempt INT NOT NULL DEFAULT 0;
ALTER TABLE inbox ADD COLUMN last_error TEXT NULL;
CREATE INDEX idx_inbox_consumption_pending ON inbox (consumption, state, scheduled_at);
