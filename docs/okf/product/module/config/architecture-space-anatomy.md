# Space anatomy — every folder and file in a Space

> **Reference page** for Product Map Item `module.config`. Checked against the code and the shipped Spaces
> (`spaces/demo`, `spaces/telco-assurance`, `spaces/ucc`, `spaces/cricket-analytics`) on 2026-11-11.
> How a Space boots, its lifecycle and its three module gates are on [Spaces](architecture.md); this page
> answers the other question: *what is this file, who reads it, and may I edit it?*
>
> **This page is checked, not trusted.** `node tools/check-space-anatomy.mjs` (CI `guards` job, so every commit)
> fails when the code declares a reserved file, registry directory, DuckDB store or config suffix this page does
> not list, when a census table here names one the code does not declare, or when a class member named here (as
> `SpaceRoot.dataRoot`) no longer exists. The link table of §12.2 is generated from the link census
> (`node tools/render-space-links.mjs`, `--check` in CI). What each file *means* is prose and is not
> machine-checked. **One home per fact:** boot, lifecycle, data-root rules and "what is not a Space" live on
> [Spaces](architecture.md) and are linked from here, never restated.

## 1. The shape at a glance

```
spaces/                                   the Spaces root (-Dspaces.root, default <install_dir>/spaces)
├── <id>/                                 one Space; id matches [a-z0-9][a-z0-9-]{0,62}
│   ├── space.toon                        manifest (§3)
│   ├── config/                           EVERYTHING AUTHORED: data-plane configs, registry, settings (§4)
│   │   ├── <area>/…_pipeline.toon …        suffix-named configs, in any sub-folder (§4.2)
│   │   ├── registry/<kind>/<id>.toon       Studio components, one file each (§4.3)
│   │   ├── registry/<kind>/.history/       their saved versions
│   │   ├── pipelines/                      Pipelines authored in the UI (§4.5)
│   │   ├── roles.toon, modules.toon, …     fixed-name Space settings (§4.4)
│   │   └── pending-changes/, recon-state/, … server-written state inside config (§4.6)
│   ├── state/                            the state zone: read, never yet written — no shipped Space has one (§4.7)
│   ├── config.secrets/                   keys and anchors, never imported or exported (§5)
│   ├── .inspecto/value-fingerprint.salt  salt for value fingerprints (§3)
│   │   ─ ─ ─ ─ ─ ─ ─ ─ below this line: the DATA ROOT (may sit on another volume, §2) ─ ─ ─ ─ ─ ─
│   ├── data/                             inboxes, landed Datasets, events, reference files, backups (§6)
│   ├── audit/                            run journal, run logs, artifacts, sealed audit logs (§7)
│   └── duckdb/                           the Space's operational DuckDB stores (§8)
├── <id>.ref                              a Space reference: one line, the absolute path of a Space kept elsewhere
├── _templates/<template-id>/             Space Templates (§10)
├── _industries/<pack>.toon               industry entries for the Link Analysis gallery (§10)
├── _shared/                              cross-Space state (the Exchange ledger)
└── onboarding-counters.tsv               install-wide onboarding counters
```

Two rules hold everywhere:

- **Config is authored, data is generated.** Everything under `config/` (plus `space.toon`) is meant to be
  read, versioned and edited; everything under the data root is written by the server. The one authored part of
  `data/` is `data/samples/` (§6).
- **Each Space is isolated.** Its own engine, scheduler, event log, stores and connection registry. Nothing
  reads another Space's folder; the only sanctioned cross-Space path is the Exchange (by grant, ledger in
  `_shared/`).

## 2. Config tree vs data root

`config/`, `config.secrets/` and `space.toon` always stay in `spaces/<id>/`. The high-growth state — `data/`,
`audit/` and `duckdb/` — sits under the Space's **data root** (`SpaceRoot.dataRoot()`):

1. `data_root: <path>` in `space.toon` — **absolute, or relative to the app root** (`SPACE-RELOCATABLE-ROOTS-1`), else
2. `-Dspaces.data.root=<mount>` → `<mount>/<id>`, else
3. the Space folder itself (the layout above).

Configs never change when the data root moves: a path such as `data/orders/inbox` or `../data/backups` that
resolves under `<space>/data|audit|duckdb` is redirected onto the data root (`PathJail.onDataRoot`).

### 2.1 The app root, and how a relative data root is stamped

Every path that names a **root** — `spaces.ref`, `space.toon data_root`, `<id>.ref` — may be written absolute or
relative, and relative always means **relative to the app root**: `-Dapp.root` or `APP_ROOT`, else the process
working directory (`AppRoot`). The launchers `cd` to the install directory, so by default the app root is the
install. It is logged beside the spaces root on the `SpaceManager` boot line, because a server started from the
wrong directory still boots — it just resolves every relative root somewhere else.

A moved data root holds an ownership stamp `.inspecto-space` naming its Space folder (`SpaceDataRoot.STAMP`); a
copied Space needs its own data root, or it is refused. **The stamp's form follows the `data_root`'s.** Declared
rooted, it holds the Space's absolute path, as it always has. Declared relative, it holds the Space's path *from
the data root* (`../../spaces/<id>`), so the pair travels together: move the install and the stamp still resolves
to the same Space, where an absolute one would name the old path and refuse every Space — the 2026-11-13
relocation failure.

⛔ Do not "simplify" this to skipping the stamp for relative roots. Two installs *can* resolve relative roots onto
one directory (a shared disk, a second server on the same box), and an unstamped root is then claimed — and
purged — by whichever Space reaches it first. A relative stamp still names a different folder per install, so
ownership is not weakened.

