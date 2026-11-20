# Assistant — help on the screen you are on (plan)

> **Status: PROPOSED 2026-11-20 — D-SH1…D-SH3 open (§6); nothing built.** Phase 4 (the **helpdesk** role) of the
> agent roles in [`2026-11-20-agent-kit-ak2-plan.md`](2026-11-20-agent-kit-ak2-plan.md) §1.1. Unlike phases 1–3 it is
> the in-product **Assistant**, not the Agent Kit (`GLOSSARY.md` keeps them apart), and it has no write path. It
> depends on no earlier phase. Product Map Items: `module.intelligence`, `module.agent`, `layer.product`. Inventory
> checked against `origin/master` on 2026-11-20.

## 1. The goal

A user on any screen, looking at any Widget, Dataset, Run, Incident or Case, asks the Assistant *what am I looking at,
why does this show that, what do I do next* — and gets an answer that is:

- **about this object**, not the product in general: the screen, the object on it, and its live facts;
- **grounded in the Handbook**, with the page it came from linked, so the user can read on;
- **within the user's own rights**: it sees what the user sees, masked as the user sees it, and nothing else;
- **honest**: it says "I don't know" rather than invent, as the Assist agent already abstains.

Done means: on the screens of the builder tutorials (T1–T4) and the 30 how-tos' screens, the Assistant answers the
golden questions of §5 correctly from the user's context, cites a Handbook page each time, and never reveals a masked
value or an object the user cannot open.

## 2. What exists today

| Piece | Where | What it does | What it lacks |
|---|---|---|---|
| Explain dialog | `inspecto-ui/src/app/inspecto/ai-assist/ai-explain.dialog.ts`, the `?` beside a title on 23 screens (each passes `[terms]` to `inspecto-page-header`: Collectors, Expectations, Runs, Pipelines, Datasets, Queries, Jobs, …) | the screen passes its **name, its terms and optionally its own definitions** (which win); other terms resolve through `glossary_lookup`, else `docs_search` over user-facing docs | no object, no live facts, no question from the user; answers terms only. **The two lookups are Intelligence-agent tools** (`InspectoTools`, served at `POST /agent/tools/{name}`, 503 without the module), so beyond a screen's own definitions the dialog explains nothing below Advanced |
| Assistant page | `/assist`, the shared `assist-panel` | free questions to the Assist agent | does not know which screen or object the user came from |
| Assist agent | `features/inspecto-agent` (manifest id `agent`) | explain, diagnose, draft and triage skills; read-only or draft-only; abstains below confidence | grounding is the repository docs search, not the Handbook |
| Intelligence agent | `features/inspecto-intelligence` (manifest id `intelligence`, only in the Advanced Offering) | multi-turn sessions, tool calls, the approvals gate; **`/agent/sessions` and `…/{id}/ask` already accept a `page` context** (`pageId`, `entityIds`, `filters`) that `ContextBroker` renders as the session's focus | the chat page (`/agent-chat`) sends only its own route, under a key the broker ignores; no screen sends `pageId` / `entityIds`; Advanced only |
| Handbook links | `help-link` in the page header (a **book** icon — the `?` is the explain dialog), `/help`, the Handbook manifest's chapters | opens that screen's Handbook chapter | the Assistant does not read the Handbook as its source |

## 3. Design

### 3.1 The screen context

Each screen supplies a small, typed **screen context** when the user opens the Assistant from it: the screen id (its
route), the object kind and id when one is open (Widget, Dashboard, Dataset, Pipeline, Run, Incident, Case), and the
filters in force — the shape the Intelligence agent's session `page` map already takes (`pageId`, `entityIds`,
`filters`), which this plan reuses rather than adding a second one. The context carries **ids only, never values**. The server resolves the facts from the ids under the
user's own Subject, by the same routes the screen itself reads — so the rights, the Space scope and the masking are the
screen's, and the Assistant cannot see what the user cannot open.

