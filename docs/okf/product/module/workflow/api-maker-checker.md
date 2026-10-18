---
type: Reference
title: "Maker-checker (Pending Changes) — API"
description: "Control-plane routes for Maker-checker (Pending Changes), with their gates."
tags: [workflow, maker-checker, api]
timestamp: 2026-10-19T00:00:00Z
---

# Maker-checker (Pending Changes) — API

Routes for Maker-checker (Pending Changes). Design and internals: [Maker-checker (Pending Changes) architecture](architecture-maker-checker.md).

| Route | Gate | Notes |
|---|---|---|
| `GET /settings/approval` | read | carries `failedClosed` |
| `PUT /settings/approval` | `canAdminister`; 403 with no Authenticator | empty body 422 - send `{"approval": {}}` to turn off |
| `GET /pending-changes[?status&kind]` | read | capped at 500, `total` + `truncated` |
| `GET /pending-changes/{id}` | read | with `current` and `proposed` |
| `GET /pending-changes/{id}/diff` | read | line diff over both contents as TOON |
| `POST /pending-changes/{id}/approve` · `/decline` | `canApproveChanges` | four-eyes, roster, stale check |
| `POST /pending-changes/{id}/withdraw` | author only | status `withdrawn` |
| `GET|PUT /settings/approvers` | PUT `canAdminister` | the roster |