⛔ **"It resolves" is not the test.** Written relative or rooted, a data root may not **overlap** the spaces root
or any Space directory (that would hand this Space another Space's `config/` as an allowed root), may not be a
`*.secrets` directory, and may not be a directory another Space has stamped. One check differs by form, and only
because it means nothing in the other: a **rooted** root's parent must already exist, so an unmounted volume is
refused rather than silently created on the root disk; a **relative** root names a path under the app root, which
no mount can be, so its parents are created on first boot.

⚠ "Rooted", not "absolute": on Windows `Path.of("/mnt/bigdisk").isAbsolute()` is `false`, so an operator's POSIX
path would otherwise be taken for a relative one. The test is `Path.getRoot() != null` (`AppRoot.rooted`). The
refusal rules and the move procedure are on [Spaces §4](architecture.md) and the
[configuration reference](admin-guide-configuration-reference.md).

## 3. Space-level files

| File | Written by | What it holds |
|---|---|---|
| `space.toon` | create / `PUT /spaces/{id}` | `display_name`, `description`, `created_at`; optional `data_root` (absolute, or relative to the app root) and `min_product_version` (a WARN at boot when the running product is older). Optional: a Space without it still boots. |
| `<id>.ref` (in the Spaces root) | operator | the path of a Space folder outside the Spaces root, which must itself be named `<id>` — absolute, or relative to the app root. A real `<id>/` beside it wins. Purge deletes only the `.ref`. |
| `spaces.ref` (in the app root) | operator | the spaces root, when it is not `<app root>/spaces` — absolute, or relative to the app root. `-Dspaces.root` / `SPACES_ROOT` still win. A present-but-unusable pointer fails the boot loudly rather than falling back. |
| `.inspecto/value-fingerprint.salt` | the engine, on first use | the salt for the keyed fingerprints that rejected lines and failure texts carry instead of the values themselves (`FailureText.SALT_FILE`). Deleting it changes every future fingerprint. |
| `.inspecto-space` (data root only) | `SpaceDataRoot.stake` | the ownership stamp described in §2. |

## 4. `config/` — the authored tree

### 4.1 How the server reads it

At boot (and on hot reload) the Space's `config/` is walked **recursively**. Three mechanisms pick files up:

- **By suffix** — a file anywhere in the tree whose name ends in a known suffix (`orders/orders_pipeline.toon`)
  is loaded as that kind (§4.2). Sub-folders are free-form: group by feed, team or topic.
- **By registry path** — `registry/<kind-dir>/<id>.toon` is one Studio component (§4.3).
- **By fixed name** — a small set of files at the config root is read by exact name (§4.4).

A `.toon` file that matches none of these is ignored. TOON rules that bite here (see the team note
`toon-config-traps`): no `#` comments, list fields carry a count (`members[1]: operator`), rows of maps use
the tabular form `tiles[3]{widgetId,span}:`.

### 4.2 Data-plane configs (by suffix)

| Suffix | Kind | Read by | Notes |
|---|---|---|---|
| `*_pipeline.toon` | **Pipeline** — Collector, `dirs:`, parsing, Stage-2 `steps:` | `MultiCollectorProcessor.resolveConfigs` | the heart of a feed; see [Pipeline config keys](spec-pipeline-config-keys.md) |
| `*_schema.toon` | Schema — raw fields, mapping, partitions | named by a Pipeline (`processing.schema_file`) | one per record type in multi-segment Pipelines |
| `*_enrich.toon` | Enrichment (Stage-2), with optional `references:` joins | `ServiceBootstrap.loadEnrichJobs` | |
| `*_job.toon` | Job (cron, on-signal or on-demand) | `ServiceBootstrap.loadJobs` | |
| `*_job_template.toon` | Job template, expanded into each Job naming it | `JobTemplate.discover` | never loaded as a Job itself |
| `*_connection.toon` | Connection (SFTP, local, Postgres, …) | `ServiceBootstrap.loadConnections` | the UI also writes `registry/connections/` |
| `*_meta.toon` | Semantic model (catalog terms) | `ServiceBootstrap.loadSemantics` | |
| `*_rca.toon` | RCA template | `ServiceBootstrap.loadRcaTemplates` | core, loads on every edition |
| `*_tag.toon`, `*_tagrule.toon`, `*_workflow.toon` | Tag, Tag Rule, Incident workflow | `OpsEngineProvider` (optional ops module) | not read on a build without the module |
| `*_caserule.toon` | Case Rule | case-management module | |
| `*_grammar.toon`, `*.grammar.toon` | reusable Grammar | named by a Pipeline | |
| `*_profile.toon` | Decode Profile (ASN.1) | named by a Pipeline | |
| `*_structure.csv`, `*_mapping.csv` | column structure / Mapping table | named by a Pipeline or Schema | |
| `views/<name>_view.toon` | View | `ViewStore` | |
| `imports/<id>_snapshot.toon` | Postgres snapshot import (Add data) | `PostgresSnapshot`, by name | written only by the Add data commit |
| `*.asn`, `*.asn1`, `*.sql` | grammar / SQL files | only when a config names them | |

### 4.3 Registry components — `registry/<kind-dir>/<id>.toon`

One file per component; the file name is the component id. Every UI save writes the current file and a copy
to `registry/<kind-dir>/.history/<id>.vN.toon` (`orders_by_region.v1.toon`, `…v2`, …). The directory → kind
map is `ComponentRegistry` (`platform/inspecto-engine/.../pipeline/ComponentRegistry.java`):

| Area | Directories (kind) |
|---|---|
| Data-plane parts | `connections` · `grammars` · `schemas` · `mappings` · `transforms` · `sinks` |
| Studio / BI | `datasets` · `queries` · `preset-queries` · `widgets` · `dashboards` · `kpis` · `findings-specs` |
| Quality and decisions | `expectations` · `alert-rules` · `decision-rules` · `reconciliations` · `rule-templates` · `requirements` · `runbooks` |
| Investigation | `link-analysis-views` · `geo-map-views` · `pattern-packs` |
| Models | `risk-scores` · `anomaly-models` · `segmentation-models` · `churn-models` · `forecast-models` |
| Notifications | `channels` · `notification-rules` |
| Access (admin only) | `access-catalog` (singleton) · `access-profiles` (one per subject) |
| Governance (admin only) | `workflows` · `sla-policies` · `escalation-rules` |

Example, from `spaces/demo`: `registry/datasets/orders_dataset.toon`, `registry/alert-rules/orders_low_volume.toon`,
`registry/dashboards/orders_overview.toon`. The demo's `config/README.md` has one working, editable sample of
almost every kind, with the screen that edits it.

### 4.4 Fixed-name Space settings (config root)

Read by exact name. Each has its own settings screen or route, usually with a narrower permission than
authoring; **no import, bundle or Exchange may write any of them** (`ReservedConfigPaths.FILES`).

| File | Meaning |
|---|---|
| `roles.toon` | role → capability table, e.g. `roles[1]:` / `- name: manager` / `capabilities[3]: canManageIncidents,…` |
| `demo-users.toon` | Demo sign-in users and their roles (Demo auth only) |
| `access-policies.toon` | access policies |
| `approval.toon`, `approvers.toon` | maker-checker policy and its approvers |
| `modules.toon` | `disabled: [ids]` — the per-Space **Enabled** gate; absent = every installed module on |
| `safety-policy.toon` | the Space Safety Policy tier |
| `egress.toon` | outbound allowlist (`allow[n]: …`, `seededAt`) — an SSRF boundary |
| `offers.toon`, `grants.toon` | Data Exchange offers and grants |
| `publication-destinations.toon`, `mail-attachments.toon`, `attach-approvals.json` | where published output may go, and attachment approvals |
| `branding.toon` | `logo_data_url`, `caption`, `footer_text` |
| `nav-menus.toon` | the Space's menu: `version`, `landing`, `nodes[]` each with `title`, `icon`, `binding {kind, componentId}` |
| `geo.toon`, `link-analysis.toon`, `icon-map.toon` | per-Space preference documents for Geo Map, Link Analysis and icons (`SettingsRoutes`) |
| `timezone.toon` | the Space's time zone (`TimezoneSettings`) |
| `list-limit.toon` | default row limit for lists; a value outside the allowed range reads as the default (`ListLimitSettings`) |
| `pipeline-history.toon` | Pipeline history settings (`PipelineHistorySettings`) |
| `data.toon` | the Add data switch `data.self_import` (`DataSettings`); an unreadable file reads as unset |
| `scheduler.toon`, `la-limits.toon`, `partition.toon`, `notification-preferences.toon`, `onboarding-counters.tsv` | **install-wide**: read beside the Spaces root (or the single Space's write root), not per Space |
| `rename.journal` | an in-progress Pipeline rename (`/pipelines/rename/resume`) |
| `dataset-publications.tsv` | last publication per Dataset (§7) |
| `case-link.json`, `investigation-details.json`, `la-integration.json` | Link Analysis Investigation state: Case link, name and purpose, where evidence and webhooks go |
| `day-manifest.tsv` | the per-day delivery manifest; it lives in a Pipeline's `markers` directory (`DeliveryCheck`), reserved by name so an import cannot forge one |

### 4.5 Authored Pipelines — `config/pipelines/`

A Pipeline built in the UI (`/pipelines/authored`) is saved under `config/pipelines/`. A Space whose
`config/flows/` predates the rename keeps resolving there until it is renamed on disk
(`SpaceRoot.pipelinesSubdir`: `pipelines/` unless only `flows/` exists). <!-- vocab-allow: on-disk directory name kept by the rename (GLOSSARY §13) -->

### 4.6 Server-written state inside `config/`

These live in the config tree but are written by the server, never by hand or by import
(`ReservedConfigPaths.DIRS`):

| Directory | Holds |
|---|---|
| `pending-changes/` | maker-checker Pending Changes (HMAC-signed with the key in `config.secrets/`) |
| `action-requests/`, `regulatory-reports/`, `screening-hits/`, `publication-approvals/` | signed records of those add-ons |
| `recon-state/` | Reconciliation break state |
| `expectation-baselines/` | accepted Expectation baselines |
| `agent/` | the assist agent's `policy.json`, `approvals.jsonl` and run logs |
| `audit/` | sealed, hash-chained logs (LA snapshots and Investigations, LA uploads, the Entity Fact log) — **only while the Space has no separate data root**; with one, they move to `<data root>/audit/` on first open |
| `registry/access-catalog/`, `registry/access-profiles/` | the access config; written only through its own `canConfigureAccess` routes |
| `.history/` (anywhere) | saved versions of edited configs |

### 4.7 The state zone — `state/` beside `config/` (SE-4 step 1: readers only)

The directories of §4.6 are to leave `config/` for `spaces/<id>/state/<dir>`, so that `config/` holds only what
people author ([Space evolution plan](plan-space-evolution.md) §4.4, D-SE5).
Readers ship one release before any writer (D-SE13). **Step 1, built 2026-11-13, is the readers only:** nothing moves,
nothing is written under `state/`, and no shipped Space or Space Template has a `state/` folder. `.history/` does not
move (D-SE6). `ReservedConfigPaths` keeps reserving the old `config/` locations.

**The rule** (`SpaceStateDirs`, one resolver every switched store reads through):

- **Read:** `state/<dir>/<file>` when that file exists, else `config/<dir>/<file>` (`SpaceStateDirs.readable`). A
  listing holds both places, the state one first, one entry per name (`SpaceStateDirs.list`). So a Space a newer
  release has moved, or half moved, still reads every record.
- **Write:** always `config/<dir>` (`SpaceStateDirs.writeDir`) — the one place the writer step changes.
- **A record that has a copy in `state/` belongs to a newer release — whether or not `config/` holds one too.**
  Writing it here would land in `config/`, where no read sees it, so this release refuses: a 409 for the signed stores (`PendingChanges.requireWritable` is checked before a
  held change is replayed, an Action Request saves before it dispatches, a Regulatory Report checks before it
  delivers), an IOException for Reconciliation and baseline state (a delete says so too), and for the agent's files
  the change stays in memory, logged at ERROR for the autonomy policy because even a kill switch set then holds only
  until the next restart. A refused delete changes nothing. Expiry skips such a record. This can only happen once a writer release has
  run against the Space (a rollback, or two releases on one Space).
- **Which roots:** only a Space's `config/` (a folder named `config` inside a Space folder — the rule
  `PathJail.spaceDirOf` and `SpaceAuditDir` use). The single-tenant default Space (`-Dassist.write.root`, no Space
  folder) reads `config/` only. **No link is followed below the Space folder** (`SpaceStateDirs.linkFree`): a `state/`, a `state/<dir>` or a file
  that is a symlink or junction is never read, backed up, exported or restored through — a link at `state/` itself
  included, which a containment check resolving both sides would let through. The Space folder itself may be a link
  (a Space kept elsewhere).

**Per store** — "Signature covers the path?" was checked in the code: a store whose MAC covered its folder could not
move. None does.

| Directory | Store (class) | Reads through the resolver | Signature covers the path? | Step 1 |
|---|---|---|---|---|
| `pending-changes/` | `PendingChanges` | `read`, `list` | **No** — HMAC-SHA256 over the record's canonical JSON; the key is found from the config root (`config.secrets/`), not from the record's folder | switched |
| `action-requests/` | `ActionRequests` | `read`, `list` | **No** — `PendingChanges.domainMac` over the content; the id inside must equal the file name (a name, not a folder) | switched |
| `regulatory-reports/` | `RegulatoryReports` | `read`, `list` | **No** — as Action Requests | switched |
| `screening-hits/` | `ScreeningHits` | `read`, `list`, the index's directory pass (both folders); the `dedupe-index.json` cache is read from `config/` once this release has written one, from `state/` until then | **No** — as Action Requests; the index is signed over its content | switched |
| `recon-state/` | `ReconStateStore` | `load` | **No** — unsigned | switched |
| `expectation-baselines/` | `BaselineProfileStore` | `read` | **No** — unsigned | switched |
| `agent/` | `DurableJsonlRing` (approvals, triage runs, feedback, runbook runs), `AutonomyPolicyStore` | load at construction | **No** — unsigned | switched; only applies when `-Dassist.write.root` is a Space's `config/` (`AgentWriteRoot`) |
| `publication-approvals/` | `PublicationApproval` | — | **No** — unsigned; bound to its approved Pending Change by a nonce | **not switched**: `PublicationApproval.forget` and `PublicationApproval.sweep` delete a record so a re-created Job needs a new approval; a fallback read those deletes cannot reach would honour a forgotten approval. It stays `config/` only — fail closed: a record only in `state/` means re-approve. The writer step must make the deletes reach both places first. |
| `audit/` (no data root) | `SpaceAuditDir`: `SnapshotStore`, `FsInvestigationLayout`, LA uploads, `EntityFactLog` | — | **No** — `EntityFactLog` chains each fact file's bytes (`prevHash`) with the sequence in the file name; snapshots are sealed by create-new; audit anchors pin the event audit chain, not files | **not switched**: these stores write where they read (an Investigation's directory, the attachments log, the upload ledger), so a read fallback would either write into `state/` or split one record across two places. It needs a third location inside `SpaceAuditDir`, chosen per record, before any writer. |

