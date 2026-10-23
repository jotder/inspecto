# BI result masking - design (`BI-QUERY-MASKING-1`)

> **ARCHIVED 2026-10-23 — `BI-QUERY-MASKING-1` CLOSED after its security review (section 12).** As built:
> [`okf/product/module/processor/spec-queries.md`](../../okf/product/module/processor/spec-queries.md) § *Classified entity keys
> are masked in BI result rows*. The status lines below are history.
>
> **Status: DESIGN + first slice + gap-closure slice built 2026-10-12 (lane), awaiting operator security review before any push.**
> Gap closure (section 11): `/db` routes, Report exports, KPI values, Rule Template simulate, numeric key derivations and the
> `$parameter` oracle are covered; Exchange snapshots stay open on decision D-M6 (section 11.6).
> Decision D7 of [`parked-rows-scoping-2026-10-12.md`](parked-rows-scoping-2026-10-12.md): classified entity keys in BI
> result rows are MASKED unless the caller holds the reveal capability. This is a security change that alters what
> every dashboard, report and share viewer sees; it is written to be reviewed line by line.

## 1. Problem and threat model

A Dataset with a classified column (registry `columns[]{classification}`, or a classification inherited through
lineage) returns that column RAW from `/bi/query`, `/queries/{id}/run`, `/datasets/{id}/rows` and the public share query,
to whoever may call them. The platform already masks the same keys elsewhere: Link Analysis reads (alias
`masked:<16 hex>`), Entity List members, audit rows and Risk / Anomaly Score reads (`keyMasked`). BI is the hole.

| Reader | Today | Concern |
|---|---|---|
| Dashboard viewer (Viewer role, no `canRevealLinkEntities`) | sees raw keys in any table/top-N Widget | the viewer role is the broadest audience |
| Report recipient | a Report job writes a file from rows (CSV/XLSX/PDF) | the file leaves the platform and is not capability-checked at read |
| Public / self-verifying share (`/public/dashboards/{token}/query`) | anonymous caller, NO Subject | worst case: no identity at all |
| Alert / Case evidence links | an evidence link opens a Widget or a Query run | inherits whatever the BI route returns |
| A filter typed by a viewer | `msisdn like '9%'` or `>` bisects raw values out of a masked column | an inference channel even when cells are masked |
| An aggregate that returns a key (`min`/`max` of a key) | returns one raw key per group | leaks through a measure column instead of a dimension |

Out of scope here: a caller who holds the reveal capability (sees raw by design), and the Space operator reading the
store directly.

## 2. Inventory of every path that returns Dataset rows to a viewer

Found by grepping every `QueryExecutor.run` / `DatasetRelation.relationSql` call site (not guessed).

| # | Path | Caller | Masked today | After this slice | Proposed rule / note |
|---|---|---|---|---|---|
| 1 | `POST /bi/query` | viewer / Widget / Dashboard / Alert evidence | no | **yes** | helper over the result; dimension + min/max cells; refuse filter / order on a masked column |
| 2 | `POST /queries/{id}/run` | viewer / Query Library / Report preview | no | **yes** | helper over the result, matched by output column name; see 4.4 for aliasing |
| 3 | `GET /datasets/{id}/rows` | Widget Builder, viewer | no | **yes** | the same helper (a one-line reuse) |
| 4 | `POST /public/dashboards/{token}/query` | anonymous share recipient | no | **yes, never revealed** | same helper with reveal = false whatever the request carries; the stored dashboard filter is trusted, the recipient's own `when` is vetted |
| 5 | Widget / Dashboard data | (client-side over 1) | n/a | **yes via 1** | a Widget has no data route of its own; the Dashboard fetches `/bi/query` per tile |
| 6 | `GET /db/table`, `POST /db/query` (business-store group) | viewer | no | **yes (gap closure, 11.1)** | a STORE carries the classification of the Datasets over its `physicalRef` plus the pipeline schema; sort / WHERE / JOIN on a masked key refused |
| 7 | Report render/export (`ReportJob`: CSV / XLSX / PDF written by a scheduled Job) | the Job (no request caller) | no | **yes, always masked (gap closure, 11.2)** | masks at WRITE time as a non-revealing caller; a reveal option is the open half of D-M5 |
| 8 | Share snapshot / Exchange snapshot (`ExchangeSnapshotWriter`) | another Space | no | **NOT covered - open on D-M6 (11.6)** | publishes a Dataset as Parquet to a consumer Space; a data product, its own contract |
| 9 | `GET /kpis/{id}/value`, `POST /rule-templates/{id}/simulate` | viewer / author | no | **yes (gap closure, 11.3, 11.4)** | a KPI whose Measure is min / max / sum / avg of a masked column is refused (a one-row period IS the key); simulate masks its rows and refuses a hole bound to a masked column |
| 10 | Intelligence tools (`InspectoTools` query_author) | assistant | no | not covered | reads rows under the author capability |
| 11 | Risk / Anomaly Score reads | viewer | yes (`keyMasked`) | yes | existing; uses the Risk Score key (see 6) |
| 12 | Link Analysis reads | viewer | yes | yes | existing (`ExplorationMasking`) |

