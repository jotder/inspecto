---
type: Reference
title: Operations reference — utilities, batching, output, deployment
description: The long-form operations manual — output structure, the status log and auditing, batch processing, remote-server deployment, and onboarding a new Collector. The largest reference in the bundle.
resource: inspecto/package.ps1
tags: [operations, utilities, deployment, batching, output, onboarding]
timestamp: 2026-07-16T00:00:00Z
item: layer.platform
audience: internal
status: current
verified: 2026-11-20 last-edit
---

# Operations: Utilities, Batching, Output & Deployment
> **Deep reference — the detail tier.** Start at [Operations](admin-guide-launch-flags.md) for the summary; this page is the long form it points to.

> 🔴 **NOT the authority on `-D` flags, auth headers, launchers, or the Java floor.** This page is 66 KB
> moved wholesale from the retired root-level `admin-guide-launch-flags.md` on 2026-07-16 and its long tail is still sound — output
> structure, the status log, batch processing, deployment and Collector onboarding.
> Its *cross-cutting* material has drifted, and a 2026-09-08 sweep verified four defects against source:
>
> - ✅ **`-Dcontrol.token` — the dead flag is GONE from this page (2026-09-14, `DOC-DEADTOKEN-1`).** It has
>   **zero** Java readers, and so does its sibling **`-Dassist.read.token`**, so every example that set them
>   authenticated nothing while reading exactly as though it did. The launch examples no longer pass them,
>   the `CONTROL_TOKEN=…` invocations are plain `serve.sh` / `serve.bat`, and the curls now send
>   `Authorization: Bearer $TOKEN` — the header shape was always right for Standard/Advanced; only the
>   value's provenance was a lie.
>   ⚠ **What `serve.sh` actually reads was ALSO wrong here**, and is corrected from the generated launcher
>   rather than from this page's memory: `PORT`, `SPACES_ROOT`, `CORS_ORIGIN`, `AUTH_OIDC_*` — never
>   `CONTROL_TOKEN` or `ASSIST_TOKEN`. For real auth read
>   [auth & security](../../module/access/admin-guide-auth-security.md).
> - ⛔ **`-Dui.static.log=DEBUG` does not exist** — no such key in any Java source, so the two places
>   that tell you to turn it on to make an abort visible cannot work.
> - ⚠ **`java -jar` and `mvn clean package`** are both wrong for this repo: the launcher uses `-cp`
>   (RUNSH-CP-1) and packaging is `-pl inspecto,:inspecto-connectors -am -DskipTests -q`. See
>   [build & test](spec-build-test.md).
>
> For the curated flag table read [Operations](admin-guide-launch-flags.md); for build, reactor and `package.ps1` read
> [Build & Test](spec-build-test.md). Fix a cross-cutting fact **there**, not here. *(Moved from the retired root-level `admin-guide-launch-flags.md` (docs consolidation, 2026-07-16).)*

> Part of the [Inspecto](../../../../../inspecto/README.md) documentation. See the [docs index](../../../../INDEX.md).

## Parts

This page is split by topic into 4 parts; each part lists the sections it holds.

1. **[Output structure and the status log](admin-guide-operations-reference-output-audit.md)** — Pre-ETL file staging — retired · Output Structure · Status Log & Auditing
2. **[Batch processing](admin-guide-operations-reference-batch-processing.md)** — Batch Processing
3. **[Batch processing — the status backend onward](admin-guide-operations-reference-status-backend.md)**
4. **[Remote deployment and onboarding a Collector](admin-guide-operations-reference-deployment.md)** — Deployment — Remote Server · Onboarding a New Collector