**Backup, export and git** learned `state/` in the same step:

- `BackupTask`: a backup whose `dir` is a Space's `config/` (the demo's `config_backup` Job) also archives the
  Space's `state/`, as `../state/<path>` entries; the secret rule applies there too (`action-requests`,
  `regulatory-reports` and `*.secrets` are never archived). A restore into a Space's `config/` puts those entries
  back under that Space's `state/` (`BackupTask.restoreTarget`); into any other folder they are refused as escaping.
  A backup of the whole Space folder already carried `state/`.
- `BundleExporter.exportSpace` (`GET /export`, and so promotion): `state/` files ride under the config-relative names
  they had in `config/` (`state/recon-state/x.json` → `recon-state/x.json`), a file in both places is taken from
  `state/`, only the switched directories travel (`SpaceStateDirs.DIRS`, so a later store in `state/` is never exported by
  accident), and the four signed stores stay out wherever they live. The bundle layout and the importer are unchanged.
- The root `.gitignore` ignores `/spaces/*/state` whole (no trailing slash, so a link named `state` is ignored too): all of it is
  server-written.

**Hard-coded paths** (searched 2026-11-13, repo and `tools/`): no script or tool names `config/pending-changes` or
any other zone directory; only `.gitignore` (`/spaces/*/config/audit/`, `/spaces/*/config/pending-changes/`, kept
while those locations exist) and a comment in `FsInvestigationLayout`. `tools/doc-diagrams/screening.py` labels
`screening-hits/<id>.json` without a parent folder, which stays true.

