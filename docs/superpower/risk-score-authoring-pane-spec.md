<!-- ASSURE-RISK-SCORE-RESIDUALS-1 residual (2): the Risk Score authoring pane. DRAFT SPEC, 2026-10-04.
     Active plan (tier 2): while in flight it lives here; when built, distil the as-built facts into
     okf/backend/control-plane/risk-scores.md and git mv this to archived-documents/plans-archive/. -->
# Risk Score authoring pane — UX / product spec

**Status:** decisions D-RP1..D-RP10 approved (operator, 2026-10-06); S1 built, S2/S3 open. **Owns:** residual (2) of BACKLOG row
`ASSURE-RISK-SCORE-RESIDUALS-1`. **As-built of the model:** [`okf/backend/control-plane/risk-scores.md`](../okf/backend/control-plane/risk-scores.md).
Vocabulary per [`GLOSSARY.md`](../GLOSSARY.md): *Risk Score*, *Factor*, *indicator* (a Measure), *Dataset*, *Alert Rule*, *Incident*.

## 1. Grounding — what exists today

| Fact | Evidence |
|---|---|
| Model keys (allow-list): `id, entityType, highThreshold, dataScope, description, factors, watchList, retainDays, retainRuns` + envelope `name, owner, shares` | `inspecto-engine/.../risk/RiskScoreModel.java:60` |
| Factor keys: `id, label, dataset, key, measure, filters, weight, cap, evidence` | `RiskScoreModel.java:62` |
| Limits: 32 factors, 8 evidence columns per factor, watch `ttlHours` 1..24, `retainDays`/`retainRuns` whole number 1..100000 and mutually exclusive, `highThreshold` in (0,100], `scoresDataset` not authorable | `RiskScoreModel.java:56-57,113,146+` |
| **There are no "score bands".** The only band is binary: `high = score >= highThreshold`. Any 3-band UI (low/med/high) would be new model | `RiskScorer` (okf as-built "The math"); `RiskScoreModel.java` has no band key |
| Evaluator: pure `evaluate(model, relationSql, masker)` returns scored entities; scores every entity ANY factor names; fails the run above 200 000 entities | `RiskScoreEvaluator.java:48,75` |
| Writes go through generic `/components/risk-score` (POST/PUT/DELETE, restore), gate `canAuthorWorkbench`, then `validateKind` (structure + compiled queries), `requireStorable` (columns in Dataset Schema, output-name collisions), then `PendingChanges.hold` (maker-checker) | `ComponentRoutes.java:96-101,751-761,467`; `RiskScoreRoutes.java:121-126` |
| Only read route: `GET /risk-scores/{model}/{entityKey}`, `canWorkIncidents`, existence-hiding 404 | `RiskScoreRoutes.java:45` |
| SPA today: service `RiskScoresService.latest()`, `RiskScorePanelComponent` (factor-bar breakdown), Job palette entry + model picker over `components.list('risk-score')`. No list/detail/form | `inspecto-ui/.../api/risk-scores.service.ts`; `components/risk-score-panel.component.ts`; `components/entity-option-loaders.ts:45` |
| **Preview/dry-run route: does NOT exist.** `POST /components/{transform,grammar,sink}/{id}/test` and `/components/transform/preview` exist for other kinds only; none for `risk-score`. The evaluator is callable in-process but no route exposes it, and `RiskScoreJobType` always writes | `ComponentRoutes.java:104-111`; no dry/probe/preview match in `RiskScoreRoutes.java` |
| Authoring-pane pattern: dialog + `<inspecto-schema-form>` driven by an `AttributeSpec[]`, option loaders (`datasetOptionLoader`, `datasetColumnOptionLoader('dataset')`), `extraValidators`, `guardDirtyClose`; two-step Config / Save | `modules/admin/alerts/alert-rule-form.dialog.ts:113-170,246-270` |
| Masking: evidence of classified columns (`MSISDN, IMSI, ACCOUNT, PII`) masked at write; the entity key is raw everywhere, **D-P8 open — not decided here** | `okf/backend/control-plane/risk-scores.md` "Masking" |

## 2. Who authors, and with which capability

