---
type: Capability
area: PKG
title: Editions & packaging (PKG) — capability spec
description: The requirement-of-record and as-built specification for what Inspecto ships — the three editions and the mechanism that produces them, the nine optional modules and the twelve staged jars, the absence contract that makes a missing module answer 503, the runtime image and the Java floor, release integrity and the bill of materials, plus the deployment topologies, the security overlay, the recovery posture and the acceptance contract absorbed from the deployment-topology plan.
status: current
written: 2026-09-09
supersedes-rows: REQUIREMENTS §3.16 PKG-1 through PKG-4, and EDITIONS OPS-07 (this file corrects them, see §2)
---

# Editions & packaging (`PKG`)

> **How to read this file.** §1–§2 are the *requirement of record*: where they disagree with
> `docs/REQUIREMENTS.md` §3.16 or an `docs/EDITIONS.md` row, **this file wins** and the disagreement is
> stated in place. §3 is the as-built specification, §4 the dated decisions, §5 what is not built, §6 what
> was refused, §7 the pointers, §8 how it is verified.
>
> ⛔ **This area has two upstream concept homes, not one.** [`okf/backend/editions/`](../../backend/editions/index.md)
> owns the assembly model and the authentication story;
> [`build-test.md`](../../backend/build-run/build-test.md) owns bundling and reactor sizes. Neither is
> retired by this spec — both stay as the concept tier, and both are corrected below.
>
> ⚠ **This spec also absorbs a live plan.** `superpower/deployment-topology-plan.md` is the only plan in
> the consolidation whose archival would lose *design* rather than provenance, and its stated distillation
> target never existed. §3.9–§3.13 are that design, distilled. **The plan stays in `superpower/`** — its
> Phases 0–5 are unbuilt, so it is still in flight; what moves here is the part that is settled.
>
> ✅ **The loudest finding in this area was not about editions at all — and is now fixed (2026-09-09).**
> The bill of materials that ships inside every signed bundle **declared the wrong module set** — four
> first-party jars where a Standard bundle carries ten and an Enterprise one eleven (§3.7). It was a
> compliance artifact wrong on the wire, and nothing in the repository could catch it. The set now lives
> once in `tools/bundle-modules.mjs`, and `tools/check-sbom-modules.mjs` fails CI when it drifts from what
> the packaging script stages.

## 1. Purpose & scope

This capability answers one question: **what does a customer actually receive, and how is it produced?**
Every other area describes a feature. This area describes the artifact the feature arrives in — and it is
where the gap between "the code exists" and "the code ships" is either closed or hidden.

The organising rule is one sentence, and it is the most load-bearing sentence in the area:

> **A feature an edition lacks is a module that edition does not bundle.** Never a branch, never a
> conditional in the core, and — after 2026-09-07 — never a launch flag either.

**In scope**

* **The three editions** — Personal, Standard, Enterprise — as build flavours of one commit, and the five
  mechanisms that assemble them.
* **The nine optional modules**, the twelve staged jars, and the shape assertions that prove each staged
  jar can do its job.
* **The absence contract** — the provider seams, the five stub route groups, and the requirement that a
  missing module answers with an explanation rather than a not-found.
* **The runtime image and the Java floor** — the trimmed JVM, its module list, and the three different
  Java versions this repository targets.
* **Release integrity** — checksums, signatures, the bill of materials, and which artifacts a release
  actually publishes.
* **The examples suite**, because it ships inside the bundle and is the only end-to-end thing a customer
  can run on arrival.
* **Deployment** — the four topologies, the composable security overlay, the recovery posture, the
  five-beat installation shape, the preflight checks and the acceptance contract.

**Out of scope**

* *What* each feature does — that belongs to the owning area's spec. This file only records which editions
  carry it, and only where the packaging mechanism is the interesting part.
* The compliance *programme* — certification scope, evidence and the control matrix are
  [`compliance`](../compliance/compliance.md). This spec owns the supply-chain **mechanism**; that spec
  owns whether it satisfies a control.
* Version and release-line policy — `docs/BRANCHING.md`. The one requirement that belongs here is its
  corollary: versions are branches, editions are not.

## 2. Requirements of record

`docs/REQUIREMENTS.md` §3.16 carried four rows, all marked `SHIPPED`. **Three of the four overstate what
ships**, and the fourth is accurate about the mechanism while its status is contradicted in three other
files. The corrections below are the requirement of record.

