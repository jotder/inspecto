<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-10-03 (operator away; decisions made rationally and recorded below, none irreversible).
  Retire per the three-tier lifecycle in CLAUDE.md when the embeddable view (`LA-EMBED-VIEW-1`) ships or is declined:
  distil into `okf/frontend/features/link-analysis.md` §*External references and the Dossier bundle*, then archive.
-->

# LA separation — D-6 design (integration: references, Dossier bundle, embeddable view)

Option D's phase **D-6** ([`la-separation-feasibility-plan.md`](la-separation-feasibility-plan.md) §7.6, §7.8): let an
Investigation point at things in other systems and hand its evidence to someone outside — without any installation
trusting another. Integration is **by reference first**; a live call (and so the trust relationship I1) is needed
only by features that make live calls, and none of the three below does.

## 1. What shipped, and what did not

| # | Item | Status |
|---|---|---|
| 1 | External references appended to an Investigation | ✅ built 2026-10-03 |
| 2 | Dossier export bundle (portable, sealed, verifiable, masked) | ✅ built 2026-10-03 |
| 3 | Read-only embeddable view (URL + scoped token) | ⏸ **deferred** — `LA-EMBED-VIEW-1` (§5) |
| — | I1 trust between installations | ⛔ not built — no live call exists to need it (decision D6-7) |

## 2. External references (decisions D6-1…D6-3)

`GET` / `POST /inv/investigations/{id}/references` — `{system, type, id, url?, label?}`.

* **D6-1 Storage.** An append-only `references.jsonl` beside the op log (`SnapshotStore.appendReference` /
  `readReferences`), not a header field: the header is write-once, and a reference is a relationship, not evidence. So it
  is outside the Dossier manifest too — adding one never invalidates an issued Dossier. No edit, no delete; a wrong
  reference is superseded by a new one. A duplicate `(system, type, id)` is a 409; at most **200** per Investigation
  (409) — check-and-append is one locked step, so two racing callers cannot both pass the limit. The directory is under
  `audit/`, already a reserved import path (`ReservedConfigPaths`), so an imported bundle cannot plant one. A fork or an
  instantiated template is a NEW Investigation and starts with none.
* **D6-2 Never trusted.** A reference is caller text. It is **never dereferenced** (the server never calls `url`),
  never merged into the Working Set or the log, never read as an id the analysis acts on, and it **grants nothing** — a
  reference naming a Case does not share the Investigation with that Case's team (only `PUT …/case` does, which checks
  the Case). Every record carries `trusted:false`. `url` must be an absolute `http`/`https` URL with a host and no
  embedded credentials (`javascript:` / `file:` / `ftp:` would be an XSS or local-read vector in any UI that renders it as
  a link); `system` / `type` match `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`; `id` ≤ 256, `url` ≤ 2048, `label` ≤ 200 characters,
  no control characters.
* **D6-3 Gates.** `POST` is `canManageIncidents` (it is Case work, like appending to the log — no new capability) →
  owner-only (`InvestigationRoutes.open`) → 422 field → 409 duplicate/full → append. `GET` takes the read gate
  (`openForRead`: owner or a member of the linked Case; R3; the Enterprise PDP). Audited: `LINK_INVESTIGATION_REFERENCE_ADDED`.

## 3. Dossier export bundle (decisions D6-4, D6-5)

`GET /inv/investigations/{id}/dossier/bundle?at=&snapshots=` and `POST …/dossier/bundle/verify`
(`DossierBundleRoutes`; the Dossier build was factored in `DossierRoutes` so the Dossier, its verify and the bundle share
ONE implementation).

```
{ format: "inspecto-dossier-bundle/1", investigationId, at, snapshots, masking,
  dossier,                                  // exactly what GET …/dossier answers, masked
  references,                               // the Investigation's references, masked, each trusted:false
  custody: { manifestRoot, referencesCount, referencesHash },
  seal: { algorithm: "SHA-256", value },    // over the canonical JSON (keys sorted) of every field above
  generatedAt }                             // outside the seal, like the Dossier's own
```

