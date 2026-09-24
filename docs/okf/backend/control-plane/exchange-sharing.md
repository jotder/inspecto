---
type: Concept
title: Exchange — Cross-Space Sharing
description: Grant-mediated, read-only Dataset/Widget sharing across Spaces — offer/request/approve ledger, snapshot/live delivery, version pin + drift, the sharing.component UI.
resource: inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java
tags: [control-plane, multi-space, exchange, sharing]
timestamp: 2026-09-25T00:00:00Z
---

# Exchange — Cross-Space Sharing

Datasets, Widgets and saved Views can be shared **read-only, per-item, opt-in** across [Spaces](multi-space.md) via
a grant-mediated ledger — the "ministries" model (a department's Dataset used by another department's
Widgets/Queries/Alert Rules, without reprocessing or re-creating pipelines). Nothing is discoverable
across Spaces unless its owner offers it; nothing is usable without an active grant (fail-closed).

## Backend — `com.gamma.exchange` + `ExchangeRoutes`

Installation-scope, un-prefixed routes (like `/spaces`) — they address `spaces/_shared/`, not one
Space's engine, so they fall through `ControlApi.dispatch`'s `/spaces/{id}` seam untouched. Every route
409s outside a multi-space runtime (`Exchange.under(containerRoot)`), and 409s are open only to reads
until a capability check (writes gated on `canOfferDatasets`/`canRequestShares`/`canApproveShares`; a
no-op on Personal, enforced on Standard).

```
GET  /exchange/offers[?owner=]                 the shareable catalog (metadata only, never rows)
POST /exchange/offers                          owner lists/updates an offer
POST /exchange/refresh                         owner republishes a dataset's snapshot
POST /exchange/requests                        consumer requests use
POST /exchange/grants/{id}/{approve|deny|revoke}  owner acts on a grant
POST /exchange/grants/{id}/pin                 consumer pins/clears a snapshot version
POST /exchange/grants/{id}/expiry               owner sets/clears a grant's expiry
GET  /exchange/grants[?space=]                 the grant ledger
GET  /exchange/datasets/{owner}/{item}[?consumer=]  one item's metadata (+ grant status)
GET  /exchange/widgets/{owner}/{item}?consumer=     render-only view of a shared Widget
GET  /exchange/views/{owner}/{item}?consumer=       render-only view of a shared saved View (D9)
```