| Object | Facts resolved (read routes the screen already uses) |
|---|---|
| Widget | its Visualization Type, its query or Measure, its Dataset, the Dataset's freshness, the last value shown |
| Dataset | its schema, row count, freshness, the Pipeline that writes it, its Expectations and their latest results |
| Run | its state, timings, failure reason, rejected and quarantined counts |
| Incident / Case | its fields, history, Briefs, linked Alert and Expectation |

### 3.2 Grounding in the Handbook

The Assistant's source of explanation is the **Handbook's external pages** (the manifest, `audience: external`), not
the repository's docs: the screen's own Handbook chapter first (the page its book link opens), then a search over the
external pages. Every answer cites the page(s) used. The explain dialog's fallback `docs_search` moves to the same
corpus, which retires its hand-kept filter of user-facing docs.

### 3.3 Where it shows

The explain dialog grows a question box: *Ask about this screen*. The agent chat page (`/agent-chat`, which
already opens sessions) accepts the same screen context when opened from a screen, through the existing session `page`
map. A Widget's menu gains *Explain this Widget*. The server side is the existing `/agent/sessions` path plus the
resolve of §3.1 inside `ContextBroker`; no new route. The `/assist` page (the Assist agent, `POST /assist/{intent}`)
would need a context field on `AssistRoutes` to do the same — left out unless D-SH1 puts screen questions below Advanced.

### 3.4 What it never does

No write, no tool that changes state, no reveal, no answer about an object outside the context the user is in.
Anything the user wants **done** is a link to the screen or how-to that does it.

## 4. Phases

| Phase | Delivers | Proof |
|---|---|---|
| **SH-1** Handbook grounding | the Assistant and the explain dialog search the Handbook's external pages and cite them; the lookup reaches the editions D-SH1 names | the explain dialog's terms on its 23 screens resolve to Handbook pages; no internal page is ever cited (guard) |
| **SH-2** screen context | the context contract, the server-side resolve under the user's Subject, *Ask about this screen* on the explain dialog | golden questions on the T1–T4 screens; a masked column stays masked; an object of another Space is not resolved |
| **SH-3** Widgets and objects | *Explain this Widget*, the Run, Incident and Case contexts | golden questions per object kind (§5) |
| close | a Handbook how-to *Ask the Assistant about a screen*; a security review of the context resolve | review at confidence ≥ 8 finds nothing |

## 5. The golden questions (evaluation)

Per object kind, a fixed set asked on a seeded `order-to-cash` install, graded by the platform's own state, as the
Agent Kit's golden tasks are: *What does this Widget show?* · *Why is this number different from yesterday?* · *Why did
this Run fail?* · *What should I do about this Incident?* · *What does this column mean?* A pass cites a Handbook page,
states only facts the user's own routes return, and abstains where the facts do not answer the question.

## 6. Decisions — open

| # | Question | Recommended |
|---|---|---|
| **D-SH1** | Which editions get screen help? | **Term and Handbook lookup (SH-1) on every edition that ships the Handbook** — it needs no model, so it moves out of the Advanced-only Intelligence agent into a plain read that serves the same answer; **questions about the screen (SH-2, SH-3)** wherever the agent modules ship (today the session `page` context is Advanced only; check at build which Offerings carry `agent`). |
| **D-SH2** | Does the Assistant see data rows, or only metadata? | **Metadata and the aggregates the screen already shows** (counts, the Widget's own values, freshness), never raw rows — a raw-row question links to the Query screen. |
| **D-SH3** | Which model, and offline? | The model the Space's model settings name, as today; a local model for air-gapped installs, with SH-1 working without any model for glossary terms. |

## References

- Agent roles and phases: [`2026-11-20-agent-kit-ak2-plan.md`](2026-11-20-agent-kit-ak2-plan.md) §1.1.
- Handbook: [`2026-11-19-user-enablement-and-agent-kit-plan.md`](2026-11-19-user-enablement-and-agent-kit-plan.md) §4.
- Assistant today: [`user-guide-assist.md`](../okf/product/module/intelligence/user-guide-assist.md),
  [`features-ai-assist.md`](../okf/product/module/intelligence/features-ai-assist.md).
