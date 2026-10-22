# Inspecto LA onboarding: from a blank install to a first finding (`LA-ONBOARD-1`)

**Status: CLOSED 2026-10-22 — phases 0-8 shipped; archived.** As-built facts: la-core [architecture](../../okf/product/module/la-core/architecture.md) §3.4, §6.10, §6.11, §8 and processor [api-onboarding-authoring](../../okf/product/module/processor/api-onboarding-authoring.md); acceptance drive [tests-onboarding-live-drive](../../okf/product/module/la-core/tests-onboarding-live-drive.md); open leftovers in [BACKLOG](../../BACKLOG.md) `LA-ONBOARD-RESIDUALS-1`, `LA-ONBOARD-DEFECTS-1`, `LA-ONBOARD-ORPHAN-PIPELINE-1`. Original header: PLAN 2026-10-22 — recommendations for operator review. Derived from the agreed use
cases [`spec-first-time-user-use-cases.md`](../../okf/product/module/la-core/spec-first-time-user-use-cases.md)
(UC-01…UC-15, findings F-1…F-13, operator answers A-1…A-16). Decisions OB-1…OB-14 below are the assistant's
recommendations; each needs operator confirmation before its phase starts. Seams were located on origin/master
`aa2374130` by two read-only surveys (backend and UI) on 2026-10-22; treat a file reference as a pointer to read,
not as proof. When a phase ships, distil the as-built facts into the `module/la-core` hub (and `module/processor`
for the shared import route), close its board rows, and archive this file when the last phase ships.

## 1. Goal and acceptance

A first-time user of the free Inspecto LA app (Basic, no sign-in, `la-app` only) goes from an empty install to a
finding **with the screens alone**: create a Space, add a file, say what the columns hold, start from one account,
grow, run an algorithm, save the case. And an enterprise install (sign-in) without the Investigations add-on gets
the same screens under full governance.

**Started 2026-10-22 (operator: "start suitable phases in parallel with suitable dependency order"): OB-1…OB-14 are taken as confirmed for the phases started.** Phase 0 shipped (`aa8ea0ab3`, `b2ebe38d1`). **Wave 1 shipped:** phase 1 `3715e9b6e` (Investigations add-on `addons.laInvestigations` / `INS-AO-LA-INVESTIGATIONS`, `enforcement: runtime`; masking by sign-in; `/bootstrap` `laLevel`, `featureReasons`; `inspecto-locked-action`; *Find paths (server)* deliberately left unlocked: an open Basic explorer route), phase 2 `ba57239fc` (`/onboard/files` draft + commit, `GET /onboard/runs/{id}`, `POST /datasets/{id}/files` and `/run`, `PUT /datasets/{id}/columns/{column}`, `GET|PUT /settings/data` with `/bootstrap` `selfImport`; as built in `module/processor/api-onboarding-authoring.md` and la-core `architecture.md` §6.10), phase 3 `f01053379` (Getting started, Space guide, switcher with zero Spaces, `POST /spaces {template, loadSamples}`, new Basic-safe sample template `la-sample` because `la-showcase` cannot be created on Basic). Wave 2 (phases 4, 5, 6) started. Waves: **1** = phases 1, 2, 3 in parallel; **2** = phases 4, 5, 6 after phase 2; **3** = phase 7 after phase 6; **4** = phase 8 last. Lane boundaries: the per-Space self-import switch (OB-7) is built in **phase 2**, not phase 1; changes to the Link Analysis starter cards belong to **phase 6** only.

**Reconciled 2026-10-22 with the console session** (`DATA-TIERS-1` + `ONBOARDING-FIRST-RUN-1`, all six points
agreed; OB-5 operator-confirmed there): see §9.

**Acceptance = the 2026-10-22 blank-slate walkthrough, repeated** (`transfers.csv` + `accounts.csv`, mule ring +
busy merchant), scripted as a live drive in the la-core `tests` slot:

1. Zero API calls and zero Pipeline terms on screen on Basic.
2. Launch to first graph centred on `MULE-HUB` in at most 10 minutes for a first-time user (think-aloud), counted
   by the local onboarding counters (A-10).
3. The ring is found (shortest path or communities) and saved as a case under *My work*.
4. The same walkthrough on a sign-in install without the Investigations add-on: masking on, "Add data" locked
   until an administrator turns it on for the Space, Investigation actions locked with the add-on as the reason.

## 2. What exists today (surveyed 2026-10-22)

