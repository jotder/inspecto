# Runbook — rotating the SEC-INCIDENT-1 OAuth client secrets

> **Status (re-verified 2026-08-26):** rotation **STILL NOT PERFORMED**, and it is now **gated on
> nothing**. The code-side blocker cleared long ago — the PKCE work (`579632ba`, `932eff92`, plus the
> mandatory follow-up `ce49a681`) is **all on `master`**, verified by `git merge-base --is-ancestor`, so
> no supported line sends a client secret. ⚠ **The bundle you deploy must include `ce49a681`** —
> `932eff92` alone breaks login depending on the IdP's callback parameter order.
>
> ⚠ **`4.x` NO LONGER EXISTS** (deleted 2026-08-17, operator call). Every instruction below that named it
> has been rewritten; **do not try to `git log 4.x`** — the PKCE commits are on `master` and that is the
> line you cut a build from. Nothing was lost: 4.x carried nothing master lacks.
>
> 🔴 **THE EXPOSURE IS ONGOING, NOT HISTORICAL.** The 2026-07-26 history rewrite did **not** end it:
> all five PRs merged *after* the leak commit, so every `refs/pull/N/head` still pins the old lineage and
> still serves the secret literals — verified against the live API after the force-push. **A repo owner
> cannot delete or rewrite `refs/pull/*`; only GitHub Support can purge them** (Step 0-B below).
> Treat these credentials as live and public until rotation completes.
>
> This runbook is the execution checklist;
> [`../BACKLOG.md`](../BACKLOG.md) §5 is the incident record and closes only on *confirmed* rotation.
> **Operator-executed.** Every step below happens in the IdP console and in deployment infrastructure —
> none of it is an agent action, and no secret value belongs in this repo, a commit, or a chat transcript.

## Why this is not a simple "change the secret" job

Five OAuth client secrets were public on GitHub for ~6 weeks (2026-06-12 → 2026-07-25). Removing them from
`master` in `94d98593` **remediated nothing** — the values persist in git history, every clone, every fork,
and GitHub's caches. Rotation at the issuer is the only fix.

The complication **as it stood before P1**: `4.x` authenticated with these exact values, so a naive
rotation was a customer-visible outage at `app1.pronto.lebara.sa`, not a quiet swap.

**As of `932eff92` that is fixed in source** — `4.x` uses PKCE and holds no `appClientSecret`. But the
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

## Step 0 — ~~pull the auth logs BEFORE rotating~~ MOOT: the evidence is gone

🔴 **This step is no longer executable and is NOT a gate. Do not wait on it.** The issuer's auth logs
were deleted (operator, 2026-07-26) before they were exported, so the question this step existed to
answer — *was the exposure exercised?* — **can never be answered**.

What that changes, concretely:

- **"Assume compromised" is the only defensible reading**, and it is the assumption the rest of this
  runbook now runs on. There is no clean-log outcome available to report.
- **Rotation is gated on nothing.** Previously this step blocked it; it does not.
- A customer or auditor asking "was it used?" gets *"the logs were not retained"* — a materially worse
  answer than a clean log, and the reason the export-before-rotating instruction existed. Preserve this
  section rather than deleting it: the next incident's Step 0 should be run, not skipped.

## Step 0-B — ask GitHub Support to purge `refs/pull/*` (do this NOW, in parallel)

**This is the only remaining action that can reduce the exposure itself**, and it is independent of
rotation — start it immediately rather than after. The force-push cleaned branches and tags; it could not
touch pull-request refs, which a repo owner has no ability to delete. Until Support purges them,
`git fetch origin refs/pull/1/head` still hands anyone the secrets.

A ready-to-send draft is in [`github-support-purge-request.md`](github-support-purge-request.md) —
**review it, then send it from the account that owns `jotder/inspecto`.** Record the ticket number in the
BACKLOG §5 row so the next shift can chase it rather than re-derive it. Do not paste any secret value
into the ticket: reference the *files and refs*, never the literals.

## Step 1 — choose the rotation mode

Ask the IdP owner **one question**: *can a client hold two valid secrets at once (or can we stand up a
parallel client)?*

