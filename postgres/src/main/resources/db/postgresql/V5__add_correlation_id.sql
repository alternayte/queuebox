-- F-047: one identifier that follows a message from the inbound request to the outbound publish.
--
-- The inbox row stores the identifier. The relay copies it into the outbox headers, and the
-- publisher forwards the headers, so the outbox needs no column of its own.

ALTER TABLE inbox ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(128);

COMMENT ON COLUMN inbox.correlation_id IS 'Identifier that follows the message across the system';
