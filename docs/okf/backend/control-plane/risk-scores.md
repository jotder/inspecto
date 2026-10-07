---
type: Concept
title: Risk Scores — weighted-factor scoring per entity
description: The risk-score component kind, the risk.score Job that writes the scores Dataset, GET /risk-scores/{model}/{entityKey}, and Incident priority through a per-entity Alert Rule.
resource: platform/inspecto-engine/src/main/java/com/gamma/risk/RiskScoreEvaluator.java
tags: [control-plane, risk-score, job, alert-rule, incident, assurance, ws-22]
timestamp: 2026-09-27T00:00:00Z
---

# Risk Scores

> **Module (MODULE-REORG-1 P7, 2026-10-07).** Risk Scores are the optional `inspecto-scoring` module (id `scoring`, package `com.gamma.risk`, from Professional up) — a deliberate behaviour change: **Personal no longer ships them**. The module holds `RiskScoreModel`/`RiskScorer`/`RiskScoreEvaluator`, the `risk.score` Job Type (`JobTypeProvider`), `RiskScoreRoutes` (feature id `scoring`) and `RiskScoreKindValidator` (the `ComponentKindValidator` SPI the component save gate loops). Without it `/risk-scores*` answers 503 (`AbsentRiskScoreRoutes`), `/bootstrap` reports `features.scoring=false`, and a `risk-score` component is accepted as opaque config. The core keeps `PendingAlertRules` (reading only the naming/ownership facts in `com.gamma.alert.RiskScoreOutputs`) and the masking resolver `com.gamma.mask.EvidenceMasker`; the `WatchListFeed` seam moved to `inspecto-entity-list` (`com.gamma.entitylist`), so the dependency is scoring → entity-list.

A **Risk Score** (ASSURE-RISK-SCORE-1, WS-22; term chosen by operator decision D-P1) is an explainable
0–100 number per entity — subscriber, account, device, SIM, dealer, channel, partner, or a free-form
entity type. It is **computed on the server by a Job**, never by a Step Processor (the operator's hold on
new Step Processors stands). The Link Analysis `suspicionScore` is a different thing: a client-side graph
centrality composite. Keep the two apart.

## The model — the `risk-score` component kind

It is stored at `registry/risk-scores/<id>.toon` through `ComponentStore` / `ComponentRoutes`, so history,
diff, restore and the maker-checker hold come with the generic component routes. It is parsed and
validated by `com.gamma.risk.RiskScoreModel`.

The scores Datasets are **always** `risk_scores_<id>` and `risk_scores_<id>_latest`. The name is derived,
never authored: sending `scoresDataset` is a 422. A model id is letters, digits and `_` only, so the mapping
is injective (`a-b` and `a_b` cannot share an output).

| Key | Meaning |
|---|---|
| `entityType` | `subscriber` · `account` · `device` · `sim` · `dealer` · `channel` · `partner`, or any plain token |
| `highThreshold` | a score at or above this is `high` (in (0, 100]) |
| `dataScope` | optional; a data-scoped caller must hold it to read a score |
| `watchList` | optional `{list, ttlHours}`: feed every high entity to a `watch` Entity List for 1..24 h ([Entity Lists](entity-lists.md)) |
| `factors[]` | `id`, `label?`, `dataset`, `key`, `measure`, `filters?`, `weight`, `cap?`, `evidence?` |

A factor's **indicator** is a Measure (`count` or `agg(field)`, with the Measure grammar's `filters`) over
one declared Dataset, grouped by that Dataset's entity-key column `key`. It is compiled by
`MeasureCompiler`, the same guarded compiler `/bi/query` and the Alert Rule probe use. There is **no raw
SQL key**, and an unknown key is refused.

### Save-time gates (fail closed, 422)

1. `ComponentRoutes.validateKind` → `RiskScoreModel.fromMap`. It checks the structure and refuses unknown
   keys. Weights and caps must be finite numbers (a numeric string is accepted). Every factor's value query
   and evidence query is **compiled** there, so an unknown aggregation, an unknown filter operator or an
   unsafe identifier fails the save.
