# Inspecto LA (Link Analysis) showcase A: mobile money (runbook, synthetic data)

*Status: written 2026-10-16 from the code, the seed script, the smoke runner and a live pass (a fresh Space from the template on a demo-auth bundle, seed `--apply` clean, smoke 10/10).
Scenes marked **UNVERIFIED (rehearse first)** have no test or probe behind one of their claims; the browser walk-through of the Graph page is not part of this pass.*

Vertical A of the [multi-vertical showcase plan](../../../../archived-documents/plans-archive/la-multi-vertical-showcase-plan.md) (archived; the rehearsal still owed is in the [roadmap](../../../../superpower/link-analysis-roadmap.md) §D5): **"the agent who never sleeps"**. A Space created from the `la-mobile-money`
Space Template holds 420 wallets, 40 agents, 40 merchants and about 5 700 transactions over 14 days (1 to 14 October 2026), with seven planted stories whose ground truths are pinned
by `LaMobileMoneyGoldenTest` and `MmLinksJobGoldenTest`. It does not replace the shipped mule and telecom demo ([demo runbook](../../module/la-core/admin-guide-demo.md)); it is a second, self-contained one.
Every value is invented. Do not make a precision or detection-rate claim from it: the plants are planted.

## 0. Pre-flight

| # | Step | Command | Expect |
|---|---|---|---|
| 1 | Demo-auth bundle on a free port | as the [demo runbook](../../module/la-core/admin-guide-demo.md) section 0, plus `-Dla.embed.enabled=true` for scene M-12 | `serve-demo` banner |
| 2 | Lay the Space down (offline, before the server starts) | `node tools/seed-la-mm.mjs --scaffold <spacesRoot>` | `scaffolded: _templates, la-mobile-money` |
| 3 | Start the server on `<spacesRoot>`; the three Pipelines ingest the 14 landed days and the master files | wait for `data/wallet_txn/database` | a few seconds |
| 4 | Fold the link Dataset (the Job also runs every 10 minutes) | sign in as `mm.manager`, `POST /api/v1/spaces/la-mobile-money/jobs/mm_links/trigger` | Run `SUCCESS`, `5 777 row(s)` (5 732 transactions + 45 shared pairs) |
| 5 | Seed the runtime state | `node tools/seed-la-mm.mjs --base http://127.0.0.1:<PORT> --apply` | `failed: -`, a pending request and the `mm-structuring` Alert staged |
| 6 | Smoke | `node tools/la-mm-smoke.mjs --base http://127.0.0.1:<PORT>` | `10/10 passed` |

