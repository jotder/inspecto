---
type: Capability
title: Editions & packaging (PKG) — capability spec — Purpose, scope and requirements of record
description: Part 1 of 4 of Editions & packaging (PKG) — capability spec: Purpose & scope; Requirements of record
status: current
item: layer.platform
audience: internal
verified: 2026-11-19 last-edit
---

# Editions & packaging (PKG) — capability spec — Purpose, scope and requirements of record

Part 1 of 4 of [Editions & packaging (PKG) — capability spec](plan-editions.md) · next: [Specification](plan-editions-specification.md).

## 1. Purpose & scope

This capability answers one question: **what does a customer actually receive, and how is it produced?**
Every other area describes a feature. This area describes the artifact the feature arrives in — and it is
where the gap between "the code exists" and "the code ships" is either closed or hidden.

The organising rule is one sentence, and it is the most load-bearing sentence in the area:

> **A feature an edition lacks is a module that edition does not bundle.** Never a branch, never a
> conditional in the core, and — after 2026-09-07 — never a launch flag either.

**In scope**

* **The three editions** — Basic, Standard, Advanced — as build flavours of one commit, and the five
  mechanisms that assemble them.
* **The 25**<!--count:optional-modules--> **optional modules**, the 32<!--count:advanced-first-party-jars--> first-party jars an Advanced bundle stages (plus the `postgresql.jar` sidecar), and the shape assertions that prove each staged
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
  [`compliance`](plan-compliance.md). This spec owns the supply-chain **mechanism**; that spec
  owns whether it satisfies a control.
* Version and release-line policy — `docs/BRANCHING.md`. The one requirement that belongs here is its
  corollary: versions are branches, editions are not.

## 2. Requirements of record

`docs/okf/product/layer/platform/spec-requirements.md` §3.16 carried four rows, all marked `SHIPPED`. **Three of the four overstate what
ships**, and the fourth is accurate about the mechanism while its status is contradicted in three other
files. The corrections below are the requirement of record.

| Row | Register says | Correction of record |
|---|---|---|
| **PKG-1** | One fat JAR + trimmed runtime; per-edition bundles via the packaging script — `SHIPPED`, all editions | 🟡 **Bundles three of three since 2026-09-10** (`STANDARD-BUNDLE-1` — the release pipeline gained the Standard step, §3.7); until then no Standard artifact was produced by any automated path. Still open for the runtime: **no released artifact contains the trimmed runtime yet** — but the blocker is FIXED (2026-09-14) and it was never the one recorded. 🔴 The stated cause, "the runner has no module cache", is wrong twice over: `package.ps1` hardcoded **`jlink.exe`** in all three probes, which cannot exist on the `ubuntu-latest` runner, so the step could only throw; and measured inside `eclipse-temurin:25-jdk` (what `setup-java` installs there) that JDK ships **zero** jmods yet `jlink` still links a working ~64 MB runtime **from the run-time image itself** (JEP 493). ⛛ Do not provision a module cache — that is not what was missing. The lookup is now platform-aware and `release.yml` no longer passes `-NoRuntime`; the claim turns true on the first `v*` tag. |
| **PKG-2** | Lean bill of materials: framework-free core, network dependencies isolated in the connector module — `SHIPPED`, all editions | ✅ The set of first-party jars lives once in `tools/bundle-modules.mjs` and a CI guard holds it against the staging script.
A **duplicate `postgresql` component**, carrying an invalid `bom-ref` / `SPDXID`, was fixed in the same pass. The isolation half still does not hold as written: the connector sidecar ships in **every** edition by a 2026-09-07 decision, and the mail dependency **left** it for `inspecto-notify-channels` in gating cell 1. "Lean" was measured and belongs to the *bundle*, not the reactor (§3.7). |
| **PKG-3** | Runnable, self-contained example suite — `SHIPPED` (should), all editions | 🟡 **Shipped and largely unexercised.** Thirty examples in seven categories are tracked and staged into every bundle. **One** of the thirty runs in any pipeline, and only on a tag. "Runnable" is proven for one example and asserted for twenty-nine. |
| **PKG-4** | Verify the Standard bundle's runtime module set against the token library — `SHIPPED` (must, Standard), verified 2026-07-07 | ⚠ **The evidence covers five of the twelve modules.** The recorded verification names the four the token library needs plus the elliptic-curve provider; the remaining seven in the image are unattributed by it. The status is then stated three ways: the register says resolved, the backlog says not re-verified, and the build concept page says the runtime step is unproven. All three are moot for shipped artifacts, which contain no runtime at all. |
| **OPS-07** (`EDITIONS.md`) | Embedded trimmed JVM runtime in the bundle — ✅ in all three editions | 🟡 **Blocker fixed 2026-09-14; no shipped artifact contains it until the next tag.** Same root cause as PKG-1 — and that cause was misdiagnosed: a hardcoded `jlink.exe`, not a missing module cache. The row still describes a capability of the script rather than a property of the bundle a customer receives, and stays amber until a release proves otherwise (marking it green now would be a fresh `SPEC-GREENCELL-1`). |
| **§Matrix packaging row** and **§Assembly module list** (`EDITIONS.md`) | Standard = core plus six named modules | ⚠ **Understated, and the two lists are different sixes.** The build stages **eight** optional modules for Standard and nine for Advanced. The prose in `build-run/build-test.md` has the correct set; the two board sites do not (§3.3). |

**On the Edition column.** `docs/EDITIONS.md` is authoritative for which edition carries a feature, and
`docs/okf/product/layer/platform/spec-requirements.md` §3 mirrors it — that direction was settled 2026-09-08 and this spec keeps it. For
`PKG` the corrections run the other way from the usual: the register's *edition* column is right, and the
defect is in its *status* column.

**On the scale ceiling.** `NFR-8` records single-node as an accepted constraint, and it is the reason
active/active is refused rather than deferred (§3.9, §6). It is the one non-functional requirement this
area does not correct.

## Changes

- 2026-09-09: PKG-2 corrected — the generator had declared four first-party jars against the ten a Standard bundle carries and the eleven an Advanced one does, knowing none of the seven gating modules; the mail dependency consequently appeared in no bill of materials (§3.7).
