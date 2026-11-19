---
type: Reference
title: Link Analysis — as-built reference and design records — Grounded limits and consequences
description: Part 2 of 6 of Link Analysis — as-built reference and design records: Grounded limits and consequences
tags: [module, la-core, link-analysis, architecture]
timestamp: 2026-11-15T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Link Analysis — as-built reference and design records — Grounded limits and consequences

Part 2 of 6 of [Link Analysis — as-built reference and design records](architecture-records.md) · previous: [Product, code layout, pattern packs and labels](architecture-records-product-layout.md) · next: [Evidence, the op log and Graph Run](architecture-records-evidence-graph-run.md).

## Grounded limits and consequences

*Code read 2026-09-20 / 2026-09-22, updated through 2026-11-14.* Durable facts distilled from the archived
`link-analysis-spec.md`; open work is on `docs/BACKLOG.md` §3.12.

### Limits in force

**Every limit is configuration (`LA-CONFIG-LIMITS-1`, 2026-10-15)** — catalogue, layers, routes and hot apply:
[architecture-la-core.md](architecture-la-core.md) §9. SPA side: `LaLimitsService` (`la-limits.service.ts`) loads
`GET /settings/link-analysis/limits` per Space into the `laLimit(key)` store (`la-limits.ts`); code reads `laLimit(key)`
(the analyst's own value for `browser:true` items, else the Space value). `LA_LIMIT_FALLBACKS` is the ONE fallback table
(before the GET answers, after it fails, or in the embed viewer), pinned by `LaLimitCatalogueParityTest`. Personal values:
`localStorage` `inspecto.la.limits.personal.<space>.<actor>`.

* **Defaults now:** canvas `projection_node_cap` 1,000; `analysis_node_cap` 2,500 (selection algorithms hand over to the
  server above it, the branching matcher refuses); `suspicion_node_cap` 1,000, the ONE value browser and server share
  (suspicion computes betweenness without betweenness's guard, so a 501–1,000 node run is not refused in betweenness's
  words); per-algorithm `browser_algorithms.<id>` = min(2,500, the server inline ceiling) — betweenness 500; closeness,
  cliques, max flow, link prediction, eigenvector, Katz, HITS 1,000; Louvain, label propagation 2,500. Refusals name the
  algorithm and the cap. Betweenness's 500 is where EXACT ends (`LA-PERF-P5-1`, 2026-11-13): above it the ranking is
  sampled and marked "Approximate", up to the analysis cap. Display caps (`display.*`: LOD labels 300, layered 30, radial
  100, information density 300, clustering force 200, embed 80 / 3,000, geo 5,000) are per-analyst; `/geo`'s default
  limit is `display.geo_point_cap`.
* **Connect** sends `connect.default_hops` (3); `connect.max_hops` (6) is only the ceiling the analyst may pick.
* `MAX_CASE_MEMBERS` (100) stays an SPA constant (server `CaseRoutes.MAX_CASE_ENTITIES`). The dashboard link and geo
  widgets: [architecture-la-core.md](architecture-la-core.md) §9 *SPA loading*.

| Limit | Default | Where | At the edge |
|---|---|---|---|
| `projection_node_cap` | 1,000 | `projectionNodeCapValue()` (`entity-projection.ts`) | truncates; the 4,000-row read cap often binds first (465–767 nodes) and the notice says which cap cut it |
| `analysis_node_cap` | 2,500 | `analysisNodeCapValue()` | ⚠ throws on super-linear algorithms (caught, see below) |
| `traversal.rows_default` / `rows_max` | 4,000 / 40,000 rows | `LaLimit.java` | clamps, `truncated: true` |
| Render | none | `graph-view.component.ts` | no virtualisation, culling or WebGL (WebGL measured and refused: [spec-la-core.md](spec-la-core.md)) |

* **Measured-defaults caveat:** the shipped caps came from one host, one browser, one synthetic shape; a denser graph or
  slower laptop moves them.
* ⛔ **A persisted limit is refused, not clamped** (422 naming the key and the range): an operator typed it and deserves
  to be told; a silent clamp is for per-request parameters only.
* ⛔ **Client setters fail closed:** a cap that is not a finite integer ≥ 1 is ignored and the previous value stands (a
  `0` or `NaN` cap would switch the whole toolbox off). Pinned by a mutation-checked spec.
* ⚠ **The graph modules are pure libraries with no DI**, so limits are PUSHED in (`configureGraphLimits`,
  `configureProjectionLimits`) and enforcement sites read the live value. 🔴 A Space change RESETS to defaults before
  applying the new Space's values (else an absent field would keep the previous Space's override).
