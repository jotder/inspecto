---
type: Reference
title: Platform key decisions
description: The why behind editions as build flavors, auth-free core, edition gating, a lean core and the master-only track.
tags: [decisions, editions, auth, modules]
timestamp: 2026-11-19T00:00:00Z
---

# Platform key decisions

Moved verbatim from the retired `PROJECT_NOTES.md` §3 (D-IR5, 2026-11-19). The multi-space decision is in [spec-multi-space.md](../product/spec-multi-space.md).

- **Editions are build flavors, never git branches.** One source tree (`master` = auth-free common core);
  edition-only code in its own Maven module (`inspecto-oidc` for Professional/Enterprise), assembled via
  `-Pedition-*` profiles + `ServiceLoader` + `-D` flags. A fix lands once in core; all editions inherit it
  at build. Rationale: branches would force perpetual cross-line cherry-picking. → [`spec-editions.md`](spec-editions.md).
- **All auth removed from `master`/common core (2026-06-16).** Personal is genuinely auth-free (every
  ControlApi route open; SPA boots to `/dashboard`; no token paste/guards). Professional re-adds auth out-of-band
  via the **`inspecto-oidc` module (BUILT, W6 2026-07-06** — `OidcAuthenticator` Nimbus+JWKS, `RoleMapper`,
  `OidcTokenRelay` (renamed from `KeycloakTokenRelay` 2026-07-25, D15); reactor-gated behind the `edition-standard` profile) behind the
  `Authenticator`/`Subject`/`TokenRelay` SPIs (`com.gamma.control`), plus HTTPS (`HttpsServer`) and the BFF
  `/auth/exchange|refresh|logout` routes; Angular uses OIDC Auth-Code+PKCE driven by `bootstrap.features.authMode`
  (no-op on Personal). **The `-Dassist.write.root` 503 write-gate is SEPARATE from auth and stays.**
- **Edition gating is real as of 2026-09-07, not just a matrix claim (EDG-01).** Five "not for Personal"
  features left the core into optional modules — `inspecto-notify-channels` (CP-15), `inspecto-backup`
  (OPS-06), `inspecto-geo-link` (CP-09), `inspecto-exchange` (SEC-10) and `inspecto-observability` (CP-13's
  exposition) — joining `inspecto-oidc` and `inspecto-policy`. Personal answers their surfaces **503
  naming the module**, never 404. 🔴 The load-bearing one was `/metrics`: a `PUBLIC_PATH` on an edition
  that ships no authenticator and binds every interface. Recipe + traps:
  [editions model](architecture-editions.md). ✅ **EDG-01 COMPLETE 2026-09-08** — all six
  cells are now true of the build; the plan is archived. ⚠ The two hardest cells each had to change what
  the product PROMISES, not just where code lives: cell 6 kept a narrow core `/audit/*` read because
  gating `/events*` would have removed Personal's Audit-log screen (§Audit promises it), and cell 7
  amended `OPS-01` and `SP-CTL-02` because they promised Personal an `objects` store and a gap watchdog
  that raises ALERT objects. **Read what the neighbouring matrix rows promise before scoping a gating
  cell** — neither collision appeared in the census.
- **Keep the core lean.** All network deps live in `inspecto-connectors`; hosted-AI SDKs in
  `inspecto-agent-hosted` (physically absent from air-gapped builds). The zero-new-dep rule was retired
  2026-06-13 (logback replaced slf4j-simple, user-approved) — still no gratuitous deps.
- **Pipeline-graph track is `master`-only** (`feat:` → master; empty merge-forward set; retired lines untouched).
