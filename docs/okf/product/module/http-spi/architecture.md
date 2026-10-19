---
type: Concept
title: Route module contract — architecture
description: How http-spi lets optional modules contribute Control API routes, save-time validators, delete hooks and governable kinds — discovered by ServiceLoader, appended after built-ins, stubbed with 503 when absent.
resource: spi/inspecto-http-spi/
tags: [module, http-spi, spi, architecture, control-api, editions]
timestamp: 2026-10-19T00:00:00Z
---

# Route module contract — architecture (`module.http-spi`)

## Position

`inspecto-http-spi` is a contract module of the [SPI layer](../../layer/spi/architecture.md) (`buildRole:
contract`, `offeringRole: base`, `bindingTime: build`). It depends on `inspecto-auth-spi` (for `Subject`,
`RequestAttrs`, `ApiException`, `WriteRootProvider`). The host, `ControlApi`, implements `ApiContext`; feature,
add-on and Link Analysis modules implement `RouteModule` and never depend on the host.

```
 ControlApi boot
   1. built-in RouteModules (fixed list in ControlApi)
   2. OptionalSpi.all(RouteModule.class)  -> register(api)    (appended; duplicates refused)
   3. HostBootHooks                                            (work that needs the host)
   4. stubs: for each provides.routes of an ABSENT module, api.stub(...) -> 503
   /bootstrap features = featureIds() of modules whose register() returned
```

## Design decisions

- **Open/closed host.** Until EDG-01 (2026-09-07) `RouteModule`, `ApiContext`, `Handler` and `ApiException` were
  package-private, so every route group had to live in the core and shipped in every bundle. Making them public
  let optional modules carry their own HTTP surface (see
  `docs/archived-documents/plans-archive/edg-01-edition-gating-plan.md`).
- **Order is load-bearing.** Route matching is first-match; ServiceLoader order is unspecified, so discovered
  modules are appended after the built-ins and a discovered `(method, pattern)` that collides with a built-in is a
  boot failure, never a silent shadow.
- **Fail-soft discovery.** Route modules are loaded with `OptionalSpi`: a module jar that cannot link on the host
  is skipped with a WARN and its routes are stubbed, so the server still boots.
- **Honest feature flags.** `hasRoute` ignores stubs and `featureIds()` is read only after a clean `register`, so
  `/bootstrap` reports a feature only when its routes actually bound.
- **Manifest equals code.** A module's `module.toon` `provides.routes` is what the host stubs when the module is
  absent; `ModuleRoutesParity` asserts it equals what `register` binds, in both directions.
- **Component hooks instead of processor edits.** Validators, delete hooks and governable kinds let a module own
  its config kind without editing the processor; absence is fail-closed for governance (422) and lenient for
  validation (opaque config accepted).
- **Platform test kit.** The test-jar (`com.gamma.control.testkit`) carries `FakeApiContext`, the abstract
  `RouteModuleContract` TCK and `ModuleRoutesParity`; feature modules consume it with `type test-jar`, scope `test`
  (`spi/inspecto-http-spi/pom.xml`). See [architecture-module-taxonomy](../../layer/platform/architecture-module-taxonomy.md).

## Who implements what

The provider list for every http-spi seam is in the [SPI layer architecture](../../layer/spi/architecture.md#who-implements-what).

## Failure modes

| Symptom | Cause |
|---|---|
| Boot refused: duplicate route | A discovered module registers a `(method, pattern)` a built-in or another module owns. |
| Feature routes answer 503 with an explanation | The module is absent from the bundle, or failed to link (WARN in the log). |
| Feature missing from `/bootstrap` features | `register` threw, or the Space switched the feature off in `modules.toon`. |
| Approval policy refused 422 naming a kind | The module that provides that governable kind is not installed. |

## References

- `spi/inspecto-http-spi/src/main/java/com/gamma/spi/http/RouteModule.java`
- `spi/inspecto-http-spi/src/test/java/com/gamma/control/testkit/ModuleRoutesParity.java`
- `inspecto/src/main/java/com/gamma/control/ControlApi.java`
- [API](api.md) · [Features](features.md)
