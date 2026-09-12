# `RECON-CARDINALITY-1` — one-to-many / many-to-many reconciliation

**Status:** ✅ **tier 1 COMPLETE 2026-09-12 — backend AND client.** Both §6 residuals are closed; the type
is reachable end to end. Tier 2 remains demand-gated. Opened 2026-09-12. The row calls the design half the hard half, and it
is right — but for a different reason than it states, and the job is much smaller than it looks.

⚠ **Read first: this is NOT a release gate.** The row's *"until it lands the brochure is untrue"* is false.
Whitepaper **v1.2** (`db11a412`, 2026-09-11) deleted the one-to-many sentence a day **before** the operator's
"build rather than drop" decision was recorded, so the claim was already dropped when the choice was
offered. §4.1 and the §2 layer summary both now say tolerance is *"exact, absolute or percentage"* — exactly
what the code does. Nothing is untrue while this waits. See the `BACKLOG.md` row.

---

## 1. What is actually true today

Each side is **pre-aggregated to one row per key before the join runs**:
`ReconService.sideSql` (`:290-302`) emits `SELECT keys…, SUM/COUNT(measures)…, COUNT(*) AS mr … GROUP BY
keys`, and `joinChain` (`:342-351`) `FULL OUTER JOIN`s those CTEs on `IS NOT DISTINCT FROM` key equality.

⇒ So cardinality is not "a match option nobody added". **Many rows sharing a key cannot reach the join as
multiple rows at all** — they are summed away first. That is why no test exercises a duplicate key: the
`GROUP BY` made it impossible, not merely untested.

🔴 **The finding that shrinks this whole row: the multiplicity is ALREADY COMPUTED and then thrown away.**
`COUNT(*) AS mr` is that per-side, per-key row count. It survives the join as `s0_mr`/`s1_mr` (`:326`) and
`sa_mr`/`sb_mr` (`:420-421`) — and is then reduced to a **boolean** at `:495-497` (`inA`/`inB`/`inC` =
`!= null`). The number is only ever surfaced when `includeRecordCount` is set (`:514`). **A key matched by
one row on the left and three on the right is already fully distinguishable at the join; nothing reads it.**

## 2. 🔴 The semantic question, answered

The row asks: *what is a break when one row legitimately matches three?* Grounding it changes the answer.

For the canonical recon case — **one invoice against three payments** — today's arithmetic is **already
correct**: you want the payments *summed* and compared to the invoice. The aggregate is the right
comparison, not a workaround. What is missing is not the matching. It is that the engine

- **cannot tell you** the match was 1:3 rather than 1:1, and
- **cannot assert** that it should have been 1:1 — so a duplicated invoice silently doubles a sum and
  reconciles clean.

⇒ **That second point is the real defect, and it is a correctness hole, not a feature gap.** Today a
duplicate row on either side is indistinguishable from a genuinely larger value.

## 3. Two tiers — build tier 1, demand-gate tier 2

| | Tier 1 — cardinality as an **assertion** | Tier 2 — **row-level pairing** |
|---|---|---|
| Question answered | *was the match 1:1 / 1:N / N:M, and was that expected?* | *which specific rows paired with which?* |
| Join change | **none** — `mr` already carries it | must NOT aggregate; changes join semantics |
| Compare semantics | unchanged (sum-vs-value stays correct) | undefined until a pairing rule is chosen |
| Break shape | one new type, keyed exactly as today | needs a shape that names N counterparts |
| Cost | small | large |

✅ **Recommendation: build tier 1 only.** It closes the correctness hole, it is the semantic real
reconciliation wants, and it needs no change to the join or to tolerance. Tier 2 is demand-gated — it
should not be built until someone names the workflow that needs row-level pairing, because its Break
shape cannot be designed without one.

### 3.1 Tier 1 concretely

- **Config** — one new optional key per reconciliation in `ReconConfigLoader` (`:22-52`), the single place
  both `ReconRoutes.spec()` (`:230-`) and `ReconRunJob` (`:74-79`) read, so the two cannot drift:
  `cardinality: one_to_one | one_to_many | many_to_one | many_to_many`. ⚠ **Default `many_to_many`**, i.e.
  assert nothing — anything else would newly break every existing reconciliation, and today's behaviour
  asserts nothing.
- **Detection** — compare `s0_mr` / `sN_mr` against the declared shape at the row that already has both.
  No new SQL: stop discarding the count at `:495-497`.
- **A fourth break type** — `cardinality_break`, alongside `missing_left` / `missing_right` /
  `value_break`. ⚠ `ReconService.breaks` validates the type string at `:187-188` and the UI mirrors the
  set in `reconciliation-types.ts`; **both must gain it or the type is unreachable**.
- **Break payload** — carries the observed counts per side. The Incident attrs at `ReconRoutes:190-197`
  (`reconciliation`, `breakKey`, `breakType`, `column`, `runId`) then need **only** the new `breakType`
  value plus the counts; `breakKey` stays 1:1 with a key, so `POST /recon/promote`'s dedup on
  `(reconciliation, key)` keeps working untouched.