* ⛔ **The analysis-cap refusal is graceful:** `requireUnderCap` throws, all ten sites catch it, clear the stale result
  and render a warning naming the algorithm, cap and size; converting them to an outcome value was refused as churn.

### Projection, safety and the canvas

* **An edge is a folded aggregate** (`GROUP BY`, carrying `count`), nodes are implied endpoints, and nothing is persisted
  server-side — every call re-aggregates. 🔴 **A saved view is not evidence**: reopened after the Dataset changed it
  silently shows a different graph (see *saved views* below).
* **Server algorithms are all reachable** (`LA-ALGO-UI-PARITY-1`, DR-U11/U12): *All algorithms (server)*
  (`link-analysis-server-algorithms.component.ts`) builds its form from the catalogue's parameter descriptors, reuses
  `LinkAnalysisServerRunComponent` and reads answers by result kind (`graph-run-summary.ts`). Every completed answer goes
  to `applyCatalogueResult` BY KIND: `SCORES`/`HITS` → the ranking; `COMMUNITIES` and connected components' disjoint
  `GROUPS` → communities; cliques (overlapping) stay summary-only. The browser runs 21 algorithms with local code on the
  main thread; the backend's projection does SQL fold and filter only (deliberate), so the browser path is bounded by
  one tab.
* **Safety is narrowness:** identifiers match `SAFE_IDENT`, values bind as JDBC `?`; 503 without a write root, 404
  unknown Dataset, 422 bad identifier.
* **The canvas is not a rebuild** (LA-05). `GraphViewComponent.ngOnChanges` does the smallest thing: layout / plugin /
  tooltip / fill change recreates; data change is applied onto the live graph; cosmetic change (emphasis, display, theme)
  repaints without layout. Comparisons are by VALUE (`stableKey`) because every input is a fresh `computed()` reference.
  Measured on 170 nodes: toggling node labels keeps the G6 instance and moves 0 of 170 nodes. `displayOptions` and
  `canvasPlugins` carry `equal:` comparators.
* 🔴 **`graph.draw()` does not repaint** in G6 v5: it re-renders held specs without re-running style mappers. A repaint is
  `setData(this.data)` then `draw()` (mappers re-run, layout does not). Found by measurement; jsdom cannot see it.
* ⚠ **A `labelText` mapper returning `undefined` does not clear a label** (G6 reads "no change"); use `label: boolean`.
* **Two-stage filter, stage 2 live since LA-01:** `POST /inv/projection` and `/neighbors` take an optional `filter` (the
  `query-types.ts` condition tree), rendered by `ConditionSql` and AND-ed into the `WHERE` **ahead of the `GROUP BY`**, so
  `count` folds filtered rows. Every leaf `field` is checked against the relation's columns (unknown → 422
  `CONFIG_VALIDATION_FAILED`; D-S5, 2026-09-22); cost one zero-row probe per filtered call. 🔴 **The `filter` root must be
  a group** (2026-09-23; `/projection`, `/neighbors`, `/projection/multi` top-level and per-edge,
  `/traversal/recursive-paths`): a bare condition used to render `TRUE` and return every row (fail-open); now 422. An
  empty group and incomplete leaves are a deliberate no-op ([decision rules](../processor/spec-decision-rules.md)).
* **Audit** (LA-04): `link.projected`, `link.expanded`, `link.schema.inspected`, `geo.projected`, `geo.routes.projected`
  carry dataset, result size and `truncated`, best-effort (an audit failure never fails the query). Exclusion and reveal
  are audited server ops; only the browser-local canvas export emits nothing.
* **14 layouts and 8 canvas plugins**, each a G6 v5 built-in id. LA-09 added `fruchterman`, `dendrogram` and `fishbone`
  (same tree/forest gate), the `snapline` lens, and `bubble-sets`, which draws the same Louvain communities as the hull
  overlay — **mutually exclusive by construction** (bubble sets win).
* ⚠ **Refused on grounding (LA-09), with reasons:** `timebar` (needs `data` + `getTime`, duplicates the time column
  control); `history` (🔴 a second undo stack on Ctrl/Cmd+Z beside `undoPresentation`/`redoPresentation`);
  `contextmenu` (empty without a decided action set); `watermark` (content is a decision); `combo-force` is not a G6 v5
  id (nearest `combo-combined`); combos are a data-model change (`comboId`), not a plugin.
