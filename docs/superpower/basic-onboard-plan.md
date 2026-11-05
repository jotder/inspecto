# Basic data onboarding: three ways in, one default Pipeline, every application (`BASIC-ONBOARD-1`)

> **PLAN 2026-11-05 — the operator accepted the recommendations (§4) and answered the open questions (§5) the same
> day. Nothing built under this name yet.** Operator: *"This is minimum data onboarding (basic version of
> pipelining), reusable functionality that will work across many applications (not only Link Analysis: the Basic
> versions of MFS, rating, roaming analysis…)."* And the rule behind every placement below: **"Basic is not a demo;
> it must be usable."**

## 1. The goal in the operator's words

A Basic user onboards data **three ways**:

1. **Choose an inbox directory.** Files dropped there are imported.
2. **Upload a file** once.
3. **Choose a table** by connecting a database.

What makes it Basic: **no transformation**, **no user-defined schedule** (*Run now*, or an automatic poll), and
**no Pipeline view or edit**. Behind it, a **default Pipeline** that converts, arranges and indexes flat data
(delimited, Excel) into Parquet.

## 2. What exists (master `b4de24231`, 2026-11-05)

| Piece | State | Where |
|---|---|---|
| Hidden one-step Collector + Pipeline per import (`origin: import`); an upgrade to Standard shows it as an ordinary editable Pipeline | built | `DATA-TIERS-1` DT-2; `OnboardImport` |
| Upload once: CSV / TSV, Parquet, JSON / NDJSON, Excel with the bundle's extension | built | `/onboard/files`; console `/add-data` and LA Add data share one component (DT-8, `ONBOARDING-FIRST-RUN-1`) |
| Point at a file in place (fingerprint re-check) | built | DT-3 |
| Inbox: *N files waiting · Import waiting files* | in flight | `LA-ARRIVAL-FEEDS-1` lane (Run now, no poll) |
| Arrival rules: columns checked by name, duplicate refusal, *Replace the period*, arrivals list, compaction | in flight | `LA-ARRIVAL-MODES-1`, `LA-ARRIVAL-LEDGER-1`, `LA-DATASET-COMPACT-1` lanes |
| Expectations, quarantine, dedupe on Basic | built | DT-15 |
| Column review (names, types, roles, Entity Types) | built | Add data review step; `LA-COLUMN-ROLES-1`, `ENTITY-TYPE-CATALOG-1` |
| Database on Basic | **locked** | DT-4 (files only) - **reversed here** |
| Basic per application | **LA only**; others Standard+ until decided | DT-10 - **widened here** |

## 3. Decisions this plan reverses or narrows

| Earlier decision | Now |
|---|---|
| **DT-4** files only on Basic; a database shown locked | **Reversed:** Basic imports a database table as a snapshot (§4.3). Other remote adapters (SFTP, object stores, message buses) stay Standard. |
| **DT-5 / DT-17** no polling on Basic; a trigger-less Pipeline never polls | **Narrowed:** an import inbox may poll every 30 s on Basic (§5 Q1). Still no user-defined schedule, cron or event trigger. |
| **DT-10** each application's Basic form decided one by one; others default to Standard+ | **Widened:** onboarding itself is a platform feature every application's Basic placement reuses; *which* applications get a Basic form stays decided one at a time (§5 Q3). |
| **`ONBOARDING-FIRST-RUN-1` C3** *Open in editor* on the review screen | **Moved here:** on Basic there is no editor; on Standard+ the existing *Open in editor* on the Dataset card after Create stands. Revisit only if a Standard user asks. |

## 4. Design (operator-accepted recommendations)

1. **One onboarding feature, owned by the platform.** *Add data* is a platform feature with three ways in and one
   default Pipeline; each application (LA today; MFS, rating, roaming later) places the same screen in its own UI.
   No application forks the import.
2. **A fixed default Pipeline: read → arrange → write Parquet → follow-on.** *Arrange* is the review step (names,
   types, column roles, Entity Types). *Follow-on* is a small list each application contributes to; it runs inside
   the user's run (DT-16's in-run rule). LA contributes its link index.
3. **A database table is a snapshot you re-run.** Pick a table (or a saved read-only query), copy it into Parquet;
   *Run now* (or the poll, if the user turns it on) refreshes it as a *replace everything* arrival. Read-only
   credentials, stored as a `${ENV:…}` secret reference, never the raw value.
4. **The same arrival rules for all three ways in.** Name-checked columns, duplicate refusal, *Replace the period*,
   the arrivals list, compaction and retention apply to an inbox file, an upload and a snapshot alike.
5. **Placement.** This plan owns the Basic column of `DATA-TIERS-1`; `DATA-TIERS-1` keeps the Standard / Advanced
   gates and the breaking-change refusals (DT-9).

## 5. Operator answers (2026-11-05)

