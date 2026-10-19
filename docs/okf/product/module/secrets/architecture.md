---
type: Concept
title: File-keystore secrets provider — architecture
description: Where the secrets provider sits between configuration references, SecretResolver and the edition classpath.
resource: providers/inspecto-secrets/
tags: [module, secrets, architecture, spi, editions]
timestamp: 2026-10-19T00:00:00Z
---

# File-keystore secrets provider — architecture

```
config reference                    core (every edition)                    inspecto-secrets.jar
"${KEYSTORE:db_pw}" --> SecretResolver.resolve --ENV/SYS--> System.getenv / System.getProperty
                                  |
                                  +--FILE/KEYSTORE--> ServiceLoader<SecretsProvider> --> FileKeystoreSecretsProvider
                                                        (none found: refuse, naming the edition)
```

* **Contract** — `SecretsProvider` in `spi/inspecto-auth-spi/src/main/java/com/gamma/auth/secrets/SecretsProvider.java`
  (owned by [auth-spi](../auth-spi/index.md)); the resolver sits in the same package.
* **Discovery** — `ServiceLoader`, once, cached in `SecretResolver`. Presence of the jar is the switch.
* **Editions** — staged in Standard and every higher edition (`offerings/standard.toon`); Basic carries no
  provider, so `${FILE:…}` and `${KEYSTORE:…}` are refused there with a message naming the inspecto-secrets module.
* **Consumers** — anything that calls `SecretResolver`: connector connection profiles
  ([connectors](../connectors/index.md)), the OIDC client secret (`auth.oidc.clientSecret`,
  [oidc admin guide](../oidc/admin-guide.md)), and the keystore password itself (a reference resolved recursively).
* **No state** — no cache of resolved values; each resolve reads the file or keystore again.
