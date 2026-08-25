# Project Notes — durable, non-obvious knowledge

> **What this is.** The consolidated, repo-local home for durable project knowledge that isn't
> obvious from the code or git history — gotchas, conventions, key decisions, and a map to the
> authoritative docs. Consolidated from scattered per-user agent memory on **2026-06-19**.
>
> **Where things live:**
> - **This file** = durable cross-cutting knowledge + a pointer map (below).
> - **Authoritative per-topic docs** = `docs/` (see the map). Don't duplicate them here.
> - **Live / in-flight working state** = `SESSION_STATUS.local.md` (gitignored, not here).
> - **Machine-specific + personal-workflow detail** = the gitignored `.claude/skills/*` (local).
>
> Point-in-time claims (file:line, counts, "as of") may drift — **verify against current code** before
> asserting. Update this file when a durable fact changes.

---

## 1. Identity & module map

**Inspecto** (formerly *UCC File Processor*; repo `inspecto`, checked out here as
`C:/sandbox/inspecto-clean`). Java (core bytecode
`release=24`; agent modules need a **JDK 25+ runtime**; built & bundled on **JDK 26**) / Maven
multi-module · embedded **DuckDB** · **TOON** config · OpenCSV. Mainline = `master` — the ONLY
line (`4.x` was deleted 2026-08-17; the next major's branch is cut when it ships, `BRANCHING.md` §0-A).
Editions = build flavors (see below), **never branches**.

Module dirs were renamed 2026-06-12; the **artifactIds caught up on 2026-08-10** (`a1da65f5`), so dir ==
artifactId everywhere — with one deliberate exception: `inspecto/` is `inspecto-processor`, because a bare
`inspecto` would collide with the aggregator. The shipped bundle is named `inspecto.jar` (renamed from
`file-processor.jar` on 2026-08-13, along with the `inspecto-deploy/` bundle dir and the
`inspecto-security.jar` / `inspecto-policy.jar` edition jars); that is the deployment surface, not an
artifactId:

Reactor = **13 modules** (build order below; WS-D 2026-07-22 added `inspecto-engine`, then split
`inspecto-etl`, `inspecto-event`, and `inspecto-acquire` out of it the same day, increments 2–4).
Authoritative shape, version management, and the module-extraction playbook:
[`okf/backend/modules/reactor.md`](okf/backend/modules/reactor.md).

| Dir | Role | artifactId / jar |
|---|---|---|
| `inspecto-api/` | dependency-free leaf: the `@PublicApi` annotation | `inspecto-api` |
| `inspecto-util/` | leaf: DuckDB access + CSV/file/tar helpers + `CronExpression` | `inspecto-util` |
| `inspecto-config/` | config spec / codec (TOON) / safety | `inspecto-config` |
| `inspecto-sql/` | sandboxed DuckDB SQL (`SqlSandbox`/`SqlOracle`/`SqlGuard`/`SqlViews`) | `inspecto-sql` |
| `inspecto-etl/` | `com.gamma.etl` — pipeline config + batch ingest (foundation leaf below engine) | `inspecto-etl` |
| `inspecto-event/` | `com.gamma.event`+`metrics` — Operational-Intelligence event store + metrics | `inspecto-event` |
| `inspecto-acquire/` | `com.gamma.acquire` — file/remote acquisition, ledger, stability/gap/retry | `inspecto-acquire` |
| `inspecto-engine/` | the remaining engine cluster (`pipeline`/`job`/`inspector`/… ) below core | `inspecto-engine` |
| `inspecto/` | control plane + composition root (lean core), ships the fat JAR | `inspecto-processor` / `inspecto.jar` |
| `inspecto-connectors/` | remote connectors (SFTP/FTP/FTPS/DB), all network deps | `inspecto-connectors` |
| `inspecto-agent/` | optional AI assist skills (vendored kernel layer + eoiagent transport) | `inspecto-agent` |
| `inspecto-agent-hosted/` | hosted model providers (omitted from air-gapped builds) | `inspecto-agent-hosted` |
| `inspecto-intelligence/` | embedded-intelligence agent (eoiagent-backed) | `inspecto-intelligence` |
| `inspecto-security/` | Standard/Enterprise OIDC auth, `-Pedition-standard` only (not in default `<modules>`) | `inspecto-security` |
| `inspecto-policy/` | Enterprise ABAC policy engine (`AccessDecider` impl), `-Pedition-enterprise` only (= standard + this) | `inspecto-policy` |
| `inspecto-ui/` | Angular SPA (gamma/Fuse template), serves from the engine | — (npm; dev :4204) |

agent-kernel is GONE (discontinued upstream, replaced 2026-07-07): its reasoning layer is vendored at
`inspecto-agent … com/gamma/agent/kernel/**`; model transport is **eoiagent** (`com.eoiagent:*:0.1.0-SNAPSHOT`,
local `.m2` from `C:/sandbox/agent-brainstorm`) — see `docs/superpower/agent-kernel-replacement-plan.md`.

---

## 2. Authoritative docs map (go here first)

| Topic | Doc |
|---|---|
| Production investigation (process/events/metrics/state/`-D` flags/Control API/troubleshooting) | [`ADVANCED_GUIDE.md`](ADVANCED_GUIDE.md) — **living doc** |
| Pipeline-graph design (IR, lift, validator, executor, registry, T-checklist §14) | [`pipeline-graph-design.md`](okf/backend/pipeline-graph/pipeline-graph-design.md) |
| Live execution of authored Pipelines (`JobType.PIPELINE`, T32) | [`live-execution.md`](okf/backend/pipeline-graph/live-execution.md) |
| Data acquisition framework (Phases A–F, connectors, dedup, watermarks) | [`data-acquisition-framework.md`](okf/backend/acquisition/data-acquisition-framework.md) |
| All TOON config keys | [`configuration.md`](okf/backend/config/configuration.md) |
| Editions (Personal/Standard/Enterprise = build flavors) | [`EDITIONS.md`](EDITIONS.md) |
| Branch & release policy (versions=branches; merge-forward; SemVer+CC) | [`BRANCHING.md`](BRANCHING.md) |
| Parsing/grammar | [`parsing-options-reference.md`](okf/backend/config/parsing-options-reference.md), [`delimited-grammar-design.md`](archived-documents/plans-archive/delimited-grammar-design.md) |
| Perf benchmarks & tuning | [`performance.md`](okf/backend/build-run/performance.md) |
| Strategy / roadmap / stakeholder decks | [`roadmap/`](roadmap/) |
| Curated index of all current docs | [`INDEX.md`](INDEX.md) |
| Engineering knowledge bundle (OKF, consolidated 2026-07-07; graphify-indexed) | [`okf/`](okf/index.md) — sections [`frontend/`](okf/frontend/index.md) · [`backend/`](okf/backend/index.md) · [`agentic/`](okf/agentic/index.md) |
| Requirements-of-record + MoSCoW · stakeholder set | [`REQUIREMENTS.md`](REQUIREMENTS.md) · [`stakeholders/`](stakeholders/README.md) |

---

## 3. Key decisions (the "why", not derivable from code)

- **Editions are build flavors, never git branches.** One source tree (`master` = auth-free common core);
  edition-only code in its own Maven module (`inspecto-security` for Standard/Enterprise), assembled via
  `-Pedition-*` profiles + `ServiceLoader` + `-D` flags. A fix lands once in core; all editions inherit it
  at build. Rationale: branches would force perpetual cross-line cherry-picking. → [`EDITIONS.md`](EDITIONS.md).
- **All auth removed from `master`/common core (2026-06-16).** Personal is genuinely auth-free (every
  ControlApi route open; SPA boots to `/dashboard`; no token paste/guards). Standard re-adds auth out-of-band
  via the **`inspecto-security` module (BUILT, W6 2026-07-06** — `OidcAuthenticator` Nimbus+JWKS, `RoleMapper`,
  `KeycloakTokenRelay`; reactor-gated behind the `edition-standard` profile) behind the
  `Authenticator`/`Subject`/`TokenRelay` SPIs (`com.gamma.control`), plus HTTPS (`HttpsServer`) and the BFF
  `/auth/exchange|refresh|logout` routes; Angular uses OIDC Auth-Code+PKCE driven by `bootstrap.features.authMode`
  (no-op on Personal). **The `-Dassist.write.root` 503 write-gate is SEPARATE from auth and stays.**
- **Keep the core lean.** All network deps live in `inspecto-connectors`; hosted-AI SDKs in
  `inspecto-agent-hosted` (physically absent from air-gapped builds). The zero-new-dep rule was retired
  2026-06-13 (logback replaced slf4j-simple, user-approved) — still no gratuitous deps.
- **Flow-graph track is `master`-only** (`feat:` → master; empty merge-forward set; retired lines untouched).
- **Multi-space (multi-project), `master`-only `feat:` track.** One server hosts many isolated **spaces**
  (`-Dspaces.root`, default `./spaces`); each = `spaces/<id>/{config,data,audit,duckdb,flows}` + `space.toon`.
  The ~40-method `@PublicApi` per-instance `CollectorService` is **wrapped, not rewritten**: `SpaceManager` →
  `SpaceContext` → unchanged `CollectorService`. Isolation of the five process-wide singletons (EventLog /
  MetricRegistry `space` label / ConnectionRegistry / StabilityGate / AcquisitionLedgers) is by the **`space`
  SLF4J MDC** (`EventLog.currentSpaceId()`; fallback `"default"` = no MDC = byte-identical single-space). API
  seam: `/spaces/{id}/…` (`ControlApi.dispatch` strips + MDC-binds; un-prefixed → current/default). Space CRUD
  (no restart): `GET/POST /spaces`, `DELETE /spaces/{id}?purge=` (purge = opt-in file removal). **No flat
  fallback** — migrate once via `com.gamma.service.SpaceMigrator`. Editions/auth stay future SPI (no
  `if(edition==)`). → [`configuration.md` §Spaces](okf/backend/config/configuration.md#spaces-multi-project-layout).
  **UI (Stage 7):** `SpacesService` (signals) + a global `spaceInterceptor` rewrite `/api/<p>` →
  `/api/spaces/<id>/<p>` so every feature service stays space-agnostic (no-op single-tenant = byte-identical);
  header space-switcher + `modules/admin/spaces` admin (CRUD + per-space/per-data-source zip export + import
  with dry-run preview + create-from-bundle). The UI tells discover from single-tenant via the additive
  **`GET /spaces/_meta` → `{multiSpace}`** (= `SpaceManager.supportsCrud()`), never by space-list length (a
  fresh discover server returns `[]`). See the `angular-ui` skill §7.

---

## 4. Cross-cutting gotchas (the expensive-to-rediscover ones)

- **`git commit` commits the INDEX, and on this shared checkout a shift can hand you a dirty one.**
  Shifts end without committing, so `git status` may open with work *already staged* — on 2026-08-15 a
  previous shift's staged `git mv` was silently swallowed into an unrelated `docs:` commit, because
  `git commit -F-` takes whatever is in the index regardless of what you just added. ⛔ **Run
  `git diff --cached --stat` immediately before every commit** and confirm the list is exactly what you
  intend. (Caught on `git show --stat` and unwound before pushing; nothing reached origin.) The same
  hazard is why staging here is always explicit paths — ⛔ never `git add -A` at the repo root, where the
  untracked `spaces/**` is real operator CDR data.

- **A find-and-replace-all reporting success is NOT evidence of complete coverage.** Replacing four call
  sites of `userFor(f)` with the pattern `, userFor(f));` hit only three on 2026-08-15: the fourth was a
  **ternary arm** ending in `)` with no trailing semicolon, so the pattern never matched it and the tool
  reported success anyway. The half-applied fix compiled and looked done. **When the surrounding syntax
  varies across call sites, grep the result and count them** before believing the edit.

- **A green suite across a behaviour change means the behaviour is UNPINNED, not that it is right.** On
  2026-08-15 the offline mock's lift was changed to emit a `transform.map` node unconditionally, altering
  the graph topology of *every* pipeline without an authored projection — and the full UI suite passed with
  a **zero delta**, 2433/5 before and after. Nothing anywhere asserted the node's existence, which is
  exactly why the drift had survived since `processing.map` shipped. ⛔ When a change alters observable
  behaviour and no test notices, **stop and add the guard** rather than banking the green run; then falsify
  it (restore the old behaviour and watch it fail). The same shift's `record()` transaction and
  `bind-kinds` contract were both confirmed this way. Related: the backlog row for the drift had itself
  warned that "the node-count assertions must be updated deliberately" — **there were none**.

- **A residual buried in a BACKLOG row marked ✅/SHIPPED is the likeliest thing in the file to be already
  done.** Four were found stale in one shift (2026-08-15): a `PipelineJobRunner` gap closed *one day* after
  it was written up, a regression test recorded as "worth adding" that already existed, a retention-docs
  gap whose work had landed in `operations-reference.md` rather than the runbook the row named, and a
  "8/8 stores, one missing" criterion that was really 10 stores with three missing. The cause is
  structural: a residual records the file its author *expected* the fix to take, the fix lands elsewhere in
  a later shift, and the row's ✅ header stops anyone re-reading the prose. ⛔ **Grep the CLAIM, not the
  path the row names** — each of these was refuted in under two minutes by checking the behaviour. Three
  would otherwise have been re-implemented on top of working code.

- **An `HttpExchange` attribute is per-request by DEFAULT, not by guarantee — derive or clear, never
  trust a stamp.** `sun.net.httpserver.ExchangeImpl` decides *once at class-init* whether each exchange
  gets a private map:
  `static final boolean perExchangeAttributes = !System.getProperty("jdk.httpserver.attributes","").equals("context")`.
  Wherever it falls back — **any pre-26 runtime, or a current one started with
  `-Djdk.httpserver.attributes=context`** — `ControlApi`'s single `createContext("/")` means **one map
  shared by every request in the JVM**. This produced two real defects on 2026-08-11: a stamped `v1` flag
  that latched across requests (`890025e9`, fixed by deriving it from the URI) and, more seriously,
  an authenticated `ATTR_SUBJECT` readable by a later unauthenticated request — set only on success and
  never cleared, so it flowed into `requireCapability`, `actor()` and `authorize()` (`f0d5f131`, fixed by
  clearing the whole `ControlApi.REQUEST_SCOPED_ATTRS` roster as dispatch's first act, with a
  reflection-based completeness guard so a new `ATTR_*` constant missing from the roster is a red build
  rather than a leak). ⚠ **Do not dismiss this as latent on the strength of the local JDK** — the
  `-NoRuntime` bundle documents "Java 24+" as its target requirement, so the shared-map configuration is
  a *supported deployment*. Read the JDK source before reasoning about attribute lifetime.

- **A profile-scoped module is invisible to the verify loop that everyone actually runs.**
  `inspecto-security` / `inspecto-policy` live in the parent's *profile-scoped* `<modules>`
  (`-Pedition-standard` / `-Pedition-enterprise`), not the default list — so `mvn -o clean test`
  reports **BUILD SUCCESS while both edition builds are broken**. The 2026-08-10 artifactId rename
  proved it: the two poms still declared `file-processor-parent`, which is a *non-resolvable parent*,
  and nothing caught it because the routine loop never loads them. **Any change to the parent pom, a
  shared artifactId, or a managed dependency must be verified with
  `mvn -o clean test -Pedition-enterprise`** — that profile is the only one that pulls every module in.
  Grepping `*/pom.xml` for the old name finds these two instantly; the reactor never will.

- **A change to a SERVED catalog is not verifiable from the module that computes it**, and
  `mvn -o test -pl <module>` cannot be trusted on its own. Proved 2026-08-10: adding a second
  `transform` entry to `PipelineProjection.RECIPE_VERBS` (inspecto-engine) was verified with
  `-pl inspecto-engine`, where `StepTypesContractTest` lives — and left `master` RED for four commits,
  because the *same* catalog is asserted again one module up by `ControlApiPipelinesTest` at the route
  level. Route-level contracts live in the module that **serves** them, so a catalog change needs a
  reactor-wide run. Two mechanical traps make module-scoped runs worse than useless here:
  **(a)** `-pl inspecto` alone fails with `NoClassDefFoundError: com.gamma.notify.MailAccess` — it
  resolves siblings from stale installed jars, so it needs `-am`. ⚠ **That trap also has a SILENT form,
  which is the dangerous one** (hit 2026-08-14): when the stale jar is merely *old* rather than
  incompatible, everything compiles and runs, and the module simply tests the sibling's **previous
  behaviour** — a route test asserting a newly-added 422 reported `200` and looked exactly like a broken
  fix. One command settles it before you debug anything:
  `unzip -p ~/.m2/repository/com/gamma/inspector/<artifact>/4.0.0-SNAPSHOT/<artifact>-4.0.0-SNAPSHOT.jar
  com/gamma/<pkg>/<Class>.class | grep -c <NEW_SYMBOL>` — `0` means the run proved nothing. (Note the
  groupId path is `com/gamma/inspector/`; the artifactIds were renamed to `inspecto-*`, the groupId was
  not.) **(b)** with `-am` **and** `-Dtest=`,
  the run dies on the first upstream module with *"No tests matching pattern"* unless you add
  `-Dsurefire.failIfNoSpecifiedTests=false` (`-DfailIfNoTests=false` does **not** cover it — that flag
  is for "no tests at all", not "the filter matched nothing in this module").
  ⚠ **Read a reactor summary for SKIPPED, not just for FAILURE.** A fail-fast abort marks downstream
  modules `SKIPPED`, and "25 modules" in a summary means *listed*, not *built* — a run reported as
  "25 modules, 3017 tests, 0 failures" on 2026-08-10 had in fact failed one module and skipped six,
  `inspecto-security` and `inspecto-policy` among them.

- **A UI spec that RUNS green is not proof it typechecks — there are THREE tsconfigs and the root one
  is a different gate, not a superset.** Proved 2026-08-11 (`842a3a77`): a spec asserting `toHaveLength`
  on an element list passed `npm run test:ci`, passed the production `npm run build`, and passed **both**
  `tsconfig.app.json` and `tsconfig.spec.json` — while failing `npx tsc -p tsconfig.json --noEmit`. The
  root config sets no `types`, so every `@types/*` is ambient and **`@types/jasmine` supplies the global
  `expect`**; only `tsconfig.spec.json` names `vitest/globals`, and the root config does not extend it.
  The fix is importing `{ describe, expect, it }` from `vitest` in the spec (307 of 319 already do) —
  **never** editing the tsconfigs to paper over it. Commands + rationale: `angular-ui` skill §12 step 2b.

- **`git mv` stages the rename from the INDEX, not the working tree.** Edit a doc and *then*
  `git mv` it, and the edits stay **unstaged** — `git status` shows `RM` (renamed + modified), which
  is easy to skim past, and the commit ships the file's *pre-edit* content at its new path. This bites
  the documentation lifecycle directly (root `CLAUDE.md`: rewrite a plan's status header, then archive
  it), and it did: the platform-services plan landed in `archived-documents/` still claiming work
  remained. **Either `git add` the new path explicitly after the move, or verify with
  `git show HEAD:<newpath>`.**

- **`ResultSet.wasNull()` reports on the most recent `get*`, not on a named column.** Read it *immediately*
  after the getter whose nullness you care about, or it answers for whichever column you fetched last. Cost a
  real bug in `DbConsignmentOutputStore.producerHighWater` (2026-08-10): an absent instant read back as
  `1970-01-01`. The same trap applies to any nullable numeric — `getLong`/`getInt` return `0`, so the sentinel
  is silent by construction.

- **A write path must read a declaration it already holds — never resolve one by reverse lookup.** Going from a
  *store name* back to the dataset that describes it is ambiguous by construction (the catalog builds that map
  with `putIfAbsent`, so it is first-scan-wins and depends on directory order). Two write paths needed an
  event-time column and both had to take the local route instead: ingest uses the schema's date `PartitionDef`,
  Pipeline sinks use their own `partitions[].source`. This is why `DatasetRelation.temporalColumn` has **no
  caller** despite being built for exactly that job — see
  [`okf/backend/engine/consignment-addressing.md`](okf/backend/engine/consignment-addressing.md) §2.

- **Removing a component from a record that is persisted as JSON breaks reading the history.** A bare
  `new ObjectMapper()` fails on unknown properties by default, and every append-only JSONL store here uses one
  (`RunArtifactStore`, `RunLogStore`). Drop a field from the record and Jackson throws on every historical line
  that still carries the key — the whole run's read fails, not just the stale field. Retiring
  `RunArtifact.timeRange` (2026-08-10) needed `FAIL_ON_UNKNOWN_PROPERTIES=false` in the same change. **A reader
  of durable history must tolerate the shapes history actually contains**, so set that flag when the store is
  created, not when a field is first removed.

- **Surefire `-Dtest=A,B` wants commas.** The `+` form matches nothing. On its own it at least fails with
  "No tests matching pattern … were executed", which reads like a missing class rather than a bad separator —
  but ⛔ **combined with `-Dsurefire.failIfNoSpecifiedTests=false` it reports BUILD SUCCESS having run
  nothing at all**, and those two flags are habitually passed together. Observed again 2026-08-15 on
  `-Dtest=ControlApiSystemRoutesTest+OperationalDbTest`: green build, no `Tests run:` line anywhere.
  **A pass is only a pass if a `Tests run:` line appeared for each class you named** — otherwise run them
  as separate invocations.

- **Ask a verify agent for the MODULE COUNT, not just the total.** Two `-Pedition-enterprise -fae` runs on
  the same tree reported 3355 and 3458 (2026-08-18); nothing had changed but the summariser, which dropped a
  module. The known cause is Maven logging `Tests run:` at `[WARNING]` for any module with skips, so a grep
  filtered on `[INFO]` silently loses whole modules — but the *total alone cannot reveal that*, because a
  plausible number looks like a real one. **19 test-reporting modules** is the current shape of a full
  enterprise run; a total that moves without a module count is not evidence either way.

- **TOON schema serialization** — `ConfigCodec.toToon(map)` does **not** emit tabular-array format. A schema
  whose `fields`/`rules` are Java-constructed `List<Map>` round-trips as nested maps, and the TOON parser then
  throws `Array length mismatch: declared N, found 0`. In any test that writes a schema file for TOON loading,
  write the schema as an **inline TOON string** (`fields[N]{name,selector,type}: …`), not via `toToon(schemaMap)`.
  Round-trip only works when the map was originally JToon-decoded (e.g. a `SchemaSelector` loaded from a real
  `.toon`).
- **DuckDB reserved words** — `day` is a keyword: alias it (`run_day`) in SQL; quote `"trigger"` too. `rows` is
  one as well (window frames: `ROWS BETWEEN`), which is why the `consignment_outputs` registry spells its count
  column `row_count` even though the plan sketch says `rows` — matching `inspecto_pipeline_provenance` and the
  `lineage` CSV. Watch for these whenever generating SQL with date/trigger/count columns.
- **`schema` names TWO unrelated config shapes — never resolve one to the other's spec.** The **registry
  component** is a bare column list (`{fields:[{name,type[,format]}]}`, stored under `schemas/`, authored by
  the Components pane, validated by `ConfigSpecs.schemaComponent()`); the **TOON schema config** describes a
  raw source (`raw.name` required, `raw.format`, `mapping.canonicalName`, e.g. `events/call_schema.toon`,
  validated by `ConfigSpecs.schema()`). `component_draft`/`config_schema` mean the **component** and route
  through `InspectoTools.specFor`; `/validate` means the **config** and routes through `ConfigSpecs.forType`.
  Conflating them shipped a defect that survived two slices (fixed 2026-07-27). ⚠ **Do not generalize this
  into "registry kinds have no `ConfigSpec`"** — `widget` and `dashboard` are shared words whose specs
  describe the registry components *accurately*; `schema` is the one overloaded word, and a blanket reroute
  breaks two working kinds. The collision stands deliberately (renaming breaks on-disk dirs + the UI
  `ComponentType` union) — see the exception recorded in `GLOSSARY.md`; say "schema component" / "schema
  config" when context does not disambiguate.
- **A new component kind needs TWO registrations, in different modules** — `ComponentStore.WRITABLE_TYPES`
  (else every CRUD call 400s) **and** `ComponentRegistry.TYPE_BY_DIR` (else the first write has nowhere to
  land). Adding only the first compiles and passes an unfocused build. `ComponentStoreTest`
  `everyWritableTypeHasARegistryDir` now asserts the pair; keep it.
- **A UI `ComponentType` the server does not know fails only at runtime, per call.** The union in
  `inspecto-ui/src/app/inspecto/api/components.service.ts` is a hand-maintained mirror of `WRITABLE_TYPES`
  with **nothing enforcing the mirror**. `rule-template` sat unmirrored for the entire life of the data-table
  Pro Max "save as rule" feature (fixed 2026-07-27): list/save/remove all 400'd against a real server while
  the mock served them happily, and the Registry's `allSettled` swallowed the failure so it never surfaced
  as anything louder than console noise. ⚠ Two lessons that generalize: a per-kind fan-out behind
  `Promise.allSettled` **hides a contract break as missing data**, and a docstring claiming a backend enum
  is "still closed / real backend later" is a claim to re-verify, not to trust — that one was ~15 kinds stale.
- **Hand-assembling a backend classpath will trip the edition model** — which is why nobody assembles one
  by hand anymore: since 2026-08-13 the `inspecto-backend` launch config runs `tools/run-backend.ps1`, which
  derives the classpath at launch and gets the rule below right *by construction*. Read on before editing it.
  `inspecto-connectors` is an *optional* ServiceLoader module carrying `SmtpEmailChannel`, and it
  is deliberately **not** in `inspecto`'s dependency tree — putting its `target/classes` on the classpath makes
  `NotificationService.discoverChannels` find a channel whose `javax.mail` dep was never shipped, and boot dies
  with `NoClassDefFoundError: javax/mail/Message`. Derive the list from
  `mvn -o dependency:build-classpath -pl inspecto -am` (9 modules today) rather than globbing `*/target/classes`,
  and put the module `target/classes` **ahead** of the `.m2` jars so fresh code shadows the stale installed
  `inspecto-*` artifacts (that ordering also silences the duplicate-`logback.xml` warning).
- **PowerShell splits an unquoted `-Dfoo.bar=baz` native argument AT THE DOT.** A token starting with `-`
  is parsed as a parameter name, which ends at the `.`, so `-Dmdep.includeScope=runtime` reaches the
  process as **two** arguments (`-Dmdep` and `.includeScope=runtime`). Maven then reports the tail as an
  *unknown lifecycle phase*, which reads like a broken POM rather than a quoting bug — it killed
  `tools/run-backend.ps1` on 2026-08-15 (`ad6eb73c`). ⚠ **Quote every `-D` argument, including the ones
  containing no variable**: the ones with `$vars` are usually already quoted for interpolation and so
  survive, which is exactly why the bug hides — six unquoted `-D` JVM properties sat latent in the same
  script's launch step, invisible only because it died earlier. Prove it in one line, don't reason about
  it: `pwsh -NoProfile -Command 'function Show { $args | % { "[$_]" } }; Show -Da.b=c "-Dd.e=f"'`.
- **Two pure-Node CI guards run BEFORE the Maven build** in `ci.yml`, so either can fail a green-code push:
  `tools/check-vocabulary.mjs` (banned synonyms in user-facing docs, **plus banned KEYS in the committed
  TOON config corpus** since 2026-08-04 — it reads `git ls-files`, not the working tree, so local matches
  CI and `spaces/**` runtime state is never scanned; its `CONFIG_ALLOW` doubles as the Flow→Pipeline Tier-3
  debt register and fails when an entry goes stale) and `tools/check-secrets.mjs` (a
  secret-ish key assigned a ≥16-char literal — SEC-INCIDENT-1). Both take a per-line `vocab-allow` /
  `secret-allow` comment as the escape hatch. ⚠ **`check-secrets.mjs` was "master-only" while `4.x` still carried the
  live OAuth secrets** (merging it forward would have pinned that branch's CI red, BACKLOG §5); P1 fixed
  the code there and the branch itself was deleted 2026-08-17, so the caveat is now history. A third guard,
  `npm run lint:tokens`, runs in the separate path-filtered `ui.yml` (§6).
- **`BatchEvent.pipeline()` is the LOWERCASED pipeline name** (`cfg.identity().pipelineName()`). Any name
  matching against it (triggers, `runPipeline`, `pathFor`) must use the lowercased id — tests call
  `runPipeline("up_stream")`, not `"UP_STREAM"`.
- **Synchronous bus + a held run claim ⇒ never dispatch inline** — the event bus publishes **synchronously on
  the publishing thread**, which holds that pipeline's `PipelineRunGuard` claim. An event-triggered run of the
  **same** pipeline dispatched inline blocks forever on the claim its own thread holds. Hand off to an off-bus
  virtual-thread pool (`triggerWorkers`) — same reason `JobService` hands off. The last inline holdout, the
  pre-v1 legacy trigger/notify routes, moved off the request thread 2026-07-24
  (`CollectorService.runPipelineOffThread` → submit to `triggerWorkers`, block for the result; the pre-v1
  `200 RunResult` body is preserved). So no HTTP request thread holds a run claim.
  <br>⚠ **The rule outlived its original name.** Until 2026-08-01 this was one global `ingestLock`, a
  `ReentrantLock` held across an entire poll cycle for every pipeline. Two things changed: exclusion is now
  **per pipeline** (`PipelineRunGuard`), so unrelated pipelines never block each other and the poll tick
  dispatches-and-returns; and the guard is a **non-reentrant** binary semaphore, because a claim is taken on
  the selecting thread and released by the thread that ran the work. Note the old wording was itself wrong:
  a re-entrant lock would *not* have deadlocked on an inline same-pipeline run — it would have silently
  re-entered and **double-ingested the inbox**. The hand-off is what makes the claim mean anything.
- **`JobService` total concurrency is bounded only when asked** — `-Djobs.maxConcurrentRuns` (default `0` =
  unbounded) installs a `Semaphore` acquired on the **worker** thread inside `submitRun`/`submitAdhocRun`,
  never the caller, so a full pool queues Runs rather than blocking cron/event/manual dispatch. Distinct from
  the batch-ingest `maxConcurrentRuns` (`MultiCollectorProcessor`) and from same-job non-overlap (`LockingRunner`).
  ⚠ **The ingest lane got its own hierarchy on 2026-08-25** — `ConcurrencyBroker` (per-Pipeline / per-space /
  per-server Consignment caps + a 1–3 priority share), hot-tunable from Settings ▸ Scheduler. The **T (job) lane
  is deliberately out of its scope**, so `-Djobs.maxConcurrentRuns` remains the only bound on Runs. See
  [`okf/backend/engine/consignment-concurrency.md`](okf/backend/engine/consignment-concurrency.md).
- **Incident resolution is hard-gated backend-side** (I1, 2026-07-24) — `ObjectService.commit()` rejects
  INCIDENT→RESOLVED (422) unless `attributes.postmortem` has a timeline + cause-analysis + corrective-action
  entry and `dueAt` is set; mirrors the UI `mail-model.ts` `postmortemGaps` soft-warn. Keep the two in sync.
- **T15 back-pressure SHIPPED 2026-07-25 — the per-cycle admission cap now exists.** `IntakeGovernor.capFor`
  holds a per-pipeline cap and `CollectorProcessor.admit()` truncates a cycle's candidate set to it (oldest
  first); the rest wait in the durable inbox. Off by default (`-Dingest.maxFilesPerCycle`=0 ⇒ byte-for-byte
  pre-T15 behaviour). The controller halves on **cycle overrun** and doubles back with hysteresis — **inbox lag
  is deliberately NOT the throttle input** (capping intake raises lag, so throttling on it is positive
  feedback); lag stays observability only (`CollectorProcessor.oldestInboxAgeSeconds` / `InboxStatus` /
  `inspecto_inbox_oldest_seconds`). B4 (acquisition back-pressure, 2026-08-02) is the deliberate mirror on the
  *producer* side. See `pipeline-graph-design.md` §3.5.
- **`PartitionWriter` requires non-empty partition columns** (it emits `PARTITION_BY (...)`, and `reveal` derives
  each partition from the staged file's parent dir). Unpartitioned single-file `COPY` paths live in
  `PartitionSinkWriter` and `SummaryWriter.writeFlat`; the legacy writer is untouched.
- ⚠ **Never issue DuckDB `COPY … PARTITION_BY` directly for durable output.** DuckDB names every partition file
  `data_0.parquet`, so two writers targeting the same partition **overwrite each other** — which silently converts
  the append-only invariant into a rewrite. `PartitionWriter` exists for this: it stages, then reveals each file
  under a caller-supplied per-unit-of-work name. `SummaryWriter` reuses it for exactly this reason.
- **Flow seed = exactly one `source_store`** in Phase-A live execution (rejects 0 or >1; multi-source merge is
  the `transform.merge` path).
- **Per-space `space` MDC must reach EVERY worker thread on the execution path.** Singleton routing reads the
  MDC on the *current* thread, and MDC does NOT cross thread-pool boundaries. Each executor running ingest/commit
  work must `MDC.getCopyOfContextMap()` on the caller + `setContextMap` on the worker + `clear()` in finally —
  `MultiCollectorProcessor.runAll`/`runConfigs` **and** `CollectorProcessor`'s per-batch executor (the batch commit,
  per-batch metrics and event log fire there, not on the poll thread). Miss one and that space's metrics/events
  silently fall back to `"default"`. The `default` space sets NO MDC, so single-space output stays label-free.
- **Hand-authored `.toon` rules (verified live 2026-07-10, `spaces/demo` shakeout):** (1) **No `#` comments
  anywhere** — suffix-scanned loaders (`*_pipeline/_job/_connection/_alert/_queue/…`) strict-reject the file
  ("Multiple primitives at root depth"), and even the lenient registry read mangles comment lines into junk
  keys. Some loaders tolerate them today (template/escalation) — do not rely on it.
  🔴 **A hard reject is the BEST case, and not what happens for a comment between two top-level sections
  (re-verified 2026-08-18).** There, `JToon.decode` **silently truncates at the comment** and
  `PipelineConfig.load` accepts the result with **no error and no warning** — a 4-line explanatory block added
  to `orders/orders_pipeline.toon` dropped `output_store:`, `steps:` AND the pre-existing `collector:` gap
  detection (9 top-level keys → 6), and the file still "loaded". The job then pointed at a Stage-2 chain that
  did not exist in the parsed config. ⇒ after editing any `.toon`, **decode it and diff the top-level key
  list**; a clean load proves nothing. (2) **Lists need counts**:
  inline `members[1]: operator` or tabular `tiles[3]{widgetId,span}:`; bare `- item` lists fail (exception:
  authored-flow `nodes[n]:` blocks accept `- id:` maps). (3) **Alert rules need an `alert:` wrapper** and
  `severity` ∈ {CRITICAL, INFO, WARNING} — not WARN. (4) **Job-type params are FLAT keys under `job:`**
  (`JobConfig.fromMap` treats unknown keys as params); only `args:`/`bind:` nest. A `params:` wrapper in some
  design-doc sketches is doc-only, not the shipped parser.
- **Authored flows live under `config/flows/` — one dir for both readers (FIXED 2026-07-10):** the UI/HTTP
  authored-pipeline CRUD always wrote `writeRoot()/flows` (= the space's `config/flows/`), but
  `DirSpaceRoot.flowsDir()` pointed `JobService`/the T32 deletion fence at a sibling `spaces/<id>/flows/`, so a
  `type: pipeline` job couldn't resolve a UI-authored flow in multi-space mode. `flowsDir()` now returns
  `config().resolve("flows")`; a top-level `spaces/<id>/flows/` is dead (still tolerated by
  `SpaceLayoutContract` as historical) and new spaces no longer mint it. Regression test:
  `SpaceBootstrapTest.flowJobResolvesAFlowAuthoredUnderConfigFlows`.
- **Pipeline-internal paths resolve against the JVM CWD, NOT the space root.** A pipeline's `schema_file`,
  `grammar`, and `dirs.*` are `Paths.get(...)` in `PipelineConfigParser` with **no rebasing** to `spaces/<id>/`.
  Only the *space discovery* layer (`-Dspaces.root`, `SpaceRoot`) is space-relative. So when configs were moved
  under `spaces/<id>/config/` (`ffbf311`), every in-config path had to be rewritten to repo/bundle-root-relative
  form (`spaces/<id>/config/…`, `spaces/<id>/data/…`) — and the `SpaceMigrator` cannot auto-fix absolute or
  author-relative paths for the same reason. Shipped examples now: `spaces/default` (subscriber + events +
  connections), `spaces/ucc` (voucher; lowercase id `ucc`, display "UCC").
- **`RouteModule.register(api)` runs BEFORE any Space is hosted — never call `api.service()` there.**
  Registration only wires handlers; the Space (and therefore the per-Space service) is resolved *per
  request*. Touching `api.service()` at registration time throws `IllegalState No spaces are hosted` and the
  whole `ControlApi` fails to construct, so it surfaces as **every** `ControlApi*Test` erroring in setup, not
  as one focused failure (26 of them, 2026-07-27). A per-Space migration therefore has to run **lazily on
  first use**, guarded by a `WeakHashMap` keyed on the service (`WidgetTags.backfillOnce`) — the object-CSV
  equivalent gets away with living in `CollectorService` only because that *is* the per-Space object.
- **A job write body is the `job:` TOON section, and an unknown key is ABSORBED, not rejected.**
  `POST /jobs` / `PUT /jobs/{name}` hand the body straight to `JobConfig.fromMap`, so the keys are
  **snake_case** (`on_pipeline`, `on_signal`, `catch_up`) and type-specific parameters are **flat**
  alongside them — never nested under `params`. `fromMap`'s `default ->` branch sweeps every
  unrecognised top-level key into the job's parameters, so a camelCase `onPipeline` is **not a 422**:
  it becomes an inert parameter and the job silently ends up with no trigger. The UI had been sending
  exactly that since the endpoint landed, and nothing caught it — no backend test POSTed a job body at
  all, and the offline mock read `body.onPipeline` and echoed it back, so the preview looked perfect.
  Fixed 2026-08-10: `jobToWire`/`jobFromWire` (`inspecto/api/jobs.service.ts`) own the mapping, the mock
  mirrors `fromMap` **independently** (reusing the client adapter would make the round-trip
  tautological), and `ControlApiJobCrudTest` pins all three shapes. ⚠ The read side is **asymmetric on
  purpose**: `GET /jobs` is a Java record (camelCase `onPipeline`/`onSignal`) while `GET /jobs/{name}`
  is the config section (snake_case, flat) and carries **no run state** — `lastStatus`/`nextFire` exist
  only on the list, so a detail pane must merge an enable/reschedule response, never replace with it.

---

## 5. Engine seams & performance (durable; current in `inspecto/`)

- **Single ingestion SPI:** `StreamingFileIngester` (emit-based) is the **only** ingestion SPI. Per-batch the
  framework picks **union** mode (many small files → per-member views `UNION ALL` → one transform/write pass) vs
  **generation** mode (one huge file → bounded flushing). Selector `processing.streaming.large_file_bytes`
  (default 256 MB); generation budget `processing.streaming.flush_records` (default 5,000,000).
- **DuckDB `Appender` ingest** (vs JDBC `executeBatch`) ≈ **75× faster** (1M-row bench ~6.9k → ~510k rows/s).
- **Modularity seams** (behavior-preserving; SQL/`.toon`/on-disk output unchanged): `OutputFormat`
  (enum-as-strategy), `TransformCompiler` (`transformType → ColumnRule`), `BatchIngestStrategy` (Csv/Plugin →
  typed `IngestOutcome`; `BatchProcessor` is a thin coordinator).
- **Auto-derive `duckdb_threads`** — `DuckDbUtil.effectiveWorkerThreads`: `0`=auto `max(1,cores/concurrency)`,
  `>0`=verbatim, `-1`=DuckDB per-core default; single-batch→all cores. Avoids the threads×cores oversubscription
  stall (~+15% tax, widens with cores).
- **Quarantine semantics:** throw → `QUARANTINED_UNREADABLE`; 0 emitted rows → `QUARANTINED_MISMATCH`;
  `SinkFlushException` → fail the batch.
- **Output files: the JSON manifest is authoritative for EXISTENCE, `consignment_outputs` only for STATE.**
  `PartitionOutput(partition, outputFile, bytes)` is an *ephemeral* return value — produced by
  `PartitionWriter.reveal()`, consumed once, discarded — in **three** paths: ingest, `EnrichmentEngine`, and
  `PartitionSinkWriter`. (`DecisionRuleApplier` is *not* a fourth: its `RouteSink` already calls
  `LineageCollector`, and `BatchIngestStrategy.writeAndTrace` seeds its accumulators from `applied.outputs()`, so
  routed-rule outputs reach the ingest hook for free. The hook is `BatchProcessor.finalizeSource`, once per
  Consignment — *not* `writeAndTrace`, which has four callers and is invoked **per segment** in union mode.)
  The durable registry (`DbConsignmentOutputStore`, plan §11.3) is **default-off** and `ServiceStores` degrades a
  failed open to `null`, so **never read a missing registry row as proof a file does not exist** — `BatchManifest`/
  `ManifestStore` stays the artifact of record. Note also that no per-file row count exists at write time (a
  multi-file partitioned `COPY` reports none): ingest sums `LineageCollector`'s per-`(srcId, partition)` counts,
  while enrichment and sinks use `ConsignmentOutputs.countByPartition` (needs no `__src_id`).
  → [`db-layer.md`](okf/backend/engine/db-layer.md) §3.9.
- **Platform Services — the one way a Job reaches an engine facility** (2026-08-09, plan Stage 1 S1-1…S1-7).
  `PlatformServiceRegistry` is built at boot in `CollectorService` and **must be populated before
  `JobService` is constructed** (its constructor registers built-ins and scans packs, and registration
  validates `requires:` fail-closed). A Job Type declares `requires: [<id>]` on its `JobTypeDescriptor`;
  `JobService.runJob` grants exactly that set into `JobContext.services()`, so an **undeclared service stays
  invisible even when it exists** (that honesty is what makes the declaration a security statement, not a
  label). Ids: `notifications`, `incidents`, `schema`, `consignment-status`, `alerts`. Mutating services are
  substituted by `DryRunServices` under a dry run (log the would-be effect, act on nothing) — **every new
  mutating service must be added there**, or `dryRun()` becomes a lie the moment a Job calls it. Two rules
  worth keeping: a **built-in** may declare a grant even when no registry is wired (lean/embedded
  `JobService`, e.g. an engine unit test — the service ships in the same build), while packs and classpath
  providers are always strict; and a grant is only worth declaring if the code **looks it up** — the reach
  `AlertService` has via its own `IncidentAccess` was never `alert.evaluate`'s grant to claim (D7), which
  is why that Job waited for an `alerts` service (shipped 2026-08-10) instead of taking a decorative one.
- **A service whose call cannot be previewed still needs the dry-run treatment — a stand-in is not enough.**
  `alerts` evaluation *is* the action (a breach fires an Alert, advances a cooldown, may open an Incident),
  so `DryRunServices` returns empty AND the consuming Job reports "nothing was evaluated". Returning empty
  alone would let a caller print "no rule breached", which is a worse lie than the one MNT-1 forbids.
  `alert.evaluate` shipped with exactly that bug for three days: it ignored `dryRun()` entirely, so a
  preview fire really evaluated. **When you grant a Job a mutating service, check the Job honours the flag
  too** — the substitution protects the store, not the Job's own reporting.
- **A boot lambda must not capture a `final` field that is assigned later in the same constructor** — it is a
  `variable might not have been initialized` compile error, not a runtime NPE, and it will greet anyone
  registering a service beside the `notifications`/`incidents` block in `CollectorService`. Bind through the
  existing accessor instead (`n -> notificationService().notify(n)`, `IncidentAccess.over(this::objects)`),
  which also gets the live per-space value rather than a boot-time snapshot.
- **An `INCIDENT` does not start `OPEN` and `resolve` is fenced.** Its workflow is
  `IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED` with **only `ARCHIVED` terminal**, and `resolve` throws
  `IllegalState … missing: timeline, cause analysis, corrective actions, SLA` until the postmortem is
  complete (`ObjectService.java:1292`). A test that wants a *terminal* Incident should `archive` (legal
  straight from `IDENTIFIED`), not `resolve`. `ALERT` is the one that starts `OPEN` with `RESOLVED` terminal.
- **`com.gamma.util` CLI cluster** (~11 `main()` tools: `MainApp`, `TarExtractor`, …) sits at low coverage and is
  **kept by decision** (self-contained; `MainApp` is wired into `package.ps1`/ops). Tested engine+control-plane
  is ~86%. Long-term: extract the CLI cluster to its own module. → [`performance.md`](okf/backend/build-run/performance.md).

---

## 6. inspecto-ui conventions (for adding panes)

Angular 22 · Material/Tailwind · ag-Grid 35 · Chart.js · AntV G6 5. **Read the `angular-ui` skill before
touching `inspecto-ui/`.** Highlights (full detail there):

- **API clients** in `src/app/inspecto/api/` (barrel `index.ts`): `@Injectable({providedIn:'root'})`,
  `inject(HttpClient)`, `apiUrl('/path')` (→ **`/api/v1`** since W7) + `toParams({...})` from `api-base.ts`;
  interfaces inline in the service. Interceptor chain: first-position `v1Interceptor` (shape-guarded envelope
  unwrap), `spaceInterceptor` (space id **after** `/v1`), `errorInterceptor`, and `auth.interceptor` — the
  auth flow is a **no-op on Personal** (OIDC only when `bootstrap.features.authMode` says so, W6d).
- **Feature panes** in `src/app/modules/admin/<feature>/`, **signals + OnPush**. A pane can be reused across
  routes via `ActivatedRoute.snapshot.data` (Cases/Issues = one `ObjectsComponent`).
- **Second "lens" on a pane = `mat-button-toggle-group`, NOT a new nav item** (Flows `flow|combined`, Jobs
  `schedules|reporting`). Factor shared blocks into `<ng-template>` + `*ngTemplateOutlet`.
- **No hardcoded colors** — CI guard `npm run lint:tokens` fails on hex/`rgb()`/`levelClass`-style helpers under
  `inspecto/**` + `modules/admin/**` (allowlist: `chart-tokens.ts`, `status-badge.component.ts`). Status/level
  colors come from `<inspecto-status-badge>` only. `rgba(var(--gamma-…))` is allowed.
- **a11y gate** — `expectNoA11yViolations(el)` (`inspecto/testing/a11y.ts`, axe-core) in component specs; runs in
  CI. Manual WCAG: `docs/ui/accessibility-audit.md`.
- **Shared design system**: `status-badge` / `empty-state` / `skeleton` / `grid` (+ `noRowsOverlay`) /
  `connectivity-banner` / `ai-assist`. Living gallery at `/design`.
- **`<inspecto-ai-assist>`** (`inspecto/ai-assist/`, AGT-6a) is the ONE inline AI authoring surface —
  panes **adopt** it, never fork it. The pane names a non-mutating agent tool, passes its own context as
  `[args]`, and applies the returned draft through **its own** validated route (the surface has no write
  path, so the human stays the audited actor). → `okf/frontend/features/inline-ai-authoring.md`.
- 🔴 **A root-level `{ provide: MatDialog, useValue: … }` in a component spec is SILENTLY IGNORED** (found
  2026-07-27). `MatDialogModule` sits in the standalone component's own `imports` and provides `MatDialog` at
  the **element** level, which shadows the TestBed root provider — `catalog.component.spec.ts` had mocked it
  since it was written while the component used the real service throughout. Reach it with
  `TestBed.overrideComponent(C, { add: { providers: [{ provide: MatDialog, useValue: MOCK }] } })`. Applies to
  any service a component's imported Material module also provides. ⚠ **Other specs mocking `MatDialog` at the
  root are mocking nothing — not audited beyond that one file.**
- ⚠ **`LensService` snaps to the read-only Business lens when `SessionService.capabilities()` is `[]`**, because
  `allowedLenses` qualifies Builder only via `canAuthorWorkbench`. A spec mocking capabilities as `[]` sees every
  authoring affordance hidden, and the resulting "the button never fired" failure looks like broken wiring. Pass
  `['canAuthorWorkbench']` when testing an authoring path.
- ⚠ **Narrowing a UI test run:** `npx vitest run <file>` fails here (`Cannot find package 'app/inspecto/api'` —
  the path aliases come from the Angular builder). Use `npx ng test --no-watch --include='<glob>'`, and the
  include must be a **glob**: a literal file path yields *"No test files found, exiting with code 1"*, which
  reads like a config break rather than a bad flag.
- ⚠ **The offline mock must never be more lenient than the server** (2026-07-27, AGT-6a A5.3). A handler
  that accepts a shape the backend rejects — or returns a richer shape than the backend returns — turns a
  hard failure into a passing rehearsal. The Pipelines `pipeline_author` adoption shipped **broken through
  two slices** (flat args where the tool requires `flow`; a name string where `adaptToolResult` requires the
  graph, so a successful call rendered as *"no suggestion"* with no error) and looked correct offline
  throughout. When touching `inspecto/mock/handlers/`, diff the handler's accepted args **and** its result
  keys against the real route, and pin the strictness in a `*.handler.spec.ts` — the preview cannot catch
  what the mock permits.
  ⚠ **It was not one bad branch — a deliberate audit (2026-07-27, `feb6f6e7`) found the same class live in
  two more branches of the same file**, and a lenient mock hides *server* defects too: tightening
  `component_draft` immediately exposed that its `schema` kind validates the wrong `schema` entirely.
  Two rules that came out of it: (1) where full parity would mean re-implementing a backend subsystem,
  mirror **acceptance** — the same inputs refused, the same inputs rendering nothing — and say so at the
  branch; (2) a mock stand-in must emit the shape the **server** parses, never a convenient flat one, or it
  teaches the wrong contract to whatever consumes it next.
- **ag-Grid gotchas:** (a) action/string cell renderers don't render on first paint with static `rowData` →
  call `refreshCells({force:true, columns:[…]})` on `(firstDataRendered)`/`(rowDataUpdated)`; (b) the shared
  theme MUST be the gamma-token `themeQuartz.withParams(GAMMA_GRID_PARAMS)` (`app/inspecto/grid/index.ts`) — never
  bare `themeQuartz`; (c) off-screen (virtualized) columns aren't in the DOM until you scroll horizontally — set
  `scrollLeft` before asserting in preview.
- **`@if/@else` + `mat-icon` button ⇒ NG8011** (icon won't project). Keep always-on icon buttons outside the
  branch, or make the branch's only root the button.
- **A `computed()` that reads a plain `@Input` field NEVER invalidates** — computeds track *signal*
  dependencies only, so a parent flipping the input leaves the derived value permanently stale (cost us a
  disabled-forever button in `ai-assist`, 2026-07-26). Use **signal inputs** (`input()`) whenever a
  `computed()`/`effect()` reads the input. ⚠ Specs miss this by construction if they set inputs *before*
  the first render — assert the change with `fixture.componentRef.setInput(...)` *after* it, and note that
  assigning to a signal input no longer compiles.
- **TestBed `{provide: MatDialog, useValue: …}` is silently shadowed** on any pane that imports
  `DataTableComponent` (or anything else importing `MatDialogModule`): the standalone component's
  *standalone injector* re-provides the real `MatDialog` closer than the testing module, so the pane
  injects the real service and `open()` explodes in jsdom (`undefined.push` in material dialog.ts).
  Fix: after `createComponent`, `vi.spyOn(componentInstance['dialog'], 'open').mockReturnValue(...)` —
  spy on the instance the component actually got (see `alerts.component.spec.ts`). Several older specs
  carry the dead-weight provider without noticing because they never call through `open`.
- **jsdom forbids spying `window.location.assign`** (`TypeError: Cannot redefine property: assign`), so any
  code that navigates *away from the SPA* is untestable in place. `SessionService.redirect(url)` is the one
  seam for it (authorize + OIDC end-session); stub that, not `location`. Route it through the seam rather
  than adding a second `location.assign` call site.
- **Authenticated file download** — go through `HttpClient` (`responseType:'text'|'blob'`) + `Blob` +
  `createObjectURL` + transient `<a download>`; a plain anchor `href` doesn't carry headers.
- **Live tail** — `visibleInterval(ms)` (`api/auto-refresh.ts`, pauses on hidden tab); hold/resubscribe/unsub;
  `silent` flag avoids loader flash. `DEFAULT_REFRESH_MS=15000`.
- **Connectivity** — `ConnectivityService` (status 0 ⇒ unreachable) + `<inspecto-connectivity-banner>` owns the
  "backend down" UX (don't add per-screen toasts; **503 ≠ backend-down**). Banner host needs
  `:host{display:contents}` so it doesn't steal layout width.
- **Mocking** — ONE mock backend: `inspecto/mock/` (framework-free `MockStore`: per-Space, localStorage
  `inspecto.mock.vN` = `MOCK_STORE_KEY`, RefRule 409s, seed packs) behind the single `mockApiInterceptor` — ALL six feature mocks
  absorbed (demo → connections → components → pipelines → ops → jobs handler order = old chain precedence).
  New mock endpoints = new handler there, **never** a new per-feature mock interceptor. 4xx replies must be
  `HttpErrorResponse`s. `simulator.ts` ticks Runs/Events/Alerts lazily per intercepted request (no timers);
  bump `MOCK_STORE_KEY` whenever a seed pack's SHAPE changes or stale localStorage masks the new seeds.
- **Config-attribute forms are schema-driven** — declare `AttributeSpec[]` (tier `required|optional|advanced`,
  `dependsOn`) in `inspecto/component-model` and render with `<inspecto-schema-form>` (demo at `/design`;
  pilot: jobs `job-form.dialog`). Hand-build only bespoke sections (canvases, key/value arrays). `tier`
  (visibility) and `required` (validation) are decoupled — `required?: boolean` defaults from the tier but
  can be set explicitly, e.g. `tier:'required', required:false` for an always-visible optional field
  (`widget-option-attributes.ts`). Duplicate-name guard on create is a local `uniqueNameValidator` attached
  to the id control, skipped entirely when the field is locked on edit (jobs/dataset-editor/
  dashboard-editor/widgets all use this shape). ⚠ **Pipeline NODE attributes are a special case since
  2026-08-04: the server publishes them** on `GET /pipelines/node-types` (`attributes[]`, from
  `NodeAttributes.java`), so `pipelines/node-attributes.ts` is a **fallback**, not the source. Change a node
  attribute in BOTH, or the committed `inspecto/mock/node-attributes.contract.json` drift check fails on one
  of the two sides (deliberately — see `okf/frontend/features/pipelines.md`). Adding an `AttributeType` still
  needs `FindingsSpec.TYPES` widened, which `NodeAttribute` now delegates to.
- **Optimistic mutations** — `optimisticMutate({apply,commit,reconcile,rollback,onError})` (`inspecto/api/
  optimistic.ts`); reassign arrays (`rows=[...]`) so the grid re-renders.
- **G6 graph** — reuse `modules/admin/catalog/graph-view.component.ts` (`@Input data`, `@Output nodeClick`);
  nodes are canvas-drawn (not DOM) → verify inspector logic via unit test, not preview clicks. Flow graph data
  via `flow-graph.ts#toFlowG6Data`.
- **Viz plugins register by side effect** — `import 'app/inspecto/viz/plugins'` runs `registerBuiltinViz()`.
  Admin shell surfaces trigger it transitively; a **guest/shell-less or lazy route that renders widgets must
  import it explicitly** or `getViz(type)` returns undefined and every tile reads "not embeddable" (bit BI-6
  `/share/:token` + BI-8). Reference: `modules/admin/share/share-viewer.component.ts`.
- **Anonymous routes** — add the path prefix to `space.interceptor` `SERVER_GLOBAL` (e.g. `/public`) or the
  active-space rewrite 404s it; the call is token/credential-addressed, not space-scoped.
- **`<inspecto-empty-state>` inputs are `title` + `message`** (not `heading`); `message` is required. Wrong
  input names fail silently (dropped in prod, caught only by a text assertion).
- **A Dataset's rows come from `DatasetRowsService`** (`inspecto/viz/dataset-rows.service.ts`, split S2
  2026-08-14) — `/db/table`, or `/db/query` with its Query Core model compiled by `compileSql`, live; the
  offline sample page otherwise. ⛔ Never re-introduce a `SAMPLE_SOURCES[ds.sourceName]` lookup in a
  feature: that synchronous read is why Studio showed sample data against a real backend. Results are
  **pages** (honour `truncated`, surface `error`). Detail + the ⛔ on the three offline arms that must NOT
  be converted: `okf/frontend/features/studio.md` and the `angular-ui` skill.
- **BI widget/dashboard content shape** — a `widget` component is `{vizType, datasetId, controls, options}`
  (channel mapping, NOT a raw query spec); a `dashboard` is `{name, tiles:[{widgetId, span}]}`. Anything
  writing these server-side (e.g. `BiTemplates`) must emit this shape or the Studio can't render it.
- **Dev**: `npm start` (`ng serve` :4204); `proxy.conf.json` maps `/api` → `:8080`. `.claude/launch.json`
  defines both preview servers.

---

## 7. Related sandboxes (separate repos — pointers only)

- **agent-kernel** (`C:/sandbox/agent-kernel`) — DISCONTINUED; Inspecto vendored its reasoning layer 2026-07-07.
- **eoiagent** (`C:/sandbox/agent-brainstorm`) — agent platform; Inspecto's model transport. Pinned to the
  released **`0.1.0`** (tag `v0.1.0`, EOI-7a 2026-07-08; trunk now `0.2.0-SNAPSHOT`). Rebuild into local `.m2`
  with `git checkout v0.1.0 && mvn -o clean install` until a registry is chosen (EOI-7b).
- **CVVE** (`C:/sandbox/agentic-doc-validation`) — kernel's 3rd consumer; first real `HumanHandoff` driver.

(Detailed progress for these lives in the per-user agent memory, not in this repo — they are different projects.)