* **D6-4 Verifiable.** The seal makes the bundle tamper-evident *as shipped* (anyone can recompute it). The embedded
  manifest's own root is self-checking offline. What needs the server — whether the **store** still agrees — is the
  verify route: it checks the seal, `custody.manifestRoot` = the embedded manifest's root, that the bundle's references
  are still the **first `referencesCount`** records of the store (`referencesIntact`; later additions are reported as
  `referencesAddedSince`, not a failure — the file is append-only), and then runs the SAME manifest comparison as
  `POST …/dossier/verify` (`DossierRoutes.verifyManifest`). A seal that fails is a RESULT (`verified:false`, with
  `problems`), never an error that hides what changed. Re-sealing an edited bundle is caught by custody.
* **D6-5 Masking and gates on export.** The Dossier and the references are masked per the Space's `maskingMode` as
  the bundle is built (D-U6) — a bundle never carries a raw id its caller could not see (a reference whose id equals a
  masked entity id is masked too). The manifest and `referencesHash` hash the RAW store, so the **root is identical masked
  or not** and the masked bundle still verifies; the seal covers what shipped. R3 (a Dataset the caller can no longer
  view reads as absent, for the bundle and each included snapshot) and the Enterprise PDP apply through
  `openForRead`. **Four-eyes (D-U7) holds by construction**: the bundle draws only on the sealed log, which holds an
  expand only once approved (and then names requester and approver); a PENDING request is not in it. The export is a read
  that persists nothing, so it takes no capability (the verify POST is the existing "read-shaped" exemption); both
  are audited (`LINK_DOSSIER_EXPORTED`, `LINK_DOSSIER_BUNDLE_VERIFIED`) because handing evidence out is an act.
  ⚠ Trade-off accepted: a Case member (read-only) can export what they can already read.

## 4. Decision D6-7 — no trust between installations (I1)

Nothing here calls another installation: a reference is a pointer, a bundle is a file, verification is local. I1 stays
**unbuilt** until a feature needs a live call (a remote Case write, the `LA-EMBED-VIEW-1` token exchange across hosts).

## 5. Deferred — the embeddable view (decision D6-6)

A read-only embeddable view (URL + scoped token) is **not built**, on purpose:

1. It needs a **new principal type**. Today `Authenticator` returns a `Subject` with capabilities; a view token is a
   credential scoped to one Investigation, read-only, expiring and revocable — a token store, mint and revoke routes
   (each a new gated mutation), an expiry sweep, and an exemption in `ComponentAccess` / the PDP / R3 for a caller who is
   not a user. That is a security-model change in `inspecto-auth-spi`, shared by both hosts, not a route.
2. A leaked URL would hand **masked evidence to an unauthenticated holder** with no audit identity, and the embed needs
   `frame-ancestors` / CSP and cross-origin cookie decisions the SPA does not have today. These are decisions the operator
   should see, not ones to take silently while away.
3. The view itself is a **UI** (a read-only Working Set / canvas shell in `projects/link-analysis`); another lane is
   editing that panel now.

The bundle (§3) covers the sharing need that exists today with none of that risk: it is portable, sealed, masked and
verifiable. Row `LA-EMBED-VIEW-1` (P3, `docs/BACKLOG.md` §3.12) records the gate: build only when a consumer asks for a
live, revocable view rather than a file.

## 6. Proof

`ControlApiDossierBundleTest` (real HTTP, armed Authenticator): references append/list/`trusted:false`/duplicate/limit;
hostile URLs and fields refused; owner-only, capability and no-access-granted; the bundle's content and seal; an edited
bundle (dossier body, reference, re-sealed, root) fails; a store edited after export fails custody; references added
after export do not break it but a rewritten one does; masking on export with an identical root; owner-only, R3 and
422 shapes. The key negative tests were mutation-checked (seal always true, references unmasked, references prefix
unchecked, POST not owner-only, URL scheme and credential checks removed, `trusted` flipped).