| Persona | Needs | Capability (existing) |
|---|---|---|
| Fraud / assurance analyst | read models, see factor breakdown for an entity | `canWorkIncidents` (read route) |
| Workbench author | create / edit a model | `canAuthorWorkbench` (generic component write) |
| Approver | approve a held change | `canApproveChanges` (**unverified** for this kind) |
| Data-scoped caller | sees only models whose `dataScope` they hold | model `dataScope` |

The pane adds **no new capability** unless D-RP4 says so. Personal edition has no Subject, so gates are no-ops (`ComponentRoutes.java:95`).

## 3. What the author must express (form map)

| Section | Fields | Control | Validation source |
|---|---|---|---|
| Identity | `id` (letters, digits, `_`; create only), `description`, `entityType` (combo: 7 named + free token) | text, combo | `fromMap` messages, shown verbatim |
| Threshold | `highThreshold` 0 < x <= 100 | number | `fromMap` |
| Factors (1..32, reorderable) | `id`, `label`, `dataset` (picker), `key` (column picker of that Dataset), `measure` (`count` or `agg(field)`), `filters[]` (field/op/value), `weight` (negative = protective), `cap` (>= 0), `evidence[]` (<= 8 columns) | repeating group | compiled server-side at save |
| Scope | `dataScope` token | text/picker | `fromMap` |
| Watch List | `watchList.list` (watch Entity List picker), `ttlHours` 1..24 | optional group | `fromMap` |
| Retention | `retainDays` XOR `retainRuns`, off by default | radio + number | `fromMap` |
| Read-only footer | derived outputs `risk_scores_<id>` and `_latest`, link "to raise Incidents add a per-entity Alert Rule" | text | never authorable |

Save-time validation: the pane **reuses the server's 422 messages verbatim** (they name the field path, e.g. `risk-score.factors[2].cap ...`) and maps the `factors[i]` prefix to the matching row; it duplicates no rule client-side except cheap required/number checks. Schema-column and output-collision errors come from `requireStorable`.

Conventions (angular-ui skill): standalone OnPush components, signals, design-system parts (`status-badge`, `empty-state`, `skeleton`, `connectivity-banner`), no hardcoded colours (CI guard), WCAG 2.2 AA + an axe spec per component, lazy route, `guardDirtyClose` on the form. **Schema-form per spec:** `<inspecto-schema-form>` suits the flat sections; the Factors group is a repeating row editor, not one spec per factor (per-spec schema-form instances multiply tier disclosures) — one schema-form for the flat model plus a row editor for factors (**unverified** that `editable-grid.component.ts` fits nested filters; S2 spike).

## 4. States

| State | Behaviour |
|---|---|
| Loading | `skeleton` rows in the list; detail shows a skeleton form |
| Empty (no models) | `empty-state`: "No Risk Scores yet" + New button (author) or text only (read-only) |
| Error | load error with retry; a 404 on one model = "not visible or removed" (existence-hiding, same for scope denial) |
| Offline | `connectivity-banner`; Save disabled |
| Held change | save returns the held state: show "Awaiting approval" badge (reuse the pending-changes affordance) |
| Unparseable stored model | list row flagged "invalid — open in raw editor"; the read route already 404s it |

## 5. Preview of a score on a sample entity

**Missing:** a server preview. The model can be evaluated in-process (`RiskScoreEvaluator.evaluate`, pure, no writes) but no route calls it, and the Job always writes.

| Needed piece | Size |
|---|---|
| `POST /risk-scores/preview`, body `{model (unsaved content), entityKey}` -> `{score, high, factors[]}`: run `fromMap`, `requireStorable`, evaluate with the masker, keep one entity (key bound, never spliced) | M: one route, four-gate treatment per the `endpoint` skill, plus an `openapi-v1.json` entry |
| Cost bound: evaluate scores every entity of every factor Dataset, so the single-entity filter must be pushed into the spec or it is a full-Dataset scan (**unverified** that `MeasureCompiler` takes an extra key filter without a new field) | part of the route |
| UI: sample-entity input + factor breakdown reusing `RiskScorePanelComponent` | S |

## 6. Slices

