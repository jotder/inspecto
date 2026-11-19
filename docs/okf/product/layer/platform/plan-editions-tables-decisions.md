---
type: Capability
title: Editions & packaging (PKG) — capability spec — Specification — the plan's tables; decisions and not built
description: Part 3 of 4 of Editions & packaging (PKG) — capability spec: Decisions (dated one-liners); Not built
status: current
item: layer.platform
audience: internal
verified: 2026-11-19 last-edit
---

# Editions & packaging (PKG) — capability spec — Specification — the plan's tables; decisions and not built

Part 3 of 4 of [Editions & packaging (PKG) — capability spec](plan-editions.md) · previous: [Specification](plan-editions-specification.md) · next: [Refused, as-built pointers and verification](plan-editions-refused-verification.md).

## 3. Specification (continued)

### 3.14 The six tables the plan held — verbatim, because counts cannot carry them

⚠ Distilled 2026-09-09 when `deployment-topology-plan.md` was archived. §3.9–§3.13 above deliberately
render the plan's design as prose with counts, which is right for narrative and **wrong for numbers an
operator or an auditor has to act on**: a count cannot tell you the RTO you signed up to, or which
acceptance row failed. These six are reproduced rather than summarised.

#### Indicative sizing (§4)

| Tier | App host | Postgres | Notes |
|---|---|---|---|
| T1 | 2 vCPU · 8 GB · SSD 20 GB+ | — | ~90 MB idle footprint (NFR-2) |
| T2 | 4–8 vCPU · 16–32 GB · SSD 100 GB+ | optional, 2 vCPU · 8 GB | `memory_limit` ≈ 25–50 % RAM ÷ concurrency |
| T3 | 8–16 vCPU · 32–64 GB · SSD 250 GB+ | 4 vCPU · 16 GB, PITR | + gateway/IAM hosts per vendor sizing |
| T4 | T3 × 2 sites | + streaming replica | + replication bandwidth ≈ daily delta |

