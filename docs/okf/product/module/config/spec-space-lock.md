# Space lock — the server's signed record of a Space (SE-1a)

**State:** built 2026-11-12 (SE-1a of the Space evolution plan, §4.1 step 1: shadow, report-only). It refuses
nothing and changes no load. Rename handling (SE-2a) is not part of it. Built on it 2026-11-13
(`SPACE-LINK-CENSUS-1` LC-4, LC-6): the per-Space `links:` mode (§10), the events (§11), the accept route (§12) and
the count on the Spaces screen (§13).

## 1. What it is

`spaces/<id>/.inspecto/space.lock` is one JSON file the server derives from the Space's `config/` tree. It lists
every live config with a permanent `uid`, and every link the link census resolves. It is **derived state**:
deleting it loses only the history it carries (the `uid`s and the "changed outside the server" marks); the next
computation starts fresh. A missing, unreadable, badly formed or badly signed lock is recomputed with no
baseline, never an error. `.inspecto/` sits beside `config/`, so no loader reads it (the boot walks only the
config root; `.inspecto/` already holds `value-fingerprint.salt` and `created-from.toon`).

Only a hosted Space (a folder with `config/`) has a lock. The single-tenant default Space configured by
`-Dassist.write.root` has no Space folder and gets none.

## 2. Format

```
{
  "format": 1,
  "space": "demo",
  "computedAt": "2026-11-12T10:15:02Z",
  "files": [ {"path": "orders/orders_pipeline.toon", "kind": "pipeline", "id": "orders",
              "uid": "4f0c…", "hash": "sha256 hex of the bytes",
              "outside": {"change": "modified", "at": "…"} } ],
  "removedOutside": [ {"path": "…", "kind": "…", "id": "…", "uid": "…", "at": "…"} ],
  "renamed": [ {"kind": "pipeline", "uid": "4f0c…", "from": "orders", "to": "orders_v2", "path": "…", "at": "…"} ],
  "deleted": [ {"kind": "…", "uid": "…", "id": "…", "path": "…", "at": "…"} ],
  "links": [ {"from": "orders/orders_daily_enrich.toon", "fromKind": "enrichment", "fromName": "…",
              "key": "triggers.on_pipeline", "value": "orders", "toKind": "pipeline", "status": "RESOLVED"} ],
  "mac": "HMAC-SHA256 hex"
}
```

