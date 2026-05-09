# Cryptography & Partner Authentication — Concepts & Project Notes

A consolidated reference covering the cryptographic primitives used by the
Partner Event Gateway and the design choices behind `HmacVerifier.java`.
Every concept is tied back to where it appears in the codebase.

---

## 1. The Big Picture

Every partner request the gateway accepts is verified by `HmacVerifier`. The
verification answers one question:

> *"Did the holder of this partner's key produce this exact request, recently?"*

That single question decomposes into three independent checks:

| Concern | Mechanism | Where in code |
|---|---|---|
| Authenticity ("the right partner") + Integrity ("unmodified bytes") | **HMAC-SHA256** over a canonical message | `HmacVerifier.matches(...)` |
| Freshness ("recently") | Timestamp + skew window | `HmacVerifier.withinSkew(...)` |
| Key safety at rest | Store **SHA-256(secret)**, not the raw secret | `Partner.secretHashHex()` |

End-to-end flow:

```
        PARTNER SIDE                              GATEWAY SIDE
─────────────────────────────              ─────────────────────────────
secret                                     DB: SHA-256(secret) as hex
   │                                                      │
   │ SHA-256                                              │ HexFormat.parseHex
   ▼                                                      ▼
key bytes ─────────────┐                        key bytes (same 32 bytes)
                       │                                  │
canonical msg          │                       canonical msg (rebuilt
   │                   │                          from request)
   ▼                   ▼                                  ▼
HMAC-SHA256(key, msg)                          HMAC-SHA256(key, msg)
   │                                                      │
   ▼                                                      ▼
32 bytes ──base64──► X-Signature header ──► base64-decode ──► 32 bytes
                                                              │
                                                              ▼
                                                    MessageDigest.isEqual ✅/❌
```

Three crypto primitives, all in the hashing family, **no encryption** in the
auth path (TLS handles confidentiality at the transport layer).

---

## 2. SHA-256

### What it is

A **cryptographic hash function** that maps any input to a fixed **256-bit
(32-byte) digest**. Part of the SHA-2 family, published by NIST in 2001.

```
SHA-256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
```

### Properties that matter for this project

1. **Deterministic** — same input always gives the same output (so partner and
   gateway can independently derive the same key).
2. **Fixed-size output** — 32 bytes regardless of input length (perfect for an
   HMAC key, for a `CHAR(64)` DB column, for a fixed-width audit log entry).
3. **Avalanche effect** — one-bit change in the input produces a completely
   different output (so any tampering with the request body invalidates the
   signature).
4. **Preimage resistance (one-way)** — given the digest you cannot recover the
   input. This is what protects the partner secret if the DB leaks.
5. **Collision resistance** — finding two inputs that hash to the same value
   is computationally infeasible (SHA-1 lost this property in 2017; SHA-256
   has not).

### What it is NOT

- ❌ Not encryption — there is no key, and no inverse exists
- ❌ Not authentication on its own — `SHA256(secret ‖ message)` is vulnerable to
  length-extension attacks; that's why HMAC exists
- ❌ Not a password hasher — too fast; use bcrypt / scrypt / Argon2 for human
  passwords (this gateway's secrets are high-entropy machine-generated, so
  fast hashing is correct)

---

## 3. HMAC and HMAC-SHA256

### The problem HMAC solves

A bare hash proves integrity *if you trust the hash*. To bind a message to a
*sender*, you need to mix in a secret key. The naive way:

```
SHA-256(secret ‖ message)   ← DON'T DO THIS
```

is broken — Merkle–Damgård hashes (including SHA-256) leak enough internal
state for an attacker to extend the message and produce a valid hash for
`secret ‖ message ‖ extra` without knowing the secret. This is the
**length-extension attack**.

### How HMAC fixes it

HMAC nests the hash twice with two different padded keys:

```
HMAC(key, message) = H( (key ⊕ opad) ‖ H( (key ⊕ ipad) ‖ message ) )
```

The outer hash is the only thing an attacker observes. The inner state is
never exposed, which neutralizes length extension.

You never implement this by hand — the JDK does it:

```java
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(key, "HmacSHA256"));
byte[] tag = mac.doFinal(message);
```

### What HMAC gives you

| Property | Meaning |
|---|---|
| **Authenticity** | Only someone with the key can produce a valid tag |
| **Integrity** | Any change to the message produces a completely different tag |
| **Determinism** | Same key + same message → same tag, on every machine |

### What HMAC does NOT do

- ❌ Encrypt the message (TLS handles that)
- ❌ Prevent replays — same message + same key always gives the same tag
- ❌ Identify which key was used — needs a partner ID alongside
- ❌ Provide non-repudiation — both sides hold the same secret

