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
local `.m2` from `C:/sandbox/agent-brainstorm`) — see `docs/archived-documents/plans-archive/agent-kernel-replacement-plan.md`.

---

## 2. Authoritative docs map (go here first)

| Topic | Doc |
|---|---|
| **What a capability REQUIRED / has BUILT / has LEFT / REFUSED** (new tier 2026-09-08) | [`okf/capabilities/`](okf/capabilities/index.md) — one doc per area ID; **9 of 17 slots written** as of 2026-09-08 (`ACQ` pilot, then `SEC` · `INC` · `API` · `SPC` · `MET` · `DAT` · `ING` · `OPS`). Each one's §2 is the requirement-of-record and **overrides `REQUIREMENTS.md` §3 and `EDITIONS.md` where they differ**; §5 lists what is not built. The remaining 8 slots are named in [`GLOSSARY.md`](GLOSSARY.md) §14 and sized in [`docs-consolidation-plan.md`](archived-documents/plans-archive/docs-consolidation-plan.md) §5.1. ⚠ The `okf/` sections answer only "what is built", and answer it by CODE LAYER |
| Production investigation (process/events/metrics/state/`-D` flags/Control API/troubleshooting) | [`ADVANCED_GUIDE.md`](ADVANCED_GUIDE.md) — **living doc** |
| Pipeline-graph design (IR, lift, validator, executor, registry, T-checklist §14) | [`pipeline-graph-design.md`](okf/backend/pipeline-graph/pipeline-graph-design.md) |
| Live execution of authored Pipelines (`JobType.PIPELINE`, T32) | [`live-execution.md`](okf/backend/pipeline-graph/live-execution.md) |
| The token model + decisions D1–D10 | [`okf/backend/engine/node-types.md`](okf/backend/engine/node-types.md) § *The token model* · [`okf/capabilities/pipeline-authoring/pipeline-authoring.md`](okf/capabilities/pipeline-authoring/pipeline-authoring.md) § *Decisions* *(the plan was archived 2026-09-10)* |
| Execution residuals — X3 park detail · X2 cross-lane provenance · X1 bounded COMMIT retry (**SHIPPED + archived 2026-09-02**; X4/X5 sketches in BACKLOG §4) | [`execution-residuals-plan.md`](archived-documents/plans-archive/execution-residuals-plan.md) |
| Pipeline editor UI (Recipe/graph views, drawer, attribute forms) | [`pipeline-editor.md`](okf/frontend/features/pipeline-editor.md) |
| Authoring redesign for business users — the sectioned Parse pane (Delimited first: compact property rows, plain-language labels, grounded defaults, ONE "Columns that come out" table) + the **SQL-first** `transform.sql` Step (the 2026-09-03 Simple fields grid was superseded and deleted the same day the operator reviewed it live) (**SHIPPED + archived 2026-09-04**: `98ffc90b` · `7e13dd82` · `d012f721` · `c119a6af` (steps: home) · `1d557bbd` (one property-row idiom, no Description) · `24171333` (SQL-first); follow-ons in BACKLOG §4 AUTHORING-REDESIGN-1) | as-built: [`grammar-config.md`](okf/frontend/features/grammar-config.md) (Parse pane) · [`schema-mapping-authoring.md`](okf/frontend/features/schema-mapping-authoring.md) §0 (Transform pane) · [`pipeline-editor.md`](okf/frontend/features/pipeline-editor.md) · [`catalog-vs-executors.md`](okf/backend/engine/catalog-vs-executors.md) (engine) · decisions + R1–R12 review: [`parse-pane-redesign-plan.md`](archived-documents/plans-archive/parse-pane-redesign-plan.md), [`sql-transform-v1-plan.md`](archived-documents/plans-archive/sql-transform-v1-plan.md); mockup source `archived-documents/plans-archive/assets/authoring-redesign-mockup/` |
| **Pipeline authoring finalized (2026-09-04, `0a2fa82f`)** — the redesign's open seams closed in one pass: the **`sql` Recipe verb** (between `transform` and `summarize`; `RecipeCompiler`/`RecipeConverter` round-trip `{sql, fields}`, so `GET /pipelines/{id}/recipe` no longer throws `UNSUPPORTED_STEP` on a SQL Step — mid-branch it compiles but deliberately does NOT arm, `RouteArming.BRANCH_STEP_KINDS` excludes it) and **`POST /components/transform/describe`** over `TypeFlow.describe`, which fills "Comes out as" and surfaces DuckDB's binder message as-you-type without executing a row. 🔴 Three defects were found by reading, not by tests: the describe route ran author SQL with NO `SqlGuard` and no sandbox (a `DESCRIBE` never executes the plan, but the binder OPENS a `read_csv('…')` target to infer its schema — the one author-SQL entry point that could read arbitrary files; now guarded, with a negative test whose probe is a CSV that really exists); hand-written SQL in CodeMirror could not be SAVED at all (`valueChange` bound straight to the signal emitted no `dirtyChange`, and `canApply()` refused `sqlOnly` outright); and the Load pane's new legacy-`columns` fallback would have written `rules` beside a surviving `columns`, which `RowShaper.columnsOf` PREFERS — a save that is real and dead at once. Two more came from DRIVING the pane on a real backend: it sent VARCHAR for every upstream column, so `AMOUNT * 2` over a declared DOUBLE was reported as a binder error and BLOCKED Apply (a false refusal — the declared types now travel via `[upstreamColumnTypes]`), and the JDBC wrapper line ("…closed pending query result") led every message, so the first thing the author read was driver plumbing. Also: only a 422 blocks Apply (offline/404/503 must never lock the author out), and the Parse↔Sink `partitions[]` stale-Apply race is closed by re-reading the companion before the write. Verified `mvn -o clean test` exit 0 (23 modules, 3971/0/0/5) + UI gate exit 0 (2788 tests). ⚠ The commit itself was created by a CONCURRENT peer session sweeping the working tree, so its message under-describes the three defects above — this row and BACKLOG `AUTHORING-REDESIGN-1` are the provenance. | [`schema-mapping-authoring.md`](okf/frontend/features/schema-mapping-authoring.md) §0 · [`catalog-vs-executors.md`](okf/backend/engine/catalog-vs-executors.md) · [`pipeline-config-keys.md`](okf/backend/pipeline-graph/pipeline-config-keys.md) · [`grammar-config.md`](okf/frontend/features/grammar-config.md) · BACKLOG `AUTHORING-REDESIGN-1` (a)/(h)/(n1)/(n2)/(n3)/(o) |
| Step park & drain (disabled route branches, `POST /runs/{n}/drain`) | [`step-park-drain.md`](okf/backend/pipeline-graph/step-park-drain.md) |
| Branch-aware ingest executor (armed `route:`, engagement) | [`branch-aware-ingest.md`](okf/backend/engine/branch-aware-ingest.md) |
| Data acquisition framework (Phases A–F, connectors, dedup, watermarks) | [`data-acquisition-framework.md`](okf/backend/acquisition/data-acquisition-framework.md) |
| All TOON config keys | [`configuration.md`](okf/backend/config/configuration.md) |
| Editions (Personal/Standard/Enterprise = build flavors); feature × edition board; Step Processor catalog table | [`EDITIONS.md`](EDITIONS.md) |
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
  `OidcTokenRelay` (renamed from `KeycloakTokenRelay` 2026-07-25, D15); reactor-gated behind the `edition-standard` profile) behind the
  `Authenticator`/`Subject`/`TokenRelay` SPIs (`com.gamma.control`), plus HTTPS (`HttpsServer`) and the BFF
  `/auth/exchange|refresh|logout` routes; Angular uses OIDC Auth-Code+PKCE driven by `bootstrap.features.authMode`
  (no-op on Personal). **The `-Dassist.write.root` 503 write-gate is SEPARATE from auth and stays.**
- **Edition gating is real as of 2026-09-07, not just a matrix claim (EDG-01).** Five "not for Personal"
  features left the core into optional modules — `inspecto-notify-channels` (CP-15), `inspecto-backup`
  (OPS-06), `inspecto-geo-link` (CP-09), `inspecto-exchange` (SEC-10) and `inspecto-metrics` (CP-13's
  exposition) — joining `inspecto-security` and `inspecto-policy`. Personal answers their surfaces **503
  naming the module**, never 404. 🔴 The load-bearing one was `/metrics`: a `PUBLIC_PATH` on an edition
  that ships no authenticator and binds every interface. Recipe + traps:
  [editions model](okf/backend/editions/editions-model.md). ✅ **EDG-01 COMPLETE 2026-09-08** — all six
  cells are now true of the build; the plan is archived. ⚠ The two hardest cells each had to change what
  the product PROMISES, not just where code lives: cell 6 kept a narrow core `/audit/*` read because
  gating `/events*` would have removed Personal's Audit-log screen (§Audit promises it), and cell 7
  amended `OPS-01` and `SP-CTL-02` because they promised Personal an `objects` store and a gap watchdog
  that raises ALERT objects. **Read what the neighbouring matrix rows promise before scoping a gating
  cell** — neither collision appeared in the census.
- **Keep the core lean.** All network deps live in `inspecto-connectors`; hosted-AI SDKs in
  `inspecto-agent-hosted` (physically absent from air-gapped builds). The zero-new-dep rule was retired
  2026-06-13 (logback replaced slf4j-simple, user-approved) — still no gratuitous deps.
- **Pipeline-graph track is `master`-only** (`feat:` → master; empty merge-forward set; retired lines untouched).
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

- 🔴 **FOUR NEW FALSE-GREEN MECHANISMS, all of which report SUCCESS** (2026-09-17). (1) `mvn … ; echo
  "EXIT=$?"` records the **echo's** status, so a failed build reports exit 0 — capture Maven's status
  directly. (2) `-DfailIfNoSpecifiedTests=false` is **silently ignored**; the working spelling is
  `-Dsurefire.failIfNoSpecifiedTests=false`, and without it a filtered `-am` run dies at `asn-core` for the
  wrong reason. (3) **`-Dtest=A+B+C` runs ZERO tests and reports BUILD SUCCESS** — `+` is Surefire's *method*
  separator, so the whole thing is one literal pattern matching nothing. Commas are required. (4) A subagent
  reporting **"Verdict: PASS" from the wrong tree and without `-Pedition-enterprise`** — 23 modules instead
  of 32, so every edition-gated module was skipped, with the verdict read off the log rather than from
  `check-reactor-verdict.mjs`. ⇒ **Re-derive every verdict with the tool, on the tree you are about to
  commit.**
  ⚠ And one **false RED**, which is rarer and makes people "fix" a non-problem: with `node_modules` absent in
  a fresh worktree, `npx tsc` resolves to an unrelated npm package and fails with a message that reads exactly
  like a type error.

