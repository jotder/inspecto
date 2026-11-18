---
type: Guide
title: File-keystore secrets provider — admin guide
description: Use FILE and KEYSTORE secret references, create the keystore, set the secrets.keystore.* flags, rotate, and troubleshoot.
resource: providers/inspecto-secrets/
tags: [module, secrets, admin-guide, keystore]
timestamp: 2026-10-19T00:00:00Z
---

# File-keystore secrets provider — admin guide

Ships as `inspecto-secrets.jar` in Standard, Advanced and Preview bundles. Nothing to switch on: write a
reference wherever a credential is asked for (connection profiles, `auth.oidc.clientSecret`).

## 1. Reference forms

| Reference | Resolves to | Edition |
|---|---|---|
| `${ENV:NAME}` | environment variable | all |
| `${SYS:prop}` | JVM system property | all |
| `${FILE:/run/secrets/db_pw}` | file contents, one trailing newline stripped | Standard and up |
| `${KEYSTORE:db_pw}` | a secret-key entry from the configured keystore | Standard and up |

A plain literal is accepted but discouraged.

## 2. Mounted files (Docker / Kubernetes secrets)

Mount the secret read-only and readable by the service account; reference the absolute path. The file is
re-read on each resolve, so replacing it rotates the secret without a restart.

## 3. Java KeyStore

| Flag | Default | Meaning |
|---|---|---|
| `-Dsecrets.keystore.path` | none (KEYSTORE references stay unresolved) | Keystore file |
| `-Dsecrets.keystore.type` | `JCEKS` | Any JVM keystore type that holds secret-key entries (for example `PKCS12`) |
| `-Dsecrets.keystore.password` | none | Store and key password; give a reference such as `${ENV:INSPECTO_KS_PW}` or `${FILE:/run/secrets/ks_pw}` |

Create an entry with the JDK `keytool` (it prompts for the value, keeping it off the command line):

```
keytool -importpass -alias db_pw -storetype JCEKS -keystore secrets.jceks
```

Use the same password for the store and the entry. Protect the file with OS permissions and back it up with
the deployment's other secrets, not with Space data. Rotation: replace the entry or the file; the next
resolve picks it up (the older rotation runbook is `docs/archived-documents/secret-rotation-runbook.md`).

## 4. Troubleshooting

| Symptom | Cause |
|---|---|
| Refusal naming "Standard and Advanced features (inspecto-secrets module)" | Basic bundle, or the jar is missing from `modules.list`. Use `${ENV:…}` or `${SYS:…}` there. |
| A connection test reports the reference as unresolvable | File missing or unreadable by the service user; no `secrets.keystore.path`; wrong password or type; alias absent or not a secret-key entry. The cause is deliberately not logged, so check each in turn. |
| The value has a stray character | The file ends in more than one newline or starts with a BOM; only one trailing newline is stripped. |
