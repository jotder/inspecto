# Space origin — which template made a Space (SE-5a)

**State:** SE-5a built 2026-11-13 (the Space evolution plan, §4.5 steps 1 and 2); SE-5b, the upgrade proposal (step 3),
built 2026-11-13 (§5 below).

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

## 5. Upgrade proposal (SE-5b)

A Space never changes because its template did. An administrator reads what a newer template version would change and
applies the part they choose. Code: `com.gamma.control.SpaceTemplateUpgrade` (the comparison) and
`SpaceOriginRoutes` (the routes).

### 5.1 The three-way comparison

The server keeps no older template copies, only the hashes recorded at creation (or adoption). Per config file:
**then** = the recorded hash, **now** = the installed template's file rendered with `${SPACE}` = the Space id (as creation
renders it), **Space** = the file the Space holds. When the Space already holds what the template now says, the file is
left out. Equality is decoded TOON for `.toon` files, setting aside the keys the server stamps on a seeded Job
(`InstalledApps.sameConfig`, PA-7d's rule), and bytes otherwise.

| `change` | When | Applied? |
|---|---|---|
| `update` | the Space's file still equals the recorded hash; the template's differs | yes, by default |
| `add` | the template has a file the Space never had (not recorded, absent) | yes, by default |
| `kept` | the Space changed (or deleted) the file; the template's still equals the recorded hash | no, the Space's edit stays |
| `conflict` | both changed, or the Space has its own file where the template now adds one | never; listed with `recordedSha256`, `templateSha256`, `spaceSha256` to resolve by hand |
| `template-removed` | recorded, gone from the template, still in the Space | never, report only (§3: the record also holds files the server writes into every Space, such as `egress.toon`, which therefore always shows here) |

An `update` or `add` that no import may write (`ImportPaths`: not a config file, a reserved settings or access document, a
suffix-scanned ops config) carries `refused` and is never applied by the upgrade; its own route writes it.

⚠ A seeded Job is stamped at creation, so its recorded hash is of the stamped file. A Space that edited such a Job reads
as `conflict` even when the template left the Job alone: the safe side, never applied.

### 5.2 Reading it — `GET /spaces/{id}/origin/upgrade`

Report-only, always 200, `canAuthorWorkbench`. `status`: `none`, `unavailable` (as `/origin`), `missing` (the template is
no longer installed), `same-version` (the template's version equals the recorded one: nothing is proposed, whatever its
files say), or `proposal` with `{template, fromVersion, toVersion, proposalId, files: [{path, kind?, change, …hashes,
refused?}], applicable}`. `kind` is the approval-policy kind (`PendingChanges.kindOfConfigPath`); `applicable` is what an
apply takes without `files`. `proposalId` is a SHA-256 over every entry and both versions.

### 5.3 Applying it — `POST /spaces/{id}/origin/upgrade {toVersion, files?, proposalId?}`

`canAdminister`. Never automatic: only this request applies anything, and only `update` / `add` files. Gates, in order:

1. no Space folder 503;
2. body 400 (`toVersion` missing, `files` not a non-empty list of strings, `proposalId` not a string) / 422 (`toVersion`
   not a version);
3. a named path that is not a plain relative path 403;
4. no origin recorded 409 (adopt first); the template no longer installed 404;
5. already at the template's version 409; `toVersion` not the installed version, or `proposalId` not the current
   proposal's, 409 `CONFLICT_STALE_VERSION` (the template or the Space moved since the caller read it);
6. a named file no import may write 403; a named file that is not an `update` or `add` 409. With no `files` and nothing
   applicable left (only conflicts, `kept`, `template-removed` or refused files), the apply writes no file, skips 7 and 8,
   and only records the new version (step 9's record rewrite, every hash kept) — §5.4;
7. the gates a Space bundle import runs over raw config files: a kind whose own route needs more
   (`ImportCapabilityGuard`: a Connection needs `canOnboardConnections`, …) 403, every Decision Rule through
   `DecisionRuleGuard`, every Job server-stamped (`JobAuthority`);
8. the maker-checker hold (`PendingChanges.holdSet`): when the Space's approval policy holds the kind of ANY chosen file
   (a file whose kind cannot be told counts as held), the whole set becomes ONE Pending Change, kind
   `space-template-upgrade`, name = the template id, answered 202. Its rules must name one approver capability, else 409
   (apply the files in groups, §5.4). It pins the template's hashes over the chosen files' Space hashes and the
   `proposalId`; approving replays this request as its author, and anything the proposal listed that moved meanwhile
   (a chosen file, a conflict the Space edited further, the template's files) makes it stale (409, Pending Change
   status `stale`). With no policy the administrator's request applies directly (D-SE7's detail);
9. write: every file through the import's writer (`BundleImporter.writeConfig` + `ImportJournal`, atomic per file, all
   rolled back if one fails), a Pipeline also into its history (`PipelineHistory`) and refusal audit; then the record is
   rewritten (temp file + move): `templateVersion` = the new version once no `update` / `add` is left unapplied (else
   it stays, §5.4), each applied file's hash = the bytes written (a
   stamped Job's, not the template's), every other hash kept, so a conflict still reads against the old baseline;
   `recordedAt` now; `template`, `origin` and the PA-7d App fields kept. A failed record rewrite rolls the files back.

