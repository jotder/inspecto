# Space evolution — a Space the server understands as a whole, reached without breaking one

**State:** IN PROGRESS — D-SE1…D-SE9 answered (§6); SE-0, SE-6a, SE-1a, SE-3a, SE-5a, SE-5b and SE-6b built (table below); SE-2a is a peer lane; what is left is gated (§5).
Board row `SPACE-EVOLUTION-1`.
**Depends on:** [`2026-11-11-space-link-census-plan.md`](2026-11-11-space-link-census-plan.md) phase LC-1
(the declared link census and its graph). This plan does not repeat that design; it builds on it.
**As-built today:** [Space anatomy](../okf/product/module/config/architecture-space-anatomy.md).

## Progress

| Step | State | Commits | Notes |
|---|---|---|---|
| SE-0 corpus equivalence gate, old-reader check | built 2026-11-12 | `7243bcd07` | 3 Spaces + 30 templates; capability flags registered: `space.integrity`, `links.delete`, `links.save` |
| SE-6a tests that travel with a Space | built 2026-11-12 | `cbbc0523a`, `199affd8d`; fixes `2aad8fc9b`, `ac09275b4` | pushed without the whole module's tests, which left three tests red on master until the fixes; spec `spec-space-tests.md`; five operator questions open |
| SE-1a Space lock, report-only | built 2026-11-13 | `ad946532e`, `1f61ef26f` | `GET /spaces/{id}/integrity`; kill switch `-Dspace.integrity=off`; residuals `SPACE-LOCK-CLOSE-RESIDUALS-1`; spec `spec-space-lock.md` |
| SE-3a column contract, report-only | built 2026-11-13 | `d94dec6be`, `1f62b8235` | spec `spec-column-contract.md` |
| SE-2a hand-rename detection, report | in flight (peer lane) | — | |
| SE-5a template version + origin | built 2026-11-13 | `54d10255d`, `0385d15c4` | the origin lives in `.inspecto/created-from.toon`, not the lock (spec `spec-space-origin.md` §1) |
| SE-5b template upgrade proposal | built 2026-11-13 | `5f43a9475`, `af71a6fad` | `GET` / `POST /spaces/{id}/origin/upgrade`: three-way per file (recorded hash, template now, Space now), applies only `update` / `add`, one Pending Change under a policy; a partial apply keeps the old version until the last group; spec `spec-space-origin.md` §5; known limit `SPACE-UPGRADE-STUCK-VERSION-1` |
| SE-6b promotion into an existing Space | built 2026-11-13 | `45667eed3`, `fc7ff8993` | `POST /spaces/{id}/promotion/preview` and `/apply`: a `GET /export` bundle plus `${NAME}` parameters in the request, a previewed change set, one Pending Change under a policy (D-SE7), removals only with `removals: true`, kill switch `-Dspace.promotion=off`; spec `spec-space-promotion.md`; known limits `SPACE-PROMOTION-PARAM-TYPES-1` |
| SE-1b / 2b / 3b, SE-4 | gated | — | §5 |

Only targeted test classes ran on each lane; the full gate was not run.

## 1. Why

A Space today is a folder of text files held together by naming conventions. The server loads each file on
its own and never holds a model of the Space as a whole. Read from the code on 2026-11-11, that shows as:

| Weakness today | Evidence |
|---|---|
| Three ways to recognise a file (suffix, registry path, fixed name) | anatomy §4.1 |
| A name is the identity, so a rename must find and rewrite every referrer | `PipelineRenameRoutes`; the folder-depth and `job:` defects fixed 2026-11-11 |
| Column names are checked by nothing | anatomy §12.2 |
| A hand edit takes effect unseen | no record of what the server last wrote |
| Server-written state lives inside `config/` | 34 reserved files and 12 reserved directories in `ReservedConfigPaths` |
| A Space made from a Space Template loses the link to it | `createFromTemplate` copies `config/` and rewrites `${SPACE}` |
| No test travels with a Space; `spaces/uat/` is a clone of the demo config | root `.gitignore`, `tools/seed-uat.ps1` |

## 2. The rule of this plan: nothing breaks

Every workstream below obeys all of these. A step that cannot is not built.

1. **No file format change is required of any Space.** A Space written today loads, unchanged, on every
   release of this plan. New facts about a file are kept **beside** it (the lock, §4.1), never demanded inside it.
   Reason, verified: some parsers are strict and fail closed on an unknown key (`SafetyPolicyFiles`), so a new
   header key inside existing files could turn a working Space into one that does not load.