* **Super-node stand-ins** (LA-06): opt-in, off by default, labelled with the count; only nodes whose ONLY link is to the
  hub fold, so no path is hidden. Clicking opens it; toggling the control is the way back. 🔴 **A stand-in is not a
  record and never carries an `objectRef`**; it has its own `kind`, excluded from legend and tallies, and working-set
  tiles are measured before aggregation (demo: 159 marks drawn, 170 entities reported).
* ⛔ **Viewport culling and progressive edge loading REFUSED as premature** (the cap was 500 then, the edge array is
  weight-ordered, the targeted bottleneck was the pre-LA-05 rebuild). 🔴 Culling by filtering `data` would re-run a full
  layout on every pan. Cheap starting points if the cap rises: G6 `optimize-viewport-transform`, `auto-adapt-label`.
* 🔴 **An expand used to drop `truncated`** (LA-02: `mergeGraphs` returns bare `G6GraphData`); `expandNode` now carries
  it monotonically — only a fresh `run()` resets it.

### Settings documents

* **`PUT /settings/link-analysis` replaces the document** (decided 2026-10-12): a key absent from the body is written
  `null` = inherit the shipped default (never unbounded) — except limit keys, which since 2026-10-15 are KEPT when the
  body leaves them out (`SettingsRoutes.keepUnsentLimits`). The SPA sends every stated key back as read.
* **Settings ▸ Link Analysis pane** (`settings/link-analysis-settings.component`): four-eyes thresholds
  (`fourEyesBudgetAbove`, `fourEyesFanOutAbove`); `mergedDistinctCap` + `mergedDistinctCapInForce`; `seedByDistinctCap`
  only when the server reports it; since 2026-10-24 (`LA-GROW-EXCLUDE-RESIDUALS-1`) a warning per live auto-apply
  category list that would refuse every new Investigation (`problem` key of `GET /inv/exclusion-lists`).
  `maxSetBytes` (`max_set_bytes`, 2026-10-10): per sealed Working Set, default 64 MiB, 1 KiB..1 GiB, over it `413` naming
  the limit. `maxInvestigationBytes` (`max_investigation_bytes`): per-Investigation total of sealed sets (main + open
  Drafts), default 4 GiB, 1 MiB..1 TiB, `413` naming Investigation, total and budget. Both have fields shown only when
  the server reports the key. DR-U3 (2026-10-10, `LA-UI-SETTINGS-MASKING-1`): `maskingMode` (typed | all | none, blank =
  inherit, `maskingModeInForce`), `index.enabled` (inherit / on / off, neutral wording), `threads` 1..64, `queue`
  1..1000, `maxDiskBytes` 0..10^15 (0 = no limit), and the `graphRun` default budget `maxNodes`/`maxEdges`/`timeoutMs`
  (1..10,000,000; in-force values and ceilings from `GET /inv/graph/algorithms`, "-" if it fails). Read only:
  `entityTypesInForce`, `indexInForce`, `investigationStoreInForce` (`fs` | `db`, process-wide). A save keeps
  `keepVersions` and the graph-run pool keys as read, sends no `*InForce` key, omits a wholly blank `index`/`graphRun`
  block; the two selects are disabled (not hidden) without `canAuthorWorkbench`. Numeric limits moved to the limits
  section (`link-analysis-limits.component.ts`: Analysis = Space items, Host resources = install items read-only unless
  `canAdminister`, My browser = personal value + *Reset to Space value*).
* **Embed links settings** (2026-10-12): `embed.defaultTtlHours` / `maxTtlHours` (1..8760; default ≤ cap; blank cap =
  720), `refuseClassifications` (≤ 20 names of ≤ 64 chars), `embedInForce` shown, `embed.frameAncestors` (*Sites that
  may frame the viewer page*; in the form because a save that omitted it would wipe it). Client range check mirrors
  `SettingsRoutes.embed`; an all-blank block is omitted. The feature is OFF unless the host runs `-Dla.embed.enabled`
  (no status API).
