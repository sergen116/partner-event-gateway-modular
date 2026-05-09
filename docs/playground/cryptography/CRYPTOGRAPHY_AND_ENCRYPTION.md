# Cryptography and Encryption — A Clean Summary

## 1. Cryptography vs Encryption

- **Cryptography** is the broad science of securing information. It covers confidentiality, integrity, authenticity, and non-repudiation.
- **Encryption** is one tool inside cryptography. It transforms readable data (*plaintext*) into unreadable data (*ciphertext*) so only authorized parties can read it back.

> Encryption ⊂ Cryptography. Cryptography also includes hashing, signing, key exchange, and MACs.

---

## 2. The Four Goals of Cryptography

| Goal | Meaning | Typical Tool |
|---|---|---|
| **Confidentiality** | Only intended recipients can read it | Encryption (AES, RSA) |
| **Integrity** | Data wasn't modified in transit | Hashing, MAC |
| **Authenticity** | Message is from who it claims to be | Digital signatures, MAC |
| **Non-repudiation** | Sender can't deny sending it | Digital signatures |

---

## 3. Encryption: Two Families

### 3.1 Symmetric Encryption (one shared key)

The same key encrypts and decrypts. Fast, used for bulk data.

```
Plaintext --[Key]--> Ciphertext --[Same Key]--> Plaintext
```

- **Algorithms:** AES (standard), ChaCha20
- **Strength:** Fast, efficient, great for large data
- **Weakness:** How do you securely share the key?

### 3.2 Asymmetric Encryption (public/private key pair)

Two mathematically linked keys. What one encrypts, only the other decrypts.

```
Plaintext --[Public Key]--> Ciphertext --[Private Key]--> Plaintext
```

- **Algorithms:** RSA, ECC (Elliptic Curve), Ed25519
- **Strength:** Solves the key-distribution problem
- **Weakness:** Slow — not suited for bulk data

### 3.3 In Practice: Hybrid Encryption

Real systems (TLS, PGP, JWE) combine both:
1. Use **asymmetric** crypto to securely exchange a **symmetric** session key.
2. Use the **symmetric** key for the actual data.

You get the security of asymmetric + the speed of symmetric.

---

## 4. Hashing (One-Way Functions)

A hash maps any input to a fixed-size fingerprint. **Not encryption** — there is no "decrypt." It's used for integrity, not confidentiality.

- **Properties:** deterministic, fast, irreversible, collision-resistant
- **Algorithms:** SHA-256, SHA-3, BLAKE3
- **For passwords:** use slow, salted hashes — bcrypt, scrypt, Argon2 (never plain SHA-256)

```
"hello" --> SHA-256 --> 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
```

---

## 5. Digital Signatures

Proves *who* signed something and that it wasn't altered.

```
Sign:   message + private key  --> signature
Verify: message + signature + public key --> valid? yes/no
```

Used in: TLS certificates, JWTs, software updates, Git commits, blockchain.

---

## 6. MAC (Message Authentication Code)

Like a signature, but with a **shared symmetric key** instead of a key pair. Faster, but both sides need the same secret.

- **HMAC** is the most common construction (e.g., HMAC-SHA256).
- Used for API request signing, JWT (HS256), session tokens.

---

## 7. Key Exchange

How do two parties agree on a shared secret over an untrusted channel?

- **Diffie-Hellman (DH)** and its modern variant **ECDH** let two parties derive the same secret without ever transmitting it.
- Foundation of TLS 1.3 handshakes.

---

## 8. Encryption Modes & AEAD

A block cipher like AES needs a **mode** to handle data longer than one block.

- **Old/risky modes:** ECB (leaks patterns), CBC (needs separate MAC)
- **Modern AEAD modes:** combine encryption + integrity in one step
  - **AES-GCM** — industry standard
  - **ChaCha20-Poly1305** — fast on devices without AES hardware

> Always prefer AEAD. Never roll your own combination of cipher + MAC.

---

## 9. TLS in 30 Seconds

When you visit `https://...`:
1. Server presents a **certificate** (signed by a CA) — proves identity.
2. Client and server perform an **ECDH** key exchange — derive a shared session key.
3. All further traffic is encrypted with **AES-GCM** (or ChaCha20-Poly1305).
4. Signatures confirm authenticity, AEAD confirms integrity.

That's hybrid encryption + signatures + key exchange working together.

---

## 10. Practical Rules of Thumb

- **Don't invent your own crypto.** Use vetted libraries (libsodium, Tink, JDK `javax.crypto`).
- **Use AEAD** (AES-GCM, ChaCha20-Poly1305) for encryption.
- **Use Argon2/bcrypt/scrypt** for passwords. Never plain hashes.
- **Use TLS 1.3** for transport. Disable old versions and weak ciphers.
- **Rotate keys.** Treat keys as disposable, not eternal.
- **Random ≠ pseudo-random.** Use a CSPRNG (`SecureRandom` in Java, `crypto.randomBytes` in Node).
- **Public key for encrypting / verifying. Private key for decrypting / signing.** Never swap them.

---

## 11. Quick Mental Map

```
Cryptography
├── Encryption
│   ├── Symmetric  (AES, ChaCha20)
│   └── Asymmetric (RSA, ECC)
├── Hashing       (SHA-256, Argon2)
├── Signatures    (RSA-PSS, ECDSA, Ed25519)
├── MACs          (HMAC, Poly1305)
└── Key Exchange  (DH, ECDH)
```

If you remember just one thing: **encryption hides data, hashing fingerprints it, signatures prove who made it, and key exchange lets strangers agree on a secret.**