2. `RiskScoreRoutes.requireStorable`, which needs the Space:
   - Every column a factor names (key, measure field, filter fields, evidence) must be in its Dataset's
     Schema, as `DatasetMeasureProbe.columns` reads it. A Dataset whose Schema cannot be read refuses the
     save. This is the same rule as the Alert Rule `by` check.
   - The derived output names must collide with nothing. The save is refused for a directory of that name
     under the data root that this model did not create, a Dataset of that id over another store, or **any**
     registered Dataset whose `physicalRef` is that name. The one exception is a Dataset over a store that is
     already this model's own output: the documented Alert Rule Dataset over `_latest` reads it and never
     collides.
3. Then `PendingChanges.hold`, the maker-checker funnel.

🔴 **The `risk_scores_` prefix is reserved.** A `dataset` or `sink` whose id, or whose store (`physicalRef` /
`store` / `output_store` / `path` / `sourceName`), starts with `risk_scores_` is refused (422). The match is
case-insensitive and made on the normalised first path segment (`RiskScoreRoutes.requireNotReserved`, run by
every writer). The one exception is the documented Dataset whose id and `physicalRef` are both
`risk_scores_<model>_latest` over a saved model.

**Every writer runs gates 1 and 2.** `ComponentRoutes.validateKind(api, …)` is called by the component
route and by both bulk writers (`BundleRoutes`, `BiTemplates`), so a bundle cannot plant a model the
authoring route refuses.

🔴 **The writer never deletes what it does not own.** Each scores directory carries a
`.risk-score-output` marker naming its model. `RiskScoreEvaluator.write` creates the directory with the
marker, or accepts one whose marker names this model. Anything else fails the run, untouched. The
`_latest` swap removes only `scores-*.parquet`. Before this fix, an authored `scoresDataset: orders`
wiped a real Dataset's files, and two models sharing an output erased each other.

## The `risk.score` Job

`RiskScoreJobType` takes one parameter, `model`, and emits `risk.score.produced`. It reads the registry
from `SpaceConfigRoot` and writes under the Space `dataDir`. Trigger it on a `cron` schedule, or with
`on_pipeline:` on the pipeline that lands its Datasets.

The math is in `RiskScorer` and is pure:

- `contribution = weight × indicator`, then the cap bounds the **absolute** contribution: at most `cap`, and
  at least `-cap`. Negative weights are allowed and act as protective factors. The cap never applies to the
  raw indicator.
- A **missing** indicator is `0` and is flagged `missing: true`. This covers an entity with no rows for the
  factor, or a NULL aggregate.
- `score = Σ contribution`, clamped to `[0, 100]`.
- `high = score ≥ highThreshold`.

The Job scores every entity that **any** factor names. It writes two outputs:

- `<dataDir>/risk_scores_<id>/scores-<ts>-<runId>.parquet` is the **history**: one row per entity per run.
- `<dataDir>/risk_scores_<id>_latest/` is swapped to this run's rows alone. This is the relation an Alert
  Rule watches.

Both files are written as `*.tmp` and revealed by an atomic move.

The columns are `model, entity_type, entity_key, score, high, factors (JSON), model_version, run_id,
scored_at`. Each element of `factors` is `{indicator, label?, value, missing, weight, cap?, capped,
contribution, evidence[]}`, with up to 3 evidence rows per entity. `model_version` is a 12-hex SHA-256 of
the stored model, excluding the `name`/`owner`/`shares` envelope.

**Every score is reproducible from its factors.** `RiskScorer.recompute(factors)` re-derives each
contribution from `weight`, `value` and `cap`; it does not trust the stored `contribution`. The Job test
and the HTTP test both assert that it matches the stored score.

⚠ **A query failure carries a generic message.** A DuckDB cast error quotes the cell it could not convert,
and a Job failure's message reaches the run ledger, the logs and the UI. So `RiskScoreEvaluator` rethrows
with the model, the factor, the Dataset and the error class only. It keeps neither the message nor the
cause.

