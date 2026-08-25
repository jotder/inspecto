---
name: test-author
description: >
  Write or extend tests for this repo in house style. Trigger on "add tests for X", "test the new
  route/step/component", or any request to raise coverage. Encodes where tests live per module,
  the naming conventions, and the three test idioms: real-HTTP control-plane tests, engine unit
  tests mirroring main packages, and Angular vitest specs (TestBed single-configure rule).
---

# /test-author — tests in house style

## Where tests live

- **Backend:** `src/test/java` mirrors `src/main/java` package-for-package in every Maven module.
  Test class = `<Class>Test.java` next to the mirrored package (e.g. `inspecto-engine/src/test/java/com/gamma/pipeline/ComponentStoreTest.java`). No separate IT/integration suffix — integration-style tests are plain `*Test` classes.
- **Control-plane (`inspecto` module):** `inspecto/src/test/java/com/gamma/control/…` — routes get a
  dedicated test class per resource area.
- **UI:** co-located `*.spec.ts` beside the source file in `inspecto-ui/src/app/**`; route-level spec at
  `app.routes.spec.ts`. Run with `npm run test:ci` (vitest via `@angular/build:unit-test`, jsdom).

## Idiom per layer

1. **ControlApi routes → real-HTTP tests.** Model on `ControlApiConfigWriteTest`: boot on an
   ephemeral port, issue actual requests, **one test per fail-closed gate + the happy path**
   (gate order: write-root 503 → spec/validator 422 → path jail 403 → conflict 409). Happy-path
   assertions must read the payload under the envelope's `data` key — see `/endpoint` skill for
   the envelope trap. Never mock the HTTP layer.
2. **Engine classes → plain JUnit units.** Construct inputs directly; use temp dirs / in-memory
   structures; no Spring context, no network. Contract-style tests (`*ContractTest`) pin cross-module
   invariants (e.g. `BindKindHomeContractTest`).
3. **UI components/services → vitest + TestBed.** One `TestBed.configureTestingModule` **per test** —
   build one fixture and mutate `@Input`s + `detectChanges()` between assertions, never call the
   configure helper twice. Give spec hosts an explicit `changeDetection`. A11y via
   `expectNoA11yViolations` (axe-core) where the skill calls for it.

## Rules

- Mirror existing naming and assertion style of the nearest sibling test — read it first.
- Cover behavior, not implementation details; one logical scenario per `@Test`/`it()`.
- A bug fix lands together with the regression test that reproduces it.
- Don't chase coverage numbers; test what the change makes risky.
