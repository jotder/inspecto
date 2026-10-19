---
type: Guide
title: "Screening — admin guide"
description: "Enabling the screening add-on, permissions, Job parameters, hit storage, backup and common failures."
resource: features/inspecto-screening/src/main/resources/META-INF/inspecto/module.toon
tags: [screening, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Screening — admin guide

## Enabling

- Optional module `features/inspecto-screening` (manifest id `screening`,
  [module.toon](../../../../../features/inspecto-screening/src/main/resources/META-INF/inspecto/module.toon)), add-on
  `screening` in [offerings/standard.toon](../../../../../offerings/standard.toon): Standard edition and above.
  It requires the `entity-list` and `ops` modules.
- Absent module: the routes answer 503 with the manifest's `absentMessage`. A Space that switches the module off in
  `modules.toon` answers its routes 404 `MODULE_DISABLED` and skips `screening.run` Jobs.
- The nav entry *Operations ▸ Screening Hits* shows only when `/bootstrap` `features.screening` is `true`.

## Permissions

| Action | Gate |
|---|---|
| `POST /screening/check` | read-shaped exemption, no capability |
| List / read hits | Space access |
| Decide a hit (escalate, dismiss, confirm) | `canWorkIncidents`; four-eyes: whoever escalated cannot confirm |
| Reveal a masked subject (audited) | `canRevealLinkEntities` |

## Job parameters (`screening.run`)

`dataset`, `keyField`, `nameField` and/or `idField`, `lists` (comma separated), `threshold` (default 0.85),
`maxRows` (default 100 000, ceiling 1 000 000). Bad params, or an enabled Job naming an unknown Dataset or Entity
List, are refused at save (422); a draft (`enabled: false`) may name ones that do not exist yet.

## Storage, backup and audit

- One HMAC-signed JSON per hit at `<write-root>/screening-hits/<id>.json`, signed with the Space's Pending Change
  key. Reserved from every import, skipped by the whole-Space export, carried by backup (compliance evidence).
- No retention: hits are kept. A Run lists every hit to dedupe; fine for thousands, not for millions.
- Audit rows `screening.run.hits`, `screening.hit.decided`, `screening.hit.revealed`; never the screened name.

## Common failures

- Run fails "refusing to screen a subset": the Dataset has more rows than `maxRows`; raise it (ceiling 1 000 000).
- A hit reads `integrity: invalid` and decide answers 409: the file was edited outside the server.
- Decide answers 409 on a stale `version`, a final state, or `four-eyes`.
- Confirm answers 503 when the `ops` module is absent; nothing changes.
- A read error carries only its class; a DuckDB message is never shown.