⚠ **`memory_limit` ≈ 25–50 % RAM ÷ concurrency is the sizing rule**, and it is the only stated guidance
anywhere for a knob whose absent 2 GB "default" has been asserted in seven documents (§5.3).
✅ **GAP-4 closed 2026-09-26 (session decision): the rule is now the code default** — unset,
`memory_limit` = 40 % of the RAM the JVM sees ÷ 4 (the Run bound's default), floored at 1 GiB, so the
D11 pair's worst case is ~40 % of the host. A deployment that raises `maxConcurrentRuns` or runs many
concurrent batches must lower `memory_limit` itself (`-Dprocessing.duckdb.memory_limit` or Settings ▸
Scheduler); the divisor is fixed, not live. Reasoning and scope: `okf/product/module/engine/spec-duckdb.md`.

#### Failure → tier response (§5)

| Failure | Tier response |
|---|---|
| Process crash | Service wrapper auto-restart; `/ready` gates traffic; the in-flight run resumes or re-runs idempotently |
| Disk loss | Restore the last verified backup; Datasets rebuild from re-ingest where retained |
| Postgres down | 🔴 Stores **degrade to memory (alert!)**; reconnect means a restart after DB recovery. ⛔ **Except the operational objects on Advanced** (Incidents, Cases, notes, links, tags — `OBJECTS-BACKEND-DEFAULT-MEMORY-1`, 2026-09-25): `-Dobjects.backend=postgres` (set by the Advanced `serve.*`) never degrades — boot refuses without a PostgreSQL URL, and a store that will not open stops its Space |
| Node loss | T2/T3: rebuild from bundle + restore, within the RTO below. T4: promote the standby |
| Site loss | T4 only: DNS/LB failover to site B |

The Postgres row is the one §3.11 quotes a cell of: degradation is silent by design, which is why the
acceptance block has to assert every subsystem is `UP` rather than merely that the service answers.

#### Recovery targets — D6, signed 2026-09-06 (§5)

⛔ **These are contract service levels, not suggestions.** They existed **only** in the archived plan until
2026-09-09; `plan-editions.md` recorded that "recovery targets as contract service levels" had been signed
without ever carrying the numbers.

| Tier | Backup cadence | RPO | RTO | DR drill |
|---|---|---|---|---|
| T1 | daily `config_backup` (04:00 sample job) | ≤ 24 h | ≤ 4 h (reinstall + restore) | — |
| T2 | hourly config + daily full (post-CHECKPOINT) + volume snapshot | ≤ 1 h | ≤ 1 h | yearly restore test |
| T3 | T2 + Postgres PITR/WAL archiving + off-site copy | ≤ 15 min | ≤ 2 h (rebuild) | half-yearly restore drill |
| T4 | T3 + spaces-tree sync + streaming replica | ≤ 5–15 min (async) | ≤ 30 min (promote) | quarterly failover drill |

⚠ `compliance/evidence/rto-rpo-statement.md` still carries `<OPERATOR TO STATE>` placeholders and an empty
drill table. Transcribing these into it is **G6's** job, not a documentation edit — it needs the drill
record too, and an auditor-facing statement should be filled by the operator, not inferred from a spec.

#### Preflight — the nine rows §3.12 does not reproduce (§8)

§3.12 lists the five that encode a lesson. The rest are the system requirements, and `SCR-1`'s acceptance
is "catches every §8 row", so the list has to exist somewhere checkable:

| Check | Detail |
|---|---|
| Artifact integrity | `.sha256` match always; `.asc` GPG signature when policy requires |
| OS / arch | Matches the bundle variant (Windows vs `-linux`); **glibc** for the DuckDB JNI on Linux |
| Runtime | `runtime/` present, or host JDK ≥ 24 (≥ 25 with agent/intelligence modules) with native-access support |
| Resources | CPU/RAM/disk ≥ the tier row above; temp-dir capacity vs `max_temp_directory_size` |
| Port | `control.port` (default 8080) free |
| Filesystem | Write permission on the bundle root and the `spaces/` tree |
| OS limits (Linux) | **open-files `ulimit`** sane for Parquet partition fan-out |
| Network posture | CORS origin decided (the firewall half is in §3.12) |
| Postgres (if used) | Reachable; `${ENV:…}` credentials resolve |
| Gateway (T3) | Gateway JWKS reachable; the `X-JWT-Assertion` header agreed |
| Model host (optional) | Local model endpoint reachable |

#### Acceptance — VER-1…VER-12 (§9)

⛔ No `VER-` identifier existed anywhere in the current tier before 2026-09-09, yet `SCR-6`'s whole
acceptance is "§9's acceptance block as a script".

| Row | Block | Assertion |
|---|---|---|
| **VER-1** | basic | `/health` = 200 `{"status":"UP"}`; `/ready` reports the expected pipeline count |
| **VER-2** | basic | `/api/v1/bootstrap` `data.edition` matches intent. 🔴 **Cannot pass for Advanced** — the value is two-valued (§3.5); probe `data.features` instead, noting four of nine optional modules have no flag |
| **VER-3** | basic | `/api/v1/health/details` shows every intended subsystem `UP` — **not** silently `NOT_CONFIGURED` or an in-memory fallback. The counter-check to graceful degradation |
| **VER-4** | basic | `/metrics` scrapes and parses (Prometheus text 0.0.4) |
| **VER-5** | basic | functional round-trip: `seed-inbox` → poll → Dataset partitions written → run + events visible; UI loads with client-side route fallback |
| **VER-6** | standard | unauthenticated call → 401; valid IAM token → 200 with `permissions[]`; expired token rejected |
| **VER-7** | standard | handshake pins TLS 1.3 (T2b) or proxy chain + HSTS (T2a); `/metrics` unreachable from a non-monitoring address |
| **VER-8** | standard | incident round-trip via `seed-ops` with `objects.backend=db`, still present after a service restart |
| **VER-9** | standard | `config_backup` (`dryRun=true`, then real) → `backup_verify` green → restore dry-run into a scratch space |
| **VER-10** | advanced | request via the gateway with Backend-JWT only → accepted; tampered or unsigned assertion → rejected |
| **VER-11** | advanced | tenant-A subject reading a tenant-B space → deny + an `access.denied` event present (decision-audit evidence) |
| **VER-12** | advanced (T4) | failover drill: promote the standby per runbook **inside the RTO target**, then fail back |

#### Phase sequencing, and the T4 mechanics (§6, §11)

| Phase | Contains |
|---|---|
| **0** hygiene & unblockers | the bind flag, `SCR-9`, `SCR-10`, `SCR-8` — makes all three editions packagable and the docs honest |
| **1** script suite | `SCR-1`…`SCR-6` — T1/T2 installable and verifiable **by a client operator, not just by us** |
| **2** T2 reference deployment | one real server, both TLS variants, Postgres-backed, monitored; validates the sizing and recovery tables above; also closes OPS-5's "needs a live deploy" |
| **3** T3 reference | live Keycloak + gateway pair (resolves D8/GAP-7), tenant-isolation evidence pack |
| **4** T4 DR pack | standby runbook + quarterly-drill template; publish the SLOs per D6 |
| **5** (optional, per D1) | container image |

⚠ **Each phase ends with its own acceptance evidence pack** — that is the exit criterion, and it is what
makes the phases sequential rather than a wish list.

**The T4 promote runbook order**, which is the part that must not be improvised: stop A if alive → promote
Postgres → start B → repoint LB/DNS → the full acceptance block. Replication is Postgres **PITR/WAL
archiving** plus a scheduled post-CHECKPOINT `spaces/` sync (`robocopy`/`rsync`).

⚠ **`SCR-3`'s vendor decision is CLOSED — this paragraph used to say it was open** (distilled from the
archived deployment-topology plan and not refreshed when the row shipped, so the same file carried both
states; see the `SCR-3` row in §3.14). The systemd side was always settled (`Restart=on-failure`,
`WorkingDirectory=` the bundle root, `EnvironmentFile=`). The Windows half was decided **on grounding
2026-09-11**: ⛔ **`sc.exe` cannot host a JVM** — it expects a service-control binary and a plain
`java -jar` under it dies with error 1053 — so neither `sc.exe` nor WinSW was taken and Windows gets a
**boot Scheduled Task** instead.