## 3. Which columns are masked (the rule)

One helper decides, per Dataset and Space. The Space's `maskingMode` (the same setting Link Analysis reads) governs:

* `none` - nothing is masked (the Space operator's explicit choice).
* `typed` (the default) - a result column is masked when the Dataset's classification for it is claimed by a MASKED
  in-force Entity Type (`EntityTypes`, defaults claim MSISDN, IMSI, IMEI, WALLET, ACCOUNT, SUBSCRIBER). Classification is the
  platform's one resolver: the Dataset's own `columns[].classification`, plus lineage (`EvidenceMasker.lineageClassification`,
  sibling Datasets over the same store and the pipeline schema through the mapping).
* `all` - every column classified MSISDN / IMSI / ACCOUNT / PII is masked, whether or not an Entity Type claims it.
* **Untraceable lineage** (a view or virtual Dataset over a classified store, an unreadable pipeline) fails CLOSED:
  every non-numeric dimension column is masked, since a rename (`msisdn AS m`) cannot be followed statically.

⚠ In `typed` mode a classified column no masked Entity Type claims (PII under the default types) stays raw, exactly as
in Link Analysis. That is the rule the operator named; decision D-M1 offers masking every sensitive class instead.

## 4. Mechanics

### 4.1 Alias
`masked:<16 hex>` = `MaskTokens.token(key, rawId)`, the HMAC-SHA256 under the Space's ONE key (the identity fact log's
`mask.key`, `<write root>/audit/entity-facts/`), the key Link Analysis exploration reads and Entity Lists use. So a BI
alias equals the Link Analysis exploration alias and the Entity List alias of the same raw id in the same Space, and an
analyst can paste an alias from a Widget into an Entity List. The key derivation is now ONE function
(`MaskTokens.spaceKey`), which `ExplorationMasking` delegates to. **D-M7 BUILT 2026-10-13 (operator, option 2):** the Space identity key now lives in `<write root>.secrets/.risk-score-mask.key`
(owner-only, outside every exported tree) and is read by `MaskTokens.spaceKey` AND `EvidenceMasker`, so a Risk / Anomaly Score row, an audit
read, an Entity List member, Screening, Link Analysis exploration and a BI cell show one alias for one id. Investigation keys stay per Investigation.
Tokens minted before the move differ (free). Pinned by `ControlApiBiQueryMaskingTest` (BI alias equals `EvidenceMasker` token).

### 4.2 Aggregation
The mask is applied AFTER execution, to the output cells. Grouping, `countDistinct` and ordering run over raw values, so a
grouped query over a masked key returns exactly the groups it returned before, with the key shown as its alias. The alias
is a function of the value, so equal keys show equal aliases and a Widget can still count entities.

### 4.3 What a filter, an order or a key-returning aggregate on a masked column means
A viewer must not be able to TEST or BISECT raw values through a masked column. For a caller who cannot reveal:

* a `filters[]` term, a `when` condition, or an `orderBy` term (visible or hidden) on a masked column is **refused 422**
  (`... is masked; filtering or ordering by it would leak its values`);
* a `min` / `max` / `sum` / `avg` measure over a masked column is **refused 422** (it returns a raw key per group);
  `count` and `countDistinct` stay allowed (they return a number, never the key).

