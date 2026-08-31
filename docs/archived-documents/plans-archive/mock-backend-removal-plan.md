# Removing the offline UI mock backend

**Status:** ✅ **COMPLETE and ARCHIVED 2026-08-31.** Shipped as `f1553136` (removal + the three defects
it surfaced), with the follow-on closures `757add5e`, `4ad3583c`, `6bc4a782`, `41464d51`. The one slice
NOT taken — §3b, the four dead in-browser compute blocks — moved to `docs/BACKLOG.md`
(**MOCK-DEAD-COMPUTE-1**). Kept for provenance; not maintained.
**Operator directive:** *"remove offline UI facility, it's adding complexity and confusion. work with
real backend."* Two follow-on decisions taken the same day (§1).

## 0. Why

`inspecto-ui` carries a second, parallel implementation of the control plane: 82 files / ~22k LOC of
handlers, a persistent per-space store, and an HTTP interceptor, plus **five production files that
branch on a build flag** and therefore behave differently offline than against the engine. That
divergence is not theoretical — it is the direct cause of BUNDLE-SCHEMA-1, where the same bundle
imports differently offline and against a backend.

## 1. Decisions (2026-08-31, operator)

| # | Question | Answer |
|---|---|---|
| D1 | `npm run start:offline` is the air-gapped stakeholder demo. Remove it? | **Yes** — demo against a real backend. The five demo seeds are NOT ported. |
| D2 | The 381 tests inside `mock/` | **Delete with the mock.** Keep only specs for modules being promoted. |

## 2. 🔴 What lives in `mock/` but is NOT a mock

Deleting the directory outright turns the **Java reactor** red and breaks the **shipped bundle**.

- **Six committed contract JSONs** — `node-attributes`, `step-types`, `bind-kinds`, `attribute-spec`,
  `expression-guard`, `measure-grammar`. These are server-published drift ratchets, read **by path**
  from seven Java tests: `NodeAttributesContractTest`, `StepTypesContractTest`,
  `BindKindHomeContractTest`, `ExpressionGuardContractTest`, `MeasureGrammarContractTest`,
  `FindingsSpecContractTest`, `PipelineNodeExecutorTest`.
- **Two of them are PRODUCTION runtime imports**, not fixtures — `calculated-column-guard.ts:1` and
  `measure-grammar.ts:3` import their contract JSON into the shipped bundle.
- `pipeline-editable.ts` (`liftConfig` / `lowerGraph`) — the UI half of the lift/lower contract,
  pinned by `pipeline-graph.contract.spec.ts`.

They live under `mock/` by accident of history, not by design.

## 3. Slices

| # | Slice | Verify |
|---|---|---|
| S1 | Promote the survivors out of `mock/` into `src/app/inspecto/contracts/` (six JSONs + their specs) and `pipeline-editable.ts` into the pipelines module. Update every TS import. | `ng test` green on the promoted specs; `npm run build` |
| S2 | Repoint the **seven Java tests** at the new path, in the SAME change as S1. | `mvn -o test` on the two engine test classes' modules |
| S3 | Delete the mock backend: `mock/` remainder, `mock-api.interceptor`, `mock-store`, `mock-http`, seeds, `environment.offline.ts`, the `offline` blocks in `angular.json`, `start:offline`, the ten `mock*` flags. | build green |
| S4 | Collapse the **five production branch sites** to their remote path: `dataset-result.service.ts:37`, `kpi.component.ts:52`, `recon-exec.service.ts:30,45`, `dataset-rows.service.ts:53,76,94`. Delete the now-dead in-browser engines they guarded (`runOffline`, `sampleRows`, `recon-board` compare) only where nothing else calls them. | `ng test`, `npm run build` |
| S5 | Docs: GLOSSARY/INDEX/OKF touchpoints, BACKLOG BUNDLE-SCHEMA-1 re-framed. | vocabulary guard |

⚠ Each S4 branch removal deletes a real in-browser computation path. The remote counterpart was
confirmed to exist server-side before scheduling: `/bi/query`, `ReconRoutes`, `/db/*`.

## 3b. OPEN — the client-side compute the arms guarded (S6, not done)

Removing the offline arms left four blocks of in-browser computation referenced **only by their own
specs**. They are the offline computation facility itself, so deleting them is in scope, but it is a
separable slice — each carries a spec suite that may be read as a mirror of the server's contract:

| Dead block | Home |
|---|---|
| `projectPoints`, `projectRoutes` | `modules/admin/studio/geo-map/geo-projection.ts` |
| `projectEntities` | `modules/admin/studio/link-analysis/entity-projection.ts` |
| `aggregateRecon`, `reconBreakSets` | `inspecto/reconciliation/recon-board.ts` |

⚠ Decide per block whether the spec pins a server contract worth keeping as a mirror before deleting.
`inspecto/fixtures/sample-sources.ts` survives only to feed these and three other spec suites; it goes
when the last consumer does.

## 3c. End-user test (2026-08-31) — run, and its findings FIXED

Driven against a **real** ControlApi (`tools/run-backend.ps1`, write root `spaces/demo/config`) with
`ng serve` on :4204. Boot, the Pipelines list + editor (Recipe and Edit), the relocated TS lift/lower,
the parser palette, Reconciliation and Link Analysis were all exercised. **15/15 startup calls 200 under
`/api/v1`; zero JS exceptions.** 🔴 `GET /parsers` now comes from the real `ParserRoutes` — it had been
answered by `parsersHandler()`, the one **UNGATED** mock handler, even in shipping builds.

Every console error traced to a **pre-existing** defect the mock had been masking. All three are now
fixed and pinned — see BACKLOG `MOCK-GONE-1` for the per-fix table.

⚠ `.claude/launch.json`'s `inspecto-ui-offline` entry was removed with the `offline` configuration.

## 4. Exit criteria

- Full reactor green; UI `ng test` exit 0; `format:check`, `lint:tokens`, all three tsconfigs, `build`.
- `grep -rn "environment.mock" inspecto-ui/src` returns nothing.
- No Java test references a path under `inspecto-ui/src/app/inspecto/mock/`.
