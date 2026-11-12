---
type: Concept
title: Link Analysis — data onboarding
description: How data reaches Link Analysis and what a first-time user meets - Add data and the shared import route, help on every screen and the onboarding counters, the explorer defects and onboarding residuals, column roles, discovery in Basic, arrivals, arrival feeds, Dataset storage and the watched folder (sections 6.10 to 6.13 and 6.15 to 6.20 of the backend architecture).
resource: la/
tags: [module, link-analysis, onboarding, add-data, arrivals, dataset, architecture]
timestamp: 2026-11-12T00:00:00Z
---

# Link Analysis — data onboarding

How data reaches Link Analysis and what a first-time user meets: Add data and the shared import route, help on every
screen and the onboarding counters, the explorer defects and onboarding residuals, column roles, discovery in Basic,
arrivals, arrival feeds, Dataset storage and the watched folder.
Section numbers continue those of [architecture-backend.md](architecture-backend.md), from which this page was split on 2026-11-12.

## 6.10 Add data: the shared import route (LA-ONBOARD-1 phase 2, as built 2026-10-22)

The route family lives in the **core** (every edition, Basic included, not under `/inv`); its contract, gates,
limits and the commit's write order are in
[`api-onboarding-authoring.md`](../processor/api-onboarding-authoring.md) ("Add data"). What Link Analysis relies on:

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
(`docs/archived-documents/plans-archive/la-onboarding-plan.md`). What phases 1-7 built is in [architecture-masking.md](architecture-masking.md) §3.4 (*Start from...*,
*My work*), §6.10 and [architecture-backend.md](architecture-backend.md) §8 (*Trust vs level*); the acceptance drive is
[`tests-onboarding-live-drive.md`](tests-onboarding-live-drive.md).

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
* **Found by the acceptance drive and fixed here** ([`tests-onboarding-live-drive.md`](tests-onboarding-live-drive.md)):
  * **Add to a list was a dead end on the explorer.** `growEntityType` (used by *Add to a list* from Find something
    interesting and by Exclude → Add to a list) knew only the Investigation binding, the Domain Profile's `investigate`
    type and typed canvas nodes; a graph drawn by a Start box start has none, so the dialog offered no list and no *Create
    a new list…*. It now falls back to the start Dataset's `primaryEntityType` (`discoverDataset()`), and the dialog says
    why and where to make a list when nothing can take the entities.
  * **No screen turned Add data on.** With sign-in `data.self_import` is off until an administrator turns it on, and the
    locked screen says "ask an administrator" - who had only `PUT /settings/data`. la-app **Settings → This Space → Add
    data** (`AddDataSettingsComponent`, `OnboardService.setDataSettings`) is that switch (editable with no sign-in or with
    `canAdminister`, read-only otherwise), and the Add data page shows "You may turn it on: Settings, Add data" to the
    person who may.
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
  the analyst upload ([architecture-http-surface.md](architecture-http-surface.md) §6.6) into their own Collector. Test: `ControlApiOnboardFilesTest`
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

## 6.15 Column roles (LA-COLUMN-ROLES-1, as built 2026-11-04/05)

A Dataset column has a **role**, stored once on the registry entry as `columns[].role`, one vocabulary with Studio:
`entity | temporal | measure | dimension | detail`, shown as **Entity · Time · Measure · Category · Detail**
(`docs/GLOSSARY.md` *Column Role*; operator decision B). A Measure may carry a `unit`; a Time, Measure or Category
column may say `carried: false`. A Mapping copies its base's columns with their roles and marks.

- **The rules** (`DatasetEntryRules`, run on every Dataset write): a role is one of the five or absent; an Entity column
  holds an Entity Type (`classification`); a `unit` is a Measure's only; a Measure, and the primary Measure, is a
  number column (`number`, BIGINT, DOUBLE, DECIMAL(p,s)...; a column without a type is not checked); `carried` is a
  boolean. An entry without roles still loads and reads back-compatibly: holds an Entity Type → Entity; the mapping's
  primary Time or `eventDate` → Time; its primary Measure → Measure; its kind → Category; on the Data page a
  date/timestamp column → Time; anything else → Detail.
- **A base's roles from its Mappings (operator 2026-11-05, display only).** On the Data page's Columns card, a base's
  column that states no role and that the base itself reads as a Detail shows the role the primaries of the Mappings
  built on it give it (primary Time → Time, primary Measure → Measure, kind → Category; `data-page.ts` `mappingRole`),
  with a note naming them (*From Mapping mm_wallet_links - not set on this Dataset*). Mappings that disagree leave it a
  Detail. Nothing is written: only a person's pick (or a Unit, which saves the Measure) stores a role. The server's
  readers (`DatasetEntryRules`, presets, profiles) do not derive it.
- **Primaries.** `defaultLinkMapping` names `primaryTime` (dates each link: the window, newcomers, playback's first
  choice, Reconciliation's `DatasetRelation.temporalColumn` when several Times exist) and `primaryMeasure` (presets'
  sums). `time` / `amount` are their old names, read for stored entries and in an Add data commit body; two different
  columns under both names are refused. A primary's column, when it states a role, has the matching role. Readers:
  `DatasetRelation.temporalColumn`, `PresetQueries.roles`, `EntityTypeBindings`, `OnboardImport`'s index build, the LA
  start path (`readLinkMapping`), Studio.
- **Add data** proposes a role per column (`entityTypes.columns[].role`, with evidence; `distinct` for a text column):
  Entity when it holds an Entity Type; the link's kind / time / measure take Category / Time / Measure; else a DATE or
  TIMESTAMP is a Time, a DOUBLE (or a BIGINT named as an amount) a Measure, a text column whose sampled values repeat
  under 200 distinct a Category (sure under 20), anything else a Detail (always sure). The commit writes the roles; a
  stated role that contradicts the Entity Type, the link's primaries or a Measure on text is 422. The Roles step shows a
  role row per column and offers each primary only among columns of its role; a Measure whose type is changed to text
  becomes a Detail.
- **Carriage (operator decision A, 2026-11-04).** The **link index** carries every Time, Measure and Category column
  (`DatasetEntryRules.carriedColumns`, column order, never a link end, at most 50 = the index build's own bound): Add
  data's first build passes them, and the Edge index tool's first-build form is prefilled with them. A **link read
  carries none by default**: a read's `attrCols` are grouping keys (server `edgeSql`, the UI fold key), so carrying a
  timestamp or an amount would unfold the links. Play's Time picker (the timeline menu and a picker on the playback
  strip) lists the dated link columns and the Dataset's Time columns, the primary first; picking one the links do not
  carry re-reads them carrying that one column, and only while the canvas is still the plain query's result
  (`plainGraph`: a seeded start, an Investigation, an expand, a Grow or a push is never replaced by the whole Dataset).
- **Filter by role** (`role-filters.ts`, `link-analysis-role-filter.component.ts`): a range per Measure, a value list per
  Category (its distinct values read with one SQL per column, at most 200, as toggle chips with a find box past 12; read
  once per Dataset, a late answer for a Dataset no longer drawn is dropped), a window on any Time column. Applying writes
  the conditions into the one predicate tree - taking out only the conditions the panel wrote last time (JSON-equal), so
  the analyst's own and a loaded saved view's conditions stay even on a role column; an OR or negated root becomes one
  group ANDed with them - and pushes it as the read's `filter`, which the server renders over the Dataset in SQL or,
  when the read is served from the link index, over its attribute columns (`IndexedRead.renderFilter`) - so no column
  needs carrying for it. A Category value holding a comma cannot travel in an `in` list and is left out.
