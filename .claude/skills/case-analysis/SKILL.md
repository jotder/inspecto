---
name: case-analysis
description: >
  Analyse a Case in Inspecto: Case Manager, Case Rules grouping Incidents, cases from entities,
  link-analysis investigations, risk/anomaly scores, Entity Lists and screening hits. Trigger on
  "analyse this case", "why was this case opened", "who is linked to X", "score / screening hit for
  entity", "investigation graph". Routes through module.case-management, module.la-core, module.scoring,
  module.screening and module.entity-list hubs with grounded Control API routes.
---

# case-analysis — Cases, links, scores, screening

Vocabulary: a **Case** groups **Incidents**; a **Case Rule** is the saved search that auto-groups them;
check `docs/GLOSSARY.md` before naming anything (Entity List, Expectation, Decision Rule).

## Read first (hubs under `docs/okf/product/`)

| Topic | Hub / slot file |
|---|---|
| Case model, routes, permissions | `module/case-management/architecture-overview.md` (add-on index: `addon/caseManagement/index.md`) |
| Case Rules within Incidents | `addon/incidents/plan-incidents.md` (Case Rules section) |
| Link analysis (investigations, graph) | `module/la-core/architecture-overview.md`, `module/la-core/user-guide-link-analysis.md`, `module/la-core/user-guide-investigation-pivot.md` |
| Scoring + Decision kernel | `module/scoring/architecture-decision-kernel.md`, `module/scoring/spec-risk-scores.md`, `module/scoring/architecture-scoring-entity-lists.md` |
| Entity Lists | `module/entity-list/spec.md` |
| Screening | `module/screening/architecture-overview.md`, `module/screening/spec.md` |
| Anomaly scores | `module/anomaly/` hub |

## Control API routes

| Question | Route |
|---|---|
| Open Cases from entities | `POST /cases/from-entities` (one Incident per entity key) |
| Case Rules | `GET /cases/rules`, `POST /cases/rules`, `DELETE /cases/rules/{ruleId}`, `POST /cases/rules/{ruleId}/evaluate` |
| Case contents / links | `GET /objects/{id}`, `GET /objects/{id}/graph`, `GET /objects/by-entity` |
| LA investigations | `GET` / `POST /inv/investigations`, `GET /inv/investigations/{investigationId}/log` |
| Investigation ↔ Case | `GET` / `PUT` / `DELETE /inv/investigations/{investigationId}/case`, `POST /inv/case-history` |
| Graph algorithms | `GET /inv/graph/algorithms`, `POST` / `GET /inv/graph/runs`, `GET /inv/graph/runs/{runId}` |
| Risk / anomaly score | `GET /risk-scores/{model}/{entityKey}`, `POST /risk-scores/preview`, `GET /anomaly-scores/{model}/{entityKey}` |
| Entity Lists | `GET /entity-lists`, `GET /entity-lists/{entitylistId}`, `POST /entity-lists/{entitylistId}/match` |
| Screening | `POST /screening/check`, `GET /screening/hits`, `GET /screening/hits/{hitId}`, `POST /screening/hits/{hitId}/decide` |

Permissions (case-management hub): creating or deleting a Case Rule needs `canAuthorWorkbench`;
evaluate needs `canAdminister`. `GET /screening/hits/{hitId}/reveal` unmasks data — only on operator
request. Handlers: `features/inspecto-case-management/src/main/java/com/gamma/ops/cases/CaseRoutes.java`,
`la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationCaseRoutes.java`.

## Procedure

1. **Why does the Case exist?** Read the Case object: raised by a Case Rule (evaluate it, check its
   threshold) or by `POST /cases/from-entities`?
2. **What is in it?** List member Incidents and linked objects (`/objects/{id}/graph`).
3. **Who is connected?** Open or attach an LA investigation; pivot per
   `user-guide-investigation-pivot.md`; run a graph algorithm only when asked (it is a Run).
4. **How risky?** Read risk / anomaly scores for the key entities; explain them via the Decision kernel spec.
5. **Listed / sanctioned?** Entity List match and screening hits; deciding a hit is a mutating action —
   never decide without the operator's go-ahead.
6. **Write up** findings on the Case (findings / RCA) — no client data in commits or docs.