⚠ **Tier 1 is exactly why the Incident shape does not need redesigning.** The shape cannot express *which
of N counterparts* — but a cardinality assertion never needs to: it reports *how many*, against a key that
is still unique. ⛔ Only tier 2 forces that redesign, which is a further reason to demand-gate it.

## 4. Verification

- A test that feeds **duplicate keys on one side** — the case `ReconServiceTest` has never had, and the one
  that proves the `GROUP BY` is no longer hiding multiplicity.
- Mutation: make the declared cardinality unenforced and confirm the duplicate-key test goes red **on the
  assertion**, not on a sum that merely changed — the whole-feature rule, not one clause.
- ⚠ A regression test that an existing reconciliation with **no** `cardinality` key behaves byte-identically.

## 5. As-built (tier 1 backend, 2026-09-12)

`ReconService.Cardinality` (`ONE_TO_ONE`/`ONE_TO_MANY`/`MANY_TO_ONE`/`MANY_TO_MANY`) is a new `Spec`
component; `Spec.of` keeps a 4-arg overload defaulting to `MANY_TO_MANY`, so the one production call site
and every existing test compiled unchanged. `ReconConfigLoader` parses a `cardinality` key (absent ⇒
`MANY_TO_MANY`; an unknown word throws, which both callers already surface as 422).
`cardinalityBreaksSql` mirrors `valueBreaksSql` — same join, different `WHERE` — and reads the `mr` that
was already there. `breakSet` gained `alwaysCounts`, because for this break type the count IS the evidence.

⚠ **The key is emitted only when an assertion is declared** (or when asked for by name). Adding it
unconditionally broke the pre-existing `breaksSetsPathScopingAndTypeFilter`, which asserts the map has
exactly **3** entries — a good failure: it caught the payload changing for reconciliations that assert
nothing. That test now passes untouched and is the regression proof.

🔴 **The reference fixture had contained a hidden 2:1 all along.** `REL_A` carries two
`('EU','voice',100.0)` rows summing to the single `200.0` in `REL_B`, and the fixture's own comment calls
it *"matched-equal"*. It reconciled clean — the exact hole this closes — and an existing assertion at
`ReconServiceTest:101` already read `2L` records for that key without anyone treating it as a cardinality
question. ⚠ So the earlier claim that "no test feeds a duplicate key" was **wrong**: one always did.

Verified: 32 modules, **4373** tests (4367 + 6), 0 failures, 20 skipped. Mutation-proven on the WHOLE
feature (`asserted = false`, i.e. the pre-option behaviour): 4 of the 6 new tests go red reading
`expected: <1> but was: <0>`, with no compilation error, and the two that stay green are exactly the two
that should (pure enum parsing, and type-filter acceptance).

## 6. ✅ Residuals CLOSED 2026-09-12 — the pair landed together

Both consumer-side gaps this shipped with are now closed, in the same shift, rather than being left as the
kind of unpaired half `CONSUMER-PAIRS-1` exists to police.

1. **The run summary counts them.** `pairSummarySql` gains one `COUNT(*) FILTER` and `byType` gains the
   key — **both only when a cardinality is declared**, so a reconciliation predating the option gets the
   summary row it always got. ⚠ The predicate is now extracted as `cardinalityViolation` and **shared**
   with `cardinalityBreaksSql`: a summary that counted something different from the break list it
   summarises would be worse than no summary. ⚠ It counts **keys**, unlike `value_break`, which counts
   (key × column) — a key has one cardinality, not one per compare column.
2. **The client can name it.** `BreakType` gains the member, `ReconBreakSets` gains the wire key, the
   `summarize` seed gains the tally, and `breaksFromSets` maps the set — carrying the per-side row counts
   in `leftValue`/`rightValue`, which is why `breakSet`'s `alwaysCounts` exists.

🔴 **Picking the right client seam mattered.** `recon-board.ts:17-19` records that
`aggregateRecon`/`reconBreakSets` — the offline mirror — **have had no caller since the mock backend was
removed (2026-08-31)** and survive only as a parity mirror. A type wired only into *them* would have looked
done and been invisible to the running app. The live path is
`/recon/breaks` → `breaksFromSets` → the Break lifecycle, and **`breaksFromSets` had no spec at all** until
this change added three. ⚠ The offline mirror is deliberately left alone and remains in parity: with no
cardinality declared the server emits nothing, which is exactly what the mirror produces.

Verified: backend 32 modules / **4374** tests; UI **2952** passed, `recon-board.spec` 16/16, lint and
production build exit 0. Mutation-proven on both halves separately — dropping the summary key fails the
summary test on `expected: not <null>`, and dropping the client mapping fails exactly the two specs that
assert presence while the one asserting absence correctly stays green.

## 7. Open question for the operator

Tier 1 reports a cardinality violation as a **Break**. Should a `one_to_one` violation instead be a
**hard failure of the recon run** (nothing reconciles when the inputs are malformed), or a Break like any
other (the run completes, the violation is triaged)? ⚠ Break is the smaller, more consistent choice and
what this plan assumes; a hard failure is defensible if duplicate keys mean the upstream feed is corrupt.