The gateway closes the replay gap with a **timestamp inside the canonical
message** plus a **skew check** (`HmacVerifier.withinSkew`). Together they
bind every signature to a moment in time.

---

## 4. Hashing vs Encryption

| | **Hashing** | **Encryption** |
|---|---|---|
| Purpose | Verify integrity / produce fingerprint | Hide data so only authorized parties can read it |
| Reversible? | ❌ One-way | ✅ Reversible with a key |
| Output size | Fixed (e.g. 32 bytes for SHA-256) | Roughly the size of the input |
| Keys | None (plain hash) or shared secret (HMAC) | Always — symmetric or asymmetric |
| Used to answer | "Is this the same?" | "Only you can read this" |

### Why this project is **all hashing, no encryption**

The question the gateway needs to answer is *"is this request authentic and
unchanged?"* — that's an integrity + authenticity problem. Confidentiality
is already provided by TLS at the transport layer. Adding encryption on top
would solve nothing and add complexity.

So the partner-auth pipeline uses three pure-hashing primitives:

1. **SHA-256** — turn the partner secret into a stored, irreversible key fingerprint
2. **HMAC-SHA256** — turn `(key, canonical message)` into a per-request tag
3. **Constant-time comparison** (`MessageDigest.isEqual`) — compare tags without leaking timing info

---

## 5. Text Encodings: Hex and Base64

Both are **reversible text encodings for binary data**. Neither is
encryption. Neither adds any security. Their only job is to package raw
bytes (which often contain non-printable characters like `\0`, `\n`, `\x7f`)
inside something safe to put in a database column, an HTTP header, or a log
line.

| | **Hex** | **Base64** |
|---|---|---|
| Alphabet | 16 chars: `0-9 a-f` | 64 chars: `A-Z a-z 0-9 + / =` |
| Bits per char | 4 | 6 |
| 32 bytes encodes to | **64 chars** | **44 chars** |
| Size overhead | 100% | ~33% |
| Case-sensitive? | No | Yes |
| URL-safe? | ✅ | ❌ (needs `base64url` variant) |
| Visually scannable? | ✅ Each byte = 2 chars, fixed boundary | ❌ Bytes blur into 4-char groups |
| Easy to diff in logs? | ✅ Byte-by-byte alignment | ❌ One byte change shifts ~4 chars |

### Why this project uses **hex** for the stored secret hash

```java
byte[] keyBytes = HexFormat.of().parseHex(secretHashHex);
```

Hex is chosen for **operational ergonomics**, not security:

1. **Convention** — every standard tool produces SHA-256 in hex
   (`sha256sum`, `openssl dgst`, `git rev-parse`). A partner computing
   their own key bytes will produce hex out of the box.
2. **Fixed width** — always exactly 64 chars, perfect for `CHAR(64)`
   columns and length constraints.
3. **Case-insensitive** — survives normalization in logs and downstream
   systems without ambiguity.
4. **Diff-friendly** — operators eyeballing two hashes can spot byte-level
   differences immediately.
5. **URL-safe and shell-safe** — no `+`, `/`, or `=` to confuse pipelines.

### Why the **wire signature** uses base64

```java
byte[] presented = Base64.getDecoder().decode(presentedSignatureBase64);
```

The `X-Signature` header has different constraints:

- HTTP headers must be ASCII; raw bytes can't go in unmodified
- Every byte on the wire counts → 44 chars beats 64
- The signature is fresh per request, so human readability matters less
- Most HTTP signing libraries default to base64

So **hex wins for storage, base64 wins for wire format** — same encoding
problem, different optimal answer.

---

## 6. Why SHA-256 is Used — Compared to Alternatives

The project uses SHA-256 in two places: as the inner hash of HMAC, and to
hash the partner secret at rest.

### vs MD5 ❌
Collisions producible in seconds; broken since 2004. Banned for any
cryptographic use.

### vs SHA-1 ❌
Collisions broken by SHAttered (2017); deprecated by NIST and TLS. Git is
moving away from it.

### vs SHA-512
Stronger (256-bit security level vs 128-bit), but **128-bit is already
overkill** for HMAC. SHA-256 is smaller in DB columns and HTTP headers
(44 vs 88 base64 chars) and faster on 32-bit / embedded targets.

### vs SHA-3 (Keccak)
Designed as a backup if SHA-2 broke — and SHA-2 hasn't. SHA-3 is slower in
software on most CPUs because there's no equivalent of Intel SHA-NI / ARMv8
acceleration for it. Library and SDK support is also less universal.

