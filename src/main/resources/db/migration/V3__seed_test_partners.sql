-- =============================================================
-- V3__seed_test_partners.sql
-- Seed two test partners for local development.
--
-- Secrets (DO NOT USE IN PRODUCTION — these are local-dev only):
--   partner-acme    secret = "acme-shared-secret-2024"
--   partner-globex  secret = "globex-shared-secret-2024"
--
-- secret_hash = lower(hex(sha256(secret))). The partner computes the same
-- SHA-256 of their secret and uses those raw bytes as the HMAC-SHA256 key.
-- =============================================================

INSERT INTO partners (partner_id, secret_hash, active) VALUES
    ('partner-acme',
     -- sha256("acme-shared-secret-2024")
     '1c7d268494e7067c99d968dfdbe9cfda6470c78c9b82a94e96a91fa3b0370e46',
     TRUE),
    ('partner-globex',
     -- sha256("globex-shared-secret-2024")
     '625ca4e2f3ba50466cf2ff01ec9c64961448a9f575ec12b024043126bb662ba4',
     TRUE)
ON CONFLICT (partner_id) DO NOTHING;
