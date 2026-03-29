-- Roller 5.2.x to 6.0.0 migration
-- Remove OAuth tables (OAuth 1.0a API removed)

DROP TABLE IF EXISTS roller_oauthaccessor;
DROP TABLE IF EXISTS roller_oauthconsumer;

-- Remove mobile template renditions (mobile device detection removed in 6.0)
DELETE FROM custom_template_rendition WHERE type = 'MOBILE';

-- Remove orphaned rag_properties table (Planet/Aggregator feature removed)
DROP TABLE IF EXISTS rag_properties;
