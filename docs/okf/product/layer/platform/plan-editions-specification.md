---
type: Capability
title: Editions & packaging (PKG) — capability spec — Specification
description: Part 2 of 4 of Editions & packaging (PKG) — capability spec: Specification
status: current
item: layer.platform
audience: internal
verified: 2026-11-19 last-edit
---

# Editions & packaging (PKG) — capability spec — Specification

Part 2 of 4 of [Editions & packaging (PKG) — capability spec](plan-editions.md) · previous: [Purpose, scope and requirements of record](plan-editions-scope.md) · next: [Specification — the plan's tables; decisions and not built](plan-editions-tables-decisions.md).

## 3. Specification

### 3.1 The unit of shipping

One zip per platform per edition, self-contained, installed by unzipping. Inside it: the fat JAR, the
sidecar jars its edition adds, generated launchers, the trimmed runtime when built, the examples suite,
the documentation set, and the bill of materials. Around it: a checksum always, and a detached signature
when signing is requested.

The state a deployment owns is one tree — `spaces/<id>/` holding configuration, data, audit and the
embedded database files. That single-tree property is what makes backup a zip, promotion a copy, and
disaster recovery a file sync (§3.11).

⚠ **Launch from the bundle root.** Paths inside an authored pipeline resolve against the process working
directory, not the space root. Starting from anywhere else is the most-reported installation failure, and
it is why the preflight has a working-directory check (§3.12).

### 3.2 The assembly model — and the two mechanisms that do not exist

Five mechanisms, in the order the build applies them:

1. **A separate Maven module.** The primary mechanism. Nine of them (§3.3).
2. **A Maven profile** naming that module set — `edition-standard` and `edition-advanced`. Both are
   **module lists and nothing else**: no properties, no dependencies, no compilation change. No child
   module declares a profile at all.
3. **A provider seam.** The core loads an interface; the module supplies the implementation; core
   behaviour when nothing is found is defined per seam (§3.4).
4. **A launch flag**, for configuration rather than capability.
5. **The packaging script's edition switch**, which selects the profile, stages the jars, and asserts each
   staged jar's shape.

The core contains **no** edition conditional. That is a standing ban, not a convention (§6).

⛔ **Two documented mechanisms are not real.**

* **There is no `edition-basic` profile.** Basic is the default reactor. The correction matters
  because of *how* the old instruction failed: an unknown profile makes Maven **warn and then build the
  default**, so the wrong command produced the right bundle and nobody noticed.
* 🔴 **The profiles do not vary the fat JAR's shaded content.** Two concept pages say they control "which
  modules and shade includes go into the fat JAR". The shade configuration is single and unconditional,
  and no child module has a profile to vary it. Optional modules ship as **separate sidecar jars** and are
  never shaded in. Corrected in this commit.

Both pages also cite the parent build file at two line numbers for the profile identifiers; both lines are
comments, and the identifiers moved when the gating modules were inserted. **Cite the identifier, not the
line** — the same lesson the compliance area recorded about its own citations.

### 3.3 Optional modules and staged jars — 24<!--count:optional-modules--> modules, 31<!--count:advanced-first-party-jars--> first-party jars (+ the postgresql sidecar)

| Edition | Optional modules compiled | Jars staged into the bundle |
|---|---|---|
| Basic | none (default reactor) | 2 — the fat JAR and the connector sidecar |
| Standard | 10 (the assist agent is a default-reactor module) | 13 — those two, the ten, and the database driver |
| Advanced | 12 (the intelligence agent is a default-reactor module) | 15 — the thirteen plus the policy engine and the intelligence agent (`ASSURE-INTELLIGENCE-BUNDLE-1`, 2026-09-29) |

🔴 **Staged is not the same as first-party, and the difference is exactly one jar.** The database driver
is `postgresql.jar`, which is **third-party**: Standard stages **13** jars of which **12** are first-party,
and Advanced stages **15** of which **14** are. Conflating the two counts is what made the
bill-of-materials correction hard to state (§2 `PKG-2`) — quote the staged count or the first-party count,
never one as the other. Until 2026-09-09 this file described the jar only as "the database driver", so the
name a reader would grep for lived solely in `REQUIREMENTS.md`.

The nine Standard modules are the authentication module, the Entity List module (SEP-08, 2026-10-01 — Entity Lists and the shared entity fact log, split out of the geographic and link module) plus the seven produced by the edition-gating
work: notification channels, backup tasks, geographic and link analysis, exchange and sharing, the metrics
exposition, the events feed, and operational objects. Advanced adds the policy engine and the intelligence agent (a shaded jar carrying onnxruntime natives, ~148 MB), and the profile
lists all nine **literally** — Maven profiles do not inherit, so there is no superset relation in the build
file even though there is one in the module set. ⚠ **Since `LA-EDITIONS-1` phase 3 (2026-10-17) the Entity List module
and the Link Analysis modules (`la-core`, `la-storage`, `la-api`, `geo-link`) ship in Basic too** — the Link Analysis
explorer — and only the Investigation surface stays Standard and up, withheld by the `investigations` edition feature
(`docs/EDITIONS.md`, *Basic — the Link Analysis explorer*).

Two of the eight stage a **shaded** jar rather than the thin one, because the thin artifact cannot run:
the authentication module needs its token library, and the channels module needs its mail library. That
distinction was learned the hard way — a 16 KB thin jar carried no token library and **every** Standard and
Advanced bundle failed to boot (§4, 2026-09-07).

**Each staged jar is asserted for shape**, by opening the zip and reading it — not "the file exists" but the
classes and provider registrations it must contain. The operational-objects jar is asserted for **two**
registrations, routes and an engine provider, with the reason recorded in the script: a jar with routes but
no provider would answer 503 on every path while claiming the feature is installed. That is the most
instructive assertion in the build, because it names a failure that would otherwise look like success.

⚠ **The staged set is stated in eight places and three are complete.** The boot smoke's classpath, the
staging code itself, and the build concept page's prose are right. The script's own header names three
jars, the board's packaging row omits three modules and both sidecars, the board's assembly table omits
two, the build page's *table* omits everything the same page's *prose* gets right — and the eighth is
**code that ships as a compliance artifact** (§3.7).

✅ **`PKG-5` RESOLVED 2026-09-12 — the assistant now ships.** `inspecto-agent` is staged as
`inspecto-agent.jar` in **Standard and Advanced** bundles and wired into all four launchers
(`serve.sh`/`serve.bat`/`run.sh`/`run.bat`) through the bundle's `modules.list` (by jar presence until P3d, 2026-10-08), like every other optional module. Operator
decision, taken as "all editions, optional" and narrowed the same day to **Standard and above** once the
sidecar's weight was measured — Basic already carries the ~32 MB connector sidecar and is not sold on
the assistant. Verified by BUILDING both bundles: Standard carries the jar, Basic does not.

⚠ **`inspecto-agent-hosted` and `inspecto-intelligence` still build on every run and ship in nothing.**
That remains deliberate — plain reactor modules, not gated ones, compiled and tested continuously — but
it is now two modules, not three, and the assistant routes answer 503 only where the jar is absent.

🔴 **The row's stated blocker was the wrong one, for the seventh-odd time on this board.** `PKG-5` read
"blocked on the Java floor". The floor is real — every `eoiagent-*` jar is class-file major **69**
(Java 25), measured 2026-09-12, against a compile target of 24 — but it was never what made bundling
refusable. The blocker was that `CollectorService.start()` called `ServiceLoader.load(AssistAgent.class)`
**unguarded**: an unloadable jar raises `UnsupportedClassVersionError`, an `Error` that `ServiceLoader`
propagates rather than wrapping, so staging the assistant would have made **the whole server fail to boot**
on a Java 24 host because an *optional* component could not load. `com.gamma.spi.OptionalSpi` now
treats unloadable as an absence at all six discovery sites, and the floor question dissolves: an optional
module that cannot load is simply absent. (The compile target has since moved to **27**, which is now the
whole product's floor.) → §3.6

⚠ **One module is unreachable and undocumented.** A vendor-transform plugin under the decoder tree
registers roughly forty legacy transform functions through a real provider seam, discovered by a registry
that **is** shaded into the fat JAR. Nothing depends on the module and no staging step copies it, so those
functions are unreachable in every bundle, and the documentation set does not mention it anywhere. This is
the shape the connector-bundling decision had before it was noticed. Whether the omission is intentional
could not be determined from the repository (§5).

⚠ **One seam has no implementation at all** — an expression provider interface with no registration in any
module. Harmless today; it is a seam declared for work that never came.

✅ **Standard jlink runtime vs Nimbus — verified 2026-09-15.** `pwsh inspecto/package.ps1 -Edition Standard -NoUi`
(runtime embedded, boot smoke ON): *"verified: Nimbus classes + 3 SPI registrations present in the security
sidecar"*, both trimmed runtimes built (Windows 91.5 MB, Linux 105.5 MB), and *"the staged Standard bundle boots
and answers /health"* on the embedded Windows runtime — exit 0. The `-NoRuntime until confirmed` caveat on the
API v1 row is discharged.

### 3.4 The absence contract

Every provider seam that carries edition behaviour has **a defined answer when nothing is found**, and the
answers differ by design:

| Shape | Behaviour when absent | Examples |
|---|---|---|
| **Silent skip** | The check does not happen. Basic is byte-for-byte unchanged. | Authentication, access decisions, the assistant seams |
| **Explained refusal at the route** | 503 naming the capability, never 404 | The session broker, and all five stub route groups |
| **Loud throw at use** | An exception naming the missing module | Maintenance tasks, secret schemes, connector factories |
| **Empty list, feature degrades** | The remaining implementations carry on | Notification channels — in-app delivery is intrinsic and unaffected |
| **Named fallback** | A no-op implementation that abstains | Description providers |

Singular gates share one small helper — first provider wins, cached, empty when absent — so the
"nothing found" path is written once rather than per seam.

**The five stub route groups** are the mechanism behind "503, never 404". Each iterates a list of
method-and-path pairs, **skips any pair a real module already claimed**, and stubs the rest to throw a 503
with a message naming the module. They register **last**, so a present module always wins first match. And
the stub deliberately does **not** make the route-presence check return true — otherwise the derived
feature flags would lie about what is installed.

Two messages are worth quoting for their precision, because they distinguish *recording* from *reading*:

* The events stubs: events are still **recorded**, including the audit trail; only reading the feed back
  over HTTP is edition-gated.
* The metrics stub: instrumentation still **runs**; only the HTTP exposition is gated.

That distinction is why the audit read deliberately **stayed in the core** as a narrow pair of routes,
fail-closed to audit and access-denied types so it cannot become the events feed by another name. The
board promises Basic a readable append-only log, and gating the whole feed would have broken that
promise (§4, 2026-09-08).

The largest group stubs **44 paths** across four route families. ⚠ **Nothing keeps these lists in sync with
the real modules' routes** — the board says roughly fifty. A stub list that drifts short means a real
route 404s in Basic instead of explaining itself (§5).

### 3.5 What the running system says it is

🔴 **The edition string cannot report Advanced.** The bootstrap route derives it from **one launch flag**,
returning `basic` when authentication mode is unset and `standard` otherwise. Three consequences, all
mechanical:

* An **Advanced** bundle reports `standard`, while carrying the policy engine.
* A **Basic** bundle started with the flag reports `standard`, while carrying no authenticator.
* A **Standard** bundle started without it reports `basic`, while carrying all eight modules.

In practice the launchers set the flag when they detect the authentication jar, so the string is usually
right — **by launcher convention, not by derivation**. And the defect has already propagated into the
acceptance contract: the deployment plan's edition probe checks that the reported value "matches intent
(`basic` vs `standard`)", so the check as written **cannot verify an Advanced deployment** (§3.12).

The **feature flags** on the same route are the opposite, and are the model to copy: each is derived from
what actually registered, using literal paths, with a *write* path probed for operational objects so it
cannot be confused with a future core catch-all. The stub routes deliberately do not count. ⚠ The flag set
is incomplete — **four of the nine optional modules have no flag**, so channels, backup, the metrics
exposition and the policy engine are invisible to a client asking what is installed.

🔴 **The launcher's edition detection is a commercial exposure, not just a reporting defect.** The
launchers infer the edition from **jar presence**: the authentication jar means Standard and turns on
delegated authentication; the policy jar as well means Advanced. The release pipeline builds Basic and
Advanced only, labelling the Advanced step "superset of Standard". That label is true at the module
level and **false at runtime identity** — hand that bundle to a Standard customer and it self-identifies as
Advanced and enables attribute-based access control, a tier they have not purchased. ✅ **Closed 2026-09-10
(`STANDARD-BUNDLE-1`):** the release pipeline packages, signs and publishes a distinct
`inspecto-deploy-standard.zip` built by `-Edition Standard` (no policy jar), and the collect step **fails the
release** if a policy jar is found inside it — so a Standard customer receives a bundle whose jar presence
yields `standard`. The reporting defect (the two-valued edition string) is untouched by this and stays open.

### 3.6 The runtime image and the Java floor

The trimmed runtime is built by **`jlink`** from **twelve** platform modules — seven derived by dependency
analysis of the fat JAR, five added because they are loaded reflectively and analysis of a fat JAR cannot
see them. The tool is located in three places in order (GraalVM cache, then `JAVA_HOME`, then `PATH`), and its
absence **throws**, pointing at the skip switch **`-NoRuntime`**. The probe is **platform-aware** since
2026-09-14: `jlink.exe` on Windows, `jlink` elsewhere. It hardcoded the `.exe` before, which is why the
Linux CI runner could never embed a runtime.

⚠ **The recorded verification attributes only five of the twelve**: `java.base`, `java.sql`,
`java.net.http`, `jdk.httpserver` and `jdk.crypto.ec`. The other seven in the image are unattributed by it
(§2 `PKG-4`) — moot for shipped artifacts, which carry no runtime at all, but not moot the day one ships.

**Cross-platform, from one host.** The invoked tool is always the Windows one; the *target* platform is
selected by the module path, so pointing it at a Linux module cache produces a Linux-native image on a
Windows host. A Linux failure is a **warning that skips the Linux zip**, not a build failure — which is how
a Linux bundle can silently not exist.

**Three Java versions, and they are not interchangeable:**

| Number | What it governs |
|---|---|
| **27** | The compile target for the whole reactor (`maven.compiler.release`, class files v71; it was 24 when this section was written), and therefore the floor a skip-runtime deployment must meet |
| **25** | The floor the assistant modules' upstream dependency imposes — now below the compile target, so it no longer binds separately |
| **27** | What the build needs (`JAVA_HOME` must be JDK 27; `tools/dev-doctor.mjs` checks it) |

✅ **The two documents that once claimed 26** (the parent build file and the build concept page) no longer do.

🔴 **That framing was wrong, and it is what kept `PKG-5` refused for months.** "Bundling would raise the
bundle's floor from 24 to 25" assumes the floor is a property of the BUNDLE. It is a property of the
**module**, and it binds only a host that actually loads it. Since 2026-09-12 an optional module that
cannot link is skipped with a warning (`com.gamma.spi.OptionalSpi`) and its routes answer 503 — the
ordinary absence contract. So the assistant ships in Standard and Advanced, and an optional module that
cannot load gives the product without that module instead of a product that will not start. ⛔ Do not
re-refuse this on floor grounds. (Since the compile target moved to 27 the whole product needs 27, so the
assistant's 25 floor no longer differs from the bundle's.)

The parent build file's "JDK 26" was corrected to 25 on 2026-09-09.

### 3.7 Release integrity, the bill of materials, and the missing edition

**Checksums and signatures.** A checksum is always written, needing no key. A detached signature is written
when signing is requested, and requesting it is **fail-closed three ways** — no signing binary, no key, or
a non-zero signing exit all throw. That was a deliberate fix from an earlier silent downgrade to a warning.

**The bill of materials** is generated per bundle, **after staging and before zipping**, so it ships inside
the archive and is covered by the checksum and the signature. A non-zero exit throws: a bundle ships with
its bill of materials or not at all. Generating it per bundle rather than per reactor was itself a decision,
and a good one — the reactor attests a set no customer installs.

✅ **The generator declared the wrong set — fixed 2026-09-09.** Its first-party table held **four**
artifacts: the processor, the connectors, the authentication module for non-Basic, and the policy engine
for Advanced. It knew **none** of the seven gating modules. So, until the fix:

* Every Standard bill of materials declared 4 first-party jars where the bundle carries **10** (11 staged
  jars, counting the driver sidecar); every Advanced one declared 4 where the bundle carries **11** (12).
* The **mail dependency appeared in no bill of materials at all**. It left the connector sidecar for the
  channels module in gating cell 1, and the generator's own comment still credited the sidecar with
  bringing it.
* The comment above that table claimed it was "the SAME table the packaging script stages from". It was
  not, and saying so is what made it invisible.

Nothing could catch it: there was no test over the generator, and the release pipeline merely copies its
output. This was the same failure shape the control-API area found in a served contract file: **a generated
artifact that is authoritative to its consumer and unverified by its producer.**

**What closed it.** The module set now lives once, in `tools/bundle-modules.mjs`, read by both the
generator and a new CI guard, `tools/check-sbom-modules.mjs`. The guard parses the packaging script's
**three independent enumerations** — the module list, the staging steps, and the boot-smoke classpath —
requires them to agree with each other and with that file, and checks each module's declared artifact
identifier, because a wrong directory-to-identifier pair contributes **zero** components for that module
without saying so. It was falsified in both directions before being wired in, including against the
original four-module table. Measured after the fix: Basic 2 first-party, Standard 10, Advanced 11,
with the mail dependency present in the latter two and absent from Basic, where no channels module
ships. A second defect surfaced in the same pass and was fixed with it: the driver sidecar was appended
unconditionally although the connector module already resolves it at compile scope, so every
Standard and Advanced document carried a **duplicate** component identifier — invalid under both
schemas, in a document whose purpose is to be machine-validated.

⚠ **One thing the fix does not close.** The generator resolves through the build tool, which cannot see a
sibling module's previously-built jar across separate invocations — so it needs the reactor **installed**,
and the release pipeline installs only the agent dependency, never this reactor. Advanced is the first
edition to expose it, because the policy module gained a test-scoped edge to the operational-objects module
in gating cell 7 and resolution covers every scope. Tracked in §5.3.

✅ **CLOSED 2026-09-10 (`STANDARD-BUNDLE-1`) — the release pipeline packages, checksums, signs, SBOMs and
publishes all three editions:** `inspecto-deploy-{basic,standard,advanced}.zip` + `.sha256` + `.asc` and
`inspecto-<edition>.{cdx,spdx}.json`, with the Standard collect step refusing a bundle that carries the policy
jar. Until then the pipeline packaged **Basic and Advanced** only — no Standard step, no Standard
artifact — while four documents disagreed about it (kept as the record of what each said):

| Where | What it says |
|---|---|
| The board's packaging row | Describes a Standard package in detail |
| The board's assembly note | Names `-basic` and `-standard` **classifiers** — and no `-advanced` one, for the flavour that *is* released |
| The branching policy's manual fallback | Uploads Basic and **Standard** jars |
| The board's status note | "The script builds and bundles it" — true of the script, false of any release |

No two of the four agreed, and the editions board marked both supply-chain controls green for Standard — a
column with **no artifact behind it**, the same hole the compliance area found from its side. With the step
in place the column is backed; `BRANCHING.md`'s classifier note now names all three flavours.

The bundle filenames carry **no edition**, so all three flavours emit the same path and the release pipeline
renames them afterwards. That is why the omission is easy to miss: nothing in the build's output names what
is absent.

### 3.8 The examples suite

Thirty examples in seven categories — ingest, parsing, schema and transform, output, acquisition, serve,
and steps — each with an authored pipeline, a schema or grammar, and synthetic samples. They are tracked in
the repository and staged as-is, because every path inside an example is relative to its own directory.
Runners resolve the JAR from an environment variable, then the bundle, then the build tree, so one runner
works from both a checkout and an installed bundle.

⚠ **One example is exercised anywhere** — a single ingest example, in the release pipeline only, asserting
that at least one output file appears. The continuous-integration pipeline runs none. Thirty examples ship;
one is known to work.

### 3.9 Deployment topologies

Four topologies, mapping one-to-one onto the edition flavours. This is the design absorbed from the
deployment plan; the plan itself remains the build plan for what is unbuilt.

| Tier | Shape | Edition | Identity | State |
|---|---|---|---|---|
| **T1** | Single workstation, unzip and run | Basic | none — the core is authentication-free | Local tree; the embedded runtime means no host Java is needed |
| **T2** | Single server, behind a proxy or with in-process transport security | Standard | Delegated to the customer's identity provider; the product is a resource server | Local tree, optionally database-backed |
| **T3** | Gateway-fronted, multi-team or multi-tenant | Advanced | Same, plus gateway assertion trust as a second anchor | Database-backed; per-tenant spaces |
| **T4** | Active/passive warm standby — **fault-tolerant DR** | **Standard** *(was Advanced until 2026-09-10 — operator decision, §4)* | As T2/T3 | Database-backed and replicated; ⚠ DR **requires** the Postgres-backed state, so "optionally database-backed" (T2) becomes "database-backed" here |
| **T5** | Partitioned scale-out on Kubernetes — **the cluster** | **Advanced** *(operator decision 2026-09-10; design **SIGNED 2026-09-10**, D1′–D13: `superpower/enterprise-scale-out-plan.md` §9)* | As T3, plus gateway assertion trust | Postgres + object store; N pods, each owning Spaces; one DuckLake catalog |

Three properties of this ladder are worth stating because they are decisions, not consequences:

* **The gateway is transport only.** Routing, throttling, cross-origin policy and edge authorisation live
  there; the product **re-validates every token itself**. Never trust the gateway blindly.
* **Identity federation happens at the identity tier, never in the product.** Kerberos, directory
  federation and assertion brokering are the identity provider's job; the product only ever validates the
  resulting token. That is what keeps the dependency tree small, which is itself a compliance asset.
* **A misconfigured identity provider fails the boot** rather than serving open. A Standard or Advanced
  bundle cannot construct its authenticator without configuration — which is why the build's own boot
  smoke has to inject placeholders, with a warning in the script never to mistake them for a working
  configuration.

**T1's exposure note is the one every tier inherits.** The control plane **binds every interface by
default, in every edition**. A flag restricts it, and an unresolvable value fails the boot rather than
widening. The default is deliberate: narrowing it would make deployed installs unreachable on upgrade. So
loopback-only is an *operator action* plus a firewall, not a property of the Basic edition — and the
board's old "binds localhost only" claim was the defect, not the behaviour.

**T4 replicates the whole tree** after a checkpoint, so the embedded database files are crash-consistent,
plus the relational store by streaming replication where used. Failover is: promote the store, start from
the same bundle version on the second site, repoint traffic, run the acceptance block. ⛔ **Active/active is
explicitly not offered** — both schedulers are in-process and the single-node ceiling is an accepted
constraint. The escape-hatch prerequisites are documented seams and a priced conversation, not a
configuration.

### 3.10 The security overlay

The overlay matrix is the area's second structural insight: **security posture is an overlay, not a tier.**
Thirteen overlays compose per customer policy, and most are patterns rather than product features —
transport security at a proxy, at-rest encryption at the volume, key management at the customer's own
manager. The product supplies the seam; the deployment supplies the control.

Two rows carry a caveat that belongs in this spec rather than the matrix:

* ⚠ **Health and metrics are unauthenticated by design.** On any served tier they must be restricted to the
  monitoring network at the proxy. The product does not do it, and the templates that would document it are
  unbuilt.
* ⚠ **The write gate is boot-time only.** With no write root configured, mutation routes answer 503 — a
  legitimate read-only boot mode. Discovered multi-space roots are always writable, so it is not a
  general read-only switch.

### 3.11 Restart-safety, backup and recovery

**Recovery from process death is restart**, and that is a design property rather than a limitation:
processing is crash-isolated and idempotent, the commit-ordering invariant makes a mid-run crash resumable,
job non-overlap prevents double-runs, and authored configuration re-registers on boot. The first
fault-tolerance layer is therefore the service wrapper's restart policy — **which is unbuilt** (§5).

🔴 **One trade-off dominates everything else in this area, and it is not about editions.** Database-backed
stores **degrade to in-memory rather than failing the boot**. Startup therefore never blocks on a database
— and **durability becomes unverifiable by observation**. A deployment that silently fell back looks
healthy, serves correctly, and loses everything on restart. This single property is why the acceptance
contract has an explicit backend assertion, why the recovery table lists "stores degrade to memory
(alert!)" as the response to a database outage, and why a health-detail watch is mandatory rather than
advisable. **Pair every durable-store deployment with the assertion.**

**Backup, verify and restore already ship as maintenance tasks** — no shell scripts. The chain is: back up
to a zip with a checksum manifest, catalogued and signalled; a verify task chained on that signal, raising
a critical signal on mismatch; and retention with a floor so a sweep can never delete the last backups.
Restore is fail-closed — manifest required, hash-verified, path-jailed, with a dry-run preview and conflict
blocking — and supports **restoring into a new space**, which doubles as the disaster-recovery drill and the
environment-clone mechanic. The embedded-database requirement is absolute: **checkpoint before copying**.

⛔ **What the platform does not do is move backups off the box.** The off-site copy is a deliberate gap with
a named deliverable, and it is the difference between a backup and a recovery plan.

The verify step's critical signal **needs an Alert Rule and a channel wired** — an unwired verification
failure is a silent one, and on Basic the channels module is not even bundled.

### 3.12 The deployment shape, preflight and acceptance

**Every tier is the same five beats: preflight, install, configure, start, verify.** Only the content
differs. T1 is verify-checksum, unzip, run, basic block; uninstall is deleting the folder. T2 adds the
service wrapper, the identity configuration, the store backends and the resource caps. T3 adds the gateway
definition import and the tenant isolation evidence.

**Fourteen preflight checks**, machine-readable, any failure blocking the install. The ones that encode a
lesson rather than a system requirement:

* **Working directory equals bundle root** — the path-resolution gotcha from §3.1.
* **Clock synchronisation** — token expiry tolerance is sixty seconds, so skew breaks Standard and
  Advanced authentication and looks like a credential problem.
* **Native access smoke** — the embedded database must open in-process, which catches a stripped
  native-access flag before it becomes a runtime failure.
* **Identity provider reachable *before* first boot** — because misconfiguration is a deliberate boot
  failure, not a degraded start.
* **Firewall rule present** — the operator half of the bind-everything default.

⚠ Two preflight rows describe a manual step the build already performs: the database driver is staged
automatically for Standard and Advanced, so "driver in the library directory" is a check against a design
that was superseded (§5).

**Twelve acceptance rows**, in three blocks — a basic block every tier runs, a standard block adding
authentication, transport, durability and the backup chain, and an advanced block adding the gateway
path, tenant isolation and the failover drill. Sign-off is every applicable row green with evidence
archived against the deployment record.

The two rows that carry the most weight are the ones derived from this area's own defects: the **backend
assertion**, which is the counter-check to graceful degradation (§3.11), and the **edition probe** — which
🔴 **cannot pass for Advanced as written**, because the value it checks is two-valued (§3.5). Fixing the
reported string fixes the check.

### 3.13 Environments, promotion, upgrade and rollback

Environments are separate instances, or separate spaces on shared lower-tier hardware. Configuration is
code: authored files live in the space tree and belong in the customer's version control. Promotion
mechanics that exist today are whole-space export and import with a dry run, space templates, and a clone
generator.

**The same artifact promotes through every environment** — verify its checksum at each hop, and vary only
the launch flags and environment variables. Upgrade is: retain the previous bundle, unzip the new one
alongside, stop, swap, start, verify. Rollback is the same in reverse, which is why retaining the previous
version is part of the procedure rather than a suggestion. Automating both directions is unbuilt (§5).

**The committed operating-system support list is a decision that was signed without being written** (§4,
D5). Windows and Linux bundles ship today; the proposal is Windows Server 2019 and later plus the long-term
support Linux distributions on 64-bit. Until it is written down, "supported platform" has no answer.

## Changes

- 2026-09-07: the `edition-basic` profile instruction corrected — there is no such profile.