## 4. Decisions (dated one-liners)

| Date | Decision | Who |
|---|---|---|
| 2026-09-10 | **Standard ships as its own release artifact** — the pipeline packages, signs and publishes three bundles and refuses a Standard bundle that carries the policy jar; a Standard customer never receives Advanced identity (`STANDARD-BUNDLE-1`, chosen over licence-key gating and "document the exposure") | operator |
| 2026-06-16 | **Hand-rolled bearer-token authentication removed from the common core.** Basic is genuinely authentication-free and authentication becomes an edition concern behind a seam — so fixes land once, and the code matches "editions add modules, never branches" | engineering |
| 2026-07-06 | **The authenticator seam and its gate ship in the core, edition-neutral** and no-op when absent; the authentication module supplies the implementation, profile-gated | engineering |
| 2026-07-07 | **The trimmed runtime's module set is sufficient for the token library** — dependency analysis, a library probe, and a boot on the exact image. The skip-runtime switch is demoted from requirement to option | engineering |
| 2026-07-23 | **The policy engine ships as the Advanced profile** = Standard plus one module, on a decision seam, enforcing at the route and at the row | engineering |
| 2026-07-24 | **Per-tenant space isolation ships as two engine-resident seeded policies**, engaging only once a space claim is mapped, exempting access configuration, and tailorable by authoring same-named policies | engineering |
| 2026-07-25 | **Advanced becomes a real packaging flavour with no new runtime flag** — the presence of the decision provider's registration *is* the switch, the launchers auto-detect from bundle contents, and Basic bundles stay byte-for-byte unchanged | engineering |
| 2026-08-29 | **Binding every interface stays the default in every edition**; a flag lets a deployment restrict itself, and an unresolvable value fails the boot. Narrowing the default would silently make deployed installs unreachable on upgrade | operator |
| 2026-09-02 | **Six features declared not for Basic**, opening the feature board and the edition-gating debt row | operator |
| 2026-09-02 | **The bill of materials is generated per packaged bundle, not per reactor** — the reactor attests a set no customer installs | engineering |
| 2026-09-02 | **A checksum always; a signature on request, and requesting it is fail-closed** — a missing binary, a missing key or a non-zero exit all throw rather than warn | engineering |
| 2026-09-06 | **Secret schemes are tiered by edition**: environment and system every edition, file and keystore Standard and up, vault and cloud key management Advanced **and only when a client policy requires it**. A Basic bundle refuses the gated schemes with a message naming the edition | operator |
| 2026-09-06 | **The eight deployment decisions signed** — a container image as a convenience with orchestration out of scope; bundle the database driver; vault and key management gated on the first client policy that needs them; a committed platform list; recovery targets as contract service levels; a government cryptographic variant only against a concrete opportunity; and the reference identity and gateway pair | operator |
| 2026-09-07 | **The gating mechanism is provider modules for every cell** — ⛔ explicitly **not** launch-flag capability switches, because a switch leaves the code in the Basic bundle. Basic has no authenticator and binds every interface, so a mis-set flag would re-expose an unauthenticated surface. A module is a packaging boundary; a flag is only a policy one | operator |
| 2026-09-07 | **The route-module interface becomes public.** Core registers its own ordered list first and **appends** discovered modules; discovery order is unspecified, and a duplicate method-and-path **fails the boot** rather than resolving silently | engineering |
| 2026-09-07 | **There is no Basic profile** — Basic is the default reactor. Corrected because an unknown profile only warns, so the wrong command had been producing the right bundle | engineering |
| 2026-09-07 | **The authentication module must ship shaded**, and the script verifies the *staged* artifact for its token library and all three registrations — the thin jar carried none of it and every Standard and Advanced bundle failed to boot | engineering |
| 2026-09-07 | **The connector module is bundled in every edition** and deliberately **not** gated: remote acquisition is a core capability, and gating it would mean correcting the acquisition rows | engineering |
| 2026-09-07 | **All four launchers use an explicit classpath**, never the jar switch — the jar switch ignores the classpath, and the fat JAR declares none, so a launcher could reach no sidecar | engineering |
| 2026-09-07 | **Only the metrics HTTP exposition is gated; the registry stays in the core**, because nine classes across three modules call it and it cannot be a module. The exposition is the whole of the exposure | engineering |
| 2026-09-08 | **The events surface moves whole**, because the audit export is a query-parameter branch inside one route — gating it alone would need a conditional inside a core route, the one mechanism the assembly model bans | operator |
| 2026-09-08 | **The audit read deliberately stays in the core** as a narrow pair of routes, fail-closed to audit and access-denied types so it cannot become the events feed by another name — because the board promises Basic a readable append-only log | engineering |
| 2026-09-08 | **Two board rows amended rather than left contradicting the build** — object stores leave Basic, and the gap watchdog is partial there | operator |
| 2026-09-08 | **The object engine provider is declared in the host module, not beside its seam**, because opening the stores needs host types and that dependency may not be inverted | engineering |
| 2026-09-08 | **The grantable capability vocabulary stays static across editions** — ⛔ not derived from registered routes, because a role file authored on Standard would then fail validation on Basic. Dead vocabulary has no route behind it and is harmless | engineering |
| 2026-09-08 | **The area is named "Editions & packaging"**, concept first and mechanism second, because *Edition* had no glossary entry until then | engineering |
| 2026-09-08 | **The board's matrix is authoritative for the Edition column**; the requirements register mirrors it and is the one to correct on disagreement | engineering |
| 2026-09-10 | **Fault-tolerant DR is a STANDARD capability; the Kubernetes cluster is ADVANCED.** T4 (active/passive warm standby) moves from Advanced to **Standard**; T5 (partitioned scale-out on Kubernetes) is added as **Advanced**. ⚠ Consequence: the run lease and the fully-Postgres-backed state that make standby automatic (`superpower/enterprise-scale-out-plan.md` phases A–B) must ship in the **Standard** bundle, not behind `inspecto-policy`. T4 needs Postgres, so Standard DR is "database-backed", not "optionally". The scale-out DESIGN itself is still pending signature (plan §9) | operator |
| 2026-09-10 | **The Advanced scale-out design is SIGNED (D1′–D13)** — D1 REVISED: the container image is the **Advanced unit of deployment** and orchestration is IN SCOPE for Advanced only (Basic/Standard stay single-artifact, no orchestrator); shared-nothing partitioning **by Space** (D2/D3, the distributed engine REFUSED); object store + **DuckLake catalog commit as visibility** with a POSIX-volume fallback (D4); a **TTL lease table** — never advisory locks, never the Kubernetes Lease API (D5); `events.backend=db` (D6); the connection pool un-parked as phase A (D7); the dependency claim per edition — Basic zero, Standard zero unless DR, Advanced Postgres + object store (D9); registrar failure **fatal** in partitioned mode (D10); rebalancing phase C+1 (D11); the switch is `-Dinspecto.topology=partitioned` (D12); Postgres views over the Parquet via the DuckDB extension as the **external query surface**, gated on spike S5 (D13). ⚠ Per D8, phases A–B (shared Postgres state + the lease) are the **Standard** T4 DR deliverable and ship in the Standard bundle. → `superpower/enterprise-scale-out-plan.md` §9 | operator |