Answer: `{template, fromVersion, toVersion, applied, recordedVersion, remaining, notApplied: [{path, change}]}` —
`remaining` the applicable files still unapplied.

### 5.4 Applying in groups

`files` may name a subset (a policy whose kinds name different approvers needs it). A partial apply records the hashes of
the files it wrote but keeps the recorded `templateVersion`, so the next `GET` still proposes the remaining updates and
adds against the same newer version (the applied files now equal the template and drop out). The apply that leaves no
applicable `update` / `add` records the new version; conflicts, `kept`, `template-removed` and refused files never hold
it back. Until then `/origin` reports `templateSince: newer-version`, which is true: part of the newer version is not in
the Space. Decision (SE-5b verification, 2026-11-13): first built as "every apply records the new version", which made a
second group read `same-version` and be refused.

Deliberately not done: a registry component written by the upgrade gets no MET-5 version-history copy (the import writer
writes raw files, as a Space bundle import does); nothing is deleted; a Pipeline the upgrade writes takes effect as after
any config write. Rollback: none needed for the record (ignore it); a written file is restored from its history or by hand.

**Nothing applicable left** (`SPACE-UPGRADE-STUCK-VERSION-1`, 2026-11-15): when the files a group left become conflicts
(the Space edited them between groups) and the operator resolves them by hand, or a proposal holds only conflicts, an
apply without `files` writes nothing and records the new version (answer: `applied` and `remaining` empty, the conflicts
under `notApplied`). Until 2026-11-15 it answered 409 "the proposal has no update or add to apply", so `GET` kept
proposing and the record stayed at the old version for good. Decision: record the version from that apply rather than
close the proposal on `GET` — a read never writes, and the operator's explicit apply is the acknowledgement. Every gate
before it still holds: a stale `toVersion` or `proposalId` 409, a named conflict 409. Every hash is kept, so a file the
operator resolved by hand reads as a conflict again at the next template version, even one that leaves that file
unchanged: the safe side, since the Space's file still differs from the baseline it was made from. No Pending Change: no
config file is written, only the origin record. A replay of an approved upgrade never takes this path: the files it was
approved to write are no longer an update or add, so it answers 409 with the stale-version code, the Pending Change
turns `stale` and no version is recorded.

## 6. Tests

`SpaceOriginTest` (create with/without version, unrecorded Space, adopt full/partial/none, the report's space and
template sections) and `ControlApiSpaceOriginTest` (real HTTP, armed: 401, 403 for each route, every adopt gate, the
read). SE-5b: `SpaceTemplateUpgradeTest` (each classification, the stamped Job left out, the proposal id moving with the
Space) and `ControlApiSpaceOriginUpgradeTest` (real HTTP, armed: the plan's proof on `orders-starter`, 401, 403 per route,
every apply gate, the capability gate, 202 + approval applying the set, a stale approval, groups, and an apply with only a
conflict left recording the version without writing). `ImportLoaderInventoryTest.ALLOWED` names `created-from.toon` (outside `config/`, where an import writes).
