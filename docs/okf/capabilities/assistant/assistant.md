---
type: Capability
area: AGT · EOI
title: Assistant (AGT + EOI) — capability spec
description: The requirement-of-record and as-built specification for the Assistant — the reflex assist agent's seven skills, model transport and the air-gap invariant, the deliberative embedded-intelligence layer with its tool belt and autonomy ladder, the gated write path, and the eoiagent runtime it depends on. Includes the finding that no shipped bundle carries any of it.
status: current
written: 2026-09-08
supersedes-rows: REQUIREMENTS §3.13 AGT-1..AGT-6b and §3.14 EOI-1..EOI-7 (this file corrects them, see §2)
---

# Assistant (`AGT` + `EOI`)

> **How to read this file.** §1–§2 are the *requirement of record*: where they disagree with
> `docs/REQUIREMENTS.md` §3.13/§3.14 or `docs/EDITIONS.md`, **this file wins** and the disagreement is
> stated in place. §3 is the as-built specification, §4 the dated decisions, §5 what is not built,
> §6 what was refused or superseded, §7 the pointers, §8 how it is verified.
>
> ⛔ **`AGT` and `EOI` are ONE capability.** `docs/GLOSSARY.md` §14 settles it: "embedded intelligence"
> is a *module* name (`inspecto-intelligence`), "Agentic" has no glossary entry, and `EOI` names a
> **separate repository** whose rows are this Assistant's runtime. Both ID ranges keep their numbers.
>
> 🔴 **Read §3.10 before you read anything else as a promise.** Every `AGT` row is marked SHIPPED with an
> edition of `All`, and **no artifact `package.ps1` produces contains any of this code.** The assist
> routes answer `503` in every bundle, by design. "SHIPPED" here is true only in the sense of *built and
> tested in the reactor* — it is not reachable by an operator. This is the same distinction the repo has
> already had to learn twice, for `@PublicApi` (intent, not exposure) and for `inspecto-connectors`
> (in the reactor, in no deployment).

## 1. Purpose & scope

The Assistant is the platform's AI surface: a **reflex** layer that answers questions about what the
system contains and drafts artifacts read-only, and a **deliberative** layer that investigates, authors
and — under an explicit human gate — acts.

**In scope**

* **The assist agent** — the `AssistAgent` SPI, its seven read-only/draft-only skills, the abstain-only
  escalation policy, and the `/assist/*` routes.
* **Model transport** — the provider seam, the eoiagent gateway bridge, the native Ollama provider, and
  the **air-gap invariant** that keeps hosted SDKs physically out of a build.
* **Model Settings** — the pane's routes and the per-tier connectivity probes.
* **Embedded intelligence** (`inspecto-intelligence`) — grounding through the context broker, the tool
  belt, the **autonomy ladder** (Explain → Draft → Act-with-approval → bounded autonomy), the Case store
  and the seeded runbooks.
* **The gated write path** — how a mutating agent action reaches an approval and a dry-run preview.
* **The Signal-Backbone's agent half (S3–S7)** — the AG-UI projection, the A2UI render channel, the agent
  context fabric, and the reflex-layer unification. `OPS` deliberately scoped these here.
* **eoiagent as a dependency** — what Inspecto consumes, how it is pinned, and how it is built.
* **The client surfaces** — the Assist console, the streaming Agent Chat, the in-product glossary,
  inline draft authoring on five panes, the Approvals inbox, the Autonomy dashboard, Model Settings,
  and the A2UI render channel they all share.
* **What a shipped bundle actually contains** (§3.10) — the packaging truth for all of the above.

**Out of scope (owned elsewhere)**

* **eoiagent's own internals** — its 18-module reactor, its ports and adapters, its ADRs. It is a
  **separate repository** with its own documentation bundle; `docs/okf/agentic/` is a distilled map and
  this spec treats the dependency as a black box with a version and a seam (§3.8).
* **The signal ledger, events, metrics and the audit trail** → `OPS`
  ([`observability/observability.md`](../observability/observability.md)). The Assistant *emits onto* it.
* **Alert Rules, Alerts, Incidents and Cases** (the operational-object kind) → `INC`
  ([`incidents/incidents.md`](../incidents/incidents.md)). ⚠ The Assistant's own **Case store** is a
  different thing with the same word — see §3.5.
* **The component registry and `ConfigSpec` validation** → `MET`. Drafting tools write through it.
* **Authentication, the write-root gate and ABAC** → `SEC`; **bundling and the Java floor** → `PKG`.
* **The panes an agent drafts into** → their own areas (`Studio`, `PIP`, `ING`).

## 2. Requirements of record