| # | Question | Answer |
|---|---|---|
| Q1 | "Manual scheduling" | ***Run now*, or auto: a 30-second poll.** No user-defined scheduling. The poll is per import inbox (and per snapshot when the user turns it on), off by default, one interval, not configurable on Basic. |
| Q2 | Which databases first | **Postgres.** |
| Q3 | Which applications next | **Decided later, as we go.** The first non-LA placement is its own row when chosen. |
| Q4 | What "index" means | **The follow-on index is for the applications that need one** (LA's link index). For the others, the default Pipeline ends at sorted, partitioned Parquet; an application adds a follow-on step only when it has its own. *(Recorded reading of "Index for non-LA apps only" - confirm at P0.)* |

## 6. Phases

| Phase | Scope | Size |
|---|---|---|
| **P0 verify** | V1 the follow-on list as a seam (where LA's index call sits today in `OnboardImport`); V2 Postgres read via DuckDB's `postgres` extension vs the `db` Collector - which ships in the Basic bundle, and its size; V3 a 30 s poll's cost on an idle install (no Spaces open, many inboxes); V4 confirm Q4's reading | S |
| **P1 Platform seam** | *Add data* as a platform feature: the follow-on list (LA registers its index), the default Pipeline's four steps named in the import's config, the console `/add-data` and LA placements on one component (already shared; make the seam explicit) | M |
| **P2 Inbox poll** | Per-inbox *Watch this folder* toggle (30 s), on Basic and up; one poller for all inboxes; a refused file is set aside with its reason (as *Run now* does); visible in Data status | M |
| **P3 Postgres snapshot** | Connect (host, database, user, `${ENV:…}` password), pick a schema / table or paste a read-only `SELECT`, preview, review columns, Create; *Run now* / poll refreshes as *replace everything*; connection test refuses write-capable statements | L |
| **P4 Docs and sale** | EDITIONS rows, the whitepapers, the user guide; DT-4 / DT-5 / DT-10 / DT-17 amended in `data-tier-editions-plan.md` | S |

### P0 V3 - what a 30 s poll costs on an idle install (measured 2026-11-05)

**What one tick does (the P2 design the measurement assumes).** The poll is to keep the list of watched inboxes
in memory (read once from each Space's Dataset entries, then kept by the *Watch this folder* toggle), so an idle tick
reads no registry and opens no Space: per watched inbox it lists the folder (depth 4, as *Import waiting files* does)
and reads each visible file's size and modified time. A run starts only when a file is waiting and has settled.

**Measured** (JDK 27, Windows 11, 12 logical CPUs, local NTFS disk with the default virus scanner on; synthetic
folders; 20 warm ticks averaged; CPU and allocation of the poll thread only):

| Watched inboxes | Files waiting in each | CPU per tick | Wall per tick | Allocated per tick | Share of one core at 30 s |
|---:|---:|---:|---:|---:|---:|
| 10 | 0 | 1.6 ms | 1.8 ms | 25 KB | 0.005 % |
| 100 | 0 | 22 ms | 31 ms | 255 KB | 0.07 % |
| 1,000 | 0 | 176 ms | 185 ms | 1.9 MB | 0.6 % |
| 10 | 5 | 6 ms | 6 ms | 101 KB | 0.02 % |
| 100 | 5 | 66 ms | 66 ms | 770 KB | 0.2 % |
| 1,000 | 5 | 1,010 ms | 1,041 ms | 7.6 MB | 3.4 % |

**Verdict: affordable; no change to the design.** An idle install with empty inboxes costs under 1 % of one core
at 1,000 watched inboxes (a verification re-run measured 0.87 %, so "under 1 %" is close to its limit there), and the
garbage per tick is small. The cost grows with the files *left* in inboxes, each stat-ed every tick. A Basic user
leaves files there in two ways: a file whose columns do not match is moved to `refused/` by the import (so it stops
costing), and a file already imported under the same name stays and is counted (the inbox says *rename to import
again*). Not in the harness: for each waiting file the poll also asks whether it was already imported under its name
(`MarkerManager.isAlreadyProcessed`, one or two more file look-ups), so the "files waiting" rows understate the cost
somewhat - the same order of magnitude. The design runs one thread for every inbox, at a fixed delay, so a tick that
overruns 30 s delays the next one rather than overlapping it. A slow network folder is the risk the numbers do not
cover: there each listing is a round-trip.

## 7. Not in scope

- Transformation on Basic (joins, derived columns, filters beyond the review step) - Standard.
- User-defined schedules, cron, event or signal triggers on Basic.
- Other databases (MySQL, SQL Server, Oracle) and other remote adapters - later rows on demand.
- A Pipeline view or editor on Basic.
- Choosing the next application's Basic form (§5 Q3).

## 8. Board

`BASIC-ONBOARD-1` (P2) in `docs/BACKLOG.md`.

## References

- `docs/superpower/data-tier-editions-plan.md` (DT-2, DT-3, DT-4, DT-5, DT-8, DT-10, DT-15, DT-16, DT-17)
- `docs/superpower/first-run-onboarding-plan.md` (C3)
- `docs/archived-documents/plans-archive/la-data-arrival-plan.md` (arrival rules)
