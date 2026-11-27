# Space coherence — one graph behind every edit, strict for agents first (`SPACE-COHERENCE-1`)

Status: **APPROVED 2026-11-27 — D-CO1…D-CO6 all DECIDED (operator, 2026-11-27); nothing built; next is SC-0.** Owner: this shift. Items: `module.config`, `module.engine`,
`module.mcp`, `layer.product`. Builds on `SPACE-LINK-CENSUS-1` and `SPACE-EVOLUTION-1`
([anatomy §12](../okf/product/module/config/architecture-space-anatomy.md),
[plan-space-evolution](../okf/product/module/config/plan-space-evolution.md)); it replaces neither.

## 1. Why now

An agent can now create almost every kind of config for any kind of user. The number of edits per day goes up and the
care per edit goes down. The existing protection was designed for a person who changes one file at a time and reads the
warning. This plan closes the gaps that matter at agent speed: links nothing reads, edits judged one file at a time,
copies that drift, and components nobody can account for.

## 2. What exists (do not rebuild)

| Piece | What it does | State |
|---|---|---|
| Link census (`LinkCensus.LINKS`, `LinkGraph`) | one declared list of 48 links between config files; rename, impact, validate, import and delete checks read it | built |
| Delete and save refusals (`LinkRefusals`) | refuse a delete of a used target, or a save that adds a dangling link | built, default `shadow` (reports only) until the operator's D-LC3 flip |
| Space lock (`GET /spaces/{id}/integrity`) | per-file hash, permanent `uid`, duplicates, outside edits, hand-rename detection | built, report-only by default |
| Column contract | classes a Schema change and lists the consumers it breaks | built, refusal opt-in |
| Agent tools | `validate_config`, `impact`, `get_space_integrity`, `propose_change`, `dry_run_pipeline` | built |
| Baseline | 3 Spaces and 30 Templates: 0 findings in every check (2026-11-13) | gate `SpaceFindingBaselineTest` |

## 3. Findings (measured 2026-11-27 on the 1,310 tracked config files, 25,474 lines)

Method: a read-only scan of `git ls-files spaces`; the script is not committed. Counts by text match are upper bounds.

| # | Finding | Evidence |
|---|---|---|
| F1 | **The census reads 14 kinds; at least 9 kinds that hold links are outside it.** | 168 files are of kinds with no link declared. Unread keys: `kpi.dataset` (26), `risk-score.dataset` (24), `anomaly-model.dataset` (8), `view.pipeline` (4), `insight-profile.dataset`, and `model` + `dataset` in six entity-score files. Runbook, pattern-pack and case-rule links were not traced. |
| F2 | **11 of the 48 declared links are checked by nothing.** | anatomy §12.2 rows marked **none**: Job `dataset`, `target`, `pipeline_config`, `on_signal`, `when`, `dir`; Enrichment `input.database`, `references.*.path`; Expectation `refDataset`; `nav-menus` binding |
| F3 | **Two derivations of "what does this reference".** | the server's census, and `refsForComponent` in `inspecto-ui/.../component-model/refs.ts`, which feeds the Usage graph and the bundle closure |
| F4 | **Every check judges one file or one target.** | Pipeline impact, column impact and delete `brokenLinks` are separate reads; nothing answers "what do these five edits do together". Whether `propose_change` takes more than one component is not verified |
| F5 | **Refusals are in shadow, for agents too.** | `-Dlinks.delete` and `-Dlinks.save` default `shadow`. Precedent for treating agents differently: an unknown key is 422 for an agent and a warning for a person (`UNKNOWN-KEYS-SILENT-1`) |
| F6 | **Facts are restated.** | 200 of 300 Datasets carry their own `columns` list beside the Schema; 1,740 hand-written `dirs.*` path lines (9.0 per Pipeline); 65 byte-identical copies in 22 groups (`demo-users.toon` 19 times, `roles.toon` 15, four pattern packs 4 each); 45 file names whose content has diverged between owners |
| F7 | **Nothing says whether a component is used, or why it exists.** | 140 of 300 Datasets are named by no other config; a Dataset can be a legitimate end point, and config alone cannot tell which. 35 `owner:` keys across about 640 registry components; no record of the request that created one |
| F8 | **Three ways to recognise a file, and about 30 one-off file names.** | suffix, registry path, fixed name (anatomy §4.1); the scan could not classify 30 files by kind |

## 4. Recommendation

**One sentence:** make the link graph the only door — every create, edit, rename and delete, by a person or an agent,
is a *change set* judged against the whole graph before it is written — and make agents strict first.

### 4.1 Harden

