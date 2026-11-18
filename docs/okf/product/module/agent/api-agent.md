---
type: Reference
title: "Assistant agent — API"
description: "The /assist/* control-plane routes served by the agent module, with their gates and status mapping."
resource: inspecto/src/main/java/com/gamma/control/AssistRoutes.java
tags: [agent, assist, api]
timestamp: 2026-10-19T00:00:00Z
---

# Assistant agent — API

Routes registered by `AssistRoutes`
([`AssistRoutes.java`](../../../../../inspecto/src/main/java/com/gamma/control/AssistRoutes.java)) and answered by
the `AssistAgent` this module provides. When the module is absent, every route except `GET /assist/diagnoses`
(empty list) and `GET /assist/skills` answers 503 `CAPABILITY_UNAVAILABLE`. The intelligence routes (`/agent/*`)
are in [AI assist — API](../intelligence/api-ai-assist.md).

| Route | Gate | Notes |
|---|---|---|
| `GET /assist/diagnoses?limit=` | read | Recent failure diagnoses (default 50) |
| `GET /assist/settings` | read | Model-provider settings, masked |
| `GET /assist/metrics` | read | Counts only, never data values |
| `GET /assist/skills` | read | Static list: explain-entity, diagnose-break, draft-pipeline, triage-alert |
| `POST /assist/settings` | `canAdminister` | Validated write; 400 on a bad value (e.g. a `baseUrl` for `anthropic` / `gemini`) |
| `POST /assist/settings/test` | `canAuthorWorkbench` | Real outbound probe of each model tier, through the endpoint allowlist |
| `POST /assist/{intent}` | per skill | Body: `screenContext`, `partialInput`, `userText` |

`POST /assist/settings` and `/assist/settings/test` are gated together: the settings are server-wide and `/test`
calls whatever was last saved. The capability check runs before the module check, so a caller without it sees 403
even when the module is absent.

## `POST /assist/{intent}` status mapping

| Outcome | Status |
|---|---|
| Module absent | 503 |
| Unknown intent | 404 |
| Skill's model unavailable | 503 with its message |
| Otherwise | 200, the `AssistResult` (`applyVia` is always null) |

Design: [Assist agent](spec-assist-agent.md).