**For the writer step** (found while building step 1):

- The Pending Change store lock is `config/pending-changes/.lock`. A writer release must take the same lock (or
  both), or two Pods of different releases on one Space do not exclude each other.
- The `la-audit` Space Template keeps authored Pipelines and Schemas in `config/audit/`; a migrator must move only
  the sealed sub-folders (`snapshots/`, `la-uploads/`, `entity-facts/`), never the whole folder.
- `incident-briefs/` (written by the Action Requests module) is server-written state in `config/` that neither
  `ReservedConfigPaths.DIRS` nor plan §4.4 names.

## 5. `config.secrets/`

A sibling of `config/`, outside the config tree on purpose: no import, export, Exchange or `BackupTask` reaches
it, and `PathJail` refuses any `*.secrets` path. Files are created owner-only.

| File | Meaning |
|---|---|
| `.pending-changes.key` | the Space's HMAC key — signs Pending Changes and audit anchors (`PendingChanges.KEY_FILE`) |
| `audit-anchors.jsonl` | one signed anchor per line pinning the audit chain (daily, plus on-demand) |
| `audit-anchoring.json` | when anchoring started (`AuditAnchors.STARTED_FILE`) |

⚠ Losing the key invalidates every signature it made. Export anchors off the box (`GET /audit/anchors`) if
tamper evidence must survive a local administrator.