The index build follows five minutes after each `mm_links` run (cron, because the `la.index.build` Job type fires only on a clock or a Pipeline commit, never on another Job's output); trigger `la_index_build`
as `mm.manager` to skip the wait (`admin` holds no `canOperateRuns`: 403; rehearsed 2026-10-24). A Space path under about 150 characters avoids a Windows "filename too long" on the events store.

### Personas (Demo Users; names deliberately differ from `la-showcase`)

| User | Role | Does |
|---|---|---|
| `mm.analyst` | `mm-analyst` | lead of the Investigation; runs algorithms, builds the index, authors the Alert Rule; **no Reveal, no approval** |
| `mm.manager` | `mm-manager` | the second person: approves expansions, presses Run on Jobs, owns the Case |
| `mm.reviewer` | `mm-viewer` | read-only member (the regulator liaison) |
| `mm.outsider` | `mm-viewer` | not a member: sees nothing (404) |
| `admin` | `admin; fraud-analyst; la-settings` | lead; Reveal; Settings; mints embed links; opens the Case (a Case from an entity of a classified Dataset needs Reveal) |

The saved views (`mm_money_trail`, `mm_structuring_band`, `mm_device_farm`) project `mm_links_dataset` (`src`, `dst`, `kind`; `ts`, `amount`, `via` on the link).

## 1. The planted stories (ground truth)

| # | Story | Ground truth | Pinned by |
|---|---|---|---|
| 1 | Agent-assisted structuring | agent `AG-18` cashes in for **25** wallets (`STRUCT-01..25`) inside 36 h (6 Oct 18:00 to 8 Oct 04:24); every amount in the top 5 % band under the Tier-1 limit 5 000 (4 750 <= amount < 5 000); each wallet pays `HUB-01` | golden test, smoke (Measure `structuring`: 1 entity, 25 legs, 25 payers) |
| 2 | Mule chain | `HUB-01` forwards **98 %** within 10 minutes to `RELAY-1/2`, on to `RELAY-3`, cash-out at `AG-39`; shortest path hub to `AG-39` **3 hops**; Maximum flow **25** (one unit per transaction) | golden test (amount-weighted flow = forwarded total), smoke |
| 3 | Device farm | `FARM-1..9` share handset `DEV-FARM-01` and hold **nine different** national ids; 36 `shared_device` pairs; Jaccard of their counterparty sets above 0.6 | golden test, `MmLinksJobGoldenTest` |
| 4 | Round-trip loop | `LOOP-1 > 2 > 3 > 4 > 1`, the same 1 500.00 five times: the **only** directed cycle | golden test, smoke |
| 5 | Dormant awakening | `DORMANT-1` (opened 2 Nov 2025, prior lifetime volume 150.00 in the KYC reference) moves 6 000.00 out on 11 and 12 Oct = **40 x** | golden test |
| 6 | Benign hub | `MER-ELECTRIC`: **300** payers, one-way (no outflow); tops the default Suspicion score and the Degree centrality anyway | golden test |
| 7 | Alert story | the `mm-structuring` Alert (aggregate only) fires on story 1; open the Incident and the Case | seed, smoke |

Benign look-alikes by design: a household of three wallets sharing one national id (`shared_id`), a family phone shared by four wallets (`shared_device`), eight wallets whose handset is `UNKNOWN`, six blank, five `000000000000000`
(placeholders, held under the pair cap, so **only** the Job's explicit exclusion keeps them out; pinned).

## 2. The 12-minute version

| Min | Scene | Click path | Say |
|---|---|---|---|
| 0-1 | Data trust | open `mm-structuring-case`; read the footer counts and the data-trust strip | "14 landed days, one event-day column; this is synthetic and the plants are planted" |
| 1-3 | M-1 Ladder and masking | seed `HUB-01`, expand one hop (25 payers, 2 relays); entities show as `masked:` tokens | "typed masking: the analyst sees structure, not wallets; Reveal is `admin`'s, audited" |
| 3-4 | M-2 Four-eyes | as `mm.analyst` press Expand with no limit: held; sign in as `mm.manager`, Approvals, approve | "above 100 neighbours needs a second person" |
| 4-6 | M-3 Structuring Measure | Value Measures, `structuring`, 4 750 to 5 000 | "one wallet, 25 legs, 25 payers, all just under the 5 000 limit; the cash-in agent is `AG-18`" |
| 6-8 | M-4 The chain | Shortest path `HUB-01` to `AG-39` (3 hops); Maximum flow (25); `passThrough` Measure (0.98) | "the money does not stay: 98 % leaves within minutes" |
| 8-9 | M-5 The loop | Find cycles on a `LOOP-1` neighbourhood | "exactly one cycle: four wallets passing 1 500 round for fees" |
| 9-10 | M-6 The look-alike | Suspicion score: the biller first; open the factor breakdown | "high connectivity is not guilt: 300 payers, nothing out, held on expand" |
| 10-11 | M-7 Alert to Case | Alerts, `mm-structuring`; open the Case as `mm.manager` | "standing detection, aggregate only, then Incident and Case" |
| 11-12 | M-8 Embed | M-12 below | "the regulator sees a read-only masked view in its own page" |

## 3. Scenes

Algorithm scenes run **client first** (instant, small) and then **Run on server** (progress, cancel, result parity). A run is always seeded: an all-nodes canvas is a dust cloud. After seeding `HUB-01` and `AG-39` and
expanding three times the working set holds about 219 entities; seed `LOOP-1` for the loop.

| Scene | Algorithm / feature | Seed and settings | Expected (verified live unless marked) |
|---|---|---|---|
| M-1 | Degree centrality | `HUB-01`, 3 expansions | `MER-ELECTRIC` first (degree 110 in the working set), `HUB-01` second (27) |
| M-1 | Connected components | the same | the structuring cluster is one component with the relays and `AG-39` |
| M-1 | Neighborhood, Ego network | node `HUB-01`, 1 hop | 25 payers + `RELAY-1/2` around the hub |
| M-1 | Descendants | node `HUB-01` | `RELAY-1`, `RELAY-2`, `RELAY-3`, `AG-39` (4) |
| M-4 | Shortest path | `HUB-01` to `AG-39`, direction out | 3 hops, 4 nodes |
| M-4 | All paths | the same, limit 10, max hops 8 | routes through `RELAY-1` and `RELAY-2` (parallel transaction edges repeat them) |
| M-4 | Weighted shortest path | the same | the same 4 nodes |
| M-4 | Maximum flow | `HUB-01` to `AG-39` | **25** (capacity is the events count per link, an integer: one per transaction); the amount view is `passThrough` (inbound 121 726.88, outbound 119 292.34, ratio 0.98) |
| M-5 | Find cycles | `LOOP-1`, 3 expansions, limit 50, max length 8 | exactly 1 cycle, 4 nodes |
| M-6 | Suspicion score | default weights | the biller first; the nine `FARM-` wallets in the top 15 (k-core and triangles); with weights degree 1, betweenness 3, PageRank 3, core 0, triangles 0 `HUB-01` ranks in the top 3 (golden test) |
| M-9 | Propagated risk | seed risk 100 on `AG-39`, default weights | `RELAY-3` 100, then falling by distance; the chain lights up |
| M-3 | Value Measures | `structuring` 4 750 to 5 000; `passThrough`; `cashOutConcentration` with `minShare` 0.05 | 1 entity (25 legs, 25 payers); 4 entities at ratio about 1.0 and the hub at 0.98 |
| M-10 | Temporal pack, dormant wake | window 11 to 12 Oct on `DORMANT-1` | 6 000.00 out; the KYC reference (Enrichment) shows prior lifetime volume 150.00: 40 x. **UNVERIFIED (rehearse first)**: the pack is not shipped for this vertical; read the two cash-ins and four p2p legs on the canvas |
| M-11 | Entity resolution | saved view `mm_device_farm`; Entity List `farm-wallets`; identity `FARM-1` = `FARM-2` | nine wallets on one handset, nine ids: the handset links them and the ids do not (the benign household is the contrast) |
| M-2 | Four-eyes | see the 12-minute table | an unbounded expand answers `pending`; the seed leaves one in `mm.manager`'s inbox |
| M-7 | Alerts to Case | `mm-structuring` Alert; Case `Structuring through HUB-01` assigned to `mm.manager` | the Alert names counts, never a wallet |
| M-12 | Embed link and portal | as `admin`, Share, mint; Settings, Link Analysis, Embed, add `http://localhost:8140`; serve [`regulator-portal.html`](../../../../../spaces/_templates/la-mobile-money/portal/regulator-portal.html) with `python -m http.server 8140` in its folder; paste the link | the frame shows the read-only masked Investigation; Revoke, and the frame is blocked |
| M-13 | Dossier | as `admin`, build the Dossier of `mm-structuring-case`, then Verify | verifies; alter one byte of the manifest and Verify fails (see the demo runbook, tamper step). **UNVERIFIED (rehearse first)** on this Space |
| M-14 | New data lands | copy `data/staged/wallet_txn/WALLET_TXN_20261015.csv` into `data/inbox/wallet_txn`; wait for the poll, the `mm_links` run, then the index build | a new index version; no new cycle, no new band leg (golden test). The gap-day file `...20261017.csv` leaves 16 Oct missing for the coverage read |

## 4. Decisions and findings (this lane)

* **Own template, no shared kit.** The planned shared helper was dropped; generator, seed, smoke and runbook are self-contained. `admin` is defined exactly as in `la-showcase` (`DemoUsers` refuses a user id defined differently in two Spaces), so its role names (`fraud-analyst`, `la-settings`) exist in this Space's `roles.toon` too; the other four ids are new (`mm.*`) and `LaMobileMoneyTemplateTest` pins both facts.
* **Plan section 2.A story 3 is internally inconsistent** ("9 different National-IDs" and "shared-ID links agree"): nine different ids cannot produce `shared_id` links. Built literally: the handset links the farm, the ids do not, and `shared_id` appears only for the benign household. That contrast is the demo beat.
* **Maximum flow counts transactions, not money.** The server weights an edge by an integer events column (`eventsCol` refuses a DOUBLE amount, 422), so the in-product flow is 25 and the amount figure comes from the `passThrough` Measure. The golden test pins the amount-weighted flow in Java.
* **The Suspicion score does not hide the biller.** A 300-payer star tops every default factor; the plan's "correctly not flagged" is realised as an explained high score (one-way, nothing out), and the golden test pins both the default order and the flow-weighted order that puts the hub in the top 3.
* **Ordinary traffic is a layered DAG with repeated counterparties** (cash-in agents to group A to group B to cash-out agents and merchants): that keeps the loop the only directed cycle and the graph sparse enough for the hub to stand out. Consequence: ordinary wallets use either a cash-in or a cash-out agent, never both.
* **Shared links are one direction (lower id to higher id)**, unlike the telecom Job's both directions, so they cannot form a 2-cycle. A column cannot mix Entity Types, so `src`/`dst` carry the `WALLET` classification for wallets, agents and merchants alike; the agent register is classified `AGENT`.
* **The index build runs on a cron**, because `la.index.build` fires only on a clock or a Pipeline commit, not on another Job's output.
* **A Case opened from an entity needs Reveal**, so the seed opens it as `admin` (and links it as the Investigation's lead), keeping `mm.analyst` without Reveal for the masking beat.

## 5. Reset and regenerate

Regenerate the corpus byte-identically with `python tools/gen-la-mm-data.py` (`node --test tools/gen-la-mm-data.test.mjs`). Reset a demo by deleting the Space folder and scaffolding again; the seed is idempotent
and safe to re-run.

## 6. Browser rehearsal, 2026-10-24

Driven in the browser as `mm.analyst`, `mm.manager` and `admin` on a fresh Demo Auth bundle (smoke 10/10): ladder and Connect seeds, Degree, PageRank (client and server, identical order), Find cycles (one, four nodes), Suspicion score (biller first), Structuring (1 entity, 25 legs, 25 payers, 121 726.88), four-eyes approve by `mm.manager`, Alert to Investigation, Dossier build, verify and tamper by upload, embed mint, view and revoke. M-10: the Entity profile of `DORMANT-1` shows opened 2 Nov 2025 and six rows on each of 11 and 12 Oct; the 150.00 prior volume is in `mm_kyc_dataset`, not on the profile, so the 40x is the presenter's arithmetic. Numbers and traps: [the cross-vertical tour](../la-showcase/admin-guide-cross-vertical-tour.md).