| Row | Register says | Correction of record |
|---|---|---|
| **PKG-1** | One fat JAR + trimmed runtime; per-edition bundles via the packaging script — `SHIPPED`, all editions | 🔴 **Two-thirds shipped.** The fat JAR ships. The per-edition bundles are **two of three** — no Standard artifact is ever produced by any automated path (§3.7). And **no released artifact contains the trimmed runtime**: the release pipeline passes `-NoRuntime` for every bundle, because the runner has no module cache. The runtime is real, buildable, and shipped in nothing. |
| **PKG-2** | Lean bill of materials: framework-free core, network dependencies isolated in the connector module — `SHIPPED`, all editions | ✅ **Corrected 2026-09-09.** The generator had declared **four** first-party jars against the **ten** a Standard bundle carries and the eleven an Enterprise one does, knowing none of the seven gating modules; the mail dependency consequently appeared in **no** bill of materials (§3.7). The set now lives once in `tools/bundle-modules.mjs` and a CI guard holds it against the staging script.
A **duplicate `postgresql` component**, carrying an invalid `bom-ref` / `SPDXID`, was fixed in the same pass. The isolation half still does not hold as written: the connector sidecar ships in **every** edition by a 2026-09-07 decision, and the mail dependency **left** it for `inspecto-notify-channels` in gating cell 1. "Lean" was measured and belongs to the *bundle*, not the reactor (§3.7). |
| **PKG-3** | Runnable, self-contained example suite — `SHIPPED` (should), all editions | 🟡 **Shipped and largely unexercised.** Thirty examples in seven categories are tracked and staged into every bundle. **One** of the thirty runs in any pipeline, and only on a tag. "Runnable" is proven for one example and asserted for twenty-nine. |
| **PKG-4** | Verify the Standard bundle's runtime module set against the token library — `SHIPPED` (must, Standard), verified 2026-07-07 | ⚠ **The evidence covers five of the twelve modules.** The recorded verification names the four the token library needs plus the elliptic-curve provider; the remaining seven in the image are unattributed by it. The status is then stated three ways: the register says resolved, the backlog says not re-verified, and the build concept page says the runtime step is unproven. All three are moot for shipped artifacts, which contain no runtime at all. |
| **OPS-07** (`EDITIONS.md`) | Embedded trimmed JVM runtime in the bundle — ✅ in all three editions | 🔴 **No shipped artifact contains it.** Same root cause as PKG-1. The row describes a capability of the script, not a property of the bundle a customer receives. |
| **§Matrix packaging row** and **§Assembly module list** (`EDITIONS.md`) | Standard = core plus six named modules | ⚠ **Understated, and the two lists are different sixes.** The build stages **eight** optional modules for Standard and nine for Enterprise. The prose in `build-run/build-test.md` has the correct set; the two board sites do not (§3.3). |

**On the Edition column.** `docs/EDITIONS.md` is authoritative for which edition carries a feature, and
`docs/REQUIREMENTS.md` §3 mirrors it — that direction was settled 2026-09-08 and this spec keeps it. For
`PKG` the corrections run the other way from the usual: the register's *edition* column is right, and the
defect is in its *status* column.

**On the scale ceiling.** `NFR-8` records single-node as an accepted constraint, and it is the reason
active/active is refused rather than deferred (§3.9, §6). It is the one non-functional requirement this
area does not correct.

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
2. **A Maven profile** naming that module set — `edition-standard` and `edition-enterprise`. Both are
   **module lists and nothing else**: no properties, no dependencies, no compilation change. No child
   module declares a profile at all.
3. **A provider seam.** The core loads an interface; the module supplies the implementation; core
   behaviour when nothing is found is defined per seam (§3.4).
4. **A launch flag**, for configuration rather than capability.
5. **The packaging script's edition switch**, which selects the profile, stages the jars, and asserts each
   staged jar's shape.

The core contains **no** edition conditional. That is a standing ban, not a convention (§6).

⛔ **Two documented mechanisms are not real.**

* **There is no `edition-personal` profile.** Personal is the default reactor. The correction matters
  because of *how* the old instruction failed: an unknown profile makes Maven **warn and then build the
  default**, so the wrong command produced the right bundle and nobody noticed. Corrected 2026-09-07.
* 🔴 **The profiles do not vary the fat JAR's shaded content.** Two concept pages say they control "which
  modules and shade includes go into the fat JAR". The shade configuration is single and unconditional,
  and no child module has a profile to vary it. Optional modules ship as **separate sidecar jars** and are
  never shaded in. Corrected in this commit.

Both pages also cite the parent build file at two line numbers for the profile identifiers; both lines are
comments, and the identifiers moved when the gating modules were inserted. **Cite the identifier, not the
line** — the same lesson the compliance area recorded about its own citations.

### 3.3 Nine optional modules, twelve staged jars

| Edition | Optional modules compiled | Jars staged into the bundle |
|---|---|---|
| Personal | none (default reactor) | 2 — the fat JAR and the connector sidecar |
| Standard | 8 | 11 — those two, the eight, and the database driver |
| Enterprise | 9 | 12 — the eleven plus the policy engine |

🔴 **Staged is not the same as first-party, and the difference is exactly one jar.** The database driver
is `postgresql.jar`, which is **third-party**: Standard stages **11** jars of which **10** are first-party,
and Enterprise stages **12** of which **11** are. Conflating the two counts is what made the
bill-of-materials correction hard to state (§2 `PKG-2`) — quote the staged count or the first-party count,
never one as the other. Until 2026-09-09 this file described the jar only as "the database driver", so the
name a reader would grep for lived solely in `REQUIREMENTS.md`.

The eight Standard modules are the authentication module plus the seven produced by the edition-gating
work: notification channels, backup tasks, geographic and link analysis, exchange and sharing, the metrics
exposition, the events feed, and operational objects. Enterprise adds the policy engine, and the profile
lists all nine **literally** — Maven profiles do not inherit, so there is no superset relation in the build
file even though there is one in the module set.

Two of the eight stage a **shaded** jar rather than the thin one, because the thin artifact cannot run:
the authentication module needs its token library, and the channels module needs its mail library. That
distinction was learned the hard way — a 16 KB thin jar carried no token library and **every** Standard and
Enterprise bundle failed to boot (§4, 2026-09-07).

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

**Three modules build on every run and ship in nothing** — the two assistant modules and the intelligence
module. This is deliberate and documented: they are plain reactor modules, not gated ones, so they compile
and test continuously and reach no bundle. Consequently the assistant routes answer 503 in **every**
artifact the build produces. Bundling them is blocked on the Java floor (§3.6), tracked as `PKG-5`, and
refused as scoped (§6).