A holder of the reveal capability is unaffected. This is the fail-closed choice: the stored filter of an author is NOT
exempt for a viewer (the migration note names the visible break). Alternative (D-M3): accept `eq`/`in` against an alias
by resolving it server-side - rejected for now, it needs the in-memory alias book Link Analysis keeps.
The default ordering of an ungrouped-order grouped query is by raw key; it does not disclose a value, only a rank.

### 4.4 `/queries/{id}/run` (arbitrary SQL)
Output columns are named by the author's SQL, so the match is by name PLUS a conservative rule: the output column is masked
when its name equals a masked Dataset column, OR when the query text mentions a masked column and the output column is a
string that is not itself a known unclassified column of the Dataset (`SELECT upper(msisdn) AS u` and `msisdn AS m` are
masked; `region` and `count(*) AS n` are not). A NUMERIC derivation of a numeric key (`CAST(msisdn AS BIGINT) AS m`) is now detected by the DuckDB parse tree
(11.5); only when the tree cannot be trusted does the coarse rule apply.
A bound-query Widget on `/bi/query` (`query` in the body) uses the same rule over the saved query's text.

### 4.5 Response
`masking: {mode, masked, columns[], basis}` is added beside `rows` (like the LA routes). A masked column's descriptor type
becomes `string` (a numeric MSISDN column otherwise advertises `number` over a string cell). A Dataset with no masked
column is untouched: the body is byte-identical to today (no `masking` key), pinned by a test.

### 4.6 Capability and audit
Capability: `canRevealLinkEntities` (`Roles.CAN_REVEAL_LINK_ENTITIES`), the one grant meaning "may see raw keys"
(Audit read masking, Risk Scores, Link Analysis). A request with no Subject (Personal, no auth module) is unrestricted,
as every `withCapability` gate is; the PUBLIC share route is the exception and never reveals.
Reveal audit: decision D-P8 (2026-10-06) deliberately does not audit reads; this slice follows it. Auditing every BI read
that revealed a key would write one event per Widget refresh; if the operator wants it, record one `REVEAL` event per
request that returned an unmasked classified column (D-M4).

### 4.7 Performance
Zero extra queries when no column is masked (the decision reads the registry the route already opened, and
`LinkAnalysisSettings` is a tiny file). When masking is active the cost is one HMAC-SHA256 per masked cell (about 1 us), at
most the row cap (10 000 rows x masked columns), with a per-request cache so a repeated key hashes once.

## 5. Export, report, share handling

* Report / CSV / XLSX / PDF from a scheduled Job: **not covered** in this slice (7 above). Recommendation: the Report Job
  masks at write time unless its configuration carries an explicit, capability-checked `revealKeys` (the Job has no
  viewer to ask), mirroring `EvidenceMasker`'s write-time masking of Risk Score evidence.
* Public / self-verifying shares: path 4 is covered and never reveals. Share SNAPSHOTS (8) are not.
* Alert / Case evidence links resolve through 1-3, so they are covered when the link opens a BI route.

## 6. Options

