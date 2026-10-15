# Link Analysis: technical / security review demo plan (whitepaper claim to live scene)

**Status: PLAN 2026-10-15, not yet rehearsed.** Audience: CISO, lead architect, forensic and audit reviewers. Length: 35 to 40 minutes plus questions.
Purpose: every PROVEN row of the whitepaper's Claims Register (section 7 of the Link Analysis whitepaper, `LINK_ANALYSIS_WHITEPAPER.md`, written 2026-10 and not yet committed)
is shown live or by a named test, and every NOT YET / DOCUMENTED row is said out loud. It reuses the scenes, personas and seed of the
[demo runbook](../ops/la-demo-runbook.md) (S1 to S15); it does not replace it. Variants (Postgres store, Personal) are in the
[variants runbook](../ops/la-showcase-variants-runbook.md); Q&A answers are in the [stakeholder Q&A](../ops/la-stakeholder-qa.md).

When this ships: distil the as-built facts into `okf/frontend/features/link-analysis.md`, then `git mv` this plan to `archived-documents/plans-archive/`.

## 1. Ground rules

* Rehearse on a **fresh full bake** of the `la-showcase` Space (runbook section 0); run the 9-probe pre-flight 30 minutes before.
* The live corpus is small. It shows behaviour, not scale. Scale is shown from the recorded benchmark only (scene T9).
* Say the NOT YET rows before a reviewer finds them: 10^9 scale, retention purge, published precision rates, multi-pod HA, native Hive, real-IAM on the standalone app.
* Order is by trust question, not by feature: *what is on screen, who may see it, what happens when two people disagree, what can be proven afterwards*.

## 2. Run of show

| # | Min | Whitepaper claim (section) | Scene (runbook ref) | Persona | Proof shown | Evidence if challenged |
|---|---|---|---|---|---|---|
| T1 | 3 | In-process, no external graph database (1, 4.1) | Task manager / process list: one JVM; Dataset behind the graph | `fm.analyst` | S1 projection from a Dataset; footer states nodes, links, caps | architectural invariant; fence guards |
| T2 | 4 | Explainable Suspicion score, Propagated Risk (3.2, 3.3) | S1 Suspicion score, then factor breakdown | `fm.analyst` | hub `ranks first`; per-factor attribution | `GraphSuspicionParityTest`, `ControlApiGraphRunTest` |
| T3 | 3 | 29 algorithms, TypeScript and Java parity (3.1) | S5 Pattern match, Run on server (hub, 96 legs, 12 payers) | `fm.analyst` | server result equals the client result | `GraphAlgorithmsParityTest` |
| T4 | 3 | Value Measures, mule ring 97 % cash-out (3.5) | S6 Pass-through, Cash-out (`TILL-06`) | `fm.analyst` | 13 accounts, relays 0.985; 97 % | `ControlApiLaShowcaseFiguresTest` |
| T5 | 5 | Keyed masking, anti-oracle, audited Reveal (5.2, 5.4) | S3 typed versus all; try a raw id lookup; S4 Reveal | `fm.analyst`, then `admin` | alias on canvas and lists; raw-id lookup refused; Reveal writes an audit row | `ControlApiExplorationMaskingTest`, `ControlApiInvestigationOversightTest` |
| T6 | 4 | 7-step gate order, strict 404 (5.1) | S7 `curl` block: 401, 404, 403, 422 in order; members and roles | terminal, `ra.analyst` | each status with its body, no existence leak on 404 | `ControlApiGeoLinkModuleGateTest`, `ControlApiInvestigationMembersTest` |
| T7 | 4 | Four-eyes, self-approval blocked (5.3) | S8 unbounded expand waits; `fm.analyst` tries to approve own request (403); `fm.manager` approves | `fm.analyst`, `fm.manager` | Waiting for approval, 403, then approved | `ControlApiInvestigationOversightTest` |
| T8 | 5 | Op log, deterministic replay, drift on file append (2.2) | S2 open `mule-hub-case`, Replay versus Re-read after a late file lands | `fm.analyst` | sealed replay identical; Re-read shows drift | `InvestigationEvaluatorTest`, `ControlApiReplayDriftOnFileAppendTest` |
| T9 | 4 | SHA-256 Dossier, tamper-evident (2.3) | S11 Verify, then the prepared tampered manifest shows NOT verified | `fm.analyst` | verified, then not verified with the failing step named | `ControlApiDossierTest` |
| T10 | 3 | Drafts, atomic promotion (2.4) | S9 fork, step, rebase, promote | `fm.analyst`, `fm.manager` | the base Investigation is untouched until promote | draft tests in the runbook |
| T11 | 3 | Ephemeral embed, auto-revoke (5.5) | S14 mint, public GET with headers, revoke, repeat (403/404); per-Space frame allow-list | `admin` by `curl`, then the viewer | token shown once; DENIED and REVOKED audit rows | `ControlApiInvestigationEmbedTest` |
| T12 | 2 | Data trust strip (freshness, coverage, quarantine) | the Data trust strip on a graph; one stale or late file | `fm.analyst` | as-of, Files, in-the-index state | `docs/ops/la-data-trust-live-drive.md` |
| T13 | 2 | Scale and limits (6, 7) | S15 evidence slide, recorded numbers only: 10^8 edges lookups about 43 ms, build 368 to 852 s | slides | benchmark table | `InvTraversalBench`; 10^9 run plan `docs/ops/la-index-scale-run-plan.md` |