| ID | Requirement (as it holds) | MoSCoW | Status of record | Edition of record |
|---|---|---|---|---|
| **AGT-1** | **Assistant** skills — seven, read-only/draft-only, abstain-only escalation | Must | ✅ Built and tested. The seven are `DiagnoseAndAlertSkill`, `ExplainEntitySkill`, `KpiToSqlSkill`, `NlToScheduleSkill`, `ReportNarrativeSkill`, `ReportSqlSkill`, `SuggestConfigSkill` | 🔴 **CORRECTION — no edition.** The row says `All`; `EDITIONS` `CP-14` says `—/—/—` and is right. See §3.10 |
| **AGT-2** | Pluggable model transport: eoiagent gateway bridge + native Ollama provider; hosted providers isolated in `inspecto-agent-hosted` | Must | ✅ Built and tested | 🔴 **CORRECTION — no edition** (§3.10) |
| **AGT-3** | Air-gap guarantee: hosted SDKs physically absent from air-gapped builds | Must | ✅ Built and tested, and the invariant is asserted by a test ⚠ **Named for grep:** the assertion lives in `EgressGuardTest` (`inspecto-agent/src/test/java/com/gamma/agent/EgressGuardTest.java`). | **All** — this one *is* edition-wide, but trivially: the guarantee is that the code is **absent**, and in a stock bundle the whole layer is absent (§3.10) |
| **AGT-4** | Model Settings pane + per-tier connectivity probes | Should | ✅ Built and tested | 🔴 **CORRECTION — the pane ships, the thing it configures does not** (§3.10) |
| **AGT-5** | Embedded intelligence: context grounding, tool belt, autonomy ladder | Should | ✅ P0–P5 complete 2026-07-21 + polish. The belt is **23 tools**, pinned by `InspectoPackTest`'s `assertEquals(23, tools.size())` — the board is right and the concept page is not (§3.5) | 🔴 **CORRECTION — no edition.** The row's `All (L3 = S+, opt-in)` describes a gating that no bundle can reach (§3.10) |
| **AGT-6a** | "AI behind every screen" — inline natural-language authoring on every pane, reusing the shipped draft tools | Should | 🟡 **CORRECTION — more is shipped than "PLANNED" suggests.** The mechanism is live: `<inspecto-ai-assist>` runs six draft tools on **five real panes** (pipeline editor, Query Library, Expectations, Dashboard Builder, Link-Analysis query panel), and `<inspecto-ai-explain>` is adopted by **twelve** panes. Deterministic single-tool dispatch shipped 2026-07-26. The live plan is blunter than the board: it records **D1–D4 and D8–D11 answered** and **all of A1–A5 shipped**, and stays open only for the `kpi_report_builder` host. The board still says "ready to schedule pending D1–D4". What remains is the *ambition* — every screen — not the capability | — |
| **AGT-6b** | Multi-step agent graphs — model-composed plans beyond the seeded runbooks | Could | 🟡 PLANNED, demand-gated. ⚠ Its stated upstream prerequisite (a per-tool dry-run seam) was **discharged 2026-09-08**, so the row is now actionable and no longer externally gated | — |
| **EOI-1** | Embeddable framework-free Java agent library | Must | ✅ SHIPPED upstream | — (separate repo) |
| **EOI-2** | Pluggable models: gateway seam, OpenAI-compatible + local portability | Must | ✅ SHIPPED upstream | — |
| **EOI-3** | Governance: approval gate + dry-run for every mutating action | Must | ✅ SHIPPED upstream. ⚠ The **per-tool** dry-run seam Inspecto wanted arrived later (2026-09-08) | — |
| **EOI-4** | Audit trail + observability of agent decisions | Must | ✅ SHIPPED upstream | — |
| **EOI-5** | Core vs application-pack split | Must | ✅ SHIPPED upstream; Inspecto's pack is `com.gamma.intelligence.pack` | — |
| **EOI-6** | Eval harness for skill/orchestration regression | Should | ✅ SHIPPED upstream | — |
| **EOI-7** | Cut a `0.1.0` release + publish artifacts | **Must** | 🔴 **CORRECTION — the cell is false on its own terms.** It claims "(a) cut + pinned … both Inspecto agent poms pin `eoiagent.version 0.1.0` (**no SNAPSHOT anywhere**)". The reactor's parent pom pins **`0.2.0-SNAPSHOT`**, the version is managed by the **parent** (not the two agent poms), and continuous integration **clones the upstream and rebuilds it from the branch head on every run**. So half (a) has regressed and half (b) — publish — is still the open half | — |

**The version is stated four ways.** This is the `count-stated-N-ways` disease applied to a dependency:

| Where it is stated | What it says |
|---|---|
| `okf/agentic/index.md`, `okf/agentic/overview.md` | `0.1.0-SNAPSHOT`, "no release tag yet" |
| `REQUIREMENTS.md` `EOI-7` | `v0.1.0` tagged, poms pin `0.1.0`, "no SNAPSHOT anywhere" |
| `pom.xml` (the build) | **`<eoiagent.version>0.2.0-SNAPSHOT</eoiagent.version>`** |
| `okf/backend/agent/embedded-intelligence.md` | CI builds from the upstream `main`, "currently `0.2.0-SNAPSHOT`" |

The build is the authority: **`0.2.0-SNAPSHOT`, rebuilt from a moving branch head, published nowhere.**

**The upstream's identity is stated two ways.** The product is called **eoiagent** (Maven groupId
`com.eoiagent`); the repository continuous integration actually clones is **`jotder/inspect-agent`**.
⚠ `okf/agentic/` additionally points at a **local sandbox path that does not exist on this machine** and
calls it the home of the authoritative docs. Anyone grepping "eoiagent" for the repo will find nothing.

## 3. Specification

### 3.1 Two layers, and two unrelated tool systems

The Assistant is deliberately **two** things:

* **The reflex layer** — `UccAssistAgent` on the vendored kernel (`com.gamma.agent.kernel.*`, formerly
  the discontinued agent-kernel). Single-turn, read-only or draft-only, no memory, **abstain-only**.
* **The deliberative layer** — `InspectoIntelligenceAgent` in `inspecto-intelligence`, an eoiagent
  application pack. Multi-turn, grounded, tool-using, with an autonomy ladder and an approval gate.

🔴 **Two unrelated `Tool` abstractions coexist and must not be conflated**: eoiagent's
`com.eoiagent.tool.Tool`/`ToolSpec` (what the intelligence pack and the Signal tools use) versus the
older `com.gamma.agent.kernel.tool.ToolRegistry` (the reflex skills' path, e.g. `AlertRuleTool`,
`SqlOracleTool` in `inspecto-agent`). Nothing bridges them, and adding a tool means choosing a side.

### 3.2 The assist agent (AGT-1)

**The SPI is core, the implementation is not.** `AssistAgent`
(`inspecto/src/main/java/com/gamma/assist/spi/AssistAgent.java`) is discovered by `ServiceLoader`; the
core JAR carries the interface and nothing that implements it (§3.10). `UccAssistAgent`
(`inspecto-agent/src/main/java/com/gamma/agent/UccAssistAgent.java`) registers **seven** skills:
`DiagnoseAndAlertSkill`, `ExplainEntitySkill`, `KpiToSqlSkill`, `NlToScheduleSkill`,
`ReportNarrativeSkill`, `ReportSqlSkill`, `SuggestConfigSkill`.

**Dispatch** runs `SyncOrchestrator` → `CapabilityRegistry` → skill → `UccConfidenceEstimator`, and then
either surfaces the result or returns `EscalationRung.Abstain`. **Abstain-only is the whole policy**:
there is no tier-bump and no human handoff, and `applyVia` is always `null` — the reflex layer can never
be the thing that applies a change.