**Entity cap (operator decision 2026-10-06: configurable + fail).** A factor that names more entities than the cap
**fails the run** — never a partial run; the message names the cap and how to raise it. The cap is the model's
optional `maxEntities` (a whole number 1..2 000 000, `RiskScoreModel.MAX_ENTITIES_CEILING`; anything else is refused
at save, 422), else the system default `-Drisk.score.maxEntities` (default 200 000; a malformed or non-positive value
keeps 200 000 — the cap can never be disabled). `RiskScoreEvaluator.maxEntities(model)` is the one resolver.
Evidence reads at most 20 000 rows per factor, and the run log reports `evidenceTruncated` when that cap
is hit.

## Incident priority — through the existing Alert Rule machinery

No new alerting path exists. To raise Incidents, declare the `_latest` relation as a Dataset and point one
per-entity Alert Rule at it. Use `by: [model, entity_key]` and set the threshold to the model's
`highThreshold`:

```
POST /components/dataset     {"id": "risk_scores_subs_latest", "physicalRef": "risk_scores_subs_latest"}
POST /components/alert-rule  {"id": "high-risk-subscriber", "dataset": "risk_scores_subs_latest",
                              "measure": "max(score)", "by": ["model", "entity_key"],
                              "comparator": "gte", "threshold": 50, "severity": "CRITICAL",
                              "description": "High Risk Score"}
```

`CRITICAL` or `ERROR` is what opens an Incident. Each high entity gets one Alert and one Incident, with
the attributes `key.model` and `key.entity_key`. When the score falls back below the threshold on the next
run, the Alert heals, as every per-entity Alert does. That healing is why the rule watches `_latest` and
not the history. `RiskScoreAlertTest` proves the flow end to end over real DuckDB.

## The read route — `GET /risk-scores/{model}/{entityKey}`

`RiskScoreRoutes` returns the entity's latest score with its factors. The latest row is ordered by
`scored_at`, then `run_id`. The gates are:

- `canWorkIncidents`.
- Then an **existence-hiding 404** for each of these cases, all indistinguishable: an unknown model; a
  stored model that no longer parses (a hand edit, or a model written before a rule tightened); a
  model whose `dataScope` the caller's data scopes lack; a `RowScope` DENY on the resource kind
  `risk-score`; or no score for the entity.

**Data scopes are stricter than for objects.** A data-scoped caller reads only a model whose `dataScope`
it holds. An **unscoped** model is readable by unscoped callers alone, because its evidence is raw source
rows. The entity key is a **bound parameter**. It is never spliced into SQL and never resolved as a path.
Real-HTTP coverage with an armed Subject is in `ControlApiRiskScoreTest`.

⚠ "Dataset data scopes" do not exist as a separate mechanism. `Subject.dataScopes` filters case-typed
objects (SEC-7d), and nothing scopes Dataset rows. The route applies that rule to the model's optional
`dataScope`.

## The preview route — `POST /risk-scores/preview` (slice S3, 2026-10-06)

Scores ONE entity now and writes nothing: no scores Dataset, no `_latest`, no Signal, no watch-list entry
(D-RP5, operator 2026-10-06). Body: exactly one of `model` (a saved id) or `content` (an unsaved model), plus
`entityKey` (1..256 chars). Gates (D-RP4): `canWorkIncidents` at the route; unsaved `content` additionally needs
`canAuthorWorkbench` (403), runs `fromMap` + `requireStorable` and answers their message verbatim as 422. A saved
model that is unknown, unparseable or outside the caller's data scopes is 404 as on the GET; content outside the
caller's scopes is 403. No write/data root is 503.