- **Yes → overlap rotation (preferred, zero downtime).** Add the new credential, deploy bundles that use
  it, verify, then revoke the old one. The exposed value dies with no service gap, and prod does not need
  to wait on the `4.x` rebuild.
- **No → cutover rotation.** Old and new cannot coexist, so every environment needs a scheduled window,
  and **prod must wait for a bundle built from `master`** (which carries P0+P1 and `ce49a681`) to be
  deployed, then rotate.

The rest of this runbook branches on that answer. Do not start without it.

## Step 2 — order of operations

Two orderings are in tension and the right choice depends on Step 1:

- **By risk:** prod first — it is the named-customer credential and the highest-value target.
- **By availability:** prod last — it is the only environment where a mistake is customer-visible.

**Overlap mode → rotate prod first** (risk order wins; there is no outage to trade against).
**Cutover mode → rehearse on gammadev → gamma → dev/offline, and schedule prod last**, after a bundle
that no longer needs the secret is deployed there. Rotating prod first in cutover mode means an outage of
unknown length on a customer system — do not do it. ⚠ The rebuild is no longer "unwritten" as this step
once warned: the fix is on `master`, so the only open variable is **what is actually deployed at
`app1.pronto.lebara.sa`** — confirm that before choosing an ordering, rather than inferring it from the
source line.

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
- [ ] GitHub Support purge of `refs/pull/*` requested (Step 0-B), ticket number recorded in
      [`../BACKLOG.md`](../BACKLOG.md) §5 — and **confirmed complete**, which is a separate event from
      requesting it.
- [x] ~~Step 0 log review recorded, including a negative result~~ — **NOT ACHIEVABLE**: the issuer logs
      were deleted 2026-07-26 before export. Record *that* in §5 instead; "assume compromised" is the
      standing reading and no clean-log outcome is available.
- [ ] Pre-rewrite backup bundle deleted —
      `C:/sandbox/cgi_decoder/ucc-prerewrite-backup-20260726-203545.bundle` (16 MiB) still holds all five
      values in cleartext. Deliberately retained while the incident is open, as the only pre-rewrite
      recovery point; **delete it at close.**
- [ ] BACKLOG §5 row closed — it closes on confirmed rotation, not on a merged commit.
- [x] ~~`tools/check-secrets.mjs` merged forward to `4.x`~~ — **done 2026-07-25** (`f1fb6f20`). `4.x`
      stopped holding live values in `932eff92` (P1), so the guard runs green there; verified it also
      goes red on an injected secret, i.e. it is guarding, not just passing.
- [x] ~~Orphaned worktrees deleted~~ — **done 2026-07-25**; both dirs are gone from disk and from
      `git worktree list`.

## Standing constraints

- ~~**No `git-filter-repo` history rewrite** (operator call, 2026-07-25)~~ — ⚠ **REVERSED AND ALREADY
  EXECUTED 2026-07-26.** The operator ordered the rewrite; it ran (73 occurrences replaced, 0 secrets in
  22 598 objects, all 1008 commits preserved, `HEAD` tree byte-identical) and `master`/`4.x`/two tags were
  force-pushed. **It bought local and branch hygiene, not remediation** — exactly as the original
  constraint predicted about forks and caches, and worse: `refs/pull/*` still serves the literals
  (Step 0-B). Its one lasting cost was ~24 invalidated commit hashes across `docs/`, repaired 2026-08-26.
  **Do not run another rewrite** — there is nothing left for one to clean, and rotation is still the fix.
- **Do not re-issue a secret that still ships inside a browser bundle.** A public SPA cannot keep a
  confidential-client secret; handing `4.x` fresh values just reproduces this incident with new numbers.
  That is what the PKCE plan exists to prevent.
- Retired lines `1.x`–`3.x` very likely carry the values too. Policy forbids committing there; rotation
  covers them.
- Lower severity, same files, unaddressed: internal hostnames/IPs are published in-repo
  (`68.183.16.242`, `p20.prod.pronto`, `app1.pronto.lebara.sa`).