**Routes**: `POST /assist/{intent}` (unknown intent → unsupported; model down → unavailable),
`GET /assist/diagnoses`, `GET|POST /assist/settings` (live reconfigure behind the write gate),
`POST /assist/settings/test` (probe each model tier), `GET /assist/metrics` — **counts only, never data
values**, which is the privacy line this surface holds.

### 3.3 Model transport and the air-gap invariant (AGT-2, AGT-3)

Transport is a seam, not a dependency: the eoiagent gateway bridge supplies hosted and
OpenAI-compatible models, and a **native Ollama provider** covers the local case. Hosted SDKs are
quarantined in a separate module, `inspecto-agent-hosted`, so that an air-gapped build does not merely
disable them — **the classes are not on the disk**. The invariant is asserted by a test rather than
documented, which is the right shape: a disabled egress path is a configuration, an absent one is a
guarantee.

### 3.4 Model Settings (AGT-4)

`GET|POST /assist/settings` reads and live-reconfigures the model tiers under the write-root gate, and
`POST /assist/settings/test` probes each tier so the pane can show per-tier connectivity rather than one
aggregate "AI is up". ⚠ In a stock bundle the pane's own routes are part of the 503 surface (§3.10).

### 3.5 Embedded intelligence (AGT-5)

Phases P0–P5 are complete (2026-07-21, plus polish), and the layer is organised as tiers:

* **P0 — the session spine.** A multi-turn agent with grounding through the context broker.
* **P1 — the investigation tier.** Analysis tools, the **Case store**, RCA and impact playbooks,
  autonomous triage, and a `goalKind` seam. ⚠ **This "Case" is not `INC`'s Case**: it is the agent's own
  investigation memory, a **256-entry capped ring** (`CaseStore`), and the similarity scoring behind it
  is `CaseSimilarity.score`.
* **P2 — the authoring tier.** Five drafting tools, completed when `kpi_report_builder` shipped
  2026-07-22.
* **P3 — the gated-action tier** (§3.6).
* **P4 — bounded autonomy (L3)**, with a durable policy carrying a **kill switch** and per-action-class
  mode and budget, plus an **autonomy ledger** recording what the monitor loop did, why, and what it
  spent. The loop is opt-in.
* **P5 — learning**, complete.

🔴 **All three optional tiers are off by default, and each is a `-D` switch**:
`-Dintelligence.triage.enabled`, `-Dintelligence.act.enabled` and
`-Dintelligence.opsmonitor.enabled` all default to `false`, and the policy engine is **inert until a
driver calls authorize** (an unconfigured action class reads as `OFF`, with kill-switch → mode → budget
precedence). ⚠ Note the irony this creates for the board: `AGT-5`'s edition cell says "L3 = S+",
but nothing in the code edition-gates L3 — it is exactly the **`-D` capability switch** that EDG-01
rejected as an edition mechanism, and the live plan puts L2 at Standard+ and L3 at Enterprise while the
original ladder gated L3 on Decision Rules the as-built policy engine does not use. Four sources, four
gatings, all moot while nothing is bundled (§3.10).

**The tool belt is 23 tools, and the canonical source is a test.** `InspectoPackTest` asserts
`assertEquals(23, tools.size())`, so the number is pinned even though no single enum lists it.
**Sixteen are non-mutating**, declared in `InspectoTools`: `glossary_lookup`, `docs_search`,
`status_get`, `signals_query`, `signal_timeline`, `timeline_build`, `diff_batches`,
`config_versions_diff`, `anomaly_scan`, `suggest_expectations`, `config_schema`, `component_draft`,
`query_author`, `projection_author`, `kpi_report_builder`, `pipeline_author`. **Seven mutate**, in the
action classes: `component_apply`, `component_rollback`, `job_run`, `pipeline_rerun`, `alert_ack`,
`schedule_apply`, `runbook_operator`.

🔴 **The count is stated six different ways across the docs** — 9, 18, 19, 21 (twice) and 23 — with
the concept page carrying four of them as it grew, and the two tools it never mentions at all
(`projection_author`, `config_schema`) being exactly the 21-to-23 gap. Quote the test, not a page.
⚠ A related miscount sits inside one paragraph of that page: the P2 heading says "5 of 5 tools" and
the sentence under it says "all four".

**The autonomy ladder** is the safety model: **Explain** (read) → **Draft** (write nothing, produce a
proposal) → **Act-with-approval** (L2, a human gate) → **bounded autonomy** (L3, opt-in, budgeted, with
a kill switch). L3 is documented as Standard+ and opt-in.

### 3.6 The gated write path

A mutating tool never applies its own change. The approval inbox plus a bridge make the upstream's
approval gate **non-headless**, so a human sees the request; the human-in-the-loop approval handler is
supplied **only when the act tier is opted in**, which means an un-opted deployment cannot approve
because there is nothing to approve with. The write itself lands through the ordinary component
registry, under the same write-root gate and `ConfigSpec` validation every human write uses — the agent
gets no privileged path. Two details make that real rather than aspirational. **A session header
(`X-Agent-Session`) sets `actor=agent:<session>` for attribution only and is never a capability
grant.** And the inline invoke round-trip **requires an already human-authored Decision Rule** — the
agent can simulate and then apply one, but it cannot conjure the rule.

⛔ **Two shapes here must not be "simplified".** Mutation is decided by `ToolSpec.mutating()`, never by
an allow-list of the known draft tools, so a newly added act tool is covered automatically. And a
runbook takes **one approval for its whole resolved plan**, because nesting gated calls **deadlocks**
the parked-thread gate — per-step approval is refused for that reason, not for taste. Consequence policy is deliberately narrow: the write path is `/apply`-only and
human-initiated, and a general event-triggered consequence gate is refused (§6).

### 3.7 The Signal-Backbone's agent half (S3–S7)

`OPS` owns the ledger and stopped at the envelope; this area owns the projections.

* **S3/S4 — AG-UI projection and the A2UI render channel.** `AgUiProjection` maps Signals onto the
  AG-UI shape (a thin edge adapter, "AG-UI-shaped, domain-named", never adopted wholesale). ⚠ **"AG-UI"
  here is this repo's own SSE + artifact protocol, not the third-party specification** — no such
  dependency exists.
  🔴 **The channel is half-wired, and the halves are the opposite way round from the backend note.**
  `AgentAskResult.artifact` has **no live producer** (the backend side is proven only by
  direct-construction tests), but the client **consumer is real and mounted**: `A2uiRenderComponent`
  renders an `A2uiArtifact` for both the Assist console and Agent Chat, fed by the stream's `artifact`
  frame, behind a **closed client allowlist** (`text`, `kpi`, `chart`, `data-table`, fail-closed). The
  Assist console even **synthesises** an artifact from a skill's prose when the server sends none. So the
  render path is exercised in production; what is missing is a server that emits the real thing.
