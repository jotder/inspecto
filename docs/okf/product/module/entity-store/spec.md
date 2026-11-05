---
type: Feature
title: "Entity store — spec"
description: "Implementation detail of platform/inspecto-entity-store: the identity fact log format, the Entity List fold, Entity Types, masking, Industry packs and the Link Analysis settings and limits."
tags: [entity-store, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Entity store — spec

Module: `platform/inspecto-entity-store`, package `com.gamma.entitystore`, `buildRole: platform`,
`offeringRole: base`, `bindingTime: build` ([module.toon](../../../../../platform/inspecto-entity-store/src/main/resources/META-INF/inspecto/module.toon)).
It holds no HTTP routes: the Entity List routes live in [entity-list](../entity-list/api-routes.md) and Link
Analysis in [la-core](../la-core/api-link-analysis.md); both read this module.

## Classes

| Class | Role |
|---|---|
| `EntityFactLog` | Per-Space append-only identity fact log at `<Space config root>/audit/entity-facts/`, or `<data root>/audit/entity-facts/` for a Space with a data root (`SpaceAuditDir`). |
| `EntityRegistry` | Pure fold of the log into Entity Lists as of one sequence number. Not cached. |
| `EntityListFacts` | Host-free read and append helpers shared by the Entity List routes and Link Analysis. |
| `EntityListEntries` | Range entries stored as one canonical string: `prefix:<key>`, `range:<lo>..<hi>` (same-length keys), `cidr:<network>/<len>` (IPv4 or IPv6, no host bits). |
| `EntityListSidecar` | Derived Parquet copy of a list at `<dataRoot>/entity_list_<id>/entries.parquet`, read by a Dataset with `physicalRef: entity_list_<id>`. |
| `EntityTypes` | Per-Space Entity Types: key normaliser (closed set), masking, Dataset classifications. Stored as `entity_types` in `link-analysis.toon`; `null` inherits the defaults, a stated list replaces them, an empty list is refused. Reader guidance (`ENTITY-TYPE-CATALOG-1` P0, 2026-11-04): each type sits under one family of the closed `FAMILIES` (party, organisation, account, identifier, device, place, document) with a `definition` and an `example`; a stated type that leaves them out takes the core default's of the same id, a new id has none (the picker files it under *Other*). An Industry pack overlay keeps family and example and lends its term's definition. Never part of identity. |
| `MaskTokens` | `masked:<16 hex>` = HMAC-SHA256 of the id under a random key. |
| `LinkAnalysisSettings` | Per-Space `link-analysis.toon`: node caps, `maskingMode` (default `typed`), four-eyes thresholds, Entity Types, distinct caps, hub threshold, graph-run knobs. |
| `LaLimit`, `LaLimits`, `LaLimitValues` | The one catalogue of Link Analysis limits, with range checks and the per-Space resolution. |
| `IndustryPack`, `IndustryPacks` | Classpath-discovered vertical vocabularies; refused whole on any rule break. |

## Fact log format

- One immutable JSON file per fact, named by a 12-digit zero-padded sequence (`000000000001.json`).
- Fields in order: `seq`, `at`, `actor`, `reason`, `kind`, `listId`, the kind's payload, `prevHash` (SHA-256 of the previous file's bytes; `""` for seq 1).
- Write: staged temp file, forced, then published by a hard link (`publishNew`), so an existing seq is never overwritten, even across processes. In-JVM writers serialise on `lock()`.
- Read verifies the whole chain each time; a gap, a seq mismatch or a wrong `prevHash` throws `BrokenChainException`. Tail truncation is caught only by a cited head hash.
- Fact kinds folded by `EntityRegistry`: `list.created`, `list.member.added` (optional `expiresAt`), `list.member.removed`, `list.range.added`, `list.range.removed`, `list.retired`. The normaliser is sealed at `list.created`.

## Sidecar

Columns: `list_id, purpose, entity_type, match, entry, lo, hi, expires_at, added_at, added_by, reason`.
Expired entries are kept with `expires_at`; a consumer filters on it. The writer only touches a directory
carrying its own `.entity-list-output` marker. A failed sidecar write never undoes the fact.

## Masking keys

- `MaskTokens.key(dir)`: a `mask.key` per directory, created on first use and published by hard link.
- `MaskTokens.spaceKey(writeRoot)`: the Space identity key `.risk-score-mask.key` in the Space secrets directory, shared by Link Analysis, Entity Lists, Screening, BI and Risk and Anomaly Scores.

## Limits

`LaLimit` items are `SPACE` layer (in `link-analysis.toon`) or `INSTALL` layer (in the server-wide
`la-limits.toon`, located by `-Dsystem.config.dir`, then the spaces container root, then the sole Space's
write root). A write outside an item's range is refused 422; a stored value outside it reads as the default.
Writes are read-modify-write over the raw TOON map under one lock, so unknown keys survive.

## Industry packs

