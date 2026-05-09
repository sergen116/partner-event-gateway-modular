# Cryptography & Partner Authentication — Quick Summary

A short index of `CRYPTO_PRIMITIVES_QA.md` for fast recall.

---

## 1. The Big Picture
Every partner request answers: *"Did the holder of this partner's key produce this exact request, recently?"*

Three independent checks:
- **Authenticity + Integrity** → HMAC-SHA256 (`HmacVerifier.matches`)
- **Freshness** → timestamp + skew (`HmacVerifier.withinSkew`)
- **Key safety at rest** → store `SHA-256(secret)` (`Partner.secretHashHex`)

All hashing, no encryption — TLS handles confidentiality.

---

## 2. SHA-256
- 256-bit (32-byte) digest, deterministic, fixed-size, avalanche, one-way, collision-resistant.
- **Not** encryption, **not** standalone authentication (length-extension), **not** a password hasher.

---

## 3. HMAC and HMAC-SHA256
- Bare `SHA-256(secret ‖ msg)` → broken by **length-extension attack**.
- HMAC nests hash twice: `H((key⊕opad) ‖ H((key⊕ipad) ‖ msg))`.
- Provides authenticity, integrity, determinism.
- Does NOT: encrypt, prevent replays, identify the key, give non-repudiation.
- Replay gap closed by timestamp + `withinSkew`.

---

## 4. Hashing vs Encryption
- Hashing = "is this the same?" (one-way, fixed size).
- Encryption = "only you can read this" (reversible with key).
- Project is **all hashing** because it needs integrity + authenticity, not confidentiality.

Three primitives: SHA-256 (key derivation), HMAC-SHA256 (signing), `MessageDigest.isEqual` (constant-time compare).

---

## 5. Hex vs Base64
- Both reversible text encodings, no security.
- **Hex** for stored hash: 64 chars, fixed width, case-insensitive, diff-friendly, URL/shell-safe — `CHAR(64)` column.
- **Base64** for `X-Signature` header: 44 chars, ASCII-safe, every byte counts on the wire.

---

## 6. Why SHA-256 (vs Alternatives)
- **MD5/SHA-1** ❌ broken.
- **SHA-512** — overkill, larger.
- **SHA-3** — slower in software, less hardware support.
- **BLAKE2/3** — fast but no HW accel + not FIPS-approved.
- **bcrypt/scrypt/Argon2** — wrong tool, deliberately slow for low-entropy passwords.
- SHA-256 wins: secure in 2026, 256-bit output, fast (SHA-NI), universal, FIPS-approved.

---

## 7. The Stored Hash *Is* the Key
- DB stores `secretHashHex` = `SHA-256(secret)` in hex.
- `parseHex` is **not reversing SHA-256** — it's hex → bytes.
- Partner computes `SHA-256(secret)` → uses as HMAC key.
- Gateway uses stored hash bytes directly as HMAC key.
- Both sides arrive at the same key by **forward computation**.
- DB leak → attacker can forge to *this* gateway, but cannot recover the original secret or attack other systems.
- Production refinement: KMS / sealed secret store.

---

## 8. `HmacVerifier.java` Walk-Through
1. Active partner check — early reject.
2. `withinSkew(timestamp)` — anti-replay.
3. Build canonical message: `partnerId\ntimestamp\nMETHOD\npath\nbody`.
4. `matches(currentKey, ...)` — verify against current key.
5. Rotation grace: also try `previousSecretHashHex` if within window.

`matches`: hex → bytes → HMAC-SHA256 → `MessageDigest.isEqual` (constant-time).

`MessageDigest.isEqual` matters — `Arrays.equals` short-circuits and leaks timing.

---

## 9. TL;DR
- SHA-256 used twice: derive HMAC key + inner hash of HMAC.
- HMAC-SHA256 = authenticity + integrity, length-extension safe.
- No encryption in auth path — TLS owns confidentiality.
- Hex for storage, base64 for wire.
- Stored hash *is* the HMAC key — forward computation, never reversed.
- Replay protection from timestamp + skew, not HMAC.
- Constant-time compare prevents timing attacks.

---

## Common Issues / Gotchas
- Naive `SHA-256(secret ‖ msg)` → length-extension; must use HMAC.
- `Arrays.equals` for tag compare → timing leak; use `MessageDigest.isEqual`.
- Canonical message field order / separators / uppercase method must match exactly — any drift breaks the tag.
- HMAC alone does not prevent replays — timestamp must be **inside** the signed message.
- Hex parsing is encoding conversion only, not decryption.
- Storing raw secrets in DB is a leak risk — store `SHA-256(secret)` (or KMS in prod).
- Don't use bcrypt/Argon2 for machine secrets on the hot path — wrong perf profile.
