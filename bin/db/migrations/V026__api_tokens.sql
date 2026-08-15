-- V026: API tokens for the automation surface.
--
-- Deliberately a separate table from roller_user_token rather than a new
-- UserToken.Purpose: that entity is single-use with a one-hour TTL and an
-- atomic consume, all of which are wrong for a long-lived credential.
--
-- Only the SHA-256 digest is stored. The secret is high-entropy random, so
-- there is nothing to brute-force and authentication must stay a single
-- indexed lookup -- a slow KDF would be wrong on both counts.

CREATE TABLE IF NOT EXISTS roller_api_token (
    id            VARCHAR(48)  NOT NULL PRIMARY KEY,
    userid        VARCHAR(48)  NOT NULL,
    label         VARCHAR(255) NOT NULL,
    token_sha256  VARCHAR(64)  NOT NULL,
    scope_weblog  VARCHAR(255),
    scope_role    VARCHAR(16)  NOT NULL,
    created       TIMESTAMP    NOT NULL,
    last_used_at  TIMESTAMP,
    expires_at    TIMESTAMP,
    revoked_at    TIMESTAMP
);

DO $$
BEGIN
    ALTER TABLE roller_api_token
        ADD CONSTRAINT roller_api_token_userid_fk
        FOREIGN KEY (userid) REFERENCES roller_user (id);
EXCEPTION
    WHEN duplicate_object THEN NULL;
    WHEN duplicate_table THEN NULL;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS roller_api_token_digest_uq
    ON roller_api_token (token_sha256);

CREATE INDEX IF NOT EXISTS roller_api_token_userid_idx
    ON roller_api_token (userid);