Total 45 minutes at rehearsal pace. **Cut line for 35 minutes:** drop T10 and T12, shorten T3. **Hard keep:** T5, T6, T7, T9 (the four a security reviewer tests).

## 3. Say out loud (NOT YET / DOCUMENTED)

| Claim | State | What to say |
|---|---|---|
| 10^9 edges | NOT YET | measured to 10^8; 10^9 hit the disk floor and 5x10^8 hit OOM at the 8 GB build cap on the test box |
| Retention purge of closed Investigations | NOT YET | append-only by decision D-U8, no purge route |
| Published false-positive / precision rates | NOT YET | depend on customer thresholds |
| Multi-pod active-active HA | NOT YET | single-database lease proven on one machine; `HA-DR-DRILL-1` |
| Native Hive / Kerberos JDBC | NOT YET | landing Parquet through file Collectors (D-ING5) |
| Real-IAM sign-in on the standalone app | DOCUMENTED | runbook exists; the demo build uses loopback Demo Users, so this is not an SSO demonstration |
| Full-disk consistent rewrite | DOCUMENTED | an attacker with root filesystem write can recompute hashes; anchor the Dossier root externally |

## 4. Preparation checklist

1. Fresh bake and seed: `tools/seed-la-showcase.mjs`; pre-flight 9/9 probes green.
2. Prepare the **tampered manifest** (T9), a **late file** for the drift scene (T8), and an **admin token** in `$ADMIN` (T6, T11).
3. Two browser profiles (analyst, manager) so T7 needs no sign-out.
4. A recorded fallback (screen capture) per scene: T8, T9 and T11 are the ones a cold box can break.
5. Print section 3 for the presenter; it is the part most likely to be skipped under time pressure.

## 5. Open questions for the operator

* Is a **real-WSO2 or Keycloak sign-in** wanted in the room (it exists: `la-app-real-signin-runbook.md`), or stay on Demo Users?
* Should T12 (data trust) be kept in the technical cut? It postdates the 2026-10-14 go/no-go rehearsal.
* Rehearse once as a dry run with a second person playing the sceptical reviewer, then record the result as a new section in the demo runbook.

## References

* Whitepaper: the Link Analysis whitepaper (`LINK_ANALYSIS_WHITEPAPER.md`, under the stakeholders docs folder); plain text on purpose, because it was untracked when this plan was written. Make this a link once it is committed.
* Runbook: [`la-demo-runbook.md`](../ops/la-demo-runbook.md), section 4 (claims we do NOT make) and section 7 (last rehearsal)
* Readiness plan: [`la-demo-readiness-plan.md`](la-demo-readiness-plan.md)
