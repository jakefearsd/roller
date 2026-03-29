-- Roller 5.2.x to 6.0.0 migration
-- Remove OAuth tables (OAuth 1.0a API removed)

DROP TABLE IF EXISTS roller_oauthaccessor;
DROP TABLE IF EXISTS roller_oauthconsumer;