* **Share (embed link) panel** (`LA-EMBED-SPA-1`, 2026-10-12; API and headers in
  [architecture-http-and-ui.md](architecture-http-and-ui.md) §6.2): `link-analysis-embed.component.ts` in the Investigation
  tab lists grants (`GET …/embeds`: label, status, expiry, views, scope, masking, Revoke behind a confirm; never a token);
  `link-analysis-embed-mint.dialog.ts` asks label, scope (`working_set` / `with_notes` / `full`), masking (the Space's mode
  or stricter, never looser), lifetime 1..`maxTtlHours`. Gate: `LensService.canShareInvestigation` AND the members
  route's `you` = lead. The token lives only in the dialog's `link` signal (`<origin>/embed/inv/<token>`), cleared on
  Done/destroy, never in a URL, storage or host state. **Viewer** (`la-app` `embed-view.page.ts`, `embed/inv/:token`,
  outside the guarded shell): read-only canvas (`layoutEmbedGraph`: one ring per hop; zoom, pan by drag or arrows,
  *Highlight*; ≤ 3000 links drawn, labels on small sets or the selection) and a searchable paged Working Set table (the
  accessible equivalent), notes and references per scope, all masked as returned; no chrome, no write control, no
  third-party call. After the first answer the address bar becomes `/embed/inv/shared` (`Location.replaceState`), so a
  reload says *open the original link again*; one neutral page for bad / expired / revoked, a separate *Could not reach
  the server* page for status 0 / 5xx; reads through a raw `HttpBackend` (no Authorization header, no interceptor sees
  the token). Closed 2026-10-14: framed only by the token's OWN Space allow-list; inlined Google `@font-face` stripped
  (system fonts, no `mat-icon`); token redacted from the host log for the page path; proxy-log redaction duty in
  [admin-guide-demo.md](admin-guide-demo.md); expiry pinned with an injected clock (`EmbedGrantsExpiryClockTest`).

### Patterns: temporal order, branching and the server half

* **Time order** (LA-14a): a step marked `afterPrevious` needs an event time strictly later than the previous step's,
  optionally within `maxGapHours`; time from an edge attribute column, `Date.parse` as the time filter. `layering-chain`
  and `pass-through` set it (48 h) in the constants AND all six authored TOON copies (authored packs merge over built-ins,
  so an untouched copy would reinstate the unordered motif). 🔴 Before, pass-through was a topology claim worded as a
  flow claim: 200 demo matches with no regard to time.
* ⛔ **A temporal motif FAILS CLOSED:** no time column → nothing and "choose a time column" (not "No matches"); an edge
  with missing or unparseable time is rejected.
* ⚠ **Only ordering survives the projection:** a timestamp arrives as a STRING (`CAST(col AS VARCHAR)`), and 🔴 an
  attribute column joins the `GROUP BY` fold key, so a timestamp de-folds the projection into one edge per instant —
  honest at demo cardinality only; the server half (below) handles scale.
* 🔴 `followsInTime` closed an LA-14a hole: an un-ordered hop recorded `NaN` and `t <= NaN` / `t - NaN > gap` are both
  false, so an unknown time counted as "in order". Pinned.
* **Branching** (LA-14b, browser, 2026-09-23; `branching-pattern-engine.ts` `matchBranchingPattern`): ordered **stages**,
  each `fan-in` / `fan-out` with a minimum of DISTINCT counterparties, optional `threshold` band (`min` inclusive, `max`
  exclusive — a reporting threshold is crossed AT its value), `windowHours`, `afterPrevious` / `maxGapHours`. It extends
  `matchPattern` (sharing `followsInTime`, `edgeTimeIndex`, `baseEdgeKind`). Ordering is per branch; a fan-in "arrives"
  when it reaches its minimum breadth.
* **Built-in branching packs** (constant-only, reach every Space via the PACK-1 merge): **Structuring** = fan-in ≥ 5 legs
  in `900 ≤ AMOUNT < 1000` within 24 h → fan-out ≥ 2 → re-converge ≥ 2, each within 48 h (🔴 a band, not "under 1,000":
  an open bound made every busy account a collector; demo `mule_structuring`, 1,928 transfers / 170 accounts → exactly
  the planted ring in ~9 ms). 2026-10-05 (`LA-INVESTIGATION-OPS-DEFERRED-1`): `layering-network` (fan-out ≥ 3 within 24 h
  → re-converge ≥ 3 within 48 h) and `circular-financing` (two split-and-merge rounds, each within 48 h).
