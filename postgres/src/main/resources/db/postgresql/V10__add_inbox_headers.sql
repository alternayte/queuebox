-- The inbox keeps the headers that the broker or the webhook request carried, one string value per
-- key, in the same shape as outbox.headers. The default keeps an existing writer working.
ALTER TABLE inbox ADD COLUMN IF NOT EXISTS headers JSONB NOT NULL DEFAULT '{}';
