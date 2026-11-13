# Space lock — the server's signed record of a Space (SE-1a)

**State:** built 2026-11-12 (SE-1a of the Space evolution plan, §4.1 step 1: shadow, report-only). It refuses
nothing and changes no load. Enforcement (SE-1b), rename handling (SE-2a) and any UI are not part of it.

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

Gated on `canAuthorWorkbench` (it lists every config path); available on every edition (D-LC5). Answers 200:

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

- **Enforcement** of any kind (SE-1b): a broken link, a duplicate or an outside edit refuses nothing.
- **Rewriting** a renamed kind other than a Pipeline, and any rewrite in a Space that did not opt in (§3a).
- **Edits at runtime with no later server write** are reported at the next computation, not when they happen;
  there is no file watcher.
- **Server writes outside a request** (a Job or background task writing a config file) carry no window and may be
  reported as outside edits.
- **Files outside the live set** (`.asn`, `.sql`, unreferenced `.toon` files) and the single-tenant default Space.
- **UI.**
- **Write windows live in memory.** A crash loses them, so the server writes not yet in a lock are reported as
  outside edits at the next boot. A second JVM writing the same Space, and a server write to a Space other than the
  one the request is bound to, carry no window here and are reported as outside edits too.

## References

- Plan: `docs/superpower/2026-11-11-space-evolution-plan.md` §4.1, §4.2, D-SE1.
- Link census: `platform/inspecto-engine/src/main/java/com/gamma/pipeline/LinkCensus.java`, `LinkGraph.java`.
- Folder layout: [Space anatomy](architecture-space-anatomy.md).
- Code: `inspecto/src/main/java/com/gamma/service/SpaceLock.java`,
  `inspecto/src/main/java/com/gamma/control/SpaceIntegrityRoutes.java`.
