---
type: TestsInventory
title: OIDC authenticator and token relay — tests inventory
item: module.oidc
description: Generated list of the test classes for module.oidc, with how to run them.
tags: [tests, module]
---

# OIDC authenticator and token relay — tests inventory (`module.oidc`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `providers/inspecto-oidc/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.oidc`](../../../../../product-map/module/oidc.toon).

8 test class(es): 7 unit, 1 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl providers/inspecto-oidc
mvn -o test -pl providers/inspecto-oidc -Dtest=AuthenticatorDiscoveryFailClosedTest,OidcAgainstRealProviderTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `AuthenticatorDiscoveryFailClosedTest` | `com.gamma.oidc` | unit | `providers/inspecto-oidc/src/test/java/com/gamma/oidc/AuthenticatorDiscoveryFailClosedTest.java` | What SPI discovery does when this module is on the classpath but <b>misconfigured</b> — the case a |
| `OidcAgainstRealProviderTest` | `com.gamma.oidc` | unit | `providers/inspecto-oidc/src/test/java/com/gamma/oidc/OidcAgainstRealProviderTest.java` | {@link OidcAuthenticator} against a <b>real OpenID provider over real HTTPS</b> — the one path this |
| `OidcAuthenticatorTckTest` | `com.gamma.oidc` | unit | `providers/inspecto-oidc/src/test/java/com/gamma/oidc/OidcAuthenticatorTckTest.java` | {@link OidcAuthenticator} against the platform's Authenticator TCK (MODULE-REORG-1 P5b): an in-memory JWKS, no network. |
| `OidcAuthenticatorTest` | `com.gamma.oidc` | real-HTTP control-plane test | `providers/inspecto-oidc/src/test/java/com/gamma/oidc/OidcAuthenticatorTest.java` | Offline (no network) tests for {@link OidcAuthenticator}: a self-signed RSA key pair stands in for |
| `OidcKeycloakAgainstRealProviderTest` | `com.gamma.oidc` | unit | `providers/inspecto-oidc/src/test/java/com/gamma/oidc/OidcKeycloakAgainstRealProviderTest.java` | {@link OidcAuthenticator} with {@code auth.oidc.provider=keycloak} + {@code auth.oidc.discoveryUrl} |
| `OidcProviderPresetTest` | `com.gamma.oidc` | unit | `providers/inspecto-oidc/src/test/java/com/gamma/oidc/OidcProviderPresetTest.java` | The provider preset ({@code auth.oidc.provider}) and discovery ({@code auth.oidc.discoveryUrl}): |
| `OidcTokenRelayTckTest` | `com.gamma.oidc` | unit | `providers/inspecto-oidc/src/test/java/com/gamma/oidc/OidcTokenRelayTckTest.java` | {@link OidcTokenRelay} against the platform's TokenRelay TCK (MODULE-REORG-1 P5b): a stand-in IAM that refuses every grant. |
| `OidcTokenRelayTest` | `com.gamma.oidc` | unit | `providers/inspecto-oidc/src/test/java/com/gamma/oidc/OidcTokenRelayTest.java` | Offline tests for {@link OidcTokenRelay}: a local {@link HttpServer} stands in for the IAM's |