## 6. `data/` — inboxes and landed data

| Path | Meaning |
|---|---|
| `data/inbox/<feed>/` | the poll directory a Pipeline's `dirs.poll` names. **Consumed**: files leave it once processed. |
| `data/<store>/…` | one store per Pipeline, with the folders its `dirs:` block names (below) |
| `data/events/` | the rolling-Parquet event store (default events backend) |
| `data/ref/` | Reference files joined by Enrichment `references:` (e.g. `region_dim.csv`) |
| `data/samples/` | **committed, pristine** sample input plus `seed-inbox.ps1`/`.sh` that copy it into the inbox; the only tracked part of `data/` |
| `data/<anything else>/` | **named by a config, not by the product**: an Enrichment's `output.database` (the demo writes `data/reports/orders_daily`), a backup Job's `dir` (`data/backups`, `data/maintenance_backups`), a Dataset materialised by a Job (`data/orders_by_region`). To learn what a folder is, grep `config/` for its name. |
| `data/entity_list_<uuid>/` | the data of one Entity List |
| `data/.duckdb_tmp/` | DuckDB spill; skipped by backups and storage reports |

A Pipeline's store, as `spaces/demo/config/orders/orders_pipeline.toon` declares it:

```
dirs:
  poll:       data/inbox/orders         input files wait here
  database:   data/orders/database      the landed Hive-partitioned Parquet (year=2026/…), with .staging/
  backup:     data/orders/backup        a copy of every processed input file
  temp:       data/orders/temp          per-run scratch
  errors:     data/orders/errors        structural rejects (<file>_errors.csv)
  quarantine: data/orders/quarantine    quarantined records
  markers:    data/orders/markers       <file>.processed duplicate-check markers, .last_cleanup
  status_dir: data/orders/status        per-run batches / lineage / status CSVs, <store>_commits.log, manifests/
  log_dir:    data/orders/logs          run logs
```

Deleting a file's `.processed` marker makes the duplicate check accept that file again.

## 7. `audit/`

| Path | Meaning |
|---|---|
| `jobs_runs.csv` | the Job run journal: `run_id, job, type, trigger, start_time, end_time, status, duration_ms, message` |
| `runlog/<runId>.jsonl` | one structured log per run (`RunLogEntry`); pruned by the `runlog_prune` Job |
| `artifacts/` | Run Artifacts (`RunArtifactStore`) |
| `<pipeline>_stage2_branch_commit_<store>-<epoch>.csv` | Stage-2 branch commit logs |
| `dataset-publications.tsv` | `<dataset>\t<epoch ms>` — last publication per Dataset (`DatasetFreshnessProbe`) |
| `snapshots/`, `la-uploads/`, `entity-facts/` | sealed LA and Entity logs when the Space has a separate data root (§4.6) |

## 8. `duckdb/`

Each store is a DuckDB file by default (`SpaceRoot`, one URL per store); its backend flag can point it at
Postgres instead, and some files exist only when their flag is set. Each file has a `.wal` beside it.

| File | Store | Created |
|---|---|---|
| `jobs_report.duckdb` | Job run reporting | default |
| `provenance.duckdb` | data-plane provenance | default |
| `inspecto-status.db` | status projection | default |
| `inspecto-ops.db` | operational objects (Incidents, Cases) | default |
| `inspecto-ops-links.db` · `inspecto-ops-notes.db` · `inspecto-ops-tags.db` | correlation links · evidence and notes · tag assignments | default |
| `inspecto-alerts.db` | Alert records | `-Dalerts.backend=db`, the default on every edition |
| `inspecto-dedup-ledger.db` | cross-Consignment record dedup ledger | when used |
| `inspecto-consignment-outputs.db` | Consignment output-file registry | `-Dconsignment.outputs.backend` |
| `inspecto-acquisition.db` | acquisition (dedup) ledger | `-Dacquire.ledger.backend=db` |
| `inspecto-events.db` | event store (instead of `data/events/`) | `-Devents.backend=db` |
| `inspecto-file-stages.db` | per-file stage progression | `-Dfile.stages.backend` |
| `inspecto-delivery-receipts.db` | delivery receipts | `-Ddelivery.receipts.backend` (off by default) |
| `inspecto-run-lease.db` | cross-process run lease | `-Drun.lease.backend=db` — useful only on shared Postgres |
| `inspecto-inbox-registry.db` | fleet registry of declared inboxes | `-Dinbox.registry.backend` — opened once per pod at the **Spaces root**, not per Space |

## 9. What git keeps