* **Closure** (LA-CIRCFIN-2, 2026-10-05): `BranchStage.closesToStart`, valid ONLY on the last stage, a `fan-in` after ≥ 1
  other stage (else SPA refuses, server 422s); its collector must be in the ORIGIN layer (`layers[0]`) — the one place
  "never revisit a used node" is lifted. `circular-financing` sets it (a proven round trip). Seams: TS
  `matchBranchingPattern`; Java `TemporalMotifMatcher` (`platform/inspecto-graph`) + `PatternQueryCompiler` (closing
  stage prunes to legs landing on stage 0's anchor/senders); authored packs carry an optional `closesToStart` column.
  Parity: the `closure` section of `branching-parity.fixture.json` (`branching-parity.spec.ts`,
  `ControlApiInvPatternTest`). Calls: a stage flag, not a new `shape`; origin = first layer (works for a fan-in start); a
  rotated start is rejected by time; `circular-flow` keeps routing to Cycles.
* ⛔ **It REFUSES rather than answering "none":** no time column · no link with the threshold attribute · no link passing
  the threshold (a `≥ 5,000` view removed every leg before the matcher ran) · graph over the analysis cap (a refusal,
  never thrown). Work is budgeted (`BRANCHING_WORK_BUDGET`), matches capped, both surfacing `truncated`.
* ⚠ An authored branching pack is one flat TOON row per stage
  (`stages[n]{shape,minBranches,edgeKind,nodeKind,windowHours,afterPrevious,maxGapHours,thresholdAttr,thresholdMin,thresholdMax}`;
  blank = wildcard; ANY unusable row drops the pack); no TOON copy is seeded, so that shape has not been through
  `ConfigCodec`'s round-trip.
* **Server half** (LA-14b, 2026-09-23): `POST /inv/pattern/branching` (`PatternRoutes` → `PatternQueryCompiler` →
  `TemporalMotifMatcher`). 🔴 **Why:** the projection is capped (4,000 rows `cnt DESC`, 1,000 nodes) and structuring legs
  are small one-offs that sort LAST and are cut FIRST. SQL pushes each stage's kind, band and the `filter` into the
  `WHERE` of the whole Dataset and prunes with NECESSARY conditions only; the windowed search runs in Java (a
  line-for-line port of the TS matcher). Node keys normalised IN SQL with `normalizeEntityKey`'s rule. Fences: bound
  values, identifiers checked (threshold attrs too), R3 via `InvRoutes.relationFor`, ≤ 100,000 legs (`legCapped` +
  `truncated`), 5 s timeout, the work budget, match limit 200 (≤ 1,000). Refusals are 200 with `refusal` in the
  browser's words; audited `LINK_PATTERN_MATCHED`; read-shaped exemption. ⛔ **Parity is the contract**
  (`branching-parity.fixture.json` asserted by both sides). **Run on server** shows only when the graph is truncated and
  an edge mapping is loaded; the answer merges onto the working set (`branchingResultToGraph`). Deliberate differences:
  no node cap server-side; a `nodeKind` other than `entity` matches nothing. The motif matcher's platform split:
  [architecture-la-core.md](architecture-la-core.md) §5.

### Value Measures and standing detection

* **`GET /inv/value-measures`** (LA-18, 2026-09-30; `ValueMeasures`): entities breaching a named Measure's thresholds —
  `passThrough`, `velocity`, `timeToCashOut`, `cashOutConcentration`, `structuring`, `benefitTransfer`, plus non-alertable
  `valueWeightedLinks` — in a `[from, to)` window of ≤ 31 days (offsets normalised to UTC). ⛔ No view filter is accepted
  (a `≥ 5,000` view cannot hide structuring); `valueMeasureQuery` never sends one. Rolling `last: <N>h|<N>d` (exactly one
  of it or `from`/`to`; same cap), stored relative, resolved at every read, bind and sweep in **UTC** (DuckDB
  `TimeZone=UTC`; a naive `timeCol` assumed UTC); the answer's `window` states what it read. `cashOutConcentration`
  takes an optional `agentList` (Entity Type `agent`; other type 422, unknown 404, retired 409) restricting answered
  agents to live exact members under the list's sealed normaliser (denominator stays all cash-out); a masked list
  answers its own tokens. An Alert Rule binds one through `POST /inv/investigations/{id}/alert-rules` with
  `valueMeasure:{…}` (one Alert per rule when ≥ 1 entity breaches). Definitions and deviations: archived backlog plan
  §2.6.1.
* **SPA:** the Investigation pane's *Value Measures* panel and (2026-10-12) an Analysis catalogue entry
  (`link-analysis-value-measures.component`, `value-measures.ts`): Dataset + role autocomplete, blank thresholds = server
  default re-filled with values in force, `truncated` / `unvalued` as counts, rolling *Or the last* window
  (`valueMeasureWindowIssue` refuses both-or-neither), the resolved `[from, to)` shown, *Agent list* autocomplete; *Watch*
  only over the open Investigation's Dataset and roles, binding the answered `measure` block verbatim (a rolling window
  stays rolling).
