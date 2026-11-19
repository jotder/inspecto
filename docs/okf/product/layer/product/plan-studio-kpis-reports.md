---
type: Capability
title: Studio (BI + INV) — capability spec — Specification — KPIs, Reports and scheduled delivery onward
description: Part 2 of 3 of Studio (BI + INV) — capability spec: 
status: current
---

# Studio (BI + INV) — capability spec — Specification — KPIs, Reports and scheduled delivery onward

Part 2 of 3 of [Studio (BI + INV) — capability spec](plan-studio.md) · previous: [Purpose, requirements and specification](plan-studio-scope-specification.md) · next: [Decisions, not built, refused and verification](plan-studio-decisions-verification.md).

## 3. Specification (continued)

### 3.6 KPIs, Reports and scheduled delivery

`/kpi-reports` is the **operational** gallery: **KPIs** (single-number Measures with a target or
threshold, rendered mini → standard → max) and **Reports** (run health, freshness, SLA). It reads
Dashboards and adds scheduled export.

**A KPI target is a business acceptance criterion** (product sign-off 2026-07-22): it is authored by
Business **on the Requirement**, not by the Builder inside the component that implements it — a
`kind: 'kpi'` Requirement carries `target` with `comparator`/`unit`, and the Widget that satisfies it
renders against that agreed bar.

