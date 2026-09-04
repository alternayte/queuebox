-- F-047: one identifier that follows a message from the inbound request to the outbound publish.
--
-- The inbox row stores the identifier. The relay copies it into the outbox headers, and the
-- publisher forwards the headers, so the outbox needs no column of its own.

IF COL_LENGTH('inbox', 'correlation_id') IS NULL
    ALTER TABLE inbox ADD correlation_id NVARCHAR(128) NULL;