* **Standing detection** (LA-LIVE-DETECTION-1 LD-1..LD-7, 2026-10-04/05; D-LD1 = option A). A value-measure rule reads the
  live Dataset on every sweep and a sweep has no caller, so it is **not evaluated until the owner enables it**
  (`POST …/standing-detection {rule}`, `canAuthorAlertRules`, owner only, bound value-measure rule only). Enable records
  the owner's authority under `standing` (id, capabilities, data scopes, IdP attributes, Dataset, masking snapshot);
  `StandingDetection` re-decides before every read (principal, refusal codes and the `la.detect` Job:
  [architecture-la-core.md](architecture-la-core.md) §7): owner id or a **`user`** share still grants view on the Dataset's CURRENT
  envelope (`ComponentAccess.canViewAs`; **a role share cannot be re-resolved off a request, so role-only is refused**;
  `canConfigureAccess` never honoured), owner still a lead, masking not **tightened**, PDP (synthetic Subject,
  `RowScope.visibleAs`) does not DENY. Every refusal is an EMPTY `Reading` with a code and an audit event
  (`LINK_STANDING_DETECTION_ENABLED / _SWEPT / _REFUSED`, aggregate only); undoing the cause resumes the sweep;
  re-enabling re-snapshots. ⚠ Residual: the synthetic Subject's grants are the enable-time snapshot with no role names,
  so a DENY keyed on `subject.roles` cannot be reproduced.
* **Routes beside it:** `GET /inv/investigations/{id}/alert-rules` (`InvestigationMeasureRoutes.listBound`; read gate
  `openForRead`, 404 non-member, 503 no write root or alert engine) lists armed bound rules with `valueMeasure`, `edited`
  (hash mismatch: a sweep refuses it) and `standingDetection {enabled, principal?, enabledAt?, dataset?}` (never
  capabilities, masking or a value); `DELETE …/standing-detection/{rule}` (idempotent; rule stays bound, sweep refuses
  `NOT_ENABLED`); `PUT …/alert-rules/{rule}` (edit in place; new hash; **recorded authority dropped**, owner re-enables);
  `GET /inv/investigation-templates` (caller's own, newest first; sharing not built, D-LD6). `la.detect` (no parameters,
  `requires: [alerts]`, Signal `la.detect.completed` with a count and Alert Rule names) calls
  `AlertAccess.evaluateInvestigationRules()`; fails the Run closed without `alerts` or the module; a dry run evaluates
  nothing. Wire shapes and D-LD2..D-LD18 ("decided by the assistant, pending operator confirmation"):
  [`la-live-detection-design.md`](../../../../archived-documents/plans-archive/la-live-detection-design.md) §8, §9.
* **SPA (LD-6, LD-7):** *Enable standing detection* (`LinkAnalysisStandingDetectionComponent`, shown on
  `canAuthorAlertRules`; the server's 403 is the owner check); sweep principal, masking snapshot, enabled time; refusal
  codes in plain language (`STANDING_REFUSAL_HELP`). *Monitoring* (`LinkAnalysisStandingMonitorComponent`) counts
  `_SWEPT` / `_REFUSED` from `GET /events/search` (counts and codes only). `LinkAnalysisBoundRulesComponent` shows the
  bound list (state survives reload) and edits severity, plus the threshold of a Working Set rule (name never changes;
  saving says the authority was dropped). One Enable button per rule.

### Schema overlap and relation SQL

* **`POST /inv/schema/overlap-profile`** (LA-15) measures what naming only guesses: Jaccard of two columns' value sets by
  inclusion–exclusion over `APPROX_COUNT_DISTINCT` (`|A∩B| = |A|+|B|−|A∪B|`) — no values into the JVM, no cross join.
  Pair count is the only quadratic axis and is capped, with the true total reported.
* 🔴 **A relation must be passed as `relationSql`, never inlined as a subquery.** `QueryExecutor.run` registers the
  relation BEFORE sealing the sandbox, the only place file-reading SQL may run; an inlined subquery works on a
  VALUES-backed fixture and is refused against a real Parquet Dataset.
* **A recursive CTE survives `QueryExecutor`'s derived-table wrap** (`QueryExecutorRecursiveCteTest`, D-S2). 🔴 The
  executor's `LIMIT n+1` is OUTSIDE the derived table, so it does not bound the recursion; a traversal fence must live
  INSIDE it (on a cyclic graph the in-recursion depth predicate is the only stop).

### Saved views, legends and node kinds

* **A saved view says what it is on every surface** (D-S1): it stores the QUERY and presentation, never rows, and no read
  is version-addressable. `SAVED_VIEW_NOT_EVIDENCE` is stated in the saved-views menu, on the dashboard widget (🔴 the
  surface that most needed it: a tile reads as a fixed report and is a live re-projection) and in the Attach-to-Case
  dialog. A LABEL, not a guarantee; sealed snapshots (below) are the evidence path.
* **The saved-view widget** (R3-04, 2026-09-26): `LinkViewWidgetComponent` (tile + Menu item) mounts
  `<inspecto-link-analysis-legend>` fed by the studio's `legendItemsFor` / `legendEdgeKindsFor` (colour =
  `display.nodeColors[kind]` else `nodeColor(kind)`, super-nodes never counted), open unless `view.legend: false`; the
  viewer's toggle is never saved. `[showDescription]="true"` (set by `MenuArtifactComponent`) renders the view's
  `description` through `<app-dashboard-header>`; a tile leaves it off.
