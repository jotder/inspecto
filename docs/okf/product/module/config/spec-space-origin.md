# Space origin — which template made a Space (SE-5a)

**State:** built 2026-11-13 (SE-5a of the Space evolution plan, §4.5 steps 1 and 2). The upgrade proposal (step 3,
SE-5b) is not part of this.

## 1. Decision: one record, the one that already existed

PRODUCT-ASSEMBLY-1 PA-7d already wrote `<space>/.inspecto/created-from.toon` (template, App, App version) and
`created-from.sha256` (each config file's SHA-256 at creation) — but only for a template an installed App owns.
SE-5a **extends that record** instead of adding an origin to the Space lock:

- The plan (§4.5) said "in its lock". The lock (`SpaceLock`, `.inspecto/space.lock`) is a derived cache: it is
  recomputed from the files, may be deleted at any time, and `-Dspace.integrity=off` switches it off. An origin is a
  fact recorded once that cannot be recomputed, so it does not belong in a cache. Keeping it out also leaves
  `SpaceLock` untouched while SE-3a adds to it.
- A second record beside `created-from.toon` would have two answers to "where did this Space come from".

So `created-from.toon` is now written for **every** template, and gains two keys. PA-7d's keys are unchanged, so
`InstalledApps.spaceOffers` reads it exactly as before (a shipped template writes no `app:`, so it never matches an
App's upgrade).

```
template: orders-starter
templateVersion: "1.0"        # SE-5a; "unversioned" when template.toon has no version:
origin: created               # SE-5a; created | adopted
recordedAt: "2026-11-13T10:00:00Z"
app: acme-spend               # PA-7d, only for an installed App's template
version: "1.2.0"              # PA-7d, the App's version
```

`created-from.sha256` keeps its format: `<sha256>  <config-relative path>` per line. The code is
`com.gamma.service.SpaceOrigin`; `SpaceManager.createFromTemplate` calls it after every file the server writes at
creation, so the hashes are the created state (seeded Jobs already stamped). The record is written last (temp file +
move), so a half-written pair never reads as an origin. Recording is best effort: a failure is logged and the Space is
still created.

## 2. Template version

`template.toon` may carry `version: "<v>"`. Without it the template reads as `unversioned`. The template gallery
(`GET /spaces/templates`) now shows `version`. No shipped template was given a version: no guard or test needs one,
and the first version bump of a template is what makes the "since" report say anything.

## 3. Reading it — `GET /spaces/{id}/origin`

Report-only, always 200, `canAuthorWorkbench` (it lists config paths, like `/integrity`).

| `status` | When |
|---|---|
| `none` | no record (every Space made before SE-5a, or by `POST /spaces` without a template) — behaves as before |
| `unavailable` | the single-tenant default Space (no Space folder) |
| `recorded` | `{template, templateVersion, origin, recordedAt, space, templateSince}` |

`space`: `unchanged` (hash equals the recorded one), `changed` (differs), `removed` (recorded, now absent), `added`
(present, not recorded). `templateSince.status`: `same-version`, `missing` (the template is no longer installed), or
`newer-version` with `currentVersion` and the template's `added` / `changed` files against the recorded
hashes (no `removed`: the recorded hashes also hold files the server writes into every new Space, such as
`egress.toon`, so a recorded file the template lacks need not have been the template's; a template `.toon` rendered with `${SPACE}` = the Space id, as creation does). Hashes are bytes, so a Job the
seed gate stamped at creation can read as `changed` against a newer template; SE-5b's three-way proposal compares
decoded TOON as `InstalledApps.sameConfig` does.

## 4. Adopting — `POST /spaces/{id}/origin/adopt {template, version?}`

For a Space made before SE-5a (or copied by hand): match its config against a template by file hash and record the
origin with `origin: adopted`. `canAdminister`. Only the template version installed now can be matched (the server
keeps no older copies); naming another version is a 409.

Gates, in order: no Space folder 503 → body 400 (`template` missing, `version` not a string) / 422 (`version` not
`[A-Za-z0-9][A-Za-z0-9._+-]*`) → template id that is not a bare id 403 → no such template 404 → a record already there,
or a `version` that is not the template's current one 409 → write.

Answer: `{template, templateVersion, match: full|partial|none, recorded, matching, differing, onlyInTemplate,
onlyInSpace}`. `full` means every template file is in the Space unchanged (files only the Space has, its own or
`egress.toon`, do not make it partial). The record is written only when at least one file matches (`none` records nothing), and its hashes are
the **template's** rendered hashes, so a file the Space holds differently reads as `changed` by the Space afterwards.
Adopt writes only `.inspecto/created-from.*`, never config. Re-adopting is refused (409); to start over, delete the two
files (rollback is "ignore the record").

## 5. Tests

`SpaceOriginTest` (create with/without version, unrecorded Space, adopt full/partial/none, the report's space and
template sections) and `ControlApiSpaceOriginTest` (real HTTP, armed: 401, 403 for each route, every adopt gate, the
read). `ImportLoaderInventoryTest.ALLOWED` names `created-from.toon` (outside `config/`, where an import writes).