| Field | Meaning |
|---|---|
| `files[]` | every live config, sorted by `path` (config-relative, `/` separators). `outside` is present only on a file the server did not write (§4). |
| `kind` | the registry kind for `registry/<kind-dir>/<id>.toon` (`ComponentRegistry`); else the suffix kind (table below); else `setting` for a fixed-name Space setting at the config root. |
| `id` | for a Pipeline, Job, Enrichment or Connection: the id or name the file declares (a Pipeline's `id`, else its `name` lower-cased with `_` for spaces, as the link census reads it); for a registry component: its component name; otherwise the file name without its suffix. |
| `uid` | a random UUID, assigned the first time the server sees a file and kept (§3). |
| `hash` | SHA-256 of the file's bytes. |
| `links[]` | every edge `LinkGraph.of(configRoot)` finds, with its status (`RESOLVED`, `DANGLING`). |
| `removedOutside[]` | files that disappeared while no server write was in progress. A path that reappears is dropped from it. |
| `renamed[]`, `deleted[]` | SE-2a (§3a): renames and deletes still named by some link. |

**Live-file set.** The same walk the loader and `PipelineDependents.configFiles` use
(`LinkGraph.configFiles(root, suffix, ReservedConfigPaths::reserved)`): `.history/` copies, the server-written
state folders and `registry/access-*` are skipped. Kinds by suffix:

| Suffix | Kind | Suffix | Kind |
|---|---|---|---|
| `_pipeline.toon` | pipeline | `_meta.toon` | semantic-model |
| `_schema.toon` | schema | `_rca.toon` | rca-template |
| `_enrich.toon` | enrichment | `_tag.toon` | tag |
| `_job.toon` | job | `_tagrule.toon` | tag-rule |
| `_job_template.toon` | job-template | `_workflow.toon` | workflow |
| `_connection.toon` | connection | `_caserule.toon` | case-rule |
| `_grammar.toon`, `.grammar.toon` | grammar | `_view.toon` | view |
| `_profile.toon` | decode-profile | `_snapshot.toon` | snapshot-import |
| `_structure.csv` | structure | `_mapping.csv` | mapping |

A file under `registry/<kind-dir>/` is classed by the registry first. Fixed-name settings are the
`ReservedConfigPaths.FILES` present at the config root.

## 3. How a file gets its `uid` and keeps it

Plan §4.2, applied only to assign and record: on each computation a file takes the `uid` of the previous lock's
entry **at the same path**; else of a previous entry **with the same content hash** whose path is gone (a moved,
unchanged file keeps its `uid`); else a new one. With no valid previous lock every file gets a new `uid`.
What a `uid` tells is SE-2a (§3a).

## 3a. Hand renames and deletes (SE-2a)

Compared with the previous lock, whoever made the change (an editor, a copy, a server write):

- **Rename** — a file keeps its `uid` (same path, or moved unchanged) but its `id` changed: `renamed[]` gets
  `{kind, uid, from, to, path, at}`. A file edited in place to `id: orders_v2` is the common case; a suffix-named
  file moved unchanged to a new name is another.
- **Delete** — a `uid` of the previous lock is in no live file: `deleted[]` gets `{kind, uid, id, path, at}`.

An entry stays in the lock while some link still names its old id and no live config of that kind has taken the
id again; then it is dropped (at most 200, newest last). The report adds, per entry, `referrers` (a rename) or
`dependents` (a delete): `{kind, name, path, keys}` for every config still naming the old id. For a Pipeline these
are exactly the holders the Pipeline rename route rewrites — the census links with `onRename: REWRITE` whose target
is a Pipeline or its store (`PipelineLinkRewrite.holders`, shared with `PipelineRenameRoutes`); for any other kind,
the links whose target kind is that kind and whose value is the id (trimmed, case-insensitive).

**Limits.** A file renamed **and** changed in one step loses its `uid` (new path, new hash): it reads as a delete
plus an add, and the delete is reported with its dependents. A case-only change of an id is not reported (ids match case-insensitively). Only
Pipeline renames can be rewritten; other kinds are reported.

**Opt-in rewrite (D-SE3).** Off by default. A Space opts in with `rename_rewrite: true` in its `space.toon` (beside
`config/`; a lenient reader, so older releases ignore the key; a display-name change keeps it). Then, after a
computation that reports a Pipeline rename with referrers, the referrers are rewritten through the rename route's
own census rewrite (`PipelineRenameRoutes.rewriteHandRename` → `PipelineLinkRewrite.rewrite`; no second rewrite
path), inside a server write window so the rewritten files are not reported as outside edits, and the lock is
computed again. When the Space's approval policy holds a kind among those dependents, nothing is rewritten and the
entry's `rewrite.held` names them. The outcome is logged (`[SPACE-LOCK]`) and shown on that report's entry as
`rewrite: {rewritten, held}`. Kill switch `-Dspace.rename.rewrite=off` rewrites nothing anywhere; the report stays.
The report's `renameRewrite` says `report`, `rewrite` or `disabled`.

## 4. "Changed outside the server"

The server cannot see which file a request wrote, so it records **when** it was writing: every successful
mutating request (POST, PUT, PATCH, DELETE answered 2xx) bound to a hosted Space opens a write window at its
start and closes it at its end. At the next computation, compared with the previous lock:

- a file whose content changed, or a new file, is marked `outside` unless its last-modified time falls inside a
  window (2 s slack for coarse file-system clocks);
- a file that only moved (same hash, new path) or disappeared is marked outside only when no window was recorded
  since the previous computation;
- an unchanged file keeps its mark; a file the server rewrites loses it.

Edits made while the server was stopped are therefore reported at the next boot. The rule errs toward silence:
a hand edit made within two seconds of a server write to the same Space, or a hand move during a server write,
is not reported.

## 5. Signature

The lock is signed the way `AuditAnchors` signs anchors: HMAC-SHA256 with the Space's Pending Change key
(`<space>/config.secrets/.pending-changes.key`, created on first use by `SpaceSecretKeys.readOrCreate`) over the
domain prefix `space-lock\n` plus the canonical JSON (keys sorted, compact) of the lock without its `mac`. A lock
that does not verify is ignored as a baseline and replaced. As with every key in `config.secrets/`, the MAC proves
the lock came through a door that can read the key — an import or a copied folder cannot forge one — not that a
local administrator did not write it.

## 6. When it is computed (D-SE1)

- **After boot, in the background**: `SpaceContext.start()` schedules it on one shared daemon thread; the boot
  path only schedules.
- **After server-side writes**: one hook in the Control API request pipeline (`ControlApi.spaceWrites`), after the
  Space is bound, covers every write route without touching any of them. Bursts are debounced: each write
  restarts a 1.5 s timer, but a burst postpones a computation by at most 10 s, so steady write traffic still
  gets one. A computation consumes only the write windows that had ended when it began.
- **On first read**: `GET /spaces/{id}/integrity` schedules a computation for a Space that has none yet.
- The longest delay from a server write to its computation is therefore about 11.5 s (10 s plus the 1.5 s timer).
- The file is rewritten only when its content (all but `computedAt`) changed.
- **Closing a Space** runs after its service closed, so the store drain keeps the Space's whole close budget
  (`SpaceManager.CLOSE_DEADLINE_MS`, 10 s). It cancels the pending computation; a server write not yet in a lock is
  flushed then, so the next boot does not report it as an outside edit. A running computation is waited for at most
  2 s; past that the flush is skipped and logged. A closed Space gets no new state until it starts again, and a
  computation never writes for a closed Space or a config root that no longer exists, so a deleted Space's folders
  (`.inspecto/`, `config.secrets/`) are never recreated. (These close rules fix
  `SPACE-LOCK-CLOSE-RESIDUALS-1`.)

## 7. `GET /spaces/{id}/integrity`

Gated on `canAuthorWorkbench` (it lists every config path); available on every edition (D-LC5). Every answer for a
Space with a Space folder also carries `linksMode` (§10) and `columnsMode` ([spec-column-contract.md](spec-column-contract.md) §9). Answers 200:

| `status` | Body |
|---|---|
| `computing` | no report yet; ask again. |
| `ready` | `checkedAt`, `lockWrittenAt`, `baseline` (`lock` or `none`, with `baselineNote` why), `files`, `links`, and three lists — `broken` (dangling links), `duplicates` (two or more files claiming one `kind:id`, case-insensitive) and `changedOutside` (§4). Each list is `{total, items}`, at most 200 items. |
| `disabled` | the kill switch is off. |
| `closed` | the Space was closed (or deleted) and not started again. |
| `absent` | its config folder no longer exists; nothing is written. |
| `unavailable` | the bound Space has no Space folder (single-tenant default). |
| `failed` | the computation threw; `error` says why. |

A `ready` report also carries `renamed` and `deleted` (§3a, `{total, items}`) and `renameRewrite`, and
`columnChanges` — Schema column changes and the consumers they break (SE-3a,
[spec-column-contract.md](spec-column-contract.md)); the lock then holds an additive `columns` field.

## 8. Kill switch

`-Dspace.integrity=off` (default on, because it only reports): no lock is written, no computation is scheduled,
and the route answers `disabled`. `SpaceCorpusEquivalenceTest` loads every shipped Space with the switch off and
on and proves the load identical.

## 9. Deliberately not covered

- **Enforcement at load** (SE-1b): a broken link, a duplicate or an outside edit changes no load. The opt-in
  refusals at save and delete are §10.
- **Rewriting** a renamed kind other than a Pipeline, and any rewrite in a Space that did not opt in (§3a).
- **Edits at runtime with no later server write** are reported at the next computation, not when they happen;
  there is no file watcher.
- **Server writes outside a request** (a Job or background task writing a config file) carry no window and may be
  reported as outside edits.
- **Files outside the live set** (`.asn`, `.sql`, unreferenced `.toon` files) and the single-tenant default Space.
- **UI** beyond the count on the Spaces screen (§13): there is no details view yet.
- **Write windows live in memory.** A crash loses them, so the server writes not yet in a lock are reported as
  outside edits at the next boot. A second JVM writing the same Space, and a server write to a Space other than the
  one the request is bound to, carry no window here and are reported as outside edits too.

## 10. The `links:` mode (LC-4)

A Space opts into refusing broken links in its own `config/safety-policy.toon`:

```
links: refuse-activation
```

| Value | Effect |
|---|---|
| `report` (default; also the key absent) | the JVM-wide `-Dlinks.delete` / `-Dlinks.save` modes apply unchanged (LC-3, default `shadow`: the delete or save goes through and the response lists what it broke). |
| `refuse-activation` | for THIS Space the LC-3 save and delete checks act as `refuse` wherever the JVM mode is `shadow`: a delete of something a link still names answers 409 with the dependents (`?force=true` still deletes), and a save of an ACTIVE config that adds a dangling link answers 422 (a draft, `active: false` / `enabled: false`, still saves — D-LC1). The JVM kill switch `off` still wins. |

- **Parsing.** `SafetyPolicyFiles` knows the key, strictly: any other value (`links: refuse`) or the key in the
  server file makes the policy unreadable (`ERR_SAFETY_POLICY_UNREADABLE`, the message names the two values), as for
  every bad key. No server route writes `safety-policy.toon` (a reserved file the operator edits on disk), so the
  strict parse at read is the refusal. A file without the key parses exactly as before; the key narrows nothing in
  the folded Safety Policy tier. No shipped Space sets it.
- **Editions (D-LC5).** Refusing needs Standard or Advanced. On Basic the key parses and acts as `report`, and the
  integrity report says so.
- **An unreadable policy file** fails closed: the link checks act as under `refuse-activation` (on Standard and
  Advanced). The same file already refuses every run of the Space until it is fixed.
- **Reported** as `linksMode` on `GET /spaces/{id}/integrity`: `{configured: report|refuse-activation|unreadable,
  effective: report|refuse-activation, note?}`; the note gives the Basic or unreadable reason.
- **Not built: the load-time hold.** The plan's "a Pipeline or Job with a dangling link stays inactive" has no clean
  seam: a Pipeline's `active` flag is read in six places of `PipelineScheduler` from the `ConfigRegistry` parse,
  which is reused while the Pipeline's own files are unchanged and so cannot see a link target in another file
  disappear, and Jobs load through a separate scheduler. `refuse-activation` therefore refuses saves and deletes
  only; every config still loads and runs as before.
- Code: `SpaceLinksMode`, `LinkRefusals.deleteMode` / `saveMode`, `SafetyPolicyFiles.links`,
  `SafetyPolicy.linksForSpace`. Tests: `ControlApiSpaceLinksModeTest`,
  `SafetyPolicyFilesTest.linksIsASpaceSettingWithTwoValues`.

## 11. Events (LC-4, LC-6)

Each computation compares its findings with the previous lock and emits, into the Space's own event log
(`source: space-lock`), only what is new:

| Type | Level | Once per | Attributes |
|---|---|---|---|
| `CONFIG_LINK_BROKEN` | WARN | DANGLING link the previous lock did not hold (same file, key and value) | `space`, `path` (the linking file), `fromKind`, `fromName`, `key`, `toKind`, `target` |
| `CONFIG_CHANGED_OUTSIDE` (the plan's `config.changed.outside`) | INFO | file this computation marked changed outside the server (§4), a removal included | `space`, `path`, `kind`, `id`, `change` (`added`, `modified`, `moved`, `removed`), `brokenLinks` (dangling links the file now holds) |

A finding the previous lock already holds is never emitted again, so a steady Space emits nothing. With no previous
lock (first boot, or a lock that does not verify) every broken link is new and nothing is marked outside. At most
200 events of each type per computation; the rest are counted in the log. An event-sink failure leaves the lock as
computed. The names follow the event catalog's upper-case convention (`EventType`). Code: `SpaceLockEvents`. Test:
`SpaceLockEventsTest`.

## 12. `POST /spaces/{id}/integrity/accept` (LC-6)

Accepts hand edits so they leave `changedOutside`. Body `{paths?: [config-relative path, ...]}`; absent = every file
currently changed outside (a git checkout, a template clone). Under the Space's computation lock it computes the
lock afresh, so the accepted hashes are the files' current ones, drops the `outside` mark of each accepted file and
the `removedOutside` entry of each accepted removal, re-signs the lock (§5) and replaces the report. The next
computation reads the accepted lock as its baseline; an accepted file reappears only when it changes again.

- **Gate:** `canAdminister`. Accepting clears the record of an out-of-band change to any config, fixed-name settings
  such as `roles.toon` and `safety-policy.toon` included, so it is an administrator's act rather than an author's
  (the report itself is an author's read). Every edition: it only acknowledges a report every edition has.
- **Gates in order:** kill switch off, no Space folder, or no lock could be computed → 503; `paths` not a non-empty
  list of non-blank strings → 400; a path that is not a config file in the Space's lock → 404 (nothing accepted);
  then 200 `{accepted: [{path, change, hash?}], notChangedOutside: [path], changedOutside: <left>, acceptedBy}`. A
  path that is a config file but not changed outside is listed, not refused, so a repeated accept is a 200.
- **Audit:** one AUDIT event, `action: config.changed-outside.accepted`, actor = the operator, attributes `space`,
  `count`, `paths`. An accept that accepts nothing is not audited.
- Code: `SpaceLockAccept`, `SpaceIntegrityRoutes`. Tests: `ControlApiSpaceIntegrityAcceptTest` (401, 403, 503, 400,
  404, 200), `SpaceLockEventsTest.anAcceptedHandEditLeavesTheReportAndTheLockStillVerifies`.

## 13. The count on the Spaces screen (LC-4)

Each Space row on the Spaces screen shows a chip "N integrity findings" (broken links + duplicates + files changed
outside), in the warning tone when above zero; its tooltip and screen-reader text give the three numbers. There is no
details view yet. While the report is `computing`, `disabled`, `unavailable` or `failed` the row shows nothing, never
a zero nobody measured.

**Data path.** `GET /spaces` adds `integrity: {status, broken?, duplicates?, changedOutside?}` to each row, read from
the report already in memory (no computation or file read on the request thread; a Space with none yet is
`computing` and the read schedules one). It is added only for a caller holding `canAuthorWorkbench` in THAT Space's
role table (checked per row as `/integrity` would check it; everyone on Basic). One list request instead of one
`/integrity` request per row, each up to 600 listed items, and no config path leaves the server. Code:
`SpaceRoutes.withIntegrity`, `SpaceIntegrityRoutes.summary`; UI `inspecto-ui/src/app/inspecto/spaces/space-integrity.ts`.

## References

- Plan: `docs/superpower/2026-11-11-space-evolution-plan.md` §4.1, §4.2, D-SE1.
- Link census: `platform/inspecto-engine/src/main/java/com/gamma/pipeline/LinkCensus.java`, `LinkGraph.java`.
- Folder layout: [Space anatomy](architecture-space-anatomy.md).
- Code: `inspecto/src/main/java/com/gamma/service/SpaceLock.java`,
  `inspecto/src/main/java/com/gamma/control/SpaceIntegrityRoutes.java`,
  `inspecto/src/main/java/com/gamma/service/SpaceLockEvents.java`,
  `inspecto/src/main/java/com/gamma/service/SpaceLockAccept.java`,
  `inspecto/src/main/java/com/gamma/control/SpaceLinksMode.java`.
- Link census plan: `docs/superpower/2026-11-11-space-link-census-plan.md` §2.2–§2.4, LC-4, LC-6.