2. **Readers before writers, one release apart.** A release that can *read* a new shape ships first. Only the
   next release may *write* it. A Space written by the new release names the oldest product that reads it in
   `space.toon` `min_product_version` (the key exists today and is checked at boot).
3. **Shadow, then opt-in, then default, then retire.** Each capability first only *reports* (shadow). Then one
   Space may opt in. Then new Spaces get it by default. Existing Spaces change only by an explicit operator step.
   The old path is retired only by a recorded operator decision, never by a release note. One recorded
   exception: delete refusal for every kind reaches every existing Space once its shadow period is clean
   (link census plan, D-LC3).
4. **A Space always boots.** No integrity finding ever stops a Space from loading. In the strictest mode the
   *one broken object* stays inactive and says why; everything else runs.
5. **Nothing moves at boot.** Files move only through an explicit migrator with `--dry-run`, copy → verify →
   atomic rename, never overwrite — the rule `SpaceMigrator` and `SpaceAuditDir` already follow.
6. **Every step is reversible until its retire step.** Opt-out restores the previous behaviour with no data loss;
   the plan names the rollback for each workstream.
7. **One kill switch per capability** (a `-D` flag, default on only once the capability is past shadow), so an
   operator can turn a misbehaving check off without a new build.
8. **Proof before each default flip:** the corpus equivalence gate (§3) is green, and the capability has run in
   shadow on the shipped Spaces with zero false findings.

## 3. SE-0 — the safety net, built first

**Corpus equivalence gate.** A test that loads every shipped Space and every Space Template
(`spaces/*`, `spaces/_templates/*`) twice — capability off and on — and asserts the same registered Pipelines,
Jobs, Enrichments, Connections, components, Alert Rules and the same load warnings. It runs in the affected-module
plan of every change under this row. A capability that changes what loads fails here, before any operator sees it.

**Old-reader check (release gate).** Before a release that *writes* a new shape: the previous release's bundle
boots a Space the new release wrote, or the Space carries the `min_product_version` that makes the old release
warn. Recorded in the release checklist.

**Finding baseline.** The first shadow run on each shipped Space is reviewed by hand; each finding is either a
real defect in that Space (fixed there) or a false positive (the capability is fixed). Shadow ends at zero unknowns.

## 4. Workstreams

Each has: the target, how it arrives, what stays compatible, the rollback, the proof.

### 4.1 SE-1 — the Space lock (the server's model of a Space)

**Target.** `spaces/<id>/.inspecto/space.lock`: one derived, signed file listing every live config with its
path, kind, id, a permanent `uid`, its content hash, and the resolved link graph (from the link census). It is
derived state: deleting it loses nothing but history of what the server last saw.

**Arrival.**
1. *Shadow:* computed at boot and after every server-side write. Reported at `GET /spaces/{id}/integrity`
   (broken links, duplicates, files changed outside the server). Refuses nothing, changes no load.
2. *Opt-in* (`safety-policy.toon` `links: refuse-activation`): a Pipeline or Job with a dangling link stays
   inactive; a save or delete that would break a link is refused with the dependents listed (`?force=true` as
   today).
3. *Default for new Spaces*, after two releases of clean shadow (D-SE2).

**Compatible.** No config file changes. The directory `.inspecto/` exists today (the fingerprint salt) and is
not scanned by any loader. A Space without a lock is simply computed fresh.
**Rollback.** Kill switch `-Dspace.integrity=off`; or delete the lock; or set `links: report`.
**Proof.** Equivalence gate; one planted break per link type is reported; boot time measured on the largest
shipped Space with the switch on and off (bound agreed in D-SE1).

### 4.2 SE-2 — permanent ids without touching files

**Target.** A config keeps its human name in the file; its permanent `uid` lives in the lock. Links stay written
as names — hand-authoring does not get harder. What changes is what the server can *tell*:

- same `uid`, new name → a **rename**, even when done in a text editor. The server lists the referrers and offers
  to rewrite them (the census `REWRITE` links), or rewrites them when the Space opted in.
- `uid` gone → a delete; the dependents are reported.
- A file is matched to its `uid` by path first, then by content hash (a moved, unchanged file keeps its `uid`).