⚠ **One module is unreachable and undocumented.** A vendor-transform plugin under the decoder tree
registers roughly forty legacy transform functions through a real provider seam, discovered by a registry
that **is** shaded into the fat JAR. Nothing depends on the module and no staging step copies it, so those
functions are unreachable in every bundle, and the documentation set does not mention it anywhere. This is
the shape the connector-bundling decision had before it was noticed. Whether the omission is intentional
could not be determined from the repository (§5).

⚠ **One seam has no implementation at all** — an expression provider interface with no registration in any
module. Harmless today; it is a seam declared for work that never came.

### 3.4 The absence contract

Every provider seam that carries edition behaviour has **a defined answer when nothing is found**, and the
answers differ by design:

| Shape | Behaviour when absent | Examples |
|---|---|---|
| **Silent skip** | The check does not happen. Personal is byte-for-byte unchanged. | Authentication, access decisions, the assistant seams |
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
board promises Personal a readable append-only log, and gating the whole feed would have broken that
promise (§4, 2026-09-08).

The largest group stubs **44 paths** across four route families. ⚠ **Nothing keeps these lists in sync with
the real modules' routes** — the board says roughly fifty. A stub list that drifts short means a real
route 404s in Personal instead of explaining itself (§5).

### 3.5 What the running system says it is

🔴 **The edition string cannot report Enterprise.** The bootstrap route derives it from **one launch flag**,
returning `personal` when authentication mode is unset and `standard` otherwise. Three consequences, all
mechanical:

* An **Enterprise** bundle reports `standard`, while carrying the policy engine.
* A **Personal** bundle started with the flag reports `standard`, while carrying no authenticator.
* A **Standard** bundle started without it reports `personal`, while carrying all eight modules.

In practice the launchers set the flag when they detect the authentication jar, so the string is usually
right — **by launcher convention, not by derivation**. And the defect has already propagated into the
acceptance contract: the deployment plan's edition probe checks that the reported value "matches intent
(`personal` vs `standard`)", so the check as written **cannot verify an Enterprise deployment** (§3.12).

The **feature flags** on the same route are the opposite, and are the model to copy: each is derived from
what actually registered, using literal paths, with a *write* path probed for operational objects so it
cannot be confused with a future core catch-all. The stub routes deliberately do not count. ⚠ The flag set
is incomplete — **four of the nine optional modules have no flag**, so channels, backup, the metrics
exposition and the policy engine are invisible to a client asking what is installed.

🔴 **The launcher's edition detection is a commercial exposure, not just a reporting defect.** The
launchers infer the edition from **jar presence**: the authentication jar means Standard and turn on
delegated authentication; the policy jar as well means Enterprise. The release pipeline builds Personal and
Enterprise only, labelling the Enterprise step "superset of Standard". That label is true at the module
level and **false at runtime identity** — hand that bundle to a Standard customer and it self-identifies as
Enterprise and enables attribute-based access control, a tier they have not purchased. One added packaging
step closes it (§5).

### 3.6 The runtime image and the Java floor

The trimmed runtime is built by **`jlink`** from **twelve** platform modules — seven derived by dependency
analysis of the fat JAR, five added because they are loaded reflectively and analysis of a fat JAR cannot
see them. The tool is located in three places in order, and its absence **throws**, pointing at the skip
switch **`-NoRuntime`**.

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
| **24** | The compile target for the whole reactor, and therefore the floor a skip-runtime deployment must meet |
| **25** | The floor the assistant modules' upstream dependency imposes — its bytecode cannot load on 24 |
| **25** | What the pipelines build on, and what the module cache actually holds |

⚠ **Two documents claim 26.** The parent build file says "the bundled JDK 26 qualifies" and the build
concept page says a "Java 26 toolchain". The cache holds 25. The *conclusion* survives — 25 satisfies the
assistant modules' floor — but the number is wrong in both places, and one of them is the file that sets
the floor.

This is the whole of `PKG-5`: bundling the assistant modules would raise the bundle's floor from 24 to 25.
Since every released bundle skips the runtime and therefore depends on the host's Java, that is a change to
the stated system requirement, not a packaging convenience.

### 3.7 Release integrity, the bill of materials, and the missing edition

**Checksums and signatures.** A checksum is always written, needing no key. A detached signature is written
when signing is requested, and requesting it is **fail-closed three ways** — no signing binary, no key, or
a non-zero signing exit all throw. That was a deliberate fix from an earlier silent downgrade to a warning.

**The bill of materials** is generated per bundle, **after staging and before zipping**, so it ships inside
the archive and is covered by the checksum and the signature. A non-zero exit throws: a bundle ships with
its bill of materials or not at all. Generating it per bundle rather than per reactor was itself a decision,
and a good one — the reactor attests a set no customer installs.

✅ **The generator declared the wrong set — fixed 2026-09-09.** Its first-party table held **four**
artifacts: the processor, the connectors, the authentication module for non-Personal, and the policy engine
for Enterprise. It knew **none** of the seven gating modules. So, until the fix:

* Every Standard bill of materials declared 4 first-party jars where the bundle carries **10** (11 staged
  jars, counting the driver sidecar); every Enterprise one declared 4 where the bundle carries **11** (12).
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
original four-module table. Measured after the fix: Personal 2 first-party, Standard 10, Enterprise 11,
with the mail dependency present in the latter two and absent from Personal, where no channels module
ships. A second defect surfaced in the same pass and was fixed with it: the driver sidecar was appended
unconditionally although the connector module already resolves it at compile scope, so every
Standard and Enterprise document carried a **duplicate** component identifier — invalid under both
schemas, in a document whose purpose is to be machine-validated.

⚠ **One thing the fix does not close.** The generator resolves through the build tool, which cannot see a
sibling module's previously-built jar across separate invocations — so it needs the reactor **installed**,
and the release pipeline installs only the agent dependency, never this reactor. Enterprise is the first
edition to expose it, because the policy module gained a test-scoped edge to the operational-objects module
in gating cell 7 and resolution covers every scope. Tracked in §5.3.

