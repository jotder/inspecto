# Inspecto LA onboarding: from a blank install to a first finding (`LA-ONBOARD-1`)

**Status: PLAN 2026-10-22 — recommendations for operator review, nothing built.** Derived from the agreed use
cases [`spec-first-time-user-use-cases.md`](../okf/product/module/la-core/spec-first-time-user-use-cases.md)
(UC-01…UC-15, findings F-1…F-13, operator answers A-1…A-16). Decisions OB-1…OB-14 below are the assistant's
recommendations; each needs operator confirmation before its phase starts. Seams were located on origin/master
`aa2374130` by two read-only surveys (backend and UI) on 2026-10-22; treat a file reference as a pointer to read,
not as proof. When a phase ships, distil the as-built facts into the `module/la-core` hub (and `module/processor`
for the shared import route), close its board rows, and archive this file when the last phase ships.

## 1. Goal and acceptance

A first-time user of the free Inspecto LA app (Basic, no sign-in, `la-app` only) goes from an empty install to a
finding **with the screens alone**: create a Space, add a file, say what the columns hold, start from one account,
grow, run an algorithm, save the case. And an enterprise install (sign-in) with an Explorer licence gets the same
screens under full governance.

**Acceptance = the 2026-10-22 blank-slate walkthrough, repeated** (`transfers.csv` + `accounts.csv`, mule ring +
busy merchant), scripted as a live drive in the la-core `tests` slot:

1. Zero API calls and zero Pipeline terms on screen on Basic.
2. Launch to first graph centred on `MULE-HUB` in at most 10 minutes for a first-time user (think-aloud), counted
   by the local onboarding counters (A-10).
3. The ring is found (shortest path or communities) and saved as a case under *My work*.
4. The same walkthrough on a sign-in install with `la.level=explorer`: masking on, "Add data" locked until an
   administrator turns it on for the Space, Investigation actions locked with the licence reason.

## 2. What exists today (surveyed 2026-10-22)

| Area | Fact | Seam |
|---|---|---|
| Edition features | Decided by which jars are on the classpath: `EditionFeatures.present()` asks every `EditionFeatureProvider` (ServiceLoader); `investigations` comes from `OpsEditionFeatures` (Standard, Advanced, Preview). No runtime setting, no licence or entitlement concept anywhere. | `platform/inspecto-etl/.../EditionFeatures.java:35-128,212`; `features/inspecto-ops/.../OpsEditionFeatures.java` |
| Route gating | A route module naming an absent route feature answers 503 `CAPABILITY_UNAVAILABLE`. | `inspecto/.../ControlApi.java:1716-1720` |
| `/bootstrap` | `edition()` is derived: `auth.mode` none → basic, else advanced if an access decider is active, else standard; `features.investigations` = registered minus disabled minus denied. | `inspecto/.../BootstrapRoutes.java:99-163` |
| Masking default | `EditionMaskingDefault` returns `none` unless `investigations` is present. It keys on the LA level, not on sign-in — so an Explorer licence on a sign-in install would turn masking off. | `inspecto/.../EditionMaskingDefault.java:12-18`; SPI `platform/inspecto-entity-store/.../MaskingDefault.java` |
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
- **The LA level** (a new install setting: Explorer / Investigations) decides the screens.
- **Where data comes from** (self-import per Space, or Pipelines) decides the entry points.

