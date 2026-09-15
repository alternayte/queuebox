-- The inbox keeps the headers that the broker or the webhook request carried, one string value per
-- key, in the same shape as outbox.headers. The default keeps an existing writer working.
IF COL_LENGTH('inbox', 'headers') IS NULL
    ALTER TABLE inbox ADD headers NVARCHAR(MAX) NOT NULL CONSTRAINT df_inbox_headers DEFAULT '{}';