**Arrival.** Shadow report first ("this looks like a rename of X; 4 files still say X"), then opt-in auto-rewrite.
**Compatible.** Files unchanged; the rename route keeps working and now reads the same census.
**Rollback.** Turn auto-rewrite off; the report remains.
**Proof.** Hand-rename a Pipeline in a copy of the demo Space: the report names `orders/orders_daily_enrich.toon`
and the Jobs; with opt-in they are rewritten and the Space loads identically apart from the name.

### 4.3 SE-3 — columns as a contract

**Target.** A Schema's mapped columns are already declared (`mapping.fields`). The lock records, per Dataset, the
columns it offers and, per consumer, the columns it uses (Dataset `dateField`, Widget `controls`, Expectation
`column`, Decision Rule `field`, Reconciliation `keyColumns` / `compareColumns`, and SQL text read through the
DuckDB parse tree). A change to a Schema is classed: *added* (safe), *removed*, *renamed*, *type narrowed*
(breaking), with the consumers it breaks.

**Arrival.** Shadow report; then a warning on Schema save listing the consumers; then opt-in refusal.
**Compatible.** Derived entirely from files as they are. SQL the parser cannot read is reported as "not
analysed", never as broken.
**Rollback.** Kill switch; warning-only mode.
**Proof.** Rename a mapped column in a copy of the demo Space: the widgets, the Expectations and the Enrichment
SQL that use it are listed, and nothing else.

### 4.4 SE-4 — three zones: authored, state, data

**Target.** `spaces/<id>/state/` holds what the server writes about config: `pending-changes/`,
`action-requests/`, `regulatory-reports/`, `screening-hits/`, `publication-approvals/`, `recon-state/`,
`expectation-baselines/`, `agent/`, and the sealed `audit/` of a Space without a data root. `config/` then holds
only what people author. Fixed-name settings files stay in `config/`: they are authored.

**Arrival** (the `SpaceAuditDir` pattern, already proven for the sealed logs).
1. *Readers:* each store reads `state/<dir>` first and falls back to `config/<dir>`. Writes still go to `config/`.
2. *Opt-in move:* an explicit migrator (`--dry-run`, copy → verify hash → atomic rename, never overwrite) moves
   one Space; from then on that Space writes to `state/`.
3. *Default for new Spaces.* Existing Spaces stay where they are until moved by their operator.

**Compatible.** `ReservedConfigPaths` keeps reserving the old locations for as long as any Space may hold them,
so no import can write there in the meantime. `BackupTask`, bundle export and the root `.gitignore` learn
`state/` in step 1, before anything is written there. Signatures are over content, not path, so signed records
stay valid after the move (to be confirmed per store in step 1 — a store whose MAC covers its path is excluded
until that is changed).
**Rollback.** Until the move: nothing to undo. After it: the migrator's reverse direction; the fallback read
means a half-moved Space still reads every record.
**Proof.** Equivalence gate; a Space moved and moved back is byte-identical; every store's own test runs against
both locations.
**Deliberately last among the structural steps:** it is the only one that moves files.

### 4.5 SE-5 — Space Templates with provenance and assisted upgrade

**Target.** `template.toon` gains `version:`. A Space created from a template records, in its lock,
`origin: {template, version, per-file hash at creation}`. That is enough to answer, for any Space: which files
still equal the template, which the Space changed, and what a newer template version would change.

**Arrival.**
1. Templates get a version; creation records the origin (additive, new Spaces only).
2. *Adopt:* an existing Space may be matched to a template version by file hashes, on request.
3. *Upgrade proposal:* a three-way comparison (template then, template now, the Space) produces a change set the
   operator reviews — delivered as Pending Changes where the Space has an approval policy. Never automatic.

**Not proposed:** a live `uses: template@version` overlay resolved at load. It would change how every config is
found, which is exactly the risk rule 1 forbids. Copy-with-provenance gives upgrades and drift without it.
**Compatible.** A Space with no origin behaves as today. **Rollback.** Ignore the origin record.
**Proof.** Create from `orders-starter`, edit one file, bump the template: the proposal touches only unedited
files and flags the edited one as a conflict.

### 4.6 SE-6 — tests that travel with a Space, and promotion

**Target.** `spaces/<id>/tests/` (outside `config/`, so no loader reads it): a case is sample input plus the
expected landed row counts, rejects and a checksum per Dataset. A `space.test` Job and
`POST /spaces/{id}/tests/run` execute cases through the existing dry-run engine, touching no live store.
Promotion: the same locked Space plus a small per-environment parameter file is applied to a target Space as a
previewed change set.

