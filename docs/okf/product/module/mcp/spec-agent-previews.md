---
type: Spec
title: Agent authoring previews (AGENT-KIT-1 W6 group A) — design and as-built
description: Which authoring previews an AI agent may call through the MCP endpoint or directly (parsing, schema casts and mapping, schema suggestion, mapping check), why each is safe for every agent, the body cap, the no-file-reference rule, the sealed schema preview, the preview_config MCP Tool and the tests that prove it.
tags: [agent-kit, mcp, agents, authoring, security]
timestamp: 2026-11-26T00:00:00Z
item: module.mcp
audience: internal
status: current
verified: 2026-11-26 code-reviewed
code: features/inspecto-mcp
---

# Agent authoring previews

Widening plan `docs/superpower/2026-11-25-agent-kit-ak2-widening-plan.md` § W6, group A; decision **D-AK10**
(signed 2026-11-25): the authoring previews are open to **every** agent, a person's and a machine's. An agent tries
its draft against the server's own parser, casts and mapping until it is clean, then proposes once
(`propose_change`), instead of proposing and reading the refusal. Groups B to E (simulators, Space tests, analytic
reads, analyst previews) are separate changes, each with its own review.

## The four routes

All four are `read-shaped` exemptions in `CapabilityManifest` (no capability) and sit in `AgentGate.AUTHORING_PREVIEWS`,
part of the allowed bucket ([The agent gate](spec-mcp.md#the-agent-gate-ak-2a)). None is person-only.

| Route | What it does | Why it persists nothing |
|---|---|---|
| `POST /config/preview/parsing` | parses raw `sample_text` with a Pipeline draft's `parsing:` settings | a throwaway DuckDB and a temp sample file, both deleted in `finally` |
| `POST /config/suggest/schema` | infers typed fields from parsed `sampleRows` (and the drift from a posted draft) | a throwaway DuckDB |
| `POST /config/preview/schema` | casts `sampleRows` against a schema draft and runs its mapping rules | a throwaway DuckDB, **sealed** (below) |
| `POST /components/mapping/validate` | checks a mapping's `rules` | pure validation, no I/O |

## The three bounds, for an agent

Applied in `ControlApi.handle` on every door (a direct request and every in-process replay, so the MCP tool too)
whenever the Subject is an agent and the route is one of the four; a person's own token is unchanged.

1. **Body cap.** At most `AgentGate.PREVIEW_MAX_BODY_BYTES` (256 KiB, the MCP endpoint's own cap), read once with
   `rawBody(ex, cap)` before the handler parses it: over it is **413**, refused on the declared `Content-Length` or after
   256 KiB + 1 bytes. A person keeps the route's own limits (`sample_text` up to 1,000,000 characters).
2. **No file named by the draft.** The handler runs inside `PathJail.withoutConfigRefs`: every
   `PathJail.resolveConfigRef` — the one method a Pipeline parse resolves a `schema_file`, `schemas[].schema_file`,
   `mapping_file`, `grammar`, `ingester_config.grammar` or Decode Profile ref through — throws before the path is
   resolved, probed or read, and the route answers **422** "… names a file: an agent's preview draft names no file".
   A missing and an existing file get the same answer, so the refusal is no existence oracle. Data paths (`dirs`,
   a join's `reference`) are resolved, never read, by a parse, and stay allowed.
3. **Sealed SQL (every caller).** `ComponentPreview.schema` runs in a `SqlSandbox`, sealed after the sample rows are
   seeded (`enable_external_access=false`, `lock_configuration=true`), as `ComponentPreview.transform` already did. An
   `EXPR` mapping rule is the author's SQL emitted verbatim: before the seal, `(SELECT content FROM
   read_text('<path>'))` in a rule returned any readable file in `mappedRows`, to anyone who could call the route
   (which needs no capability). Sealed, it is a 422 for everyone. The parsing preview stays unsealed — it must read its
   own temp sample file — and every draft value it puts into SQL is quoted (`SqlIdent.sqlStr` / `q`).

**Why `POST /lookups/test` is not one (operator, 2026-11-26).** The plan listed it in group A, and the lane's security
review refused it: it returns the matched Reference row's columns unmasked, a fuzzy match also lists `candidates` — keys
the caller never sent, so repeated calls enumerate the Reference — and it has no per-caller rate limit, while
`refresh: true` rebuilds an in-memory index of up to 256 MiB on every call. A Reference can be a Pipeline's own output
store. It stays refused to every agent (a person's own token is unchanged) until masking, a bounded candidate list and
a rate limit are built and proven: BACKLOG `AGENT-LOOKUP-PREVIEW-1`.

## The MCP Tool

`preview_config` (read, `readOnlyHint: true`) — arguments `kind` (`mapping`, `parsing`, `schema`,
`suggest-schema`) and `body` (the route's own JSON body); `McpTools.PREVIEWS` maps each kind to its route. A refusal
comes back as the route's status (`isError`, "HTTP 413", "HTTP 422 … names a file").

## Skills and golden tasks

- `author-pipeline` and `safe-config-change` say **preview, then propose**: parse a pasted sample, read the suggested
  schema, and check the casts and the mapping before `dry_run_pipeline` and `propose_change`.
- Developer golden tasks `dev-12-schema-from-sample` (the suggested types of an inline sample) and
  `dev-13-cast-rejects` (how many rows of an inline sample a schema draft rejects: 3, the ground truth
  `ControlApiAgentPreviewsTest` asserts on the same rows).

## Tests

- `ControlApiAgentPreviewsTest` (real HTTP): a person's agent and a machine agent each get every preview's own 200, and
  a before-and-after read (every file under the Space, the Pending Changes, the Pipelines) is unchanged; a body over the
  cap is 413 on all four routes for both, while the person's own token is not capped; a draft naming an existing or a
  missing file is 422 "names a file" for both agents while the person's own token reads it; the EXPR `read_text` probe
  reads nothing for the person or the agent.
- `AgentGateParityTest.theAuthoringPreviewsAreAllowedToEveryAgent`; `McpToolRoutesTest` (every `preview_config` kind
  replays a manifest route the gate allows a person's agent); `ControlApiMcpWriteToolsTest` (the tool end to end, and
  the file-naming draft through it).
- **Falsified** (each negative test turned red with its guard removed, then restored): the body cap off; the
  `withoutConfigRefs` scope off; the schema preview unsealed; the four routes out of `ALLOWED` (the 200 test is 403).

## Security review

Scoped to what group A adds, by the lane's `verification` subagent before integration (2026-11-26); its findings and
their resolution are recorded in the lane's report.