🔴 **No Standard artifact exists, and four documents disagree about it.** The release pipeline packages
**Personal and Enterprise**, both with the runtime skipped and both signed. There is no Standard step and
no Standard artifact. Against that:

| Where | What it says |
|---|---|
| The board's packaging row | Describes a Standard package in detail |
| The board's assembly note | Names `-personal` and `-standard` **classifiers** — and no `-enterprise` one, for the flavour that *is* released |
| The branching policy's manual fallback | Uploads Personal and **Standard** jars |
| The board's status note | "The script builds and bundles it" — true of the script, false of any release |

No two of the four agree, and the editions board marks both supply-chain controls green for Standard — a
column with **no artifact behind it**, which is the same hole the compliance area found from its side.

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
| **T1** | Single workstation, unzip and run | Personal | none — the core is authentication-free | Local tree; the embedded runtime means no host Java is needed |
| **T2** | Single server, behind a proxy or with in-process transport security | Standard | Delegated to the customer's identity provider; the product is a resource server | Local tree, optionally database-backed |
| **T3** | Gateway-fronted, multi-team or multi-tenant | Enterprise | Same, plus gateway assertion trust as a second anchor | Database-backed; per-tenant spaces |
| **T4** | Active/passive warm standby | Enterprise | As T3 | As T3, replicated |

Three properties of this ladder are worth stating because they are decisions, not consequences:

* **The gateway is transport only.** Routing, throttling, cross-origin policy and edge authorisation live
  there; the product **re-validates every token itself**. Never trust the gateway blindly.
* **Identity federation happens at the identity tier, never in the product.** Kerberos, directory
  federation and assertion brokering are the identity provider's job; the product only ever validates the
  resulting token. That is what keeps the dependency tree small, which is itself a compliance asset.
* **A misconfigured identity provider fails the boot** rather than serving open. A Standard or Enterprise
  bundle cannot construct its authenticator without configuration — which is why the build's own boot
  smoke has to inject placeholders, with a warning in the script never to mistake them for a working
  configuration.

**T1's exposure note is the one every tier inherits.** The control plane **binds every interface by
default, in every edition**. A flag restricts it, and an unresolvable value fails the boot rather than
widening. The default is deliberate: narrowing it would make deployed installs unreachable on upgrade. So
loopback-only is an *operator action* plus a firewall, not a property of the Personal edition — and the
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
failure is a silent one, and on Personal the channels module is not even bundled.

### 3.12 The deployment shape, preflight and acceptance

**Every tier is the same five beats: preflight, install, configure, start, verify.** Only the content
differs. T1 is verify-checksum, unzip, run, basic block; uninstall is deleting the folder. T2 adds the
service wrapper, the identity configuration, the store backends and the resource caps. T3 adds the gateway
definition import and the tenant isolation evidence.

**Fourteen preflight checks**, machine-readable, any failure blocking the install. The ones that encode a
lesson rather than a system requirement:

* **Working directory equals bundle root** — the path-resolution gotcha from §3.1.
* **Clock synchronisation** — token expiry tolerance is sixty seconds, so skew breaks Standard and
  Enterprise authentication and looks like a credential problem.
* **Native access smoke** — the embedded database must open in-process, which catches a stripped
  native-access flag before it becomes a runtime failure.
* **Identity provider reachable *before* first boot** — because misconfiguration is a deliberate boot
  failure, not a degraded start.
* **Firewall rule present** — the operator half of the bind-everything default.

⚠ Two preflight rows describe a manual step the build already performs: the database driver is staged
automatically for Standard and Enterprise, so "driver in the library directory" is a check against a design
that was superseded (§5).

**Twelve acceptance rows**, in three blocks — a basic block every tier runs, a standard block adding
authentication, transport, durability and the backup chain, and an enterprise block adding the gateway
path, tenant isolation and the failover drill. Sign-off is every applicable row green with evidence
archived against the deployment record.

The two rows that carry the most weight are the ones derived from this area's own defects: the **backend
assertion**, which is the counter-check to graceful degradation (§3.11), and the **edition probe** — which
🔴 **cannot pass for Enterprise as written**, because the value it checks is two-valued (§3.5). Fixing the
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

## 4. Decisions (dated one-liners)