* 🔴 **Node kinds are never derived from the profile or id prefixes.** A single `query.projection` stamps every node
  `kind: 'entity'`; `profile: telecom` shapes tile labels, measure / time columns and suggested tools only. The only
  kind-bearing path is LA-08's `query.multi.nodes[]`: one `MultiNodeMapping {dataset, idColumn, category}` per kind,
  `category` a constant (no per-row kind column), so the data needs one Dataset per entity kind plus edge Datasets.
  **Category colours** (2026-09-27): `projectMultiResult` stamps `data.color` per category from
  `CHART_CATEGORICAL_NEUTRAL` in first-seen order (a saved `display.nodeColors` still wins). The telco
  `simbox_ring_jeddah` / `fraud_entity_graph` views moved to `entity-projection-multi` (one node Dataset per kind, one
  edge Dataset per link kind, from the same rows): four colours, four-row legend.

### Cases: save, attach and create in place

* **Ops absent vs lookup failed are two states.** The Case picker fills from `GET /objects?type=CASE`. Since 2026-11-14
  (`LA-BASIC-PLACEHOLDER-CASE-1`) there are no placeholder Cases: ops-absent shows "Needs Case management (Standard and
  above).", Attach to Case is disabled, and the server refuses an attach without Case management (503) or to an absent /
  invisible Case (404); a real error clears the list and shows an `<inspecto-alert variant="error">`. 🔴 Attaching
  evidence to a fabricated Case id is a silent wrong answer. The states live in ONE place,
  `LinkAnalysisCaseFieldComponent`.
* **Save and attach are ONE action, the Case optional** (operator, 2026-09-22): **Save this analysis** = name,
  description, frozen-content summary, optional Case. Save stays enabled when the lookup fails (the analysis is kept
  unattached); the picker offers nothing then. **Attach-to-Case** survives for an analysis saved without one; there a
  Case is required and a `caseId` reaching the form while the lookup is errored is refused.
* **A Case page opens Link Analysis on itself:** an `<a>` in `object-detail`'s header (CASE only) →
  `/studio/link-analysis?case=<id>`, which pre-selects the Case in the save dialog. ⚠ The param is deliberately NOT
  stripped (the `?open=` rule): it survives reload and bookmark.