| Option | What | Verdict |
|---|---|---|
| A | one helper over the result of the primary routes (1-4), fail closed; the rest as follow-up rows | **recommended, built** |
| B | A plus a data-scope on the Dataset (viewers cannot select classified columns at all) | stronger but changes authoring; a later row |
| C | mask at the relation (a view that already hashes), nothing at the route | catches every path at once but breaks reveal and Entity List joins; needs a per-caller relation, not available |
| D | mask only Datasets flagged as scores outputs (the scoping note's D7c) | leaves every other classified Dataset raw; rejected |

## 7. What this slice does NOT cover (honest list)

Exchange snapshots (11.6); the assistant's query tool; the other Job types that write rows to files; the raw-order rank of a
default-ordered grouped result; the Risk Score key mismatch (4.1); `typed` mode leaving an unclaimed PII column raw. Closed
since the first slice (section 11): `/db` routes, Report Job exports, KPI values, Rule Template simulate, numeric
derivations, the `$parameter` oracle.

## 8. Migration note (what existing dashboards visibly change)

* A table or top-N Widget that showed a classified key now shows `masked:<16 hex>` to a viewer; the same Widget shows raw
  to a holder of `canRevealLinkEntities`. Charts counting distinct keys are unchanged.
* A Dashboard or Widget with a FILTER or SORT on a classified column now answers 422 for a viewer (it keeps working for a
  revealer). Remove the filter, or grant the capability to the viewer role.
* A Widget using `min`/`max`/`sum`/`avg` on a classified column answers 422 for a viewer.
* Public share recipients see aliases, always.
* A Space that wants today's behaviour sets `maskingMode: none` in Link Analysis settings (affects Link Analysis too).

## 9. Test matrix

Real-HTTP `ControlApiBiQueryMaskingTest` with an armed Subject: classified column masked for a viewer and raw for a holder
of `canRevealLinkEntities`; an unclassified Dataset byte-identical (no `masking` key); Space mode `all`; Space mode `none`;
alias equals the Space-key alias; a grouped query over a masked key keeps its group count; a filter / order / `min` on a
masked column is refused 422; no raw id anywhere in the body; `/queries/{id}/run` and `/datasets/{id}/rows` and the public
share route. Each negative test is MUTATED once (skip the mask -> red; swap the capability -> red).

## 10. Decisions for the operator

* **D-M1** `typed` mask only classes a masked Entity Type claims (built), or every sensitive class (PII too)?
* **D-M2** a Space on `maskingMode none` opts BI out as well (built), or BI always masks?
* **D-M3** refuse filter / order on a masked column (built), or accept an alias value through a server-side book?
* **D-M4** audit a reveal (one event per BI request that returned raw classified data)? Today: no (D-P8).
* **D-M5** Report Job exports: built as ALWAYS masked (the fail-closed default). Still open: an explicit, capability-checked
  `revealKeys` on the Job for a recipient who legitimately needs raw keys (needs a Job write gate; see 11.2).
* **D-M6** Exchange snapshots of a classified Dataset: mask, refuse the offer, or carry the classification (OPEN, 11.6).
* **D-M7** unify the Risk Score mask key with the Space identity key so one id has one alias everywhere?

## 11. Gap closure (2026-10-12, second slice)

Every item below follows the first slice's rule (fail closed, a Subject-less request unrestricted, the public share never
reveals) and is pinned by a real-HTTP or real-Job test in which the SAME request succeeds for a caller holding
`canRevealLinkEntities` (or with `maskingMode none`), so the refusal or the alias is the only variable.

### 11.1 `/db/table` and `/db/query` (stores of business data)
A store is not a Dataset, so `DbBrowserRoutes.storeMasking` builds a pseudo-Dataset over the store: `physicalRef = <store>`
and the merged `columns[]` of every Dataset whose `physicalRef` is that store. The one resolver then adds the pipeline-schema
and sibling-Dataset lineage as usual. `GET /db/table`: the rows are masked, a `sort` on a masked column is refused 422.
`POST /db/query` is the caller's OWN SQL, so a masked key may only be SELECTED: any other mention (WHERE, HAVING, GROUP BY,
ORDER BY, JOIN, subquery, CTE) is refused 422 using the DuckDB parse tree (`SqlGuard.parseSelectNode`, a thin public reader
of the parser the safety gate already runs); text that does not parse as one plain SELECT is refused when it names a masked
column. A `count(CASE WHEN key = ..)` or `count(*) FILTER (WHERE key = ..)` is treated as a derivation (its output is aliased),
not as a count. A store no Dataset or pipeline classifies is byte-identical. The operational (`ops:*`) groups are unchanged
(already behind `canAuthorWorkbench`; they hold control-plane tables, not entity keys).

### 11.2 Report Job exports
`ReportJob` (scope `dataset`) writes a file for recipients nobody can capability-check, so the rows are ALWAYS written as a
non-revealing caller would see them. The classification lives in the entity-store module, which the engine cannot see, so the
engine declares the seam `com.gamma.job.ReportRowMasking` (a `ServiceLoader` SPI) and the control-plane module provides it
(`BiReportRowMasking`, registered under `META-INF/services`). With no provider on the classpath (an engine-only deployment)
there is no classification surface and rows pass through. A `min / max / sum / avg` Measure over a masked column FAILS the Run
(`IllegalArgumentException`, nothing written): it would write one raw key per group. `maskingMode none` opts the export out.
Open (D-M5): a `revealKeys` option on the Job. It needs a capability gate at Job write (`JobWriteGuard`) and an approval
fingerprint entry like `attach`, and it is a product decision: who may author a Report that ships raw keys outside the platform.