## 5. Not built

### 5.1 Tracked, and externally gated

Nothing a shift can close from this checkout.

* **Live deployment validation** — the T2, T3 and T4 reference deployments, the identity and gateway pair,
  and the blueprints that are written but never run against live instances. The repository-side half closes
  when the recovery-target statement carries agreed numbers and at least one drill row.
* **The certification programme** — applicability, boundary, auditor engagement, penetration test, and the
  demand-gated government package. Owned by [`compliance`](plan-compliance.md); seven open rows,
  closing at zero.

### 5.2 Tracked and actionable

* **The deployment gap ledger** — service wrappers, the embedded-database memory cap default (✅ closed
  2026-09-26, GAP-4), surge
  admission, the vault and key-management provider, launcher token-line debris, and the thirteen archived
  documents a broken permission silently drops from every bundle. **Phases 0 through 5 are all unbuilt.**

#### The deployment script suite — distilled here 2026-09-09 so its plan could be archived

⚠ **These acceptance criteria existed only in `deployment-topology-plan.md` §7.** That plan's *topology*
design was distilled into §§3.9–§3.13 when this spec was written, but its **deliverable table was not** —
so `SCR-1` through `SCR-11` appeared nowhere outside the plan, and archiving it would have lost the
definition of "done" for every unbuilt script. Board row: *Deployment topology live validation*
(`GAP-3`/`GAP-9`/`GAP-10`; the earlier pointer `DUCKDB-INMEMORY-SCRATCH-UNCAPPED-1` is a closed DuckDB row).

