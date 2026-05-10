# Cryptography & Partner Auth — Summary

Combines `CRYPTO_PRIMITIVES_QA.md`, `CRYPTOGRAPHY_AND_ENCRYPTION.md`, `HEX_VS_BASE64_QA.md`.

## The big picture
Every request answers: *"Did the holder of this partner's key produce this exact request, recently?"*
- **Authenticity + Integrity** → HMAC-SHA256 (`HmacVerifier.matches`).
- **Freshness** → timestamp + skew (`HmacVerifier.withinSkew`).
- **Key safety at rest** → store `SHA-256(secret)` (`Partner.secretHashHex`).

All hashing, no encryption — TLS handles confidentiality.

## SHA-256
- 256-bit (32-byte) digest, deterministic, fixed-size, avalanche, one-way, collision-resistant.
- **NOT** encryption, **NOT** standalone auth (length-extension attack), **NOT** a password hasher.

## HMAC-SHA256
- Bare `SHA-256(secret ‖ msg)` is broken by **length-extension** (Merkle–Damgård leak).
- HMAC nests hash twice: `H((key⊕opad) ‖ H((key⊕ipad) ‖ msg))` — neutralizes it.
- Provides authenticity + integrity + determinism.
- Does **NOT**: encrypt, prevent replays, identify the key, give non-repudiation.
- **Replay protection** comes from timestamp inside signed message + `withinSkew`, not HMAC.

## Stored hash *is* the HMAC key (the trick)
- Partner: has raw secret → `key = SHA-256(secret)` → HMAC.
- Gateway: has `SHA-256(secret)` (hex in DB) → `parseHex` → uses 32 bytes directly as HMAC key.
- Both arrive at same key by **forward computation**. SHA-256 never reversed.
- `parseHex` is text→bytes only, NOT cryptographic.
- **DB leak impact**: attacker can forge to *this* gateway, but cannot recover the raw secret nor attack other systems where partner reused the secret. Defense in depth.
- **Production refinement**: KMS / sealed secret store — even hash never leaves KMS.

## Why SHA-256 over alternatives
- MD5/SHA-1: ❌ broken.
- SHA-512: overkill (256-bit security level), larger.
- SHA-3: slower in software (no SHA-NI equivalent), less universal.
- BLAKE2/3: fast in pure SW, no HW accel, not FIPS-approved.
- bcrypt/scrypt/Argon2: **wrong tool** — deliberately slow (~10–100ms) for low-entropy human passwords. Partner secret = 256 bits random → entropy itself is the defense; slow hash adds zero security but caps throughput at ~10 req/s/core.
- **Rule**: slow hashes for things humans choose, fast hashes for things machines generate.
- SHA-256 wins: secure in 2026, 256-bit, hardware-accelerated (SHA-NI/ARMv8), universal, FIPS 140-approved.

## Hex vs Base64 — different jobs

| | Hex (storage) | Base64 (wire) |
|---|---|---|
| Alphabet | `0-9 a-f` | `A-Za-z0-9+/=` |
| Bits/char | 4 | 6 |
| 32 bytes → | **64 chars** | **44 chars** (incl `=` padding) |
| Case-sensitive? | No | Yes |
| URL-safe? | ✅ | ❌ (needs base64url) |
| Visually scannable? | ✅ each byte = 2 chars at fixed pos | ❌ bytes blur into 4-char groups |
| Diff-friendly? | ✅ 1-byte change = 2 char change at predictable position | ❌ smears 1–4 chars due to 3-byte/4-char grouping |

**Why hex for storage**: convention (every tool — `sha256sum`, `openssl` — emits hex), fixed `CHAR(64)`, case-insensitive (survives log normalization), diff-friendly (instant 2am diagnostic), URL/shell-safe.

**Why base64 for X-Signature header**: 31% smaller (44 vs 64 chars) — every byte counts on wire; HTTP headers must be ASCII; no human reads it; HTTP convention (Basic Auth, JWT, AWS Sig v4, Stripe webhooks); `=` padding fine in headers (only awkward in URLs).

## `HmacVerifier.verify` walk-through
1. Active partner check (early reject).
2. `withinSkew(timestamp)` — anti-replay.
3. Build canonical msg: `partnerId\ntimestamp\nMETHOD\npath\nbody` (field order/separators/uppercase method must match exactly).
4. `matches(currentKey, ...)` — verify against current secret.
5. Rotation grace: also try `previousSecretHashHex` if within window.

`matches` internals: hex → bytes → HMAC-SHA256 → `MessageDigest.isEqual` (constant-time — `Arrays.equals` short-circuits and leaks timing).

## Quick mental map (broader crypto context)
```
Cryptography
├── Encryption:   Symmetric (AES, ChaCha20) | Asymmetric (RSA, ECC)
├── Hashing:      SHA-256, Argon2
├── Signatures:   RSA-PSS, ECDSA, Ed25519
├── MACs:         HMAC, Poly1305
└── Key Exchange: DH, ECDH
```
TLS = hybrid encryption + signatures + key exchange (ECDH for session key, AES-GCM/ChaCha20-Poly1305 for traffic, AEAD ciphers for combined confidentiality+integrity).

## Common gotchas
- Naive `SHA-256(secret ‖ msg)` → length-extension. Always HMAC.
- `Arrays.equals` on tags → timing leak. Use `MessageDigest.isEqual`.
- Canonical msg field order/separators/uppercase method must match exactly — drift breaks tag.
- HMAC alone ≠ replay protection — timestamp must be **inside** signed msg.
- bcrypt/Argon2 for machine secrets on hot path = wrong perf profile.
- Storing raw secret in DB = leak risk; store `SHA-256(secret)` (or KMS in prod).
