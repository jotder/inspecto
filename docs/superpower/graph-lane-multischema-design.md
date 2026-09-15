# Design — `GRAPH-LANE-MULTISCHEMA-1`: let the graph lane carry a multi-schema write

**Status:** design pass DONE 2026-09-16; **one operator call owed** before code (§5 below).
**Row:** `BACKLOG.md` §3 · **blocks:** §2 Row 15 (ELT §6 step-4 deletion half) together with
`GRAPH-LANE-RULE-ROUTED-1`.

## 1. What the gate showed

`mvn -o -Dingest.lane=graph test` refuses 13 `inspecto-engine` tests. **12 of them are this gap**, all with
one message: *the lifted graph's sink count (3) differs from sinks[] (1)* (`events_etl`, `typed_record_etl`).

## 2. Three findings — the last one refutes how the row was first written

Grounded 2026-09-16 with a scratch spike (reproduced in §4) plus a caller sweep, not from the row's prose.

**(a) The lifted shape.** A `segments:` config lifts to one `map_<KEY> → sink_<KEY>` chain per schema, plus a
quarantine sink on a **control** (`unmatched`) edge:

```
acq -[data]-> parse
parse -[route:CALL]-> map_CALL -[data]-> sink_CALL
parse -[route:SMS]-> map_SMS  -[data]-> sink_SMS
parse -[unmatched]-> quarantine
```

So "3 sinks" counts **two data sinks plus quarantine**, against **one declared destination**. The count is
comparing sink NODES with destinations — two different things.

**(b) The executor ALREADY carries this — it is not an executor feature.** `PipelineExecutor.execute` takes
`Map<String,String> seeds`, not one seed (multi-source seeding shipped for the job lane, T32 Phase C). Seeded
with `{map_CALL: t_call, map_SMS: t_sms}` it walks **both** trees and commits both branches:

```
sinks written      = [sink_CALL <- t_call, sink_SMS <- t_sms]
committedBranches  = [sink_CALL, sink_SMS], sourceFinalized = true
```

Quarantine is correctly skipped (a control edge carries no seeded relation). ⚠ Note `ConsignmentGraphRunner.
dataFedSinkCount` reports **1** for this graph and `engages` reports **false** — those predicates deliberately
treat per-schema dispatch as trunk (`hasRouteFedChain` excludes it by name). They disagree with what the
executor actually did, and that is fine: they gate **route** pipelines, and the commit log keys on sink ids.

**(c) 🔴 The write path never needs more than ONE seed — which refutes both the row's "seeding contract is
violated structurally" and the multi-seed design (b) suggests.** `UnionModeIngester:122-168` loops **per
segment**: it materialises `transformed_<KEY>`, then calls `writeAndTrace(..., dbDir=database/<segKey>, ...,
writeScope=segKey)` **once per segment**. Every such call is already a single-destination write of one
segment's mapped table. What is wrong is only that the admission lifts the **whole** pipeline on every one of
those calls and compares 3 sink nodes against 1 destination — **it asks its question at pipeline granularity
while the caller works at segment granularity.**

## 3. The change

Thread the call's segment identity into the admission, and admit against the sub-chain that call actually
writes (`map_<segKey> → sink_<segKey>`) instead of the whole lifted graph.

- `ConsignmentIngestStrategy.admittedLift` / `graphLaneCarries` / `flatReason` take the segment key;
  `seedFeedingTheWrite` then resolves to that segment's `map_<segKey>` — still **one seed, one sink**, so the
  seeding contract is untouched and `ConsignmentGraphRunner.Input` needs no new field.
- ⚠ **`writeScope` cannot be used as the segment key without a test.** It is overloaded across the four
  callers: `""` for a whole-batch write (`CsvIngestStrategy:182`), the chunk base name for a chunked write
  (`NativeCsvStreamingEngine:279`), the segment key for a segment write (`UnionModeIngester:157`). Membership
  in `cfg.schemas().segments().keySet()` is the discriminator, and it is exactly checkable.
- Quarantine needs no handling: it is control-fed and the executor never commits it as a branch.

**Not in scope:** the sink-count check for genuine `sinks[N]` fan-out, which is a real N-destinations-of-one-
branch case and already works.

## 4. Reproducing the evidence

The spike was a scratch test in `com.gamma.inspector` (deleted after the pass — it seeded the two map nodes of
the `ConsignmentIngestorPluginTest` fixture and ran `PipelineExecutor.execute` with a capturing `SinkWriter`).
To re-derive: lift that fixture's config, print nodes/edges, then execute with
`Map.of("map_CALL", t1, "map_SMS", t2)`.

## 5. ⛔ The operator call owed before code

**Does `-Dingest.lane=auto` start diverting multi-schema segment writes to the graph lane, or does the graph
lane merely become *able* to carry them (`graph` only), leaving `auto` flat?**

- **Flip `auto`** — the parity gate goes green for these 12 and the lane gets real production exposure, but
  the live write path changes for every `segments:` pipeline at once.
- **`graph` only** — no production change, but it creates a path that only the gate exercises. ⚠ This repo has
  been bitten by exactly that (a well-tested mirror that no live caller reached), so it should be a deliberate,
  time-boxed choice, not the default.

⛔ Do not pick this while implementing: it is a production-behaviour call, and the gate can be made green
either way.

## 6. Verification

`mvn -o -Dingest.lane=graph -pl inspecto-engine -am -Dsurefire.failIfNoSpecifiedTests=false test` ⇒
`ConsignmentIngestorPluginTest`, `ConsignmentIngestorPluginDeepTest`, `TypedRecordIngesterTest` green
(12 of the 13). `DecisionRuleWiringTest` stays red until `GRAPH-LANE-RULE-ROUTED-1` lands. Then re-run the
whole suite under the flag — the gate is one flag, so run it, do not reason about it.