* **S5 — the agent context fabric.** The context broker grounds an answer in real metadata rather than
  the model's memory. ⚠ **`SignalIngress` is tested but deliberately unwired**, and its severity floor
  (ERROR and above) differs from the context broker's (WARN and above) — the mismatch is the reason it
  was left unwired rather than an oversight.
* **S6 — gated agentic write** (§3.6).
* **S7 — reflex-layer unification and the editor resolver.**

🔴 **The run-claim hand-off rule governs every subscriber here.** `EventLog.emit` is synchronous on the
publishing thread, which holds that Pipeline's run claim, so a subscriber may do only a type check and a
bounded-queue offer before handing off to its own virtual-thread executor. **Never run an investigation
inline.** This is `OPS` §3.1's rule and it is load-bearing for the triage queue and the ingress.

### 3.8 The client surfaces

Two chat surfaces, deliberately separate, matching the two layers of §3.1:

| Surface | Route | Talks to | Shape |
|---|---|---|---|
| **Assist console** | `/assist` | `POST /assist/{intent}` | request/response over the seven intents; renders through the A2UI channel |
| **Agent Chat** | `/agent-chat` | `POST /agent/sessions`, then the session's `ask/stream` | **SSE streaming** — tokens append live into an `aria-live` region; a terminal frame carries `{kind, text, citations, navigationTarget, artifact}` |
| **Approvals inbox** | `/approvals` | `GET /agent/approvals`, `POST /agent/approvals/{id}/decision` | the L2 human gate: each parked mutating call shows its **dry-run preview** before a decision |
| **Autonomy dashboard** | `/autonomy` | `GET\|PUT /agent/policy`, `POST /agent/policy/kill-switch`, `GET /agent/actions` | the L3 controls: kill switch, per-action-class mode (`OFF`/`SHADOW`/`AUTO`) and hourly/daily budgets, plus the action ledger |
| **Model Settings** | `/model-settings` | `GET\|POST /assist/settings`, `POST /assist/settings/test` | provider + per-tier model ids, base URL, a **write-only** key reference, and per-tier probe results |
| **Diagnoses** | its own pane | `GET /assist/diagnoses`, then the diagnose intent | the one skill with a durable list behind it |

**Two inline components carry the rest of the AI surface area.**

* **`<inspecto-ai-explain>`** — the in-product glossary. A pane declares its canonical terms and the
  dialog resolves each through the tool route `glossary_lookup`, falling back to `docs_search` (with
  `{file, line, snippet}` citations) when a term has no entry. **No model is in the loop**, which is why
  it is safe on every pane. **Twelve panes** have adopted it — Alerts, Catalog, Collectors,
  Expectations, Jobs, the object mail view, Pipelines, Runs, Studio's Datasets, Link Analysis and
  Queries, and Tags — and Link Analysis was indeed the twelfth; **nothing has adopted it since
  2026-07-26**.
* **`<inspecto-ai-assist>`** — inline draft authoring, six non-mutating tools invoked through the tool
  route (with a natural-language `derive` mode). **Draft-only: applying is always the pane's own
  write.** Five live hosts: `pipeline_author` in the pipeline editor, `query_author` in the Query
  Library, `suggest_expectations` in the Expectation form, `kpi_report_builder` in the Dashboard
  Builder, `projection_author` in the Link-Analysis query panel. ⚠ **`component_draft` is the sixth tool
  and has no host**: it was adopted on the Components pane's `schema` kind and **retired 2026-07-31**
  when unification deleted that registry component. The adapter and the backend tool are intact, with
  nowhere to attach — which is exactly what the board's P3 row says.

**The inline explain-a-failure path** is a third component: a status dialog reached from Alerts,
Processing status, Events and the Incident/Case detail. On Events it prefers a `correlationId`, which
is what makes "what led to this" a causal chain rather than a single row.

