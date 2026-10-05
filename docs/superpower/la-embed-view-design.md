<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-10-05. DESIGN ONLY — nothing built, no code. Decisions EMB-1…EMB-14 OPEN (section 12). BACKLOG row: `LA-EMBED-VIEW-1` (P3, §3.12).
  Operator 2026-10-03: KEEP DEFERRED until a named consumer asks — this doc exists so the build is a decision-list, not a research task.
  Retire per the three-tier lifecycle when the view ships (distil into okf/frontend/features/link-analysis.md) or is declined.
-->

# Read-only embeddable Investigation view — design

**Question.** Can a person with no Inspecto login be shown a *live, revocable, read-only* view of ONE Investigation (a URL a
partner or auditor opens, or an iframe in another system) — and what must be true so a leaked URL is a bounded, auditable event?

**Answer in one line.** Yes, by cloning the BI share-token *pattern* (self-verifying public prefix, fail-closed, uniform 404) but
**not its token** (a stateless HMAC cannot be revoked one at a time), adding a server-side grant record, and giving the holder a
new, deliberately powerless principal type `EmbedGrant` that can never reach a capability-gated route.

## 1. Context (what exists)

* **BI share tokens** — `inspecto/.../control/ShareTokens.java` + `ShareRoutes.java`: `base64url(type/name/exp).HMAC-SHA256`, verified
  statelessly; revoked only by rotating `-Dbi.share.secret` (ALL links at once); inert without that secret; every failure is one
  indistinguishable `404`; routes `POST /dashboards/{name}/share` (gated `canAuthorWorkbench`), `GET /public/dashboards/{token}`,
  `POST /public/dashboards/{token}/query` (fenced to the Dashboard's own Datasets, caller filters ignored). The auth gate exempts the
  prefix via `ControlApi.isSelfVerifyingPublic`, and `CapabilityManifest` carries a `self-verifying-public` exemption row.
* **Investigation** (`inspecto-la-api`, `InvestigationRoutes`): an append-only replayable log + Working Set; every response carrying
  entity ids is masked per the Space `maskingMode` (`EntityMasking`, D-U6); reveal is per entity and capability-gated.
* **Dossier bundle** (D-6, shipped): a sealed, masked, portable file — the sharing path that exists today.
* **Auth SPI** (`inspecto-auth-spi`): `Subject(id, capabilities, dataScopes, attributes, ...)` — every gate speaks capability verbs
  over a `Subject`; `AuditTrail`, `AccessDecider` (PDP), `ComponentAccess`, `RowScope`, `CapabilityManifest` (route table).
* `ShareTokens` is a package-private `final class` in `inspecto` core; `inspecto-la-api` is a separate module (and can be a separate
  deployment), so the embed cannot simply call it.

## 2. Does the BI share-token seam fit?

| Need | BI seam | Verdict |
|---|---|---|
| Scoped to one resource | `type/name` in payload | fits (`investigation/<id>`) |
| Expiring | `exp` in payload | fits |
| Self-verifying public prefix, fail-closed, uniform 404 | `isSelfVerifyingPublic`, `requireScope` | **reuse as-is** |
| **Revocable per token** | rotate the global secret only | does not fit — one leaked link must not kill every link |
| Audit identity | none (anonymous) | does not fit — needs a grant id on every read |
| Listable ("who did I share this with") | no record | does not fit — needs a record |
| Reachable from the LA host | package-private in core | does not fit — LA is another module |

**Recommendation: reuse the pattern, not the class.** A new `EmbedGrants` store holds the record; the token is an opaque random
256-bit value (not an HMAC payload) and only its SHA-256 is stored. A forged token cannot verify (no secret to guess, no oracle),
revoke is a row flag, and there is no signing secret to configure or leak. (Alternative, EMB-3: HMAC with a `jti` plus the record.)

## 3. The principal

`EmbedGrant` — a record in `inspecto-auth-spi`, **not** a `Subject` and not a subtype of one:

```
EmbedGrant(grantId, space, investigationId, issuedBy, issuedAt, expiresAt, label)
```

* Set on the exchange as a new `RequestAttrs.ATTR_EMBED_GRANT` only by the public embed route after a successful lookup. **No
  `Subject` is ever attached**, so `ApiContext.withCapability(...)` — which needs a Subject — fails closed for the holder on every
  existing route. The embed is therefore reachable ONLY at its own `/public/investigations/...` routes; it cannot "escape" into
  `/inv/...`, `/bi/...`, or any other. This is the main safety property, and it is structural, not a list of denials.
* It carries **no capabilities and no data scopes**; it never reaches the PDP (`AccessDecider`) because no Subject exists to decide
  for. An exemption in `ComponentAccess` / R3 is therefore **not** needed — the exemption is "no Subject, no gated route".
* Audit identity is the pair `(grantId, issuedBy)`: the actor recorded for a view is `embed:<grantId>`; the issuer is a field.

## 4. Scope

* One Investigation, one Space, bound at mint: `(space, investigationId)`. A lookup whose Investigation was deleted is the uniform 404.
* **Read-only, by route set** (section 9): `GET` only; no pipeline/BI/Dataset/query access.
* What the view returns: header (title masked, `purpose`), Working Set (nodes/edges), log summary and annotations, References (as
  stored, `trusted:false`). **Not** returned: Dataset names/ids beyond what the graph needs, raw SQL, other Investigations, the Case,
  drafts, pending (four-eyes) expands, reveal. Evaluate once per request via the existing evaluator; no new compute path.
* The grant adds NO wider read: it re-evaluates the Investigation's log, and never accepts a caller seed, filter or Dataset.

## 5. Expiry

`expiresAt` is mandatory. Default 72 h, maximum 30 days (shorter than BI's 7 d / 365 d: Investigation evidence is more sensitive than
a dashboard). Checked on every request against the server clock; an expired token is the uniform 404. Expired rows are swept on the
existing sweep tick (row kept for audit, token hash purged).

## 6. Revocation

* Per grant: `POST /inv/investigations/{id}/embeds/{grantId}/revoke` sets `revokedAt` (immediate; the next request is a 404).
* Bulk: revoke-all-for-Investigation, and automatic revoke when the Investigation is deleted, closed (EMB-9) or its masking is loosened.
* Global kill switch: `-Dla.embed.enabled=false` (default **off**, fail-closed like BI's secret) — with it off, mint is `503` and every
  public route is `404`.

## 7. Masking and data protection

* The view is **always masked**: at least as strict as the Space `maskingMode`, and masked even if the Space is `off` (EMB-6).
  `reveal` does not exist on the embed. Titles are masked as the Investigation list masks them.
* No download/export route; the Dossier bundle stays the file path and keeps its own capability.
* Honest limit: a holder can screenshot. The control is expiry + revocation + audit, not DRM. The mint dialog says so.
* A leaked URL yields masked evidence for ONE Investigation until expiry/revocation — the residual risk the operator accepts (EMB-5).

## 8. Storage

`embeds.jsonl` beside the Investigation's other sidecars (append-only; state = fold of `mint` / `revoke` events plus a view counter),
keyed by token hash. Because `investigation-store-design.md` (`LA-INVESTIGATION-STORE-DESIGN-1`) is moving those sidecars behind an
`InvestigationStore` seam, the grant store goes **behind that seam from day one** (file implementation now, Postgres after) — no second
storage mechanism. The token plaintext is shown once at mint and never stored or logged. Because the token is in the URL path,
access-log redaction of `/public/investigations/*` is part of the build (EMB-12).

## 9. Minimal route set (all new)

| Route | Who | Purpose |
|---|---|---|
| `POST /inv/investigations/{id}/embeds` | new capability `canShareInvestigation` | mint; body `{ttl_hours, label, frame_ancestors}`; returns token + URL once |
| `GET /inv/investigations/{id}/embeds` | read-shaped (same readers as the Investigation) | list grants (no secrets): id, label, issuer, expiry, revoked, last view, view count |
| `POST /inv/investigations/{id}/embeds/{grantId}/revoke` | `canShareInvestigation` | revoke |
| `GET /public/investigations/{token}` | the token | masked view JSON (header, Working Set, annotations, references) |
| SPA route `/embed/inv/:token` | the token | read-only shell (EMB-8); a UI task, not a backend route |

Gate consequences (the known four-gate trap for a new route): `CapabilityManifest` entries (mint and revoke gated, list read-shaped,
the public GET a `self-verifying-public` exemption with a LITERAL path), an `openapi-v1.json` entry per route, the
`isSelfVerifyingPublic` prefix `/public/investigations/`, the per-IP rate-limit bucket (as `/public/delivery-status/`), and
`AbsentGeoLinkRoutes` for hosts without LA. There is no `POST` on the public surface at all.

## 10. Embedding headers, CSRF and cookies

* **No cookies, no ambient credential.** The token is in the path; public routes set no cookie and read none. Classic CSRF does not
  apply (nothing auto-sent, nothing mutated, GET only) and third-party-cookie blocking is irrelevant.
* **Framing.** The public view response carries `Content-Security-Policy: frame-ancestors <allowlist>`, the **per-grant** allowlist
  entered at mint (validated as `https://origin` only, at most 5; empty = `'none'`, link-only, EMB-4). Every OTHER response should carry
  `frame-ancestors 'none'` / `X-Frame-Options: DENY`; a grep of `inspecto*/src/main` for those headers finds none today, so adding
  them is part of this work and a host-wide behaviour change (EMB-13).
* Also on public responses: `Referrer-Policy: no-referrer` (the token is in the URL), `Cache-Control: no-store`,
  `X-Content-Type-Options: nosniff`, and **no** `Access-Control-Allow-Origin` (the shell fetches same-origin).
* The shell page's CSP: `default-src 'self'`, no inline script, no third-party loads.

## 11. Audit trail, capability and PDP interaction

* `AuditTrail` events: `embed.mint` (grantId, issuer, Investigation, expiry, allowlist), `embed.revoke`, `embed.view` (grantId, source IP,
  user-agent hash, outcome) and `embed.denied` for failed lookups **without** logging the presented token (log a hash prefix). Views
  are also rolled up per grant (`lastViewAt`, `viewCount`) so the log is not flooded.
* The PDP is not consulted for the holder (no Subject). It IS consulted for mint/revoke like any gated mutation, with a new ABAC
  action `embed` so a policy can forbid sharing a classified Investigation (EMB-10); strictest-wins classification applies: an
  Investigation above the embed's maximum classification cannot be minted (`422`).
* Capability: **one new verb** `canShareInvestigation` (default: Case owner's role and Super), added to `Roles` and the controls matrix.
  Four-eyes on mint (EMB-11) would reuse the existing approval queue; recommended off for v1.

## 12. Operator decisions (numbered; recommended answer given)

| # | Decision | Recommended |
|---|---|---|
| **EMB-1** | Build now, or keep deferred until a named consumer asks (the 2026-10-03 call)? | **Keep deferred.** Build on a named consumer; this doc is the ready spec. |
| **EMB-2** | Reuse the `ShareTokens` class, or a new grant store? | **New store**, reuse only the pattern (section 2). |
| **EMB-3** | Token format: opaque random + hashed record, or HMAC with `jti` + record? | **Opaque random 256-bit**, SHA-256 stored. |
| **EMB-4** | Iframe embedding allowed? | **Yes, opt-in per grant** with an `https://` origin allowlist; default `'none'`. |
| **EMB-5** | Accept that a leaked URL yields MASKED evidence until expiry/revocation? | **Yes**, TTL default 72 h, max 30 d, stated in the mint dialog. |
| **EMB-6** | Masking: follow the Space `maskingMode`, or force masked? | **Force masked**, no reveal, even if the Space is `off`. |
| **EMB-7** | New principal type `EmbedGrant` (not a `Subject`)? | **Yes** — structurally unable to satisfy any capability gate. |
| **EMB-8** | View delivery: JSON only, or JSON plus a read-only SPA shell in `projects/link-analysis`? | **JSON + shell**, shell as a separate UI task after the API. |
| **EMB-9** | Auto-revoke on Investigation close / delete / masking loosened? | **Yes**, all three. |
| **EMB-10** | Add ABAC action `embed` and a max-classification refusal? | **Yes.** |
| **EMB-11** | Mint needs four-eyes approval? | **No for v1**; revisit if a regulator asks. |
| **EMB-12** | Redact `/public/investigations/*` tokens from access logs and telemetry in the build? | **Yes — required.** |
| **EMB-13** | Add `frame-ancestors 'none'` / `X-Frame-Options: DENY` to every non-embed response? | **Yes**; host-wide header change needing its own test. |
| **EMB-14** | Code split: `inspecto-auth-spi` (principal, attr), `inspecto-la-api` (routes, store), core `ControlApi` (prefix exemption, headers)? | **Yes**; make the public-prefix list a registered extension point rather than a third hard-coded `startsWith`. |

## 13. Test plan (real HTTP, armed Authenticator; mutation-check the negatives)

1. Mint needs `canShareInvestigation` (a read-only Case member gets `403`); kill switch off gives `503`; TTL clamp; over-classification `422`.
2. The token never appears in the list response, a log line or an audit event (scan the captured output).
3. Public GET: a valid token returns the masked view; **unknown, tampered, expired, revoked, deleted-Investigation and disabled all
   return the same `404`** (assert identical status AND body).
4. **No escape:** the token presented to `/inv/...`, `/bi/query` or `/public/dashboards/...` is refused; every non-GET on the public
   path is `405`; the response has no unmasked id, no `reveal`, no pending expand.
5. Headers: `frame-ancestors` equals the grant's allowlist (and `'none'` when empty); `Referrer-Policy`, `no-store`, no CORS, no `Set-Cookie`.
6. Revoke is immediate; revoke-all; delete/close auto-revokes; the sweep purges the hash and keeps the audit row.
7. Audit: mint/revoke/view/denied events carry grantId and issuer; the rollup counters move.
8. Gates: `CapabilityManifestTest`, openapi contract, `AbsentGeoLinkRoutes`, route-gating `--check`, authgate coverage (BARE route
   literals), vocabulary guard.
9. One-line mutations for the operator to run by hand (the classifier refuses an agent editing a guard): drop the expiry check; drop the
   revoked check; attach a Subject on the public path; unmask the view; let a non-GET reach the handler; log the token.

## 14. Out of scope

Trust between installations (I1), write access of any kind, per-viewer identity (that is a normal login), export from the embed,
embedding the editor, and several Investigations per grant (mint several).

## 15. References

* `inspecto/src/main/java/com/gamma/control/ShareTokens.java`, `ShareRoutes.java`; `ControlApi.isSelfVerifyingPublic`
* `inspecto-auth-spi/src/main/java/com/gamma/control/Subject.java`, `CapabilityManifest.java`, `AuditTrail.java`
* `inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java`
* [`archived-documents/plans-archive/la-separation-d6-design.md`](../archived-documents/plans-archive/la-separation-d6-design.md) section 5
* [`la-separation-feasibility-plan.md`](la-separation-feasibility-plan.md) · [`investigation-store-design.md`](investigation-store-design.md)
* [`okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md) section *External references and the Dossier bundle*