- **Entity profile measures** (`EntityProfiles.measures`): for each binding of the Entity Type, each Measure of that
  Dataset over the rows naming the Entity in that column - rows holding a number, sum, lowest, highest, the unit - inside
  the profile's window on the binding's time column; a value that is no number is not counted (`TRY_CAST`; sums rounded
  to 6 places through BigDecimal, a non-finite one is null). Inside a window a binding with no time column adds none.
  The bound column and any column holding an Entity Type are never a measure here - and `DatasetEntryRules` refuses a
  Measure that holds an Entity Type ("an identifier, not a measure"), because its sums would surface raw ids the
  profile masks; the Data page drops an unclaimed Entity Type when a column becomes a Measure. The Entity page shows
  them as **Measures** (absent when there are none).
- **Entity Type profile measures** (`LA-COLUMN-ROLES-2` D-1 b, 2026-11-05; `EntityProfiles.typeMeasures`, section
  `measures`): one row per binding x Measure (the same column rule, `measureColumns`) over the rows where the bound
  column names an Entity (non-empty normalised key) - rows, sum, lowest, highest, unit - in ONE aggregate statement per
  bound Dataset (each binding is its own row, so no row is summed twice), plus `distribution`: p50 / p90 / max of the
  per-Entity totals (`quantile_disc`) and the Entities measured, one GROUP BY per binding. That read groups at most
  `entity_profile.measure_sample_entities` Entities (Space limit, default 100,000, 1..10,000,000): above it a key-hash
  sample, `hash(k) % every = 0` with `every = ceil(approx distinct x 1.1 / cap)` (the headroom keeps the hash spread under
  the cap), the cap as a hard `LIMIT`; the answer says `sampled`, `every`, `sampleEntities`, `approximate`, and
  `truncated` when the LIMIT may have cut it. Optional `from` / `to` on `GET /inv/entity-types/{type}/profile` scope this
  section only (on each binding's time column; a binding with none is not assessed inside a window), cached per window.
  A failed or timed-out per-Entity read is `distribution: {assessed:false, retryable}` beside the row's sums and keeps
  the section out of the cache. The Entity Type page shows it as **Measures**. Measured on 200,000 synthetic Entities
  with a cap of 1,000: 1 in 232, under 1,000 grouped, the LIMIT not reached (`ControlApiEntityProfileScaleTest`).
- **Roles from the record type (`ENTITY-TYPE-RECORD-ROLES-1`, 2026-11-05).** A record type names its usual Time,
  Measure and Category columns by hints (entity-store `spec.md` *A record type's usual roles*; the one rule is
  `EntityShapes.hintedRole` / UI `hintedRole` in `column-roles.ts`, kept alike). **Add data:** after the record type is
  proposed, `EntityShapes.recordTypeRoles` gives each column a hint names its role - never one holding an Entity Type
  or chosen as the link's kind, time or measure - sure when the record type is, and keeps the role proposed without it
  as `base`. Picking another record type on the screen re-proposes (`roleUnder`) every role still as proposed: back to
  `base`, then the new record type's hint; a role the person picked stays. **Data page:** under *Each row is*, the
  roles the record type proposes for columns that state none or Detail and are none of the link mapping's
  (`recordTypeProposals`; a role the column already reads as is not repeated), saved together by *Use these roles*;
  a role the person set is never proposed over. The Holds picker marks *Shape matches* per column from
  `GET /inv/datasets/{id}/shapes` (read once per page per Dataset).
- **A Mapping's own carriage (`LA-COLUMN-ROLES-2`, 2026-11-05).** The Mapping form has an *On each link* row per Time,
  Measure and Category column of its base (as the page reads the base's roles; never the Mapping's From or To),
  seeded from the base for a new Mapping and from the Mapping when editing it (`MappingForm.notCarried`,
  `carriedChoices`). The Mapping's entry writes its OWN `carried: false` marks, so its link index carries what it says,
  whatever its base says; a kept-off column whose base states no role gets the role the page shows (a date is a Time).
- **Link width (`LA-COLUMN-ROLES-2` D-2 a, 2026-11-05).** A link read may name `measureCol` (`/inv/projection`,
  `/neighbors`; an edge mapping's `measureColumn` on `/multi`): the server SUMs it per folded link as `total`
  (`TRY_CAST ... AS DOUBLE`; a link with no number has null) beside `cnt`, never as a grouping key - in the flat SQL
  (`edgeSql`) and, for a neighbours read, from the link index when it carries the column (`IndexReader.fold`'s
  `totalCol`; else flat, `column_not_indexed`). The canvas draws a link's width from `data.weight`
  (`link-width.ts` `weighLinks`): by its count (default, *Width: Count*) or by its total relative to the largest on
  the canvas (null or not positive = thinnest); the client folds add totals (10 + null = 10). The Display menu offers
  the Measure only when every read Dataset names the same primary Measure, and not on an Investigation canvas (it
  draws its Working Set by count); over a graph grown beyond the query it can be picked only once the links carry
  their totals (else *Run the query again first*); the choice is kept in a saved view (`linkWidth: 'measure'`).
  Not yet weighed: the dashboard link-view widget and the embed view.
- **Filter by role over several Datasets** (`LA-COLUMN-ROLES-2` D-3 b, 2026-11-05; `role-filters.ts`
  `withDatasetRoleFilters`, the panel's `sections`). Over a read of several Datasets (`entity-projection-multi`) the
  panel draws one section per edge mapping's Dataset, in mapping order, titled by its name, from that Dataset's own
  `columns[].role` (a Dataset with no Measure, Category or Time column gets none); each section is a group named by its
  heading and its controls are named `<Dataset>: <field>`, so the same column in two sections reads apart. Category
  values are read per Dataset (one SQL per column, at most 200), once per Dataset while it is drawn; a late answer for a
  Dataset no longer drawn is dropped. Applying writes each section's conditions as the own `filter` of every edge
  mapping over that Dataset on `POST /inv/projection/multi` (which narrows only that mapping,
  `ControlApiMultiDatasetFilterTest`) and re-runs the read as a push does (merged, stranded nodes marked). Only the
  conditions the panel wrote into the run on screen are taken out (JSON-equal, per Dataset; recognised by the mapping
  `filter` objects the push sent, which a Refresh or a Measure re-read keeps, so they never pile up - while from a new
  run's or a loaded view's mappings nothing is taken out; loading a view or Clear in the filter also empties the
  panel); the analyst's own mapping filter and the top-level `filter` stay. A section left empty sends no filter for its Dataset. Node mappings are not narrowed. A single-Dataset read is
  unchanged.

## 6.16 Discovery in Basic: presets, Discover, entity summary, query builder (LA-BASIC-DISCOVERY-1, as built 2026-11-03/05)

The operator's ask: profiling and discovery in Basic, querying the relationship data without SQL, preset queries. Four
read-only surfaces, all **Basic and up** (feature `geoLink`, Q-1), all over ONE Dataset's mapped roles, masked like
exploration, capped by the Space's limits and audited. The full Entity profile pages stay Standard (D-LAE4 unchanged).
Rows closed: `LA-PRESET-QUERIES-1`, `LA-BASIC-DISCOVER-1`, `LA-ENTITY-SUMMARY-1`, `LA-QUERY-BUILDER-1`; plan
[`la-basic-discovery-plan.md`](../../../../archived-documents/plans-archive/la-basic-discovery-plan.md).

| Route | Class | What |
|---|---|---|
| `GET /inv/presets?dataset=` | `PresetRoutes` | the pack's preset questions the Dataset can answer, its roles, the builder's role columns (`columns {time, measure}`) and the Space presets saved from the builder (`saved`) |
| `POST /inv/presets/{id}/run` | `PresetRoutes` + `PresetQueries` | one preset; read-shaped, EXPENSIVE, `LINK_PRESET_RUN` |
| `GET /inv/datasets/{id}/discover` | `DiscoverRoutes` + `DatasetDiscovery` | what is in a Dataset; EXPENSIVE, `LINK_DATASET_DISCOVERED` |
| `GET /inv/datasets/{id}/shapes` | `DiscoverRoutes` | the Entity Types whose shape each column's first 200 rows fit, marks only (the Data page's *Shape matches*; `ENTITY-TYPE-RECORD-ROLES-1`); cheap, not audited (no value leaves) |
| `GET /inv/entities/{type}/{key}/summary?dataset=` | `EntitySummaryRoutes` + `EntitySummary` | one entity over one Dataset; EXPENSIVE, `LINK_ENTITY_SUMMARIZED` |
| `POST /inv/datasets/{id}/query` | `QueryRoutes` + `QueryBuilder` | a builder question (or a saved preset's); read-shaped, EXPENSIVE, `LINK_QUERY_RUN` |

**Never free SQL (Q-2).** la-api may not depend on the engine (LA separation D-1), so `MeasureCompiler` is out of reach:
each surface renders its own fixed statement over the Dataset's real, quoted columns with bound values. The builder's
`QueryBuilder.parse` accepts exactly `by, rank, sumColumn, from, to, kinds, timeColumn, after, before, measureColumn,
measureMin, measureMax, min, top` and checks each column's ROLE (Time = `columns[].role: temporal` or the primary Time;
Measure = `role: measure` without a classification, else the mapping's primary Measure). A saved preset is parsed again
each time it is listed or run, so a stored query is never trusted as written. The window is half-open (`after`
included, `before` not), a Measure range inclusive.

**Roles.** `PresetQueries.roles` gives From, To, Kind and the primaries (`defaultLinkMapping.primaryTime` /
`primaryMeasure`, or their old names `time` / `amount`; else the classified columns, `*_kind`, the event date, the pack's
`measure_hints`). Without a mapping, From / To are (`PresetQueries.ends`, `LA-PRESET-ROLES-1` fixed 2026-11-05) the
Dataset's record type's parties (its `from_hints` / `to_hints` among the classified columns), else the first two
columns holding the SAME Entity Type, else the first two - so a subscriber's number and SIM are no longer read as a
link when the call's two parties are classified too. `DatasetDiscovery.measures` and `QueryBuilder.columns` add every Measure and Time column
(LA-COLUMN-ROLES-1). A role the Dataset lacks: the preset or saved question is not offered (Q-5); a Discover section is
`assessed:false` with why; a summary part is null.

**Presets (P3).** Code keyed by Industry pack id (`PresetQueries.forPack`; mobile money and telecom put one question
ahead of the core set), not `industry-pack.toon` entries: a preset's compile rule is code either way. Shapes: the
aggregate (group by one end; counterparts, links, total; a HAVING on the first measure), graph statements over the links
joined to themselves (`graphSql`: Two-way pairs, Short loops, Newcomers, Two steps), and the `timeToCashOut` Value
Measure. Reached by many, Reaching many and Newcomers read the link index's `nodes` table, Two-way pairs and Short loops
its `pairs_back` / `loops` (`LA-INDEX-PAIRS-LOOPS-1`), through `IndexedRead.select` (the Space's index switch and the
staleness refusal apply); each answer carries `source`. Counting from the index differs (each side apart, per kind of
link) and says so. **Space presets (Q-3)** are registry components `preset-query/<id>` `{label, description, query}`
(`ComponentStore`, read through the `DatasetProvider.presetQueries` seam), listed only to callers who may view them
(`ComponentAccess.canView`), a raw From / To in a stored query echoed as its alias.

**Discover (P1).** V4 measured the `EntityProfiles` union read (one Entity Type across every bound Dataset) at **112 s**
on IMS (39.4 M links, 12.35 M distinct numbers), so Discover never uses it. It reads THIS Dataset in cheap statements:
one pass for rows, the period on the primary Time and each Measure's count, sum and `approx_quantile(0.5)`; one
`approx_count_distinct` per Entity Type over its columns, keys normalised as the profiles normalise (`EntityKeySql`;
2.6 s on IMS, 0.15 % off the exact 18.5 s count); one `GROUP BY` on the kind (at most 50); and the 10 busiest per end
through the presets' path (`PresetRoutes.fromIndex` with Reaching / Reached by many, else the aggregate over the Dataset
under the statement timeout; a timeout is `assessed:false`, "a link index answers it quickly"). Cold Discover on IMS is
about 6 s (over the plan's 3 s); cached it is instant. **Cache:** per Space, Dataset entry, roles, limits, held types and
masking mode; stamped with the Dataset's `files:` input fingerprint AND the link-index versions currently published for
it, so an arrival or a new index recomputes. Entity ids are cached raw and masked on the way out. A failed or timed-out
read is answered but never kept (`retryable`; an unmeasured row count carries `rowsReason`). A Dataset without a
`files:` fingerprint (a view) is never cached. `busiest.fromType` / `toType` name the Entity Type a busiest row's
summary asks for.

**Entity summary (P2).** One statement per entity: the links naming it at either end (keys compared normalised under
the type's normaliser), grouped once by `GROUPING SETS ((), (o), (k), (d))` - headline, per counterpart, per kind, per
day - each set cut by a window rank (top 20 by links and top 20 by the primary Measure, 50 kinds, the 90 days before its
last seen). V4: a one-key read of IMS costs 3-4 s, so four statements would not do. A self-link or an empty other end
counts as a link but is no counterpart. **Key rule** as the Entity profile's: for a type masked under the Space's mode
the URL takes only the alias (a raw key is refused 422, never read; the UI resolves a raw key through
`POST .../resolve` in a body first); when the queried type is masked EVERY returned id is an alias
(`ExplorationMasking.forced`), whatever its columns are classified as - the first review found raw counterparts over
unclassified columns, the entity's own key among them through a self-link.

**V3 (pattern packs in the browser):** yes, over the loaded graph only (`matchPattern`, `matchBranchingPattern`); the
whole-Dataset run is `POST /inv/pattern/branching`. Discover therefore runs no pack.

**UI.** `link-analysis-presets` (under Basic's Start box) holds the pack questions, the saved ones (*... (saved here)*)
and `link-analysis-query-builder` (shown when the Dataset maps From and To; *Save as preset* writes the component).
`link-analysis-dataset-discover` sits on the la-app Data page (one card per Dataset; Draw is offered from Explore) and
under the Start box as *About <Dataset>*, following the Start box's pick; it drops its answer when its Dataset changes.
A busiest row opens `EntitySummaryDialog`; the node dialog's on-demand *Summary* reads the Dataset the graph was drawn
from (`summaryDataset`). Everything is read on demand: a large Dataset takes seconds.

**Gotchas.** DuckDB refuses the zone id `Z`: pass `ZoneId.of("UTC")`. The graph's own *Find something interesting*
already owns `discover.ts` / `link-analysis-discover.component.ts` - the Dataset panel is `dataset-discover`. A
component that imports `DataTableComponent` brings `MatDialogModule` into its injector, so a host spec mocking
`MatDialog` needs `TestBed.overrideProvider`. Section landmarks repeated per Dataset need unique names (axe
`landmark-unique`).

**Deliberately not done.** Bridges, Shared counterparts and Bursts presets; a Category value-list filter in the builder
beyond the kind of link (`LA-COLUMN-ROLES-2`); drawing from the Data page (it points to Explore); a live drive on the
Basic install (the plan's §5 check over IMS and Mobile money) waits for a redeploy.

**The Data page's layout and a Dataset's name (`LA-DATA-MENU-1`, `LA-DATA-DETAILS-1`, operator 2026-11-10).** The la-app
Data page is laid out like Settings: a menu of Datasets grouped by kind (Streams, References) on the left, the picked one
on the right (`?dataset=<id>` keeps it across a reload; the first listed shows when none is picked); nothing folds. A
filter box over the menu (shown past six Datasets) keeps those whose name, id or description hold every word typed.
The open Dataset's header shows its name, its id (copyable), its description and facts (kind, origin, columns,
Mappings, Time column); *Rename or describe* opens `DatasetDetailsDialog`, which re-reads the component and writes it
back with only `title` and `description` changed, against its `contentHash` (a concurrent change is refused with the
stale-write message, never overwritten). Opening a Mapping form also brings its base into view.
**Gotcha:** the shown name is stored as **`title`**, never `name` - `ComponentRoutes` stamps `name` with the id on every
write, so a renamed `name` is silently put back. The id never changes (Mappings, saved views and Pipelines refer to it).
The shared `DatasetsService.fromContent` reads `title` first, so every Dataset picker in the app shows the new name, and
a Studio save carries `title` through untouched.

## 6.17 Arrivals: the ledger, Data status and what a later file does (LA-ARRIVAL-LEDGER-1 + LA-ARRIVAL-MODES-1, as built 2026-11-03/05)

Every edition; Add data Datasets only (a Pipeline-fed Dataset answers no arrivals).

- **`GET /datasets/{id}/arrivals`** (`inspecto/.../control/DatasetArrivals.java`) is a READ over the engine's per-file
  audit ledger (`StatusStore.files`) joined to a sidecar `<dataRoot>/<id>/arrivals.jsonl`. The covered period is the
  min / max of the Dataset's `eventDate` over the arrival's own Parquet, read in a DuckDB sealed to `<dataRoot>/<id>`.
  Gates as `/freshness` (no capability); an entry whose import fields do not name ITS OWN Add data Pipeline reads nothing.
- **The sidecar** gets one line per file a run took (`DatasetArrivals.recordRun`): the upload's name, mode, sha256; every
  file's index outcome and its mode (`append` / `full` / `compact`). *Run now* records the drops it took (`sender: system`).
  A system line is keyed by name AND batch; a line written before senders were recorded reads as an upload. A file
  with NO line (imported before the sidecar existed, 2026-11-03, or its best-effort write lost) is an upload when its
  inbox name is Add data's own `add_<stamp>_<name>` (`DatasetArrivals.ADD_DATA_NAME`), shown as `<name>`; else a
  system's. A refused
  waiting file has only its sidecar line (`status: REFUSED`, `reason`, `setAside`) and is merged in by time.
- **Data status** is one component, `<inspecto-la-data-status>` (`link-analysis/src/add-data/data-status.component.ts`),
  under the Start box's Dataset picker and above the Rows dock table: *Received · covers · graph up to date / N files
  behind*. A refused arrival is left out. Extension point: `dataStatusLines()` in `add-data.model.ts`.
- **The Dataset card** (LA-STREAM-FACTS-1, 2026-11-07) says the same facts under their own labels in a `<dl>`:
  `datasetFacts()` (Origin, Filter, Received, Covers, Graph, Inbox / Kept up to date, Storage, Compacted; each only when
  known), built from the same parts as the one line (`dataClocks`, `feedFact`, `storageFacts`), so the words cannot
  drift. Its **Arrivals** are the shared `inspecto-data-table` (`arrivalRow` / `arrivalColumns`: File, Arrived newest
  first, How, Result, Rows, Left out, Covers, Columns; ten a page); a row a later replace set aside is muted and its
  Result says so.
- **V4:** on Basic every append (Add a file, or a drop Run now takes) builds the link index in mode `append`; only the
  first build is `full`. About 7 to 11 s per 10,000-row append at 1 M rows, end to end
  (`ControlApiOnboardLinkAnalysisTest#v4MeasuredAtScale`).
- **Three modes** for *Add a file*: `replace` (everything), `append` (add rows), `period` (D-2: the file's rows are added,
  then every older Parquet file loses its rows on the calendar days the file covers; `DatasetPeriod`, originals kept in
  `database.period-<stamp>/` until the import succeeded). `period` needs a When column. A rewritten file makes the next
  index build `full`.
- **The same bytes again** (D-3): append or period whose sha256 matches an upload whose rows are still held answers 409
  with the first import's date (`DatasetArrivals.alreadyImported`). Replace everything may always reload. A folder drop
  under the same name is skipped by the import Pipeline's duplicate policy and stays in the inbox.
- **Schema drift** (D-6, operator 2026-11-05): `OnboardImport.checkColumns` judges a later file by column NAME before
  anything lands. A missing column, or a delimited column at another position, refuses it naming each (422 for an upload;
  Run now, and Add a file for the drops its run takes, move a refused waiting file to `<id>/refused/`). An import with no
  header line is judged by its column count only.
- **A value a whole-number column would cut** (`DECIMAL-TEXT-NOT-BIGINT-1`, P2 defect from the 2026-11-06 Basic live
  check, as built 2026-11-06): the engine's `TRY_CAST('12.25' AS BIGINT)` rounds to 12 with no rejection, so
  `checkColumns` also refuses a later file when a column the Dataset reads as `BIGINT` holds a value with a fractional
  part ANYWHERE in the file (`OnboardDraft.Col.fraction`, one scan of the whole file, not the 1,000-row sample), with
  the same 422 / set-aside as a missing column: `'amount' holds whole numbers and this file has '12.25'`. `60.00` is
  taken (nothing is cut). Refused rather than quarantined because the arrival rules judge a whole file before it lands
  and nothing in the engine's cast marks the row. A Dataset's column type cannot be widened yet: the reason says to add
  the data as a new Dataset with the column as DOUBLE. The draft no longer proposes BIGINT for such a column (see
  `api-onboarding-authoring.md`). Test: `ControlApiDecimalColumnTest`.
- **New columns are kept as details** (`LA-ARRIVAL-NEW-COLUMNS-1`, operator 2026-11-06, as built 2026-11-06;
  `inspecto/.../control/DatasetNewColumns.java`). A run keeps the new columns EVERY file it takes carries (delimited
  text: the same leading new columns in the same order, since the engine reads by position; JSON / Parquet: by field
  name). Each kept column is appended to the import's schema (`raw.fields`, the next position for delimited text; a
  `keep` mapping) with the type its values imply (`DatasetNewColumns.typed`, operator 2026-11-06, as built in
  `DECIMAL-TEXT-NOT-BIGINT-1`): the draft's inference in every file the run takes (`0.50` and `47.00` are DOUBLE, plain
  digits BIGINT, a date with its format); whole and decimal numbers across files widen to DOUBLE; any other
  disagreement, or a number read through a decimal mark (`12,22`, `1,234.50`: a plain cast reads it as nothing), stays
  VARCHAR. The Pipeline is re-read
  (`CollectorService.refreshConfigs`, the registry's content hash sees the schema change), and the Dataset entry gains
  `{name, type (number / date / timestamp / boolean / string), role: detail, keptFrom {file, at}}`
  (a versioned write). All of it is undone if the run fails. The answer and the arrival carry `keptColumns`.
  **Left out**, as before (delimited files are rewritten with the columns kept, because the engine sets aside rows with
  more fields than it reads), named in `newColumns` with `newColumnsReason`, when: the person unticked *Keep new
  columns as details* (`POST /datasets/{id}/files?newColumns=leave`; Run now, the inbox poll, feeds and a snapshot
  refresh always keep); another file of the same run lacks it; its name is taken (a Dataset column or a schema field,
  case-insensitive) or cannot name a column; or the Space's approval policy holds `dataset` or `schema` changes (a
  column is then added only on its own). **Once kept, a later file without it is refused** like any missing column
  (operator 2026-11-06, not accepted blank), the reason saying `'fee' is missing (kept from 'wider.csv' on <date>)`.
  **One clock for person-facing times (`DATES-ONE-ZONE-1`, fixed 2026-11-06):** an arrival's `received` is the
  server's local wall clock without a zone (`yyyy-MM-dd HH:mm:ss`), so `<date>` above is `keptFrom.at` (an ISO UTC
  instant on the wire) turned to the server's local day (`DatasetNewColumns.localDay`), never its UTC date part; the duplicate-import 409 ("'x' was
  already imported on <date>", from the arrival sidecar line's instant) uses the same. The UI
  shows every server-recorded time through `ledgerTime()` (`add-data.model.ts`): a zoneless value as it is, a value
  WITH a zone (`compactedAt`, a carried compaction block's `received`) in local time. Event dates (`coversFrom/To`) are
  the data's own values and are never moved. Gap: a zoneless value is the server's clock and a zoned one is turned to
  the browser's, so they agree only when the browser and the server share a zone (always on loopback Basic).
  **Reads:** every read of the Dataset's Parquet already lines files up by name - the engine Dataset read
  (`DatasetRelation` via `SqlViews.readerOverLiteral`, `union_by_name=true`; Link Analysis, Discover, BI and the index
  build read through it), `/db/table` and `/db/query`, `countRows`, the arrivals' periods and *Replace the period*
  (`SqlViews.parquet(…, true, …)`), compaction (`UNION ALL BY NAME`); an older file reads the column as empty. The row
  filter (`where`) runs on the import, over each file before it lands, and every file it sees carries every column the
  Dataset reads, so a kept column never makes it fail. A typed kept column takes a range filter and can be a Measure;
  a later value that does not parse is stored empty and counted as a cast failure, as for any typed column, and a
  fraction into a kept BIGINT column is refused (above). Tests (typing: `ControlApiDecimalColumnTest`):
  `ControlApiArrivalNewColumnsTest` (older and newer files read together, the refusal, leave, the every-file rule, Run
  now keeping on its own, JSON, an approval policy, a row filter, compaction over mixed columns),
  `ControlApiOnboardLinkAnalysisTest#aKeptNewColumnRidesTheIndexBuiltOverOlderAndNewerFiles` (made a Category, the column
  rides a full index build over the mixed files).

**Deliberately not done.** Row-key upserts (with SCD2,
D-ING4); the inbox and *Keep this up to date* (`LA-ARRIVAL-FEEDS-1`); Dataset Parquet compaction (`LA-DATASET-COMPACT-1`);
a refresh of the Start box / dock status after a run (each reads once per Dataset); a live drive on Basic and Preview.
Plan: `docs/archived-documents/plans-archive/la-data-arrival-plan.md` §5a-§5d, §6.

## 6.18 Arrival feeds: the inbox and Keep this up to date (LA-ARRIVAL-FEEDS-1, as built 2026-11-05)

Plan [`la-data-arrival-plan.md`](../../../../archived-documents/plans-archive/la-data-arrival-plan.md) P3, decision D-1. Class
`inspecto/.../control/DatasetFeed.java`, registered from `OnboardRoutes`; gates as *Add a file* (`canUploadData`, 503 no
write root, 403 `data.self_import` off, then `OnboardImport.importDataset`: 404 unknown, 409 not an import Add data wrote
for THIS id).

| Route | What |
|---|---|
| `GET /datasets/{id}/feed` | `inbox` (absolute path on the server), `waiting` (count) and `files` (oldest first, at most 50, `truncated`), `automatic` (`pipeline.automation` present), `feed {kind: uploads \| folder \| sftp, every?, connection?, readyMarker?}` read from the Pipeline itself |
| `PUT /datasets/{id}/feed` | *Keep this up to date*: `{kind, every?, connection?, readyMarker?}`; 503 without `pipeline.automation` (Basic, D-1); 422 for a Reference, a bad kind, an interval outside 1m-1d, a marker that is not a file-name suffix, an sftp feed without an sftp Connection |
| `DELETE /datasets/{id}/feed` | back to uploads (`trigger: manual`, the collector Add data wrote); every edition |

- **The feed lives in the import Pipeline, nowhere else.** The PUT sets `imports/<id>_pipeline.toon`'s `trigger`
  (`{type: schedule, every}`, default `15m`) and, inside the existing `collector`, `discovery: poll`, `connector: local`
  or `sftp` + `connection`, `accepts_upload: true`, `stability {window: 30s, size_checks: 2, ready_marker?}` and
  `duplicate {mode: checksum, algorithm: SHA256, on_change: ignore}`; the DELETE removes those feed keys and sets
  `trigger: manual`. Every other collector key (set in the guided editor) is kept. The write goes the way every Pipeline
  write does: an approval policy over Pipelines refuses it (409, `PendingChanges.holdRefusing`, as Add data's own writes;
  `ConfigWriteFunnelTest`), `SaveGate` blocks only the findings the edit introduces, the import is claimed in
  `OnboardImport.IN_FLIGHT` against a run or a delete of the same Dataset (409), `RefusalConfigAudit.write` +
  `PipelineHistory.record`, then `refreshConfigs()`; a file that does not load is put back. The quarantine is the one
  Add data already wrote.
- **A watched folder IS the Dataset's inbox** (`<dataRoot>/inbox/<id>`): no caller-supplied path, so nothing to jail.
  An SFTP feed reads the Connection's `base_path`.
- **A person's *Add a file* is imported at once on a fed Dataset** (`ADD-FILE-FEED-1`, defect, fixed 2026-11-05). The
  upload lands in that same inbox, and the folder feed's stability gate held every file 30 s, so the person's own run
  took nothing and answered 422 "nothing was imported". `OnboardImport.addStaged` now opens
  `com.gamma.inspector.RunFiles.complete({landed file})` around its run: `CollectorProcessor.collect` adds the files a
  `complete` scope names back after `gateStability` (run path only; discovery order kept). The upload is whole by
  construction (`Job.landFromDraft` moves it into the inbox complete). Every other waiting file is still gated, so a sender's
  file that has just arrived waits for the feed; on Basic (no stability block) nothing changes - Add a file still takes
  the files waiting. No double import: the run moves the upload out of the inbox into
  `<id>/backup/` as it commits it, so the feed never lists it; were it left there, the feed's checksum duplicate check
  (`duplicate {mode: checksum}`) and, below that, the ingest's own commit record would each still skip it (mutation
  check 2026-11-05: with the move AND the duplicate check both disabled, the feed lists the upload again and adds no
  row). An SFTP feed was never affected (a
  remote collector does not gate the local inbox). Same seam as `BASIC-ONBOARD-SETTLE-1` (§6.20). Not covered: the
  scope is not tied to one run, so a scheduled run that lists the inbox while it is open (the person's run then waits
  on the Pipeline's run lock) takes the upload; it is imported once, by the feed, and the person's run reports
  nothing imported. Tests:
  `ControlApiDatasetFeedTest` (`addAFileRightAfterKeepThisUpToDate…`, `onBasicAddAFileStillTakes…`), `RunFilesTest`.
- **Waiting files** = regular files under the inbox (depth 4, links not followed) with the import's extension, never a
  hidden one (`.landing-*` uploads in flight). A file whose name the import took before (`MarkerManager
  .isAlreadyProcessed`) is skipped by Run now and would wait for ever, so it is flagged (`alreadyImported`, per file and
  counted) and the inbox says to rename it (found by the ledger lane).
- **The index Job is proposed, not written.** The PUT answers `indexJob`: an `la.index.build` Job
  (`on_signal: pipeline.commit`, `when $signal.pipeline == <id> && SUCCESS`, the link mapping's columns, `allow_full`)
  or null without a link mapping; the client saves it through `POST /jobs`, so the Jobs route's rights, approvals and
  owner stamping decide. Without `canAuthorWorkbench` the card names the Job to ask an administrator for; a 409 (Keep
  again after Stop: the Job is already there) is said as kept.
- **UI:** `add-data/dataset-feed.component.ts` (Inbox, *Import waiting files* = the card's Run now, the Keep form with
  option pickers and a pen field, *Stop*), mounted in the card's *Keep this up to date* block; the card keeps *Open in
  editor* (console) for more settings. The *Data status* line (`feedStatusLine`: *Inbox: N files waiting* or *Kept up to
  date: …*) reaches the shared `<inspecto-la-data-status>` through the arrivals answer's `feed` (one read for the card, the
  Start box and the Rows dock).
- **Decisions taken (review on return):** (a) `on_change: ignore` - a corrected file re-sent under the SAME name is
  skipped, because a feed's arrivals add rows and re-reading it would double them; send corrections under a new name. D-2 (a watched
  feed defaults to *Replace the period*) is not applied to scheduled runs: a scheduled run has no upload to take its days from; (b) an SFTP feed
  only (no FTP / FTPS / S3) - the plan names SFTP; (c) a Reference is not offered a feed (a feed adds rows; a
  Reference is replaced); (d) the routes are `canUploadData`, like Add a file, not `canAuthorWorkbench`: the PUT
  changes only the import's own Pipeline, within fixed safe defaults.
- Tests: `ControlApiDatasetFeedTest` (real HTTP: Basic inbox + Run now, Standard PUT/DELETE and the reloaded config,
  every refusal, armed 401/403/200), `dataset-feed.component.spec.ts`, `import-dataset-card.component.spec.ts`.

## 6.19 Dataset storage: compaction, the merge map and retention (LA-DATASET-COMPACT-1, as built 2026-11-05)

Plan [`la-data-arrival-plan.md`](../../../../archived-documents/plans-archive/la-data-arrival-plan.md) P4, decisions D-5, D-7…D-10 (operator
2026-11-05). Class `inspecto/.../control/DatasetCompaction.java`, registered from `OnboardRoutes`.

| Route | Gate | What |
|---|---|---|
| `GET /datasets/{id}/storage` | `canUploadData` | `files`, `bytes`, `threshold` (20), `compactedAt`, `compactedArrivals`, `removedArrivals`, `retention`, `retentionAvailable` |
| `POST /datasets/{id}/compact` | `canUploadData` | *Compact now*, at any count; claims `OnboardImport.IN_FLIGHT` (409 while an import or a delete runs); the answer carries `compaction {result, filesBefore, filesAfter, rowsRemoved, message}` |
| `PUT /datasets/{id}/storage` | `canAuthorWorkbench` | `{retention: {days: N} \| {arrivals: N} \| null}` on the Dataset entry; 503 without `pipeline.automation` (Basic); an approval policy over Datasets refuses (409) |

All three after Add a file's gates (503 no write root, 403 `data.self_import`, 404 / 409 `importDataset`).

- **On its own (D-7).** `OnboardImport.Job.followOn` calls `DatasetCompaction.afterImport` first, so every import path
  (commit, Add a file, Run now) compacts past 20 files inside the person's run, before the index build; the summary
  carries `compaction`. Never throws: a failed compaction leaves the files and says so. Every edition.
- **How.** Every Parquet file under `<dataRoot>/<id>/database` (no hidden file, no link) is read in a DuckDB sealed to
  `<dataRoot>/<id>`, each row tagged with its arrival (`__seq`) and its row number, and written ONE file
  `compacted_<stamp>.parquet` ordered by arrival (`UNION ALL BY NAME`, so drifted columns line up). Arrival order: the
  segments the previous merged file holds (its map's order), then the loose files by modification time.
- **The merge map (D-8)** `<dataRoot>/<id>/compaction.json`: `merged`, `at`, `arrivals[{file, received, firstRow, rows,
  coversFrom, coversTo}]` (the arrival's own output name, its row segment, the period of its kept rows),
  `removed[{file, at, reason}]` (at most 500), `retention`. `DatasetCompaction.merged(dataRoot, id)` answers it ONLY
  while the merged file exists, so a *Replace everything* (which sets the whole `database` folder aside) silences it.
- **The swap.** Write the merged file in `<id>/temp`, write `compaction.json.pending` (merged name, inputs, next map),
  move the merged file in, delete the inputs (only inside `database`, never the merged file), commit the map. `heal`
  (run before every compaction) finishes a pending swap whose merged file moved in, or drops one whose file did not.
  Between the move and the deletes a reader may count rows twice; an input that cannot be deleted (held open) stays
  named in the pending file and is counted twice until the next compaction removes it - the outcome says so. While a
  pending file still names one, compaction waits (`NOT_NEEDED`): its rows are in the merged file already, and merging
  it again would keep them twice for good.
- **Retention (D-9), Standard and up**, applied at compaction: *keep N arrivals* drops the oldest arrivals whole; *keep N
  days* keeps rows whose event date (`eventDate`, the When column) is within N days - a row whose date is missing or
  cannot be read as a timestamp always stays - and without a When column (or one the files do not have, checked with
  `DESCRIBE`) drops whole arrivals received before the cutoff. A policy stored on Basic is ignored
  (`retentionInForce`): Basic never drops a row.
- **Link index (D-10).** The files are rewritten, so the index's next build is a full one (its own advice, nothing added).
- **A run outside Add data compacts on its commit (`LA-DATASET-COMPACT-2`, 2026-11-05).** A Standard feed's scheduled run
  (or any run of the import Pipeline not started by Add data) never reaches `Job.followOn`. `CollectorService` subscribes ONE
  commit step to its Space's `ConsignmentEventBus` in its constructor - before `JobService.start()` subscribes - and the
  control plane sets it (`DatasetCompaction.register` → `CollectorService.commitStep`). The bus publishes synchronously on the
  run's thread, under the Pipeline's run claim, so the compaction finishes before the Space's Jobs (the `la.index.build` Job
  on `pipeline.commit`) are handed the commit; no other run of the import can write meanwhile. `DatasetCompaction.afterCommit`
  takes a SUCCESS, non-dry-run commit, finds the Space whose service published it (the attached ControlApis' Spaces), reads
  its roots under that Space's MDC, and acts only for an import Add data wrote for THIS id (`OnboardImport.isOurs`; the
  import Pipeline is named as its Dataset). It claims `OnboardImport.IN_FLIGHT` and **skips** when the claim is held: an
  import (which compacts in its own follow-on), *Compact now* or a delete is at work. Threshold and retention as on import.
  Never throws; a compaction that ran is logged. *Chosen over a `dataset.compact` Job step on `pipeline.commit`* (the row's
  suggestion): two Jobs on the same signal run concurrently, so the index build would race the swap, and the Job would
  have to be authored per Dataset. *Not* the `ImportFollowOn` seam either: a scheduled run never calls it.
- **The graph after a compaction (`LA-DATASET-COMPACT-2`).** The link index's read gate (`IndexedRead.select`,
  `IndexStaleness.removedInput`) already refuses an index whose input files are gone, so the graph reads the flat Dataset
  after a compaction - but it compared the files through `InputFingerprintCache` (30 s, cleared only by an index build), so
  for up to 30 s a read was served from the index with the rows a retention removed (measured: `/inv/projection/neighbors`
  answered `source.kind: index, stale: false`). Every compaction that merged (or failed part-way) now calls each installed
  application's `ImportFollowOn.filesRewritten` (a default no-op method; inside a run, *Compact now* and a scheduled
  commit, every edition); Link Analysis's `LinkIndexFollowOn` drops the Dataset's cached fingerprints
  (`InputFingerprintCache.forget`), so the next read answers `index_stale_refused` from the flat Dataset. *The smaller fix
  was chosen*: no rebuild inside *Compact now* - the graph reads flat until the next index build (Basic: the next import;
  Standard: the next commit's Job), as after any other removal.
- **UI:** `add-data/dataset-storage.component.ts` in the card (*Storage* line, *Compact now*, on Standard the *Keep*
  picker for a person who may author); `storageStatusLine` is its *Data status* line.
- **Arrivals and Data status.** `DatasetArrivals` reads the merge map: an arrival whose own output a compaction merged is
  still *kept*, with the period of its segment. The arrivals answer carries `feed` (`DatasetFeed.summary`: kind, interval,
  Connection, `waiting`, `alreadyImported` - never the inbox path) and `storage` (`describe`), and `dataStatusLines()` adds
  one line for each (`feedSummaryLine`, `storageStatusLine`), so the card, the Start box and the Rows dock say them.
- **A merged file rewritten since** (*Replace the period*, §6.17, removes days from every older file, the merged one
  included): the map keeps the merged file's `mergedBytes` and `mergedModified` and trusts its row segments only while
  both match. **Replace the period keeps the map true** (`LA-DATASET-COMPACT-2`, as built 2026-11-06): when the file it
  rewrites IS the map's merged file and the map still holds (`DatasetCompaction.trustedSegments`), it writes the kept
  rows in their row order (`file_row_number`), re-counts each arrival's segment and period from the same read
  (`DatasetPeriod.recount`), and commits the map with the new size and time (`segmentsRewritten`; undone with the
  import, `restoreMap`). An arrival left with no row drops out of the map, so the arrivals answer reads it as replaced
  (not as a retention removal). So *keep N arrivals* after a *Replace the period* counts every arrival. Only when the
  map cannot be trusted (an older map, a row outside every segment, a rewrite by anything else) do its arrivals stay
  *kept* with no known period and the next compaction carries the file as ONE block (`holds` names the arrivals in it),
  counted as one arrival by *keep N arrivals* - never sliced by stale offsets.
- **Deliberately not done (review on return; board `LA-DATASET-COMPACT-2` keeps (e)):** (a) *(built 2026-11-05, above:
  a scheduled run compacts on its commit)*; (b) *Compact now* with a retention policy does not rebuild the index - the
  graph reads the flat Dataset until the next build *(since 2026-11-05 it never shows a removed row, above)*; (c) the policy is gated on `pipeline.automation` (the Standard marker
  the data-tier plan already uses; declared by `inspecto-ops` since 2026-11-05 - before, no module did, so real Standard
  bundles refused the policy and *Keep this up to date* like Basic, test `ControlApiKeepUpToDateStandardTest`); (d) a leftover that can never be deleted (a folder named `*.parquet`) keeps
  compaction waiting, saying why each time - nothing is lost or doubled; (e) *(built 2026-11-06, above: Replace the
  period keeps the map true; only a `holds` block from an untrusted map still counts as ONE arrival)*; (f) the arrivals answer has no capability (as
  before), so its `feed` tells a viewer of the Dataset the Connection's name, the ready marker and the waiting count -
  never the inbox path.
- Tests: `ControlApiDatasetCompactionTest` (21 arrivals compact with each period kept and listed as kept by the arrivals
  answer, Compact now, a Replace the period that rewrites the merged file, Replace resets the map, retention by arrivals /
  by event date / an unreadable date / a missing When column / never on Basic, the crash heal, a file held open, armed
  gates, `keepNArrivalsAfterAReplaceThePeriodCountsEachArrival`, compaction over files whose columns differ
  (`ControlApiArrivalNewColumnsTest`), `aRunOutsideAddDataCompactsOnItsCommit`: a run not started by Add data compacts, and leaves a held Dataset alone),
  `ControlApiOnboardLinkAnalysisTest#afterARetentionCompactionTheGraphNoLongerShowsTheRemovedRows` (geo-link: right after a
  *keep 1 arrival* Compact now the neighbours read is `index_stale_refused` and draws no removed row), `dataset-storage.component.spec.ts`, the Data status lines in `dataset-feed.component.spec.ts`.

## 6.20 Watch this folder: the 30 s inbox poll (BASIC-ONBOARD-1 P2, as built 2026-11-05)

Plan [`basic-onboard-plan.md`](../../../../archived-documents/plans-archive/basic-onboard-plan.md) §4.6, §5 Q1; narrows DT-5 / DT-17 (a
Basic import inbox may poll; still no user-defined schedule). Class `inspecto/.../control/InboxWatch.java`.

| Route | What |
|---|---|
| `PUT /datasets/{id}/watch` | `{watch: true \| false}`; `canUploadData`; gates as the feed (`DatasetFeed.gate`); 422 not a boolean, 422 a Reference (a watched inbox adds rows), 409 a Pipeline already on a schedule (*Keep this up to date*), 409 an approval policy over Datasets; answers the feed read |

- **Every edition, off by default, one interval.** The toggle writes `watchInbox: true` on the Dataset entry (removed
  when off); the import Pipeline stays `trigger: manual`, so the scheduler never runs it. The feed read and the arrivals
  answer's `feed` carry `watch` (true only while the entry says so AND the trigger is manual) and, after a failed poll
  import, `watchError`: a refusal's own words with the server's paths taken out (`ServerFaults.withoutServerPaths`),
  or for anything else a fixed phrase - exception text never reaches the wire (ERR-5XX-ROUTE-BODIES); the detail is
  logged. The feed read also carries `watchable` (not a Reference, trigger manual): the UI offers the toggle on it.
- **One poller.** A single daemon thread (`inbox-watch`), fixed delay 30 s, polls every registered `InboxWatch.PollTarget`
  in turn under its Space's MDC (`tick()` is synchronized). The watched list is in memory: the toggle registers or
  removes a poll target, and each ControlApi's Spaces are read for `watchInbox` entries on the first tick after it starts
  (`attach`, from `OnboardRoutes.register`; its poll targets go on close).
- **A Postgres snapshot is never polled (operator 2026-11-05).** Its 30 s refresh target (`InboxWatch.Snapshot`) is
  removed: the toggle refuses a snapshot (422), `watched()` and `watchable` are false for one, and the inbox target
  skips it. *Refresh now* exports the table to `<data root>/_onboard/a<uuid>/` and moves the complete file into the
  inbox, where the replace path takes it like an upload
  ([`api-onboarding-authoring.md`](../processor/api-onboarding-authoring.md), *Export, then move*). The Dataset card's
  feed shows "The table is copied again only when you press Refresh now" in place of the toggle.
- **The same path as Run now.** `OnboardImport.runNow`'s body is now `importWaiting(Space, id, content)`, called by
  Run now and by the poll alike: columns checked by name, a refused file moved to `<id>/refused/` with its reason and a
  REFUSED arrival, the run, the follow-on (the link index, in the run in every edition), the arrivals. The poll's actor is
  `inbox-watch`. A 409 because a person's Run now or upload holds the import is skipped silently (the next tick looks
  again); any other failure is kept as `watchError` and logged. The daemon's start lock is not the class monitor
  `tick()` holds, so a toggle never waits for a running import.
- **No half-written file.** A run starts only when at least one file the run would take is waiting (files already
  imported under the same name are ignored) and EVERY such file is unmodified for 30 s and was seen at the same size on
  two polls - the numbers a folder feed writes into its Pipeline (`stability {window: 30s, size_checks: 2}`). Not the
  `StabilityGate` class: it forgets a file once it releases it, and the run takes the whole inbox, so the poll must
  see all of it settled at once (with the gate, two files settling on alternate ticks would never run).
- **The run takes only the checked files** (`BASIC-ONBOARD-SETTLE-1`, 2026-11-05). The poll hands the files it just
  checked to `importWaiting(…, only)`: the columns check judges only those, and the run is narrowed to them by
  `com.gamma.inspector.RunFiles.only(inbox, files)`, a scope keyed by the inbox folder's real path (not the Pipeline
  name, so two Spaces never narrow each other) that `CollectorProcessor.collect` applies on the run path only (a
  pending count still sees the whole inbox). A file created between the check and the run stays in the inbox and is
  checked by later polls. Run now passes no set: it takes the whole inbox, as before. Not covered: a file already
  checked that is rewritten in that instant is still taken (the scope matches by path, since the columns check may
  rewrite a file it accepts). Its other half, `RunFiles.complete`, lets a person's *Add a file* skip a feed's
  stability window (`ADD-FILE-FEED-1`, §6.18).
