---
type: Concept
title: Screening — Match Scores against Entity Lists and the review of Screening Hits
description: What SCREENING-1 built — the optional inspecto-screening module (add-on screening), the name and identifier methods, the on-demand check route, the screening.run Job Type, the signed Screening Hit records and their review states, gates, audit and the recorded limits.
resource: features/inspecto-screening/src/main/java/com/gamma/screening/ScreeningRoutes.java
tags: [control-plane, screening, entity-list, compliance, add-on, job-type]
timestamp: 2026-10-09T00:00:00Z
---

# Screening

**Screening** compares subjects (a name and/or an identifier) against [Entity Lists](entity-lists.md) of any purpose
(sanctions, PEP, deny lists) and reports a **Match Score** per candidate entry. A **Screening Run** raises each new match
as a **Screening Hit** for a person to decide. Domain-neutral: it knows no list format and no vertical vocabulary; the
AML function pack (`PACK-AML-1`) builds on it. Vocabulary: `docs/GLOSSARY.md` §11.

## Module and gates
- Optional module `features/inspecto-screening` (artifactId `inspecto-screening`, manifest id and feature id `screening`,
  package `com.gamma.screening`), add-on `screening` in `offerings/professional.toon`: Professional, Enterprise and Preview;
  absent on Personal, where its four routes answer 503 from the manifest's `absentMessage`. `requires.modules: entity-list`.
- It reads the Entity List fact log directly through `inspecto-entity-store` (`EntityFactLog`, `EntityRegistry`,
  `EntityListFacts`); it never writes to it. A confirmed hit writes no Identity Fact (`D-M1`: never by similarity).
- A Space that switches the module off (`modules.toon`) answers its routes 404 `MODULE_DISABLED` and skips its Jobs
  (`provides.jobTypes: screening.run`).

## The two methods (`NameMatcher`, `Screener`)
- **identifier** — the list's own matching (`EntityList.match`: exact under the list's sealed normaliser, then its prefix /
  range / CIDR entries). Score 1.0. Screening cannot drift from `POST /entity-lists/{id}/match`.
- **name** — against the list's live members (range entries are identifier-only):
  - *fold*: lower-case, NFKD, combining marks stripped, Cyrillic / Greek look-alike letters mapped to their Latin twin
    (Cyrillic у is *y*, not *u*), `ß æ œ ø đ ł þ ı ð` expanded, a digit inside a letter token read as its look-alike
    letter (`0→o 1→i 3→e 4→a 5→s 7→t`; an all-digit token stays digits), punctuation to space, honorifics dropped;
  - *score* = max(**token-set**, **joined**). Token-set: each token of the name with fewer tokens takes its best
    Jaro-Winkler partner (greedy, each used once; order-free), a pair below **0.8** counts as 0, weighted by the pair's
    mean token length, times `0.8 + 0.2·min/max` of the two total token lengths; it applies when the token counts are
    equal or the shorter name has two or more tokens. Joined: Jaro-Winkler of the tokens written together, only when
    the counts differ ("Abdul Rahman" = "Abdulrahman"). Symmetric, 0..1, 4 decimals.
- **Blocking**: a name is compared only with members sharing the first folded letter of at least one token.
- The golden pairs (`NameMatcherTest`) pin the behaviour at the default threshold **0.85**: variants and transliterations
  match (Mohammed / Muhammad 0.85, Usama bin Ladin / Osama bin Laden 0.91, Vladimir Vladimirovich Putin / Vladimir Putin
  0.90); look-alike spellings fold to exactly 1.0; different people stay below (John / Jane Smith 0.56, Kim Jong Un / Il
  0.78, "Ali" / "Ali Hassan Mohammed" 0.81); **near spellings are flagged by design** (Chen Wei / Chen Wen 0.92,
  Maria Garcia / Mario Garza 0.88) — recall over precision, a reviewer dismisses them.
- A retired list matches nothing; an expired entry never matches; a masked list's entry is shown as its mask token.

## Routes
| Route | Gate | What |
|---|---|---|
| `POST /screening/check` | read-shaped exemption | `{subjects[1..1000]:[{key?, name?, identifier?}], lists[1..20], threshold? (0.5..1, default 0.85), maxMatches? (1..20, default 5)}` → `{threshold, results:[{key, name, identifier, matches:[{listId, purpose, entry, method, score}]}]}`, best first. Persists nothing; a POST so names never ride in a URL. Unknown list 422. |
| `GET /screening/hits?state=` | Space access | every hit, newest first; `state` = `open · escalated · confirmed · dismissed` (else 422). |
| `GET /screening/hits/{id}` | Space access | one hit; an id that is not `sh-<14 digits>-<6 hex>` is a 404 and never names a file. |
| `POST /screening/hits/{id}/decide` | `canWorkIncidents` | `{decision: confirm · dismiss · escalate, reason (1..500), version}` → the hit. |

