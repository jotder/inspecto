# Relocatable Space roots — `spaces.ref`, relative `data_root`, relative `<id>.ref` (`SPACE-RELOCATABLE-ROOTS-1`)

Status: **PLAN — awaiting operator approval.** Opened 2026-11-13. Owner: this shift.
Items: `module.config`, `layer/platform`. Supersedes nothing; extends `SPACE-DATA-ROOT-1`.

## 1. Why

An install is a tree under one root (call it `%APP_ROOT%` — the folder holding `inspecto-la\`,
`start-basic.cmd` and the Spaces). The operator wants that tree to run from **any** path: unzip it
somewhere else, double-click the launcher, and every Space boots.

Today most of that already works, and the gaps are narrow. What is already true:

| Behaviour | Where | Already works? |
| --- | --- | --- |
| Launchers resolve everything from the install dir | `start-basic.cmd` uses `%~dp0` | ✅ |
| Default spaces root is `<cwd>/spaces` when no flag and no config args | `ControlApi.main` 564-568 | ✅ |
| A **relative** `-Dspaces.root` is honoured | `SpaceManager.discover`, test `aRelativeSpacesRootStillStampsAnAbsoluteBase` | ✅ |
| Default Space data is `spaces/<id>/{data,audit,duckdb}` | `SpaceRoot.dataRootFor` falls through to `base` | ✅ |
| That default relocates cleanly | `SpaceDataRoot.claim` returns at once when `dataRoot.equals(base)` — **no stamp written** | ✅ |
| `space.toon data_root` is resolved against the base when relative | `SpaceRoot.dataRootFor` 157-165 | ✅ (then refused — see below) |

What does not work, and is the whole of this plan:

- **G1 — a relative `data_root` is computed and then rejected.** `SpaceDataRoot.stake` 106-107 throws
  `space.toon data_root '<v>' must be an absolute path`. So an install whose data sits beside the Spaces
  (`%APP_ROOT%\inspecto-la-data\<id>`, the showcase bundle's layout) can only name it absolutely.
- **G2 — the absolute `data_root` is then stamped with an absolute path.** `stake` 148 writes
  `.inspecto-space` holding `base.toString()`; `ownedBy` compares it canonically on every later boot. Move
  the tree and every Space is refused with `data_root ... belongs to the Space at ...`, then
  `SpaceManager: 0 space(s) booted`. This is the defect the operator hit on 2026-11-13; see
  `.claude/memory/space-data-root-stamp.md`.
- **G3 — `<id>.ref` must hold an absolute path.** `SpaceManager.resolveRef` 225: `must hold an absolute
  path to a Space folder`. A bundle that references Spaces kept beside it cannot do so portably.
- **G4 — there is no way to point the spaces root somewhere else from disk.** Only `-Dspaces.root` /
  `SPACES_ROOT`, which means editing a launcher.

## 2. Decisions (operator, 2026-11-13)

- **D1. The pointer file is `%APP_ROOT%/spaces.ref`** — one line naming the spaces root, absolute or
  relative. Absent ⇒ `%APP_ROOT%/spaces`. Same name and shape as the existing per-Space
  `<spaces root>/<id>.ref`, so there is one convention, not two.
- **D2. The launch flag wins.** `-Dspaces.root` (and `SPACES_ROOT`) keep precedence; `spaces.ref` is
  consulted only when neither is set. No existing deployment or launcher changes behaviour.
- **D3. Every path naming a root may be absolute OR relative, and ONE base resolves them all:
  `%APP_ROOT%`** (operator, revised 2026-11-13 — supersedes the first draft's per-file bases). That holds
  for `spaces.ref`, `space.toon data_root` and `<id>.ref` alike. The form a path is written in carries no
  meaning: a relative one is resolved, then judged by exactly the same rules as an absolute one.
  - **Nothing on disk migrates.** A relative `data_root` is refused today (G1) and a relative `<id>.ref`
    is refused today (G3), so **no existing config holds a relative value in either file**. Choosing
    `%APP_ROOT%` over the Space base is therefore free — there is nothing to re-interpret.
  - ⚠ `SpaceRoot.dataRootFor` 157-165 currently resolves `data_root` against the **Space base**
    (`base.resolve(declared)`). That line changes to resolve against `%APP_ROOT%`. It is reachable from
    `SpaceMigrator` too — check both callers.
- **D4. `%APP_ROOT%` becomes explicit: `-Dapp.root`, defaulting to the process working directory.**
  Resolved once at boot, normalized, and logged on the `SpaceManager` boot line.
  - *Why this is not optional under D3.* Today the working directory is load-bearing only for the
    `./spaces` default (`ControlApi.main` 568), and the launchers `cd` to the install root first. Under D3
    every relative path in the install hangs off it. Leaving it implicit means starting the server from
    another directory silently re-points every Space's data — a wrong-but-booting state, which is worse
    than a refusal. An explicit, logged root makes that a one-line check.
  - `-Dapp.root` is **not** a jail and grants nothing; it is only the base relative paths resolve against.

## 3. The rule that makes this safe

`stake`'s stamp exists for one reason, recorded in its javadoc 95-98 and proven by the 2026-10-27 MFS
rehearsal: a **copied** Space folder still pointing at the **original's** data root would write into and
drain the original's inbox. That hazard needs two Spaces resolving to **one** path.

> **A relative data root cannot be shared by two installs — it moves with the Space.** Copy the tree and
> each copy resolves its own. So for a relative `data_root` the hazard cannot arise, and the stamp that
> guards against it is not merely unnecessary: it is the thing that breaks relocation.

Hence the shape of the change:

| `data_root` | Overlap / reachability / secrets checks | Stamp | Relocates |
| --- | --- | --- | --- |
| absent (data under the base) | n/a — `claim` returns early | none | ✅ today |
| **relative** (new) | **yes, unchanged** | **none** | ✅ new |
| absolute | yes | written and enforced, unchanged | ❌ by design |

⛔ **"It resolves" is not the test.** The operator's framing — any path form is fine if it resolves — is
accepted for the *form* of a path (D3) and rejected as the *only* check. Resolution says nothing about the
three refusals that are about what a path POINTS AT, and all three stay, identical for absolute and
relative:

| Refusal | What it catches | Why resolution can't see it |
| --- | --- | --- |
| **overlap** (`stake` 116-121) | `data_root: "inspecto-la-spaces/la-banking"` | Resolves perfectly, and hands this Space another Space's `config/` as an allowed root — a `PathJail` fail-closed rule |
| **reachability** (`stake` 127-129) | an absolute root on an unmounted volume | Resolves perfectly, then silently plants a data tree on the root disk |
| **secrets** (`stake` 122-126) | a root inside a `*.secrets` dir | Resolves perfectly |

In practice, then, a relative data root points at a **sibling of the spaces root** —
`data_root: "inspecto-la-data/la-showcase"` from `%APP_ROOT%` — which is exactly the showcase bundle's
layout.

## 4. Work

Each step names its verification. Nothing is merged until every one passes.

0. **`-Dapp.root` (D4).** One accessor — `AppRoot.current()` — returning `-Dapp.root` normalized, else the
   working directory. Every relative root in steps 1-3 resolves against it; nothing else consults it.
   → verify: unit test for both branches; a boot test asserting the resolved root is logged.
1. **`SpaceDataRoot.stake` — allow a relative `data_root`.** Replace the 106-107 refusal with: resolve
   against `%APP_ROOT%` (change `dataRootFor` 157-165 from `base.resolve` — see D3), run every other check
   unchanged, and **skip the stamp** when the declared value is relative. `ownedBy` is not consulted for a
   relative root.
   → verify: `SpaceDataRootTest.relativeDataRootIsRefused` is **inverted** into
   `relativeDataRootIsClaimedWithoutAStamp`; new tests for (a) relative root overlapping the spaces root is
   still refused, (b) the tree copied to a second path boots both copies against their own data,
   (c) an absolute root still stamps and still refuses a foreign stamp (regression).
2. **`SpaceManager.resolveRef` — allow a relative `<id>.ref`,** resolved against `%APP_ROOT%`. Keep every
   other rule: folder name must equal the id, must not land inside the spaces root, must exist.
   → verify: new cases in the ref tests, including a relative ref that climbs back into the spaces root
   (refused) and one naming a sibling (accepted).
3. **`ControlApi.main` — read `spaces.ref`.** Order: `-Dspaces.root` → `SPACES_ROOT` →
   `%APP_ROOT%/spaces.ref` → `%APP_ROOT%/spaces`. A malformed or dangling `spaces.ref` is a **loud boot
   error**, never a silent fall-through to `./spaces` — a pointer the operator wrote and got wrong must not
   look like "no Spaces configured". Register the resolved root with `SpaceDataRoot.registerSpacesRoot` as
   today.
   → verify: a real-HTTP boot test per branch, including the malformed-pointer refusal.
4. **Stop stamping an absolute base where a relative one would do** *(decide during build; may be dropped)*.
   For an absolute `data_root` the stamp could hold the base's path **relative to the data root** when the
   two share a root, falling back to absolute otherwise. This would make even absolute-rooted installs
   relocatable. ⚠ It changes an on-disk format every existing install already wrote, so `ownedBy` must read
   both shapes. **Recommendation: defer.** Steps 1-3 give the operator what was asked for; step 4 is a
   migration with no requester.
5. **Showcase bundle + launcher.** Switch `tools/package-la-showcase.mjs` and `start-basic.cmd` to the
   relative form (`data_root: "inspecto-la-data/<id>"`, no `SPACES_DATA_ROOT`), so a packaged bundle is
   relocatable with no stamps to delete.
   → verify: package a bundle, unzip at two different paths, boot both, confirm 11 Spaces each and no
   `.inspecto-space` anywhere.
6. **Docs.** `docs/okf/product/module/config/` — the resolution order as a table, the relative/absolute
   stamp rule and why. `docs/EDITIONS.md` / the bundle README if they name the data root. Update
   `.claude/memory/space-data-root-stamp.md` to say relative roots need no stamp-deleting.
   → verify: `node tools/render-product-docs.mjs`, pre-commit guards.

## 5. Open questions for the operator

- **Q1. Do existing installs get a migration?** An install with an absolute `data_root` keeps its stamp and
  stays non-relocatable. The manual fix (edit `space.toon` to the relative form, delete the stamp) is two
  commands and is documented. A `POST /spaces/<id>/relocate` route would automate it — not proposed here.
- **Q2. Step 4 (relative stamps) — defer as recommended, or build it now?**
- **Q3. Does `-Dapp.root` need an env twin (`APP_ROOT`)?** Every other root has one (`SPACES_ROOT`,
  `SPACES_DATA_ROOT`), and containers set env more easily than JVM flags. Cheap to add; only asked because
  it is one more name in the vocabulary.

## 6. Out of scope

Ingest ledgers (status / lineage CSVs) keep absolute paths after a move. They are provenance, not boot
inputs. Nothing reads them to resolve a root.

## References

- `inspecto/src/main/java/com/gamma/service/SpaceDataRoot.java` — `stake` 102-149, `ownedBy` 81-86
- `inspecto/src/main/java/com/gamma/service/SpaceRoot.java` — `dataRootFor` 151-165
- `inspecto/src/main/java/com/gamma/service/SpaceManager.java` — `discover` 146-186, `resolveRef` 217-237
- `inspecto/src/main/java/com/gamma/control/ControlApi.java` — `main` 561-580
- `.claude/memory/space-data-root-stamp.md` — the 2026-11-13 relocation failure