- **The poll re-checks on every look** that the Space exists, Add data is on (else it waits), the entry still says
  watch, is not a Reference (the key can be set through another door) and is still an import Add data wrote (`OnboardImport.requireOwnImport`, split out of `importDataset`), else
  the poll target is dropped.
- **`watchInbox` through the generic door** (`PUT /components/dataset/{id}`, `canAuthorWorkbench`) is not refused. For
  an inbox that is no wider than the toggle: a builder can already put the import Pipeline on a schedule. For a
  Postgres snapshot it is: a builder may turn on the 30 s refresh, which Run now (`canUploadData`) otherwise does. The
  operator accepted this as a bounded widening (2026-11-05): the same stored connection and table, and an import can
  no longer plant `_snapshot.toon` (`ImportPaths.REFUSED_SUFFIXES`). Recorded as the `PUT /watch` ACTS reason in
  `ImportCapabilityGuardTest`.
- ⚠ **Superseded the same day (operator, 2026-11-05): a snapshot is never polled.** *Refresh now* exports the table and
  moves the finished file into the inbox; `InboxWatch.Snapshot`, `PostgresSnapshot.poll` and the card's 30 s toggle
  were removed (`InboxWatch.watched` excludes `importSource: postgres`). The bullet below records the first design, which no longer exists.