| Slice | Scope | Size | Depends |
|---|---|---|---|
| S1 | Read-only list + detail: models from `/components/risk-score`, factor table, derived outputs, latest score via the existing panel, history/diff via `component-history.dialog` | M (about 1 week) | none |
| S2 | Create / edit form (section 3), 422 mapping, maker-checker state, delete, restore | L (about 2 weeks) | S1 |
| S3 | Preview route + UI (section 5) | M backend + S UI | S2 (UI), D-RP5 |

## 7. Decision register (answer in one interview)

| ID | Question | Options | Recommendation |
|---|---|---|---|
| D-RP1 | Where does the pane live? | (a) new admin route `modules/admin/risk-scores` (b) a tab of the generic Components pane (c) inside the Alert Rules pane | **(a)** — own list/detail/preview; (b) gives no factor editor |
| D-RP2 | Form technology | (a) schema-form for flat fields + custom factor row editor (b) one big custom form (c) raw TOON editor only | **(a)** — house pattern, smaller surface |
| D-RP3 | Score bands | (a) keep binary `highThreshold` (b) add optional named bands (new model key + evaluator output) | **(a)** — nothing consumes bands; Alert Rule thresholds already cover it. Revisit on demand |
| D-RP4 | Capabilities | (a) reuse `canAuthorWorkbench` write + `canWorkIncidents` read (b) new `canAuthorRiskScores` | **(a)** — no new surface. (b) only if risk authors must differ from workbench authors |
| D-RP5 | Preview | (a) build `POST /risk-scores/preview` (b) no preview; author runs the Job and reads the panel (c) preview for saved models only | **(a)**, one entity; ship S1+S2 first, S3 may follow |
| D-RP6 | Entity key display / masking | (a) raw key, as today (b) wait for the D-P8 decision (c) mask in the pane only | **(b)** — D-P8 is open and this spec does not decide it; the pane renders the key through one function so D-P8 changes one place |
| D-RP7 | Factor row editor | (a) reuse `editable-grid` (b) bespoke repeating cards with an inline filter builder (c) reuse an existing filter builder from the query / Alert Rule panes | **(c) if reusable after the S2 spike, else (b)** — filters already have a grammar elsewhere |
| D-RP8 | Dataset pickers | (a) only Datasets with a readable Schema (matches `requireStorable`) (b) all, fail at save | **(a)** — column pickers need the Schema anyway; the fail-closed message covers the rest |
| D-RP9 | Offer the per-entity Alert Rule | (a) link to the Alert Rule dialog with `dataset` / `by` / `threshold` prefilled (b) create it in the same save (c) nothing | **(a)** — separate gate and maker-checker; no hidden second write |
| D-RP10 | Delivery order | (a) S1, S2, S3 (b) S1+S2 together (c) S3 backend first | **(a)** — each slice is releasable; S1 alone gives the read-only list |

### Decisions recorded

All ten recommendations APPROVED (operator, 2026-10-06): D-RP1 (a) new admin route; D-RP2 (a) schema-form plus a
custom factor-row editor; D-RP3 (a) keep the binary `highThreshold`; D-RP4 (a) reuse `canAuthorWorkbench` (write) /
`canWorkIncidents` (read); D-RP5 (a) build `POST /risk-scores/preview`; D-RP6 entity keys rendered through ONE
masking function, D-P8 = mask on read; D-RP7 (c) reuse the filter builder if the spike allows, else (b) bespoke
cards; D-RP8 (a) pickers show only Datasets with a readable Schema; D-RP9 (a) per-entity Alert Rule is a prefilled
link; D-RP10 (a) deliver S1 → S2 → S3.

**Progress.** S1 SHIPPED 2026-10-06 (`/risk-scores`, as-built in the OKF concept). S2 and S3 open.

## 8. Unverified claims

- The capability name approvers hold for held component changes (section 2).
- Whether `editable-grid` can host nested filters, and whether a filter builder is reusable (D-RP7).
- Whether `MeasureCompiler` can take a single-entity key filter without a new field (section 5).
- Nav-menu / i18n registration for a new admin route was not inspected.
- Sizes are estimates from the file inventory, not from a spike.
