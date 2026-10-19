---
type: Concept
title: Demo authenticator — architecture
description: How demo sign-in replaces OIDC in a demo bundle through the same SPI slots, and what keeps it out of real bundles.
resource: providers/inspecto-demo-auth/
tags: [module, demo-auth, architecture, spi, packaging]
timestamp: 2026-10-19T00:00:00Z
---

# Demo authenticator — architecture

Demo sign-in is a drop-in replacement for [oidc](../oidc/architecture.md): it fills the same two `ServiceLoader`
slots (`Authenticator`, `TokenRelay`, contracts in [auth-spi](../auth-spi/index.md)) and adds no route.

```
SPA sign-in picker --(code=demo:<id>)--> POST /auth/exchange --> DemoTokenRelay  (mints HMAC tokens)
SPA API call --(Bearer)--> ControlApi authenticate --> DemoAuthenticator --> Subject
                                                      |-- roles from the bound Space's roles.toon
                                                      |-- Access Profile denials removed
                                                      '-- held roles -> sharing / policy engine
```

Everything after authentication — capability gates, Access Profiles, sharing, and the Advanced
[policy](../policy/architecture.md) engine — runs unchanged, so a demo exercises the real access model.

## Isolation from real bundles

| Layer | Mechanism |
|---|---|
| Build | A module in the reactor but in no edition's module set (`offerings/*.toon`); only `inspecto/package.ps1 -DemoAuth` stages it, for Advanced or Preview |
| Classpath | The demo branch removes `inspecto-oidc.jar`, so a bundle never carries two Authenticators |
| CI | `tools/check-demo-auth-isolation.mjs` fails if the jar enters any edition module set or is named in `package.ps1` outside an `if ($DemoAuth …)` block |
| Runtime | `LoopbackOnly` refuses to load unless `-Dcontrol.bind` is loopback; the Authenticator is resolved at boot, so boot fails instead of serving an open sign-in |

## State

No persistent state. The HMAC secret lives in memory for the JVM's lifetime; the roster is read from each
Space's config on every call.

Operator view: [admin-guide](admin-guide.md); local run recipe:
[admin-guide-local-testing](../../layer/platform/admin-guide-local-testing.md).
