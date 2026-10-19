---
type: Reference
title: Route module contract — Java API
description: The http-spi Java contract — RouteModule, ApiContext, Handler, Envelope, Idempotency and the component hooks (ComponentKindValidator, ComponentDeleteHook, GovernableKindProvider) — with registration and how to ship routes from an optional module.
resource: spi/inspecto-http-spi/
tags: [module, http-spi, spi, api, control-api]
timestamp: 2026-10-19T00:00:00Z
---

# Route module contract — Java API (`module.http-spi`)

Package `com.gamma.spi.http` in `spi/inspecto-http-spi/`. `ControlApi` (`inspecto/src/main/java/com/gamma/control/ControlApi.java`)
is the sole `ApiContext` implementation; everything else here is what an optional module implements or calls.

## Seams (ServiceLoader)

| Interface | Methods | Called by | Registered providers |
|---|---|---|---|
| `RouteModule` | `void register(ApiContext api)`; default `Set<String> featureIds()` | `ControlApi` at boot, after its built-in route list | 11 feature modules, `la/inspecto-la-api` and `la/inspecto-geo-link` — see the [SPI layer](../../layer/spi/architecture.md#who-implements-what) |
| `ComponentKindValidator` | `type()`, `validate(id, content)`, `validateInSpace(writeRoot, dataRoot, id, content)`, `requireNotReserved(writeRoot, type, id, content)` | the processor's component save gate | `AnomalyKindValidator`, `ReconKindValidator`, `RiskScoreKindValidator` |
| `ComponentDeleteHook` | `type()`, `afterDelete(Path writeRoot, String id)` | `DELETE /components/{type}/{id}`, after removal | `ReconComponentDeleteHook` |
| `GovernableKindProvider` | `Set<String> kinds()` | maker-checker approval policy | `EntityListGovernableKinds` |

### Semantics

- **`RouteModule.register`** — bind routes with `api.get/post/put/patch/delete(pattern, handler)`. Matching is
  first-match in registration order; discovered modules are appended after the built-ins, so a discovered route
  can never outrank a built-in one, and a duplicate `(method, pattern)` is refused at boot.
- **`RouteModule.featureIds`** — collected only after `register` returned, so `/bootstrap` reports a feature
  present exactly when its routes bound. Must be a subset of the module's `module.toon` `provides.features`.
- **`ComponentKindValidator`** — refuse with `IllegalArgumentException` (mapped to 422). Without the module the kind
  is still accepted as opaque config, but nothing validates it.
- **`ComponentDeleteHook`** — removes state a module keeps beside a component so a re-created id inherits nothing.
  Refuse with `IllegalArgumentException` (400, unsafe id) or `SecurityException` (403, path jail).
- **`GovernableKindProvider`** — fail-closed by absence: an approval policy naming a kind whose module is absent is
  refused (422).

## `ApiContext` — what a module may use

| Member | Purpose |
|---|---|
| `get` / `post` / `put` / `patch` / `delete(pattern, Handler)` | Register a real route. |
| `stub(method, pattern, Handler)` | Absent-module stub (usually a self-explaining 503); does not make `hasRoute` true. |
| `hasRoute(method, pattern)` | Whether a real handler (not a stub) owns the pattern. |
| `static withCapability(capability, Handler)` | Wrap a handler in a capability gate; mutating routes must use it. |
| `static requireCapability`, `requireCapabilityIn` | In-handler capability checks (global, or in the owning Space). |
| `static resourcePermissions`, `pagination`, `podScoped` | Annotate the response envelope. |
| `body(ex)`, `static query(ex, key)`, `static str(body, key)` | Request parsing. |
| `writeRoot()`, `dataRoot()`, `attachedSpaceRoots()`, `spaceIdOf(root)` | Space roots; `writeRoot()` is `null` when the control plane is read-only. |
| `replay(outer, method, path, body, headers, attrs)` | Re-run a route in-process as the same caller (maker-checker apply). |
| `onClose(Runnable)`, `disabledFeatures()`, `registeredFeatures()` | Lifecycle and feature flags. |
| `static subject`, `actor`, `correlationId`, `attr` | Delegates to `RequestAttrs` in [auth-spi](../auth-spi/api.md). |

`Handler` is `Object handle(HttpExchange ex, Matcher m) throws Exception`; return a JSON-serialisable value, or
`ApiContext.HANDLED` when the handler wrote the response itself. Throw `ApiException(status, [errorCode,] message)`
for an error. `Envelope.shape` wraps results into the v1 `{data, metadata, links, diagnostics}` envelope or v1 error
object for `/api/v1` requests only. `Idempotency` caches the first response of a write carrying `Idempotency-Key`,
scoped to method + path + Space + principal + key and bound to a body hash; modules do not call it directly.

## Shipping routes from an optional module

1. Depend on `inspecto-http-spi` (and its test-jar, `type test-jar`, scope `test`).
2. Implement `RouteModule`; gate every mutating route with `ApiContext.withCapability`.
3. Register the class in `src/main/resources/META-INF/services/com.gamma.spi.http.RouteModule`.
4. Declare the routes under `provides.routes` in the module's `META-INF/inspecto/module.toon` — the host stubs
   exactly those with 503 when the module is absent.
5. Test: subclass `RouteModuleContract` (supply `module()`), and assert `ModuleRoutesParity` so the manifest equals
   what `register` binds. Both run on `FakeApiContext`, with no host.

```java
public final class HelloRoutes implements com.gamma.spi.http.RouteModule {
    @Override public void register(com.gamma.spi.http.ApiContext api) {
        api.get("/hello", (ex, m) -> java.util.Map.of("hello", "world"));
    }
}
```

A new route also needs an entry in `docs/api/openapi-v1.json` and the gates in the `endpoint` skill.

## References

- `spi/inspecto-http-spi/src/main/java/com/gamma/spi/http/RouteModule.java`
- `spi/inspecto-http-spi/src/main/java/com/gamma/spi/http/ApiContext.java`
- `spi/inspecto-http-spi/src/test/java/com/gamma/control/testkit/RouteModuleContract.java`
- [Architecture](architecture.md) · [Features](features.md)