* **A new Case minted from graph nodes** (`LA-CASE-CREATE-IN-PLACE-1`, 2026-09-23): *A new Case, from graph nodes* asks a
  title and a checkbox list pre-ticked from the canvas emphasis (filter at ≥ 8). ⛔ *A case CONTAINS its members*: zero
  nodes is refused on screen and by the server.
  - **Object type INCIDENT** (a Case's Contents are Incidents, GLOSSARY §9; a new `ENTITY` type would have to teach merge,
    split, Case Rules, workflow, FindingsSpec). Titled with the raw spelling, described *Raised from Link Analysis…*. ⚠
    They land in the Incidents inbox.
  - **Identity = `entityKey` + `entityDataset`** (node id, `EntityMember` accepts `^[a-z][a-z0-9_]{0,31}:.+`; Dataset =
    the node's `provenance`, else the projected one). The same pair REUSES the object; an invisible match is not reused
    (existence-hiding). `ObjectStore.findByAttributes` (portable `LIKE` re-checked exactly) THROWS on a store error (an
    empty answer would mean a silent duplicate). Eligible nodes: `case-members.ts` `caseMemberCandidates`.
  - **One route:** `POST /cases/from-entities` (`canManageIncidents`), because `POST /objects` needs an existing link
    target. All refusals checked before the first write; writes under compensation
    (`ObjectService.openCaseFromEntities` removes what it created on failure; reused objects untouched). Basic: 503 and the
    option disabled with the reason.
  - **Order seal → create the Case → attach.** A refused Case leaves the snapshot sealed and the dialog open with the seal
    remembered; a failed attach flips to the existing-Case path with the new Case picked, so a retry never opens a second.

### Entity ids: normalised keys and typed ids

* **D-S4 (2026-09-23):** every id goes through `normalizeEntityKey` (`inspecto/graph/entity-key.ts`: case-fold, collapse
  internal whitespace, strip trailing punctuation, trim), so `ACME Ltd`, `acme ltd`, ` Acme  Ltd.` are one node with
  summed counts; the label stays the first raw spelling and each node carries `spellings`. ⚠ Pre-D-S4 saved views and
  exports no longer match. `splitIdentityGroups` uses the same key and reports a group when raw spellings exceed keys
  (shown in the working set; the hint says they are counted as ONE entity). `tools/check-split-identity-fixture.mjs`
  mirrors the key in Node — change both together. Comparison is scoped (`entity:person:bob` ≠ `entity:account:bob`).
  🔴 **It reads ZERO on every shipped dataset, correctly** (measured 2026-09-22, 24 columns in 4 Spaces; the generator
  emits canonical literals); a positive control collapses 6 spellings to 2. 🔴 **Three mint sites**, not two:
  `geo-analysis.ts` `coLocations` / `coLocationGraph` normalises too (it used to leave dangling edges).
* **Typed node ids (LA-17 D-M6, 2026-09-27):** a column whose registry `classification` is claimed by an in-force Entity
  Type mints `<type>:<key>` through the type's normaliser (`msisdn` `0044 78` → `msisdn:+4478`); others keep
  `entity:<key>`. ⛔ The SPA never guesses: the server sends `columnTypes: {col: {id, normaliser}}` on `/inv/projection`
  and `…/neighbors` (always present) and `entityType` / `sourceType` / `targetType` on `/projection/multi` rows. ONE mint,
  `endpointId(mapping, side, value)` (`entity-projection.ts`); typed mappings are stamped on the graph (`idMappings`) and
  remembered on `lastRun`, so the Investigation binding, traversal, pattern and brush mint the same ids. A value without
  its column goes through `resolveEntityId` / `entityIdCandidates`. Server ids are unaffected (evaluator, Working Set and
  `excludeBy` compare raw values / per-normaliser keys; only `EntityMember` reads node ids). ⚠ No back-compat for
  `entity:<value>` of a now-typed column. The Geo co-location graph stays untyped.

### Server traversal and multi-projection

* **`POST /inv/traversal/recursive-paths`** (LA-11, 2026-09-23): one recursive CTE returns simple paths from `startNode`
  (optionally to `targetNode`), with `direction`, `weightCol`, a validated `filter`, `temporalConstraint`. Every fence is
  INSIDE the recursion: depth a bound `?`, cycles refused by `list_contains`, edge yield a bound `LIMIT` per level
  (`edgeYieldCapped` + `truncated`), a route-local `SqlSandboxPolicy` timeout via `QueryExecutor.run(Request, policy)`.
  Current depth / timeout / path defaults are configuration ([architecture-la-core.md](architecture-la-core.md) §9; the 2026-09-23
  defaults were depth 6 / cap 10 / 5 s). Audited `LINK_TRAVERSED`. Body names `dataset`/`sourceCol`/`targetCol`. SPA
  *Find paths (server)*: From (required), To (blank = any), max hops, direction; sends the node's FIRST RAW SPELLING (the
  server compares `CAST(col AS VARCHAR)` exactly) plus the pushed `filter`; `recursivePathsToGraph` mints hops through
  `entityId()`, reuses working-set links and ADDS nodes / links beyond the slice. ⚠ No `depthUsed`: the pane shows
  `fences.maxDepth`. ⚠ A folded node starts from its first spelling only.
* **`POST /inv/projection/multi` as its own GraphSource** (LA-08 SPA, 2026-09-23): `entity-projection-multi`. Node
  mappings (Dataset · id · label? · category?) and edge mappings (Dataset · source · target · type?); a half-filled row is
  refused, > 16 refused client-side. Ids minted UNSCOPED, so one key from several Datasets is ONE node with
  `data.provenance` and `data.spellings`; edges fold across Datasets. A 404 (unknown OR not viewable) refuses the WHOLE
  query. Since 2026-10-04 (`LA-SPA-OWED-SURFACES-1`): *Attribute columns* per row (node attrs merge, first mapping wins)
  and a per-edge *Filter* (the request filter still applies to every edge mapping). **Expand** on this source:
  `MultiProjectionGraphSource.expand` reads each edge mapping's one-hop neighbourhood of the node's raw spellings (≤ 8 per
  mapping) through `/inv/projection/neighbors` and folds with `projectMultiResult`, so expanded edges merge. The older
  `entity-projection` multi-mapping path (N × `/inv/projection`, type-scoped ids) coexists.
