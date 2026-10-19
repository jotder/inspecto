---
type: Reference
title: File-keystore secrets provider — implementation spec
description: How FileKeystoreSecretsProvider resolves FILE and KEYSTORE references and how it fails.
resource: providers/inspecto-secrets/
tags: [module, secrets, spec]
timestamp: 2026-10-19T00:00:00Z
---

# File-keystore secrets provider — implementation spec

One class, `providers/inspecto-secrets/src/main/java/com/gamma/secrets/FileKeystoreSecretsProvider.java`,
registered in `providers/inspecto-secrets/src/main/resources/META-INF/services/com.gamma.auth.secrets.SecretsProvider`.
Manifest: `providers/inspecto-secrets/src/main/resources/META-INF/inspecto/module.toon` (`provides: SecretsProvider`,
`bindingTime: boot`).

## `supports(scope)`

True for the upper-cased schemes `FILE` and `KEYSTORE` only.

## `FILE` — `${FILE:/path}`

* Reads the whole file as UTF-8.
* Strips exactly one trailing `\r\n` or `\n` (secret files written by `echo` or Kubernetes keep a newline).
* Missing or unreadable file: returns `null`. Nothing is logged.

## `KEYSTORE` — `${KEYSTORE:alias}`

* Keystore from `-Dsecrets.keystore.path` (no path: `null`), type `-Dsecrets.keystore.type` (default `JCEKS`).
* Store password from `-Dsecrets.keystore.password`, itself resolved through `SecretResolver`, so it can be
  `${ENV:…}` or `${FILE:…}`; the same password unlocks the key entry.
* The alias must be a `SecretKeyEntry`; its encoded bytes are returned as UTF-8. A private-key or certificate
  entry, an absent alias, a wrong password or a damaged store: `null`.
* The keystore is opened on every call (no cache), so a replaced keystore takes effect on the next resolve.
* The password char array is zeroed in a `finally` block.

## Invariants

* Never logs or throws with a resolved value; every failure is `null` ("unresolved").
* `SecretResolver` picks the first discovered provider whose `supports` is true; with none it throws
  `IllegalStateException` naming the edition.

## Tests

`providers/inspecto-secrets/src/test/java/com/gamma/secrets/FileKeystoreSecretsProviderTest.java`
([tests-inventory](tests-inventory.md)).
