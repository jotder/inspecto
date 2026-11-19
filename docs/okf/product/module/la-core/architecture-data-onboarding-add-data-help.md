---
type: Concept
title: Link Analysis — data onboarding — Add data, help and onboarding residuals
description: Part 1 of 3 of Link Analysis — data onboarding: Add data: the shared import route (LA-ONBOARD-1 phase 2, as built 2026-10-22); Help on every screen and the onboarding counters (LA-ONBOARD-1 phase 8, as built 2026-10-22; UC-15, A-9, A-10, OB-13); Explorer defects found by the acceptance drive, fixed (LA-ONBOARD-DEFECTS-1, as built 2026-10-23); Onboarding residuals (LA-ONBOARD-RESIDUALS-1, as …
tags: [module, link-analysis, onboarding, add-data, arrivals, dataset, architecture]
timestamp: 2026-11-12T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-20 last-edit
code: la/inspecto-la-core
---

# Link Analysis — data onboarding — Add data, help and onboarding residuals

Part 1 of 3 of [Link Analysis — data onboarding](architecture-data-onboarding.md) · next: [Column roles and discovery in Basic](architecture-data-onboarding-roles-discovery.md).

## 6.10 Add data: the shared import route (LA-ONBOARD-1 phase 2, as built 2026-10-22)

The route family lives in the **core** (every edition, Basic included, not under `/inv`); its contract, gates,
limits and the commit's write order are in
[`api-onboarding-authoring.md`](../processor/api-onboarding-authoring.md) ("Add data"). What Link Analysis relies on:

- **Where the screen lives (`BASIC-ELT-PROFILE-1` UI-0, 2026-11-19).** The Add data screen, the Dataset card and their
  models are core UI (`inspecto-ui/src/app/inspecto/add-data/`, `@inspecto/core/add-data/*`), with Column Roles and the
  Entity Type picker helpers (`@inspecto/core/api/column-roles`, `.../entity-type-options`). Core never imports the
  library, so the Link Analysis parts enter through one token, `ADD_DATA_LINK_ANALYSIS` (`add-data-link-analysis.ts`):
  the Space's Entity Types, the `import.*` limits, the link index status and its Rebuild. The library fills it with
  `provideAddDataLinkAnalysis()` (`link-analysis/add-data-link-analysis.providers.ts`), listed in the `providers` of
  each page placing Add data or a Dataset card (console `/add-data` and Datasets, la-app `/add-data` and Data), never
  in a shell's eager providers (the bundle-shape guard). Without it those parts are absent, not failing. Who may add
  data (`AddDataAccess`) reads `SessionService` directly. *Data status* stays in the library (below).

- **The draft's `entityTypes` block** proposes, per column, the Entity Type of the Space
  (`LinkAnalysisSettings.effectiveEntityTypes()`) from value shapes and names, the link's from / to / kind / time /
  amount / details, the primary Entity Type, and for a Reference the identifying column. Every proposal is
  `{value, evidence, confidence}` (OF-2); a column whose values fit no Entity Type stays a detail and says why.
- **The registry entry carries the link (OB-8).** A committed Dataset has `columns[].classification`,
  `primaryEntityType` and `defaultLinkMapping {from, to, kind?, primaryTime?, primaryMeasure?, details?[]}` (`time` /
  `amount` are the primaries' old names, still read; §6.15).
  `DatasetEntryRules` (run by `ComponentRoutes.validateKind` on every Dataset write, so the generic component
  routes refuse the same) checks the shape: from and to required and different, every named column one of the
  entry's `columns[]` when it lists any, `primaryEntityType` a non-blank id.
- **Mappings (LA-DATA-PAGE-1, widened by LA-PIPELINE-MAPPINGS-1 2026-11-03).** A Mapping is a Dataset entry
  `{origin: mapping, basedOn, physicalRef}` that reads another Dataset's stored rows its own way.
  - **Its base** (`DatasetEntryRules.validateMapping`) is any stored, plain Dataset: one with a `physicalRef`, not
    itself a Mapping, and none of `sql`, `view`, `sourceName`, `calculated`. Add data and Pipeline- or Job-fed
    Datasets both qualify. The Mapping's `physicalRef` must be exactly its base's, and it may name only the base's
    columns.
  - **It never widens who reads the base.** `ComponentAccess.canView` judges a Mapping on its base's envelope too,
    resolved through `ComponentAccess.DatasetEntries` (installed by `ControlApi`, cached per request). So do the
    caller-less `canViewAs(…, configRoot)` used by `ScheduledIndexBuild` and `StandingDetection`, and
    `PostgresPublishJobType.canRead`. A hidden or missing base makes the Mapping read as absent. Delete judges only
    the Mapping's own envelope, so an orphan stays removable.
  - **Its columns can drift.** A base can lose a column after the Mapping is saved. Preset roles
    (`PresetQueries.roles`) then refuse 422 with the column named, instead of falling back to the first two classified
    columns, which used to answer a different graph silently. The Data page marks such a Mapping.
  - **On the Data page** (la-app), every Dataset that may be a base, and lists at least two columns, gets the Entities
    and Mappings cards. Picking From or To starts its "holds" at the base's classification. Pipeline-fed Datasets show
    no arrivals: `GET /datasets/{id}/arrivals` serves Add data Datasets only.
  - **A Mapping has its own link index.** It is built only when a `la.index.build` Job names the Mapping.
- **Entity profiles read `defaultLinkMapping` (F-9).** `EntityTypeBindings.resolve` adds the Dataset's own default
  mapping after saved views and published index manifests (skipped when one of those already names the same two
  ends), so link roles and the `links` section are assessed with no saved view. Pinned by
  `ControlApiOnboardLinkAnalysisTest`: wallets registered with a default mapping are assessed, agents without one
  are "not assessed"; red with the fallback removed.
- **The index is built in the user's run, in every edition (DT-16; operator 2026-11-05).** The commit, Add a file and
  Run now call the `LinkIndexBuilder` SPI (the geo-link bridge, `ScheduledIndexBuild`) for the default mapping (source =
  from, target = to, kind, time, weight = amount; no attribute columns; owner = the importer; a first build is FULL),
  not a signal-driven `la.index.build` Job: a person's import always gives a fresh graph. Where Pipelines run on their
  own (`pipeline.automation`, declared by `inspecto-ops` since 2026-11-05), a feed's scheduled runs are indexed by the
  Space's Job; the index layer makes a build that meets a running one wait and re-plan (`BUILD_IN_PROGRESS`). Until
  2026-11-05 the in-run build was skipped where that feature was present (`followOnInRun`, `ImportFollowOn.deferred`,
  both removed); the Expectations are evaluated in the run in every edition too (DT-15). The in-run build waits for a
  running build of the same index (wait budget -1: bounded waits within its 300 s timeout). The summary reports `index:{result, edges, nodes}`.
- **The entry keeps its last import (`LA-ONBOARD-RESIDUALS-1` item 7, as built 2026-10-28).** The commit and *Add a file*
  write `lastImport {at, replaced, rowsBefore, rowsAfter, batch, file}` onto the Dataset's registry entry
  (`OnboardImport.Job.lastImport`; `batch` = the run's ledger `consignment_id`). The commit puts it in the entry it
  registers; Add a file re-reads the entry (an edit made meanwhile is kept) and writes it with `archive=false` (a result
  stamp, no version in the history) BEFORE the replaced rows are dropped, so a failed write still restores them. No new route: the home page's *What changed in your data* already lists these entries.
  *Run now* imports no file and records none. Session decision (assistant-decided under operator delegation, pending
  confirmation): only the LAST import is kept, not a history - the card shows one line per Dataset. Pinned by
  `ControlApiOnboardFilesTest.addAFileReplacesOrAppendsAndRunNowReRuns`.
- **Keep only some rows (`ADD-DATA-ROW-FILTER-1`, as built 2026-11-05; DT-7: Basic no-code shaping).**
  - **The screen.** The review step ("2. Check the columns") has a *Keep only some rows* block of property rows. Each
    row is a column picker (the kept columns, by their new names), a condition picker (`rowOps`: is / is not for
    every type, contains for text with a *Match case* switch (off = ignore case; the card adds "(exact case)"), is between for numbers, dates and times) and value pen fields (From / To for a
    range; the box type follows the column type).
  - **Holding Create back.** An incomplete condition, or one on a column left out, shows its reason on the row
    (`conditionProblem`) and keeps Create disabled.
  - **What is sent and shown.** The sentence "Keeps rows where …" (`keepsRowsLine`) sits under the block and on the
    Dataset card (`importFilter` on the entry; every card host passes it through `rowConditions`). The run summary
    adds "N rows were left out by the row filter", and each arrival "N rows left out by the filter". The commit
    sends `filter` only when there is a condition.
  - **Server side.** The contract, the choice of the Pipeline's `csv_settings.where` over the schema, the
    no-raw-SQL rendering and the counting are in
    [`api-onboarding-authoring.md`](../processor/api-onboarding-authoring.md) ("Keep only some rows").
  - **Specs.** `add-data.component.spec.ts` (two `ADD-DATA-ROW-FILTER-1` cases), `import-dataset-card.component.spec.ts`
    and `add-data.model.spec.ts` ("row filter words and checks").
  - **Edit filter on the card (`ADD-DATA-FILTER-EDIT-1`, as built 2026-11-06).** `ImportFilterEditComponent`
    (`inspecto-import-filter-edit`) sits on the Dataset card, under the Add a file row. It holds the same condition rows,
    over the Dataset's columns from `GET /datasets/{id}/filter`. It says "Applies to files added from now on; rows
    already imported stay." (on a Postgres snapshot: "Applies at the next Refresh now, which copies the table again and
    replaces every row."). Save waits while the card runs an import. Removing every row shows "Every row is kept." and saves `[]`. *Edit filter* follows the
    card's Add data lock. After a save the card's "Keeps rows where …" line follows the saved conditions until the host
    passes a fresh entry. Shared helpers in `add-data.model.ts`: `conditionState`, `conditionValueType`;
    `conditionProblem` takes `{name, type, drop}`. Server side: `api-onboarding-authoring.md` ("Edit filter").
    Specs: `import-filter-edit.component.spec.ts` and one card case.
- **Change a column's Entity Type later:** `PUT /datasets/{id}/columns/{column}` (`canAuthorWorkbench`, edit access
  to the Dataset). The profile cache keys on the binding signature, so the next read re-assesses.
- **Deleting an Add data Dataset retires its import Pipeline** (`LA-ONBOARD-ORPHAN-PIPELINE-1`, `LA-ONBOARD-RETIRE-GAPS-1`).
  `DELETE /components/dataset/{id}` claims the retirement (`OnboardImport.claimRetirement`) and retires BEFORE the entry
  is removed: the Pipeline is unregistered and its config, schema and config history removed (the data directory is
  kept); the step is idempotent and the Pipeline file goes last, so a failed delete is simply retried. The response then
  carries `retiredPipeline`. Detail: [`api-onboarding-authoring.md`](../processor/api-onboarding-authoring.md).
- **Not built here:** the screens (phase 4), the Start box reading `primaryEntityType` (phase 6), Reference details
  on nodes (phase 5), and the C1 format detections (another session).

## 6.11 Help on every screen and the onboarding counters (LA-ONBOARD-1 phase 8, as built 2026-10-22; UC-15, A-9, A-10, OB-13)

`LA-ONBOARD-1` closed with this phase; the plan is archived
(`docs/archived-documents/plans-archive/la-onboarding-plan.md`). What phases 1-7 built is in [architecture-la-core.md#34-masking](architecture-la-core-masking.md#34-masking) §3.4 (*Start from...*,
*My work*), §6.10 and [architecture-la-core.md](architecture-la-core.md) §8 (*Trust vs level*); the acceptance drive is
[`tests-live-drives.md#onboarding-acceptance-drive-la-onboard-1-2026-10-22`](tests-live-drives.md#onboarding-acceptance-drive-la-onboard-1-2026-10-22).

* **Onboarding counters (OB-13).** `com.gamma.util.OnboardingCounters` (inspecto-util, below both the host and la-api,
  like `IngestActivity`): a process-wide static holder of ONE append-only file per installation,
  `onboarding-counters.tsv`, one line per event, `<ISO instant>\t<event>[\t<step>]`. The closed vocabulary is the enum
  `Event` (`launch`, `space_created`, `first_dataset`, `first_graph`, `guide_completed`, `step_abandoned`) and the set
  `STEPS` (`space`, `data`, `columns`, `graph`, `list`, `add_data:file`, `add_data:columns`, `add_data:create`); a step
  word outside it is dropped, so nothing a person typed can reach the file. `first_*` and `guide_completed` are written
  once per installation (`install` reads the file, so a restart does not repeat them). `record` never throws: a full
  disk loses the count, never the action. With no file installed every call is a no-op.
  * **Where the file lives** (decided here): the server-wide document home, the same resolution as `scheduler.toon` and
    `la-limits.toon` (`SchedulerRoutes.systemDocPath`: `-Dsystem.config.dir`, else the spaces container root, else the
    sole Space's write root), not `-Ddata.dir`, which is per Space. Reserved in `ReservedConfigPaths` (an import must
    not forge it). No home = `recording: false`.
  * **Who records what.** `OnboardingRoutes.register` installs the file at boot and records `launch` (so "first launch"
    = the first server start of this installation, the drive's clock start; Settings → Getting started counters
    labels the two rows *First server start* and *Server starts*, 2026-11-05). `SpaceRoutes.createSpace` records
    `space_created` (template, sample or bare); `OnboardImport.Job.finishCommit` records `first_dataset` after the undo
    stack is cleared (inline and 202 commits alike); `InvRoutes.project` (projection AND the Start box's neighbours read)
    records `first_graph` when the answer holds a link. The browser reports only what it alone sees through
    `POST /onboarding/events {event, step?}`: `guide_completed` (the home page, once per app load) and `step_abandoned`
    (Create Space dialog closed without a Space; Add data left with a file chosen - `add_data:file`, a draft -
    `add_data:columns`, or a run in flight - `add_data:create`). Any other event, including `first_graph`, is 422: a
    browser cannot claim a milestone.
  * **Routes** (`OnboardingRoutes`, core, every edition): `GET /onboarding/metrics` (`canAdminister` in the manifest;
    with no sign-in anyone) answers `{recording, firstLaunch, launches, spacesCreated, firstSpace/Dataset/Graph,
    guideCompleted, each with *Seconds from the first launch, abandoned {step: n}, abandonedCapReached}`;
    `POST /onboarding/events` is a `self-limiting` exemption (open to any signed-in caller: a beginner is who reports it)
    on the standard rate bucket (`ControlApi.isRateLimited`). The file keeps at most
    `OnboardingCounters.ABANDONED_CAP_PER_STEP` (1000) `step_abandoned` lines per step, re-counted from the file at
    install, so an open route cannot grow it without bound; a report past that answers 200 and is dropped, and
    `abandonedCapReached` turns true (`LA-ONBOARD-RESIDUALS-1` item 11, 2026-10-23). Pinned by
    `ControlApiOnboardingCountersTest` (file, vocabulary, 401/403/200, 429), `OnboardingCountersTest` (the cap
    across a restart), and the milestone assertions in `ControlApiSpaceSamplesTest` and
    `ControlApiOnboardLinkAnalysisTest` (falsified: without the `InvRoutes` record the first graph stays null).
  * **SPA**: `OnboardingEventsService` (`src/app/inspecto/onboarding/`, fire-and-forget, a failure is silent) and the
    la-app **Settings → Getting started counters** section (server audience, read-only, listed when there is no sign-in
    or the person holds `canAdminister`).
* **Help rules (UC-15), all deterministic (A-9).**
  * **What can I do next?** - a button in the la-app rail (`la-shell.component.ts`), so on every screen.
    `next-steps.ts` (pure) turns the Space guide's `SpaceGuideState` plus *may create a Space* (`FirstRun.canCreate`) and
    *why Add data is locked* (`AddDataAccess.lockReason`) into at most three steps, the recommended one first, each with
    one line of why; `NextStepsDialog` draws them (a step the person may not take is an `inspecto-locked-action`) above
    the Space guide.
  * **Terms on first use** - `<inspecto-term term="...">` (`src/app/inspecto/onboarding/term-help.component.ts`, shared,
    usable by the console): the word, a **?** button (tooltip and accessible name carry the explanation, `aria-expanded`
    discloses it inline). `TERM_HELP` holds the sentences; a spec keeps them short and free of Pipeline words. Placed at
    the first mention on Entities, Columns, Entity Lists, Start from..., My work and Save as a case. ⚠ The input is `term`,
    not `id`: a static `id="..."` would also set the host's DOM id and duplicate it.
  * **Empty states** now name a reason and one action: Entities → *Open Columns* (switches the tab), Columns → *Add
    data*, Entity Lists → *New list* (for someone who may write lists; otherwise the existing line says why not), the
    Start box's no-Dataset line points at the *Add data* / *Explore a Dataset* cards under it, the saved-views menu
    says how to fill it, and My work says when the cases could not be read (*Try again*) instead of "No case yet".
* **Found by the acceptance drive and fixed here** ([`tests-live-drives.md#onboarding-acceptance-drive-la-onboard-1-2026-10-22`](tests-live-drives.md#onboarding-acceptance-drive-la-onboard-1-2026-10-22)):
  * **Add to a list was a dead end on the explorer.** `growEntityType` (used by *Add to a list* from Find something
    interesting and by Exclude → Add to a list) knew only the Investigation binding, the Domain Profile's `investigate`
    type and typed canvas nodes; a graph drawn by a Start box start has none, so the dialog offered no list and no *Create
    a new list…*. It now falls back to the start Dataset's `primaryEntityType` (`discoverDataset()`), and the dialog says
    why and where to make a list when nothing can take the entities.
  * **No screen turned Add data on.** With sign-in `data.self_import` is off until an administrator turns it on, and the
    locked screen says "ask an administrator" - who had only `PUT /settings/data`. la-app **Settings → This Space → Add
    data** (`AddDataSettingsComponent`, `OnboardService.setDataSettings`) is that switch (editable with no sign-in or with
    `canAdminister`, read-only otherwise), and the Add data page shows "You may turn it on: Settings, Add data" to the
    person who may. **Superseded 2026-11-15 (`LA-FULL-CYCLE-TEST-1` phase 1, D2):** that line was missed in a live
    drive, so the la-app Add data page (`add-data.page.ts`) now draws one panel with a primary **Turn on Add data**
    button in place of the locked reason for that person (`OnboardService.setDataSettings(true)`, then the Add data
    screen opens in place; a refusal is shown under the button). Anyone else reads the locked reason, which now says
    only what is true of every Space: "Add data is turned off in this Space; an administrator can turn it on"
    (`OnboardRoutes.SELF_IMPORT_OFF`, mirrored by `SELF_IMPORT_OFF` in `add-data.model.ts`). Create a Space in the la-app
    turns it on for the new Space (`FirstRun.createSpace`). The default is unchanged: off wherever people sign in, and
    no import can write `data.toon`.
  * The Link Analysis footer no longer says "pipeline planes" with no graph drawn (no Pipeline word on a Basic screen).
* **Two phase 7 leftovers fixed.** (1) **A case's name came back as its id in My work**: `ComponentStore.write` stamps
  the content's `name` with the id (in-file identity = URL id), so the display name a save wrote under `name` was
  overwritten on every save. The view codec (`link-analysis.service.ts`) now also writes `title` and reads
  `title ?? name ?? id`; older views read as before. The Geo map's saved views had the same defect; fixed in §6.12.
  (2) **One purpose vocabulary**: `ENTITY_LIST_PURPOSES` now carries the
  plain words (*Watch these* · *Never follow these (exclusion)* · *Known good* · *Block*) and `purposeWords()` reads
  them; the Entity Lists page, New list and Add to a list all say it that way (`PURPOSE_WORDS` is gone).

## 6.12 Explorer defects found by the acceptance drive, fixed (LA-ONBOARD-DEFECTS-1, as built 2026-10-23)

Each fix carries a test that fails on the code before it (falsified).

* **The index summary counts the whole index.** After *Add a file* in append mode the summary said "2,215 links between
  624 entities" for a Dataset of 4,430 rows over 312 accounts: `ScheduledIndexBuild` reported the APPEND build's own
  `IndexBuilder.Result` (the delta's edges) and the `nodes` table's row count, which after an append holds the parent's
  rows plus the delta's (an entity in both counts twice; only a COMPACT folds them). The outcome - BUILT and UP_TO_DATE
  alike - now reports what the published version holds: edges = the manifest's `out` rows, nodes =
  `IndexBuilder.distinctNodes(versionDir, manifest)` (a `count(DISTINCT id)` over the version's `nodes` files, read only
  when the manifest has deltas; a failed count falls back to the row count and never fails the build). The index status
  route's `nodes` (`IndexRoutes`, the manifest row count) is unchanged. Test: `ControlApiOnboardLinkAnalysisTest`
  "afterAnAppend...".
* **A start is typed by the Entity Type it names.** `drawStart(ds, values, entityType?)` types the drawn mapping by the
  Start box's pick (one entity, a paste), the Entity List's own type (a list), or the type the graph on screen was drawn
  as (*Start from these*); only with none does the Dataset's primary type (`startCatalogue`'s `mapping.entityType`) stand.
  It was always the primary, so a wallet started over a transfers file was read, normalised and masked as an account.
  A case saves the type as the optional `start.entityType` (read defensively by `startOf`; older cases read as before),
  and `growEntityType` (what *Add to a list* types a list by) reads the drawn mapping's type before the primary.
  Specs: `link-analysis.component.spec.ts` "a start types its mapping...", "a list start is typed by the list...",
  `link-analysis.service.spec.ts`.
* **The type-ahead refreshes on returning to *One entity*.** The Start box's suggestions stream read the kind only when
  another source emitted; the kind is now a source of its own, so a Dataset or type picked while on *A list* is asked
  for on the way back. Spec: `link-analysis-start-box.component.spec.ts` "switching back to One entity...".
* **A masked type can be revealed in the type-ahead.** `POST /inv/entities/{type}/suggest` takes an optional
  `reveal: true`: it needs `canRevealLinkEntities` (403 otherwise, checked in the route as the hand-off does; the route
  stays a `read-shaped` exemption), lists the masked type's raw values and counts per prefix, answers `revealed: true`
  and is audited with `revealed` on `LINK_ENTITY_SUGGESTED`. Without the ask nothing changes for anyone. The Start box
  never asks on its own: on a masked answer it offers someone who may reveal (`LensService.canRevealLinkEntities`) the
  button *Show its values (you may reveal them; recorded)*; the reveal holds for that Entity Type only. Tests:
  `ControlApiEntitySuggestTest` "aRevealerAskingReveal...", the start box spec "a masked type: someone who may reveal...".
* **Saved-view names on the Geo map and in Studio.** The Geo map's view codec (`geo-map.service.ts`) writes `title`
  and reads `title ?? name ?? id`, as the Link Analysis case codec does (§6.11); the Studio explore Widget's saved-view
  picker (`explore.component.ts`) shows `content.title` before `content.name`. Specs: `geo-map.service.spec.ts`,
  `explore.component.spec.ts`.
* **Sign-out and sign-in in one tab.** `KeepAliveRouteReuseStrategy` keyed a kept page on Space and person, but a
  sign-out clears the person (`SessionService.onAuthLost`) BEFORE it navigates to sign-in, so the page was kept for
  "nobody"; the next person landed back on Link Analysis before the session had read their name - "nobody" again - and
  got the previous person's page. With sign-in on, `shouldDetach` now keeps a page only while someone is signed in: a
  page left by nobody is destroyed. Without sign-in (Basic) nothing changed. A sign-out therefore also ends what the
  same person had open. Spec: `keep-alive.strategy.spec.ts` "signing out on the page and in as someone else...".
* **An Add data import is shared with its Space** (decided here: the smallest correct fix; the alternative, a *Share*
  control on the import's Dataset card, needs a sharing route - `PUT` on the component cannot drop a `shares` key and
  the sharing model names roles and users, not "everyone in this Space"). The commit no longer writes `shares: []`, so
  the entry behaves like every other Dataset in the Space: `owner` (the importer) is provenance, a colleague can view and
  start from it, and changing it still needs the route's capability (`canAuthorWorkbench` for its columns,
  `canUploadData` for *Add a file*). A Dataset made private through the component routes stays private. Imports
  committed before this change keep `shares: []`; nothing migrates them. A person who wants a file to themselves uses
  the analyst upload ([architecture-http-and-ui.md](architecture-http-and-ui.md) §6.6) into their own Collector. Test: `ControlApiOnboardFilesTest`
  "withSignInSelfImportIsOffByDefault...".

## 6.13 Onboarding residuals (LA-ONBOARD-RESIDUALS-1, as built 2026-10-24)

Calls marked * were assistant-decided under operator delegation, pending confirmation.

* **One definition of "people sign in".** `BootstrapRoutes.edition()` now asks `EditionMaskingDefault.signsIn()` (an
  `auth.mode` other than `none`, or an installed Authenticator), the same test the masking default uses; before, an
  Authenticator with no `auth.mode` made masking `typed` while `/bootstrap` still said `basic`. Test:
  `BootstrapEditionTest.anInstalledAuthenticatorMeansSignInEvenWithoutAuthMode`.
* **Role label.** The Inspecto LA Access screen shows `pipeline-developer` as *Data builder* (`AccessRolesComponent`
  takes an optional `labels` map; the LA app passes it). The id is unchanged in `roles.toon` and the console.
* **Add data settings** offers *Try again* after a refused change or a failed read (it reads the setting afresh).
* **Commits stay inline up to `import.inline_max_bytes`*** (default 64 MiB, per Space; 0 sends every commit to a
  followed 202 run). Not benchmarked; the knob is the bound, so there is no code change.
* **Find paths (server) stays unlocked on Basic*:** `docs/EDITIONS.md` lists it for all four editions.
* **Settled 2026-11-05:** item 3 - an import's Pipeline stays `trigger: manual` (DT-17 as narrowed by `BASIC-ONBOARD-1`)
  until *Keep this up to date* puts it on a schedule; the `pipeline.automation` feature, which `OnboardImport`,
  `DatasetFeed` and `DatasetCompaction` read, is declared by `inspecto-ops` on Standard and up (operator 2026-11-05), so
  Standard offers *Keep this up to date* and a retention policy while Basic's screens stay locked; a person's import still
  builds the link index inside the run in every edition. A Case saved after *Start from these* keeps no exclusion list written
  on the graph before it: a new graph starts from the Case's members only, by design. The counters' "first launch" is
  labelled *First server start*.
* **Still open:** no-code row filtering (6; in Basic by DT-7, not built) and a real drag-and-drop of a file onto Add
  data by a person (the acceptance drive handed the files to the file chooser by script).