From the root `.gitignore`: `space.toon`, `config/` and `data/samples/` are tracked; ignored are `data/**`
(except `samples/`), `audit/`, `duckdb/`, `config.secrets/`, `state/` (§4.7), `config/audit/`, `config/pending-changes/`,
`config/egress.toon`, `config/approvers.toon`, the legacy `<id>/flows/` folder, `_shared/`, and the whole
generated `spaces/uat/`. Never commit real or client data into `data/samples/` — synthetic fixtures only.
<!-- vocab-allow: quotes the ignored legacy directory name -->

## 10. Templates and industries

- **Space Template** — `spaces/_templates/<id>/` holds `template.toon` (`name`, `tagline`, `description`, `icon`,
  `contents[]`), a `config/` tree and a `data/` tree. `POST /spaces {id, template}` copies `config/` rewriting
  every `${SPACE}` token in `.toon` files, and copies `data/` verbatim. Example: `spaces/_templates/orders-starter/`.
- **Industry entry** — `spaces/_industries/<pack>.toon` (`name`, `tagline`, `minEdition`, `icon`,
  `useCases[]` naming `la-*` templates) groups Link Analysis showcase templates in the gallery.

## 11. Folders under the Spaces root that are not Spaces

A folder is skipped by its name or for having no `config/` (`SpaceManager.notASpaceReason`). The rule and its
reason are on [Spaces §4](architecture.md) — keep backups outside the Spaces root.

## 12. Links between files — what breaks when you edit one

The files of a Space form one graph. Nothing records the links: each one is a **name, a path or a column name
written as plain text** in another file. When you edit one end by hand (rename an id, move a file, rename a
column), the other end keeps the old text and breaks silently. The usual result is no load error at all, just a
widget with no data, a Job that never fires, or an Alert Rule that no longer matches.

### 12.1 The chain

```
input file ─► Pipeline ─────────┬─ processing.schema_file ─► Schema ─► mapped column names ─► (every column ref below)
 (dirs.poll)   *_pipeline.toon   ├─ use: <type>/<id> ───────► registry grammar / schema / transform / sink
               id = name:        ├─ Collector connection ───► Connection
               store = dirs.*    └─ dirs.database ──────────► data/<store>/database ◄── Enrichment input.database (path)
                                                                  ▲
   Pipeline id ◄── Enrichment triggers.on_pipeline · references.<n>.ref
               ◄── Job on_pipeline · pipeline_config (a file path)
               ◄── Expectation / Decision Rule target (targetType: pipeline)
               ◄── Alert Rule onPipeline
   store name  ◄── Dataset physicalRef (first path segment) · sourceName
   Dataset id  ◄── Widget datasetId ◄── Dashboard tiles[].widgetId ◄── nav-menus.toon binding.componentId
               ◄── Alert Rule dataset · Reconciliation left/right/thirdDataset · Job dataset / target
               ◄── Link Analysis / Geo Map view query.projection.datasetId · Dataset-consumer Pipeline
   Query id    ◄── Widget queryId
   signal name ◄── Job on_signal + when  ("$signal.dataset == orders_summary")
```

### 12.2 Every link and what protects it

The table is **generated from the link census** (`LinkCensus.LINKS`, the one list every check reads) by
`node tools/render-space-links.mjs`; CI runs it with `--check`, so a link added to the census and not to this page
fails the build. Never edit the rows by hand. Key paths read against the file's document root (a Job's inside its
`job:` section): `a.b` nested maps, `a[]` each list element, `*` each value of a map, `**` the node and every node
below it.

**On delete** / **On rename** are the census policy for when the target goes away or changes name (`refuse` /
`warn`, `rewrite` / `report`). **Checked by today**: **load** = checked when the file loads · **run** = the
Pipeline's run resolves it and fails without it (its Collector cannot be built, every cycle) · **delete 409** =
deleting the target is refused (`ComponentRoutes`, `ConnectionRoutes`) · **rename** = `POST /pipelines/{name}/rename`
rewrites the link · **impact** = listed by `GET /config/pipeline/{name}/impact` (or the column impact read) and before a
Pipeline delete · **validate** = reported by the `metadata_validate` maintenance task · **import** = a bundle import
refuses to add the break · **none** = no check reads it yet. Independently of this column, every link the census
resolves is judged by the LC-3 delete and save checks below and reported by the Space lock
(`GET /spaces/{id}/integrity`). A Pipeline's own links are judged as their loaders resolve them, and only where the
loader reads them (SE-1b, `PipelineLinks`): `collector.connection` for a remote connector other than `dataset`,
against the profiles loaded from `*_connection.toon`; `collector.dataset` with `connector: dataset`; and
`processing.schema_file` when the file loads it (no plugin, Grammar or `schemas[]`). `**.use` is not judged: no
loader reads a `use` key in a `*_pipeline.toon`. A column link to SQL the parser cannot read or attribute is reported as not analysed.