### vs BLAKE2 / BLAKE3
Faster in pure software, but lack hardware acceleration on most CPUs and
aren't on FIPS / NIST approved lists — both matter for partners in
regulated industries (PCI-DSS, FedRAMP, HIPAA).

### vs bcrypt / scrypt / Argon2

Wrong tool for this job. bcrypt, scrypt, and Argon2 are deliberately
*slow* password hashers, designed for a fundamentally different threat
model than what the gateway needs.

#### Side-by-side

|                          | **SHA-256**                                | **bcrypt / scrypt / Argon2**                       |
|---|---|---|
| Family                   | General-purpose cryptographic hash         | Password-hashing function (KDF)                    |
| Designed for             | Integrity, MAC keys, fingerprints          | Storing low-entropy human passwords                |
| Speed                    | ~1 GB/s per core (with SHA-NI)             | Tuned to ~10–100 ms per hash                       |
| Memory cost              | A few KB                                   | Hundreds of MB (scrypt/Argon2)                     |
| Built-in salt            | ❌ (caller adds one if needed)             | ✅ Per-hash salt baked in                          |
| Tunable work factor      | ❌ Fixed cost                              | ✅ Cost parameter increases over time              |
| Output                   | Deterministic 32 bytes                     | Encoded string with salt + cost + digest           |
| Use as an HMAC key       | ✅ Standard                                | ❌ Wrong shape, wrong speed                        |
| Hot-path friendly        | ✅ Free per request                        | ❌ Caps throughput at ~10 req/sec/core             |
| Threat model             | Forgery, tampering, preimage of high-entropy input | Offline brute-force of stolen password hashes |
| Right when input is…     | Machine-generated, ≥128 bits of entropy    | Human-chosen, 30–50 bits of entropy                |

#### Why the slow hashers exist

A human password like `Summer2024!` has maybe 30–40 bits of entropy. An
attacker who steals the hash can try billions of guesses per second on a
GPU and crack it in minutes. The defense is to make *each guess*
expensive — bcrypt, scrypt, and Argon2 deliberately take tens to
hundreds of milliseconds (and, for scrypt/Argon2, hundreds of MB of RAM)
per hash, so a billion guesses now takes years. They also bake in a
**per-hash salt** so identical passwords don't share a hash, defeating
rainbow tables.

#### Why that's wrong here

A partner secret in this gateway is 256 bits of random bytes. Even at a
trillion SHA-256 guesses per second, brute-forcing it would take longer
than the age of the universe — the entropy itself is the defense, no
slowdown needed. And the gateway runs HMAC-SHA256 on **every** incoming
request: swapping in a 100 ms hash would cap throughput at ~10 req/sec
per core and add 100 ms of latency to every call, for zero security
gain. The salt and tunable cost factor — the things that make bcrypt
valuable for passwords — buy nothing when the input is already
maximally random and unique per partner.

> **Rule of thumb:** slow hashes for things humans choose, fast hashes
> for things machines generate.

### Why SHA-256 wins for this gateway

| Property | Why it matters here |
|---|---|
| **Cryptographically secure in 2026** | Both collision and preimage resistance hold — necessary for HMAC and for safe storage of `SHA-256(secret)` |
| **256-bit output** | Exactly the right size for an HMAC key — strong without bloating storage |
| **Fast** | Hardware-accelerated (Intel SHA-NI, ARMv8); HMAC-SHA256 is essentially free per request |
| **Universally available** | Every partner stack — Java, Node, Python, Go, .NET, even shell — supports it natively |
| **Length-extension safe via HMAC** | The vulnerability of bare SHA-256 is neutralized by the HMAC construction the code uses |
| **FIPS 140-approved** | Meets compliance baselines (PCI-DSS, FedRAMP, HIPAA) |
| **25 years of cryptanalysis** | Known attack surface, well-understood margins |

---

## 7. The Stored Hash *Is* the Key — How That Works

The single most-confusing part of the design — once you see it, the whole
file makes sense.

### What's stored

The DB row contains `secretHashHex` — the **SHA-256 of the partner's secret**,
written in hex. The raw secret never appears in the DB or logs.

### What `parseHex` does (and doesn't do)

```java
byte[] keyBytes = HexFormat.of().parseHex(secretHashHex);
```

This is **not reversing SHA-256**. It is hex → bytes — purely a text-format
conversion, like decoding base64. The output is the same 32 bytes that came
out of SHA-256 originally, just back in raw form.

```
"a3f1c2..."  (64-char hex string)
     ↓ parseHex
[0xa3, 0xf1, 0xc2, ...]  (32 raw bytes — the digest itself)
```

