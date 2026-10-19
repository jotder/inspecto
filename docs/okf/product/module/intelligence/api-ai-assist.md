---
type: Reference
title: "AI assist — API"
description: "Control-plane routes for AI assist, with their gates."
tags: [intelligence, ai-assist, api]
timestamp: 2026-10-19T00:00:00Z
---

# AI assist — API

Routes for AI assist. Design and internals: [AI assist architecture](architecture-overview.md).

| Route | Gate |
|---|---|
| `POST /assist/{intent}` | per-skill |
| `GET /assist/diagnoses`, `/assist/metrics`, `/assist/skills`, `/assist/settings` | read |
| `POST /assist/settings` | `canAdminister` |
| `POST /assist/settings/test` | `canAuthorWorkbench` (real outbound call) |
| `POST /agent/sessions`, `/agent/sessions/{id}/...` | session owner |
| `POST /agent/tools/{name}`, `/agent/tools/{name}/derive` | the tool's declared capability |
| `/agent/triage-runs`, `/agent/feedback` | triage and learning |
| `/agent/approvals`, `/agent/policy`, `/agent/policy/kill-switch`, `/agent/actions` | approvals and autonomy |