Decide gates in order: capability → no write root 503 → body 422 → unknown hit 404 → a record that fails its integrity
check 409 → stale `version` 409 → transition not allowed 409 → save under the store lock. Test: `ControlApiScreeningTest`
(armed Subjects: 401 / 403 for no capability and for a builder role / 200).

## The `screening.run` Job Type (`ScreeningJobType`)
Params `dataset`, `keyField`, `nameField` and/or `idField` (plain column names), `lists` (comma separated), `threshold`
(default 0.85), `maxRows` (default 100 000, ceiling 1 000 000). It reads the Dataset through `DatasetRelation` +
`QueryExecutor` under the default SQL sandbox, ordered by the subject columns, one row past the cap: **more rows than
`maxRows` fails the Run** (never a silently partial screen); a read error carries only its class (a DuckDB message can
quote a cell). Each match with no hit yet is raised; a classified column's value is stored in the hit masked
(`EvidenceMasker`), matching uses the raw value. Emits the signal `screening.hits.raised`; the Run message counts
screened subjects, matches and new hits. Test: `ScreeningJobTest`.

## Screening Hits (`ScreeningHits`)
- One HMAC-signed JSON document per hit at `<write-root>/screening-hits/<id>.json` — the Action Requests storage pattern
  (atomic temp + move, path-jailed, signed with the Space's Pending Change key under domain `screening-hit`). A forged or
  edited document reads back `integrity: invalid` and cannot be decided or re-signed. Not an OperationalDb family.
- `screening-hits/` is reserved from every import (`ReservedConfigPaths`) and skipped by the whole-Space export
  (`BundleExporter`); a backup carries it (compliance evidence).
- Fields: `id, state, version, dedupeKey, listId, purpose, entry, method, score, threshold, subjectKey, subjectName,
  subjectIdentifier, source {job, runId, dataset}, raisedAt, raisedBy, decidedBy, decidedAt, reason, history[]`.
- **Identity**: `dedupeKey = sha256(listId, entry, subjectKey)`; a Run never raises a second hit for a key that has one in
  any state, so a dismissed false positive stays dismissed until the list entry changes.
- **States**: `open → confirmed · dismissed · escalated`, `escalated → confirmed · dismissed`; `confirmed` and `dismissed`
  are final. Every decision bumps `version` and appends `{state, by, at, reason}` to `history`.
- **Audit**: `EventType.AUDIT` rows `screening.run.hits` (one per Run: counts) and `screening.hit.decided` (hit id, list,
  from → to, decider). Never the screened name.

## The SPA pane (`/screening`, nav *Operations ▸ Screening Hits*)
`modules/admin/screening/` over `ScreeningService` (`inspecto/api`). The nav entry carries `navFeature: 'screening'`,
so it hides when `/bootstrap` `features.screening` is not `true`. Open / Escalated / All filter, a data-table of hits
(Match Score as a percentage, state as a status badge: open info, escalated warning, confirmed error, dismissed
success), and a detail card with the score, subject, source run and history. The decision row (Escalate / Dismiss /
Confirm match, a required reason, the hit's `version` sent with it) shows only with `LensService.canWorkIncidents()`,
only for the decisions the state still allows, and never on a record whose integrity check failed. A 503 from the list
is latched into an explained notice, not a toast. Specs: `screening.component.spec.ts` (axe included) and the
`navigation.service.spec.ts` case for the feature flag; proved end to end in the preview over a seeded Space.

## Decisions (SCR-D1 … SCR-D14, lane 2026-10-09, operator away)
Matching in Java, not DuckDB SQL (folding, token-set scoring and golden tests are plain there; DuckDB only reads rows) ·
threshold per request / per Job, no Space settings file (a new fixed config name needs reserved-path and import-inventory
work) · `canWorkIncidents` decides, no new capability (module-contributed capabilities are declined, P1-D3; revisit when
four-eyes on `confirmed` is required) · the Action Requests record pattern rather than a store family (a family needs a core
`SpaceRoot` accessor, count tripwires and backup lockstep) · a dismissed hit is never re-raised for the same entry · a
Run refuses a subset. Provenance: `docs/archived-documents/plans-archive/screening-addon-plan.md`.

## Limits and what is open (`SCREENING-1` residuals in `docs/BACKLOG.md`)
- Blocking misses a typo in the first letter of **every** token (Gaddafi / Qadhafi alone is never compared); there are no
  phonetic keys or transliteration tables beyond look-alike folding.
- A Run lists every hit document to dedupe (no index); fine for thousands of hits, not for millions.
- No four-eyes on `confirmed`; `check` cannot raise hits; no Space-level threshold default.