<!-- link-census:begin (generated by tools/render-space-links.mjs from LinkCensus.LINKS; do not edit) -->
| From | Key | To | Value | On delete | On rename | Checked by today |
|---|---|---|---|---|---|---|
| Pipeline | `processing.schema_file` | `*_schema.toon` file | path | refuse | rewrite | load |
| Pipeline | `**.use` | registry component | id | refuse | rewrite | delete 409 |
| Pipeline | `collector.connection` | Connection | id | refuse | rewrite | run, delete 409 |
| Pipeline | `collector.dataset` | Dataset | id | refuse | rewrite | run |
| Enrichment | `triggers.on_pipeline` | Pipeline | id | refuse | rewrite | rename, impact, validate |
| Enrichment | `references.*.ref` | Pipeline | id | refuse | rewrite | rename, impact, validate |
| Enrichment | `input.database` | store | path | warn | report | **none** |
| Enrichment | `references.*.path` | file | path | warn | report | **none** |
| Enrichment | `transform` | column | SQL column | warn | report | impact, validate |
| Job | `on_pipeline` | Pipeline | id, or a comma list of ids | refuse | rewrite | rename, impact, validate |
| Job | `template` | `*_job_template.toon` | id | refuse | report | load |
| Job | `pipeline_config` | `*_pipeline.toon` file | path | refuse | rewrite | **none** |
| Job | `dataset` | Dataset | id | refuse | rewrite | **none** |
| Job | `target` | Dataset | id | refuse | rewrite | **none** |
| Job | `dir` | directory | path | warn | report | **none** |
| Job | `on_signal` | signal | text | warn | report | **none** |
| Job | `when` | signal | text | warn | report | **none** |
| Expectation | `target` | Pipeline | data path; its first segment names the store | refuse | rewrite | rename, impact, validate |
| Expectation | `column` | column | column name | warn | report | impact, validate |
| Expectation | `refDataset` | store | store name | warn | report | **none** |
| Expectation | `refColumn` | column | column name | warn | report | impact, validate |
| Expectation | `when.**.field` | column | column name | warn | report | impact, validate |
| Decision Rule | `target` | Pipeline | id | refuse | rewrite | rename, impact, validate |
| Decision Rule | `when.**.field` | column | column name | warn | report | impact, validate |
| Dataset | `sourceName` | store | store name | refuse | rewrite | rename, impact |
| Dataset | `physicalRef` | store | data path; its first segment names the store | refuse | rewrite | rename, impact, validate |
| Dataset | `sql` | store | SQL relation | refuse | rewrite | rename |
| Dataset | `sql` | column | SQL column | warn | report | impact, validate |
| Dataset | `dateField` | column | column name | warn | report | impact, validate |
| Dataset | `calculated[].expr` | column | SQL column | warn | report | impact, validate |
| Alert Rule | `onPipeline` | Pipeline | id | refuse | rewrite | rename, impact |
| Alert Rule | `dataset` | Dataset | id | refuse | rewrite | impact |
| Widget | `datasetId` | Dataset | id | refuse | rewrite | impact, validate, import |
| Widget | `queryId` | Query | id | refuse | rewrite | validate, import |
| Widget | `controls.*.field` | column | column name | warn | report | impact, validate |
| Widget | `controls.*[].field` | column | column name | warn | report | impact, validate |
| Dashboard | `tiles[].widgetId` | Widget | id | refuse | rewrite | impact, validate, import |
| Link Analysis view | `query.projection.datasetId` | Dataset | id | refuse | rewrite | validate, import |
| Geo Map view | `query.projection.datasetId` | Dataset | id | refuse | rewrite | validate, import |
| Reconciliation | `datasets[]` | Dataset | id | refuse | rewrite | validate, import |
| Reconciliation | `leftDataset` | Dataset | id | refuse | rewrite | validate, import |
| Reconciliation | `rightDataset` | Dataset | id | refuse | rewrite | validate, import |
| Reconciliation | `thirdDataset` | Dataset | id | refuse | rewrite | validate, import |
| Reconciliation | `keyColumns[]` | column | column name | warn | report | impact, validate |
| Reconciliation | `compareColumns[].column` | column | column name | warn | report | impact, validate |
| Reconciliation | `impact.column` | column | column name | warn | report | impact, validate |
| Query | `text` | column | SQL column | warn | report | impact, validate |
| `nav-menus.toon` | `**.binding.componentId` | registry component | id | warn | rewrite | **none** |
<!-- link-census:end -->

Two things this table means in practice:

- **Only a Pipeline's `use:` reference, a Pipeline's dependents and a Connection refuse a delete today.** Deleting a
  Dataset, Widget or Query that another config names goes through, but since `SPACE-LINK-CENSUS-1` LC-3 the
  response lists what it broke (`brokenLinks`) and an AUDIT event (`config.delete.links-broken`) records it. That is
  the shadow of the census delete refusal (`-Dlinks.delete=shadow|refuse|off`, default `shadow`, every edition):
  in `refuse` the delete answers 409 with `dependents` (kind, name, key) and `?force=true` deletes as before. It
  covers every link the census judges (the rows naming a Dataset, Query, Widget or Pipeline id); a Pipeline delete
  keeps its own refusal in every mode.
- **A save that adds a broken link warns.** An active config (not `active: false` / `enabled: false`) that adds
  a link the census judges as dangling saves with a `WARN_LINK_DANGLING` finding (`linkWarnings` on a component
  save). In `-Dlinks.save=refuse` (Standard and Advanced only; Basic stays in shadow, D-LC5) it is refused 422
  `ERR_LINK_DANGLING` naming the key and the missing target; a draft always saves, and activating one is judged
  on every dangling link it holds. The kind-specific writers (`/alerts/rules`, `/decision-rules`,
  `/expectations`, every `/jobs` write including `POST /jobs/{name}/enable`) run the same check; their responses
  carry no findings, so in shadow they only log it.
- **Column names are reported, not protected.** Renaming a mapped column in a Schema still breaks every widget,
  rule, Expectation and SQL text that uses it; nothing refuses the save. The column impact read lists those
  consumers before the rename, and `metadata_validate` reports each one that dangles after it (`LinkGraph`
  column links, `SPACE-LINK-CENSUS-1` LC-5). A link to a store whose columns config cannot tell (a Job-written
  store with no declared `columns`, a mapping from `mapping.rules` or a `_mapping.csv`) is not judged.

### 12.3 What the rename and impact scans read

Both scans read the link census (`LinkCensus`, resolved by `LinkGraph.of`), so they read the same keys in the
same files. The delete-impact scan (`PipelineDependents`) lists every link that names the Pipeline; the Pipeline
rename (`PipelineRenameRoutes`, `POST /pipelines/{name}/rename` and its resume) rewrites the links
`LinkCensus.renamedWithPipeline` names — `onRename` REWRITE and naming the Pipeline or its store — and the
maker-checker check that refuses a rename touching a governed kind selects through the same list. A Job's
`pipeline_config` path is not rewritten: the rename moves the file, and rewriting paths is not part of it.

