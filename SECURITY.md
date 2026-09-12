# Security model

## Server is niet vertrouwd met plaintext

Het ontwerp gaat ervan uit dat de server ciphertext mag bewaren en synchroniseren, maar de inhoud niet mag kunnen ontsleutelen.

### Device keys

Per vault/device-session:

- Ed25519 private key: lokaal secure storage;
- Ed25519 public key: server voor request/record verificatie;
- X25519 private key: lokaal secure storage;
- X25519 public key: server, bedoeld voor toekomstige pairing key-envelopes;
- VaultKey (32 bytes): lokaal secure storage;
- activiteiten: XChaCha20-Poly1305 ciphertext op de server.

## Request signing

Canonical request:

```text
AW-REQUEST-V1\n
METHOD\n
/path?query\n
unix_timestamp\n
base64url_nonce\n
sha256_hex(raw_body)
```

## Record signing

```text
AW-RECORD-V1\n
vaultId\n
recordId\n
revision\n
keyEpoch\n
0-or-1-deleted\n
base64url_nonce_or_empty\n
sha256_hex(ciphertext_or_empty)
```

## XChaCha20-Poly1305

`cryptography-kotlin` biedt ChaCha20-Poly1305 met een 12-byte nonce. Deze app implementeert de standaard XChaCha-constructie:

1. HChaCha20(key, eerste 16 bytes nonce) -> subkey;
2. IETF nonce = `00000000 || laatste 8 bytes nonce`;
3. ChaCha20-Poly1305(subkey, nonce12).

Dit sluit aan bij de 24-byte nonce van de huidige PHP/libsodium smoke-test.

## Open punten vóór publieke release

- pairing/grant protocol formeel reviewen;
- server `vault_devices` many-to-many migratie;
- key epoch rotation bij revoke;
- signed grants / publieke writer keys voor sterkere cross-device auditability;
- rate limiting, push privacy en audit event retention;
- externe security review/pentest.