**Bounds.** `RiskScoreModel.forEntity` adds a `key = entityKey` filter to every factor (a quoted typed literal
via `MeasureCompiler`, like any authored filter value), so only that entity's rows are read.
`RiskScoreEvaluator.preview` then allows at most ONE indicator row per factor and `EVIDENCE_PER_ENTITY` (3)
evidence rows, under `PREVIEW_POLICY` (512 MB, 2 threads, 10 s). An entity no factor names comes back with
`found: false` and every indicator missing. Evidence is masked exactly as a Job run masks it.

**Entity key (D-P8 mask on read, D-RP6).** The response key is the Space `masked:<16 hex>` token unless the caller
holds `canRevealLinkEntities` (`keyMasked` says which). The GET route does the same (2026-10-06), through one
helper, `RiskScoreRoutes.putEntityKey`.

**Import-capability scan (operator, 2026-10-06).** `ImportCapabilityGuardTest` lists `POST /risk-scores/preview` on
its `ACTS` exemptions — a read-only POST (keys stay out of URLs) that writes nothing — so its `canWorkIncidents` gate
is not read as a stricter write route of the `risk-score` kind. The kind's import capability is unchanged.

⚠ **Truncation fix found here.** The indicator spec carried its own `LIMIT` equal to the executor cap, so the
"refusing to score a subset" check could never fire: a Job run past the cap silently scored a subset. The spec now
asks for cap + 1 rows, so the refusal fires. The cap itself is configurable — see *Entity cap* under the Job.

### Masking — evidence at WRITE time, the key on READ (operator decisions 2026-09-27, 2026-10-06)

Operator decision, 2026-09-27, under the standing "go with recommendations":

- **The entity key is masked on READ (D-P8, operator 2026-10-06).** It is stored raw (the GET looks it up as a
  bound parameter). `GET /risk-scores/{model}/{entityKey}` and the preview return it as the Space `masked:` token —
  the same token write-time evidence carries for that value — with `keyMasked: true`, unless the caller holds
  `canRevealLinkEntities` (or there is no Subject, auth off). The key in the request URL is the caller's own input.
  Unmasking the key never unmasks evidence. (Until 2026-10-06 the key was raw, like Alert and Incident keys.)
- **Classified evidence is masked at write time.** It is the widening: an Incident carries only a key and a
  value, while evidence copies source rows. The `risk.score` Job (`EvidenceMasker`, applied inside
  `RiskScoreEvaluator.evaluate`) stores the token and never the raw value, for any evidence column whose
  Dataset registry `columns[].classification` is `MSISDN`, `IMSI`, `ACCOUNT` or `PII` (case-insensitive).
  No reader can see such a value raw: not the read route, an Alert or Incident, a Widget, or the DB browser
  over `risk_scores_<id>(_latest)`.

🔴 The first version masked only in the GET route. The Job wrote raw evidence into the Dataset, and every
other reader bypassed the route.

- **Token.** `masked:<16 hex>`, an HMAC-SHA256 under the Space's key
  `<config root>.secrets/.risk-score-mask.key`. That is the same secrets directory as the Pending Change
  key. `SpaceSecretKeys` gives both keys one creation rule: `CREATE_NEW` first-writer-wins, owner-only
  (POSIX `rw-------` or a one-entry ACL), and a read retry. `BackupTask` skips `*.secrets`, `PathJail`
  refuses to resolve into it, and `.gitignore` ignores it. No route serves it.
- ⚠ **Tokens are deterministic per Space, across models.** The same value masks to the same token in every
  model, so tokens *link* records. That is useful for correlation, and it is a disclosure.
- ⚠ **A leak of that one key exposes every masked value.** Anyone holding it can enumerate the value space,
  a phone-number range for instance, and invert the tokens.
- Masking touches no number, so every score stays recomputable.
- An evidence value written before a column was classified stays raw. Re-run the Job to rewrite `_latest`.
  History files keep what they were written with.
- A column the registry leaves unclassified is not masked.

⛔ **There is no reveal.** Link Analysis's audited reveal (`EntityMasking`, `canRevealLinkEntities`) is bound
to an Investigation in the optional `inspecto-geo-link` module. A masked evidence value simply stays masked.

## UI