**The gated write, as the client performs it** (§3.6's S6 half): a dry run first —
`POST /decision-rules/{target}/simulate`, which reports matched-of-total and mutates nothing — and then
a **separate, explicit** confirm that calls `apply`, threading the chat session id as a header so the
action is attributable to the conversation that proposed it.

### 3.9 eoiagent as a dependency (EOI-1..7)

Inspecto consumes a **narrow model-transport seam, not the full embed**. What matters here:

| Fact | Value |
|---|---|
| Maven coordinates | groupId `com.eoiagent`, version property `eoiagent.version` in the **parent** pom |
| Pinned version | **`0.2.0-SNAPSHOT`** |
| Upstream repository | **`jotder/inspect-agent`** (public), cloned by continuous integration |
| Distribution | **none** — no package registry; the upstream is built from source into the runner's local repository on every run |
| Java floor | the upstream targets **JDK 25**; this reactor targets **release 24** |
| The dry-run seam | `DryRunProvider` **is** present in the pinned artifact (verified in the local repository cache), which is what discharged the gate on 2026-09-08 |

Two consequences worth stating plainly. First, **the build is not reproducible from published
artifacts** — it is reproducible only from a branch head, so a change the pack needs must be pushed
upstream before this repo can consume it. Second, **the Java floor conflict is the mechanism behind the
packaging refusal** in §3.10: the board's own packaging row declines to add a bundling switch until the
JDK 25 versus Java 24 question is resolved.

### 3.10 What a shipped bundle actually contains

🔴 **This is the section that governs how every row in §2 should be read.**

`inspecto-agent`, `inspecto-agent-hosted` and `inspecto-intelligence` are **unconditional reactor
modules** — they compile and their tests run on every build — and the packaging table marks
`inspecto-agent` / `inspecto-intelligence` as bundled **never**, in both flavors. The core fat JAR
carries the two SPI *interfaces* (`com.gamma.assist.spi.AssistAgent`,
`com.gamma.intelligence.spi.IntelligenceAgent`) and **no implementor and no `META-INF/services` entry**,
so `ServiceLoader` finds nothing and the assist routes answer **`503`** — the documented absent-module
behaviour, the same pattern the editions board uses as its reference example.

**It cannot arrive by accident**: the core build step builds upstream dependencies only, and the agent
modules depend *on* the core rather than the other way round, so nothing pulls them in.

**A bundle without the agent is a valid deployment, not a broken one.** That is the intended default,
and the absence of a packaging switch is a *recorded refusal*, not an omission (§6).

⚠ **A latent drift lives in the settings file.** `AssistModelSettings` in the agent module owns
`config/assist-settings.properties`, and a **read-only core-side twin** re-parses the identical format
so the intelligence module can read it without compile-depending on the agent module. The two parsers
are kept in step **by comment convention, not by shared code** — a format change has to be made twice.

🔴 **The client, however, ships every AI pane in every bundle.** There is **no `features.assist` key**:
the bootstrap flags cover the exchange, geo/link, events and ops modules only, so nothing hides the
Assist, Agent Chat, Approvals, Autonomy or Model Settings nav entries. Each pane instead catches its own
`503` and renders an **explained** state rather than an error — an "AI assistance not installed" alert,
a disabled-and-explained button, or, in the Approvals inbox, a deliberate panel instead of a misleading
empty list. That is careful work, and it is the right behaviour for a per-call failure. But the net
effect in a stock bundle is **five navigation entries that can only ever explain their own absence**,
which is the same "affordance that exists to explain itself" shape the Studio spec flagged for the
investigation studios. The difference is that those hide; these do not.

So the honest reading of §2 is: `AGT-1` through `AGT-5` are **built, tested, and unreachable**. The
editions board is right (`CP-14` = `—/—/—`); the requirements board's `All` describes an availability no
customer has. `AGT-3`'s air-gap guarantee is the one row that is vacuously satisfied everywhere, because
what it promises is absence.

## 4. Decisions (dated one-liners)

| Date | Decision | Where |
|---|---|---|
| 2026-06-28 | The assist surface is an **SPI in the core with an optional implementor**, so a build without AI is a first-class deployment | `assist-agent.md` |
| 2026-06-28 | The reflex layer is **abstain-only**: no tier-bump, no human handoff, `applyVia` always `null` | `assist-agent.md` |
| — | `GET /assist/metrics` returns **counts only, never data values** | `assist-agent.md` |
| 2026-07-07 | **The external agent-kernel is discontinued**: vendor its reasoning layer in-tree as `com.gamma.agent.kernel.*` and adopt the upstream as **model transport only**, because the upstream has no capability, confidence, abstain or grounding machinery | operator, same day as the assessment |
| 2026-07-07 | **Keep the `agentkernel.*` system-property names** through the rename, for deployment compatibility | replacement plan |
| 2026-07-07 | AGT-5 P0 signed off with two accepted cuts — **QA-only** (no incident explanation) and **local models only** | product owner |
| 2026-07-19 | P1 decisions: `goalKind` rides as an optional **session attribute** rather than a direct orchestrator call; the Case store starts **in-memory**; triage rides the **canonical Signal bus, never the legacy batch bus**; quality Signals are added **additively** at their evaluation sites; **"the model judges, tools compute"** — anomaly scanning is deterministic math; fix drafts land as DRAFT components through the existing path with an agent actor | AGT-5 P1 plan |
| 2026-07-21 | Autonomy is **opt-in per action class**, an unconfigured class reads `OFF`, precedence is kill-switch → mode → budget, and the engine is **inert until a driver calls authorize** | — |
| 2026-07-21 | Case similarity is **Jaccard token overlap**; embeddings **not warranted** at a 256-entry corpus | — |
| 2026-07-22 | `kpi_report_builder` ships with ad-hoc aggregation/field measures and **no server-side named-Measure resolver** | operator sign-off |
| 2026-07-25 | **AGT-6 split**: 6a promoted to a schedulable Should, 6b left a demand-gated Could | operator, in session |
| 2026-07-26 | Inline authoring decisions: one **shared standalone surface, adopted never forked**; **apply goes through the plain validated route** so the *human* is the audited actor; **no edition gate**, because draft-only carries no security weight; **deterministic derive first**, no model in the loop | operator |
| 2026-07-27 | ⚠ **D3's amendment, which the row above states in its superseded form.** A5 reintroduces local inference, so "there is not even a local-inference cost" no longer holds for the NL path. The *conclusion* stands — no edition gate, available wherever the module is, air-gapped included, because local models remain sufficient (the AGT-3 claim) — but the **503-on-stub degrade is what now keeps that promise honest** on a build with no model configured | operator |
| 2026-07-28 | **The `kpi_report_builder` host shipped as an ADOPTION** (`3750a87b`), inverting its own blocking premise: the tool builds the measures, so the host pane need only supply a dataset. ⚠ Recorded here 2026-09-09 — the board, this spec §5.1 and `inline-ai-authoring.md` all read "no viable host pane" for 43 days afterwards, and that stale row was the stated reason `agt-6-plan.md` stayed out of the archive | `inline-ai-authoring.md` |
| 2026-07-26 | The tool route's gate order is fixed: **503 → 404 → mutating 403 → 422 → 200**, and **a draft carrying findings is a 200, not an error** | shipped |
| 2026-07-27 | Containment **is the request shape**: exactly one non-mutating tool is offered, the merge is **schema-keyed and never a blind copy**, and the **pane's own arguments are applied last and win** | shipped |
| 2026-07-27 | The repair loop is capped at **three turns**, then hands over the **fewest-findings** draft — the cap is a hand-over, not a failure | answered |
| 2026-07-27 | Build the spec-to-JSON-Schema projection and a read tool for it, but **do not** use it to tighten the drafting tool's own input schema — a validator must be able to receive a malformed draft | answered |
| 2026-07-31 | The drafting affordance was **removed with the retired `schema` registry kind** rather than left to answer "no structural spec for this kind" | — |
| 2026-09-06 | The 28 outstanding operator decisions for this area were **answered in one sitting**, each recorded in its owning doc | operator |
| 2026-09-08 | The old gate probe was **unfalsifiable by construction** (zero hits even for a control term) and was replaced with a git-tree probe — which is how the dry-run gate was found to be dischargeable | gate sweep |
| 2026-07-07 | AGT-5 P0 signed off; the deliberative layer is **distinct from the reflex agent**, not an upgrade of it | `embedded-intelligence.md`; `REQUIREMENTS` `AGT-5` |
| 2026-07-08 | `v0.1.0` cut upstream and pinned, trunk bumped — ⚠ since regressed to a `0.2.0-SNAPSHOT` pin (§2) | `REQUIREMENTS` `EOI-7` |
| 2026-07-19 | **"AG-UI-shaped, domain-named"** — AG-UI is a thin edge adapter, never adopted wholesale | `signal-backbone.md` |
| 2026-07-21 | AGT-5 **P1–P5 complete**, plus P4 polish (a second pilot class and a periodic state watch) | `embedded-intelligence.md` |
| 2026-07-22 | `kpi_report_builder` shipped, completing the P2 authoring tier | `embedded-intelligence.md` |
| 2026-07-25 | The parent embedded-intelligence plan **archived**; remaining items declared **deliberate deferrals, not gaps** | `embedded-intelligence.md` §Still open |
| 2026-07-25 | **AGT-6a scoped** — inline authoring must reuse the shipped draft tools and add **no new backend capability** | live plan `archived-documents/plans-archive/agt-6-plan.md` §3 |
| 2026-07-26 | **A1 shipped — deterministic single-tool dispatch (`runTool`)**: a named tool is invoked directly rather than composed by the model | `embedded-intelligence.md` §AGT-6a A1 |
| 2026-07-28 | The `projection_author` "stale `columns.items`" half fixed, retiring that clause of the AGT-6a host row | `BACKLOG.md` §3 |
| 2026-08-01 | The run-claim hand-off seam moved to `PipelineRunGuard`; **never investigate inline** | `embedded-intelligence.md`; `OPS` §3.1 |
| 2026-08-31 | Embedding-retrieval upgrade assessed **not warranted** at a 256-entry corpus; the drop-in seam is preserved behind the similarity score | `embedded-intelligence.md`; `BACKLOG.md` §6 |
| 2026-09-08 | **AGT-5's external gate discharged** — the upstream per-tool dry-run seam shipped, so the row moved from externally-gated to actionable, and `AGT-6b`'s stated prerequisite is met | `BACKLOG.md` §2 → §3 |
| standing | **Agent-absent is the intended shipped default**, and ⛔ no packaging switch until the Java floor conflict is resolved | `BACKLOG.md` `PKG-5`; `build-test.md` |
| 2026-09-08 | This spec: every `AGT` edition cell corrected to "no bundle ships it"; `EOI-7`'s "no SNAPSHOT anywhere" refuted against the parent pom; the four-way version drift and the dead upstream path recorded | this file §2 |

## 5. Not built

### 5.1 Tracked (a `docs/BACKLOG.md` row exists)

| Item | Row |
|---|---|
| **`EOI-7b`** — publish the upstream artifacts to a registry; closes when continuous integration no longer clones and builds the upstream from source | §2, checkable in one grep |
| **`AGT-6b`** multi-step agent graphs — first cut must generalise the seeded runbook actions, ⛔ never free-form reasoning over mutating tools. Its gate was re-run 2026-09-08 and holds on its second precondition. ✅ **No new operator surface is required**: the plan appears in the existing `/approvals` inbox as a resolved step list with per-step previews, and `/autonomy` already records what/why/spend — a scoping fact that was only in the archived plan §5 | §2 |
| **`AGT-5` per-tool dry-run seam** — gate discharged 2026-09-08, now actionable: let the framework populate the approval preview | §3 P2 |
| **AI drafting has no applicable component kind** — restoring inline draft for a kind needs a backend `ConfigSpec` that none of `grammar`/`transform`/`sink` has | §3 P3 |
| **`AGT-6a` tool `args` runtime validation** — declined in favour of a contract test; revisit when the belt is bigger | §6 |
| **`AGT-5` embedding recall** — parked; the Case store is a 256-cap ring | §6 |
| Predictive maintenance (AGT-5 territory) deliberately deferred from the maintenance tier | §3 P3 |
| Hosted providers (Standard+) and the optional S8 signal slice | §3 P3 |

### 5.2 UNTRACKED — found 2026-09-08, no board row yet

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `CONSUMER-PAIRS-1`, `SPEC-STALEREF-1`, `SPEC-COUNTS-1`, `SPEC-DEADSEAM-1`, `SPEC-GLOSSARY-1`, `SPEC-MOCKRESIDUE-1`, `SPEC-PLANSTALE-1`, `SPEC-ORPHANPAGE-1`, `SPEC-AGT-EDITIONS-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`superpower/post-consolidation-sprints.md`](../../../superpower/post-consolidation-sprints.md) §Sprint 2.

1. 🔴 **The whole area's edition cells are wrong and nothing tracks the reconciliation.** Seven `AGT`
   rows claim `All`; no bundle carries the code. Either the rows say "not bundled", or a packaging
   decision changes that. The editions board already says the truth, so this is a requirements-board
   repair plus a product decision on whether AI is ever meant to ship.
2. 🔴 **`EOI-7`'s claim contradicts the build.** "No SNAPSHOT anywhere" versus a parent pom pinning
   `0.2.0-SNAPSHOT` of an unpublished upstream. The row needs rewriting to the actual state, and the
   reproducibility question — a build that depends on a moving branch head — deserves its own row.
3. 🔴 **`docs/okf/agentic/` points at a local path that does not exist** and calls it the home of the
   authoritative documentation, in a tier of six current files. Its stated version is two releases
   behind the pom. Either the tier is re-pointed at the public repository and re-stamped, or it is
   demoted to history.
4. **The concept page's tool inventory is four counts behind and omits two tools.** The belt is 23,
   pinned by a test; the page says 9, 18, 19 and 21 in different sections and never mentions
   `projection_author` or `config_schema`. A page that grows by appendix accumulates counts instead of
   correcting them.
5. **`AgentAskResult.artifact` has no producer, while the client consumer is live.** The render path is
   mounted on two surfaces and the Assist console synthesises artifacts to fill the gap. Either wire a
   real producer or record the channel as reserved — but note the asymmetry, because the backend
   concept page reads as if the whole channel were unproven.
6. **`SignalIngress` is tested and deliberately unwired**, on a severity-floor mismatch. That is a
   decision, but it is recorded only in a concept page's gotcha list; it should be a row or a stated
   refusal.
7. **The word "Case" means two different things one layer apart** — the agent's 256-entry investigation
   ring and the operational Case object `INC` owns. The glossary does not disambiguate them, and this
   is exactly the one-word-two-concepts collision the vocabulary rules exist to prevent.
8. **The Java floor conflict has no owner.** It blocks the packaging switch, it is stated in a
   packaging refusal and in the upstream's own overview, and no row tracks resolving it.
9. **Two `Tool` systems coexist with nothing bridging them** and no doc that states, for a new
   contributor, which side a new tool belongs on.
10. 🔴 **The SPA ships five AI nav entries with no feature flag to hide them** (§3.10). Either publish a
    bootstrap flag the way the other four optional modules do, or accept the entries as permanent
    self-explaining stubs. A product decision, and cheap either way.
11. **`AssistDialog` is dead code with a doc comment describing an unwired flow** — it claims to be the
    Jobs pane's schedule-from-text entry point and has **zero call sites**; Jobs uses the explain
    component instead. So `nl-to-schedule` is reachable only from the console, not from the pane where
    a schedule is actually authored.
12. **`model-settings.md` says the pane is "backed by `ConfigService`"; it uses the assist service.**
    No such wiring exists. A one-line doc fix, listed because it is the kind of claim a reader trusts.
13. **`inline-ai-authoring.md`'s "Offline" section describes the deleted mock backend** — the same
    repo-wide residue the Studio spec filed. This area is a second confirmed instance.
14. **Nothing has adopted the in-product glossary since 2026-07-26.** Twelve panes carry it and the rest
    do not; either the remaining panes are a backlog item or twelve is the intended set.
15. 🔴 **The agent's governance controls appear in no compliance control.** Exactly one control cites
    the AI layer at all, and it is about the core staying lean. **Nothing** cites the approval gate, the
    dry-run preview, the act-tier switch, the kill switch, the budgets or the agent actor attribution —
    although a named project risk cites precisely those as its mitigation. The evidence files do mention
    the agent; the matrix does not.
16. 🔴 **A vocabulary slip lives in the one string the model reads.** A drafting tool takes and
    returns an argument named for the banned synonym of Pipeline. It is tracked as accepted debt in the
    guard's allow-list and in the glossary's own contract list, but the live plan simultaneously
    requires canonical vocabulary "in every label and prompt". Dual-accepting the canonical name is the
    fix the debt note itself anticipates.
17. **A claimed glossary exception does not exist.** The live plan says the `schema` collision "is now
    recorded as an explicit exception in the glossary"; there is no such entry. Either add it or stop
    citing it — a documented exception that is absent is worse than an undocumented one.
18. **Two archived plans are the sole home of decisions nothing cites.** The P1 investigation plan holds
    its seven decisions verbatim and is referenced by no current doc; the A2UI spike declares itself the
    detailed design reference for the render host, wire format and security model and is likewise
    orphaned. §4 and §3.7 of this file are now the only current-tier route to them.
19. **A dead archive pointer**: an archived plan cites a reflex-layer history file that does not exist.
20. **Three user-facing documents promise a working Assistant.** The user guide describes the pane as
    working with a "disabled" fallback, when the fallback is the only reachable branch in a shipped
    bundle; the stakeholder overview lists the skills as shipped with hosted model routing; and the
    advanced guide's route inventory lists three agent routes and omits the tools, approvals, policy,
    actions and feedback routes. All three need the §3.10 caveat.
21. **The roadmap still tells a reader to adopt the discontinued agent library**, two months after it
    was vendored in-tree.
22. **Hosted providers are simultaneously deferred and refused.** They are listed as a Standard+
    deferral, while the intelligence agent runs on an offline deployment profile in which a hosted
    provider falls back to an offline stub. One of the two statements has to go.

## 6. Refused & superseded

| Item | Verdict | Why / where |
|---|---|---|
| Bundling the agent modules in any artifact | **Refused — the intended default** | agent-absent is a valid deployment; ⛔ no packaging switch until the Java floor is resolved (`PKG-5`) |
| A tier-bump or human handoff from the reflex layer | **Refused by design** | abstain-only; `applyVia` is always `null` |
| Data values in `GET /assist/metrics` | **Refused** | counts only |
| Wholesale AG-UI adoption | **Refused 2026-07-19 (D3)** | thin edge adapter only, domain-named |
| Wholesale replacement of the Assist panel by A2UI | **Superseded** | lossy — the render-kind allowlist has no code, chip or header kind; only faithful branches were converted |
| Free-form reasoning over mutating tools for `AGT-6b` | **⛔ Refused** | the first cut must generalise the seeded runbook actions |
| A general event-triggered consequence policy gate | **Refused for now** | the write path stays `/apply`-only and human-initiated |
| An embedding-retrieval upgrade for Case recall | **Assessed not warranted 2026-08-31** | a 256-entry corpus does not justify it; the seam is preserved |
| Runtime validation of tool `args` | **Declined** | a contract test instead; revisit when the belt is larger |
| Migrating the legacy batch event bus onto the Signal bus | **Refused** | the run-claim rule makes it hazardous; the two buses stay separate |
| Wiring `SignalIngress` | **Deliberately not done** | its ERROR-and-above floor differs from the context broker's WARN-and-above |
| Per-step Signals from the agent's own work | **Refused** (the `OPS` decision applies here) | a Signal is a durable write; gauges do not need one |
| The discontinued agent-kernel as a dependency | **Superseded** | vendored into `com.gamma.agent.kernel.*`; eoiagent supplies model transport |
| An allow-list of the five draft tools instead of asking `ToolSpec.mutating()` | **⛔ "Never simplify this"** | a newly added act tool must be covered automatically |
| Per-step approval inside a runbook | **Refused** | nesting gated calls **deadlocks** the parked-thread gate; one approval covers the resolved plan |
| Routing the inline natural-language hop through the deliberative ask route | **Refused** | that result shape cannot carry a draft |
| Folding prose into a tool's `args` as an instruction key | **Refused** | it pushes the parse into the tool and breaks the deterministic property all five rely on |
| A fourth inline AI component for the natural-language mode | **Refused** | it is a **mode**, not a sibling |
| Prompt-then-scrape for argument derivation | **Refused** | native function calling already exists; the scrape was the wrong precedent |
| Tightening the drafting tool's own input schema | **Refused** | it is a validator — constraining its input would destroy the repair loop |
| Runtime validation of tool arguments against the declared schema | **Declined** | a contract test instead; both known defects were "schema wrong, code right", and enforcement would turn doc bugs into outages |
| Completing the drafting adoption onto the other component kinds | **⛔ Refused** | none has a structural spec, so every use would fail |
| Gating the glossary and status components on the authoring capability | **⛔ "Do not make it consistent"** | neither has a write path, and a Business-lens reader needs the vocabulary most |
| Fine-tuning or training a model | **Refused** | off-the-shelf instruct models plus retrieval and constrained decoding instead |
| Inventing a pipeline-to-table lookup, or correlation ids on synthetic events | **⛔ "Do not invent"** | a 422 is the expected answer |
| Publishing the upstream to a registry | **Open, not refused** | `EOI-7b` — the registry choice is undecided, and the repo publishes no Maven artifacts at all |

## 7. As-built pointers

| Concern | Code | Docs |
|---|---|---|
| Assist SPI + routes | `inspecto/src/main/java/com/gamma/assist/spi/AssistAgent.java` | [`assist-agent.md`](../../backend/agent/assist-agent.md) |
| Reflex agent + skills | `inspecto-agent/src/main/java/com/gamma/agent/UccAssistAgent.java`; `inspecto-agent/src/main/java/com/gamma/agent/skill/AlertRuleTool.java`, `SqlOracleTool.java` | same |
| Hosted providers (quarantined) | `inspecto-agent-hosted/` | [`hosted-providers.md`](../../backend/agent/hosted-providers.md) |
| Deliberative agent | `inspecto-intelligence/src/main/java/com/gamma/intelligence/InspectoIntelligenceAgent.java`, `GatewayFactory.java` | [`embedded-intelligence.md`](../../backend/agent/embedded-intelligence.md) |
| The tool belt | `inspecto-intelligence/src/main/java/com/gamma/intelligence/pack/InspectoTools.java`, `ArgumentDeriver.java`, `GlossaryLoader.java` | same |
| Gated actions | `inspecto-intelligence/src/main/java/com/gamma/intelligence/action/ComponentActions.java`, `OperationalActions.java` | same §P3 |
| Signal projections | `inspecto-engine/src/main/java/com/gamma/signal/AgUiProjection.java`, `Signals.java` | [`signal-backbone.md`](../../backend/control-plane/signal-backbone.md) §S3–S7 |
| Packaging truth | `pom.xml` (`eoiagent.version`, `maven.compiler.release`), `package.ps1`, `.github/workflows/ci.yml` | [`build-test.md`](../../backend/build-run/build-test.md) §bundling; `EDITIONS.md` `CP-14` |
| Client surfaces | `inspecto-ui/src/app/modules/admin/assist/`, `agent-chat/`, `approvals/`, `autonomy/`, `model-settings/`; `inspecto-ui/src/app/inspecto/ai-assist/`, `inspecto-ui/src/app/inspecto/a2ui/` | [`assist.md`](../../frontend/features/assist.md), [`model-settings.md`](../../frontend/features/model-settings.md), [`inline-ai-authoring.md`](../../frontend/features/inline-ai-authoring.md) |
| The upstream (black box) | groupId `com.eoiagent`, repo `jotder/inspect-agent` | [`okf/agentic/`](../../agentic/index.md) — ⚠ stale, see §2 |

**Gap rows.** The reflex layer has **one 1.9 KB concept page** for seven skills, an SPI, five routes and
a confidence estimator, while two files carry 68% of the area's bytes (the deliberative concept page and
the inline-authoring feature page, ~71 KB between them). The imbalance is inverted relative to where the
risk is, because the reflex layer is the one with the older live surface. There is **no page for the
two-tool-systems distinction**, the single most likely thing for a new contributor to get wrong. Two of
the area's sixteen files carry **no `type:` frontmatter**. And the six-file upstream tier describes a
dependency the build no longer matches (§2).

**Archive cited as authority** (history tier, never maintained): the embedded-intelligence plan (the
phasing record and the P0 scope cuts), the agent-kernel replacement plan and the platform assessment
(the 2026-07-07 substrate decision), the event/signal backbone plan (the S0–S7 record), the edition-gating
census, and the Postgres plan (the parked Case-store interface). 🔴 **Two more are load-bearing and
cited by nothing**: the P1 investigation plan, sole home of that phase's seven decisions, and the A2UI
spike, which declares itself the design reference for the render host, wire format and security model.
One archived plan also cites a reflex-layer history file that **does not exist**.

## 8. Verification

* **Pointer check** — a capability-pointer check over this file (`tools/check-doc-citations.mjs` — **committed 2026-09-09**, wired into `ci.yml` and `.githooks/pre-push`). **One MISSING hit
  is expected and correct:** `DryRunProvider` is an **upstream** class, so it is absent from this tree by
  definition — the verifier indexes this repository only. Confirm it in the resolved artifact, not here.
* **The invariant that matters most** is the air-gap test: it asserts that hosted SDK classes are absent
  from the build rather than merely disabled. Run it before believing any air-gap claim.
* **Falsify the packaging claim, don't read it.** Build a bundle and probe it: `POST /api/v1/assist/explain`
  must answer `503` naming the absent module, and `unzip -l` on the artifact must show no
  `com/gamma/agent/` or `com/gamma/intelligence/` classes. If either changes, §3.10 and every edition cell
  in §2 change with it.
* **The upstream is a moving target.** Because the pin is a snapshot rebuilt from a branch head, a green
  build today does not prove a green build tomorrow. Re-resolve before trusting a prior result, and
  treat "the upstream has the seam" as a claim to grep in the upstream, not to infer from this repo.
* **Guards** — `node tools/check-vocabulary.mjs`, `node tools/check-doc-links.mjs`,
  `node tools/check-gate-tally.mjs`, from the repo root. ⚠ Stage a new doc (`git add -N`) before
  trusting a green vocabulary run: the local guard reads tracked files only.