| ID | Deliverable | Tier | Acceptance |
|---|---|---|---|
| `SCR-1` | `preflight` | all | §8's install checks as a machine-readable report (`--json`, each row PASS/WARN/FAIL); **any FAIL blocks install**. Runs offline and catches every §8 row |
| ~~`SCR-3`~~ | ~~Service wrappers~~ | T2+ | ✅ **SHIPPED 2026-09-11.** `package.ps1` stages `inspecto.service` + `install-service.sh` (systemd: `Restart=on-failure`, `WorkingDirectory=` the bundle root, `EnvironmentFile=-<root>/inspecto.env` seeded mode 600) and `install-service.ps1` (Windows). ⚠ Recovery from process death is **restart**, not failover — this wrapper *is* the recovery mechanism (§3.11). 🔴 **The Windows half is a Scheduled Task, NOT an `sc.exe` service, and the backlog row's `sc.exe` recommendation was REFUSED on grounding**: a Windows service binary must connect to the service control dispatcher shortly after start and `java.exe` never does, so `sc create binPath= "…java…"` yields a service that fails every start with error 1053. That installer would have *looked* installed and never once restarted anything. The task runs at boot as SYSTEM with `RestartInterval`/`RestartCount`, needs no third-party binary, and is documented alongside WinSW as the alternative for anyone needing an entry in `services.msc`. Two systemd details worth keeping: `StartLimitBurst`/`StartLimitIntervalSec` are in **`[Unit]`**, because systemd moved them there in v229 and only *warns* about the old `[Service]` placement — a copy left there is silently ignored; and hardening stops at `NoNewPrivileges`, because the bundle writes DuckDB files, Parquet events and `spaces/` under its own root. ⚠ **Acceptance is only HALF evidenced**: the unit renders and both installers parse (`bash -n`, PowerShell parser), but the live **`kill -9` → back on `/health`** and the reboot leg are **UNRUN** — they need a systemd host and an elevated Windows box, neither available in this checkout. ⛔ Do not mark the acceptance met until someone runs them; the installers print the exact commands |
| `SCR-4` | Proxy / TLS templates | T2a / T3 | nginx and IIS reference configs: TLS, HSTS, gzip for the static UI, and network restriction of `/metrics` + `/health/details` — which are **unauthenticated by design**, so the proxy is the only thing that fences them |
| `SCR-5` | `backup-offsite` | T2+ | copies verified archives off-box after `backup_verify`; acceptance is that a **restore succeeds from the off-site copy alone** |
| `SCR-6` | `verify` | all | §9's acceptance block as a script — probes, an evidence table, an exit code; green on the reference deploys and **red on each seeded fault** |
| `SCR-7` | `upgrade` / `rollback` | T2+ | §6's procedure automated including N-1 retention; drilled in **both** directions on a reference deploy |
| ~~`SCR-9`~~ | ~~Launcher hygiene~~ | all | ✅ **SHIPPED 2026-09-09.** The dead `CONTROL_TOKEN` / `ASSIST_TOKEN` lines are gone from the generated `serve.sh`, `serve.bat` and `Dockerfile`. 🔴 Worse than debris: the scripts told the operator `CONTROL_TOKEN` was *"required to use the control plane"* and printed `CONTROL_TOKEN=secret bash serve.sh` as the way to start it — so anyone following the printed instruction would believe they had secured an **auth-free** service. Acceptance met: zero token mentions in all three generated bodies, verified by extracting the here-strings rather than grepping the generator |
| `SCR-10` | Bundle-docs ACL fix | — | thirteen files under `archived-documents/plans-archive/` carry a broken deny-ACL and are **silently skipped from every bundle**; needs an Administrator `takeown` + `icacls` pass |
| ~~`SCR-2`~~ | ~~Launcher `lib/` support~~ | — | ✅ **SUPERSEDED by PG-1**: `postgresql.jar` is staged into every Standard and Advanced bundle and auto-detected |
| ~~`SCR-8`~~ | ~~`package.ps1 -Edition Advanced`~~ | — | ✅ **SHIPPED as EDG-01.** The row named a blocker that was already gone |
| ~~`SCR-11`~~ | ~~Container image~~ | — | ✅ **SHIPPED as PKG-3** — the Dockerfile is generated at `package.ps1`. Kubernetes is in scope for Advanced only since D1′ (signed 2026-09-10, §6 *Orchestration platform support*) |
* **The Standard runtime not re-verified against the token library** — skip-runtime until confirmed.
* **Multi-user relational deployment** — park lifted 2026-09-15 (a multi-operator install exists); the buildable part is done and the two-host run is ⏸ postponed 2026-10-23 (board row *Postgres multi-user*, hold: operator).
* **Step-processor gaps** — eighteen partial and sixty-seven planned rows on the board.
* **Policy-authoring experience** — hand-authored files only today.