- ~~**A Postgres table snapshot (P3) refreshes on the same toggle.**~~ For an entry with `importSource: postgres` the toggle
  registers `InboxWatch.Snapshot` instead: each poll runs `PostgresSnapshot.poll` (copy the table again; replace
  everything only when the bytes differ from the last arrival; no settle check - the server makes the copy itself). A
  snapshot is never inbox-polled; a Reference snapshot may refresh (it replaces). The feed read says `snapshot`; the
  card mounts the feed component in snapshot mode (*Refresh every 30 seconds*, no inbox); Data status says *Refreshed
  from its table every 30 seconds*. Test: `ControlApiPostgresSnapshotTest#theThirtySecondRefreshCopiesTheTableOnlyWhenItChanged`
  (real Postgres).
- **Cost (P0 V3, measured):** under 1 % of one core at 1,000 empty watched inboxes; files left waiting are the driver.
- **UI:** `dataset-feed.component.ts` - a *Watch this folder* slide toggle in the Inbox block when the feed is
  `watchable`; the inbox text says it is checked every 30 seconds; a refusal flips the toggle back and says why; a
  failed poll import shows as a warning. Data status (`feedSummaryLine`): *Inbox watched, checked every 30 seconds ·
  N files waiting · the last import failed: …*.
- Tests: `ControlApiInboxWatchTest` (real HTTP; ticks driven directly with the daemon paused (`pausedForTest`): settled import with a refused file set aside, a
  growing file holding the inbox, off, the restart read-back, refusals, a Reference watched through another door, armed 401/403/200), `dataset-feed.component
  .spec.ts` (toggle, refusal, schedule hides it, the Data status words).