One server path imports a file end to end (shared with the console's file-first onboarding), and the `la-app` gets
a first-run page, an "Add data" screen, a Start box, *My work*, and the help rules.

## 4. Decisions (assistant's recommendations; operator to confirm)

| # | Decision |
|---|---|
| OB-1 | **Scope.** This plan builds the Inspecto LA screens and the **shared server import route** (OB-2). The console adopts that route in the console onboarding companion plan of `PIPELINE-EDITIONS-1` (not written yet); until then the console keeps its guided editor. |
| OB-2 | **One import route family, core, every edition, not under `/inv`:** `POST /onboard/files` (upload + infer → a draft with evidence), `POST /onboard/files/{draft}/commit` (confirmed columns, Entity Types, link mapping → writes schema and Pipeline, registers, lands the file, runs once, registers the Dataset, returns the run summary), `POST /datasets/{id}/files` + `POST /datasets/{id}/run` (Add a file, Run now). One verb with the GLOSSARY: **Onboard**. A failed commit leaves nothing behind. |
| OB-3 | **The Pipeline behind an import is an ordinary Pipeline** marked `origin: import` (DT-2). Basic screens never show it; the Standard+ console shows and edits it like any other. |
| OB-4 | **Suggested selectors are column indexes for delimited files** (fixes F-4 for the console too), and the commit path never writes a selector registration would refuse. |
| OB-5 | **LA level = an install setting `la.level: explorer \| investigations`** (system property / install config), read by `EditionFeatures.present(INVESTIGATIONS)` and exposed in `/bootstrap` as `laLevel`. Basic is always `explorer`; Standard+ defaults to `investigations`. A plain setting now; a signed licence file is out of scope (record it as the hook). |
| OB-6 | **Masking default keys on sign-in:** `auth.mode` none → `none`, otherwise `typed`; stated mode still wins. Replaces `EditionMaskingDefault`'s `investigations` test (A-16). |
| OB-7 | **Self-import is a Space setting `data.self_import`** (default `true` with no sign-in, `false` with sign-in), changed by `canAdminister`; when on, `/onboard/files` also needs `canUploadData` (A-15). |
| OB-8 | **The Dataset registry entry carries `primaryEntityType` and a `defaultLinkMapping`** (from, to, kind, time, amount, details columns). Entity profiles and the Start box read them, so links no longer wait for a saved view (F-9). |
| OB-9 | **A generic single-entity start**: any Entity Type with a Dataset whose default link mapping uses it can be a start; a Domain Profile `investigate` stays as an override (fixes F-7, F-8). On the Explorer level it draws the seed plus one ring via the neighbours read (busy ones held); on the Investigations level it seeds an Investigation as today. |
| OB-10 | **One locked-with-reason component** (`inspecto-locked-action`: disabled control + one line + the edition or licence that has it) fed by `/bootstrap` `featureReasons`; whole Standard+ pages stay out of the Basic nav (A-11). |
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
| **1 Trust vs level** (server + bootstrap) | `la.level` setting (OB-5); masking default on sign-in (OB-6); `data.self_import` Space setting (OB-7); `/bootstrap` `laLevel`, `featureReasons`; `inspecto-locked-action` (OB-10); Investigation actions locked, not returned, on Explorer | §1.1, A-14…A-16, UC-13, UC-15 | — |
| **2 Shared import route** (server) | `/onboard/files` draft + commit, Add a file, Run now (OB-2, OB-3); value-shape detection proposes Entity Types and from/to; Reference kind (entity details); registry `primaryEntityType` + `defaultLinkMapping` (OB-8); classification change on an existing column; Basic DT-8 limits; CSV/TSV, Parquet, JSON/NDJSON | UC-03, UC-04, UC-07, UC-08, UC-14 | 1 (self-import gate) |
| **3 First run** (UI + small server) | `la-app` Getting started page keyed on "no Dataset yet"; switcher with zero Spaces (OB-11); Create Space with pack; *New from sample* with `loadSamples` (OB-12); Space guide (H1) ticks from server state | UC-01, UC-02 | 1 |
| **4 Add data screen** (UI) | Drop a file → check the columns (pre-applied, evidence, low confidence flagged) → what each column holds (Entity Types, from/to, primary) → create and run → problems in plain words; Reference "Add details"; Dataset card with *Add a file* + *Run now* (Basic), "kept up to date by…" + *Open in editor* / *Ask an administrator* (Standard+) | UC-03, UC-04, UC-06, UC-14 | 2 |
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
- **Licence vs setting.** `la.level` as a plain setting can be flipped by whoever runs the server; that is fine
  for now (the operator asked for a licence *switch*), but a commercial licence needs a signed file later (OB-5).
- **Two plans on Basic's data layer.** DATA-TIERS-1 and `PIPELINE-EDITIONS-1` both place data features by
  edition; phase 2 must follow whichever the operator names as the authority once the console plan is on
  master (use cases §9).
- **Value-shape detection is a guess.** Every proposal shows its evidence and is one click to change; nothing is
  committed without the user confirming step 3.
- **The whole-Dataset draw after *Investigate* on Basic (F-8) is not yet traced** — phase 6 starts with that trace.

## 8. Board

- P2 `LA-ONBOARD-1` — this plan, phases 1–8 (feature).
- P2 `LA-ONBOARD-FIXES-1` — phase 0 (defects F-4, F-6, F-10…F-13).

## References

- Use cases and operator answers: [`spec-first-time-user-use-cases.md`](../okf/product/module/la-core/spec-first-time-user-use-cases.md).
- [`data-tier-editions-plan.md`](data-tier-editions-plan.md) (`DATA-TIERS-1`, DT-2 and DT-8 reused; DT-4 reversed).
- GLOSSARY: Onboard, Stream, Reference, Dataset, Entity Type, Collector, Pipeline.
- [`docs/EDITIONS.md`](../EDITIONS.md): Basic ships no authenticator; masking on Basic is display-only.