### 5.3 `UNTRACKED` — found writing this spec, no board row exists

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `SPEC-STALEREF-1`, `SPEC-COUNTS-1`, `SPEC-NOPROOF-1`, `SPEC-GREENCELL-1`, `SPEC-DEADSEAM-1`, `SPEC-PLANSTALE-1`, `SPEC-DEPLOY-ROWS-1`.
> ⚠ **None of these ids is an open board row today** (re-checked 2026-11-15, `DOCS-AGENT-WRITTEN-REVIEW-1`): `SPEC-STALEREF-1` closed 2026-09-09 when `tools/check-doc-citations.mjs` became a guard, `SPEC-NOPROOF-1` closed 2026-09-09 when all six Musts had tests, and the rest were closed, refuted or split into their own rows by the 2026-09-24 board consolidation. Their history: `docs/archived-documents/backlog-snapshot-2026-09-24.md` and `docs/archived-documents/plans-archive/post-consolidation-sprints.md`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

Ranked. Items 2–4 change what a customer receives; item 1 is closed, and the numbering is kept so the
citations elsewhere in this spec still resolve.

1. ✅ **RESOLVED 2026-09-09 — the bill of materials declared the wrong module set** (§3.7): four
   first-party jars against ten or eleven, the mail dependency in none of them, and a comment asserting it
   matched the staging table. It shipped inside a signed, checksummed archive with **no test over the
   generator**. Closed by moving the set into `tools/bundle-modules.mjs` and adding the CI guard
   `tools/check-sbom-modules.mjs`, which holds it against the packaging script's three enumerations; a
   duplicate driver component, invalid under both schemas, was fixed in the same pass.
   ✅ **The second half — `SBOM-RESOLVE-1` — SHIPPED 2026-09-09.** Generating the document at all required
   the reactor to be **installed** in the local repository, because the build tool will not resolve a
   sibling module from a jar built in an earlier invocation, and the release pipeline installed only the
   agent dependency. Advanced failed first — the policy module's cell-7 test-scoped edge to the
   operational-objects module must resolve even though the document lists runtime scope only — and on a
   clean runner every edition would.
   **As built:** `release.yml` gained a reactor `mvn -DskipTests -Pedition-advanced install` before the
   packaging steps. ⚠ **The profile matters**: the nine edition modules are profile-scoped and a plain
   install leaves `inspecto-ops` absent, which is the artifact Advanced fails on. Advanced is the
   superset (9 vs Standard's 8), so one pass covers every edition packaged below. Verified
   `mvn -o -DskipTests -Pedition-advanced install` BUILD SUCCESS over 32 modules, with `inspecto-ops`
   and `inspecto-policy` jars refreshed in `~/.m2`.
   ⛔ **Do not "fix" a recurrence by narrowing the scope filter**: the failure is in **resolution**, which
   precedes filtering.
   ⛔ `release.yml` does **not** invoke the generator — `package.ps1` does, after staging — so there is
   no second staging gap here; that was checked and refuted.
   ⚠ A truly clean BEFORE could not be reproduced on the sandbox (a prior build had primed `~/.m2`); the
   clean-runner failure is the one recorded when the row was filed.
2. ✅ **SHIPPED 2026-09-10** — the release pipeline gained the Standard step (`STANDARD-BUNDLE-1`, §3.7); the
   Standard supply-chain columns now have an artifact behind them. *(Was: no Standard artifact is built,
   checksummed, signed, given a bill of materials, or published; four documents described one that did not exist.)*
3. 🔴 **An Advanced bundle handed to a Standard customer self-identifies as Advanced and enables
   attribute-based access control** (§3.5) — because the launchers detect the edition from jar presence and
   the pipeline builds Advanced as a "superset of Standard". One added packaging step closes it; until
   then the superset label is a commercial exposure, not a convenience. ⛔ Decided 2026-09-10: build the
   distinct Standard bundle — not licence-key gating, not "document it". ✅ **CLOSED the same day** (§3.5,
   §3.7): the Standard collect step fails the release if `inspecto-policy` is inside the bundle.
4. 🔴 **The reported edition string is two-valued and derived from a launch flag** (§3.5) — Advanced is
   unreportable, and the deployment plan's edition probe inherited the defect, so the acceptance contract
   cannot verify an Advanced deployment. Derive it from what registered, the way the feature flags
   already do, and add flags for the four optional modules that have none.
5. 🔴 **Gating cell 7 has no Basic-side test** — the largest extraction, forty-four stubbed paths, and
   the only one of the seven cells without a falsification test. The seam is exercised in engine tests; the
   absent-module HTTP behaviour is not.
6. 🔴 **The trimmed runtime ships in nothing** while a board row marks it green in all three editions
   (§2). Either the release pipeline builds it, or the row and the requirement say "buildable, not
   shipped".
7. ⚠ **Nothing keeps the five stub path lists in sync with the real modules' routes** (§3.4). A list that
   drifts short turns an explained 503 into a bare 404 in Basic — the exact outcome the contract exists
   to prevent.
8. ⚠ **The staged-jar set is stated in eight places and five are wrong** (§3.3), one of them being code
   that ships as a compliance artifact. One generated table, or one test, would collapse the whole class.
9. ⚠ **A vendor-transform plugin reaches no bundle and no document** (§3.3) — roughly forty legacy
   transform functions behind a real seam, discovered by a registry that *is* shaded in. Decide whether it
   is operator-side by design, then say so somewhere.
10. ⚠ **A provider seam has no implementation anywhere** (§3.3) — declared for work that never arrived.
11. ⚠ **Two documents state the Java floor as 26**, including the file that sets it; the cache holds 25
    (§3.6). The conclusion survives, the number does not.
12. ✅ **CLOSED 2026-09-15 — and it had drifted a THIRD way before it was fixed.** This read *"stated as 95
    … by the generated lock and as 94 by four prose locations"*; by the time it was acted on the lock held
    **89<!--count:locked-dependencies-->**, so every number in circulation — both of them — was wrong. The
    fix is the one this list asked for, applied literally: the count is now **derived from the lock** by a
    `count:locked-dependencies` marker in `tools/check-doc-counts.mjs`, so the three surviving prose sites
    cannot drift again. ⛔ Six hand-edits were the wrong fix and are what produced two wrong numbers from
    one right one. ⚠ The "across 25 reactor modules" half is separately still wrong and is NOT guarded —
    no stated reactor size is 25 (they are 23, 31 and 32); say which profile resolved it.
13. ⚠ **The gating row says "all six cells" after enumerating seven**, and gives the object domain as both
    31 and 37 files inside the same row. The measured count is 32 domain files, 42 in the module.
14. ⚠ **The reactor page's decomposition is wrong** — "23 today: 14 default plus 9 profile-scoped" adds up
    by coincidence. 23 is the *default build* (the root, the decoder aggregator, its eight children, and
    thirteen modules); the nine profile-scoped modules take Standard to 31 and Advanced to 32.
15. ⚠ **No pipeline builds Basic alone with tests, or Standard alone at all.** One pass runs the
    Advanced profile, justified as the superset. The build page explicitly warns that Standard is **the
    only profile that proves an optional module is self-contained**, and that the wrong dependency
    direction once passed locally off a stale local repository. The falsification tests do stay valid —
    every optional module depends on the core, so a core-side test never sees one on its classpath — but
    the self-containment proof never runs.
16. ⚠ **The gating plan's header still says "in flight"** while its own final section declares it complete
    with a commit table, and the documentation index marks it archived and closed.
17. ⚠ **Two concept pages cite a numbered section of the editions board.** It has no numbered sections.
18. ⚠ **The deployment plan says three different things about the memory cap default in one file** — the
    header and the signed clarification call it the shipped 2 GB value, its own re-grounded gap note says
    the resolver returns nothing and the gap is still open, and the decision row still reads "needs product
    call". The gap note is the correct one. **A signed decision currently describes a default that does not
    exist**, which makes the signature undischarged work rather than a settled question. ✅ Discharged
    2026-09-26: GAP-4 shipped a computed default (40 % RAM ÷ 4, ≥ 1 GiB), not the 2 GB the row names.
19. ⚠ **The deployment plan's Advanced topology still calls the packaging flavour pending**, and its
    deliverable row still names a blocker that is gone; the same file's gap ledger records it shipped.
20. ⚠ **Two preflight rows and one deliverable describe dropping the database driver into a library
    directory** (§3.12). The build stages it automatically for Standard and Advanced, so the design was
    superseded and the checks now verify a step nobody performs.
21. ⚠ **Two board rows have no backlog home** — distributed scheduler coordination and the shared object
    store — despite the board's closing claim that everything planned has one. Both cite only a
    "will need later" list.
22. ⚠ **Twelve deployment items have no backlog row at all**: the preflight tool, the acceptance script,
    the off-site copy, upgrade and rollback automation, the sizing table, the disaster-recovery pack, the
    six phases, the platform list, and the government-variant refusal row. The words *preflight*,
    *standby* and *disaster recovery* appear **nowhere** in the backlog. This spec is now their only
    durable home, which is why it had to be written before the plan could move.
23. ⚠ **One example of thirty is exercised anywhere** (§3.8). A per-category smoke would cost one pipeline
    step.
