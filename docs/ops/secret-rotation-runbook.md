# Runbook — rotating the SEC-INCIDENT-1 OAuth client secrets

> **Status:** rotation **NOT YET PERFORMED** as of 2026-07-25 — but **the code-side blocker is now
> cleared**: `4.x` P0+P1 shipped (`481a68d5`, `89cb3cce`, **plus the mandatory follow-up `8c3a7654`**), so
> `4.x` no longer sends a client secret and a rotation no longer breaks a running SPA *once the new bundle
> is deployed*. ⚠ **The bundle you deploy must include `8c3a7654`** — `89cb3cce` on its own breaks login
> depending on the IdP's callback parameter order. Check `git log --oneline 4.x` before cutting the build. Read §"The complication"
> below with that in mind — the deploy-then-rotate ordering still applies, the design blocker does not.
> This runbook is the execution checklist;
> [`../BACKLOG.md`](../BACKLOG.md) §5 is the incident record and closes only on *confirmed* rotation.
> **Operator-executed.** Every step below happens in the IdP console and in deployment infrastructure —
> none of it is an agent action, and no secret value belongs in this repo, a commit, or a chat transcript.

## Why this is not a simple "change the secret" job

Five OAuth client secrets were public on GitHub for ~6 weeks (2026-06-12 → 2026-07-25). Removing them from
`master` in `8dd072c6` **remediated nothing** — the values persist in git history, every clone, every fork,
and GitHub's caches. Rotation at the issuer is the only fix.

The complication **as it stood before P1**: `4.x` authenticated with these exact values, so a naive
rotation was a customer-visible outage at `app1.pronto.lebara.sa`, not a quiet swap.

**As of `89cb3cce` that is fixed in source** — `4.x` uses PKCE and holds no `appClientSecret`. But the
constraint it created still governs the *ordering*: **whatever is currently deployed at
`app1.pronto.lebara.sa` is still the old bundle** until someone ships the new one. Rotating before that
deploy breaks the running SPA exactly as it always would have. Deploy first, then rotate.
See [`../superpower/4x-public-pkce-plan.md`](../superpower/4x-public-pkce-plan.md).

## The credentials

| Secret | Scope | Client ID |
|---|---|---|
| `appClientSecret` | **prod — named customer** `app1.pronto.lebara.sa` | `8738429453654150144` |
| `appClientSecret` | dev / offline | `8825302933668759552` |
| `appClientSecret` | gamma | `5829657973124606976` |
| `appClientSecret` | gammadev | `2826856297262914560` |
| `iamClientSecret` | IAM server — **one value reused across all environments** | `1070682796450139008` |

Client IDs are not secrets and are retained in-repo deliberately, so the issuer-side entries are
identifiable without consulting the leaked values. **One `iamClientSecret` rotation covers every
environment**, because the same value was reused in all of them.

## Step 0 — pull the auth logs BEFORE rotating

**Do this first. Rotation destroys the evidence.** For each client ID above, export the issuer's
authentication/token-grant logs for **2026-06-12 → 2026-07-25** and look for:

- token requests from IPs outside the known deployment ranges;
- grants at times when no deployment was active;
- unusual user-agents, or `client_credentials` grants where the app only ever uses auth-code.

This is the only opportunity to learn whether the exposure was *exercised*. Record the finding (even
"nothing anomalous") in the BACKLOG §5 row — a clean log is a materially different incident outcome from
an unexamined one, and it is what a customer or auditor will ask for. If anything looks exercised, this
stops being a rotation and becomes an incident response with a disclosure question attached.

## Step 1 — choose the rotation mode

Ask the IdP owner **one question**: *can a client hold two valid secrets at once (or can we stand up a
parallel client)?*

- **Yes → overlap rotation (preferred, zero downtime).** Add the new credential, deploy bundles that use
  it, verify, then revoke the old one. The exposed value dies with no service gap, and prod does not need
  to wait on the `4.x` rebuild.
- **No → cutover rotation.** Old and new cannot coexist, so every environment needs a scheduled window,
  and **prod must wait for the `4.x` fix to ship** (P0+P1 of the PKCE plan, then deploy, then rotate).

The rest of this runbook branches on that answer. Do not start without it.

## Step 2 — order of operations

Two orderings are in tension and the right choice depends on Step 1:

- **By risk:** prod first — it is the named-customer credential and the highest-value target.
- **By availability:** prod last — it is the only environment where a mistake is customer-visible.

**Overlap mode → rotate prod first** (risk order wins; there is no outage to trade against).
**Cutover mode → rehearse on gammadev → gamma → dev/offline, and schedule prod last**, after the `4.x`
bundle that no longer needs the secret is deployed. Rotating prod first in cutover mode means an outage of
unknown length on a customer system while the rebuild is still unwritten — do not do it.

Rotate `appClientSecret` per environment, then the shared `iamClientSecret` last: it spans every
environment, so its blast radius is the widest and it should move only once the per-environment work is
proven.

## Step 3 — per-secret procedure

For each credential, in the order fixed by Step 2:

1. Generate the new secret **in the IdP console**. Never transcribe it into a file, ticket, commit,
   terminal history, or chat.
2. Place it in the deployment's secret store. Inspecto reads it as a `SecretResolver` **reference** —
   `${ENV:AUTH_OIDC_CLIENT_SECRET}` / `${SYS:…}` — never as a literal, so neither the bundle nor the
   process command line ever holds the value (`../api/deployment/README.md`).
3. Deploy / restart the consumer with the reference in place.
4. **Verify a real login end-to-end** before touching the old secret — an auth-code round trip, not just
   a health probe.
5. **Revoke the old secret at the issuer.** Rotation is not complete until the old value is dead; a new
   secret alongside a still-valid leaked one remediates nothing.
6. Confirm the old value now fails: attempt a token request with it and expect a rejection.

## Step 4 — close out

- [ ] All five secrets rotated **and old values revoked**.
- [ ] Step 0 log review recorded in [`../BACKLOG.md`](../BACKLOG.md) §5, including a negative result.
- [ ] BACKLOG §5 row closed — it closes on confirmed rotation, not on a merged commit.
- [ ] `tools/check-secrets.mjs` merged forward to `4.x`. **The gate on this is now satisfied** — `4.x`
      stopped holding live values in `89cb3cce` (P1), so merging it no longer pins `4.x` CI red.
- [x] ~~Orphaned worktrees deleted~~ — **done 2026-07-25**; both dirs are gone from disk and from
      `git worktree list`.

## Standing constraints

- **No `git-filter-repo` history rewrite** (operator call, 2026-07-25): it invalidates every clone and
  breaks the shift-shared sandbox while still not purging forks or GitHub's caches. Rotation is the fix.
- **Do not re-issue a secret that still ships inside a browser bundle.** A public SPA cannot keep a
  confidential-client secret; handing `4.x` fresh values just reproduces this incident with new numbers.
  That is what the PKCE plan exists to prevent.
- Retired lines `1.x`–`3.x` very likely carry the values too. Policy forbids committing there; rotation
  covers them.
- Lower severity, same files, unaddressed: internal hostnames/IPs are published in-repo
  (`68.183.16.242`, `p20.prod.pronto`, `app1.pronto.lebara.sa`).
