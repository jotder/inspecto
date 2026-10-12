# BI result masking - design (`BI-QUERY-MASKING-1`)

> **Status: DESIGN + first slice built 2026-10-12 (lane), awaiting operator security review before any push.**
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
| 6 | `GET /db/table`, `POST /db/query` (business-store group) | viewer | no | **NOT covered** | reads a STORE by name (not a Dataset): needs a store-to-Dataset classification resolver; filed as follow-up |
| 7 | Report render/export (`ReportJob`: CSV / XLSX / PDF written by a scheduled Job) | the Job (no request caller) | no | **NOT covered** | masks at WRITE time under the Job (no Subject): decision D-M5 |
| 8 | Share snapshot / Exchange snapshot (`ExchangeSnapshotWriter`) | another Space | no | **NOT covered** | publishes a Dataset as Parquet to a consumer Space; a data product, its own contract (D-M6) |
| 9 | `GET /kpis/{id}/value`, `POST /rule-templates/{id}/simulate` | viewer / author | no (scalar / gated) | not covered | KPI returns one aggregate, no key; simulate needs `canAuthorWorkbench` |
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
(`MaskTokens.spaceKey`), which `ExplorationMasking` delegates to. ⚠ Risk / Anomaly Score reads use a DIFFERENT key
(`.risk-score-mask.key`, `EvidenceMasker`), so a score row's alias differs from the BI alias of the same id. Pre-existing;
unifying them is decision D-M7 and is out of scope.

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
masked; `region` and `count(*) AS n` are not). A NUMERIC derivation of a numeric key (`CAST(msisdn AS BIGINT) AS m`) is NOT
detected: that is a documented residual (see 7).
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

`/db/table` and `/db/query` on business stores; Report Job exports; Exchange snapshots; the assistant's query tool;
numeric derivations of a numeric key through `/queries/{id}/run`; the raw-order rank of a default-ordered grouped result;
the Risk Score key mismatch (4.1); `typed` mode leaving an unclaimed PII column raw.

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
* **D-M5** Report Job exports: mask at write time? (follow-up row)
* **D-M6** Exchange snapshots of a classified Dataset: mask, or refuse the offer? (follow-up row)
* **D-M7** unify the Risk Score mask key with the Space identity key so one id has one alias everywhere?