`inspecto-risk-score-panel` (`inspecto/components/`) shows the score and one contribution bar per factor.
Each bar is relative to the largest contribution, and missing and capped factors are named. It appears in
two places:

- On an Incident's detail view, whenever the Incident's attributes carry `key.model` and `key.entity_key`.
- In the Link Analysis toolbox's scoring section, once a model id is entered. Each row's **Factors** button
  uses the node id as the entity key.

A 404 renders nothing, except on the Risk Scores pane's *Latest score of an entity* lookup, which sets
`explainAbsence`: there a 404 says no stored score exists yet (the risk.score Job has not scored the entity; use
Preview) and any other failure renders an error alert (2026-10-06).

**The Risk Scores pane (`/risk-scores`, slice S1, 2026-10-06).** `modules/admin/risk-scores/` is a read-only
list + detail over `GET /components/risk-score` (D-RP1: its own admin route, nav item under Operations). The
pure view model is `inspecto/risk/risk-score-view.ts` (`riskModelView`): identity, threshold, scope, Watch
List, retention, one row per factor (dataset, key, measure, filter count, weight, cap, evidence) and the two
derived Datasets, never authorable. A model with no factors is flagged *Invalid*. **History** opens
`ComponentHistoryDialog` (restore reloads the list). An entity lookup feeds the existing panel. There is no
create/edit in the S1 view itself. **Preview (S3, 2026-10-06):** the detail's *Preview* button posts the saved model and the
typed key to `POST /risk-scores/preview` and renders the result through the same panel (`preset` input, no
load), labelled *preview, not saved*.

