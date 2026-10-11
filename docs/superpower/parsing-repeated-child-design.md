# Parsing D9 — a repeated-child operation for the ASN.1 Decode Profile (design)

**Status 2026-10-11: APPROVED. SLICE 1 BUILT (commit 9c07a0aeb); slices 2-3 open.**
Operator answers: closed vocabulary, select/reduce first; `group_by` sums groups into one scalar (per-group rows =
explode, slice 2); read-only drawer in v1; 10 000 defaults; `select` matches the stringified leaf.
**Slice 1 as-built deviations:** (a) `DeriveSpec` (public, `platform/inspecto-etl`) holds compile + evaluate instead
of a package-private `RepeatedChild` in the ASN.1 module, so DecodeProfile can validate it; (b) a `SEQUENCE OF X`
decodes as `{field: {X: [..]}}`, so list paths are `field.X`; (c) TOON has no flow maps or lists: `where:` is a
nested block, `group_by[1]: leaf` an array; (d) `of` is optional for `first` / `join` (scalar list: the element
itself); (e) `group_by` is accepted for sum / min / max / count only, and since group results are combined with the
same op it does not change the scalar (kept for the design's shape); (f) `derive`, `max_list_elements`,
`max_derived_columns` flow `asn1:` to `ingester_config`; the clash with `raw.fields` is checked when segments are
declared; (g) `derive_non_numeric` / `derive_no_match` are logged per file (INFO), not in a sink summary;
(h) `ASN1-DERIVE-NOT-IN-GRAMMAR` is NOT implemented (needs the compiled grammar's schema walk); open.
Tests: `DeriveSpecTest` (13), `Asn1RecordIngesterDeriveTest` (5) over synthetic case `repeated_child`. Operator decision
2026-10-11: design it now. Home: the Decode Profile (`platform/inspecto-etl/src/main/java/com/gamma/etl/DecodeProfile.java`,
shipped C1-C4 2026-09-25; as-built in `docs/okf/backend/engine/parser-plugins.md`). When the work ships, distil
into that concept, move leftovers to `docs/BACKLOG.md`, and `git mv` this plan to
`archived-documents/plans-archive/`.

## 1. Problem and evidence

`Asn1RecordIngester` (`providers/inspecto-telecom-asn1/src/main/java/com/gamma/telecom/asn1/Asn1RecordIngester.java`)
decodes each record to a nested map (`RecordMapper.toMap`, :131) and fills one row per record:

- segments are keyed by the **record's own name only** (`selectorsByKey.get(record.name())`, :124), so a child
  element inside a record can never be its own segment;
- a selector is a dotted map path (`resolve`, :171). A repeated child is a `List`, and `scalar()` (:189) returns
  `NULL` for any `Map` or `List` (class doc :40-44 says "give it its own segment", which this ingester cannot do).

So every column that lives inside a repeated child loads `NULL`. Measured 2026-10-11 (main checkout, read-only,
DATA-GOV-1 respected: grammars and tx configs only, no captures): 24 of 31 operator grammars parse; **1266
repeated-child occurrences** (595 distinct element types); every vendor family with a real legacy tx config
derives core columns from a repeated child. The legacy engine exploded lists by cartesian join by default
(`autoJoin`, 14 uses across the tx configs) and offered `selectWhere` (4 uses), `@groupBy` (6) with `@reduce`
min / max / sum (6), `flatten` (5) and `delimited` (25 uses in 10 configs). This plan's goal is parity of
*capability*, not of syntax.

## 2. Requirements per vendor family (field names and counts only)

| Family | Needs | Repeated child to columns |
|---|---|---|
| Ericsson OCC / CCN / AIR | select + reduce | `servedSubscriptionIDs` (`subscriptionIDType`, `subscriptionIDValue`) to MSISDN (type 0) / IMSI (type 1), 9 mentions; `dedicatedAccounts` to DA charge (sum), 20 mentions; `usedServiceUnits` to volume / duration (sum), 6 mentions |
| GGSN / PGW (Huawei GSN, Roshan SGSN) | reduce (group-by) | `listOfServiceData` (23 mentions) grouped by `ratingGroup` + `localSequenceNumber`; sum `dataVolumeGPRSUplink` / `dataVolumeGPRSDownlink`, min `timeOfFirstUsage`, max `timeOfLastUsage`; billable volume derived after |
| Huawei GMSC / MSC / IMS / MGCF | select + delimit scalar lists | identity and number lists; `delimited` used in 8 of the 10 configs that use it at all |
| TAP, NRTRDE | explode | call-event detail lists are one-to-many: the child rows are the product |
| SDP | reduce / delimit | counter and account lists |

Three operations cover everything: **select** (pick one element by predicate, surface a field), **reduce**
(group + aggregate to scalar columns on the parent row), **explode** (child rows in their own segment).
Scalar-list **delimit** is a degenerate reduce (`join`).

## 3. Options

| | A. Select + reduce only | B. Explode only | C. All three (recommended, A first) | D. Expression language |
|---|---|---|---|---|
| Expressiveness | ID columns, sums, GGSN billable-volume inputs; no TAP-style child rows | child rows; parent aggregates need SQL downstream | full | unbounded |
| Profile shape | `derive:` per segment, closed vocabulary | `children:` per segment | both | strings in the profile |
| Validation | closed enums, static | static | static | parser + sandbox needed |
| Trust | nothing new | nothing new | nothing new | **refused**: the profile is untrusted input (`archived-documents/plans-archive/parser-plugins-trust-design.md`); no eval, no mini-language |
| Cost | small; one row per record, no sink or segment change | medium (second segment, parent key) | A then B | large and unsafe |

**Recommendation: C, sequenced A first.** A needs no new segment and no change to row cardinality, so it is the
smallest slice that unblocks the most families (Ericsson identity / charge / volume and GGSN billable-volume
inputs, which are NULL today).

## 4. Profile schema addition (concrete, field names only)

New optional key under `asn1:` in a Decode Profile: `derive`, a map **segment key, then column name, then
operation**. A column name must be unique within the segment and must not collide with a `raw.fields` name. An
operation is a structured map from a closed vocabulary; there are no string expressions and no `$`-references.

```
asn1:
  derive:
    CCNRecord:
      SERVED_MSISDN:
        select: servedSubscriptionIDs
        where: { subscriptionIDType: 0 }
        take: subscriptionIDValue
      SERVED_IMSI:
        select: servedSubscriptionIDs
        where: { subscriptionIDType: 1 }
        take: subscriptionIDValue
      DA_CHARGE:
        reduce: dedicatedAccounts
        op: sum
        of: dedicatedAccountValue
    PGWRecord:
      UPLINK_VOLUME:
        reduce: listOfServiceData
        group_by: [ratingGroup, localSequenceNumber]
        op: sum
        of: dataVolumeGPRSUplink
      FIRST_USAGE:
        reduce: listOfServiceData
        op: min
        of: timeOfFirstUsage
```

- `select`: list path (dotted, from the record root); `where` (exactly one field = literal, equality on the
  stringified leaf); `take` (leaf field of the matched element); optional `nth` (0-based among matches, default
  0). No match gives `NULL`.
- `reduce`: list path; `of` (leaf field); `op` in `sum | min | max | first | count | join` (`join` takes a
  one-character `sep` and covers scalar-list delimit; `count` needs no `of`). Optional `group_by: [leaf fields]`:
  the list is grouped, the op applied per group, and the per-group results combined the same way (sum / count
  summed, min / max reduced) into one scalar per record. Open question 3 covers whether per-group rows are wanted;
  that is explode, not reduce.
- Numeric ops parse the leaf as a decimal (`BigDecimal`, no float drift). A non-numeric leaf is skipped for that
  element and counted per file; it is never coerced to 0.
- Derived columns are VARCHAR like every other column (the sink stores VARCHAR; see `scalar()` :189), so a
  downstream Transformer casts. They append after `raw.fields` and before `EVENT_TYPE`.
- Slice 2 adds `children`, for example `children: { CCNRecord: { usedServiceUnits: UsedServiceUnitRow } }`. The
  child element becomes segment `UsedServiceUnitRow`, an ordinary declared segment whose schema selectors resolve
  **relative to the child**, plus the reserved selectors `$parent.<field>` (a parent leaf, same stringify rule)
  and `$index` (0-based position). `$` is a fixed prefix the ingester recognises, not an expression.

`DecodeProfile.overlay` (DecodeProfile.java:58) merges key by key and replaces `segments` whole; `derive` and
`children` are likewise replaced **whole** (the D5 reasoning: what a Pipeline loads must be readable off one
file). Neither carries file refs, so path resolution is unchanged.

## 5. How ingest implements it

All in `Asn1RecordIngester` (226 lines) plus one new package-private helper (`RepeatedChild`, about 150 lines):

1. `declareSegments` (:~145) already declares columns up front. Append each segment's `derive` columns (profile
   order) between selectors and `EVENT_TYPE`, and compile each operation into an immutable object **once** (paths
   split, `op` enum-checked). Slice 2 also declares each child segment via `sink.define`.
2. Per record (:124-134): after the existing selector loop, evaluate the compiled operations over `decoded` (the
   full nested map, no second decode). `values` grows by the derive count; a segment without `derive` follows the
   old path unchanged (byte-identical rows, regression-tested).
3. Slice 2: for each configured child, iterate the list and `sink.emit(childKey, values)`. `RecordSink` already
   takes a key per emit (:134), so no sink change. Child rows carry parent leaves through `$parent.*`; there is no
   engine-generated surrogate key (open question 2).
4. NULL semantics: missing path, empty list, no match, or non-numeric is `NULL`, never `0` or empty string. A
   container where a leaf is expected is `NULL` plus a counter, as `scalar()` does today.
5. **Limits, fail closed:** `max_list_elements` per list per record (default 10 000) and `max_derived_columns`
   per segment (default 64). Exceeding the first fails the **file** as `QUARANTINED_UNREADABLE`, exactly like a
   decode error (:140-144): a silently truncated aggregate is a wrong charge. Slice 2 adds
   `max_child_rows_per_record` (default 10 000). All are profile keys clamped by an engine ceiling the profile
   cannot raise.

## 6. Validation and error codes

Validated at profile read time. The Parse drawer preview and the load share `DecodeProfile`, so a profile that
previews is the one that ingests. Messages never echo file contents (the existing never-echo rule).

| Code | Condition |
|---|---|
| `ASN1-DERIVE-UNKNOWN-SEGMENT` | `derive` key is not a key of `segments` |
| `ASN1-DERIVE-UNKNOWN-OP` | operation or key outside the closed vocabulary (extra keys refused too) |
| `ASN1-DERIVE-COLUMN-CLASH` | duplicate column name, or clash with `raw.fields` / `EVENT_TYPE` |
| `ASN1-DERIVE-BAD-PATH` | list or leaf path empty, not dotted identifiers, or deeper than 8 |
| `ASN1-DERIVE-LIMIT` | a limit key above the engine ceiling, or non-positive |
| `ASN1-DERIVE-NOT-IN-GRAMMAR` | list path does not resolve to a SEQUENCE OF in the compiled grammar (checked when the grammar is available) |
| `ASN1-CHILD-UNKNOWN-SEGMENT` | slice 2: child segment not declared in `segments` |

Runtime, per file: `ASN1-DERIVE-LIST-TOO-LARGE` fails the file; counters (not failures) `derive_non_numeric` and
`derive_no_match` are surfaced in the ingest summary.

## 7. UI impact

The Parse drawer already shows each `asn1` key's effective value with its origin (profile / set here / default)
over `GET /parsers/asn1/profile`. Slice 1 shows `derive` as one read-only key ("N derived columns on M
segments", expandable column-to-operation list). A Pipeline cannot override single columns: whole-key replace
only, as for `segments`. The preview shows derived columns in sample rows. No editor in v1 (open question 4).
Vocabulary: the operations are **select**, **reduce**, **explode**; the output is a *derived column*
(`docs/GLOSSARY.md`).

## 8. Tests

- Unit: `RepeatedChildTest` over hand-built decoded maps (every op, NULL cases, group_by, limits, numeric skip).
- Profile validation: one red test per code in section 6, beside the existing `DecodeProfile` tests in
  `platform/inspecto-etl`.
- Synthetic tracked corpus: add `providers/asn-parser/corpus-synthetic/repeated_child/` (`case.json`,
  `grammar.asn`, `data.hex`, `expected.jsonl`, same layout as `backtoback`): a hand-written record with a
  `SEQUENCE OF` of {type, value} elements and a service-data list. Expected rows hold fabricated values only.
- Golden tests on real operator corpora stay opt-in and data-gated (DATA-GOV-1); a report states derived-column
  non-NULL rates and counts, never values.
- Regression: existing profiles without `derive` give byte-identical rows (`backtoback`, `huawei_framed`).

## 9. Slice plan

| # | Slice | Size | Verify |
|---|---|---|---|
| 1 | `derive` select + reduce: profile validation, `RepeatedChild`, ingester wiring, synthetic corpus case | M (about 400 lines + tests) | `-pl providers/inspecto-telecom-asn1,platform/inspecto-etl -Dtest=...`; corpus case passes; no-`derive` rows unchanged |
| 2 | `children` explode to a child segment with `$parent.*` / `$index` | M | multi-segment corpus case; per-segment row counts; cap fails the file |
| 3 | Drawer read-only display + preview rows | S | UI spec; preview returns derived columns |
| 4 | optional profile editor | S-M | operator-gated |

Slice 1 alone makes Ericsson identity, charge and volume columns and the GGSN/PGW billable-volume inputs
non-NULL; slice 2 covers TAP / NRTRDE. Per-change test rule: affected classes only; full reactor at handoff.

## 10. Risks

- **Aggregate semantics drift from legacy**: legacy `@groupBy` emitted one row per group; reduce-to-scalar sums
  the groups. Per-record totals are what billable volume needs, but per-group rows need explode (question 3).
- **Stringly typing**: VARCHAR derived columns put casting on the Transformer.
- **Cardinality surprises** in explode (caps, fail closed).
- **Grammar variance**: 7 of 24 grammars have OPTIONAL stripped, so `ASN1-DERIVE-NOT-IN-GRAMMAR` validates path
  existence only, not mandatory-ness.
- Scope creep toward an expression language; the closed vocabulary is the deliberate fence.

## 11. Open questions for the operator

1. Approve the closed-vocabulary `derive` block (no expressions) and the A-then-B sequencing?
2. Explode: is a parent-leaf copy (`$parent.<field>`) enough as the join key, or must the engine generate a
   per-record surrogate key (needs a stable record id across files)?
3. Reduce with `group_by`: sum the groups into one scalar per record (this design), or is per-group output (one
   row per rating group, i.e. explode) the required GGSN behaviour?
4. Is a read-only drawer view enough for v1, or is a profile editor for `derive` in scope?
5. Are the defaults for `max_list_elements` and `max_child_rows_per_record` (10 000) acceptable, or do real PGW
   records need more?
6. `select` equality matches the stringified leaf (numbers and enum names alike): sufficient for the
   subscription-ID type test, or is enum-name matching needed?