| Area | Fact | Seam |
|---|---|---|
| Edition features | Decided by which jars are on the classpath: `EditionFeatures.present()` asks every `EditionFeatureProvider` (ServiceLoader); `investigations` comes from `OpsEditionFeatures` (Standard, Advanced, Preview). No runtime setting, no licence or entitlement concept anywhere. | `platform/inspecto-etl/.../EditionFeatures.java:35-128,212`; `features/inspecto-ops/.../OpsEditionFeatures.java` |
| Route gating | A route module naming an absent route feature answers 503 `CAPABILITY_UNAVAILABLE`. | `inspecto/.../ControlApi.java:1716-1720` |
| `/bootstrap` | `edition()` is derived: `auth.mode` none → basic, else advanced if an access decider is active, else standard; `features.investigations` = registered minus disabled minus denied. | `inspecto/.../BootstrapRoutes.java:99-163` |
| Masking default | `EditionMaskingDefault` returns `none` unless `investigations` is present. It keys on the LA level, not on sign-in — so a sign-in install without the Investigations add-on would turn masking off. | `inspecto/.../EditionMaskingDefault.java:12-18`; SPI `platform/inspecto-entity-store/.../MaskingDefault.java` |
| Onboarding authoring | Draft config per `POST /config/write` (`canAuthorWorkbench`), stateless previews, `POST /runs {configPath}` registers; Dataset registration is a separate `POST /components/dataset`. No single "file in → Dataset out" call. | `ConfigWriteRoutes.java:47`, `ConfigPreviewRoutes.java:52,327`, `RunRoutes.java:54`, `ComponentRoutes` |
| Schema suggestion | `suggestSchema` sets `selector = f.name()`; registration refused a name selector in the walkthrough (F-4). The refusing check was not located by the survey. | `ConfigPreviewRoutes.java:~355`; candidate `PipelineConfigParser.java:2169-2179` |
| Analyst upload | `/inv/uploads*` gated on `{geoLink, investigations}` (503 on Basic) and `canUploadData`; lands a file in an `accepts_upload` Collector's inbox only. UI lives only in the Investigation tab. | `la/inspecto-la-api/.../UploadRoutes.java:66-78`; `link-analysis-uploads.component.ts:28` |
| Column → Entity Type | Registry `columns[].classification`, claimed by `EntityType.classifications()`. No route sets it on an existing Dataset column except the generic component write. | `EntityIdentityRoutes.java:333-345`, `EntityMasking.java:286-328` |
| Spaces | `POST /spaces {id, display_name?, template?}` (`canAdminister`), templates seed config only; nothing loads and runs sample data. | `inspecto/.../SpaceRoutes.java:68-107` |
| `GET /datasets` | Unknown extensionless paths fall through to the SPA's `index.html` (F-13). | `ControlApi.serveStatic` `:1366-1400` |
| la-app landing | Static cards; no first-run detection (the console's keys on zero Runs). | `projects/la-app/src/app/landing.component.ts:9-44` |
| Space switcher | Rendered only when `multiSpace && availableSpaces.length > 0`; "New space…" sits inside, so zero Spaces = no way to create one (F-2). | `space-switcher.component.ts:46,68-74`; `spaces.service.ts:133` |
| Single-entity start | "Investigate a number" defaults to `msisdn`; any other type needs a Domain Profile `investigate.mapping` (F-7). Entity profile *Investigate* on Basic stops in `prepareInvestigation` with the "Standard and above" text (F-8; the whole-Dataset draw afterwards is not yet traced). | `investigate-number.ts:26,108`; `link-analysis.component.ts:1414-1441` |
| Entities page | Lists every `GET /inv/entity-types` type unfiltered (F-6); "Masked: Yes" is `entityType.masked` next to `masking.mode` (F-6); Enter on the closed pen field only starts editing (F-12). | `entities.component.ts:117`; `entity-type.component.ts:96-100`; `entity-lookup.component.ts:21-57` |
| Locked with reason | Precedents: template gallery `missingFeatures` ("Not available in this edition: …") and the Grow / Exclude `whyNot` texts. No shared component; edition gates elsewhere hide or return. | `space-template-gallery.dialog.ts:91-100`; `grow-exclude.ts:291-296` |
| Console onboarding UI | `OnboardingCreateDialog` (standalone dialog) and the guided `PipelineEditorComponent` (heavy, console services). | `modules/admin/catalog/onboarding/onboarding-create.dialog.ts:75`; `pipeline-editor.component.ts:194` |

## 3. Shape of the answer

Three axes, kept apart (use cases §1.1):

- **Who the user is** (edition: sign-in or not) decides the rules: masking default, admin lists, self-import,
  Pipeline visibility, "Ask an administrator".
- **The LA level** (Explorer, plus Investigations when its billable add-on is present) decides the screens. The
  edition stays the one product-wide switch (DT-11).
- **Where data comes from** (self-import per Space, or Pipelines) decides the entry points.

One server path imports a file end to end (shared with the console's file-first onboarding), and the `la-app` gets
a first-run page, an "Add data" screen, a Start box, *My work*, and the help rules.

## 4. Decisions (assistant's recommendations; operator to confirm)

| # | Decision |
|---|---|
| OB-1 | **Scope.** This plan builds the Inspecto LA screens and the **shared pieces**: the `/onboard/files` route (OB-2), sample loading (OB-12), `inspecto-locked-action` and the `/bootstrap` `featureReasons` field (OB-10), onboarding counters (OB-13), the walkthrough fixes (`LA-ONBOARD-FIXES-1`) and **the one Add data component both apps use** (phase 4, OF-1). The console companion is [`first-run-onboarding-plan.md`](../../superpower/first-run-onboarding-plan.md) (`ONBOARDING-FIRST-RUN-1`); edition rules for the data layer are owned by [`DATA-TIERS-1`](data-tier-editions-plan.md). |
| OB-2 | **One import route family, core, every edition, not under `/inv`:** `POST /onboard/files` (upload + infer → a draft with evidence), `POST /onboard/files/{draft}/commit` (confirmed columns, Entity Types, link mapping → writes schema and Pipeline, registers, lands the file, runs once, registers the Dataset, returns the run summary), `POST /datasets/{id}/files` + `POST /datasets/{id}/run` (Add a file, Run now). One verb with the GLOSSARY: **Onboard**. A failed commit leaves nothing behind. **Draft contract (agreed with `ONBOARDING-FIRST-RUN-1`):** every proposed choice is `{value, evidence, confidence: high \| low}`; a `format` block (delimiter, header, quoting, date formats, file-name dates, decimal mark, nested-JSON record path; filled by that plan's slice C1) and an `entityTypes` block (column → Entity Type, from / to, primary; this plan). **Confidence rule (OF-2):** high when every sampled value agrees and the sample has at least 50 non-blank values, low otherwise; low is highlighted and confirm-gated, in both UIs. **On Basic the commit, Add a file and Run now also build the Dataset's link index and evaluate its Expectations inside the same user-started run** (manual work, not automation; DT-16): they are not dispatched as signal-driven Jobs. |
| OB-3 | **The Pipeline behind an import is an ordinary Pipeline** marked `origin: import` (DT-2). Basic screens never show it; the Standard+ console shows and edits it like any other. |
| OB-4 | **Suggested selectors are column indexes for delimited files** (fixes F-4 for the console too), and the commit path never writes a selector registration would refuse. |
| OB-5 | **Investigations is a billable add-on, enforced at runtime, included by default on Standard and above** (operator-confirmed 2026-10-22 with the console session; DATA-TIERS-1 DT-11 clarified to match). No per-application switch: the edition stays the one governance switch. A new Product Map add-on (for example `addon.laInvestigations`, with an order code) carries `enforcement: runtime`, a new value (today's add-ons are `build` or `contract`); when the install does not hold it, `EditionFeatures.present(INVESTIGATIONS)` answers false, Investigation routes answer 503 with the add-on named, and `/bootstrap` reports `laLevel: explorer`. Basic never has it. How the install proves it holds the add-on (a setting now; a signed entitlement later) is recorded as the hook. |
| OB-6 | **Masking default keys on sign-in:** `auth.mode` none → `none`, otherwise `typed`; stated mode still wins. Replaces `EditionMaskingDefault`'s `investigations` test (A-16). |
| OB-7 | **Self-import is a Space setting `data.self_import`** (default `true` with no sign-in, `false` with sign-in), changed by `canAdminister`; when on, `/onboard/files` also needs `canUploadData` (A-15). Exposed for the current Space through `/bootstrap` and the Space settings read, because the console's Add data (`ONBOARDING-FIRST-RUN-1`) locks on it too. |
| OB-8 | **The Dataset registry entry carries `primaryEntityType` and a `defaultLinkMapping`** (from, to, kind, time, amount, details columns). Entity profiles and the Start box read them, so links no longer wait for a saved view (F-9). |
| OB-9 | **A generic single-entity start**: any Entity Type with a Dataset whose default link mapping uses it can be a start; a Domain Profile `investigate` stays as an override (fixes F-7, F-8). On the Explorer level it draws the seed plus one ring via the neighbours read (busy ones held); on the Investigations level it seeds an Investigation as today. |
| OB-10 | **One locked-with-reason component** (`inspecto-locked-action`: disabled control + one line + the edition or add-on that has it) fed by `/bootstrap` `featureReasons`; whole Standard+ pages stay out of the Basic nav (A-11, DT-13). This plan adds the field and the component; `DATA-TIERS-1` phase 2 fills the entries from the server's `refusal()` wording. |
| OB-11 | **Space switcher renders with zero Spaces whenever the user may create one** (no sign-in, or `canAdminister`), and the first-run page offers *New Space* / *New from sample* itself. |
| OB-12 | **Sample Space loads and runs from one call:** `POST /spaces {template, loadSamples: true}` copies the template's samples into its inboxes and triggers one run per Pipeline (shared with console UC-1). |
| OB-13 | **Onboarding counters, always on, local** (A-10): append-only per-install file under the server's data directory (launch, Space created, first Dataset, first graph, guide completed, step abandoned), never sent anywhere; `GET /onboarding/metrics` for `canAdminister` (anyone with no sign-in). |
| OB-14 | **Vocabulary:** the button says **Add data**; the screen title says **Onboard a file**. GLOSSARY gains "Add data — the Inspecto LA entry point to Onboard a file as a Stream or Reference", so "Onboard" stays the one verb. |

## 5. Phases

Phases 1–3 are server-first and can run in parallel lanes; UI phases follow their contracts. Each phase is one
lane (or one small group) pushed on its own (`lane-integration-workflow.md`).

| Phase | What | Use cases / findings | Depends on |
|---|---|---|---|
| **0 Quick fixes** (defects) | "Masked" label reads the effective mode (F-6); Enter opens an Entity (F-12); centrality next-step text names the level that has Investigations (F-10); legend and Working Set collapse below a canvas width (F-11); unknown `/api/…` paths answer 404 JSON, never the SPA (F-13); suggested selectors are indexes (F-4, OB-4) | UC-07, UC-12, UC-05, UC-03 | — |
| **1 Trust vs level** (server + bootstrap) | Investigations add-on enforced at runtime, `laLevel` in `/bootstrap` (OB-5); masking default on sign-in (OB-6); `/bootstrap` `laLevel`, `featureReasons`; `inspecto-locked-action` (OB-10); Investigation actions locked, not returned, on Explorer, including *Find paths (server)* in the Analysis toolbox (seen on Basic during phase 0) | §1.1, A-14…A-16, UC-13, UC-15 | — |
| **2 Shared import route** (server) | `/onboard/files` draft + commit, Add a file, Run now (OB-2, OB-3); `data.self_import` Space setting and its gate, exposed through `/bootstrap` and the Space settings read (OB-7); value-shape detection proposes Entity Types and from/to; Reference kind (entity details); registry `primaryEntityType` + `defaultLinkMapping` (OB-8); classification change on an existing column; the draft contract and OF-2 confidence rule (OB-2); link index build and Expectation evaluation inside the manual run on Basic (DT-16); formats and limits per DT-8 (CSV / TSV, Parquet, JSON / NDJSON, Excel when the bundle carries the DuckDB `excel` extension) | UC-03, UC-04, UC-07, UC-08, UC-14 | 1 (self-import gate) |
| **3 First run** (UI + small server) | `la-app` Getting started page keyed on "no Dataset yet"; switcher with zero Spaces (OB-11); Create Space with pack; *New from sample* with `loadSamples` (OB-12); Space guide (H1) ticks from server state | UC-01, UC-02 | 1 |
| **4 Add data screen** (UI) | **The one Add data component both apps use** (OF-1), in the shared `projects/link-analysis` code; it reads the draft's `format` block (`ONBOARDING-FIRST-RUN-1` C1) and its own `entityTypes` block; the console passes "what the columns hold" as optional. Drop a file → check the columns (pre-applied, evidence, low confidence flagged) → what each column holds (Entity Types, from/to, primary) → create and run → problems in plain words; Reference "Add details"; Dataset card with *Add a file* + *Run now* (Basic), "kept up to date by…" + *Open in editor* / *Ask an administrator* (Standard+) | UC-03, UC-04, UC-06, UC-14 | 2 |
| **5 Entities and columns** (UI + server) | Entities page shows only types present in the data; *Columns* tab sets or changes an Entity Type per column; *New Entity Type* | UC-07 | 2 |
| **6 Start box** (UI + server) | "Start from…": pick Entity Type, then value (type-ahead with link counts); a list; a paste; the busiest / a community; the whole Dataset with a cap warning (OB-9); Entity profile *Investigate* starts from that entity | UC-09, UC-11, F-7, F-8 | 2 (default link mapping) |
| **7 My work and discovery** (UI) | Home *My work*: saved views as cases with the lists they use; *Save as a case*; *What changed in your data*; *Find something interesting* panel; result rows offer *Start from these* / *Add to a list*; pack-suggested first lists | UC-10, UC-12, UC-13, UC-14 | 6 |
| **8 Help and counters** | Help rules on every screen (empty state reason + next action, plain term explanations, *What can I do next?*); onboarding counters (OB-13); user guide chapter "Getting started"; the acceptance live drive (§1) | UC-15, A-10 | 3–7 |

## 6. What breaks (no shims, standing operator preference; release note only)

- A sign-in install that relied on Explorer-only builds getting unmasked ids: none exist (Explorer-on-sign-in is
  new), but `EditionMaskingDefault`'s test changes meaning — the SPI contract test must say "no sign-in".
- Templates and seeds that assume `/inv/uploads` on Basic: none (it was 503).
- The `/onboard/files` route family is new; the console's existing onboarding keeps working until its own plan
  adopts it.

## 7. Risks and open questions

- **Run once, synchronously.** A commit that runs the import inline must stay inside the request budget for DT-8's
  500 MB; above a size, answer `202` with a run id and let the screen follow the run. Measure first.
- **Add-on proof.** Until a signed entitlement exists, whoever runs the server can claim the Investigations add-on
  (OB-5); acceptable for now, recorded as the hook.
- **Cross-plan contracts.** `ONBOARDING-FIRST-RUN-1` slices C1 (format block), C3 (console Add data) and C4
  (console Space guide) build on phases 2, 3 and 4 here; change the draft contract or the component's inputs only
  with that plan's owner.
- **Value-shape detection is a guess.** Every proposal shows its evidence and is one click to change; nothing is
  committed without the user confirming step 3.
- **The whole-Dataset draw after *Investigate* on Basic (F-8) is not yet traced** — phase 6 starts with that trace.

## 8. Reconciliation with `DATA-TIERS-1` and `ONBOARDING-FIRST-RUN-1` (2026-10-22)

Agreed point by point with the session that owns those two plans:

| # | Point | Outcome |
|---|---|---|
| 1 | DT-11 "one product-wide edition switch" vs A-14 Explorer-only on sign-in installs | Investigations becomes a billable add-on enforced at runtime, included by default on Standard and above (OB-5); DT-11 clarified. Operator-confirmed in that session. |
| 2 | DT-16 stops signal-driven Jobs after a manual commit on Basic, so an import would never build the link index | The import's commit, Add a file and Run now build the link index and evaluate Expectations inside the same user-started run (OB-2); Jobs a user authors on `on_signal` stay Standard. DT-16 says so. |
| 3 | One Add data component (OF-1), one confidence rule (OF-2), format evidence in the draft (C1) | Accepted: OB-1, OB-2 and phase 4 here; `confidence` on every choice, `format` and `entityTypes` blocks. |
| 4 | `/bootstrap` `featureReasons` | Field and component here (OB-10); entries from `refusal()` in DATA-TIERS-1 phase 2. |
| 5 | Excel | DT-8 owns edition rules: Excel when the bundle carries the DuckDB `excel` extension. The draft `PIPELINE-EDITIONS-1` was merged into DATA-TIERS-1 and never published; its D-PE decisions map to DT numbers there. |
| 6 | Masking default (OB-6) and per-Space self-import (OB-7) | No overlap; the console reads `data.self_import` (OB-7 exposes it). |

## 9. Board

- P2 `LA-ONBOARD-1` — this plan, phases 1–8 (feature).
- P2 `LA-ONBOARD-FIXES-1` — phase 0 (defects F-4, F-6, F-10…F-13).

## References

- Use cases and operator answers: [`spec-first-time-user-use-cases.md`](../../okf/product/module/la-core/spec-first-time-user-use-cases.md).
- [`data-tier-editions-plan.md`](../../superpower/data-tier-editions-plan.md) (`DATA-TIERS-1`: owns the data layer's edition rules; DT-2, DT-4, DT-8, DT-11, DT-13, DT-16 used here).
- [`first-run-onboarding-plan.md`](../../superpower/first-run-onboarding-plan.md) (`ONBOARDING-FIRST-RUN-1`, the console companion; OF-1, OF-2, slices C1–C4).
- GLOSSARY: Onboard, Stream, Reference, Dataset, Entity Type, Collector, Pipeline.
- [`docs/EDITIONS.md`](../../EDITIONS.md): Basic ships no authenticator; masking on Basic is display-only.