- **H1 Complete the census.** Add the F1 kinds and give the F2 links a check or a recorded reason. Extend
  `LinkCensusParityTest` with a corpus rule: a reference-shaped key in any shipped config that the census does not
  declare fails the build (ratcheted from today's list, so it cannot grow).
- **H2 Agents strict now.** For an agent Subject, a save that adds a dangling link and a delete of a used target are
  refused, in every Space, without waiting for the D-LC3 flip for people. The refusal names the fix (the missing
  target, the dependents), which is what an agent needs to retry correctly.
- **H3 No preview, no write, for agents.** `propose_change` accepts only a change set that was previewed (§4.2) and is
  unchanged since. The preview id is stored on the Pending Change and the audit row.
- **H4 Duplicates refused at save** (the open scope of `SPACE-LINK-CENSUS-1`), plus a **content fingerprint**: a new
  component whose content equals an existing one apart from id and description answers "this is `X`" — refused for an
  agent (reuse `X`), a warning for a person.
- **H5 Restated facts become a finding only when they disagree.** A Dataset `columns` list equal to its Schema is
  redundant and harmless; one that differs is drift and is reported by the Space lock.

### 4.2 Impact analysis that is cheap enough to run on every edit

- **I1 One graph read.** `GET /spaces/{id}/graph` with `uses=<kind:id>` and `usedBy=<kind:id>`, transitive, columns
  included. The agent `impact` tool, the Catalog Usage tab and the bundle closure read it; `refsForComponent` (F3) is
  retired once the corpus shows the same edges.
- **I2 Change-set preview (what-if).** `POST /spaces/{id}/changes/preview` takes any number of create, update, rename
  and delete edits and answers, with nothing written: the findings the set *introduces* (overlay graph minus today's,
  the method `BundleRoutes.introducedIntegrityFindings` already uses), the blast radius up to Dashboards, navigation and
  Jobs, the column-contract class of each Schema change, and the Space test cases that touch an affected Dataset.
  Apply is one Pending Change, all or nothing. It reuses the promotion preview and apply (SE-6b).
- **I3 A graph diff for the reviewer.** The Pending Change shows nodes and links added, removed and re-pointed, and
  columns added or dropped — not a text diff of TOON.
- **I4 Incremental.** The lock already holds a hash per file: re-resolve only changed files and their neighbours. Set
  the budget after measuring (SC-0); the aim is a preview fast enough to run on every agent step.
- **I5 Tests chosen by impact.** A change set runs only the Space test cases (SE-6a) whose Datasets it reaches, the
  way `tools/affected-modules.mjs` picks Maven modules.

### 4.3 Simplify

- **S1 Rename without rewrite.** The lock keeps an **alias** from the old name to the `uid` for a set period: links
  that still say the old name resolve and are reported as "uses a retired name". The rewrite becomes an ordinary
  change set, done when convenient. This removes the one edit that today must touch every referrer at once.
- **S2 Inherit instead of restate, opt-in.** A Dataset with no `columns` reads them from its Schema; a Pipeline with no
  `dirs` gets the standard layout under its store. Both are additions a Space may adopt; no existing file changes
  (evolution rule 1). Whether the loader already defaults `dirs` is not verified.
- **S3 Shared Template files have one master.** `demo-users.toon`, `roles.toon` and the shared pattern packs are
  written once and copied into each Template by a tool with `--check` in CI, so the 65 copies cannot drift. No live
  overlay at load (refused in SE-5).

### 4.4 Account for everything (the new part)

- **A1 Provenance in the lock, not in the file.** Per `uid`: who created it (a person, or an agent and the person it
  acted for), the change set, and the request in the requester's words. "Why does this exist" gets an answer.
- **A2 Use, from evidence.** Join the graph with what the run and query ledgers already record: last read, last run,
  dependents. A component with no dependents and no read in a set period is *unused*, reported per Space.
- **A3 A clean-up proposal, never automatic.** The unused list becomes a change set a person reviews (I2), with the
  same preview and the same undo (`.history/`).
- **A4 An agent's footprint.** Each Space reports, per agent, what it created and how much of it is used. An optional
  ceiling on unused components per agent turns "create freely" into "create, then earn the next one".

## 5. Phases

Each phase follows the evolution rules: shadow, then opt-in, then default; one kill switch each; a Space always boots.

| Phase | Builds | Proof |
|---|---|---|
| **SC-0 Measure** | the §3 scan as a committed tool with a ratcheted baseline; graph build time on the largest Space; the answers to the "not verified" items | numbers in this plan replaced by tool output |
| **SC-1 One graph** | H1, I1; retire `refsForComponent` | parity test green; Usage tab and server show equal edges on the corpus; baseline still 0 findings |
| **SC-2 Change sets** | I2, I3, H2, H3 | one planted break per link type is refused for an agent and previewed for a person; a five-edit set applies or fails as one |
| **SC-3 Hygiene** | H4, H5, A1, A2 | the 22 duplicate groups and every differing `columns` list are reported; provenance present on every new component |
| **SC-4 Simplify** | S1, S2, S3, I4, I5, A3, A4 | a rename touches one file and the Space loads identically; preview time within the SC-0 budget |

SC-1 and SC-2 are the hardening that agents need; SC-3 and SC-4 can wait for demand.

## 6. Impact of this plan itself

- **Code reached:** `LinkCensus` / `LinkGraph` / `ComponentIntegrity` (`inspecto-engine`), `LinkRefusals` and the save
  doors (`inspecto`), the Space lock, `McpTools` (`inspecto-mcp`), the Catalog Usage tab and `refs.ts` (`inspecto-ui`).
- **Risk:** a census entry that judges a link wrongly refuses a valid agent save (H2). Guard: every new link runs one
  release in shadow on the corpus first; the gate is `SpaceFindingBaselineTest` at 0.
- **Compatibility:** no file format change is required of any Space; S2 is an addition older releases must be able to
  read, so it ships reader-first (evolution rule 2).
- **Open rows it absorbs:** scope (a)–(d) of `SPACE-LINK-CENSUS-1`; it leaves the D-LC3 flip for people untouched.

## 7. Decisions (all signed, operator, 2026-11-27)

| # | Decision | Decided |
|---|---|---|
| **D-CO1** ✅ | Agents strict before people (H2)? | **Agents strict now**, in every Space: an agent retries from a refusal at no cost; a person keeps the warning until D-LC3 |
| **D-CO2** ✅ | "No preview, no write" for agents (H3)? | **Required**, with SC-2 |
| **D-CO3** ✅ | An exact duplicate from an agent: refuse or warn (H4)? | **Refuse**, naming the existing component; a person gets a warning |
| **D-CO4** ✅ | How long does a retired name resolve (S1)? | **Until its last referrer is rewritten**, reported from day one |
| **D-CO5** ✅ | Inherit columns and dirs (S2)? | **Opt-in per Space**; never rewrite existing files; reader-first |
| **D-CO6** ✅ | A ceiling on an agent's unused components (A4)? | **Report first**; the ceiling is decided after one release of numbers |

## 8. Not verified

Whether `propose_change` carries one component or several; whether an agent save already passes `LinkRefusals`; whether
the loader defaults `dirs`; the graph build time; the links of Runbooks, pattern packs and case rules; whether the run
and query ledgers hold enough to date a last read (A2). SC-0 answers each before anything is built on it.

## 9. Board rows

Filed 2026-11-27: `SPACE-COHERENCE-1` on `docs/BACKLOG.md` (P2, §3.7); this plan's row is in `docs/INDEX.md`.

## 10. Carried over from `SPACES-CONSOLIDATION-1` (plan archived 2026-11-27)

P0–P5 of the Spaces consolidation shipped; what was built is in
[spec-space-data-layout](../okf/product/module/config/spec-space-data-layout.md). The undone work lives here now, on
the board rows `SPACES-CONSOLIDATION-1`, `SANDBOX-CLEANUP-1` and `LA-INDEX-UNDER-DATA-ROOT-1`. It is operations work
on the shared sandbox, separate from SC-0…SC-4, and none of it blocks them.

**In order (item 1 is the gate for 2 and 4):**
1. **Boot check.** Attach and boot the three private Spaces (`inspecto-mfs`, `mfs-demo`, `la-mobile-money`) with
   `tools/attach-private-spaces.ps1`, then start the Preview install (`:8080`) and the Basic install (`:8098`) once
   each, one at a time. None has run since its state moved to `C:\sandbox\inspecto_spaces` on 2026-11-25.
2. **Delete the quarantine.** `C:\sandbox\_pending-delete-20261125` holds 12.74 GB (47 folders and 156 logs, listed
   in its `MANIFEST.txt`); nothing has been deleted. The P0 backups in `C:\sandbox\backup\spaces-p0-20261125` (about
   48 GB) stay until item 1 has passed.
3. **Owner questions.** Class D (`la-share`, `builds`, `la-deploy-pkgs`, `inspecto`, `inspecto_training`,
   `la-video-out`), `antigravity`, the older backups beside the P0 set, and the debris in `inspecto-preview\backup`
   and `parked`.
4. **One build of `master` against `inspecto_spaces`.** It is also the first real run of the `package.ps1` seeding
   step, which has only been parse-checked.

**Also open:**
- `assist.safety.roots` file routes against a data root outside the spaces root, and the `SPACES_DATA_ROOT` mount
  form, are not verified.
- Whether to commit the config of `telco-assurance`, `cricket-analytics` and `la-mobile-money` (D-SC5 said track; the
  `telco-assurance` description says it is not committed) waits on the operator.
- Five `config.secrets` files could not be copied by the shift account; their owning Windows account must copy them.
- The `bi-p1-projection` worktree is blocked by an ownership check; its unpushed commits are unchecked
  (`git cherry origin/master HEAD` once access is granted, never a blind delete).
- The `inspecto-basic-*` entries in `.claude/launch.json` name a folder that no longer exists.
- The test that writes into `spaces/_templates` is not found (`TEMPLATE-STRAY-STATUS-DIR-1`).
- `LA-INDEX-UNDER-DATA-ROOT-1`: the Link Analysis index stays inside the Space's config folder (D-SC9), waiting for
  a consumer to ask.