Index `META-INF/inspecto/industry-packs.toon`; each pack is `META-INF/inspecto/industry-packs/<id>/` with
`industry-pack.toon`, `industry-pack-terms.toon`, `industry-pack-entity-types.toon`,
`industry-pack-link-kinds.toon` and, optionally, `industry-pack-record-types.toon`. Bundled: `telecom`, `mobile-money`.
Term keys are `concept.`, `entity_type.`, `record_type.`, `link_kind.`, `measure.`, `pattern.` or `term.`.

### The Entity Type catalogue (`ENTITY-TYPE-CATALOG-1`, built 2026-11-04)

- **Families.** Every Entity Type sits under one family of the closed, neutral core set `EntityTypes.FAMILIES`
  (Party · Organisation · Account · Identifier · Device · Place · Document). Families, definitions and examples are
  reader guidance only, never identity.
- **The neutral starter catalogue** is `EntityTypes.DEFAULTS`: the nine original ids (`subscriber`, `imsi`, `imei`,
  `msisdn`, `wallet`, `account`, `agent`, `handset`, `cell` — ids and classifications are data contracts, unchanged)
  plus neutral types every domain shares (`email`, `id_document`, `organisation`, `bank_account`, `card`,
  `ip_address`, `address`). A Space with no pack reads it.
- **Membership.** A Space with packs reads only the types its packs list, in pack order, each restated core id keeping
  its starter family, definition and example unless the pack states its own example or shape; a stated
  `entity_types` list still wins over everything.
- **A pack adds types** only under a core family; an added type's label and definition are the pack's own
  `entity_type.<id>` term (so `IndustryPackNeutralityTest`'s derived word list covers it). The neutrality rule is
  "every pack type sits under a core family; no pack word in the core".
- **Value shapes** (`ValueShapes`, `inspecto/graph/value-shape.ts`, one parity fixture
  `entity-shape-parity.fixture.json`): `free · e164 · digits(n|a-b) · luhn(n|a-b) · iban · email · uuid`, never a
  free regex. `e164` fits strongly with `+`, weakly as bare digits; `digits` always weakly; the narrower shape wins a
  tie, a set length above a range. Add data (`EntityShapes`) proposes the type whose shape the most sampled values fit; a
  `free` type only by name, a column's whole name matching a type id before its tokens (`ip_address`).
- **Several packs per Space:** `extra_packs` beside `domain_profile` (the Domain Profile alone gives the vocabulary);
  merged by id, first pack wins a label. One id must agree on family, normaliser, masking and classifications in every
  pack — `IndustryPacks.load` refuses a pack that does not read together with those installed before it, and
  `PUT /settings/link-analysis/domain-profile` refuses a chosen set that does not (422).
- **Record types:** a pack's `record_types[n]{id, likely_types[]}`, label from `record_type.<id>`; a Dataset's
  `recordType` (`PUT /datasets/{id}/record-type`, or the Add data commit); `GET /inv/entity-types` answers
  `recordTypes`. The "what does this column hold" picker puts the record type's likely types first (*Likely in a …*)
  and marks a type whose shape the column's values fit (*Shape matches*): Add data from the file's own sample, the
  Data page from `GET /inv/datasets/{id}/shapes` (its own row sample is masked for masked types, so the server reads
  the first 200 rows unmasked and answers marks only, `{columns: {<col>: [{type, strong}]}}`, never a value). Add data
  proposes the record type from its sure (high-confidence) column proposals: the most likely types held, then the
  larger share of the record type's list; a remaining tie is low.
- **A record type's usual roles (`ENTITY-TYPE-RECORD-ROLES-1`, 2026-11-05):** optional `time_hints`, `measure_hints`,
  `category_hints`, `from_hints`, `to_hints` per record type (`IndustryPack.RoleHints`; written and matched as the
  profile's hints: case-insensitive, anywhere unless anchored with `^` / `$`; a bad pattern refuses the pack); wire
  `recordTypes[].roleHints {time, measure, category, from, to}`. A hint gives a role only where the column's type can
  hold it (Time: a date or timestamp; Measure: a number; Category: neither a decimal number nor a date), first Time,
  then Measure, then Category. Readers: Add data and the Data page (column roles, la-core `architecture.md` §6.15) and
  the preset questions' From / To (§6.16). Telecom's and mobile money's record types carry generic hints; a broad word
  is anchored (`^to_`, `_to$`: a *total* is no payee).

⚠ Gotchas: a pack restating a starter id must repeat its classifications exactly (the agreement rule); a new type
needs both its `entity_type.<id>` term and a family or the whole pack is refused; adding a starter type changes what a
no-pack Space reads (tests pin the starter size); a shape is evidence, so a pack must not give a broad shape (`digits(5-6)`) to a type
whose values look like any small number — leave it `free` and let the name propose it.

Deliberate deferrals: the Studio Entity Types settings page shows the new fields read-only at most; a record type's
hints are pack content only (no per-Space edit).

Tests: [tests-inventory](tests-inventory.md).