- 🔴 **A TEST CAN BE GREEN FOR A REASON THAT IS NOT THE CODE** (2026-09-17).
  `ControlApiBundleNewKindsTest`'s bundle-escape guard passed or failed depending on three machine facts and
  none of them the security property: the allowed roots are
  `${session.executionRootDirectory};${java.io.tmpdir}` (`pom.xml:418`), the value resolved against the
  **process working directory** because the test helper clears `assist.write.root` before the request, and six
  `..` clamps at the drive root on Windows. `-Djava.io.tmpdir=C:\` flipped the verdict with **no code change**.
  ⛔ It was announced as "master is RED on a security guard" before being grounded — and the gate was in fact
  sound. ⇒ **Probe the gate DIRECTLY (call the validator by hand) before believing a test about it**, and pin
  such a property against an absolute path constructed to be outside every root, never by counting `..`.

- ⚠ **`git checkout -- <file>` reverts to HEAD, not to your pre-experiment state** (2026-09-17). Falsifying a
  guard by planting a bad value and then "undoing" it that way **discarded a lane's real change** to the same
  file. Copy the file aside first, or restore the intended hunk from the patch afterwards. Same family as the
  standing "unstage, never delete" rule for a peer's work.

- ⚠ **`docs/BACKLOG.md` keeps a CLOSED row's original prose as an indented child bullet** under its own
  strikethrough header (the "Original row follows" convention). So **grepping a row ID lands on open-sounding
  text first**, and two lanes were tasked from already-shipped rows in one shift. ⇒ Assign work only from a
  **top-level, unstruck** bullet: `grep -nE '^- \*\*P[123]\*\*' docs/BACKLOG.md`.

- ⚠ **A row's enumerated call-site list is a hypothesis, and it understates as often as it overstates**
  (2026-09-17, four instances). `SQLIDENT-NINE-COPIES-1` named nine copies: there were **16** across five
  modules, four of them *inline* with no helper method, and **two of the nine were the previous fix**, not the
  defect. A job-param census globbed `*_job.toon` and structurally missed `*_job_template.toon`. One row named
  one `!Files.isDirectory` site where four existed; another named four demo schemas where the whole
  25-file corpus was affected. ⇒ **Grep the pattern, then re-derive the count.**

- ⚠ **A row's prescribed FIX can be unbuildable, and the reason is usually the module graph** (2026-09-17).
  `SQLIDENT`'s prescribed home (`inspecto-sql`) would have been a **cycle** — it depends on `inspecto-util`,
  where two of the copies lived. `PACK-SPI`'s prescribed `OptionalSpi` reuse is unreachable from
  `inspecto-engine`, because `inspecto-processor` depends on the engine, not the other way round. ⇒ Check the
  dependency direction before adopting a row's remedy.

- 🔴 **A GUARD FAILS IN THE DIRECTION OF PASSING — five instances in two days** (2026-09-16/17). Each was
  green while blind: a prose regex that required the number word to touch its noun, so a **bolded** count
  matched nothing; surefire reports surviving `mvn clean` (which only cleans modules the reactor REACHES),
  so a halted build summed a previous run's greens; an allow-list scope (`ROOTS`) that never scanned
  `inspecto/`, hiding a broken customer-facing README for months; a deny-list validated on a tree that
  lacked the thing it should deny (the packaged bundle output), so it was green for its author and
  2417-red on a checkout that had ever built one; and a shipped `probe()` written as
  `curl -fsS … || echo "(request failed)"` — **a probe that cannot fail**. ⇒ **Falsify every guard BOTH
  ways before trusting it**, and prove the probe returns a hit on a positive control. A guard you have
  only ever seen pass is not verified. ⛔ When a guard's reach is WIDENED, re-run it before assuming the
  case you were chasing is the only one — every widening this shift found more.

- 🔴 **MOVING A READER IS NOT DONE UNTIL ITS COMMITTED CONFIGS MOVE WITH IT** (2026-09-16). The one-sided
  break happened THREE times in one day, and every time the reader was ours: `BackupTask`, `CleanupTask`,
  then the compactors. The symptoms are quiet — a cleanup sweeping an empty doubled directory and
  reporting success; a verify returning a green "no archive to verify". ⚠ A lane working from a stale base
  **cannot see that it is the one who moved the reader**. ⇒ Land the reader and its configs in one change,
  or sequence them explicitly on the row.

- ⛔ **THE GIT INDEX AND WORKING TREE ARE SHARED BETWEEN SESSIONS** (2026-09-17). A peer's `git add` stages
  into *your* staging area, and their uncommitted file can turn *your* pre-push red — in one case failing
  a different guard depending on whether it was staged. Use `git commit --only <paths>`. ⚠ And when you
  find a file that is not yours: **UNSTAGE it, never delete it** — `git restore --staged` leaves it on
  disk. `rm` plus `git checkout --` destroyed a peer's uncommitted plan this shift; it survived only
  because they re-created it. ⚠ `git apply -3` also writes to the index, and swept another lane's files
  into the wrong commit twice in one day.

- ⚠ **A PER-MODULE GREEN IS NOT A TREE GREEN** (2026-09-16/17, hit four times). `inspecto-exchange` is
  edition-gated, so no lane's `mvn -o -pl inspecto -am test` compiles it — it failed a combined run every
  lane had passed. A cross-field rule change in `inspecto-config` went red three modules downstream in
  `inspecto-intelligence`. ⛔ `mvn -o -pl inspecto-ops -am` does not even RESOLVE without
  `-Pedition-standard`, which is why the repo's only sweep over every committed Space config never ran in
  a default build. ⇒ Gate on `tools/check-reactor-verdict.mjs`, never on a sum of surefire reports.

- 🔴 **WRITING A FILE THROUGH A PYTHON/HEREDOC LAYER CORRUPTED THREE FILES IN ONE SHIFT** (2026-09-16).
  Three separate traps, one root cause — an escape interpreted by the *writer* instead of landing as text:
  (a) `"🔴"` in a Python string is two **lone surrogates**, which cannot be UTF-8 encoded; `open(w)`
  had already **truncated** `docs/BACKLOG.md` to **0 bytes** before the write raised, and the same command
  chain then `git add -A`'d the empty file into a commit. ⚠ **Every doc guard passed on the empty file** — an
  empty file has no violations — and `git add -A` stages destruction as readily as an edit. It was caught only
  because a `grep -c` that should have returned 5 returned 0. (b) A `\\` inside a bash heredoc **collapsed** to
  `\`, producing `replace('\', '/')` — an unclosed character literal. (c) a unicode escape for NUL landed as a **real NUL byte**, which the pre-push guard refused (a NUL makes the whole file invisible to recursive ripgrep).
  ⇒ **Use `Edit` for existing files**, especially large tracked docs; if a script must write one, `wc -c` it
  before staging, and never `git add -A` after a script that touched a doc.
- 🔴 **THE SHARED TREE HAD A PEER'S UNCOMMITTED WORK DURING HANDOFF — AND IT BLOCKED THE ARCHIVAL STEP**
  (2026-09-16). At shift end `git status` showed 10 modified + 8 untracked files from another session
  (dead-property validation, accepted-config-keys, two new designs). Two of them were load-bearing for MY
  handoff: the peer was **editing `dataset-column-derivation-plan.md`**, the very plan the doc lifecycle said
  to archive, and had **`docs/INDEX.md` dirty** — and archiving a plan *requires* an INDEX edit. ⇒ the
  archival was **deferred, not skipped**, and recorded as owed. ⛔ Do not `git mv` a plan or edit `INDEX.md`
  while a peer has either dirty: that is how a peer's work gets swept into someone else's commit (it has
  happened here before). Stage by PATH, never `-A`, and read `git diff --cached` before committing.
- 🔴 **"THE UPSTREAM SEAM HAS SHIPPED" WAS CHECKED ON THE TYPE, NOT THE SEAM** (2026-09-16). `AGT-5`'s
  external gate was discharged on 2026-09-08 because `DryRunProvider.class` is in the pinned eoiagent jar. It
  is — but `javap -public` on `eoiagent-platform`'s `PlatformBuilder` shows no `dryRunProvider(...)`; the only
  setter is on a gate builder the platform constructs internally, so the host can never supply one. A week of
  "actionable" that was not. Before discharging an external gate, check the ENTRY POINT the host would call.
- 🔴 **A GATE THAT HAS NEVER BEEN RUN IS NOT "UNVERIFIED", IT IS UNKNOWN — AND IT MAY BE ONE FLAG FROM RUNNABLE**
  (2026-09-16). The ELT §6 step-2 parity gate ("the whole suite executing through the compiled-recipe path")
  sat for months as prose. Passing `-Dingest.lane` through the root pom to surefire made it
  `mvn -Dingest.lane=graph test`; the first run turned 13 engine tests red for two reasons the lane itself
  names (sink-count mismatch on multi-schema fixtures; a Decision Rule routing rows). Parity does not hold.
  A projection round-trip test (`RecipeConverterTest`) is not an execution gate.

- 🔴 **REACHABILITY OF A FILE IS NOT REACHABILITY OF A METHOD** (2026-09-15). A P1 was filed, reported as
  confirmed, and refuted the same day on this exact gap. The claim: the graph ingest lane writes
  `run_id = null` into `consignment_outputs` and so escapes its UNIQUE key. Everything checked was true —
  the null-supplying line exists (`ConsignmentGraphRunner:82`), and the graph lane **is** reachable on the
  default `-Dingest.lane=auto`. The unchecked question was **which overload the caller uses**: that line
  lives in a two-arg `run` overload with no production caller, while production passes its own
  `IngestSinkWriter` and registers through `ConsignmentIngestor`, which mints a run id.
  ⇒ **Grep the CALL GRAPH, not the line.** "Class X is used in production" and "this constructor in class
  X runs in production" are different claims, and an overloaded method makes them come apart silently.
  ⚠ Same family as the dead-parity-mirror trap: grep for **callers**, not for the symbol's existence.

- 🔴 **AN OUTLIER AMONG SIBLINGS IS NOT EVIDENCE THAT THE OUTLIER IS THE MISTAKE** (2026-09-15). A route
  audit listed `POST /requirements` as gateable because both its siblings carry a capability gate and it
  does not — "an inconsistency, not a judgement call". It is **SEC-7(c)** and deliberate (anyone may raise
  a requirement; only a triager decides), pinned by a test that says so in its name. Gating it turned the
  build red. The same pass listed `POST /spaces`, which is the **recovery route**: deleting the last Space
  leaves a server hosting none, and a gate there bricks it exactly as an earlier `writeRoot()` resolution
  did — every route failing, *including the one that would recover it*.
  ⇒ Asymmetry is evidence that **something** was decided, not of which side was wrong. Read the sibling's
  test names before "fixing" the odd one out. ⚠ Note where the audit's rigour actually was: it reviewed
  its *correctly-ungated* buckets carefully and its *should-be-gated* bucket not at all.

- 🔴 **A ROW KEPT "FOR PROVENANCE" IS INDISTINGUISHABLE FROM AN OPEN ROW** (2026-09-15). A closed P1 sat on
  `BACKLOG.md` under its own closure note, marked *"(original row retained below for provenance)"*, and was
  counted as live work by every reader and every `grep`. ⇒ Closed work leaves the board; provenance is the
  OKF concept's job and git's. ⚠ The board's census had also been carried forward rather than recounted
  and was wrong by 17 rows — **recount on the way out of any shift that files one.**

- 🔴 **A RED REACTOR IN THE SHARED TREE IS NOT EVIDENCE UNTIL `git status` SAYS WHOSE CHANGES IT COMPILED**
  (2026-09-15, eleventh shift). A full `-Pedition-enterprise` run went red in 41 tests across
  `DbStatusStoreTest`, `CollectorServiceTest`, `RunLeaseContractTest`, `ControlApiTest` — none touched by the
  change under test. No concurrent build, no contention in the log. `git status` showed **nine uncommitted
  engine files belonging to the other session**: half-finished work on three board rows, compiled and tested
  with mine. The same patch on a clean base was 4530/0/0/28. ⇒ **Verify in a detached worktree at a PINNED
  SHA** (`git worktree add --detach <path> $(git rev-parse HEAD)` — `HEAD` moved under the peer mid-run and
  their "isolated" green turned out to contain my code), apply only your patch, and **assert a fact that
  differs between base and patch** before building (a per-class `@Test` count caught what a total could
  not). ⚠ **Keep the worktree under `C:\sandbox\`**: `ControlApiJobCrudTest`'s save-time containment
  assertion fails in a `%TEMP%` checkout and passes under `C:\sandbox` and on the CI runner — path-sensitive,
  not red. ⛔ Never `git stash` in the shared tree to isolate; ⛔ never `git checkout -- <file>` inside the
  worktree to undo a mutation (it reverts to base and loses the patch — copy aside/back). ⚠ Two verify
  agents' aggregate sums were wrong the same day (by 226 and by 1): recount from the per-module `Results:`
  blocks yourself, and beware per-class + per-module lines summed together (≈2×).
- 🔴 **STAGING A SHARED FILE BY PATH SWEEPS THE OTHER SESSION'S HUNKS INTO YOUR COMMIT** (2026-09-15).
  `87a4d97c` was staged by explicit path — the standing rule — and still carries the peer's three
  `BACKLOG.md` rows, because both sessions were editing the same file. "Never `git add -A`" protects
  against other FILES, not against a collision inside one. For `BACKLOG.md`, `INDEX.md`, `PROJECT_NOTES.md`:
  `git diff -U0 -- <file> | grep -c '^@@'` must equal the hunks you wrote, and each must be yours, before the
  file is staged. The content landed correctly; only the attribution is wrong, and `6770d031` says so.
- 🔴 **ROUTE AUTHORIZATION IS OPT-IN — AN UNDECLARED ROUTE IS OPEN** (2026-09-15). `ApiContext.withCapability`
  is a wrapper a route chooses to use; `CapabilityManifest.capabilityFor` documents `null` = "ungated" as a
  legitimate outcome; the router never sees the capability (erased into an opaque `Handler`), and
  `CapabilityManifestTest` sees only registrations that already wrap. Two whole route classes
  (`EventRoutes`, `AssistRoutes`) had no gate and no manifest entry — a *file nobody opened*, not a *route
  nobody listed*. For compliance that is a **mechanism** finding: per-route fixes pass a point-in-time review
  and fail a Type II window. The fix is a fail-closed default (`docs/archived-documents/plans-archive/route-gating-compliance-plan.md`
  step 3), ⛔ landed only after every mutating route is a capability or a categorized exemption — a ratchet
  over an unreviewed list freezes the wrong baseline. ⚠ The audit's "sibling is gated ⇒ this one was
  forgotten" inference failed **six** times; read the handler, the comment and the tests, every time.
- 🔴 **THREE WAYS A BUILD VERDICT LIED IN ONE SHIFT** (2026-09-15) — all three produced confident,
  specific, wrong answers:
  1. **A stale `build.log` in a reused scratchpad path.** A delegated verification reported two failures
     verbatim from an earlier run, both already fixed. ⛔ Tie any delegated verdict to a fresh run: ask for
     the summed per-module lines and check them against the known baseline.
  2. **`-pl <module>` without `-am`** — four tests erroring with
     `java.io.IOException: HTTP/1.1 header parser received no bytes`, which is **a server that never
     booted** against a stale sibling jar, not the defect it looked like.
  3. **`-Dtest='A+B'`** is not valid syntax: it runs **nothing** and reports `BUILD SUCCESS`.
     Use commas. (Same family as the no-op-build-reports-exit-0 note below.)

- 🔴 **A PER-SPACE FACT READ FROM A JVM-WIDE PROPERTY IS INVISIBLE TO THE WHOLE TEST SUITE.**
  Until 2026-09-15, nine engine call sites read `System.getProperty("assist.write.root")` for the component
  registry while their sibling `dataDir` was per-Space and every control-plane route resolved
  `currentContext().root().config()`. In a multi-Space deployment the two crossed: a run read **one Space's
  registry while writing another Space's data**, failing with `unknown dataset '<an id the route had just
  resolved>'` — *after* a 202 had promised a run. ⛔ **Single-Space deployments make the two paths the same
  directory** — Personal, and every test — so the defect was a no-op everywhere it was ever exercised, and
  only a live multi-Space run exposed it. ⇒ resolve through `com.gamma.pipeline.SpaceConfigRoot`
  (`current()` keys on the space MDC, which both `JobService` submit paths set on the worker thread;
  `forSpace(id)` for a caller holding an id). ⚠ **Move the registry root and the DATA root TOGETHER** — a
  per-Space registry beside a JVM-wide `-Ddata.dir` *is* the defect, so half a migration is worse than none.
  ⚠ Their precedence is deliberately **opposite** and must not be harmonised: `-Ddata.dir` **overrides** a
  Space's data dir (`CollectorService`'s rule), while the config root lets the **Space win** and falls back
  to the property for the default Space only (`ControlApi.writeRoot()`'s rule). A test pins the asymmetry.

- 🔴 **CHECK THAT A GUARD'S COMPARANDS CAN EVER BE EQUAL BEFORE WRITING IT.** A proposed one-line self-loop
  guard for the `on: dataset` trigger — compare the signal's `producer` to the pipeline name — **could not
  fire**: `producer` is `cfg.name()` (a JOB name) at `MaterializeTask:132` and `chain.get(i)` (a **processor
  component id**) at `ConsignmentProcessJobType:412`, never a pipeline name. It would have looked correct,
  passed review, and done nothing on the exact case it targeted. ⚠ Same family as *a guard I wrote could not
  fire* (2026-09-13) — the tell is a comparison between two values that were never defined as the same kind.

- ⚠ **COUNT THE REACTOR BY SUMMING SUREFIRE REPORTS, NOT BY PARSING THE LOG** — `**/target/surefire-reports/
  *.txt`, one file per class, is authoritative and sidesteps the `[WARNING]`-level-module trap entirely. Use
  `**`, not `*`: the five `asn-*` modules are nested under `asn-parser/asn-decoders/`, so a single-level glob
  reports **21 modules / 4429** — a plausible-looking total rather than an obviously broken one (true: 26 /
  4508). ⚠ And when a total does not reconcile, **suspect the BASELINE'S PROVENANCE first**: an "unexplained
  +2" on 2026-09-15 was entirely a briefing error — the baseline had been measured when a test class held 9
  tests and the reconciliation was stated against the 11 it held later. ⇒ record *when* a baseline was taken
  and *what the changed classes held then*, not just the number.

- ⚠ **A ZONE WITH NO DST CANNOT TEST DST.** Every `CronExpression` test ran in **UTC** and the one
  cross-midnight case in **Asia/Kolkata** — neither shifts — so the one behaviour a civil-zone cron exists to
  get right was unverified for as long as the feature existed. The mechanism turned out correct; the gap was
  the fixture's constants. ⇒ check what a fixture actually *exercises*, not that it exists. Pinned
  2026-09-15 in `CronExpressionTest`: spring-forward **skips** the hour that does not exist (a daily 02:30
  job does not run on 2026-03-08 — a real operational consequence), autumn-back fires **once, not twice**.

- ⚠ **A CAPABILITY-GATED ROUTE NEEDS A `CapabilityManifest` ENTRY TOO**, and the guard that says so fails in
  a **different module** (`inspecto-processor`), nowhere near the route. Because the reactor is fail-fast,
  that one failure left **13 modules SKIPPED — unverified, not passing**, including both edition modules the
  `-Pedition-enterprise` profile exists to reach. ⛔ `CapabilityManifestTest` compares the manifest against
  the `withCapability` sites **bidirectionally** — a route in NEITHER is a third case it cannot see, which is
  how 83 ungated mutating routes accumulated (`ROUTE-UNGATED-DEFAULT-1`).

- 🔴 **A RED GATE HIDES EVERY GATE BEHIND IT.** CI stops at the first failing step, so a single red guard
  makes every later one unobservable. Measured 2026-09-14: `master` was carrying **four independent
  pre-existing failures stacked in one file** — a stale generated table, then five broken doc links, then
  two dead citations, then a genuinely host-dependent Maven test. Each became visible only when the one
  ahead of it went green, and the last two had been invisible for as long as the first was red.
  ⛔ **After fixing a CI gate, re-run rather than declaring victory** — "the build is green now" is a
  claim about one step until the whole pipeline has executed. ⚠ The same shape hides in any ordered
  pipeline: the pre-push hook, `package.ps1`'s staging steps, a reactor that stops at the first module.

- 🔴 **A RED GATE CAN ALSO BE A CONCURRENT BUILD — this checkout is SHARED.** Measured 2026-09-14: a full
  reactor run died in `inspecto-engine` with `NoClassDefFoundError` for `com.gamma.util.CurrentSpace` and
  `SingleConnectionSource` — classes that compile fine, declared at **compile scope**, in a module that had
  reported SUCCESS seconds earlier. Cause: another session ran `mvn clean` in the **same working tree**,
  deleting `inspecto-util/target/classes` mid-run. ⛔ **Before debugging a classpath error, `ls -la` the
  missing `.class` and compare its mtime to your run** — a file re-created *during* your build is the tell.
  Then simply re-run: it did not reproduce, and the suite passed 4475/0. ⚠ The same race can corrupt any
  measurement taken here; a lone anomalous failure deserves one re-run before it earns a bug report.

- 🔴 **STAGING IS NOT SAFE PARKING — a peer session's stop-hook will commit and push your staged tree.**
  Twice now (latest 2026-09-14, `519673a7`): 38 staged files of one shift's refactor landed inside another
  session's commit, under a message describing something else entirely, credited to another model, and were
  pushed before they could be verified. ⛔ **Commit as soon as a coherent unit exists** rather than holding
  a large `git add -A` while you verify — and when a shared-trunk commit turns out to misdescribe its own
  contents, **record the facts forward** (a follow-up doc commit) rather than rewriting pushed history.
  ⚠ Corollary: a dirty file you did not create belongs to someone else — never sweep it into your commit.

- 🔴 **`existsSync` answers "is this on THIS disk", never "is this in the repository".** It says yes to a
  gitignored file, to build output, and — on a case-insensitive filesystem — to the wrong capitalisation.
  Both halves shipped (`LINKGUARD-CASE-1`, 2026-09-14): five docs linked `docs/okf/INDEX.md`, which does not exist
  (the tracked file is `index.md`), and two instruction files cited `.claude/sessions/snapshot.md`, which does not exist
  in a checkout either — a hook writes it into every working tree instead. Every local run was green and
  the runner was red. The seam is now `tools/tracked-paths.mjs` — `git ls-files`, case-exact, with implied
  directories. ⛔ Never fold case "to be safe" and never add an `existsSync` fallback: either restores the
  bug while staying green on Linux, where nothing would notice.
  ⇒ **Verify docs against a CLEAN EXPORT, not the working tree:** `git archive` HEAD into a temp dir, copy
  the uncommitted files over it, **`git init` + commit there** (the guards call git, and a guard that
  cannot run is not a pass), then run them from that directory.

- 🔴 **STAGED is not LOADABLE.** Shipping a file next to the jar only helps if something explicitly loads
  it from there. DuckDB's **autoload** resolves against its own `extension_directory` and ignores
  `-Dduckdb.extension.dir`, which only `DuckDbExtension` reads. Measured four ways 2026-09-14 against
  duckdb_jdbc 1.5.2.1: autoload over an empty directory fails (correct), **over the flat staged directory
  also fails**, over a `<version>/<platform>` tree works, and an explicit `LOAD '<file>'` works. So
  `postgres_scanner` was staged in the morning and still could not load in the afternoon — an air-gapped
  attach reached for a network `INSTALL` anyway. ⛔ **An extension that arrives by AUTOLOAD needs a named
  call site, or its staged file is dead weight** — and there is no `LOAD` in the source to grep for, which
  is exactly what makes it invisible. ⚠ No test caught it because every developer machine already carries
  the extension in `~/.duckdb/extensions`, where autoload does find it.

- ⚠ **A test that asserts a RESOURCE QUANTITY is host-dependent, and CI is the small host.**
  `ConfigSafetyValidator` bounds `processing.threads` by `availableProcessors()` — 12 on this sandbox, 4
  on a GitHub runner — so a test writing `threads: 7` was a 200 here and a 422 there for as long as it
  existed. ✅ **Reproduce with `JDK_JAVA_OPTIONS="-XX:ActiveProcessorCount=4"`, which reaches the forked
  test JVM.** ⛔ The older recipe here — `MAVEN_OPTS` plus `-DforkCount=0` — **hung the reactor** inside
  `ControlApiPreferencesTest`. The two variables were separated on 2026-09-14 and the verdict is that
  **`forkCount=0` was the cause and the core count was innocent**: forked at 4 cores the full enterprise
  reactor completes at **4475/0/0/24**, identical to 12 cores, and `ControlApiPreferencesTest` itself
  passes in 0.115 s (`CORECOUNT-SWEEP-1`).
  ⚠ **That result masks the core count ONLY** — the box kept 32 GB. Behaviour on a 4-core host with
  proportionally less RAM is still not established, and is deliberately not carried as an open row.

- ⚠ **A mutation anchor that is not unique proves nothing, and reports a hole that does not exist.** Twice
  on 2026-09-14 a falsification run flagged the guard as blind when the mutation had simply landed
  somewhere else — one anchor matched `run.bat` instead of `serve.bat` (the two carry identical lines),
  another was absent from the file entirely. ⛔ **Assert the anchor's occurrence count before believing
  either a red or a green**; a probe that cannot report "I did not apply" is not a probe.

- ⚠ **Run the guard sweep AFTER `git add`, not before.** `check-secrets.mjs` scans TRACKED files, so a
  brand-new script is exempt from it while untracked — the whole sweep comes back green on a file one
  `git add` away from failing. Worse, its pre-push pass scans the **push RANGE**, so a later fix commit
  does not clear a value introduced earlier in the same push; it takes a range rewrite.
  🔴 **This is not one guard's quirk — it is how the guards resolve, and it bit twice on 2026-09-14
  with a single new doc.** `check-vocabulary.mjs` and `check-doc-links.mjs` also work from
  `git ls-files`, so on a NEW file the pre-commit sweep is **doubly misleading**: the new file's own
  content is not scanned at all (vocabulary reported 218 docs clean; the same file failed on
  `[source-acquisition-entity]` the moment it was committed), while a link *to* it from a tracked file
  is reported BROKEN precisely because it is not yet tracked. ⇒ **a green sweep over an untracked file
  proves nothing, and a red link-guard pointing at your new file may just mean "not added yet".** Add,
  then sweep, then commit — and read a failure on a new path as a tracking question first.

- 🔴 **"Fails closed on failure" does not cover "succeeds at the wrong thing."** A `catalog_url` with no
  recognised backend prefix does not make DuckLake fail — it reads the value as a **file path** and
  silently creates a private local catalog (measured 2026-09-14: a 1.8 MB DuckDB file in the repo root,
  named after the whole connection string, **password included**). `D10` already made an *unreachable*
  catalog fatal; this one is reached, successfully, privately, with every batch green. ⛔ When a guard
  exists to stop divergence, ask what the WRONG-BUT-SUCCESSFUL path looks like, not just the failing one.
  Now refused by `LakehouseCatalog.requireShared` when partitioned.

- 🔴 **`Paths.get` DISAGREES ACROSS PLATFORMS about a URI, and it fails closed on the box you probe from
  and fails silent on the box you ship to.** Measured 2026-09-14 with `s3://bucket/data`: **Windows**
  throws `InvalidPathException: Illegal char <:> at index 2`; **Linux** (JDK 26, the shipped
  `linux_amd64` target) does **not** throw and returns `/s3:/bucket/data` — a real local directory
  literally named `s3:`, resolved against the working directory. So `dirs.database: s3://…` used to be
  refused here and **silently written to local disk in production**, with `PathJail.contains` giving a
  confident wrong answer about it. ⛔ **Never conclude "that value is rejected" from a Windows probe of
  path code.** Same family as the `catalog_url` note above: the failure mode is success at the wrong
  thing. Now one predicate, `PathJail.isUri`, refused by both the enforcing jail and the 422 write gate;
  ⚠ when object-store paths land (scale-out §5.4 bullets 1 and 6) **dispatch on it, do not delete it** —
  a bucket URI is not containable by `Path` comparison and needs its own rule.
- 🔴 **A prefix match is not a spelling check.** That same guard first accepted `postgres://…` because it
  *starts with* `postgres:` — endorsing the exact spelling its own error message told operators to avoid,
  and which had already been measured failing to attach. ⛔ **When a rule's message enumerates bad values,
  assert every one of them against the rule** (2026-09-14).
- 🔴 **Grep the SYMBOL, never the file list a previous pass wrote down.** `DOC-DEADTOKEN-1` named three
  files carrying a dead `-Dcontrol.token`; a sweep for the symbol found **twelve**, plus a *second* dead
  flag (`-Dassist.read.token`) the row never mentioned, plus a live instance in a file the row recorded as
  already fixed — one that **ships inside the bundle**. ⚠ A cleanup that states its own scope narrowly
  makes everything outside it read as though it had been checked (2026-09-14).
- 🔴 **Before adding a call to a shared helper, grep every CONSTRUCTION site of that helper.**
  `PartitionSinkWriter` holds a sink's store and outputs together and looked like the obvious place to
  register DuckLake output — but it serves **two** lanes, and the other already registers through a shared
  tail, so the "tidy" placement would have **double-registered** it (2026-09-14).
- ⛔ **A `#` comment between PowerShell BACKTICK CONTINUATIONS breaks the parse**, and looks correct in a
  diff. Put such notes ABOVE the call, and parse-check an edited script rather than eyeballing it:
  `[System.Management.Automation.Language.Parser]::ParseFile(path,[ref]$tokens,[ref]$errors)` (2026-09-14).
- ⛔ **Confirm another repository's DEFAULT BRANCH before pushing to it.** `jotder/inspect-agent`'s is
  `main`, not `master`; a `HEAD:master` push silently created a stray branch instead of landing, and the
  only signal was the words `* [new branch]` in the push output.
  `gh repo view <owner>/<repo> --json defaultBranchRef -q .defaultBranchRef.name` (2026-09-14).
- ⚠ **Best-effort staging cannot tell "not wanted" from "not present."** `package.ps1` stages DuckDB
  extensions from a local cache and only WARNS when it finds none — so with no cache on the runner, every
  released bundle shipped an empty `duckdb-extensions/` and `AIRGAP-EXTENSIONS-1` was never actually
  delivered by a release. ⛔ A release path that can silently produce less than intended needs a
  fail-closed switch (`-RequireExtensions`), and **a step that runs only on a tag needs a cheap variant
  that runs on every push** — "a path that executes only at release time" is itself the defect shape
  (2026-09-14, `AIRGAP-EXTENSIONS-CI-1`).
- 🔴 **`mvn compile` does NOT rebuild a downstream module when you change a SUPERTYPE's API.** Maven's
  incremental compiler looks at each module's own sources, so removing a method from an interface in
  `inspecto-util` produced an all-green `BUILD SUCCESS` in **4.5 seconds**, every module at `0.0x s`,
  while fifteen callers were still broken. ⛔ Use `clean` for any cross-module signature change, and treat
  a suspiciously fast green build as a no-op until proven otherwise (2026-09-14, `OPS-03`).
- ⚠ **`tee` masks the exit code of the command feeding it.** A `package.ps1` run that threw was reported
  as `exit 0` because the wrapper read `tee`'s status. Read the output for the exception; the code is the
  pipe's, not the program's (2026-09-14).
- ⚠ **Operational stores no longer serialise on their own monitor when backed by Postgres.** Since
  `OPS-03` (2026-09-14) each `Db*Store` borrows a connection per operation, so two operations on one
  store can interleave on a pooled source; DuckDB is unchanged. ⛔ New read-then-write code needs ONE
  borrow **and** a DB predicate (`ON CONFLICT`, a fenced `WHERE`) — `synchronized` will not save it.
  Full account, including the two audited exceptions: `okf/backend/engine/db-layer.md` §2.

- 🔴 **An SPI whose absence removes a safety property must be fail-CLOSED, and two were not.**
  `SpiSlot` routes discovery through `OptionalSpi`, which catches `ServiceConfigurationError` so an
  unloadable **optional** module is an absence rather than a boot failure (PKG-5 — right for the assistant
  sidecar). ⛔ For `Authenticator` and `AccessDecider` absence means *allow*: an empty Authenticator makes
  `ControlApi.dispatch` skip auth on **every** route, and an empty AccessDecider makes both PEPs return
  early, so `PolicyEngine`'s seeded `space-isolation` stops enforcing the **multi-tenant boundary**. Both
  slots now pass `failClosed=true` (2026-09-13). ⚠ The distinction that has to survive any future edit is
  **registered-but-broken vs never-registered** — Personal registers nothing and must still resolve empty.
  ⚠ A new `ATTR_*` constant must also be added to `ControlApi.REQUEST_SCOPED_ATTRS` or it **leaks across
  requests**; `ExchangeAttributeScopeTest` is the guard that catches it.

- 🔴 **Only a REAL provider finds provider-shaped defects.** Standing up WSO2 IS 7.3.0 immediately exposed
  that `OidcAuthenticator` rejected **every RFC 9068 access token** (`typ: at+jwt`; Nimbus allows only
  `JWT`/absent) — and recent Keycloak stamps the same type, so it was never one vendor's quirk. ⚠ The
  offline suite could not have caught it: it mints headers with **no `typ` at all**, which was always
  allowed. Getting a role through needs **three** provider settings (group named for a seeded role · the
  app *requests* the claim · the app lists it in `accessTokenAttributes`), and missing any one gives the
  identical symptom — a valid token whose Subject has **zero capabilities**. ⛔ On WSO2 the claim is
  `groups`; neither the `roles` default nor Keycloak's `realm_access.roles` applies.

- 🔴 **A row DERIVED from a decision inherits confidence without evidence.** `UI-POD-SCOPE-UNION-1` was
  filed as a consequence of a decision rather than from code; grounding it found **both** its remedies
  impossible — a signed plan (`enterprise-scale-out-plan.md` §5.5) already refused client-side routing
  outright, and no pod identity is serialized anywhere — and its premise false (`/bootstrap` does **not**
  feed the space-switcher; `session.service.ts` never reads `spaces`). ⚠ Worse, that unchecked claim had
  been copied into a shipped code comment, where it reads as verified fact. ⛔ **Never restate a row's
  claim in a comment without checking it — a comment outlives the row.**

- 🔴 **Local dev services live in [`dev-infra/`](../dev-infra/README.md) — and two test suites SKIP
  without them, which is not a pass.** `docker compose -f dev-infra/docker-compose.yml up -d` brings up
  PostgreSQL (for `PostgresStateStoreTest`, 15 tests) and WSO2 Identity Server (for
  `OidcAgainstRealProviderTest`, 2). Every command needed to arm them is in that README because none is
  guessable: the Postgres URL must arrive via **env var** (Windows `mvn.cmd` re-parses args and drops the
  password after the `&`), PG 18 rejects the legacy `Asia/Calcutta` zone a Windows JVM sends (fix with
  `Asia/Kolkata`, **never `UTC`** — that would move `record_day` boundaries), and WSO2 must be registered
  with `ext_token_type: JWT` or it issues an opaque token and every request 401s silently.

- 🔴 **`-DargLine` does NOT reach the forked surefire JVM.** The parent POM is
  `<argLine>@{argLine} …</argLine>`, and that late-bound property resolves the **project** property — a
  command-line `-D` does not override it. `systemPropertyVariables` is also too late for anything the JVM
  fixes at startup (the default TimeZone). ✅ **Use `JDK_JAVA_OPTIONS` — it reaches the FORK, so nothing
  has to be unforked** (confirmed 2026-09-14 with `-X`: the flag is absent from the forked command line
  under `-DargLine`, and under `JDK_JAVA_OPTIONS` the JVM prints `NOTE: Picked up JDK_JAVA_OPTIONS`
  **twice** — Maven's JVM and the fork — with `availableProcessors()` reading 4 inside). ⛔ The route this
  note used to recommend, **`-DforkCount=0` plus `MAVEN_OPTS`, is the one that HUNG the reactor**; it was
  never the core count. See `CORECOUNT-SWEEP-1`, settled 2026-09-14: forked at 4 cores the full reactor
  passes **4475/0/0/24**, identical to 12 cores.
  ⚠ Related: `-Dtest='A+B'` silently runs **nothing** under `failIfNoSpecifiedTests=false` (exit 0, empty
  log) — the separator is a **comma**.

- 🔴 **A skipping test can hide a broken harness, not just absent coverage.** `PostgresStateStoreTest`
  skipped cleanly from 2026-09-07 while `inspecto-ops` had **no JDBC driver at all** — `inspecto/` and
  `inspecto-engine/` declare it test-scope and **test scope is not transitive**. When a skip's
  precondition finally arrives, treat the code as untested rather than as coverage that merely paused.

- 🔴 **A doc rewrite silently retracts the claims other rows depend on.** Twice on 2026-09-12/13: the
  whitepaper's one-to-many sentence was deleted by the v1.2 rewrite a day *before* an operator chose to
  "build rather than drop" it, and `auth-security.md`'s *"`SpiSlot.active()` has no try/catch"* was true
  when written and falsified by PKG-5 routing that call through `OptionalSpi` — which is what let a
  misconfigured Standard build boot **wide open**. ✅ **When a stakeholder or concept doc is rewritten,
  re-ground every row that cites it**; `git log -S "<exact phrase>" -- <doc>` settles it in one command.

- 🔴 **A raw `jdbc:` value in a `*.backend` property is a URL, not a keyword — never lowercase it.**
  Six `ServiceStores` openers read `System.getProperty(…).trim().toLowerCase()` and then passed that same
  string on as the connection URL, so `-Djobs.backend=jdbc:postgresql://db/MyDb?user=Alice&password=Secret`
  silently connected as `mydb`/`alice`/`secret` — Postgres database names, roles **and** passwords are all
  case-sensitive — and on a case-sensitive filesystem `jdbc:duckdb:/srv/Inspecto/x.duckdb` opened a
  different file. Fixed 2026-09-11. ✅ **The rule: compare on a lowercased COPY, pass on the raw value** —
  which `OperationalDb.resolve` already did, twenty lines away. ⚠ The two openers that never lowercased
  (`status`, `events`) were the only correct ones, so "the majority does X" was the wrong signal here.
- 🔴 **A build-running subagent may return BEFORE the build finishes, and its "standing by" reads exactly
  like a result.** Happened twice on 2026-09-11, once after 11 minutes. ⇒ **Block on the terminal marker
  yourself** — `until grep -qE "BUILD SUCCESS|BUILD FAILURE" <log>; do sleep 15; done` — and confirm the
  log's mtime has stopped advancing before trusting it. ⚠ A partial Maven log is indistinguishable from a
  passing run with fewer tests: the first red run here stopped at `inspecto-processor` and **13 modules
  never executed at all**, including the one carrying a third of the change.
- 🔴 **Re-sum the reactor's module lines yourself; do not relay a subagent's arithmetic.** One reported a
  phantom "−5 test drift" by treating a handed-over baseline as *passed* when it was *Tests run*. The real
  delta was exactly the tests added. ⚠ Handoff tallies in this repo are written `run / fail / err / skip` —
  the first number is **Tests run, including skips**, and reading it as "passed" manufactures a regression.

- 🔴 **cmd.exe expands `%VAR%` inside a parenthesised block ONCE, at PARSE time — so N
  `set "X=%X% …"` lines in one `if ( … )` collapse to the LAST one executed.** Measured 2026-09-11:
  `set "OPTS=BASE"` then a block doing `-Dfirst` and `-Dsecond` yields `BASE -Dsecond`; `-Dfirst` is gone.
  This shipped in the generated `serve.bat` (`SERVEBAT-OPTS-1`): six such lines in the
  `if exist inspecto-security.jar (…)` branch meant **every Windows Standard/Enterprise bundle booted
  AUTH-FREE** — `-Dauth.mode=oidc` and three of the four OIDC flags dropped — while printing
  `edition: Enterprise`. ✅ Fix = **one statement per line**, chaining conditions
  (`if exist X if not "%Y%"=="" set …`), the form the adjacent Postgres lines already used AND documented.
  ⛔ **Not `setlocal EnableDelayedExpansion`**: these values carry client secrets and keystore passwords,
  and delayed expansion eats `!` inside them. ⚠ `serve.sh` has no such hazard — bash expands at execution.
- 🔴 **Verify a GENERATED launcher by RUNNING it, never by reading the generator — and run the OLD version
  too.** Nothing in CI executes an emitted `serve.sh`/`serve.bat` (`LAUNCHER-GUARD-1`), which is precisely
  why the bug above survived. The technique: extract the `$serve*Content` here-string from
  `inspecto/package.ps1`, write it out, stub the jars, replace the launch line with an `echo`, run it under
  `cmd.exe`/`bash`. Running only the NEW version shows it works; running HEAD's too is what shows the bug
  was real. (`SCR-9` used the same extract-the-here-string technique for its own acceptance.)
- ⚠ **A verification method can manufacture failures.**
  `[System.Management.Automation.Language.Parser]::ParseFile` reads a file as **ANSI**, and
  `inspecto/package.ps1` is UTF-8 with box-drawing characters — so it reports **40+ phantom syntax errors
  on an UNMODIFIED file**. Use `ParseInput` with an explicit UTF-8 read. ⇒ Before believing a gate has gone
  red on your change, run it on `git show HEAD:<file>`; a red on untouched content is a broken probe.

- ⚠ **`npm run format:check` (prettier) is DECLARED BUT NOT GATED** — it is in `inspecto-ui/package.json`
  and in neither `.github/workflows/ci.yml` nor `.githooks/pre-push`. Measured 2026-09-11: it was red on
  **five** files from an already-pushed commit and nothing anywhere failed, so the drift was invisible at
  commit time. ⇒ Run it in `inspecto-ui` before committing UI work. Prettier only re-wraps here (long
  signatures split, arrays exploded with a trailing comma), so `git diff -w` shows no semantic change and
  the fix is safe to take in one pass. ⚠ It is also a reminder that **a green build does not mean a clean
  tree**: this repo has more declared checks than gated ones.
- 🔴 **`tools/rename-batch-to-consignment.mjs` is a MUTATING CODEMOD sitting among the guards, and it
  APPLIES BY DEFAULT** (`--dry-run` is the opt-in safe mode). Run 2026-09-10 in a "run all the guards" sweep,
  it silently renamed `Batch`→`Consignment` inside an unrelated test and **rewrote the file with CRLF**, then
  **exited 0** — indistinguishable from a guard passing. ⚠ **It is NOT in the pre-push set.** The sweep
  included it because `grep -oE 'tools/[a-z0-9-]+\.mjs' .githooks/pre-push` matched **its name inside a
  COMMENT** ("Its old-name set is PARSED from …"), not an invocation. ⇒ The pre-push guard set is the **seven**
  `check-*.mjs`; derive it from the `run_guard` lines, never from a name-grep of the whole hook, and never
  assume a `tools/*.mjs` is read-only because it lives beside the guards.
- 🔴 **AN OPEN IDE SILENTLY CORRUPTS YOUR REACTOR RUN — 36 phantom failures, measured 2026-09-10.** A full
  `mvn -o clean test` failed with **36 errors in `inspecto-engine`**: `NoClassDefFoundError` on *production*
  classes (`com.gamma.pipeline.MappingRules`) and `javac` unable to compile the scaffold templates. Nothing
  in the change under test touched any of it, and **a re-run of the same module minutes later was
  2487/0/0/4 BUILD SUCCESS with zero edits in between.** ⚠ **The tell: the "missing" class file is present
  in `target/classes` AFTER the run.** The cause is an IDE background build writing the same output tree —
  `Get-Process java` showed **IntelliJ's Maven embedder** (`-Didea.maven.embedder.version`) and a **Redhat
  JDT language server**, both live on this checkout; IntelliJ compiles a Maven project straight into
  `target/classes`, so it races surefire. ⛔ Do not report a reactor failure that implicates code you did
  not touch until you have listed the live JVMs and **re-run the failing module**. A red module in this
  checkout is a hypothesis, not a result.
- 🔴 **Uncommitted work here is volatile — a peer can DISCARD it, and `git status` then reads exactly like
  "you did nothing".** Measured 2026-09-10: a peer staged this shift's in-progress tree, aborted the commit
  on an empty message, then reset/cleaned it. **HEAD never moved, so there was no reflog entry and no
  stash**, and four new files were simply gone. ✅ It was fully recoverable because **`git add` writes each
  staged file into the object database as a loose blob that an aborted commit leaves behind**: select loose
  objects by mtime (`find .git/objects -type f -newermt '<HH:MM>' | grep -v /pack/`) and `git cat-file -p`
  them out. ⚠ **`git fsck --lost-found` listed none of the eight** — mtime selection is what worked, and
  `.git/COMMIT_EDITMSG` still named the peer's staged file set, which is how the staging was known at all.
  ⛔ Only *staged* content is recoverable this way. ⇒ Snapshot to the scratchpad (`git diff > …patch` plus
  the untracked files) at every milestone; a `git clean` cannot reach it.
- 🔴 **Four reactor modules log their `Tests run:` SUMMARY at `[WARNING]`, not `[INFO]`** — Maven does that
  whenever a module has any skipped test. So a verify run that greps `^\[INFO\] Tests run:` finds **22
  modules and sums to ~3443** against a real **26 / 4199**, and the shortfall reads like a regression rather
  than a log-parsing bug, sending the reader hunting a build break that does not exist. ⚠ It has a **mirror
  image**: told to count WARNING lines as well, a reader then double-counts. ⇒ Match **both** levels, count
  only lines WITHOUT `-- in` (those are per-class, not per-module), and **assert the module count** — 26
  today — before trusting the total. Re-confirmed 2026-09-10, when a verify agent caught itself mid-report.

- 🔴 **`| grep -v spec` to "skip the tests" silently discards 77 % OF THIS REPOSITORY**, because the product
  name **`inspecto` contains the substring `spec`** (i-n-**s-p-e-c**-t-o). Measured 2026-09-10: **3,015 of
  3,923** tracked files have `inspecto` in their path, and **every one** is dropped by that filter — the whole
  client and every Java module. What survives is `docs/`, `asn-parser/`, `compliance/` and `.github/`, so the
  sweep looks like it ran and reports a plausible, tiny answer. ⇒ Exclude test files by a **path-anchored**
  pattern (`grep -v '\.spec\.ts$'`, `grep -v '/src/test/'`) or with ripgrep's `--glob '!*.spec.ts'`, never by
  the bare word. ⚠ This one produced a confident "no `EventSource` anywhere in the client" while the client
  had seven occurrences of it, and it is a *worse* trap than the NUL-byte one because nothing about the output
  looks truncated.

- 🔴 **A written-down finding records the INSTANCE, not the class — so its number is a lower bound.** Every
  one of Sprint 7's seven cells (2026-09-10) was scoped from a board row, and **every row undercounted, in
  the same direction**: "~16 authority citations" was 19 documents plus 5 source files · "twenty documents"
  was 27 · "five undefined words" was five, but three of them had **more senses than the row claimed** ·
  "five dead classes" was four, plus a worse third defect the row never mentioned · "five orphan documents"
  was five, but four contained an item that was **refuted or already shipped**. Whoever writes a finding sees
  one example and writes that down. ⇒ **Plan the sweep, not the row**: re-derive the set from the repo before
  estimating, and expect the true count to be larger and the shape different.
- ⚠ **A row goes stale in BOTH directions, because nobody re-reads it when they fix the thing it
  describes.** Twice in the same sweep a row still demanded work that had been done: one pointer had been
  resolved the previous day, and one "two documents tell a contributor to edit a file that does not exist"
  clause had been repaired two days earlier — while two *different* documents had the same defect, live. ⇒
  Re-ground a row against the repo before working it **and** before believing it is still open.
- ⛔ **A false ✅ is worse than a blank, because nobody re-checks a tick.** Two plans recorded a migration
  command as existing; it was in no source file, and a release gate's precondition rested on it. When a step
  is marked done, the evidence goes on the same line — and when what is green is *narrower* than what the
  step asks (round-trip parity of a projection versus the suite executing through the new path), say which
  claim the evidence supports.

- 🔴 **A fixture-corpus parity gate proves nothing about a shape no fixture uses.** A round-trip gate ran
  green over every committed fixture while a whole authored spelling was unreadable — for **13 days** —
  because no fixture used that spelling. So a corpus gate's coverage is the corpus, not the format: when a
  new spelling is added, add a fixture that uses it in the same change. *(Distilled 2026-09-10 (Sprint 7.6) from the three archived plans; this was their only home.)*

- 🔴 **`grep -r` on this sandbox returns ZERO hits for strings `git grep` finds** (2026-09-10, three times in one shift:
  `source_timezone` in four UI files; two UI callers and a Java test of `/config/suggest/schema`). Each time the
  false negative was one step from filing a shipped surface as missing or a live route as an orphan. **Absence is
  concluded only from `git grep`**; `grep -r` may locate, never negate.
- 🔴 **A verify agent's reactor total is a claim, not a measurement.** Two reports in one shift under-summed
  (3430 for a 4178 run) by skipping modules; the third was right only because it was told how to sum. Re-sum the
  per-module `Tests run:` lines — the ones WITHOUT `-- in` — from the log before writing any number down.
- 🔴 **Python's default text read converts CRLF→LF, so a "restored" file shows `M` with an empty diff** (2026-09-10).
  Open with `newline=''`, match on the file's own EOL, and `git checkout --` a stat-only ghost. Related: the Bash tool
  collapses backslashes inside heredocs AND `python -c`, so a `
` in an `old` string matches nothing and a regex's
  `\b` reaches the file as `\b` — author scripts with the Write tool and assert `count == 1` on every replace; that
  assert is what caught every instance.
- 🔴 **Ground a decision before asking it, and read a free-text answer against the QUESTION.** Of the decisions
  "owed to the operator" on 2026-09-10, three dissolved on measurement (`CONTRACT-ORPHAN-1`'s premise was a `grep`
  false negative; `MAPPING-SPELLING-1` conflated a finished migration with an unstarted one; the register's `PATH-2`
  "refusal" was unbuilt work). And one answer to "lease mechanism" was a query-surface idea — recorded against the
  wrong question it would have become a signed decision nobody made; read against the question it became D13.

- 🔴 **A guard whose scope is mostly EXEMPTION is measuring the wrong thing — so measure the obvious
  design before building it.** Three of Sprint 3's five guard designs died on measurement, and each would
  have shipped green while proving little. The sharpest example: a guard that scans prose for a stated
  count matched **14 lines over 238 current docs**, and its "failures" were a line reference
  (`Roles.java:121-131`), a sentence about one specific node type, and correction notes naming the old
  figure on purpose — while **missing** real phrasings like "the 119-entry catalog". ⛔ **A number in
  prose is indistinguishable from a line number, a version or a date fragment.** The shippable shape is
  the invariant the repo states about *itself*: a contract owns the number, docs mark the statement
  (`<!--count:ID-->`), the guard DERIVES it (`tools/check-doc-counts.mjs`).
- ⛔ **Name a count — or any id — after the SET, never the noun.** "Node types" denotes **three** sets in
  this repo: 31 (`BuiltinNodeType`, the roster), 12 (`node-attributes.contract.json`, only types with an
  attribute spec) and 16 (`step-types.contract.json`, recipe entries). ⚠ The first two moved together on
  2026-09-15 when `transform.profile` landed — they are unguarded prose here, so nothing caught them. "Transform functions" denotes 23
  (SQL mapping) *or* 30 (ASN vendor plugin). The five-way and three-way count spreads were caused by the
  **ambiguity, not the arithmetic** — nobody had written down that the noun covered several sets.
- ⚠ **Some counts have no single true value, and a guard must refuse to assert one.** Job types are **10**
  on Personal and **12** with `inspecto-ops`; maintenance tasks are **21** built-in ids across **20**
  switch arms plus **4** contributed (`soft_bounce_retry` added 2026-09-15). Both assemble from a built-in list plus `ServiceLoader` discovery,
  so "the count" does not exist until the classpath is fixed. A doc stating either must say which shape it
  means. A guard measures; it must not decide.
- 🔴 **A mutation that does not COMPILE proves nothing about a test.** Two trigger-vocabulary mutations
  (removing an enum constant that is imported elsewhere; adding a component to a record) were caught by
  the compiler, so the tests they were meant to exercise never ran. ⚠ **The tell is the absence of a
  `Tests run:` line** — a bare non-zero exit looks identical to a working mutation test. Prefer mutations
  that compile: ADD an enum constant, or **swap two same-typed record components** (legal, and it changes
  the component list).
- ⚠ **Falsify with COMMITTED files only.** `git checkout --` reverts to HEAD, so restoring a mutated file
  destroys any *uncommitted* work in it. Doing this mid-falsification silently wiped four freshly-seeded
  count markers and put a wrong figure into a commit message and a CI comment.
- ⛔ **A ratchet floor must EQUAL the current count, not sit below it.** Floors set below the marked count
  left slack: deleting a marker left 2 of 3 and the guard went green. Slack in a floor is exactly where
  the thing being counted disappears unnoticed.
- ⚠ **Re-sum a Maven reactor by hand; a naive grep under-reports by hundreds.** `[INFO] Tests run:` alone
  gave **3422** against a true **4176**, because **four module summaries print at `[WARNING]` level** when
  `Skipped > 0`, and per-*class* lines share the prefix with per-*module* ones (summing both gave 7552).
  The module summary is the line with **no** `Time elapsed` and **no** `-- in <class>`, matched across
  `INFO|WARNING|ERROR`.
- ⚠ **Archiving a plan is not covered by any single guard.** `check-doc-citations` flags dead paths but
  exempts `docs/superpower/` **as a source**, so it is blind to one in-flight plan linking another;
  `check-doc-links` has no such exemption and catches those. **Java javadoc is outside both** — two
  production files cited an archived plan as their design authority. Run both, and grep the code too.

- 🔴 **This checkout can be worked by TWO shifts at once, and a shared tree breaks builds in ways that
  look like code defects.** Observed 2026-08-26: a second agent ran `mvn -o clean test` four minutes into
  another shift's identical run, and its `clean` wiped `target/` mid-flight ⇒ `NoClassDefFoundError` on a
  sibling module's class, whole real-HTTP test classes erroring at ~0.004 s each. **That shape means a
  wiped target, not a broken change.** The same session also saw the tree go red because the other shift
  had deleted a class (`Values`) that a third module still imported — a transient mid-refactor state, not
  a regression. And a `git push` answered *"Everything up-to-date"* because the other shift's push had
  already carried the commit up. **Before trusting any build or git verdict on this box: `git log
  --oneline -1` (your `HEAD` moves when the other agent commits) and check for an `mvn` process you do
  not own.** ⛔ Never run two Maven builds on one tree. The durable fix is a worktree per shift.
  ⚠ A process-poll loop that greps for `mvn.cmd` **matches its own command line** and never reaches
  zero — match the java process, or exclude the shell wrapper.
  ⚠ **And "no java processes" is never true on this box** — long-lived stale JVMs run for days
  (2026-08-27: one alive since 2026-08-25). An `until [ ... -eq 0 ]` waiter never terminates and a
  plain count reads "busy" forever. Filter on `StartTime -gt (Get-Date).AddMinutes(-N)`, or better,
  wait on the build log's own `BUILD SUCCESS|FAILURE` marker.
- 🔴 **`.claude/worktrees/` holds a FULL second checkout pinned to an OLD commit** (2026-08-27:
  `sweet-ritchie-072797` at `60b7a6b9`, 1,437 `.java` files). **Any repo-wide census that walks the
  tree double-counts and silently mixes stale sources in** — a fan-in measurement reported exactly 2×
  on every class before it was excluded. Exclude `.claude/`, `.git/` and `target/` in every tree walk;
  `git ls-files` / `git grep` are safe because they only see the index.
- ⚠ **A verify agent's build log is overwritten per run, and a no-op build looks like a pass.**
  Launching Maven via `cmd /c` from Git Bash can silently do nothing (MSYS mangles `/c`) — **exit 0
  and an empty log**, indistinguishable from success. Always check the log's **mtime** against your
  run's start time, and prefer `MSYS_NO_PATHCONV=1` / invoking `mvn.cmd` directly.
- **The repo guards are NOT in the local build loop — run them before every commit.** Neither the
  Maven reactor nor `ng test` runs `node tools/check-secrets.mjs` or `node tools/check-vocabulary.mjs`,
  so a violation is invisible until CI. On 2026-08-26 **`master` was found sitting RED** on the
  vocabulary guard, unnoticed for an unknown stretch, purely because nothing local ran it. Both are now
  wired into `.githooks/pre-push` (`aa038358`, `442e99d7`) and `core.hooksPath` is set automatically at
  Claude Code session start — but a clone used outside Claude Code still needs
  `git config core.hooksPath .githooks` by hand.
  ⚠ **The two guards sit on OPPOSITE sides of `UCC_RELEASE_GUARD_DISABLE`, deliberately:** secrets
  **above** it (a leaked credential is irreversible, and a hurried security push is exactly when you
  want the check), vocabulary **below** it (that override exists so a human can push an emergency
  security backport, which a banned synonym must not block). ⛔ Do not "tidy" them into one block.
  ⚠ **A THIRD guard joined them 2026-08-28: `node tools/check-dependencies.mjs`** (dependency review,
  compliance G7) — but it is **not** in the pre-push pair and does **not** belong there: it shells out
  to Maven, so it costs a reactor resolution rather than a second. It runs in `ci.yml` **after** the
  Maven build (it needs the eoiagent install), which is why the "two pure-Node guards run BEFORE the
  build" shape below no longer describes the whole set. Locally:
  `MVN_CMD=<mvn> MVN_OFFLINE=1 node tools/check-dependencies.mjs`; after a REVIEWED dependency change
  re-run with `--update` and commit `tools/dependencies.lock` in the same commit.

  ⚠ **A FOURTH joined 2026-09-08: `node tools/check-coverage.mjs`** (GUARD-SWEEP-1g), enforcing
  **repo-wide** coverage floors — backend instructions ≥78% / branches ≥64%, UI statements ≥70% /
  branches ≥66%, against measured baselines of 81.01%/67.19% and 73.82%/70.04%. Like the dependency
  guard it is **not** pre-push: it needs `mvn … -Pcoverage` (≈6 min) or a UI `test:coverage` run first,
  so it lives in `ci.yml` (`--backend`) and `ui.yml` (`--ui`). ⚠ Those scope flags are load-bearing —
  `ci.yml` never builds the UI and `ui.yml` never builds Java, so one "all inputs must exist" rule would
  fail whichever job legitimately lacks the other half; each named scope still fails loudly when ITS own
  input is missing. ⛔ Not `jacoco:check`: that goal binds per module, so it would gate `inspecto-util`
  (49.6%) and `asn-golden` (4.4%) on their own numbers instead of the one repo-wide floor.
  🔴 **The first baseline was wrong in a way that looked right** — "82.92% across 21 modules" while nine
  more modules were never instrumented, because a Maven profile is inherited through `<parent>` and
  never through aggregation, and `asn-parser/asn-decoders/pom.xml` is a separate root the reactor merely
  aggregates. A tenth of the codebase sat outside a "repo-wide" number while `mvn -Pcoverage` exited 0.

  ⚠ **`node tools/check-sbom-modules.mjs` joined 2026-09-09** (`f2eaeec8`). 🔴 **This sentence said it
  brought `ci.yml` to "six" pure-Node guards; it was wrong when written** — there were ten, and the number
  had simply not been recounted. Measured 2026-09-14 by listing the steps: **twelve** pure-Node guard steps
  run before the JDK is even set up, plus the two Maven-dependent ones above, plus the `launchers-windows`
  job. ⛔ Never increment a count in prose — list the steps and count them, which is the same rule
  `check-doc-counts.mjs` enforces on product counts and cannot enforce here. It
  holds the shipped bill of materials' first-party module set against `inspecto/package.ps1` — which
  enumerates the staged jars **three** times (`$modules`, the `Copy-Item` staging steps, the boot-smoke
  classpath) — and fails if any two disagree. It exists because the generator's table said four artifacts
  while the bundle carried ten or eleven, for a document that ships **inside** the signed, checksummed
  archive. ⛔ There is **no `node --test` anywhere in this repo**: a tooling check is a `tools/check-*.mjs`
  script that exits non-zero and is wired as a `ci.yml` step. Anything else runs nowhere.

- **A guard, hook or reminder that never reaches anyone looks identical to one with nothing to say.**
  Three instances, all 2026-08-26: the committed-secret guard ran only in CI, i.e. only *after* a push
  had already made the secret public; the `PreCompact` hook emitted an invalid shape
  (`hookSpecificOutput.additionalContext` is not valid for PreCompact) and had **never once fired**,
  failing silently every compaction; and `route:` arming validated only at registration, so a save
  returned `written: true` and the operator learned at the next run. ⛔ When wiring any check, ask **when
  it fires relative to the harm** — and for a reminder, watch it fire once. Its failure text appears only
  at the moment it runs.

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
  root config sets no `types`, so every `@types/*` is ambient; only `tsconfig.spec.json` names
  `vitest/globals`, and the root config does not extend it. The `@types/jasmine` that used to leak a
  global `expect` in there was **removed as an orphan on 2026-09-08** (`89f09b6c`, with `jasmine-core`
  and the five karma packages), so the root config now supplies no test globals at all — the gate is
  unchanged, it just fails sooner. The fix is importing `{ describe, expect, it }` from `vitest` in the
  spec (all 334 specs now do) —
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
  ⚠ **The same trap fires on a class name that simply DOES NOT EXIST** (2026-09-13): `-Dtest=` named
  `RemoteAcquisitionHandlerTest`, which is not a class in this tree — the real one is
  `RemoteAcquisitionStagingTest` — and because a SIBLING name in the same list did match, surefire ran the
  sibling and swallowed the miss entirely. BUILD SUCCESS, no "Running…" line for the absent class, and the
  totals looked plausible. 🔴 **Confirm each class BY NAME in `target/surefire-reports/TEST-<fqcn>.xml`**;
  arithmetic on a total ("16 presumably includes the 4 new ones") is not confirmation.

- **🔴 A heredoc collapses `\b` and `\n` into control characters — it can silently break a REGEX.**
  (2026-09-13 — **three times in one shift: the third was this very note**, whose escapes were eaten
  as it was being written.) Writing a JS rule through `python - <<'PY'` produced
  `re: /^Hsource\.…/` where `^H` is a literal **BACKSPACE byte**, not the word-boundary `\b`. The guard
  loaded, ran, and matched nothing — reading as a clean pass. The second instance put a real newline inside
  a JS string literal and broke the module outright (that one at least crashed). ⛔ Build such literals from
  `chr(92)+'b'`, and **`cat -A` the line** to see what actually landed. ⚠ The general rule this is an
  instance of: **a guard that passes proves nothing until it is proven RED** — mutate the thing it guards
  and watch it fire, or you have written a decoration.

- **⚠ `cmd | head; echo $?` reports the PIPE's status, not the command's.** A crashed Node guard printed
  its stack through `head` and still showed `EXIT=0` (2026-09-13). Redirect to a file and test the command's
  own exit code when the exit code is the thing you are asserting.

- **⚠ `$!` in bash is the SHELL JOB, not the JVM it launched.** `nohup java … & echo $!` then
  `kill $PID` left `ControlApi` serving on :8080 while the wait loop hung (2026-09-13). Take the pid from
  `jps -l | grep ControlApi`.

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
  `NotificationService.discoverChannels` find a channel whose `javax.mail` dep is not there, and boot dies
  with `NoClassDefFoundError: javax/mail/Message`. ⚠ **Still true for a hand-built DEV classpath, but no longer
  true of a bundle**: since 2026-09-07 (CONNECTORS-BUNDLE-1) the module ships as the SHADED
  `inspecto-connectors.jar` sidecar, which carries javax.mail and the rest, so a packaged deployment boots and
  the connectors actually work. This note is now about the dev launch path only. Derive the list from
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
- **Pure-Node CI guards run BEFORE the Maven build** in `ci.yml`, so any of them can fail a
  green-code push (`tools/check-dependencies.mjs` runs AFTER it — see the guards note above).
  ⚠ **`tools/check-doc-links.mjs` joined them 2026-09-08** — the SIXTH repo guard: every relative
  markdown link in a current doc must resolve (1,415 links, zero dangling). Links inside
  `docs/archived-documents/**` are ignored (never-maintained tier) but links pointing INTO it are
  not, and the exemption plus its live count prints on every run. Links inside a ``` or ~~~ fence
  are skipped: a link in a code fence is quoted text, not a live link.
  🔴 **The lesson the guard roster keeps re-teaching: read a guard's success line as a CLAIM, not a
  result.** Three instances inside two days — `check-coverage --ui` could never pass, so `ui.yml`'s
  step was red from the moment it landed; `check-vocabulary`'s pass 1 announced "9 user-facing
  doc(s) clean" for 54 days while reading ONE (8 of 9 entries were dead paths and the count came
  from the LIST, not the read — now a `stale-scope` rule fails the build on a dead entry); and
  AGT-5's gate ran `gh search code`, which returns zero for EVERY term against that repo, control
  included. A guard's SCOPE is a silent exemption — audit it apart from its rules, and never trust
  a zero without a control probe that must succeed.
  The two that have always run first:
  `tools/check-vocabulary.mjs` (banned synonyms in user-facing docs, **plus banned KEYS in the committed
  TOON config corpus** since 2026-08-04 — it reads `git ls-files`, not the working tree, so local matches
  CI and `spaces/**` runtime state is never scanned; its `CONFIG_ALLOW` doubles as the Flow→Pipeline Tier-3
  debt register and fails when an entry goes stale; **plus the operator-visible MESSAGES since 2026-08-26** — pass 4 scanned only IDENTIFIERS, so a banned word inside a 4xx body, a Signal message or a template label was invisible to all four passes, which left the words a user actually reads as the least-guarded surface in the repo) and `tools/check-secrets.mjs` (a
  secret-ish key assigned a ≥16-char literal — SEC-INCIDENT-1). Both take a per-line `vocab-allow` /
  `secret-allow` comment as the escape hatch.
  🔴 **SCOPE AUDIT 2026-08-29 — both guards silently exempted a tier, and a guard's scope is the part
  nobody re-reads.** `check-secrets` skipped `archived-documents/`, borrowed from the vocabulary guard's
  exclusion list — but the two ask **different questions**: a lint asks "is this doc maintained" (so
  linting prose nobody may edit is unfixable noise), while the secret guard asks "is this doc
  **COMMITTED**". 195 tracked files were invisible, including `legacy-api-sunset-runbook.md` — precisely
  the class the `.md` extension exists for, since **archiving a runbook does not redact it**. The archive
  was verified clean when scope was restored, so it closed a hole rather than a leak. `check-vocabulary`
  missed `docs/stakeholders/**` and the **root canon — including `GLOSSARY.md`, the file that DEFINES the
  bans**; four canon files carried live `Flow` residue the rename program had already retired everywhere
  it looked. ⚠ The canon is a **named file list, not `docs/`**, because `docs/` also holds `ops/`,
  `roadmap/`, `api/`, `ui/` and the permanently-unscanned archive. **A new tier is unscanned by default
  and nothing says so** — that is the recurring shape.
  ⚠ **`check-secrets.mjs` was "master-only" while `4.x` still carried the
  live OAuth secrets** (merging it forward would have pinned that branch's CI red, BACKLOG §5); P1 fixed
  the code there and the branch itself was deleted 2026-08-17, so the caveat is now history. A third guard,
  `npm run lint:tokens`, runs in the separate path-filtered `ui.yml` (§6). A fourth,
  **`tools/check-dependencies.mjs`** (2026-08-28), diffs the resolved runtime dependency graph against
  `tools/dependencies.lock` — it runs LAST in `ci.yml` because it needs the eoiagent install, and it is
  a **review** device, never a vulnerability scanner. ⚠ Its lock was generated on a developer machine;
  if CI ever resolves differently the remedy is one `--update` commit, but a persistent local-vs-CI
  split is a design problem to settle, not to paper over.
- **`ConsignmentEvent.pipeline()` is the LOWERCASED pipeline name** (`cfg.identity().pipelineName()`). Any name
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
- **Pipeline seed = exactly one `source_store`** in Phase-A live execution (rejects 0 or >1; multi-source merge is
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
  authored-pipeline `nodes[n]:` blocks accept `- id:` maps). (3) **Alert rules need an `alert:` wrapper** and
  `severity` ∈ {CRITICAL, INFO, WARNING} — not WARN. (4) **Job-type params are FLAT keys under `job:`**
  (`JobConfig.fromMap` treats unknown keys as params); only `args:`/`bind:` nest. A `params:` wrapper in some
  design-doc sketches is doc-only, not the shipped parser.
- **Authored flows live under `config/flows/` — one dir for both readers (FIXED 2026-07-10):** the UI/HTTP
  authored-pipeline CRUD always wrote `writeRoot()/flows` (= the space's `config/flows/`), but
  `DirSpaceRoot.flowsDir()` pointed `JobService`/the T32 deletion fence at a sibling `spaces/<id>/flows/`, so a
  `type: pipeline` job couldn't resolve a UI-authored pipeline in multi-space mode. `flowsDir()` now returns
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

- **An optional module that is PRESENT but cannot LINK used to kill the whole boot.** Every optional
  module is found by `ServiceLoader`, and the contract is the absence contract — a missing module 503s
  its routes and nothing else changes. A raw `ServiceLoader.load(X.class).findFirst()` does **not**
  honour that: a jar that is present but unloadable throws, and it throws an `Error`
  (`UnsupportedClassVersionError`, `NoClassDefFoundError`) which `ServiceLoader` propagates rather than
  wrapping in a `ServiceConfigurationError`. Nothing up the stack caught it, and there was **no**
  `LinkageError` handling anywhere in `inspecto`'s main tree. Discovered 2026-09-12 scoping `PKG-5`:
  staging the assistant would have made the server fail to boot on a host whose Java was older than the
  assistant's dependency needs. Fixed by `com.gamma.service.OptionalSpi`, which all six discovery sites
  now use. ⛔ It deliberately does **not** catch `RuntimeException` from a provider's constructor —
  unloadable is an absence, misbehaving is a defect, and widening the catch turns a bug into a silent
  absence. 🔴 The lesson generalises past this fix: **"optional" is a property of the FAILURE HANDLING,
  not of the packaging.** A module you merely decline to stage is optional; a module whose absence has
  no handled path is mandatory however you ship it.

- **A shaded sidecar must scope the core `provided`, or it ships a second copy of the product.**
  `inspecto-connectors` gets this right; `inspecto-agent` had `inspecto-processor` at `compile` until
  2026-09-12, so its shaded jar carried 8660 entries — 137 duplicate `etl` classes, 69 duplicate
  `service` classes, **98 MB against 3.5 MB of actual module**. Duplicate `ServiceLoader` registrations
  on one classpath are not merely wasteful: they are a second, older copy of the product competing with
  the real one. ⚠ Also exclude anything the core OWNS — the SLF4J binding above all (two bindings make
  backend selection non-deterministic), plus `java.sql.Driver` and `ServletContainerInitializer`.
  Verify on the **staged artifact**: a module's own tests can never fail for a packaging gap, because
  its classes are trivially on its own test classpath (the `CONNECTORS-BUNDLE-1` lesson, re-learned).

- **`powershell -File` invoked from Bash reads the script as ANSI**, so `package.ps1` reports ~40
  phantom parse errors on lines nobody touched (mangled em-dashes). Use **`pwsh`** (PowerShell 7 is
  installed) to RUN it, and `[Parser]::ParseInput` with explicit UTF-8 — never `ParseFile` — to CHECK
  it. 🔴 Before believing any gate that goes red, run it on `git show HEAD:<file>` first: that is what
  showed this was pre-existing rather than a change of mine.

- **Never start a foreground build while a background one is running in this tree.** A concurrent
  `mvn clean` wipes the sibling build mid-run. On 2026-09-12 a gauntlet reported `MVN_EXIT=1` with 11
  of 26 modules and looked exactly like a regression; it was self-inflicted. ⚠ Related: always check
  **both** `BUILD SUCCESS` and the module count, because a clobbered run can still print a plausible
  test total (it printed 3752 against a true 4315).

- **Promoting per-process state to a shared row changes the cost CATEGORY of every write — so audit
  the READERS, not just the writers.** `PipelineScheduler.lastRunAtMs` stamped a cadence baseline for
  *every* due pipeline each tick: free as a heap `put`, but one `UPDATE` per pipeline per tick once the
  state moved onto the shared run lease (B2, 2026-09-12). Only 2 of the 5 trigger kinds ever read the
  value back — `DEFAULT_POLL` is due every tick *without consulting it*, and `EVENT`/`MANUAL` never run
  on the loop — so the fix was to stamp only triggers that read it (`usesCadence`). ⚠ That is a
  **write-elision, not a behaviour change**: the elided value is unreadable by construction. 🔴 The
  general rule: when state crosses the process boundary, re-derive who actually consumes it; the write
  pattern that was free in heap is a hot-path round trip in a table.

- **Split the fencing decision per operation — a shared row can need a fenced WRITE and an unfenced
  READ.** On the run lease, "who may run this now" and "when did it last run" are different questions:
  the cadence write is fenced on `owner = me AND epoch = mine` (a pod paused past its TTL must not move
  another owner's baseline), but the read is deliberately owner-independent, because an expired or
  released lease still carries a valid baseline and **a pod that has never held the lease must be able
  to read it** — that is the entire cross-pod fix. ⛔ Adding an `owner`/`expires_at` predicate to the
  read would make every idle pipeline fire immediately.

- **`CREATE TABLE IF NOT EXISTS` is not a migration, and the gap is invisible on a fresh install.**
  Adding `last_run_at` to the lease table (B2) would have thrown on the first read for any deployment
  whose table was already created by B0/B1 — no ordinary test can see it, because every test starts
  from an empty database. Needs a guarded `ALTER TABLE … ADD COLUMN IF NOT EXISTS` (both DuckDB and
  Postgres support it) **plus a regression test that builds the pre-migration table by hand**. ⚠ Apply
  this to every `CREATE TABLE IF NOT EXISTS` in the ops-DB families whenever a column is added.

- **Moving a private field breaks the tests that reflect on it — grep `getDeclaredField` first.**
  `CollectorServicePipelineForgetTest` read `PipelineScheduler.lastRunAtMs` reflectively; the B2 move
  required re-pointing it at `PipelineRunGuard` *and* switching its driver from `runAllOnce()` to
  `runPipeline(id)`, because its no-`trigger:` fixture is exactly the `DEFAULT_POLL` case the new
  elision stops stamping. A reflective test fails at RUN time with `NoSuchFieldException`, not at
  compile time, so the compiler will not warn you.
- **⛔ "Pipeline" names TWO disjoint id spaces, and reusing one key across them is a silent bug.** A
  **collector pipeline** is a `*_pipeline.toon` config, named through `ConfigRegistry` /
  `CollectorService.pathFor`; an **authored pipeline** is a `*_flow.toon` graph in `PipelineStore`, named by a
  pipeline job's `pipeline:`/`flow:` param. Different stores, and *nothing* enforces uniqueness between
  them, so one literal string can denote two unrelated things. 🔴 The B3 slice was designed on the
  assumption they were one namespace — that a cron pipeline-job could collide with a poll-cycle run — and
  the assumption was **false**; a shared lease would have excluded unrelated work on an accidental name
  match while fixing nothing. **Before keying any shared structure on "a pipeline id", establish which of
  the two you have.** `PipelineStore`'s class note states the split; `DbRunLease.SCOPE_AUTHORED` records it.
- **A per-name lock only excludes callers that agree on the name.** `JobService.runJob` serializes on the
  **job name**, but `triggerPipelineRun` builds a *synthetic* config named after the **pipeline id**, so an
  ad-hoc authored-pipeline run and a registered job targeting that same pipeline have different names and never
  excluded each other — they ran the one pipeline concurrently, on a single node, for as long as both paths
  existed. Two registered jobs sharing a `pipeline:` param are the same hole. ⚠ When a second entry point
  is added to an existing guarded path, check it derives the **same key**, not merely that it is guarded.
- **⚠ An exclusion claim must span the WORK, not the submit.** `if (lease.tryAcquire(x)) submit(x)` reads
  like cross-pod arming but is not: the claim is released before the run, so the next pod claims and
  submits the same firing. The plan bullet for B3 was written that way and had to be corrected in the
  build. Hold the claim in the run body's `try`/`finally`.
- **⚠ Module direction beats convenience when placing a seam.** `RunLease` lives in **inspecto**, and
  **inspecto depends on inspecto-engine** — so `JobService` (engine) cannot name it, no matter how obviously
  it is "the same idea". The fix is a narrow engine-side interface (`com.gamma.job.RunClaims`) that the host
  adapts onto at wiring time. ⛔ Do not answer this by moving the richer type downward: `RunLease` is bound
  to a `SpaceRoot` and opens operational-DB families, neither of which the engine knows about.
- **Two things that must key off one value should CALL one function, not both compute it.** The deletion
  fence's running-set and the B3 authored-pipeline claim both need a pipeline job's authored-pipeline id; `trackPipelineStart` now
  calls `authoredPipelineKey()` rather than recomputing it. The payoff showed up in mutation testing: keying `authoredPipelineKey`
  on the job name failed the *pre-existing* fence test alongside the new ones, which is the evidence they
  genuinely share a key rather than happening to agree today.
- **⚠ A python-in-bash edit REWRITES LINE ENDINGS on Windows and inflates the diff ~6×.** `.gitattributes`
  is `* text=auto eol=lf`, but Python's `io.open(path, 'w')` does universal-newline translation and emits
  **CRLF** on Windows. Git then normalises to LF in the index, so the committed *content* is right — but a
  file previously stored with CRLF shows as **fully rewritten**: a 302-line change reported as 1783/1491
  on 2026-09-12. ⛔ Nothing is broken and ⛔ do not rewrite history over it; just know the real size is
  `git show --stat --ignore-cr-at-eol`. **Pass `newline=''` when writing** (`io.open(p, 'w', newline='')`)
  to preserve what was there, or use the Edit tool for small changes. ⚠ The tell is a diff far larger
  than the edit, plus git's "CRLF will be replaced by LF" warning at commit time.
- **🔴 CAUSE FOUND 2026-09-12 (correcting the note below): "transient" `NoClassDefFoundError` in an
  untouched module is CONCURRENT MAVEN RUNS IN ONE TREE — it is self-inflicted, not flaky.** It happened
  **four times** in one shift (`asn-golden` ×2, `inspecto-etl`, plus one ambiguous run) and the pattern was
  identical every time: a **background** gauntlet still running while **foreground** targeted/mutation
  builds were fired in the same checkout. The sibling's `clean` wipes `target/classes` mid-run, so a class
  that is *demonstrably on disk when you look* was absent when the other JVM's surefire loaded it.
  ⛔ **The tell: `NoClassDefFoundError` for a class you can `ls`.** ⚠ A stale JVM is NOT the cause — that
  was this note's first hypothesis and it was wrong. ⛔ **One build at a time in this tree, full stop**;
  before launching a gauntlet, confirm no other build is live, and never fire a targeted run "just
  quickly" while one is in flight. 🔴 The expensive part is not the lost build — it is that a clobbered run
  looks exactly like a real regression in code you never touched, and costs a diagnosis every time.
- **🔴 A REAL flake did exist, and it was an ASYNC BOOT TASK racing the test body — diagnose by making
  the race LOSE, not by re-running.** `ControlApiProblemFilesTest.limitBounds…` was intermittent on the
  `inspecto` module's only gate (measured 2026-09-17: 1 of 3 full-module runs red at an **unchanged** tree,
  green 8/8 alone). Cause: the status read surface is a DB **projection**, refreshed only at boot and at the
  end of a poll cycle — and `CollectorService.start()` schedules the first cycle with **initial delay 0**, so
  that cycle's sync ran concurrently with the test, which seeded its ledger *after* boot. Win the race, green;
  lose it, the route honestly returns 0 rows. ⚠ **The `@TempDir`/shared-state/mtime hypotheses were all wrong**
  — every test had its own temp root. ⇒ **The technique that settled it in one run: insert a `Thread.sleep`
  to force the losing order**, which turned a 1-in-3 flake into a deterministic, byte-identical failure; the
  same probe passing afterwards is then real proof the race is gone. ⚠ **A retry/poll helper was not a fix but
  a FALSE one** — after the boot cycle there is no second sync inside any sane deadline, so `awaitTotal`'s
  10s loop could never recover the miss; it only widened the window it was papering over. Fix + the
  seed-before-boot rule: `e5e4ee8f`; mechanism in `okf/backend/build-run/operations-reference.md`
  § "Status backend".
- **⚠ A full reactor build can fail TRANSIENTLY in an untouched module on Windows — re-run before
  believing it.** *(Kept for the diagnosis technique; the CAUSE is the entry above, not staleness.)* Twice on 2026-09-12 `mvn -o clean test -Pedition-enterprise` died in the `asn-*` subtree
  on sources nobody had edited: once as `asn-golden` test-compile errors (*"package com.gamma.asn.schema
  does not exist"*), once as `asn-schema` failing at test execution with `NoClassDefFoundError` for **its
  own just-compiled classes**. Both vanished on a clean re-run that then went fully green. The signature
  is a **missing-symbol or missing-class failure in a module your change does not touch**, and it looks
  exactly like a real regression. 🔴 Before chasing it: confirm the module's sources are unmodified
  (`git status`), check the installed jar actually contains the "missing" symbol (`jar tf`), and look for
  a **stale `java.exe`** — a leftover JVM holding file locks is the likeliest cause on this platform.
  ⛔ Do not "fix" the untouched module, and do not commit over a red build without establishing which of
  the two it is.
- **⚠ `-pl` on the `asn-*` subtree SILENTLY DROPS `-Pedition-enterprise`** — Maven warns *"The requested
  profile could not be activated because it does not exist"* and carries on with the default profile set,
  because the profile is declared at the Inspecto root, not in that subtree's poms. So a narrow `-pl`
  re-run **does not exercise the same build as the gauntlet** and cannot, on its own, clear a failure the
  full build produced. Use it to gather evidence, never as the proof. (Sibling of the known
  `mvn package` w/o a profile SKIPS the edition modules trap.)
- **🔴 A PLAN SECTION CAN BE STALER THAN THE CODE IT PLANS — ground every bullet before building it.**
  Scale-out §5.3 listed five work items on 2026-09-12; **three were already shipped**: `IntakeGovernor`'s
  Space key (fixed 2026-09-10 — and recorded as closed in **§12 of the same document**), per-tenant ABAC
  (shipped 2026-07-24, *seven weeks* before the bullet was read), and "partition by Space", which was
  always a statement of direction rather than work. ⚠ The cost of not checking is building something that
  exists; the cost of checking is one read-only grounding pass. ⛔ Treat a plan bullet exactly like a
  BACKLOG row's stated cause — a hypothesis. **Check §12 / the defects section of the same plan first**:
  it is where the closures get recorded when the work section does not get updated.
- **⚠ Counting a reactor's modules by its dot-leader summary lines UNDER-reports.** A regex keyed on
  `\.{5,}` matched 12 of 32 on a full green build — which, against the "fewer than 26 = clobbered partial"
  rule, reads as a catastrophic partial. Count `^\[INFO\] Building ` lines instead, and treat a surprising
  module count as a suspect *regex* before a suspect *build*.

---

- 🔴 **A PROBE THAT CANNOT RETURN A HIT REPORTS "ABSENT" AND EXITS 0. Silence is not evidence.**
  Three instances in one shift (2026-09-16), two of them by the same person, one committed before it was
  caught:
  - `git ls-tree HEAD asn-parser/` run from a **module subdirectory** prints nothing and **exits 0**,
    because `ls-tree` pathspecs are CWD-relative. The subtree is fully tracked (70 files). That false claim
    reached five agent briefings, a board row and a commit message — and **five agents independently
    "confirmed" it, because they all ran the probe they were given.**
  - `ls -d legacy-code` at the repo root, for a claim about `asn-parser/asn-decoders/legacy-code/`. The pom
    exists, is a declared `<module>`, and its `<sourceDirectory>../../src/main/java</sourceDirectory>` is
    exactly the tree the rows said it compiles. A row was FILED and COMMITTED on that zero, then retracted.
  - The mirror error: `grep poi` over the poms returns **nine hits, every one a substring of
    "point"/"policy"** — a false POSITIVE. Match `org.apache.poi` or `<artifactId>poi`.
  - **THIRD occurrence, 2026-09-17, same lineage:** a row claimed `asn-parser/src/` holds *“66 Java files that
    no pom compiles (`asn-parser/pom.xml` does not exist)”* — true about that filename, and irrelevant.
    `legacy-code/pom.xml`, one level down, sets `<sourceDirectory>../../src/main/java</sourceDirectory>`,
    which resolves to exactly that tree; `legacy-code` is an unconditional `<module>`, and its
    `target/classes` holds **41 `.class` files** from a real build. ⛔ **Its remedy was “delete the tree”.**
    The row even carried a disclaimer saying it was *not* the same subject as the retracted one — exactly
    backwards. (What survived: the **21 files under `src/test/`** genuinely are unbuilt — no
    `testSourceDirectory`, no `target/test-classes`.)
  ⇒ **Four rules.** (1) Run probes from the repo ROOT, and scope the probe to the claim — a claim about a
  nested path is not tested at the root. (2) Before believing a zero, **prove the probe can return a hit**
  (run it against something you know is there). (3) ⛔ **Agreement between agents is not corroboration when
  they share a probe.** Independent confirmation means a *different* method, not another caller.
  (4) ⛔ **A Maven module's source root need not live under its own directory** — `grep -r "<sourceDirectory>"`
  before concluding any tree is unbuilt, and check `target/classes` for the artefact rather than arguing
  from poms.
  ⚠ Prior art, same class: the grep helper whose quoted-pathspec default produced literal-quote false zeros
  for a whole batch.

- 🔴 **BESIDE AN ACTIVE PEER, `git commit -- <explicit paths>` IS THE ONLY SAFE COMMIT — `git add` by
  path does NOT protect you.** This tree is shared, and a peer's files can already be **staged** before your
  session starts. On 2026-09-17 `docs/INDEX.md` and a new plan sat in the index as someone else's work-in-
  progress; `git add <my files>` left them staged, so a plain `git commit` would have shipped their work
  under my message — the incident this repo has already recorded twice, from both directions.
  ⇒ **Commit with a pathspec** (`git commit -F - -- pathA pathB …`): it commits the working-tree content of
  exactly those paths and leaves every foreign index entry untouched. Verify afterwards with
  `git status --short` that their entries are still `M `/`A ` (staged, uncommitted).
  ⚠ **One trap in that workflow:** a **new UNTRACKED** file cannot be committed by pathspec —
  `git commit -- <new file>` fails with *“did not match any file(s) known to git”*. `git add` that ONE file
  first (safe — it is yours), then pathspec-commit. ⚠ The by-hunk rule still applies to a SHARED file both
  sessions edit; the pathspec rule is for disjoint files with a dirty shared index.

- 🔴 **A COMPILE IS NOT A VERIFICATION, and a reactor halt reads as a pass.**
  A lane reported "core shipped" on a clean `test-compile`; the first real run was **5 errors**. Separately,
  a red in an UPSTREAM module **halts the reactor**, leaving downstream modules `SKIPPED` — and under
  `mvn -q` that reads as a clean run. One agent came within a sentence of certifying tests that never
  executed. ⇒ **Demand per-module `Tests run` totals and treat a SKIPPED module as a NON-VERDICT.**
  ⚠ `-DfailIfNoTests=false` is NOT the flag — `-Dsurefire.failIfNoSpecifiedTests=false` is, and the wrong
  one turns every upstream module into a reactor-halting red. ⚠ `mvn -q` can also emit an EMPTY log that
  reads as success; do not use `-q` to verify.

- ⚠ **On a long-lived board, the failure modes are structural, not random.** Measured over 21 grounded
  items: **14 were already shipped, impossible, or misdescribed.** The recurring shapes, each of which cost
  at least one round-trip: a **headline** goes stale while its detail block is updated (one row said
  "nothing retries" while a table 500 lines away said SHIPPED — same file); a row **title misreads its own
  source** (GAP-10 said "bundle missing 13 docs"; the spec described an NTFS deny-ACL needing no repo
  change); **superseded text kept as an indented sub-bullet** reads as live status (status is the STRUCK
  line ABOVE it); and worst, a stale **INSTRUCTION** ("take the shape as written and do not improvise it")
  describing work that had shipped the day before — an instruction outliving its facts gets ACTED on.
  ⇒ The durable answer is not more sweeps but **derived guards**: `check-doc-counts` (rank census),
  `route-gating-report --check` (route inventory) and `check-backlog-homes` (owning-doc pointers) each went
  RED on real drift within hours of existing. ⚠ `check-backlog-homes` can only check **14 of 54** pointers,
  because 32 of 55 rows carry no identifier — and the rows that had rotted were disproportionately the
  ungreppable ones. **Requiring an id on every row is the outstanding structural fix.**


## 5. Engine seams & performance (durable; current in `inspecto/`)

- **Single ingestion SPI:** `StreamingFileIngester` (emit-based) is the **only** ingestion SPI. Per-batch the
  framework picks **union** mode (many small files → per-member views `UNION ALL` → one transform/write pass) vs
  **generation** mode (one huge file → bounded flushing). Selector `processing.streaming.large_file_bytes`
  (default 256 MB); generation budget `processing.streaming.flush_records` (default 5,000,000).
- **DuckDB `Appender` ingest** (vs JDBC `executeBatch`) ≈ **75× faster** (1M-row bench ~6.9k → ~510k rows/s).
- **Modularity seams** (behavior-preserving; SQL/`.toon`/on-disk output unchanged): `OutputFormat`
  (enum-as-strategy), `TransformCompiler` (`transformType → ColumnRule`), `ConsignmentIngestStrategy` (Csv/Plugin →
  typed `IngestOutcome`; `ConsignmentIngestor` is a thin coordinator).
- **Auto-derive `duckdb_threads`** — `DuckDbUtil.effectiveWorkerThreads`: `0`=auto `max(1,cores/concurrency)`,
  `>0`=verbatim, `-1`=DuckDB per-core default; single-batch→all cores. Avoids the threads×cores oversubscription
  stall (~+15% tax, widens with cores).
- **Quarantine semantics:** throw → `QUARANTINED_UNREADABLE`; 0 emitted rows → `QUARANTINED_MISMATCH`;
  `SinkFlushException` → fail the batch.
- **Output files: the JSON manifest is authoritative for EXISTENCE, `consignment_outputs` only for STATE.**
  `PartitionOutput(partition, outputFile, bytes)` is an *ephemeral* return value — produced by
  `PartitionWriter.reveal()`, consumed once, discarded — in **three** paths: ingest, `EnrichmentEngine`, and
  `PartitionSinkWriter`. (`DecisionRuleApplier` is *not* a fourth: its `RouteSink` already calls
  `LineageCollector`, and `ConsignmentIngestStrategy.writeAndTrace` seeds its accumulators from `applied.outputs()`, so
  routed-rule outputs reach the ingest hook for free. The hook is `ConsignmentIngestor.finalizeSource`, once per
  Consignment — *not* `writeAndTrace`, which has four callers and is invoked **per segment** in union mode
  and **per chunk** in chunked mode. ⚠ Since 2026-08-29 that multiplicity is load-bearing: those callers pass a
  **write scope** so the batch's shared branch-commit ledger keeps their sinks distinct — without it the second
  and later writes read as "already committed" and their rows vanish. See
  [`okf/backend/engine/branch-aware-ingest.md`](okf/backend/engine/branch-aware-ingest.md) §"The lane fork".)
  The durable registry (`DbConsignmentOutputStore`, plan §11.3) is **default-off** and `ServiceStores` degrades a
  failed open to `null`, so **never read a missing registry row as proof a file does not exist** — `ConsignmentManifest`/
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
- 🔴 **A surface shown BEFORE sign-in cannot use the ordinary services** (2026-09-15, landing pages).
  `GET /settings/branding` sits behind the auth gate, so `BrandingService` — which every in-shell surface
  uses — **401s on the sign-in page**, the one screen where deployment branding matters most. Branding now
  rides `GET /bootstrap` (public) and `SessionService.branding` exposes it; ⛔ do not "simplify" the sign-in
  page onto `BrandingService`. The general rule: on a pre-sign-in surface, `/bootstrap` and `/health` are the
  only routes you may assume, and anything else must be checked against `ControlApi.PUBLIC_PATHS`.
- 🔴 **Nothing in the running system knows the product version** (2026-09-15). No Java file reads
  `Implementation-Version`, no route serves a version, `environment.ts` has no version field, and
  `inspecto-ui/package.json` says **21.0.0** — the Angular scaffold's number, **not** the product's
  `4.0.0-SNAPSHOT`. ⛔ Never print `package.json`'s version as the product version. BACKLOG `HOME-VERSION-1`.
- ⚠ **A javadoc can name a dev switch that does not exist.** `session.service.ts` documents a
  `mockAuthMode: 'oidc'` switch for previewing the OIDC arm; **grep finds that one comment and zero
  implementations**, and `tools/run-backend.ps1` passes no `-Dauth.mode` either — so the sign-in page cannot
  be opened locally at all without editing source, and it shipped unit-tested but never seen in a browser
  (BACKLOG `SIGNIN-PREVIEW-1`). Check that a switch a comment promises is real before planning to use it.
- ⚠ **`TestBed` refuses a second `configureTestingModule` once instantiated**, so a spec helper that builds a
  fixture can only be called **once per `it()`** — the documented house rule. When a test genuinely needs two
  fixtures (gating read in `ngOnInit` cannot be un-called by flipping a signal afterwards), call
  `TestBed.resetTestingModule()` at the top of the helper and say why; otherwise build one fixture and mutate
  its stub signals.
- **Feature panes** in `src/app/modules/admin/<feature>/`, **signals + OnPush**. A pane can be reused across
  routes via `ActivatedRoute.snapshot.data` (Cases/Issues = one `ObjectsComponent`).
- **Second "lens" on a pane = `mat-button-toggle-group`, NOT a new nav item** (Pipelines `flow|combined`, Jobs
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
- 🔴 **The offline mock backend was DELETED 2026-08-31** (`f1553136`, plan archived at
  `archived-documents/plans-archive/mock-backend-removal-plan.md`). `inspecto-ui/src/app/inspecto/mock/`,
  `environment.offline.ts`, the `offline` build/serve configurations, `npm run start:offline` and the ten
  `environment.mock*` flags are gone; the UI talks only to a real ControlApi. **Running it now needs a
  backend** — dev: `npm start` on :4204 with `proxy.conf.json` forwarding `/api` to :8080; packaged:
  `-Dui.dir=…/dist/gamma/browser` (note the `browser` subdir). ⚠ Two directories survive the move and are
  NOT mocks: `inspecto/contracts/` (six server-published contract JSONs, read **by path** by six Java
  contract tests and exempted in `.prettierignore` because they are byte-compared against Jackson's output)
  and `inspecto/fixtures/` (spec-only sample rows). The note below is kept for the LESSON, which outlived
  the mock: it is why a mock-vs-server divergence was the whole reason for the removal.
- ⚠ **The offline mock must never be more lenient than the server** (2026-07-27, AGT-6a A5.3) — *historical;
  there is no mock any more.* A handler
  that accepts a shape the backend rejects — or returns a richer shape than the backend returns — turns a
  hard failure into a passing rehearsal. The Pipelines `pipeline_author` adoption shipped **broken through
  two slices** (flat args where the tool requires `flow`; a name string where `adaptToolResult` requires the
  graph, so a successful call rendered as *"no suggestion"* with no error) and looked correct offline
  throughout. 🔴 **The transferable lesson: a test double that is more PERMISSIVE than the real route turns a
  hard failure into a passing rehearsal** — so a double must be diffed against the route's accepted args
  *and* its result keys, and the strictness pinned by a test. *(The mock backend this was learned on was
  deleted 2026-08-31; the lesson applies to any stub or fake that stands in for a route.)*
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
- **Mocking** — ⛔ **there is none, and adding one back is a decision, not a convenience.** The single
  offline mock backend (`inspecto/mock/`, one interceptor over a localStorage store with seed packs) was
  **deleted 2026-08-31**: the SPA needs a running control plane, and a failed call must surface as an error
  rather than as sample data. In unit tests, mock at the service boundary; ⛔ never mock the HTTP layer
  (`.claude/skills/test-author/SKILL.md`).
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
  attribute in BOTH, or the committed `inspecto/contracts/node-attributes.contract.json` drift check fails on one
  of the two sides (deliberately — see `okf/frontend/features/pipeline-editor.md`). Adding an `AttributeType` still
  needs `FindingsSpec.TYPES` widened, which `NodeAttribute` now delegates to.
- **Optimistic mutations** — `optimisticMutate({apply,commit,reconcile,rollback,onError})` (`inspecto/api/
  optimistic.ts`); reassign arrays (`rows=[...]`) so the grid re-renders.
- **G6 graph** — reuse `modules/admin/catalog/graph-view.component.ts` (`@Input data`, `@Output nodeClick`);
  nodes are canvas-drawn (not DOM) → verify inspector logic via unit test, not preview clicks. Pipeline graph data
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
- **eoiagent** (upstream repo `jotder/inspect-agent`, ⚠ not a local path — corrected 2026-09-09) — agent platform; Inspecto's model transport. Pinned to the
  released **`0.1.0`** (tag `v0.1.0`, EOI-7a 2026-07-08; trunk now `0.2.0-SNAPSHOT`). Rebuild into local `.m2`
  with `git checkout v0.1.0 && mvn -o clean install` until a registry is chosen (EOI-7b).
- **CVVE** (`C:/sandbox/agentic-doc-validation`) — kernel's 3rd consumer; first real `HumanHandoff` driver.

(Detailed progress for these lives in the per-user agent memory, not in this repo — they are different projects.)

## DATA-GOV-1 decision (2026-09-06)

🔴 **Status 2026-09-15 (operator): NOT DONE.** Neither the encrypted out-of-band archive nor the fetch script exists; the corpus has not moved. Recorded here so the section stops reading as decided-and-handled — the BACKLOG §2 gate closes only when this section carries the dated archive location and the fetch-script path, and today it carries neither. ⚠ Consequence: `asn-parser`'s corpus tests stay opt-in and data-gated, so that lane (compiled by `legacy-code/pom.xml`, not dead) is tested only against synthetic input; and real carrier data without an archive is a data-governance finding in its own right, independent of any other compliance work.

The real carrier corpus (~57 MB, two carriers, only in working trees) moves to an **encrypted out-of-band
archive on company storage, fetched by script**, access held by the data-agreement owner; the parity harness runs
wherever the archive is provisioned. A **small synthetic subset is committed for CI smoke** — a complement, not a
replacement: every parity defect so far came from real files. **The synthetic subset SHIPPED 2026-09-06**
(`asn-parser/corpus-synthetic/` + `SyntheticCorpusTest` in asn-golden, always-on: two cases — back-to-back records
with OPTIONAL/SEQUENCE OF/CHOICE, and the Huawei file shape with a 50-byte header, 4-byte record headers and 0x00
fill; BER as hex text so the `*.ber` ban stands; regenerate with `-Dasn.synthetic.write=true`). It pinned one
reader rule on the way: fill bytes are skipped BEFORE a record header, so a header must not start with 0x00/0xFF.
Git LFS is refused unless the data agreement permits
third-party hosting. ⚠ Never force-push or reset master to fix this. The archive itself is an org action
(BACKLOG §2); the synthetic subset is BACKLOG §3 `DATA-GOV-SYNTH`.