The file set is `LinkGraph.configFiles`: every `*_enrich.toon` and `*_job.toon` under `config/` **at any depth**,
as the loader does, minus `.history/` copies and the server-written state directories (§4.6). A Job's
`on_pipeline` is read inside its `job:` section (the form `JobConfig` loads), or at the top level of a document
without one; a comma list is read member by member (`JobConfig.onPipelines`), and a rename replaces only the
member that names the Pipeline. A Pipeline bundle export finds its companion Enrichments in the same file set.

One unreadable folder does not empty the answer: it is logged and skipped, and the rest is still listed.

*Fixed 2026-11-11.* Before that, both scans listed only `config/*_enrich.toon` and `config/jobs/*_job.toon`, and
read `on_pipeline` at the top level only — so a rename left `orders/orders_daily_enrich.toon` and every shipped
Job (all of them nest the key under `job:`) pointing at the old id, and a delete did not list them.

*Census, 2026-11-12 (`SPACE-LINK-CENSUS-1` LC-2).* The rename now rewrites an Enrichment's by-name
`references.<n>.ref` and one member of a comma-list `on_pipeline`, which impact also lists; a bundle export finds
companions at any depth; and `metadata_validate` / the bundle import judge a Reconciliation's `thirdDataset`.
`metadata_validate` reads every file-held pipeline link (Enrichment and Job, any folder depth) from the link
census, never a walk of its own; `registry/` and hidden folders are not read as config.

### 12.4 Duplicates — one definition per thing

A link is only safe when its target is unique. What the code does today:

| Duplicate | Today |
|---|---|
| two registry components of one kind with identical content | reported by `metadata_validate` (`ComponentIntegrity.duplicates`) |
| a registry id | cannot repeat: the file name is the id |
| a Pipeline id in two files | refused, at boot and at runtime: "pipeline id … is already registered from …" (`CollectorService`) |
| a Job or Enrichment `name` in two files of one Space | no refusal found in the `ServiceBootstrap` loaders — not traced further, treat as unprotected |
| a Connection both as `*_connection.toon` and under `registry/connections/` | not traced — treat as unprotected |
| the same fact restated in two docs | this page links instead (see the banner); the guard keeps its tables equal to the code |

Closing the unchecked rows is part of `SPACE-LINK-CENSUS-1` (§12.6).

### 12.5 Changing a Space safely

1. **Change it through the UI or API rather than in a text editor** wherever a route exists: Pipeline rename
   (`POST /pipelines/{name}/rename`), config delete (`DELETE /config/{type}/{name}`, which shows the impact and
   needs `?force=true` to go ahead anyway) and component saves (they keep `.history/`).
2. **Before you rename or remove anything, list who points at it:** `GET /config/pipeline/{name}/impact` or
   `GET /pipelines/{name}/related`. Then grep the whole `config/` tree for the id, since the rows marked
   **none** in §12.2 are covered by nothing. Grep for column names too.
3. **Run `metadata_validate` after a hand edit**, or schedule it. It is read-only, and an Alert Rule can
   subscribe to its `maintenance.metadata.findings` signal:

   ```
   job:
     name: metadata_validate
     type: maintenance
     task: metadata_validate
     cron: "0 6 * * *"
   ```

4. **Dry-run a changed Pipeline before activating it**, and leave `active: false` until it passes.
   Read the `brokenLinks` of a delete response and the `WARN_LINK_DANGLING` findings of a save response: they
   are what the census refusal modes (§12.2) would refuse.
5. **Never hand-edit server-written state** (§4.6, §5, `audit/`): signatures and hash chains break.

### 12.6 Where enforcement is going

Today each check keeps its own copy of the link keys: the rename, the impact scan, `metadata_validate`, the
import pre-check and two delete guards (`PipelineDependents` says so itself: "if a binding is added there, add
it here"). That copying is why §12.2 has rows marked **none** and why the scans drifted from the loader. The
planned fix is one declared **link census** that every check reads, refusal of a save or delete that would
break a link, a column-level check, duplicate detection, and a report of files changed outside the server:
[Space link census plan](../../../../superpower/2026-11-11-space-link-census-plan.md), board row
`SPACE-LINK-CENSUS-1`. Built so far: the census and the checks that read it (LC-1, LC-2), the delete and
save refusals in shadow mode (LC-3, §12.2), column links (LC-5) and this page's link table, generated from the
census (LC-7).

## 13. Gotchas

- **Config edits need a reload** — edit the file and restart, or re-save from the UI; the same files back the
  UI editors.
- **`*_queue.toon` and `*_escalation.toon` are not read** since `RETIRE-HALVES-1` (2026-09-14); governance lives
  in `registry/workflows/`, `registry/sla-policies/` and `registry/escalation-rules/`.

## References

- `inspecto/src/main/java/com/gamma/service/SpaceRoot.java` — every location and store URL
- `inspecto/src/main/java/com/gamma/service/ReservedConfigPaths.java` — fixed-name files and server-written dirs
- `inspecto/src/main/java/com/gamma/service/ImportPaths.java` — which suffixes and dirs an import may write
- `inspecto/src/main/java/com/gamma/service/ServiceBootstrap.java` — the suffix loaders
- `platform/inspecto-engine/src/main/java/com/gamma/pipeline/ComponentRegistry.java` — registry directory → kind
- `spaces/demo/config/README.md` — a working sample of almost every kind
- [Spaces](architecture.md) · [Configuration reference](admin-guide-configuration-reference.md) ·
  [TOON configuration](spec-toon-config.md)
