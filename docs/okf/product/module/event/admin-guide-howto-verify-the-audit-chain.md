---
type: How-to
title: "Verify the audit chain"
description: "Check that a Space's audit trail is complete and unaltered, record an anchor an auditor can carry off the machine, and read what a break means."
item: module.event
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java, inspecto/src/main/java/com/gamma/control/AuditVerifier.java, platform/inspecto-event]
---

# Verify the audit chain

## Goal

Prove that a Space's audit trail is whole: no entry removed, changed or inserted since it was written — and keep
evidence of that off the machine.

## Before you start

- **Edition:** every edition chains its audit trail. On Standard and up each entry names the signed-in person; on
  Basic there is no sign-in.
- **Who:** an administrator (`canAdminister`).
- **What you need:** an administrator's access token. Verifying and anchoring are Control API calls; the console's
  **Operations → Audit log** lists the entries but has no verify button.

## Steps

1. **Verify the chain:**

   ```powershell
   (Invoke-RestMethod -Uri "$api/spaces/o2c/audit/verify" -Headers $h).data
   ```

   `ok: true` with `firstBad: null` means every entry links to the one before it. `checked` counts the entries,
   `lastSeq` and `lastHash` name the newest. A walk stops after 100,000 entries with `complete: false`; continue it
   with `?from=<next>`. `from` and `to` limit the walk to part of the chain.
2. **Record an anchor** — a signed note of where the chain stands now:

   ```powershell
   Invoke-RestMethod -Method Post -Uri "$api/spaces/o2c/audit/anchors" -Headers $h
   ```

   It answers `created: true` and the anchor (`lastSeq`, `mac`); `created: false` when nothing was written since the
   last one. Later verifications check the chain against every anchor (`anchorsChecked`).
3. **Carry the anchors off the machine** — `GET …/audit/anchors` lists them, oldest first. Store that answer where the
   server's administrators cannot change it; that copy is the auditor's evidence.

## Check it worked

```check
GET /spaces/{space}/audit/verify
as admin
expect status 200
expect data.ok = true
expect data.firstBad absent
```

## What can go wrong

- `ok: false` — a broken chain still answers 200. `firstBad` names the first bad entry (`seq`) and a `reason` such as
  `duplicate`, `prior-epoch-missing` or `prior-epoch-altered`. Treat it as an incident: keep the Space's files as they
  are and compare with your off-machine anchors.
- An acknowledged break (`acknowledged-break`) — someone recorded a re-baseline (`POST …/audit/anchors/rebaseline`
  with a reason). It never repairs anything; it starts a new epoch, and `verify` reports the old break unless you
  ask for `?epoch=current`.
- 400 `from (n) is after to (m)` or `from must be a chain seq (an integer from 1)`.
- 403 for a non-administrator, 401 without a token.
- `anchors: "unavailable"` and 503 from the anchor routes — the server has no write root for the Space.

## Next

- [Back up and restore a Space](../backup/admin-guide-howto-back-up-and-restore.md)
- [Tutorial: run it](../../layer/platform/user-guide-tutorial-run-the-platform.md)
