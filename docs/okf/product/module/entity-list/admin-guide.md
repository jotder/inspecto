---
type: Concept
title: Entity Lists — admin guide
description: Enabling Entity Lists, the capabilities that gate them, storage on disk, the four-eyes policy, and common failures.
resource: features/inspecto-entity-list/src/main/resources/META-INF/inspecto/module.toon
tags: [entity-list, admin-guide, operations]
timestamp: 2026-10-19T00:00:00Z
---

# Entity Lists — admin guide

## Enabling

- Module `entity-list` is optional and bound at boot
  ([`module.toon`](../../../../../features/inspecto-entity-list/src/main/resources/META-INF/inspecto/module.toon)); it
  requires module `entity-store`. It is staged from Basic and in every higher Edition
  ([`offerings/basic.toon`](../../../../../offerings/basic.toon)); the add-on `scoringLists` pairs it with `scoring`
  ([`offerings/standard.toon`](../../../../../offerings/standard.toon)).
- When absent, the seven `/entity-lists` routes answer 503, `/bootstrap` `features.entityList` is `false`, and a
  Risk Score model with `watchList` is refused at save.
- The module reads no `-D` system properties.

## Permissions

| Action | Capability |
|---|---|
| List, read, `match` | Space access only |
| Create, members, retire | `canManageIncidents` |
| Create, members, retire of a category list (`ownerKind: admin`) | `canManageIncidents` and `canManageExclusionCategories` |
| Change `ownerKind` / `autoApply` / `visibility` (`PUT .../attributes`) | `canManageExclusionCategories` |
| See a `restricted` list's members, `match` it, register it as a Dataset | `canManageExclusionCategories` or `canRevealLinkEntities` |
| Register the sidecar as a Dataset | `canAuthorWorkbench` |

Route table: [api-routes](api-routes.md).

Category lists are created and switched in **Settings > Link Analysis > Category exclusion lists** (members are
added on the Entity Lists page). An auto-applied list that cannot be sealed (its Entity Type is no longer in force,
or it holds more members than `input.list_members`) refuses every new Investigation in the Space with a 409/422
naming the list, until it is fixed or switched off. Detail: [spec](spec.md), "Category lists".

## Four-eyes

`approval: {entity-list: {required: true}}` in the Space's approval policy holds `members` and `retire` for a second
approver (202 and a Pending Change). An add-only change whose every entry expires within 24 h applies at once and
answers `reviewAfter: true`. Detail: [spec](spec.md), "Four-eyes".

## Storage and retention

- Fact log: `<write root>/audit/entity-facts/<12-digit seq>.json`, SHA-256 chained, append-only. **No retention.**
- Sidecar: `<dataRoot>/entity_list_<id>/entries.parquet`, guarded by an `.entity-list-output` marker and rewritten
  on every write.
- Masking key: `<root>.secrets/.risk-score-mask.key` (moved 2026-10-13, D-M7).

## Common failures

| Symptom | Cause |
|---|---|
| 503 on every route | no write root, or the module is not in the bundle |
| 500 `INTEGRITY_VIOLATION` | the fact chain fails verification on read |
| 409 on members | the list is retired, or its Entity Type is no longer in force |
| 422 on a CIDR entry | host bits set |
| `sidecar: "failed"` in a write answer | the Parquet write failed; the fact still stands (WARN logged) |
| `sidecar: "none"` | no data root on disk |
| Slow reads on a large log | the whole chain is re-verified on every read (~1 s at 10^4 facts, ~10 s at 10^5) — [spec](spec.md), Scale facts |