**Create / edit (slice S2a, 2026-10-06).** *New Risk Score* (page header) and *Edit* (detail) are
`risk-score-actions.component.ts`, gated on `canAuthorWorkbench` (D-RP4). They open `risk-score-form.dialog.ts`: one
`<inspecto-schema-form>` over the flat fields (`riskScoreAttributes` in `inspecto/risk/risk-score-form.ts`, with
`watchList` and the `retainDays`/`retainRuns` choice flattened) plus the Factor card editor
(`risk-score-factors.component.ts`, D-RP7 (b) — the condition-group editor authors nested AND/OR the model cannot
store). The Dataset picker offers only Datasets whose columns resolve (`DatasetRowsService.columns`, D-RP8); key,
filter and evidence pickers list that Dataset's columns. Save is the generic component CRUD: `POST` on create, `PUT`
with `If-Match` from a fresh `GET` on edit. Keys the form does not author (`name`, `owner`, `shares`) ride through.
A 422 is shown verbatim in a banner and placed by `mapRiskRefusal`: `risk-score.factors[i].<field>` marks row `i`
(the server's 0-based index) and that control; `risk-score.<key>` marks the top-level field. Schema and
output-collision refusals name no path and stay in the banner. No rule is duplicated client-side beyond
required/number checks.

**Held changes, delete, restore and the Alert Rule link (slice S2b, 2026-10-06).** A save, delete or restore the
Approval Policy holds answers `202 {status: pending, pendingChange}`, which `HttpClient` delivers as a success —
every caller checks `status === 'pending'` (`heldChange` in `risk-score-form.dialog.ts`; `ComponentHistoryDialog`
now says "held for approval" instead of "Restored"). The list badges a model *Awaiting approval* from ONE read of
`GET /pending-changes?status=pending` filtered to `kind: risk-score` (`risk-score-held.ts`); a failed read shows
nothing — the badge is a hint, never a gate. A held **create** has no stored model, so the pane (which now triggers that read on every
list load) lists it as a read-only *Awaiting approval* row from what the Pending Change carries — name, author,
when — with a link to `/pending-changes`; it is not a button, so nothing tries to load a stored model. A held edit
or delete of a stored model stays a badge on that model's row (`heldCreates` in `risk-scores.component.ts`). *Delete* confirms, then `DELETE /components/risk-score/{id}`; the
derived Datasets stay and History can restore. **Add per-entity Alert Rule** (D-RP9 (a)) is a LINK, never a second
write, shown only to a caller holding `canAuthorAlertRules` (2026-10-06): `/alerts?newRule=1&dataset=risk_scores_<id>_latest&measure=max(score)&by=model,entity_key&comparator=gte&threshold=<high>`
(`perEntityAlertRuleParams`). The Alerts pane strips the params (`replaceUrl`) and opens the create dialog seeded
through the new `AlertRuleFormData.seed` (`alertRuleSeed`); the author still registers the `_latest` Dataset and
saves the rule under its own gate.

**Entity key display (D-RP6, operator 2026-10-06; D-P8 = mask on read).** Every SPA surface that prints an
entity key goes through ONE function, `displayEntityKey` (`inspecto/risk/risk-score-view.ts`): it keeps the
last four characters and masks the rest; a key the server already masked (`masked:…`) is shown as-is, never
bulleted into a fake four-character tail. Today the panel's label uses it. The response is masked server-side (above);
the key still travels in the request URL, which is the caller's own input.

**Authoring the Job.** `risk.score` is in the Jobs palette (`job-attributes.ts`, the declared fallback when
`GET /jobs/types` is unavailable). Its one parameter, `model`, is declared `STRING` (there is no component-ref
`ParamType`), so `JOB_PARAM_COMPONENT_REFS` marks it as a reference to the `risk-score` kind: `JobFormDialog`
renders it as a required autocomplete over the saved models (`riskScoreModelOptionLoader`,
`GET /components/risk-score`). Suggestions assist; the Job still refuses an unknown model when it fires.

## Residuals

- ✅ **The watch Entity List is fed (2026-09-28).** An optional `watchList: {list, ttlHours}` (1..24) adds every
  high entity to a `watch` Entity List after each run, expiring `ttlHours` later. It fails closed at save and at
  run. See [Entity Lists — assurance entries](entity-lists.md#the-risk-score-watch-list-feed).
- ✅ **Entity-key masking (D-P8 = mask on read, 2026-10-06).** The GET and preview routes mask the key unless the caller holds `canRevealLinkEntities` (`keyMasked`); the SPA displays keys through `displayEntityKey`, which passes a `masked:` token through.
- ✅ **Authoring pane shipped (2026-10-06): S1 list + detail, S2a create/edit, S2b held/delete/Alert Rule link, S3
  preview.** Decisions D-RP1..D-RP10 (operator 2026-10-06): own admin route; schema-form plus bespoke factor cards
  (D-RP7 spike: the condition-group editor authors nested AND/OR the model cannot store); score stays a binary
  `highThreshold` — **named score bands deliberately deferred** (D-RP3, nothing consumes them; revisit on demand);
  no new capability (`canAuthorWorkbench` write / `canWorkIncidents` read); pickers show only Datasets with a
  readable Schema; the per-entity Alert Rule is a prefilled link, never a second write. Not inspected: the
  capability approvers hold for held component changes. Plan archived at
  [`risk-score-authoring-pane-spec.md`](../../../archived-documents/plans-archive/risk-score-authoring-pane-spec.md).
- An indicator is a Measure. There is no free-form arithmetic expression, and no reference to a saved
  Measure component, because none exists.
- ✅ **Opt-in history retention (2026-10-04).** Optional model key `retainDays` or `retainRuns` (whole number
  >= 1; not both; fail closed at save). OFF by default: with neither set, every run is kept. After each write the
  Job deletes whole older run files from `risk_scores_<id>` (by the file's run timestamp, or all but the newest N)
  and never touches `_latest` or the run just written (`RiskScoreEvaluator.prune`).
  ⚠ **Audit vs storage.** A pruned run's factors are gone, so that score is no longer reproducible. That loss
  happens only when the operator opts in, and the default is to keep everything.
- A row written before a column was classified stays raw: **by design, won't fix** (history is never rewritten).
- Tracked as P3 `ASSURE-RISK-SCORE-RESIDUALS-1`.