### 11.3 KPI values
`GET /kpis/{id}/value` returns one number per period. A KPI over `max / min / sum / avg` of a masked column returns the key
itself (or its multiple) when the period holds one row, so it is refused 422 for a non-revealing caller; `count` and
`countDistinct` are unaffected. The refusal is at READ time (the save gate is unchanged: an author who cannot reveal may still
save such a KPI, which then serves a revealer only).

### 11.4 Rule Template simulate
`POST /rule-templates/{id}/simulate` (gated `canAuthorWorkbench`, which is NOT the reveal capability) masks its rows through
the same helper and adds the `masking` block. A hole whose `field` is a masked column, with a caller-supplied value, is refused
422: `WHERE msisdn = :who` is an equality oracle.

### 11.5 Numeric derivations and the `$parameter` oracle
* **Numeric derivation.** For free SQL the helper now reads the DuckDB parse tree: an output POSITION is masked when its
  select-list expression references a masked column (or, with an untraceable lineage, any column not declared clean) through
  anything but a plain `count`. This closes `CAST(msisdn AS BIGINT) AS m`, `msisdn::BIGINT + 1`, and an alias that reuses a
  declared-clean name (`CAST(msisdn AS BIGINT) AS amount`). When the positions cannot be trusted (parse failure, a subquery or CTE,
  `SELECT *`) every numeric output column not declared clean is masked instead (fail closed; a `count(*) AS n` then reads as
  masked in such a query).
* **`$parameter` oracle.** `POST /queries/{id}/run` substitutes caller-supplied `parameters` into the saved text, so
  `WHERE msisdn LIKE $prefix` bisects a masked key. A caller who cannot reveal and supplies ANY parameter value is refused 422
  when the text names a masked column (or the lineage is untraceable). The author's declared defaults stay trusted and a
  parameter over an unclassified column is unaffected.

### 11.6 Exchange snapshots - BUILT 2026-10-13 as option (a) (D-M6, operator); the analysis below is the decision record
`ExchangeSnapshotWriter.publish` copies the owner's WHOLE Dataset to `snapshot.parquet` and records column name / type / role
only. Two facts make this a product decision rather than a patch:
1. Classification does not travel. The consumer Space reads the snapshot through a shared ref whose own Dataset declares no
   `classification`, so the consumer's BI routes (all now masking) see an unclassified column and return the raw key. Masking
   in the consumer therefore needs the classification carried in the snapshot metadata (`current.toon` `columns[]`).