| Date | Decision | Who |
|---|---|---|
| 2026-06-16 | **Hand-rolled bearer-token authentication removed from the common core.** Personal is genuinely authentication-free and authentication becomes an edition concern behind a seam — so fixes land once, and the code matches "editions add modules, never branches" | engineering |
| 2026-07-06 | **The authenticator seam and its gate ship in the core, edition-neutral** and no-op when absent; the authentication module supplies the implementation, profile-gated | engineering |
| 2026-07-07 | **The trimmed runtime's module set is sufficient for the token library** — dependency analysis, a library probe, and a boot on the exact image. The skip-runtime switch is demoted from requirement to option | engineering |
| 2026-07-23 | **The policy engine ships as the Enterprise profile** = Standard plus one module, on a decision seam, enforcing at the route and at the row | engineering |
| 2026-07-24 | **Per-tenant space isolation ships as two engine-resident seeded policies**, engaging only once a space claim is mapped, exempting access configuration, and tailorable by authoring same-named policies | engineering |
| 2026-07-25 | **Enterprise becomes a real packaging flavour with no new runtime flag** — the presence of the decision provider's registration *is* the switch, the launchers auto-detect from bundle contents, and Personal bundles stay byte-for-byte unchanged | engineering |
| 2026-08-29 | **Binding every interface stays the default in every edition**; a flag lets a deployment restrict itself, and an unresolvable value fails the boot. Narrowing the default would silently make deployed installs unreachable on upgrade | operator |
| 2026-09-02 | **Six features declared not for Personal**, opening the feature board and the edition-gating debt row | operator |
| 2026-09-02 | **The bill of materials is generated per packaged bundle, not per reactor** — the reactor attests a set no customer installs | engineering |
| 2026-09-02 | **A checksum always; a signature on request, and requesting it is fail-closed** — a missing binary, a missing key or a non-zero exit all throw rather than warn | engineering |
| 2026-09-06 | **Secret schemes are tiered by edition**: environment and system every edition, file and keystore Standard and up, vault and cloud key management Enterprise **and only when a client policy requires it**. A Personal bundle refuses the gated schemes with a message naming the edition | operator |
| 2026-09-06 | **The eight deployment decisions signed** — a container image as a convenience with orchestration out of scope; bundle the database driver; vault and key management gated on the first client policy that needs them; a committed platform list; recovery targets as contract service levels; a government cryptographic variant only against a concrete opportunity; and the reference identity and gateway pair | operator |
| 2026-09-07 | **The gating mechanism is provider modules for every cell** — ⛔ explicitly **not** launch-flag capability switches, because a switch leaves the code in the Personal bundle. Personal has no authenticator and binds every interface, so a mis-set flag would re-expose an unauthenticated surface. A module is a packaging boundary; a flag is only a policy one | operator |
| 2026-09-07 | **The route-module interface becomes public.** Core registers its own ordered list first and **appends** discovered modules; discovery order is unspecified, and a duplicate method-and-path **fails the boot** rather than resolving silently | engineering |
| 2026-09-07 | **There is no Personal profile** — Personal is the default reactor. Corrected because an unknown profile only warns, so the wrong command had been producing the right bundle | engineering |
| 2026-09-07 | **The authentication module must ship shaded**, and the script verifies the *staged* artifact for its token library and all three registrations — the thin jar carried none of it and every Standard and Enterprise bundle failed to boot | engineering |
| 2026-09-07 | **The connector module is bundled in every edition** and deliberately **not** gated: remote acquisition is a core capability, and gating it would mean correcting the acquisition rows | engineering |
| 2026-09-07 | **All four launchers use an explicit classpath**, never the jar switch — the jar switch ignores the classpath, and the fat JAR declares none, so a launcher could reach no sidecar | engineering |
| 2026-09-07 | **Only the metrics HTTP exposition is gated; the registry stays in the core**, because nine classes across three modules call it and it cannot be a module. The exposition is the whole of the exposure | engineering |
| 2026-09-08 | **The events surface moves whole**, because the audit export is a query-parameter branch inside one route — gating it alone would need a conditional inside a core route, the one mechanism the assembly model bans | operator |
| 2026-09-08 | **The audit read deliberately stays in the core** as a narrow pair of routes, fail-closed to audit and access-denied types so it cannot become the events feed by another name — because the board promises Personal a readable append-only log | engineering |
| 2026-09-08 | **Two board rows amended rather than left contradicting the build** — object stores leave Personal, and the gap watchdog is partial there | operator |
| 2026-09-08 | **The object engine provider is declared in the host module, not beside its seam**, because opening the stores needs host types and that dependency may not be inverted | engineering |
| 2026-09-08 | **The grantable capability vocabulary stays static across editions** — ⛔ not derived from registered routes, because a role file authored on Standard would then fail validation on Personal. Dead vocabulary has no route behind it and is harmless | engineering |
| 2026-09-08 | **The area is named "Editions & packaging"**, concept first and mechanism second, because *Edition* had no glossary entry until then | engineering |
| 2026-09-08 | **The board's matrix is authoritative for the Edition column**; the requirements register mirrors it and is the one to correct on disagreement | engineering |

## 5. Not built

### 5.1 Tracked, and externally gated

Nothing a shift can close from this checkout.

* **Live deployment validation** — the T2, T3 and T4 reference deployments, the identity and gateway pair,
  and the blueprints that are written but never run against live instances. The repository-side half closes
  when the recovery-target statement carries agreed numbers and at least one drill row.
* **The certification programme** — applicability, boundary, auditor engagement, penetration test, and the
  demand-gated government package. Owned by [`compliance`](../compliance/compliance.md); seven open rows,
  closing at zero.

### 5.2 Tracked and actionable

* **The deployment gap ledger** — service wrappers, the embedded-database memory cap default, surge
  admission, the vault and key-management provider, launcher token-line debris, and the thirteen archived
  documents a broken permission silently drops from every bundle. **Phases 0 through 5 are all unbuilt.**

#### The deployment script suite — distilled here 2026-09-09 so its plan could be archived

⚠ **These acceptance criteria existed only in `deployment-topology-plan.md` §7.** That plan's *topology*
design was distilled into §§3.9–§3.13 when this spec was written, but its **deliverable table was not** —
so `SCR-1` through `SCR-11` appeared nowhere outside the plan, and archiving it would have lost the
definition of "done" for every unbuilt script. Board rows: `SPEC-DEPLOY-ROWS-1` (the family) and
§3's *Deployment topology gaps* (`GAP-3`/`GAP-9`/`GAP-10`).