### Why the gateway can sign without knowing the original secret

The gateway and partner agree on the **same 32-byte HMAC key** without ever
sharing the original secret over the wire:

| Side | Has | Computes |
|------|-----|----------|
| Partner | the raw secret | `key = SHA-256(secret)` then HMAC |
| Gateway | only `SHA-256(secret)` from DB | uses it directly as HMAC key |

Both sides arrive at the same bytes by **forward computation**. Nothing is
ever reversed. The one-way property of SHA-256 still holds — and that's the
whole point.

### What this protects against

If the partner DB leaks, the attacker gets `SHA-256(secret)`:

| Attacker can... | Why / Why not |
|---|---|
| ✅ Forge requests **to this gateway** | The hash *is* the key here |
| ❌ Recover the original secret | SHA-256 is one-way |
| ❌ Authenticate to other systems where the partner reused the original secret | They never see the original |

This is **defense in depth** — a DB leak compromises the partner's
relationship with this one gateway, not their broader credential surface.

The file's own comment notes the production refinement:

> *For real production, the secret would live in a KMS / sealed secret
> store rather than a DB column.*

In that stricter design even `SHA-256(secret)` never leaves the KMS — the
gateway calls out for HMAC computation rather than holding key material.

---

## 8. Walk-Through: `HmacVerifier.java`

Mapping every line back to the concepts above:

```java
public boolean verify(Partner partner, String timestamp, String method,
                      String path, byte[] body, String presentedSignatureBase64) {
    if (!partner.active()) return false;                 // (1)
    if (!withinSkew(timestamp)) return false;            // (2) freshness
    byte[] canonical = buildCanonicalMessage(...);       // (3) deterministic message
    if (matches(partner.secretHashHex(), canonical, presentedSignatureBase64))
        return true;                                     // (4) current key
    if (partner.previousSecretValid(Instant.now())
        && matches(partner.previousSecretHashHex(), ...))
        return true;                                     // (5) rotation grace
    return false;
}
```

1. **Active partner check** — cheap rejection before any crypto runs.
2. **Anti-replay** — HMAC alone can't stop replays; the timestamp is part
   of the signed message *and* re-checked against a configurable skew.
3. **Canonical message** — both sides build the exact byte sequence
   `partnerId\ntimestamp\nMETHOD\npath\nbody`. Field order, separators,
   and uppercase method are all critical: any difference breaks the tag.
4. **Verify against current key** — `matches(...)` does
   hex → bytes → HMAC-SHA256 → constant-time compare.
5. **Rotation tolerance** — during a rotation window, both old and new
   keys validate, so partners can roll keys without coordinated downtime.

The `matches` helper:

```java
byte[] keyBytes = HexFormat.of().parseHex(secretHashHex);   // hex → 32 bytes
byte[] expected = hmacSha256(keyBytes, canonical);          // server-side tag
byte[] presented = Base64.getDecoder().decode(presentedSignatureBase64);
return MessageDigest.isEqual(expected, presented);          // constant-time
```

`MessageDigest.isEqual` matters: a naive `Arrays.equals` short-circuits on
the first differing byte, leaking timing information that an attacker could
use to forge a valid tag byte-by-byte. Always use a constant-time comparator
when comparing MAC tags.

---

## 9. TL;DR

- **SHA-256** is a one-way hash producing 32-byte digests. The project uses it
  twice: to derive the HMAC key from the partner secret, and as the inner
  hash of HMAC-SHA256.
- **HMAC-SHA256** signs every partner request. It proves authenticity and
  integrity in one tag and is immune to length-extension attacks that would
  break a naive `SHA-256(secret ‖ message)`.
- **Encryption is not used** in the auth path — TLS handles confidentiality.
  All three primitives in `HmacVerifier` (SHA-256 key derivation,
  HMAC-SHA256 signing, constant-time compare) belong to the hashing family.
- **Hex** encodes the stored secret hash because it's what every standard
  tool produces, fits a fixed-width DB column, and is easy to compare in
  logs. **Base64** encodes the wire signature because every byte in the
  HTTP header counts and humans rarely read it.
- The stored `SHA-256(secret)` *is itself the HMAC key*. Both sides derive
  it by forward computation; SHA-256 is never reversed. If the DB leaks,
  the original secret stays protected — defense in depth.
- **Replay protection** comes from the timestamp inside the signed message
  and the `withinSkew` check, not from HMAC itself.
- **Constant-time comparison** (`MessageDigest.isEqual`) is what prevents
  timing attacks during signature verification.
