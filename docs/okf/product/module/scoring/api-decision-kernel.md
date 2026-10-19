---
type: Reference
title: "Decision Kernel (Decision Rules and the Condition Language) — API"
description: "Control-plane routes for Decision Kernel (Decision Rules and the Condition Language), with their gates."
tags: [scoring, decision-kernel, api]
timestamp: 2026-10-19T00:00:00Z
---

# Decision Kernel (Decision Rules and the Condition Language) — API

Routes for Decision Kernel (Decision Rules and the Condition Language). Design and internals: [Decision Kernel (Decision Rules and the Condition Language) architecture](architecture-decision-kernel.md).

| Route | Purpose |
|---|---|
| `GET|POST /decision-rules` | list, create (503 no write root, 422 bad body, 409 duplicate) |
| `GET|PUT|DELETE /decision-rules/{name}` | read, update, delete (404 unknown) |
| `POST /decision-rules/{name}/simulate` | evaluate `when` over body `sampleRows`; returns `matched`, `total`, `checkedAt` |
| `POST /decision-rules/{name}/apply` | run platform consequences for one record |
| `GET /decision-rules/consequences` | every action with `available`, `reason`, `module` |
