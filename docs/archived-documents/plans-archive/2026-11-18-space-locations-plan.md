# Space locations: attach a Space folder, point it at a data folder, create into both

**Status:** DONE 2026-11-18 — shipped; archived. As-built facts live in `docs/okf/product/module/config/` (architecture-space-anatomy §2.2, admin-guide-configuration-reference, api-spaces). Board rows: `SPACE-LOCATIONS-1` (server), `SPACE-LOCATIONS-UI-1` (screens).
Hub: `docs/okf/product/module/config/` (Space anatomy, §2 Config tree vs data root).

## 1. Why

An operator wants to pre-create a Space folder and a data folder on disks of their choosing, then wire them to a running
installation and work, without hand-writing pointer files or restarting. Today (as built, `SPACE-RELOCATABLE-ROOTS-1`):

| Pointer | Exists | How it is set today |
|---|---|---|
| install → Spaces root | yes | `SPACES_ROOT` / `-Dspaces.root`, or `spaces.ref` at the app root; read at start |
| Spaces root → one Space kept elsewhere | yes | `<id>.ref` (one path) written by hand; read only by `SpaceManager.discover` at start |
| Space → its data | yes | `data_root:` in `space.toon` written by hand; `POST /spaces` always writes none, `PUT /spaces/{id}` keeps what is there |

So every step but the first is a hand edit plus a restart, and there is no check of a folder before it is used.

## 2. Scope

In: check a folder; attach and detach a Space folder kept outside the Spaces root; point a Space that holds no data yet at
a data folder; create a Space (blank or from a template) directly into a chosen Space folder and data folder; the
Settings ▸ Spaces screens for all of it.

Out (kept on the board): moving a Space's EXISTING data to a new folder — `SPACE-ROOTED-DATA-ROOT-MIGRATION-1` (hold:
demand) stays the row for that; changing the Spaces root while running (a start-up setting, restart by design); bundle
import into chosen folders (follow-up if asked).

## 3. Decisions (taken under the operator's delegation, 2026-11-18)

