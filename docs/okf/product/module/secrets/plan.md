---
type: Plan
title: File-keystore secrets provider — plan of record
description: SEC-07 requirement for mounted-file and Java KeyStore secret references, what shipped and what stays deferred.
resource: providers/inspecto-secrets/
tags: [module, secrets, plan, sec-07]
timestamp: 2026-10-19T00:00:00Z
---

# File-keystore secrets provider — plan of record

**Requirement (SEC-07):** configuration never stores a credential; it stores a reference that
`SecretResolver` expands when a connector needs it. `${ENV:…}` and `${SYS:…}` are core (every edition);
`${FILE:…}` and `${KEYSTORE:…}` are Standard and above and arrive through the `SecretsProvider` contract
(gated 2026-09-06; `spi/inspecto-auth-spi/src/main/java/com/gamma/auth/secrets/SecretResolver.java`).

## Built

* `FileKeystoreSecretsProvider` serving `FILE` and `KEYSTORE`
  (`providers/inspecto-secrets/src/main/java/com/gamma/secrets/FileKeystoreSecretsProvider.java`).
* A bundle without the module refuses those schemes with a message naming the edition, never a silent null.
* Split out of the former combined security module into its own jar by module-reorg decision D-MR6
  ([oidc architecture](../oidc/architecture.md)).

## Decided

* Vault or a cloud KMS scheme ships only when a client policy requires it ("SEC-07 Vault/KMS only when a
  client policy requires it", in the decided-and-kept list of [BACKLOG](../../../../BACKLOG.md)).
* Resolved values are never logged; an absent or unreadable secret resolves to null.

## Left

Nothing open in this module. Rotation is an operator procedure (see [admin-guide](admin-guide.md)).