| ID | Deliverable | Tier | Acceptance |
|---|---|---|---|
| `SCR-1` | `preflight` | all | §8's install checks as a machine-readable report (`--json`, each row PASS/WARN/FAIL); **any FAIL blocks install**. Runs offline and catches every §8 row |
| `SCR-3` | Service wrappers | T2+ | a systemd unit (`Restart=on-failure`, `WorkingDirectory=` the bundle root, `EnvironmentFile=`) **and** a Windows service. ⚠ Recovery from process death is **restart**, not failover — this wrapper *is* the recovery mechanism (§3.11) |
| `SCR-4` | Proxy / TLS templates | T2a / T3 | nginx and IIS reference configs: TLS, HSTS, gzip for the static UI, and network restriction of `/metrics` + `/health/details` — which are **unauthenticated by design**, so the proxy is the only thing that fences them |
| `SCR-5` | `backup-offsite` | T2+ | copies verified archives off-box after `backup_verify`; acceptance is that a **restore succeeds from the off-site copy alone** |
| `SCR-6` | `verify` | all | §9's acceptance block as a script — probes, an evidence table, an exit code; green on the reference deploys and **red on each seeded fault** |
| `SCR-7` | `upgrade` / `rollback` | T2+ | §6's procedure automated including N-1 retention; drilled in **both** directions on a reference deploy |
| ~~`SCR-9`~~ | ~~Launcher hygiene~~ | all | ✅ **SHIPPED 2026-09-09.** The dead `CONTROL_TOKEN` / `ASSIST_TOKEN` lines are gone from the generated `serve.sh`, `serve.bat` and `Dockerfile`. 🔴 Worse than debris: the scripts told the operator `CONTROL_TOKEN` was *"required to use the control plane"* and printed `CONTROL_TOKEN=secret bash serve.sh` as the way to start it — so anyone following the printed instruction would believe they had secured an **auth-free** service. Acceptance met: zero token mentions in all three generated bodies, verified by extracting the here-strings rather than grepping the generator |
| `SCR-10` | Bundle-docs ACL fix | — | thirteen files under `archived-documents/plans-archive/` carry a broken deny-ACL and are **silently skipped from every bundle**; needs an Administrator `takeown` + `icacls` pass |
| ~~`SCR-2`~~ | ~~Launcher `lib/` support~~ | — | ✅ **SUPERSEDED by PG-1**: `postgresql.jar` is staged into every Standard and Enterprise bundle and auto-detected |
| ~~`SCR-8`~~ | ~~`package.ps1 -Edition Enterprise`~~ | — | ✅ **SHIPPED as EDG-01.** The row named a blocker that was already gone |
| ~~`SCR-11`~~ | ~~Container image~~ | — | ✅ **SHIPPED as PKG-3** — the Dockerfile is generated at `package.ps1:1041-1065`. ⛔ Kubernetes stays out of scope until decision D1 says otherwise |
* **The Standard runtime not re-verified against the token library** — skip-runtime until confirmed.
* **Multi-user relational deployment** — parked until a multi-operator install exists.
* **Step-processor gaps** — eighteen partial and sixty-seven planned rows on the board.
* **Policy-authoring experience** — hand-authored files only today.
* **Bundling the assistant modules** (`PKG-5`) — filed as won't-do, blocked on the Java floor (§3.6).

