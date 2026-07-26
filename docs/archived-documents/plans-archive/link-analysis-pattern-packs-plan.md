# Link Analysis V2 (c) — pattern packs as authored components

**Status:** ACTIVE — **code-complete, NOT VERIFIED** (paused 2026-07-26 by operator; no `mvn test`, no
`npm run test:ci`, no preview run). **Backlog row:** §1.5 Link-analysis V2 (c) · **Decision:** §2 D16 —
**OVERTURNED**, see below.

## The decision that changed

§2 **D16** recorded *"a dedicated system Space owns the domain-seeded pattern packs"*, because seeding packs
into user Spaces forks them per Space and a fix to a shipped pattern could never reach the copies.

**Overturned 2026-07-26 (operator):** per-Space forking is acceptable — packs are per-Space content and the
central-fix guarantee is not required. Pattern packs become an **ordinary `ComponentStore` kind** authored
per Space, and no reserved system Space is built.

Two things that killed the system-Space shape are worth keeping on record, because they are the reason the
"reserved sentinel" idea is more expensive than it looks:

- A sentinel dir is reserved *structurally*, not by a name list: `SpaceId.VALID`
  (`inspecto/src/main/java/com/gamma/service/SpaceId.java:13`) forbids a leading underscore, and
  `SpaceManager.discover` only admits dirs containing `config/` (`SpaceManager.java:91`). So `_shared`/
  `_templates` are invisible to `GET /spaces` because they were **never admitted**, not because they are filtered.
- ⚠ Therefore a `_system/` holding `config/registry/pattern-packs/` would **pass** discover's filter and then
  die in `SpaceBootstrap.load` at `SpaceId.of(root.id())` (`SpaceBootstrap.java:25`), logging a spurious
  `Skipping space dir` WARN **on every boot**. A sentinel must hold its payload *without* a `config/` subdir
  (as `_shared/` does), which in turn means `ComponentStore` can't be reached through `/spaces/{id}/…` and a
  dedicated cross-space read route would be required. All of that cost disappears with the per-Space shape.

## What gets built

A `pattern-pack` component kind, read by the Link Analysis toolbox with today's const as the fallback.

1. **Backend — two lines, no new endpoint** (the D6 precedent: a `ComponentStore` kind adds no route).
   - `ComponentStore.WRITABLE_TYPES` (`inspecto-engine/.../ComponentStore.java:46`) += `"pattern-pack"`.
   - `ComponentRegistry.TYPE_BY_DIR` (`.../ComponentRegistry.java:44`) += `"pattern-packs" → "pattern-pack"`.
   - ⚠ Reads need this too: `list`/`get` call `validateType` (`ComponentStore.java:107,113`), so a kind
     absent from `WRITABLE_TYPES` is **unreadable**, not merely read-only. There is no read-only-kind concept
     and this change does not invent one — writes come with the kind, gated by the generic
     `canAuthorWorkbench` on POST/PUT/DELETE (`ComponentRoutes.java:34-40`).
   - Free for the ride: MET-5 version history, ETags, `ComponentAccess` share filtering, `NoteTargets`,
     `InspectoTools`, and `BundleRoutes.supported()` — all read `WRITABLE_TYPES` dynamically.
   - **Deliberately NOT touched:** `BundleRoutes.APPLY_ORDER` / `INTEGRITY_KINDS` (a pack references no other
     component, so there are no broken refs to check; `kindOrder()` sorts unlisted kinds last, which is safe),
     `Exchange.DERIVED_KINDS` (a pack reads no Dataset), `SPACE_SUBDIRS` (`registry/pattern-packs/` is created
     lazily by `ComponentStore.write`).
   - **No `validateKind` branch.** Only `findings-spec` validates today (`ComponentRoutes.java:352-384`);
     pack content stays free-form TOON and the UI mapper skips a malformed pack. Cheaper guard, same outcome.
2. **Seed content — migrate today's 6 packs** to `spaces/{default,demo,ucc}/config/registry/pattern-packs/*.toon`
   — the three **tracked** spaces, 18 files. ⚠ **`spaces/uat/` is gitignored** (`.gitignore:63`) and is
   therefore deliberately NOT seeded here; it is built by `tools/seed-uat.ps1`, which has no registry step.
   Do not "fix" the missing uat packs by adding the files — they cannot be committed, so the only real
   options are a registry step in that script or leaving uat on the `PATTERN_PACKS` fallback (which works).
3. **UI** — `ComponentType` union += `'pattern-pack'`; the toolbox fetches and maps `content → PatternPack`,
   falling back to `PATTERN_PACKS`. Mock: `STUDIO_KINDS` += the kind, `MOCK_STORE_KEY` v19 → v20, seed the 6 packs.
   - ⚠ `patternPacks` is a **`signal`** seeded with the const and `.set()` on a non-empty response. The first
     draft of this plan said to keep it a plain mutable array to avoid touching call sites — **that was wrong**:
     the toolbox is `OnPush`, so reassigning a plain field from an HTTP callback never re-renders. Cost: three
     call sites gained `()` (the field, `loadPatternPack`, the template `@for`, and one spec assertion).
   - ⚠ The toolbox spec's `make()` provides no `HttpClient`; injecting `ComponentsService` without providing
     something fails **every** test in the file at `createComponent`, not just the pattern-pack ones. Resolved
     by stubbing `ComponentsService` (`{list: () => of(packs)}`) rather than wiring real HTTP — `make()` takes
     an optional second arg so one test can supply authored packs.
   - The toolbox is a **stateful feature component**, so injecting a service is in-bounds; the `angular-ui` §4
     "presentational, no HTTP" rule scopes to shared components in `inspecto/components/`.
   - **Deliberately NOT widened:** `COMPONENT_TYPES` (flow palette), `REGISTRY_KINDS`, `BundleKind`,
     `platform-kinds`, `PlaceableKind`, `ExchangeKind` — each is an independent opt-in list and none was asked for.

## Verify

1. `mvn -o clean test -Pedition-enterprise` → green, no new WARN on boot. *(No test asserts an exhaustive
   `WRITABLE_TYPES`; `NoteCoreTest.java:96` is a `containsAll`, so it stays true.)*
2. `npm run test:ci` **exit code 0** — the real type gate for the union change (`npm run build` skips specs).
3. Offline preview `/studio/link-analysis`: the pack dropdown lists the 6 seeded packs served over
   `GET /components/pattern-pack`; read back from `localStorage['inspecto.mock.v20']`, not inferred from the UI.
4. Fallback path: with the kind's data absent, the dropdown still lists the 6 built-ins.
