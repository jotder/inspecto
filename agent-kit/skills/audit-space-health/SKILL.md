---
name: audit-space-health
description: A read-only health report of one Inspecto Space - config drift and edits made outside the server, its Space Template origin and pending upgrade, Dataset storage growth, and Pending Changes or Pending Actions that are stale or expired. Use when the person asks for a health check, an audit, "is this Space healthy", or as a scheduled weekly read.
---

# Audit a Space's health

One report of what has drifted, what was edited by hand, what is growing and what is waiting too long. Every step
here is a read; nothing changes. Fixing what you find is for a person (accepting a hand edit or applying a Template
upgrade is an administrator's act) or for another skill.

## 1. Start

- `health` — a subsystem down (storage, scheduler) comes first in the report; the rest may read wrong while it is down.
- `edition_and_modules` — what this install carries; a tool of a missing module answers 503.

A 403 on any read below means the person (or machine) you act for lacks that right: put "not checked — needs
*capability*" in the report and go on. Never look for another route to the same answer.

## 2. Config integrity

`get_space_integrity` — the Space lock's report. Read its `status` first (`computing`, `disabled`, `unavailable` mean
there is no report yet; say which). When ready, report:

- **Changed outside the server** (`changedOutside`) — config files edited by hand, not through the server. Name each
  file. These bypassed the save gate and the Approval Policy, so they matter most.
- **Broken links and duplicates** — config that names something missing, or the same id twice.
- **Activation holds** — what the server holds back from running, and why.

## 3. Origin and upgrade

- `get_space_origin` — the Space Template this Space was made from. `none` means it was not made from one: say so and
  skip the upgrade. Otherwise report the files the Space changed since and whether the Template moved on.
- `preview_template_upgrade` — when a newer Template version exists: per file, update, add, conflict, kept or removed.
  Report the conflicts by name; they are the work a person must do by hand.

## 4. Storage

`list_datasets`, then `get_dataset_storage` per Dataset that matters (the large ones, the ones fed by runs): its
files, the last compaction and the retention policy. Report a Dataset with many small files or no compaction for a
long time, and a Dataset with no retention policy that grows every run. `dataset_freshness` adds how stale each one is.

## 5. Proposals waiting

- `list_pending_changes` and `list_pending_actions` with `status: pending` — for each, how long it has waited and when
  it expires. Name the ones waiting longest; nobody has read them.
- The same lists with `status: expired` (and `stale` for Pending Actions) — work that was proposed and lost. Name them
  so a person decides whether to propose again.
- `get_pending_change` or `get_pending_action` for one that needs a line of explanation.

## 6. The report

One report, most urgent first: platform faults, edits outside the server, conflicts a Template upgrade would hit,
storage growth, then stale and expired proposals. For each finding: what, where (file, Dataset or `pc-…` / `pa-…`
id), since when, and who should act. Close with what you could not check and why. When nothing is wrong, say so in
one line per section — a clean report is a result.

## Never

- Accept a hand edit, apply a Template upgrade, compact or delete storage, or withdraw, approve or decline a
  proposal — this skill only reads, and a person decides each of those.
- Report a finding the reads did not show; say what you could not see.
- Reveal a masked value, or quote one back unmasked.

## MCP Tools

- `health`, `edition_and_modules` — subsystem health, and the modules this install carries
- `get_space_integrity` — config changed outside the server, broken links, duplicates, activation holds
- `get_space_origin`, `preview_template_upgrade` — the Space Template origin, and what an upgrade would do
- `list_datasets`, `get_dataset_storage`, `dataset_freshness` — each Dataset's files, compaction, retention, staleness
- `list_pending_changes`, `get_pending_change` — Pending Changes waiting or expired
- `list_pending_actions`, `get_pending_action` — Pending Actions waiting, stale or expired