**Arrival.** Tests first (purely additive). Promotion into an *existing* Space is new behaviour (today a
whole-Space bundle only seeds a new Space) and comes last, behind D-SE7.
**Compatible.** Nothing reads `tests/` today. **Rollback.** Delete the folder; disable the Job type.
**Proof.** The demo Space's existing manual test loops (its config README) become cases and pass.

## 5. Order

| Step | Workstream | Moves files? | Changes a load? | Needs |
|---|---|---|---|---|
| SE-0 | equivalence gate, old-reader check | no | no | — |
| SE-1a | lock, shadow | no | no | LC-1 |
| SE-3a | column contract, shadow | no | no | SE-1a |
| SE-2a | rename detection, report | no | no | SE-1a |
| SE-5a | template version + origin record | no | no | SE-1a |
| SE-6a | tests in a Space | no | no | — |
| SE-1b / 2b / 3b | opt-in enforcement, auto-rewrite | no | only for a Space that opted in | zero-false-finding shadow |
| SE-4 | state zone | yes, by migrator | no | readers shipped one release earlier |
| SE-5b | template upgrade proposal | no | no | SE-5a |
| SE-6b | promotion | no | no | D-SE7 |

Everything above the first enforcement row is report-only: it can ship in any order after SE-0 and cannot break
a Space by construction.

## 6. Decisions for the operator

All eight answered by the operator on 2026-11-11.

| # | Question | Decision |
|---|---|---|
| **D-SE1** | When is the Space lock computed? | **After boot, in the background.** A Space starts as fast as today; the integrity route answers "computing" until the lock is ready. |
| **D-SE2** | When does enforcement become the default for **new** Spaces? | **After two releases of clean shadow** on the shipped Spaces (zero false findings). Existing Spaces never change on their own. |
| **D-SE3** | A hand rename is detected — what happens to the files still using the old name? | **Report by default; rewrite only in a Space that opted in.** |
| **D-SE4** | How strict is a Schema save that breaks a column consumers use? | **Stopped with the list of what breaks; `?force=true` goes ahead** — the pattern a Pipeline delete uses today. Never a hard refusal. |
| **D-SE5** | Where does the state zone live? | **`spaces/<id>/state/`, beside `config/`** — small governance records, backed up and moved with the config. |
| **D-SE6** | Does `.history/` move to `state/`? | **No. It stays beside each file.** |
| **D-SE7** | Promotion into an existing Space? | **Yes, as a previewed change set through Pending Changes** — nothing lands in the target Space without review. **Detail (operator, 2026-11-13):** a target Space with no approval policy lets an administrator apply the previewed change set directly; with a policy it goes through Pending Changes. |
| **D-SE9** | The SE-1a integrity report's gate, and SE-6a's five open questions | **Answered 2026-11-13, all as built:** `GET /spaces/{id}/integrity` stays `canAuthorWorkbench`; a dry run of `space.test` runs the cases (they write only to temp folders); a Pipeline with several outputs is checked across all of them together; the test routes also answer unprefixed for the default Space; the runner stays in the control plane. |
| **D-SE8** | When may the old paths be retired (a Space without a lock; state inside `config/`)? | **At a named major release.** Both paths live until then; that release ships the migrator, runs it as a documented upgrade step, and removes the old path. This is the agreed exception to "breaking changes are free, no shims" for this plan. The release is named when SE-4 ships. |

## 7. Risks

| Risk | Guard |
|---|---|
| A shadow finding is wrong and trains operators to ignore the report | zero-unknown baseline before any default (§3) |
| Lock computation slows boot or reload | measured in SE-1a; background mode (D-SE1); kill switch |
| The lock disagrees with the loader about what is live | both read one file set (`PipelineDependents.configFiles` today; the census later); equivalence gate |
| A store's signature covers its path, so a moved record fails verification | checked per store in SE-4 step 1; such a store does not move |
| Two releases in the field at once | readers-before-writers; `min_product_version` |
| A peer tool or script hard-codes `config/pending-changes` | grep the repo and `tools/` in SE-4 step 1; the fallback read covers what is missed |

## 8. Not in scope

A header inside config files; discovery by header instead of suffix; a live template overlay; cross-Space links.
Each would change how existing files load. They can be reconsidered once every Space has a lock — as a new plan.