**A KPI is a server-side definition** (`ASSURE-KPI-DEFINITIONS-1`, WS-20, 2026-09-27): the `kpi` registry
component — a Measure (`dataset` + `measure` shorthand + the `timeField` a period is cut on), `target`, good
`direction` (`up` / `down` / `band`), RAG `bands`, period `grain` (`day`…`year` — wider than
`MeasureCompiler.GRAINS`, which are grouping grains), `comparison` (`previous` / `last-year` / `none`),
`unit` / `format`, an optional IANA `timezone`, and the R3 `owner`. Authored through `/components/kpi`, so it gets history and restore and
reaches the maker-checker hold; validated fail closed by `KpiDefinition.fromMap`
(`platform/inspecto-engine/src/main/java/com/gamma/query/KpiDefinition.java`) on every door, and at the authoring doors
the Dataset must exist AND be readable by the author, with the measure field and `timeField` in its Schema, and the `timeField` a `DATE`, `TIMESTAMP` or
`TIMESTAMPTZ` column (`KpiRoutes.requireMeasure`). `GET /kpis/{id}/value?asOf=` (`KpiEvaluator`) evaluates the period **to date**
against the SAME elapsed length of the comparison period (a month read on the 13th compares 13 days with 13
days), through `MeasureCompiler` + `SqlGuard`, and answers value, comparison, delta, Δ%, the `timezone`, `band`
(`GREEN`/`AMBER`/`RED`, the `statusTone` words) and `tone`. 🔴 **A KPI evaluates in ONE explicit zone** — its
`timezone`, else the **Space default timezone** (`GET|PUT /settings/timezone`, `{timezone}` stored as `timezone.toon` in the Space config root — `TimezoneSettings`; the same region-name rule as a KPI, 422 otherwise; `canAuthorWorkbench`; since 2026-09-28), else **UTC**: the window is local midnight in
that zone, written as typed `TIMESTAMPTZ` literals, and the DuckDB session `TimeZone` is SET to the same zone for
the query (`QueryExecutor.run(req, policy, zone)`), because DuckDB otherwise follows the HOST. The default `asOf` is
today in that zone; a future `asOf`, one before year 1, or an Instant the calendar cannot place is a 400. Offset
forms (`+05:30`) are refused — `ZoneOffset.UTC`'s id `Z` is unknown to DuckDB, so UTC is the region id `UTC`. ⚠ A Dataset the caller cannot read answers **404
exactly as an absent one** — a KPI is never a way round Dataset sharing. The KPI tile binds with
`options.kpi.kpiId` and then shows ONLY the server's numbers — "No data" for a null value, "KPI unavailable" on a
read error; the hand-set Widget inputs apply only with no `kpiId`. A **new** KPI Widget's "Compare with (prior period)" starts as none (`—`): Show-Me's `autoAssignChannels` (`show-me.ts`) never guesses the optional `compare` channel, so no delta badge shows until a prior period is chosen; a saved compare is untouched (`KPI-DEFAULT-COMPARE-1`, 2026-10-10 — it used to take the second measure and fake a "Down 75% vs prior period"). A KPI Widget with a `kpiId` and **no `datasetId`**
(every shipped template KPI tile) is *KPI-bound* (`isKpiBound`, `widget-types.ts`): the dashboard editor, the Menu
viewer and `WidgetHostComponent` render it without a Dataset, run no query, and apply the as-of date but not the
cross-filter or drill. Before LIVEFIX2 (2026-10-09) the editor drew "Dataset for “” not found." for each one. A `band` KPI's delta and target lines drop the
up/down wording and take their tone from the server's band. A delivered `kpi` Requirement creates one
only by the explicit `POST /requirements/{id}/kpi` (`canAuthorWorkbench`), which takes its
`target`/`unit`/`title` and reads the direction off its `comparator`. In the UI that is the **Create KPI** button
on the Requirement detail (`requirement-decision.dialog`), shown for a delivered `kpi` Requirement with no `kpi`
stamp yet and gated on `lens.canAuthorWorkbench()` (the server's capability); it asks ONLY the Dataset, Measure,
date column and period (`requirement-kpi.dialog`, which makes the call and stays open on a 422/409 refusal) —
the KPI id defaults to the Requirement's. ⚠ A 202 hold does NOT stamp the Requirement until approval, so the pane
reads `GET /pending-changes?status=pending` and shows "waiting for approval" in place of the button for a held
`kpi` create of that id (else a second click 409s). A KPI-bound tile evaluates at its Dashboard's `asOf` day
(`DashboardViewStore.kpiAsOf` → tile → widget host → `VizRenderComponent.kpiAsOf` → `KpiComponent.asOf`, one read
in flight — a newer day cancels the older); only a real day not after today is sent, and a blank or invalid
`asOf` sends none, so the server's today in the KPI's zone applies — never the browser's today, which a zone ahead
of the server's could make a "future" 400. ⚠ Distinct from the semantic model's
descriptive **KPI catalog** (`kpis:` / `GET /catalog/kpis`), which is text for the assistant and runs nothing.
A Space Template may carry `kpi` definitions (`config/registry/kpis/`, 2026-09-28): on `POST /spaces` with a
`template` each meets the same gate — `canAuthorWorkbench` plus `requireMeasure` against the new Space's own
Datasets, copied first — and the first refusal (403 / 422) creates no Space (`KpiRoutes.requireTemplateKpis`;
`okf/product/module/config/plan-spaces.md` §3.5). ⚠ The Schema read refuses a KPI over a Dataset its template's pipeline
has not filled yet, so no shipped template carries a KPI pack. A bundle-imported KPI meets the save gate too.

**A schedule is a Job, not a new entity** (C6, 2026-07-04): `type: 'report'` with
`params: {reportKind, dashboardId, format, recipients}`. ⚠ Dispatch keys on `params.dashboardId` being
present, **not** on `type === 'report'`, because that type predates C6 and covers other report jobs.

Server side, `ReportJob` (`platform/inspecto-engine/src/main/java/com/gamma/job/ReportJob.java`) takes `out_dir`
and `format`, path-jails the directory, and writes `<job>_<timestamp>.<ext>`:

| Format | Requires | Renderer |
|---|---|---|
| `json` | nothing — the rollup default | pretty-printed report |
| `csv` | `scope: dataset` | row serialisation |
| `png` | `scope: dataset` | `TablePngRenderer`, JDK-native Graphics2D, a 50-row table snapshot |
| `pdf` | `scope: dataset` | `PdfRenderer` — the PNG wrapped in a hand-written PDF (one image XObject per page, no text layer), because no PDF library is on the classpath and the build is offline. Since 2026-11-16 (`REPORT-PDF-CHARTS-1`) `widgets:` adds one chart page per bar / line / KPI Widget before the table page — see [`user-guide-kpi-reports.md`](../../module/analytics/user-guide-kpi-reports.md) |

The artifact is registered on the run and a `REPORT_READY` event is emitted carrying `job`, `scope` and
`path`. ⚠ **`json` is missing from both user-facing lists** — `GLOSSARY.md` and `USER_GUIDE.md`
each say "CSV / PDF / PNG", and `json` is the *default*. ⚠ **SMTP delivers that path, not an attachment** — a stated channel limitation, confirmed in the
job's own contract.

#### Publishing to Postgres for BI tools — `publish.postgres` (ASSURE-BI-PUBLICATION-1, built 2026-09-29, security-fixed the same day)

A **Job**, not a Step Processor (the hold on new Step Processors stands). The code is `PostgresPublishJobType`,
`PostgresPublishSql`, `PublishPinnedSocketFactory`, `PublishSslFactory` and `PublicationDestinations` in
`inspecto-engine` (`com.gamma.job`). It is registered in `JobService` beside `objectstore.export`.

Parameters:
- `connection`: a `connector: db` Connection whose `options.jdbc_url` is `jdbc:postgresql://host[:port]/database`.
  The password is a `${…}` reference resolved by `SecretResolver`, keystore included.
- `datasets`: each lands in a table named after its id, lower-cased, with other characters → `_`.
- `schema`: created if absent.
- `mode`: `full-refresh` (the default) or `partition-incremental` with `partition_column`.
- `columns`: an optional allowlist of `column` or `dataset.column`.
- `include_sensitive`: `dataset.column`, or `dataset.*` for a whole view or virtual Dataset.
- `timeout_seconds` (300) and `retries` (2).

⚠ **Every** parameter is read from the **saved** Job only. A trigger arg, a Signal binding or a run-time override
of any of them (`mode`, `partition_column`, `timeout_seconds` and `retries` included) refuses the run, because those
are the values four-eyes approved (below).

**Writing**
- **Full refresh** fills `<table>__inspecto_stage`, drops the old table and renames the stage. Every Dataset of
  the run shares **one** transaction, so any failure, including one after the rename, leaves every old table and
  its rows intact. Postgres DDL is transactional; that is what makes the swap atomic.
- **Partition-incremental** fingerprints each partition in DuckDB: `count(*)` plus a HUGEINT sum of
  `hash(<published columns>)`. It compares them with `<schema>._inspecto_publication (tbl, part, fingerprint,
  run_id)`, which is written in the same transaction as the data, then deletes and re-inserts only the changed
  partitions and deletes the vanished ones.
  - The partition column must be text, integer, boolean or date: types whose text form is the same in DuckDB and
    Postgres.
  - A target whose column **names** differ from the Dataset's refuses the run ("run a full-refresh first").
  - ⚠ A column **type** change is not detected as drift. It only changes every fingerprint, so the next run
    re-sends every partition into the old column types.
- **Identifiers**: schema, table and column names must match `[A-Za-z_][A-Za-z0-9_]{0,39}` and are always
  double-quoted. A Dataset column that does not match refuses the run; it is not quoted around.
- **Comments**: single-quoted literals with `'` doubled, under `SET LOCAL standard_conforming_strings = on`. They
  are the Catalog descriptions: the Dataset's `description` → `COMMENT ON TABLE`, and each
  `columns[].description` → `COMMENT ON COLUMN`.

**Who a run acts as**
- `JobAuthority.publishAuthority`, installed by `ControlApi`, re-resolves the last editor's recorded roles
  (`updatedByRoles`) against the role table as it is at run time, with Access Profile denies applied.
- A Dataset whose sharing envelope would hide it from that author refuses the run. This is the `ComponentAccess`
  rule, and ⚠ that rule means a Dataset with **no `shares` key is viewable by everyone**: only a Dataset that
  declares `shares` (even an empty list) is restricted to its owner, its shares and `canConfigureAccess` holders.
- ⚠ **A Job saved by an administrator runs with `canAdminister`** (its last editor's roles) until that role loses
  it or someone else re-saves the Job. The four-eyes hold below is what stops an administrator from silently
  widening a publication.
- With no Authenticator the author is `OPEN`: envelopes are not enforced, as with the read routes.

**Data egress: sensitive columns are refused by default (decision, 2026-09-29)**
- A column classified `MSISDN`, `IMSI`, `ACCOUNT` or `PII` (`EvidenceMasker.SENSITIVE`) is **left out** when no
  `columns` allowlist is given, and an allowlist that names one refuses the run.
- It is published only when `include_sensitive` names it as `dataset.column` **and** the author holds
  `canAdminister` at run time.
- The column name and the class match case-insensitively (`MSISDN` = `msisdn`, class ` pii ` = `PII`).
- **Static lineage:**
  - A same-named column inherits the classification that any other Dataset declares over the same store
    (`physicalRef`, a virtual Dataset's `sourceName`, or a view's store and its `source_store` lineage).
  - A **view or virtual** Dataset over a store with classified columns is refused outright unless
    `include_sensitive` names `<dataset>.*` and the author holds `canAdminister`, because a rename or computation
    (`msisdn AS m`) cannot be traced statically.
- **Pipeline schema lineage (operator 2026-10-04, `EvidenceMasker.schemaClassification`, the ONE resolver).** A Dataset's
  `physicalRef` head (or `sourceName`) names a Stream or pipeline; `PipelineSchemas.forStore` (the one resolver,
  also what `MetadataGraphBuilder` lays its schema nodes out from) finds that pipeline's `*_pipeline.toon` and its
  schemas.
  - A schema's `raw.fields[].classification` (sensitive classes only) follows its `mapping.fields[]`: a `keep` or
    rename of a classified raw column, and any rule whose `from` or `args` text names one (`md5(MSISDN)`,
    `substr(msisdn,1,3)`, a concatenation), classify the target column. A legacy `mapping.rules[]` (from
    `processing.mapping_file` or a sibling `*_mapping.csv`) is converted first, as `DataTransformer.recordFields`
    converts it at ingest (`LINEAGE-SCHEMA-CLASS-LOST-1`). A stored column named like a classified
    raw column is classified too.
  - **Strictest wins (operator 2026-10-04).** When several classified inputs feed one column (`concat(msisdn,
    imsi)`), or lineage and the Dataset's own label disagree, the column takes the most restrictive class. The order
    lives in ONE place, `com.gamma.util.ColumnClassification`: a class the consumer masks outranks one it does not
    (publication and evidence: `SENSITIVE` = MSISDN, IMSI, ACCOUNT, PII; Link Analysis: a class a masked Entity
    Type claims), then `MSISDN > IMSI > ACCOUNT > PII`, then by name. A Dataset labelling a lineage-MSISDN column
    `INTERNAL` no longer publishes it. The origin rule (`physicalRef` + `sourceName`) is `EvidenceMasker.datasetStores`,
    shared by publication, evidence and Link Analysis (`EngineDatasetProvider.schemaClassification`).
  - **Fails closed** to *classified-unknown* when a pipeline that may be the store's cannot be loaded (any
    `*_pipeline.toon` that fails to load, unless its decoded `id` / `name` / `stream` provably name another store; the
    file name is never consulted, `LINEAGE-SCHEMA-CLASS-LOST-1`), its mapping
    cannot be read, or it has a step that rewrites the columns (`summarize`, `sql`, `lookup`, `join`, `route`)
    while a raw column is classified. The Dataset is then refused unless `include_sensitive` names `<dataset>.*`
    and the author holds `canAdminister`. The inherited set is part of the four-eyes approval fingerprint.
  - **Risk Score evidence masking uses the same lineage (2026-10-04).** `EvidenceMasker.sensitiveColumns` adds what
    `EvidenceMasker.schemaClassification` (the one resolver, moved there from `PostgresPublishJobType`) classifies
    behind a factor Dataset's `physicalRef`/`sourceName`, so a renamed or hashed MSISDN is stored as a token. Where
    that lineage cannot be traced the masker **fails closed: every evidence column of that Dataset is masked**.
    **Sibling-Dataset inheritance and view lineage apply to evidence too (2026-10-06):** both consumers call the one
    resolver `EvidenceMasker.lineageClassification` (moved from `PostgresPublishJobType`), so a same-named column
    another Dataset over the same store classifies is masked, and a view or virtual Dataset over a store with
    classified columns masks **every** evidence column (the evidence analogue of the publisher's `<dataset>.*` refusal).
  - **Aggregates and Job outputs do not inherit** (decision): a store no pipeline claims (a Job `output_store`
    such as `rollup`, an entity-list sidecar) contributes no classification.
- ⚠ **Residual:** classification is only as good as the registry and the pipeline schemas.
  - A store no pipeline claims and no Dataset classifies publishes unmasked.
  - **Link Analysis `EntityMasking` consults the same lineage (2026-10-04)**: a bound source/target column
    classified only in the pipeline schema masks every id when a masked Entity Type claims its class; untraceable
    lineage masks every id.
  - ⚠ The Catalog/rename/bundle origin-rule walks (not classification consumers) are still mirrored in four other places (`MetadataGraphBuilder.originNode`,
    `PipelineDependents.datasets`, `DataSourceBundleResolver.datasetReadsStore`,
    `PipelineRenameRoutes.names`): change the origin rule in all of them.
  - **Decided 2026-10-04 (operator): KEEP** for Job-output and sidecar stores (P3 `ASSURE-CLASSIFICATION-PROPAGATION-1`): no new refusal, no inheritance; they publish unmasked unless some Dataset classifies the store. Accepted residual; revisit when a customer publishes such a store.
  - ⚠ **Known limit (operator, 2026-10-06; row closed):** Job-output and sidecar stores publish UNMASKED unless a Dataset classifies them - classification does not flow into a store no pipeline claims.

**Destination and TLS**
- **Destination allowlist (operator 2026-09-29).** A per-Space `publication-destinations.toon` (`hosts: [...]`)
  lists the exact hosts a publication may write to.
  - It is EMPTY by default, so a new Space publishes nowhere.
  - `PUT /settings/publication-destinations` is `canAdminister`, validated and audited
    (`publication-destinations.changed`), and the file is reserved from every import.
  - ⚠ Passing the **egress policy** (`EgressPolicy.checkHost`/`resolve` with the Space Egress Allowlist, the one
    Action Requests use) is necessary but NOT sufficient: the egress policy only rules out address classes, and
    the destination must also be on this list.
- **Pinned dial, verified TLS.** The JDBC URL keeps the **authored** host, and `PublishPinnedSocketFactory`
  connects to the address the egress check resolved.
  - `PublishSslFactory` does TLS for the authored host (SNI + `HTTPS` endpoint identification), and pgjdbc's
    `verify-full` hostname check runs on top.
  - Both factories are set by the Job as driver properties; a `socketFactory`, `sslfactory`, `service` or any other
    URL parameter outside `sslmode`, `sslrootcert` and `ApplicationName` refuses the run. Only one host is
    accepted, and a tunnel or proxy on the Connection refuses the run.
- `sslmode` **defaults to `verify-full`**. `disable`, `allow`, `prefer`, `require` and `verify-ca` are refused
  unless the Connection sets `insecure_tls: true`, and that Connection content was part of an approval (below).
  Approving a publication whose Connection sets `insecure_tls` needs `canAdminister`, so the flag never takes
  effect on anyone else's say-so. Every such run is audited (`publish.postgres.insecure-tls`).
- `sslrootcert` must be a secret reference (`${KEYSTORE:…}`, `${ENV:…}`, …), resolved in-process into a trust
  store. A file path is refused, and the JDK roots are used when it is unset.
- Verified against a TLS-enabled local Postgres with a CA-signed certificate: the authored host passes
  `verify-full`, and a host the certificate does not name fails.
- **No ambient credentials.**
  - The `password` property is always set (empty when the Connection has none), so pgjdbc never reads
    `~/.pgpass` / `PGPASSFILE`.
  - No `service` parameter is accepted, so `pg_service.conf` is never read.
  - Our own `sslfactory` means `LibPQFactory` never reads `~/.postgresql/root.crt` or a client key.
  - `gssEncMode=disable` and `jaasLogin=false`.

**Approval: a content fingerprint (decision 2026-09-29)**
- **One mechanism (`APPROVAL-FINGERPRINT-UNIFY-1`, closed 2026-10-01).** `ApprovalFingerprint`
  (`platform/inspecto-engine/src/main/java/com/gamma/job/ApprovalFingerprint.java`) owns what `PublicationApproval` and `AttachApprovals` used to
  duplicate: the key-sorted SHA-256 (`hash`), the nonce and the common record fields (`newNonce`, `baseRecord`), and
  the fail-closed verifier `Gate` (no verifier installed, a `false`, or an exception all mean not honoured). A
  data-egress Job type supplies ONLY what to hash and where its record lives; the next one uses the same three calls
  and its own `Verifier`. Storage stays per Job type (`publication-approvals/<job>.json` versus one
  `attach-approvals.json`), as does the control plane's Pending Change check
  (`PendingChangeRoutes.verifyPublicationApproval` / `verifyAttachApproval`, unchanged). Pinned by
  `ApprovalFingerprintTest` plus the two adversarial suites (`PostgresPublishJobTest`, `ControlApiPendingChangesTest`).
  `ApprovalGoldenVectorTest` pins the canonical form to fingerprints recorded from the pre-unification code.
  🔴 **Still copied per type, not shared:** the store code (`approval` / `recordOf` / `record`, the atomic write and
  the delete revocation) and the `approved(fingerprint)` filter chain. A third egress Job type copies those until a
  shared `Store` helper exists; that helper is a possible follow-up, deliberately not built. `Gate` screens only "no
  verifier" and "no record": a blank nonce or pending change still reaches the type's verifier, which decides.
- Four-eyes approval pins a SHA-256 **content fingerprint** (`PublicationApproval`), not names. It covers:
  - `job`: every key of the Job except its schedule (`cron`, `on_pipeline`, `on_signal`, `when`, `catch_up`,
    `enabled`) and the server-stamped author keys;
  - `connection`: the resolved Connection's connector, host, port, database, user, base path, every option
    (`jdbc_url`, so `sslmode` and `sslrootcert`, and `insecure_tls`), tunnel/proxy, and the password's IDENTITY
    (the `${…}` reference itself, or a SHA-256 of a literal; never the secret);
  - `dataset:<id>`: each Dataset's relation keys (`physicalRef`, `view`, `sql`, `sourceName`, `calculated`), its
    columns with their classification, its sharing envelope, and a view's stored definition.
- **Hold.** `PendingChanges.hold` holds every write of a `publish.postgres` Job whose proposed fingerprint differs
  from the approved one, whatever `approval.toon` says. That covers a create, any parameter change through
  `/jobs`, `/config/write` or `/config/patch`, and re-saving an unchanged Job whose Connection or Dataset changed
  since its approval, which is how a publication is re-approved. The approver needs `canApproveChanges`, or
  `canAdminister` when the Connection sets `insecure_tls`. A schedule change or a delete is not held.
- **Approve.** The Pending Change carries the fingerprint it was proposed with. Approval refuses (409) if the
  Connection or a Dataset moved since then, and otherwise records it in `publication-approvals/<job>.json`. No Job
  writer touches that directory, and every import is refused it.
- **Run.** Every run recomputes the fingerprint and refuses on any difference with *"the approved publication
  changed (connection | dataset X | job params); re-approve"*, or *"never approved"*. The refusal writes an AUDIT
  row (`publish.postgres.run`, outcome `refused`) and raises a `publish.postgres.refused` Signal.
- **Imports.** No import (bundle, zip, Space import, Space Template) may carry a `publish.postgres` Job, because a
  bulk import cannot be approved (`ImportCapabilityGuard`, 409).
- ⚠ On a server with no Authenticator every caller is the same anonymous actor, so four-eyes can never be met and
  such a Job cannot be created through the API.
- The zero-Space recovery create runs the import gate like every other create (closed 2026-10-03,
  `TEMPLATE-RECOVERY-IMPORT-GATE-1`). By operator decision `POST /spaces` needs `canAdminister` even when no Space
  is hosted. On a fresh install the IdP's `admin` role grants it: with no Space hosted `ControlApi.authenticate`
  binds no roles root, so `Roles.effective` serves the seed table. `TemplateSeedGate.require` no longer takes a
  `checkCapability` flag. `POST /spaces/import` is `canAdminister` always too (operator 2026-10-03) and
  always runs `ImportCapabilityGuard.checkFiles`.
- ⚠ Deleting a Job leaves its approval record. A Job re-created with the same name and identical content (only
  possible by writing the file directly, since every API create is held) would run on it.

**Run behaviour and editions**
- **Timeouts / retries / failure**:
  - `connectTimeout`, `socketTimeout`, `loginTimeout`, `SET LOCAL statement_timeout` and a per-statement query
    timeout all use `timeout_seconds`.
  - The whole transaction is retried on SQLState `08*`, `40*`, `53*` or `57P*`, with 1 s, 2 s, 4 s … backoff.
  - A failed run is `FAILED` with "nothing published (rolled back)" and the cause.
- **Audit**: every run emits an `AUDIT` event `publish.postgres.run` (`outcome`, `schema`, `tables`, `rows`,
  `error`), plus the `publish.postgres.completed` Signal on success. A dry run resolves the plan, the destination
  and the egress check, and sends nothing.
- **Edition**: the Job Type registers everywhere, but the driver is the Standard/Advanced `postgresql.jar`
  sidecar (PG-1), so a Basic run fails with `No suitable driver` (`EDITIONS.md` JOB-06).

**Tests**
- `PostgresPublishJobTest` (17, with DuckDB standing in for the target) covers:
  - injection;
  - the sensitive-column rules, including case, inheritance and views;
  - sharing;
  - loopback egress and the destination allowlist;
  - URL parameters, `sslmode` and `sslrootcert`;
  - locked parameters;
  - the atomic swap on failure;
  - incremental replacement.
- `PublishTlsTest` runs a keytool-certified loopback TLS server to check the pinned dial and the hostname binding.
- `PostgresPublishPgTest` has two tests against a real Postgres, one of them TLS `verify-full`. They are enabled
  by `INSPECTO_TEST_PG_URL` and `INSPECTO_TEST_PG_TLS_*`, and skip otherwise.
- `ControlApiPublicationTest` covers the allowlist route, the mandatory hold and the import refusal.
- `PublishAuthorityTest` covers the author seam.

### 3.7 Curated templates

`GET /bi/templates` serves a corpus that is a **Java constant** (`BiTemplates.TEMPLATES`) with exactly
three ids: `kpi-overview`, `quality-monitor`, and `trend-monitor` (the temporal one, added 2026-09-02 —
two `line` Widgets over `event_date` at `month` grain plus a KPI). `POST /bi/templates/{id}/apply`
takes a Dataset, an optional id `prefix` and (since 2026-11-05, `ONBOARDING-FIRST-RUN-1` C5) an optional
`columns` map from the template's own columns (`event_date`, `amount`, `region`, `id`) to the Dataset's, refused 422
for a column the template does not read or, when the Dataset declares its columns, one it does not declare, **conflict-checks every target id first and 409s if any
exists**, then writes them all — all-or-nothing, so a half-written board is not reachable. What it
writes is **UI-native**: `{vizType, datasetId, controls, options}` Widgets and `{name, tiles}`
Dashboards, so an applied board renders immediately instead of needing a translation step.

The gallery pane lists them and applies through a dialog with a Dataset picker and the prefix field, and
blocks apply when no Dataset exists. ⚠ Template ids are **entirely server-owned** — the client has no
catalog of them, so a doc that lists ids must read the Java constant.

### 3.8 Public embed

Sharing is fail-closed by construction. `ShareTokens`
(`inspecto/src/main/java/com/gamma/control/ShareTokens.java`) mints
`base64url(type/name/expiry).base64url(HMAC-SHA256)`; the secret comes from `-Dbi.share.secret` and must
be at least 16 characters, otherwise the whole feature reports itself disabled, issuing nothing and
verifying nothing. Verification checks the signature with a constant-time compare and then the expiry,
and `ShareRoutes` returns **the same `404`** for every failure — tampered, malformed, expired or
unknown are indistinguishable to a caller.

Three routes: `POST /dashboards/{name}/share` (capability-gated, default TTL 168 hours, `ttl_hours` to
override), `GET /public/dashboards/{token}` (anonymous resolve) and
`POST /public/dashboards/{token}/query` (anonymous query). The query is **fenced to the datasets the
shared Dashboard's own Widgets reference** — anything else is a 403 — and is still `SqlGuard`-checked.
`/public/dashboards/` is exempted from the auth gate as a *self-verifying* prefix, which is deliberately
a different mechanism from the exact-match public-path set (`SEC` §3 owns that distinction).

The viewer is `/share/:token` with **no shell and no guard**. It self-registers the plugin registry,
because nothing else on that path would. `embedQueryBody()` narrows each tile to
`{dataset, measures, groupBy, orderBy, limit}` — validated pairs only, never SQL text — and returns
`null` for the two cases that cannot be embedded: a **view-bound** Widget (a saved geo or link view) and
a channel carrying a free-text **expression** (a named Measure). Those tiles say so explicitly. Each
tile fetches independently, so one bad tile shows an inline alert while the page survives; only a bad
token fails the whole page.

### 3.9 Link Analysis

`/studio/link-analysis` works on **P3 — Entity/Link graphs** (records as business entities), never on
artifact or lineage graphs (`GLOSSARY.md` §11 keeps the four graph planes distinct).

**Where the graph comes from.** The `entity-projection` **GraphSource** is a *mapping, not a store*: a column becomes the
source Entity, another the target, optional columns carry the Link type and attributes. It is
**backend-first only** — `POST /inv/projection` folds server-side in DuckDB
(`GROUP BY` with `COUNT(*)`, **heaviest-first**, default cap 2,000 and maximum 20,000, returning
`{columns, attrCols, rows[[source,target,kind,count,total?,…attrs]], truncated}` as
`application/vnd.inspecto.la-edges.v2+json` since PERF-P10-1, 2026-11-12; the SPA decodes it to `{source,target,kind,count,attrs?}`), `POST /inv/projection/neighbors` does one-hop
expansion with bound parameters, and `GET /inv/schema/relationships` infers naming-convention foreign
keys across Datasets (`<base>_id` → a Dataset named `<base>`) so a multi-mapping projection can be
pre-filled instead of hand-picked. `attrCols` are extra validated identifiers that join the group-by
key, so distinct attribute combinations become distinct rows. The client cap is
`PROJECTION_NODE_CAP = 500`, and an Entity id is type-scoped (`entity:<type>:<value>`) only when a
multi-mapping merge supplies a type, so single-mapping ids and every existing saved view stay
byte-identical.

**Rendering** is the shared G6 host, reused by the Catalog graph and by the geo bridge. ⚠ **It does not
live where two docs say it does:** `inspecto-ui/src/app/inspecto/graph/` is the core slice (canvas, types, entity key; the
pure algorithms moved to `inspecto-ui/projects/link-analysis/src/graph/` in D-5 step 3), and the
host component and the layout catalogue are a *feature* file,
`inspecto-ui/src/app/inspecto/graph/graph-view.component.ts`, which Studio imports across features.
Nodes are canvas-drawn, so inspector logic is verified in unit tests, not preview clicks.

**Toolboxes.** **Eleven layouts** (`dagre`, `grid`, `force`, `force-cluster`, `radial`, `concentric`,
`circular`, `mds`, `mindmap`, `org`, `radial-tree`, the tree shapes gated to acyclic data). Communities
by either label propagation or Louvain. The analysis depth lives in a pure, framework-free library, so a
new algorithm is a `(graph, …) ⇒ result` drop-in: advanced traversal (weighted shortest path by tie
strength, canonicalised cycles, articulation points and bridges, ego network); the algorithm library
(PageRank, closeness/eigenvector/Katz centrality, HITS, k-core, triangle count, Bron–Kerbosch cliques,
max-flow with min-cut, maximum spanning forest, Jaccard similarity, link prediction); and
**suspicion scoring**, an explainable 0–100 composite with a per-node factor breakdown whose top decile
the toolbox highlights. Super-linear work is guarded by `ANALYSIS_NODE_CAP` (2,000).

**Six pattern packs** pre-fill the motif builder: layering chain, pass-through, inbound collector,
forwarding relay, circular flow, shared associates. They became a per-Space `pattern-pack` component
kind on 2026-07-26 rather than staying a hardcoded constant; packs whose shape is not a path motif point
at the fitter tool instead.

**A timeline** filters edges only — an edge survives when its chosen attribute parses as a date at or
before the cutoff, nodes untouched — and slots into the existing filter pipeline (kind filter → time
filter → branch collapse → the shared displayed-graph binding), so no new filtering mechanism was
needed and it participates in undo/redo like every other presentation filter.

**Saved investigations** are `link-analysis-view` components with version history through the shared
component-history dialog, and a saved view whose source is `entity-projection` is renderable as a Widget.

### 3.10 Geo Map Analysis

`/studio/geo-map` answers the *where* of an investigation, sibling to Link Analysis's
*who-connects-to-whom*. Vocabulary is `GLOSSARY.md` §11-Geo: GeoSource, GeoQuery, GeoPoint/GeoRoute, Geo
View, Layer, Geocoder — ⛔ never "marker" or "pin" in a model name.

**Fully offline.** A MapLibre GL host over a basemap bundled in `inspecto-ui/src/assets/basemap/` — ⚠ **four
slimmed Natural Earth GeoJSON layers (land, boundaries, lakes, places) plus glyph fonts, ~2.7 MB. It is NOT
PMTiles**: a planet extract at z0–6 would have been ~100 MB, so D2 (2026-07-05) took GeoJSON and accepted
the consequence of no satellite or terrain imagery in-bundle. The plan kept `pmtiles://` as a registered
protocol for a future customer archive, and **no code in the tree references it today**. An offline
place-table Geocoder sits behind a pluggable seam.

**Data plane.** A **GeoSource** projects Dataset rows to GeoPoints (lat/lon column mapping) or to
weighted great-circle **od-routes**. Server side (`la/inspecto-la-api/src/main/java/com/gamma/la/api/GeoRoutes.java`):
`POST /geo/projection` filters valid WGS84 coordinates with `TRY_CAST` and reports a `skipped` count;
`POST /geo/routes` folds origin/destination/kind with a summed `weight`. **Plain SQL only** — no `ST_*`
call exists anywhere in the tree. The client cap is `GEO_POINT_CAP = 5000` on both paths.

**Display and tools.** Heatmap or markers, a time slider with playback (~30 steps), filter-to-view; an
intelligence toolbox of co-location, frequent-location and stay-point analyses; measure, radius,
polygon and note tools; a layer manager with custom GeoJSON overlay upload and GeoJSON export;
parallel-route bows. Saved views are `geo-map-view` components carrying GeoSource + GeoQuery + display
options + camera.

**The bridge** (`INV-4`): a co-location result opens a graph dialog over the shared G6 host. A point
that resolves an `objectRef` also offers "View in graph", the shared investigation-pivot contract.

### 3.11 Measure alerting

An `AlertRule` in measure form is
`alert { dataset:, measure: agg(field), comparator, threshold, severity }`
(`platform/inspecto-engine/src/main/java/com/gamma/alert/AlertRule.java`). Its constructor **requires** the
legacy ledger-metric field, `window` and `when` to be absent, which is how "no per-rule filters" is
enforced rather than merely documented. `DatasetMeasureProbe`
(`platform/inspecto-engine/src/main/java/com/gamma/query/DatasetMeasureProbe.java`) is the headless evaluator —
the same compile-then-sandbox pattern as §3.3 reduced to a scalar — and it **degrades to empty rather
than throwing**, so a broken Dataset cannot take down the sweep. `AlertService` evaluates these on every
sweep and fires the existing `ALERT_FIRED` path; the Alert object and its notification are `INC`'s.
A worked example ships: `spaces/demo/config/registry/alert-rules/orders_low_volume.toon`.

### 3.12 Edition gating

**The BI half is ungated** — `BiRoutes`, `ShareRoutes` and the templates are in the core built-in route
list, and no `features.*` flag governs the Query Library, Viz Library, Dashboard Builder, Template
Gallery or KPI gallery. `EDITIONS` `CP-08` is ✅ in all three editions and is correct.

**The two investigation studios are Standard+** (`CP-09`, EDG-01 cell 3b, 2026-09-07).
`inspecto-la-api` contributes `GeoRoutes` and `InvRoutes` (and the rest of the Link Analysis routes) through the public `RouteModule`
ServiceLoader seam (`la/inspecto-la-api/src/main/resources/META-INF/services/com.gamma.spi.http.RouteModule`); `inspecto-geo-link` is the
bridge (it binds the Dataset and Case ports and holds the Alert Rule routes).
Absent the module, core `AbsentModuleRoutes` claims the five paths and answers `503` naming what is not
installed. The SPA reads `bootstrap.features.geoLink` into a session signal and **hides** the two nav
entries and the Menu-Builder offer.

🔴 **`hasRoute` deliberately excludes stubs, and that exclusion is the whole point.** The first version
counted them, so `/bootstrap` reported `geoLink: true` on a Basic build because the 503 stub had
claimed the pattern. *"A route exists" and "the feature is installed" are different questions* — the
flag is derived from what actually registered, never guessed from the edition string. Stubs register
**last**, only for patterns `hasRoute` reports unclaimed.

⚠ **The routes stay registered when the flag is false.** Only the nav entry is hidden, so a deep link
still reaches the pane, which then renders the module's 503 as an edition message rather than a generic
query failure. Pinned by `NoGeoLinkShipsInThePersonalBuildTest`.