**Every write is authorized in the Space that OWNS what it acts on** (`EXCHANGE-OWNING-SPACE-AUTHZ-1`,
fixed 2026-09-24). Because these routes are un-prefixed, `ControlApi.authenticate` binds them to the
DEFAULT (first-hosted) Space, and the attached Subject carries *that* Space's `roles.toon` grants. Until the
fix the gates read exactly that: a caller holding `canApproveShares` only in the default Space approved,
denied, revoked and set expiry on grants owned by any other Space (and offered/refreshed their Datasets,
requested/pinned for their consumers), while the owning Space's own approver was refused. Reproduced over
real HTTP with per-Space role tables before the fix. Now each route declares
`ApiContext.withCapability(cap, RolesRootOf, handler)`; `ApiContext.requireCapabilityIn` re-runs the active
Authenticator on the request's own credential with `Roles.ATTR_CONFIG_ROOT` pointed at the owning Space
(restoring the bound Space's root and held roles afterwards). It is fail-closed: a different identity, no
config root or a credential that no longer resolves all give 403.

| Route | Capability | Decided in |
|---|---|---|
| `POST /exchange/offers`, `/exchange/refresh` | `canOfferDatasets` | body `owner` |
| `POST /exchange/requests` | `canRequestShares` | body `consumer` |
| `POST /exchange/grants/{id}/{approve,deny,revoke}`, `/expiry` | `canApproveShares` | the grant's `owner` |
| `POST /exchange/grants/{id}/pin` | `canRequestShares` | the grant's `consumer` |

An unknown grant id 404s before the gate, because it has no owner to authorize against. The ids are
derivable, and `GET /exchange/grants` is an open read, so the 404 reveals nothing new. On Personal (no
Subject) the owner is never resolved and behaviour is unchanged. Pinned by
`ControlApiExchangeOwningSpaceAuthzTest` (inspecto-exchange). Each case pairs a wrong-Space caller (403,
grant/offer untouched) with an owning-Space twin (200). A mutant that restores the bound-Space check turns
all 4 red with `approvedBy: user-steward`.

**`ShareGrant`** lifecycle: `requested → active | denied`; `active` → `revoked`/`expired`. One grant per
`(kind, item, owner, consumer)` quad, id `consumer~owner~kind~item`. Fields include `mode`
(`snapshot`|`live`), `pin` (a `"v<n>"` version string; null = track current), `expiresAt` (epoch millis;
null = no expiry). A **derived grant requires the grants of every Dataset it reads** — offering/requesting a
widget or saved view auto-pairs those datasets' offers/grants; approving it activates the whole closure;
revoking any one dataset grant cascades revocation to every derived grant that reads it. See the D9 section
below for the generalized rule (`Exchange.DERIVED_KINDS`) — a widget is just the one-dataset case.

**Snapshot delivery (S2, the default).** `ExchangeSnapshotWriter.publish` resolves the owner's own
`DatasetRelation` (never a shared ref), `COPY`s it to
`spaces/_shared/exchange/<owner>/<item>/v<epochMillis>/snapshot.parquet`, then atomically flips a
sibling `current.toon` pointer (`ExchangeSnapshots`) — a reader never observes a half-written version.
Freshness (`version`, `rows`, `refreshedAt`, Result Set columns) travels in `current.toon`, merged into
every offer/metadata response as `freshness`. Versions are **monotonic**: the server mints
`v<System.currentTimeMillis()>`, and any producer must match `/^v(\d+)$/` so versions stay comparable by
the trailing integer.

**Live delivery (S3, opt-in).** `ExchangeRefResolver` routes a `shared/<owner>/<item>` ref straight to
the owner's Table directory, read-only, through a per-grant jail root — no snapshot, always current, but
contends with the owner's workload; revocation must invalidate in-flight query plans. Consumer refs are
plain strings (`shared/<owner>/<item>`) that flow through Widget bindings, Alert Rule `dataset:`,
`$signal.dataset`, job `params` exactly like a local ref — resolution is grant-checked and fail-closed
(no active grant ⇒ ref does not resolve, even if the files exist).

## UI — Catalog `sharing.component` (§3.6, fully shipped 2026-07-19)

`inspecto-ui/src/app/modules/admin/catalog/sharing.component.ts` is one pane, two views selected by the
Catalog tab that hosts it (`shared-with-me`/`shared-by-me`, gated on `SessionService.exchangeEnabled()`
← `bootstrap.features.exchange`):

- **with-me** (consumer): grants where the active Space consumes + the requestable catalog of other
  Spaces' offers. Actions: request access (`RequestShareDialog`), pin/clear a version
  (`GrantGovernanceDialog`).
- **by-me** (owner): inbound requests + grants on the active Space's offers (approve/deny/revoke) + its
  listed offers. Actions: approve/deny/revoke, refresh a dataset's snapshot, set/clear expiry.
- **Offer flow**: `datasets.component`/`widgets.component` gain an "Offer for sharing" action
  (`OfferShareDialog` → `ExchangeService.offer()`), gated on the same `exchangeEnabled` signal.
- **Scope badges**: any dataset/widget bound to a `shared/<owner>/<item>` ref renders
  `<inspecto-status-badge value="shared" [label]="'Shared · '+owner">` in Studio's dataset/widget lists.

**Pin-drift indicator (the last §3.6 piece, 2026-07-19).** The Pinned column renders a warning-tone
"Behind" chip when an active grant's pin trails the offer's current `freshness.version` — client-computed,
no backend change, since both numbers are already loaded for the grid:

```
driftVersion(grant, currentVersion) =
  grant.status === 'active' && grant.pin && currentVersion && currentVersion !== grant.pin
    && versionSeq(currentVersion) > versionSeq(grant.pin)
  ? currentVersion : null
```

`versionSeq` parses the trailing `/^v(\d+)$/` integer; an unparseable version (either side) never
triggers a false "Behind". `myGrantRows` (a `computed`) joins each view's grants against a
`owner~kind~item → freshness.version` map built from the loaded offers. `statusBadgeHtml` gained an
optional `label` param (`statusBadgeHtml('warning', 'Behind')`) so the string cellRenderer can show
custom text under a chosen tone, mirroring the `<inspecto-status-badge>` component's `value`+`label`
split — no new color owner, still passes `lint:tokens`.

## Saved views on the kind axis (BACKLOG D9) — SHIPPED end-to-end 2026-07-26

`kind` carries a **third value, `link-analysis-view`** (the link-analysis saved view is the first adopter)
alongside `dataset`/`widget`. The call was to widen this seam rather than build a parallel sharing mechanism
for views, so grants, expiry, revocation and the audit ledger stay in one place. *(The earlier "sharing is
frontend-only" claim in the backlog was wrong — `OfferShareDialog` and `ExchangeService.offer` were
hard-typed, but so was the backend.)*

**Derived kinds are now the generalization.** `Exchange.DERIVED_KINDS = {widget, link-analysis-view}`
replaced four `if ("widget".equals(kind))` blocks in `request`/`approve`/`revoke`/`canRender`, and
`Offer.dataset` (singular) became **`Offer.datasets`** (a list) — a widget binds exactly one, a view may read
several. As built:

* **The closure spans every Dataset the item reads.** Requesting a derived item creates a pending grant per
  dataset; approving activates the whole closure atomically; revoking **any one** dataset grant cascades back
  to it. `Exchange.canRender(consumer, owner, kind, item)` requires the item's own grant **and** every
  dataset grant to be active — so an **empty closure is a denial, never a free pass**
  (`canRenderWidget` now delegates to it).
* **A view is live-mode only.** `Exchange.effectiveMode` rejects an explicit non-`live` mode with
  `IllegalArgumentException` → **422** at the edge, and defaults an *omitted* mode to `live` instead of the
  Dataset default (the paired dataset grants inherit it). Enforced **in the ledger**, not only at the HTTP
  edge, so no caller can bypass it. ⚠ Rejecting is deliberate — silently treating `snapshot` as `live` would
  hide a real misunderstanding of what a view is.
* **⚠ Only an `entity-projection` view is shareable, and its Datasets are its projection MAPPINGS** —
  `query.projections[].datasetId` (multi-mapping: the real reason one view reads several Datasets), falling
  back to the single `query.projection.datasetId`. **They are NOT `query.roots`/`query.from`** — that is the
  `lineage`/`provenance` shape, whose roots are catalog assets and Pipelines respectively, neither of which
  the Exchange can grant. A view on any other source is refused at offer time (**422**) rather than shared
  with a closure that could never be enforced. *(This cost a preview cycle: the shipped saved views all use
  the single-mapping shape, so a roots/from reading 422s every real view while passing hand-written tests.)*
* **New `GET /exchange/views/{owner}/{item}?consumer=`** mirrors the widget render route: the view read-only,
  with each mapping's `datasetId` rewritten to `shared/<owner>/<id>`. Because that is the **same
  plain-string ref shape a local Dataset uses**, `POST /inv/projection` takes it unchanged and
  `ExchangeRefResolver` keeps grant-checking it — so **`ExchangeRefResolver` needed no change at all**, and
  fail-closed still holds: revoke the dataset grant and the ref stops resolving even though the files exist.
* **Snapshot publishing is untouched.** `POST /exchange/refresh` looks up a `dataset` offer specifically, so
  a view offer simply 404s there — no view ever reaches `ExchangeSnapshotWriter`.

**Persisted-shape note.** `Offer.toMap` writes only `datasets`; `fromMap` *also reads* the pre-D9 scalar
`dataset` key so ledgers written before this change still load. **Do not "unify" that legacy read away, and
do not start writing `dataset` again** — one concept, one persisted spelling is what keeps a widget offer
from developing the split brain that D7 phase 2 had to close.

**UI.** `ExchangeKind` is the shared union; `OfferShareDialog` accepts any kind and explains the two
view-specific truths (datasets first, shared live), rendering "saved view" rather than the wire value. Link
Analysis's per-view menu gained "Offer for sharing" beside Comments/Tags (the D10 idiom), gated on
`exchangeEnabled() && canOfferDatasets()` — the same capability the server gates offers on, since a shared
view exposes its datasets' rows — **and** on the view's source being `entity-projection`, so an unshareable
view never shows an action that could only 422. The offline `exchange.handler` mirrors all of the above.

Tests: `ControlApiExchangeViewTest` (5, real HTTP — full closure, single-mapping, wrong-source 422, refresh
404, unknown kind 400) · `ExchangeTest` +4 (two-dataset closure, live-only, empty-closure denial, legacy
scalar key) · `exchange.handler.spec` +2.

## What is deliberately out of scope

No Schema sharing (a Dataset's own Result Set is self-describing). No sharing of Dashboards, Pipelines,
Jobs, Queries, Expectations — cross-instance/staging transport for those stays [Metadata Bundles](metadata-bundle.md)
(copy semantics), or the per-data-source zip bundle, whose closure carries a Pipeline's bound components and
— since 2026-09-24 — the Reference Datasets it reads, as their producing Pipelines
([editable round-trip §22](../pipeline-graph/editable-round-trip.md)). Neither is sharing: a Reference that
travels in a bundle is copied into the target Space, never granted. A connection is the one dependency a
bundle may leave behind: since 2026-09-25 importing one that names a connection the target lacks **succeeds**,
lands each Pipeline/job needing it disabled (`active: false` / `job.enabled: false`), and returns a
`connectionWarnings` entry per missing connection ("connect X to enable") instead of the former 422. No cross-space writes, ever. No per-row/column masking in v1 (an owner shares the
whole Dataset or offers a pre-filtered derived one). No wholesale/public scope.

## Design-of-record

**This page is the design of record.** *(Provenance:
`docs/archived-documents/plans-archive/storage-layout-and-sharing-plan.md` carries the original L0–S3 + UI-track
phasing, entirely shipped; kept for rationale, never maintained — do not read it for what is built.)*