- **D1 Allowed locations, fail closed.** A new launch setting `spaces.allowed.roots` (`-Dspaces.allowed.roots`, or env
  `SPACES_ALLOWED_ROOTS`; a list separated by the platform path separator). Every path these routes accept must lie
  under one of them (canonical, after `..` and links are resolved). Unset ⇒ the routes answer 409
  `SPACE_LOCATIONS_DISABLED` naming the setting. *Why:* without it, anyone holding `canAdminister` could point the
  server at any folder on the host over HTTP (system folders, another product's data). *As built:* the Spaces root is
  NOT an allowed root — a Space folder there needs no path (a plain create), so a path inside it is refused with that
  reason. `GET /spaces/_meta` answers `locationsEnabled` (asked by the screens).
- **D2 Never inside something else.** A Space folder or data folder is refused when it is, contains, or lies inside: the
  app root (the install), the Spaces root's own `_templates`/`_shared`, another hosted Space's folder or data folder
  (reuse `withoutOverlaps` / `DiscoveredRoots`), or a data folder whose `.inspecto-space` stamp names a different Space.
  *As built:* "the app root" means a folder that IS or CONTAINS it; lying inside it is allowed, because a relative
  `data_root` (the showcase layout) lies there. A data folder is also refused in the folder that holds its Space
  folder, which the boot (`SpaceDataRoot.stake`) already refuses.
- **D3 Attach = write the reference and boot, live.** `POST /spaces/attach {path, id?}`. The folder's name is the id
  (the existing `<id>.ref` rule); `id`, when given, must equal it. It must be a Space (`notASpaceReason` is null),
  the id must be free (hosted, on disk, or as a `.ref`), and D1/D2 must hold for the folder and for the data folder
  its `space.toon` names. The reference is written relative to the app root when the folder is under it, else
  absolute. Then `bootStarted` + `wire` + `publishHosted`, as `create` does. A boot failure removes the reference it
  wrote and answers 422 with the reason. The attached Space keeps the `admission` its `space.toon` says.
- **D4 Detach = stop and drop the reference, never files.** `POST /spaces/{id}/detach`, only for a Space booted
  through a `.ref` (else 409: a Space inside the Spaces root is removed with the existing `DELETE`). Same teardown as
  `delete(id, false)`, then the `.ref` is deleted; the Space folder and its data stay on disk and can be attached again.
- **D5 Point a Space at a data folder only while it holds no data.** `PUT /spaces/{id}/data-root {path}`. Allowed when
  the Space's current `data/`, `audit/`, `duckdb/` hold no files (a freshly created or attached Space), the target
  passes D1/D2, and the target is empty or already stamped for this Space. The Space is stopped, `space.toon` gets
  `data_root:` (relative to the app root when under it, so the stamp is relative too), it is booted again (the boot
  claims and stamps the folder), and the answer is the Space row. A Space that holds data is refused with 409
  `SPACE_HOLDS_DATA` pointing at the documented manual move; moving data is `SPACE-ROOTED-DATA-ROOT-MIGRATION-1`.
  `{path: null}` puts the data back under the Space folder, same rule.
  *As built:* "holds data" = files in `data/` or `audit/`. Every boot opens store files in `duckdb/`, so "no files in
  `duckdb/`" is never true of a started Space; those stores move with it (copy, then delete, Space stopped), and the
  old folder's stamp is removed. A failed change puts the Space back as it was (422).
- **D6 Create into chosen folders.** `POST /spaces` and the create-from-template route accept optional `path` (the
  pre-created Space folder, named `<id>`, empty or absent) and `dataRoot` (the pre-created data folder, empty or
  absent). With `path` the Space is created there and a `.ref` is written; with `dataRoot` `space.toon` gets
  `data_root:` before boot and the template's seeded data is moved there (`adoptSeeded`, as today). Both follow D1/D2.
  Created by a signed-in person ⇒ closed, as today.
- **D7 Check before wiring.** `POST /spaces/_locations/check {path, kind: "space"|"data", id?}` (*as built:* the
  underscore sentinel, like `_meta`, because `/spaces/locations/check` is read as the Space `locations`; `/detach` and
  `/data-root` are kept server-global by `ControlApi.SPACE_PREFIX`) answers the list of
  checks with pass/fail and a plain reason each (allowed root, exists or can be created, writable, free space against
  the existing `MIN_FREE_GB` rule, not inside another Space or the install, stamp, is-a-Space / is-empty). Nothing is
  written. The screens show it before Save; the write routes run the same checks and refuse on the first failure.
- **D8 Show where a Space lives, to admins only.** `GET /spaces` rows gain `location: {spaceFolder, dataFolder,
  referenced, holdsData}` only when the caller holds `canAdminister`; others get no paths.
- **D9 Gate order (house style, `endpoint` skill).** Every new route: `canAdminister` (403) → multi-Space server
  (409 single-tenant) → D1 setting present (409) → body/path validation (422) → D1/D2 jail (403 for outside the
  allowed roots, 409 for overlap/taken) → act under `lifecycleLock`. Each gets a real-HTTP test class covering every
  gate, a literal capability in `CapabilityManifest`, and regenerated `docs/api/openapi-v1.json` + route-gating report.
- **D10 Words.** UI says **Space folder** and **Data folder**; the docs keep `data_root` / `<id>.ref` for the files.
  Actions: *Attach existing Space*, *Detach*, *Change data folder*, and on New Space an *Advanced: folders* section.

## 4. Build

| Lane piece | Files (owner) | Done when |
|---|---|---|
| S1 allowed roots + checks (D1, D2, D7) — **built 2026-11-18** | `inspecto/.../service/SpaceLocations.java` (new), `SpaceManager`, `SpaceRoutes` | check route + unit and HTTP tests for every reason (`SpaceLocationsTest`, `ControlApiSpaceLocationsTest`) |
| S2 attach / detach (D3, D4) — **built 2026-11-18** | `SpaceManager`, `SpaceRoutes` | HTTP tests: attach boots live, survives restart, refusals, detach leaves files |
| S3 data folder (D5) — **built 2026-11-18** | `SpaceManager`, `SpaceDataRoot` | HTTP tests: empty Space re-pointed and stamped; data-holding Space refused; back to the Space folder |
| S4 create into folders (D6) — **built 2026-11-18** | `SpaceManager.create*`, `SpaceRoutes`, template create route | HTTP tests for blank and template create with both fields |
| S5 rows show location (D8) — **built 2026-11-18** | `SpaceRoutes` list | admin sees paths, analyst does not |
| U1 screens (D10) — **built 2026-11-18** | `inspecto-ui/src/app/modules/admin/spaces/*`, `spaces.service.ts` (+ la-app Spaces page if it has one) | specs; checks shown before Save; plain words |
| Docs — **built 2026-11-18** | `module/config` hub (anatomy §2, admin guide), launch-flags guide, deploy-kit guide, OpenAPI | guards green |

Tests: targeted per `node tools/affected-modules.mjs`; the full gate is not run per lane.

## 5. Ship checklist

Distil D1–D10 into `docs/okf/product/module/config/` (architecture-space-anatomy §2, admin guide), close the two rows,
keep `SPACE-ROOTED-DATA-ROOT-MIGRATION-1` open, `git mv` this plan to `docs/archived-documents/plans-archive/`.

## 6. Live check (2026-11-18)

Driven in a browser against a demo bundle built from the lane (`SPACES_ALLOWED_ROOTS` = one scratch folder): attach of a
pre-created Space folder (live, and back after a restart from its `.ref`); *Change data folder* to a pre-created empty
folder (`space.toon` names it, the folder is stamped, `data/`, `audit/`, `duckdb/` moved, the Space folder keeps only
its config); *New space* into a chosen Space folder and data folder (closed, created_by recorded); *Detach* (the `.ref`
removed, both folders kept). It found and fixed three things the tests had not: the event log every demo boot writes
(`data/events/`) made every attached Space "hold data" and hid *Change data folder*; folder paths wrapped one letter per
line on a Space card; the page header's actions and a card's action icons ran out of their box once the new actions
were added. The independent review's one finding (a mixed-slash network path reached the disk before the jail) is fixed
too. Not done: moving data a Space already holds (`SPACE-ROOTED-DATA-ROOT-MIGRATION-1`, open), bundle import into
chosen folders, partition-map checks on a multi-pod attach.