2. The offer is a data PRODUCT: the contract with the consumer may include raw keys (a join against the consumer's own
   subscriber list). Aliasing at publish time (HMAC under the OWNER's identity key) breaks that join; refusing the offer
   breaks the sharing feature for classified Datasets.

Options for the operator: (a) carry the classification in the snapshot meta and mask in the consumer's BI routes under the
CONSUMER's reveal capability (raw stays on disk, no join break; the file itself is readable by the consumer Space's operator);
(b) alias at publish under a key shared by both Spaces (breaks nothing that joins on alias, breaks raw joins); (c) refuse to
offer a Dataset with a masked column unless the grant carries an explicit `revealKeys` (strictest). The module boundary also
applies: `inspecto-exchange` cannot see the entity store, so (b) and (c) need a seam like 11.2 and (a) needs only metadata.
As built: the snapshot meta carries `classification` per column (`NONE` when clean, `UNKNOWN` when the owner's lineage is untraceable); the consumer judges it under its own entity types; a live grant or a snapshot without the field fails closed.

### 11.7 Still open (not built, no decision needed beyond filing)
* `query_author` was checked 2026-10-13: it returns a validated SQL draft and never executes it, so it returns no rows (not a hole).
  Any Job type other than the Report Job that writes rows to a file still needs the 11.2 seam wired in.
* Built 2026-10-13: `REPORT_READY` carries `aliasedColumns` (the output columns holding an alias), via `ReportRowMasking.Masked`.

## 12. Security review (2026-10-23, lane E) - findings and the fix for each

The row's remaining gate was a security review of the built masking. Each consumer path was attacked as a viewer without
`canRevealLinkEntities` (and, for free SQL, as an author without it) on DuckDB 2.0.0.0. Findings already filed as
`BI-MASKING-ROW-VALUE-LEAKS-1` (the row as a value, renames in FROM, a decodable alias, sort on an aliased key in a saved
query) belong to that row and its own lane; they are not repeated here. What this review adds:

| # | Path | Finding (reproduced) | Fix (session decision unless noted) |
|---|---|---|---|
| R1 | `/bi/query` with a bound Query | the masking read the bound text as `WITH "__bound_query" AS (...)`, which does not parse, so an opaque reference in the saved query (`SELECT #1 AS x FROM calls_ds`, a star that renames) was never seen: `x` came back RAW and a filter on it was allowed | the masking reads the saved query's own text: when it names a key, holds an opaque reference, or the lineage is untraceable, every output column is masked and a filter / order on an undeclared column is refused, exactly as for a text that names the key |
| R2 | every route that runs SQL (`/queries/{id}/run`, `/bi/query`, `/datasets/{id}/rows`, `/db/table`, `/db/query`, rule simulate, KPI value, public share) | the 422 carried DuckDB's own text, and a run-time error QUOTES the value: `error(msisdn)` answers `Invalid Input Error: 4417..`, a failed `CAST(msisdn AS DATE)` (free SQL or a calculated column) names the string | while masking is in force for the caller, only a compile-time error (`Parser` / `Binder` / `Catalog`, raised before any row is read) keeps its text; any other answers a fixed sentence. A revealer, an unclassified Dataset and `maskingMode none` keep the full text |
| R3 | `/queries/{id}/run` with caller `parameters` | the `$parameter` oracle refusal looked only for the key's NAME, so `WHERE CAST(calls_ds AS VARCHAR) LIKE $p` or `WHERE #1 LIKE $p` let a viewer bisect | refused whenever the text reads a key by any route the result masking recognises (name, opaque reference, untraceable lineage) |
| R4 | `/datasets/{id}/rows`, `/bi/query` over a calculated column | `DatasetRelation` wraps the base as `__base`; a calculated column `concat(__base)` or `__base` is the whole row, raw key included, and the name rule never saw it | a calculated column whose expression names `__base` is masked like one naming a masked column |
| R5 | `POST /public/dashboards/{token}/query` on an install where nobody signs in | `MaskingDefault` makes the Space's unstated mode `none` there (`LA-ONBOARD-1` OB-6, A-16: "anyone could reveal anyway"), so a public link handed classified keys RAW to an anonymous holder, who cannot reveal anything | the public share reads the mode the Space STATES, else the shipped `typed`, never the sign-in default. A Space that states `maskingMode: none` keeps raw shares (D-M2, its explicit choice). Session decision, recorded for the operator to overturn |
| R6 | public share, a chart over a masked key | the SPA's share viewer sends the chart's cosmetic category order (`cosmetic: true`); `/bi/query` drops it for a non-revealer, the public route refused it, so every bar / line tile grouped by a masked key failed on a shared link (fail-closed, not a leak) | the public route drops the cosmetic order as `/bi/query` does, before compiling |

Not changed, stated: a reveal is not audited per BI read (D-P8 / D-M4 stand; A-16's "reveal audited" is the explicit Link
Analysis reveal); a Report Job run that fails keeps the driver text in its Run record (an author-only two-step channel through
a calculated column; Report rows themselves are masked); two view-backed Datasets over the same view do not share a
classification (`EvidenceMasker` matches siblings by `physicalRef`; a pipeline-produced view is classified by its schema).
Tests: `ControlApiBiMaskingReviewTest` (each fails on the code before this section).

Independent verification of this section (2026-10-23) confirmed R1..R6 (each test fails on the previous code) and found two
channels the review did not close, filed as `BI-MASKING-FREE-SQL-ORACLES-1` (hold: operator): a saved query's own predicate
(`WHERE msisdn = '..'` saved and run by an author without the reveal capability answers a count; section 11.5 trusted the
author's text, `/db/query` does not), and a run-time error as a yes / no answer (`CASE WHEN msisdn = '..' THEN error('x')
END` on `/db/query`: R2 withholds the text, not the failure). Neither is closed by masking the output; the direction recorded
on the row is to let a non-revealer's free SQL name a masked key only as a bare selected or grouped column or `count`
argument.