### 5.3 `UNTRACKED` — found writing this spec, no board row exists

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `SPEC-STALEREF-1`, `SPEC-COUNTS-1`, `SPEC-NOPROOF-1`, `SPEC-GREENCELL-1`, `SPEC-DEADSEAM-1`, `SPEC-PLANSTALE-1`, `SPEC-DEPLOY-ROWS-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`superpower/post-consolidation-sprints.md`](../../../superpower/post-consolidation-sprints.md) §Sprint 2.

Ranked. Items 2–4 change what a customer receives; item 1 is closed, and the numbering is kept so the
citations elsewhere in this spec still resolve.

1. ✅ **RESOLVED 2026-09-09 — the bill of materials declared the wrong module set** (§3.7): four
   first-party jars against ten or eleven, the mail dependency in none of them, and a comment asserting it
   matched the staging table. It shipped inside a signed, checksummed archive with **no test over the
   generator**. Closed by moving the set into `tools/bundle-modules.mjs` and adding the CI guard
   `tools/check-sbom-modules.mjs`, which holds it against the packaging script's three enumerations; a
   duplicate driver component, invalid under both schemas, was fixed in the same pass.
   🔴 **What remains, and is not the same defect — now tracked as `SBOM-RESOLVE-1` (`BACKLOG.md` §4, P1),
   so this item is no longer `UNTRACKED`:** generating the document at all requires the reactor to be
   **installed** in the local repository, because the build tool will not resolve a sibling module from a
   jar built in an earlier invocation. The release pipeline installs only the agent dependency. Enterprise
   fails first — the policy module's cell-7 test-scoped edge to the operational-objects module must resolve
   even though the document lists runtime scope only — and on a clean runner every edition would. Either the
   pipeline installs the reactor before packaging, or the generator resolves within a build phase.
2. 🔴 **No Standard artifact is built, checksummed, signed, given a bill of materials, or published**
   (§3.7). Either the release pipeline gains the step, or both supply-chain rows lose their Standard
   column. Four documents currently describe an artifact that does not exist.
3. 🔴 **An Enterprise bundle handed to a Standard customer self-identifies as Enterprise and enables
   attribute-based access control** (§3.5) — because the launchers detect the edition from jar presence and
   the pipeline builds Enterprise as a "superset of Standard". One added packaging step closes it; until
   then the superset label is a commercial exposure, not a convenience.
4. 🔴 **The reported edition string is two-valued and derived from a launch flag** (§3.5) — Enterprise is
   unreportable, and the deployment plan's edition probe inherited the defect, so the acceptance contract
   cannot verify an Enterprise deployment. Derive it from what registered, the way the feature flags
   already do, and add flags for the four optional modules that have none.
5. 🔴 **Gating cell 7 has no Personal-side test** — the largest extraction, forty-four stubbed paths, and
   the only one of the seven cells without a falsification test. The seam is exercised in engine tests; the
   absent-module HTTP behaviour is not.
6. 🔴 **The trimmed runtime ships in nothing** while a board row marks it green in all three editions
   (§2). Either the release pipeline builds it, or the row and the requirement say "buildable, not
   shipped".
7. ⚠ **Nothing keeps the five stub path lists in sync with the real modules' routes** (§3.4). A list that
   drifts short turns an explained 503 into a bare 404 in Personal — the exact outcome the contract exists
   to prevent.
8. ⚠ **The staged-jar set is stated in eight places and five are wrong** (§3.3), one of them being code
   that ships as a compliance artifact. One generated table, or one test, would collapse the whole class.
9. ⚠ **A vendor-transform plugin reaches no bundle and no document** (§3.3) — roughly forty legacy
   transform functions behind a real seam, discovered by a registry that *is* shaded in. Decide whether it
   is operator-side by design, then say so somewhere.
10. ⚠ **A provider seam has no implementation anywhere** (§3.3) — declared for work that never arrived.
11. ⚠ **Two documents state the Java floor as 26**, including the file that sets it; the cache holds 25
    (§3.6). The conclusion survives, the number does not.
12. ⚠ **The dependency count is stated as 95 across 25 reactor modules by the generated lock and as 94 by
    four prose locations** — and no stated reactor size is 25 (they are 23, 31 and 32). Cite the generated
    file, and say which profile resolved it.
13. ⚠ **The gating row says "all six cells" after enumerating seven**, and gives the object domain as both
    31 and 37 files inside the same row. The measured count is 32 domain files, 42 in the module.
14. ⚠ **The reactor page's decomposition is wrong** — "23 today: 14 default plus 9 profile-scoped" adds up
    by coincidence. 23 is the *default build* (the root, the decoder aggregator, its eight children, and
    thirteen modules); the nine profile-scoped modules take Standard to 31 and Enterprise to 32.
15. ⚠ **No pipeline builds Personal alone with tests, or Standard alone at all.** One pass runs the
    Enterprise profile, justified as the superset. The build page explicitly warns that Standard is **the
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
    exist**, which makes the signature undischarged work rather than a settled question.
19. ⚠ **The deployment plan's Enterprise topology still calls the packaging flavour pending**, and its
    deliverable row still names a blocker that is gone; the same file's gap ledger records it shipped.
20. ⚠ **Two preflight rows and one deliverable describe dropping the database driver into a library
    directory** (§3.12). The build stages it automatically for Standard and Enterprise, so the design was
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

## 6. Refused & superseded

| Item | Verdict | Why |
|---|---|---|
| **Launch-flag capability switches** as the gating mechanism | ⛔ **REFUSED** 2026-09-07 | A switch leaves the code in the Personal bundle. Personal has no authenticator and binds every interface, so a mis-set flag re-exposes an unauthenticated surface. Packaging boundary, not policy boundary |
| **Edition conditionals in the core** | ⛔ **BANNED**, standing | An edition difference is a build decision — a module or a flag — never core logic. This is what forced the events surface to move whole (§4) |
| **Edition git branches** | ⛔ **REFUSED**, standing | Versions are branches; editions are flavours of one commit. There is no Personal or Standard branch and never will be |
| **A Personal Maven profile** | **NEVER EXISTED** — corrected 2026-09-07 | The parent declares exactly two profiles. Maven warns on an unknown one and builds the default, so the wrong instruction produced the right result |
| **"Personal binds localhost only"** | **SUPERSEDED** 2026-08-29 | The code never enforced it. The claim was the defect, not the behaviour |
| **A framework migration** | ⛔ **REFUSED** | At five to fifteen users a framework buys nothing the identity provider and a few small libraries do not, and a lean dependency tree is a compliance asset |
| **In-app login, user management, directory or assertion integration** | ⛔ **REFUSED** | Identity is delegated to the customer's provider; the product only validates the resulting token (§3.9) |
| **Bundling the assistant modules** (`PKG-5`) | ⛔ **REFUSED as scoped** | Agent-absent is the intended shipped default, and bundling would raise the bundle's Java floor (§3.6) |
| **Gating the connector module** | ⛔ **REFUSED** 2026-09-07 | Remote acquisition is a core capability, and the board marks it shipped in all three editions; gating it would mean correcting the acquisition rows |
| **A per-edition capability-vocabulary validator** | ⛔ **REFUSED** | It would break role-file portability — a file authored on Standard would fail validation on Personal — and dead vocabulary has no route behind it |
| **Active/active deployment** | ⛔ **NOT OFFERED** | Both schedulers are in-process; the single-node ceiling is an accepted constraint (`NFR-8`). The escape hatch is a priced roadmap conversation, not a configuration |
| **Orchestration platform support** | ⛔ **OUT OF SCOPE** per the signed container decision | A container image is a single-node convenience; cluster orchestration is not on the roadmap until that decision changes |
| **A government cryptographic variant** | 🔭 **DEFERRED**, demand-gated | The pattern is documented against the same transport seam; the work starts only against a concrete opportunity |
| **Multi-user relational deployment** | ⛔ **PARKED** 2026-09-06 | Until a multi-operator install exists. The plan is written |
| **The profiles varying the shaded fat JAR** | **NEVER TRUE** — corrected in this commit | One unconditional shade configuration, no child profiles; optional modules ship as sidecars (§3.2) |
| **"Framework-free core, network dependencies isolated"** as a shipped property | **PARTLY SUPERSEDED** 2026-09-07 | The connector sidecar now ships in every edition. The isolation is real in the reactor and absent in the bundle (§2, `PKG-2`) |

## 7. As-built pointers

Paths and identifiers, with the gap named where one exists. ⚠ **Cite the identifier, not the line number** —
this area has three sites whose line citations drifted (§3.2).

| Concern | Where it lives | Gap |
|---|---|---|
| The edition switch and staging | `inspecto/package.ps1` — the edition guard, the module list, and the per-jar shape assertions | ⚠ Its header names three of twelve staged jars |
| The profiles | `pom.xml` — two profile identifiers, module lists only | — |
| The bill of materials | `tools/sbom.mjs`, over the set in `tools/bundle-modules.mjs` | ⚠ Needs the reactor installed to resolve (§5.3 item 1) |
| Its drift guard | `tools/check-sbom-modules.mjs`, wired into `ci.yml` | — |
| The dependency lock | `tools/dependencies.lock`, written by `tools/check-dependencies.mjs` | ⚠ 95 across 25 modules against four prose sites saying 94 |
| The release pipeline | `.github/workflows/release.yml` | 🔴 No Standard step; every bundle skips the runtime |
| The test pipeline | `.github/workflows/ci.yml` | ⚠ One profile pass; no Personal-with-tests, no Standard |
| The reported edition and feature flags | `BootstrapRoutes` | 🔴 Two-valued edition string; four modules with no flag (§3.5) |
| Absence at the route | the five `Absent*Routes` classes in the control package | ⚠ No drift guard over their path lists |
| The singular seam helper | `SpiSlot` in the control package | — |
| Route contribution | the public `RouteModule` interface | — |
| Maintenance task contribution | `MaintenanceTaskProvider` in the engine's job package | — |
| Object engine contribution | `ObjectEngineProvider` in the service package, deliberately not beside its seam (§4) | — |
| The memory-limit resolver | `DuckDbUtil.memoryLimit` — falls through to nothing when nothing is installed | 🔴 Contradicts a signed decision (§5.3 item 18) |
| The falsification tests | four `No*ShipsInThePersonalBuild*Test` classes, plus module-side halves for events and metrics | 🔴 Gating cell 7 has none |
| The examples suite | `inspecto/examples/` — 30 examples, tracked and staged | ⚠ One runs in any pipeline |
| The assembly concept | [`editions-model.md`](../../backend/editions/editions-model.md) | ⚠ Says "five" then lists seven; the shade claim is corrected in this commit |
| The authentication story | [`auth-security.md`](../../backend/editions/auth-security.md) | — |
| Bundling and reactor sizes | [`build-test.md`](../../backend/build-run/build-test.md) | ⚠ Its jar *table* omits what its own prose gets right |
| The feature board | `docs/EDITIONS.md` | See §2 |
| The deployment design | this spec §3.9–§3.13; the build plan is `superpower/deployment-topology-plan.md` | Phases 0–5 unbuilt |
| The backup runbook | `docs/ops/backup-restore-runbook.md` | — |

## 8. Verification

**What is actually enforced today**

* **The Personal-side refusal of cell 7's extraction** — `NoOperationalObjectsShipInThePersonalBuildTest`
  (added 2026-09-09, closing part of `SPEC-NOPROOF-1`). All **49** stubbed operational-object paths answer
  **503 naming `inspecto-ops`** rather than 404, `features.ops` stays present-and-false, and a source
  cross-check pins the stub surface against the test's own list. ⚠ That cross-check is not redundant: a
  path shadowed by one of the two catch-alls is **invisible** to an over-the-wire check, proven by
  mutation. Cell 7 was the only one of the seven gating cells without such a test.

* **Per-jar shape assertions** on every staged artifact, reading inside the zip for classes and provider
  registrations — the strongest control in this area, and the one that caught the unshaded authentication
  jar.
* **A boot smoke** that runs last, re-derives the classpath from what was staged, launches the control
  plane on a free port, polls health, and **throws** on failure. It is the only check that proves a bundle
  can start.
* **Fail-closed signing** — a missing binary, a missing key, or a non-zero exit all throw.
* **A bill of materials that must generate** or the bundle does not ship — and, since 2026-09-09, one whose
  module set is **held against the packaging script in CI** by `tools/check-sbom-modules.mjs`, over the
  script's three independent enumerations (§3.7).
* **Six falsification tests** proving the gated features are absent from the default build, plus two
  module-side halves proving they are present with the module.
* **A dependency lock diffed on every run**, distinguishing drift from could-not-run.

**Falsify, don't read — five probes worth running**

1. **Unzip a Standard bundle's bill of materials and count its first-party entries.** Four means the defect
   is live; **ten** means it was fixed (eleven for Enterprise). Counting *staged jars* instead gives 11 and
   12 — the driver sidecar is third-party, and conflating the two is what made the original counts confusing.
2. **Grep a released Enterprise bundle's launcher for the policy jar**, then start it and read the reported
   edition. It will say `standard` while enabling Enterprise policy.
3. **Ask a Personal build for a gated path** — an object route, say. A 503 naming the module is correct; a
   404 means the stub list drifted.
4. **Look for `runtime/` in any published artifact.** Its absence falsifies the runtime row in all three
   editions.
5. **Build with the Standard profile alone.** It is the only profile that proves an optional module is
   self-contained, and no pipeline runs it.

**A capability-pointer check over this file** — it is **`tools/check-doc-citations.mjs`, committed 2026-09-09**
and wired into `ci.yml` and `.githooks/pre-push`. It checks this file's backticked paths and
class names against `git ls-files` and the module tree.
