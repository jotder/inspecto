# Approval SPI extraction — plan (2026-10-06)

> ⛔ **SUPERSEDED + ARCHIVED 2026-10-06 (operator), nothing built.** Absorbed by
> [`module-architecture-reorg-plan.md`](../../superpower/module-architecture-reorg-plan.md) P1: the governable
> config kinds become a contribution point (this plan's D-AS3), and the hold contract moves with the contract
> modules (D-MR1). D-AS1…D-AS4 were never answered and are void.

> 🟡 **PLAN — nothing built.** Decisions D-AS1…D-AS4 below are OPEN for the operator.
> Row to file on approval: `APPROVAL-SPI-1` (not yet in `BACKLOG.md`).

## 1. Goal

Maker-checker (the Pending Change hold, `ASSURE-MAKER-CHECKER-1`) is **opt-in per Space** today and must stay
so. The goal is NOT to force approval on anything. It is that **any module can offer it if it wants to** —
today only modules that depend on `processor` (directory `inspecto/`) can call `PendingChanges.hold`, so
`la-api` and `la-core` cannot, and a future separate application could not either.

⛔ Out of scope: Action Requests (`ActionRequests*` — a separate, always-on approval for outbound calls),
approval of non-HTTP changes, and exposing approval as a Platform Service to Jobs and packs.

## 2. Starting point (verified in the tree 2026-10-06)

- `PendingChanges.hold(ApiContext, HttpExchange, kind, name, proposed, current)` already takes only types that
  live outside `processor`: `ApiContext` is in `inspecto-http-spi`, `HttpExchange` is the JDK's. **The
  signature is SPI-ready; the body is not.**
- The body reaches into `processor`-internal code: `ApprovalPolicy.forRoot` (package-private record),
  `JobWriteGuard` (the mandatory approval for a report Job that attaches data), `PublicationApproval`
  fingerprints through `HostContext`, `ControlApi.routePath`, and `EventLog` for the audit entry.
- `Held` (the exception that unwinds the request into a `202`) is caught in exactly two places:
  `ControlApi.java:1016` and `PipelineSettingsRoutes.java:262`.
- Approving **replays the original HTTP request** through the same route (`ApiContext.replay`, stamped with
  `ApiContext.ATTR_APPROVED_CHANGE`), and the replay re-enters `hold`, which lets it through only if it is the
  same write. So the mechanism is for **routes** — a module is eligible only if it authors changes through a
  `ControlApi` route.
- Call sites: 22 files, 42 calls of `PendingChanges.hold|holdRefusing|holdRefusingPaths|governs` (main and test
  together). Callers outside `processor`: `entity-list` (`EntityListRoutes`), `geo-link`
  (`InvestigationMeasureRoutes`), `intelligence` (`Investigator`).
- The set of kinds a policy may name is a **fixed allow-list** (`ApprovalPolicy.GOVERNABLE`), so a module's own
  kind cannot be governed without touching `processor`.
- Every `ApiContext` is a `HostContext` (the only implementer found is that interface, in `processor`).
- Module registration touchpoints (from grepping `inspecto-audit-spi`): root `pom.xml` (`<modules>` and
  `dependencyManagement`), the module's own `pom.xml`, `tools/check-module-deps.mjs` (a per-module allow-list of
  internal dependencies), `.gitignore` (`/<module>/target/`), and the OKF pages `architecture.md` and
  `build-test.md`.

## 3. Design

The `processor` keeps everything stateful: the store (`<write-root>/pending-changes/`), `ApprovalPolicy`,
`ApproverRoster`, `PendingChangeRoutes`, the mandatory attachment and publication approvals, expiry and the
approver check. What moves out is only **the contract a route calls**:

| Moves to the SPI | Stays in `processor` |
|---|---|
| `Held` (the unwinding exception) | `PendingChanges` store and replay verification |
| `ApprovalGate`: `hold`, `governs`, `holdRefusing`, `holdRefusingPaths` | `ApprovalPolicy`, `ApproverRoster`, `JobWriteGuard`, publication fingerprints |
| A way to register a governable kind (D-AS3) | `PendingChangeRoutes`, the `/settings/approval` route |

`PendingChanges` becomes the implementation of `ApprovalGate`. ⛔ No compatibility shims: breaking changes are
free here, so every call site is changed to the new entry point in the same slice.

## 4. Open decisions

- **D-AS1 — where the contract lives.** (a) A new module `inspecto-approval-spi` next to `audit-spi`,
  `auth-spi` and `http-spi`, reached through a `ServiceLoader` facade. (b) Put `ApprovalGate` and `Held` in the
  **existing** `inspecto-http-spi` and give `ApiContext` a method returning the gate — no new module.
  ⚠ A separate module plus a method on `ApiContext` is a **dependency cycle** (the SPI needs `ApiContext`, and
  `ApiContext` would need the SPI), so (a) forces the `ServiceLoader` facade. **Recommend (b):** `http-spi`
  already holds `ApiContext.replay` and `ATTR_APPROVED_CHANGE`, the half of the approval seam that exists, and
  (b) needs no new registration touchpoints and cannot be absent at runtime. Take (a) only if the contract
  must be versioned apart from `http-spi`.
- **D-AS2 — behaviour when no gate is present.** Absent must **not** read as "off": with (a), a Space whose
  `approval.toon` names a kind but whose build lacks the implementation would write unapproved. Recommend the
  default gate **refuses writes with 503 when `approval.toon` exists and names any kind**, and passes through
  otherwise. (Irrelevant under (b), where `ControlApi` always supplies the gate — state that in the code.)
- **D-AS3 — governable kinds for other modules.** Recommend a registration call
  (`ApprovalGate.registerKind(String kind)`, called at module load) in place of the fixed allow-list, with the
  funnel test (S2) taught to read it. Without it a module can call `hold` but no policy can name its kind.
- **D-AS4 — first adopter outside `processor`.** Needed to prove the extraction: one mutating `la-api` route
  that authors config-shaped content. Pick one, or accept `entity-list` as the existing proof and skip S3.

## 5. Slices

Verification per slice is the affected test classes only (`-pl <module> -Dtest=A,B`, commas, never `+`); the
full reactor runs once, at handoff or before the push.

1. **S0 — baseline.** Run `ControlApiPendingChangesTest`, `PendingChangesKeyTest`,
   `PendingChangesLockTimeoutTest`, `PendingChangesMultiPodTest`, `ControlApiActionRequestsTest` and
   `ConfigWriteFunnelTest` on a clean tree and record the counts. → verify: all green before any edit.
2. **S1 — move `Held` and the contract.** Add `ApprovalGate` and `Held` to the home chosen in D-AS1; make
   `PendingChanges` implement it; change the two `catch` sites. → verify: `-pl inspecto-http-spi,inspecto
   -Dtest=ControlApiPendingChangesTest,PendingChangesKeyTest`.
3. **S2 — switch the 42 call sites, then repair the guard.** 🔴 `ConfigWriteFunnelTest` finds holds with the
   regex `PendingChanges\.hold\w*\(` (lines 61 and 142). A rename makes it **match nothing and pass** —
   a vacuous green. Update both patterns in the same commit and **prove it can fail**: temporarily remove one
   real hold, expect red naming that route, restore it. (The auto-mode classifier refuses an agent editing a
   guard to prove a test red — list the mutation for the operator to run by hand.) → verify: the funnel test
   reports the SAME number of held routes as in S0.
4. **S3 — one adopter outside `processor`** (D-AS4), with a real-HTTP test covering: policy off writes; policy
   on returns `202` and writes nothing; a different approver applies it; the author approving is `403`. →
   verify: that module's test class plus `ControlApiPendingChangesTest`.
5. **S4 — module and guard registration** (only under D-AS1 (a)): root `pom.xml`, module `pom.xml`,
   `tools/check-module-deps.mjs`, `.gitignore`. A new mutating route also passes the route-gating, authgate
   coverage and `openapi-v1.json` checks (three of them fire only in the full reactor). → verify: run every
   `ci.yml` no-build guard before pushing.
6. **S5 — docs, same change as the code.** The OKF concept `backend/config/config-safety.md` (the hold
   section) and `control-plane/action-requests.md` (the cross-reference), `okf/backend/architecture.md`, the
   `docs/INDEX.md` row (this plan leaves `superpower/` when shipped), a `BACKLOG.md` row for anything left
   open, and `docs/GLOSSARY.md` only if D-AS1 introduces a new canonical term.

## 6. Risks

- **A vacuous guard** (S2) — the largest, because it fails toward green.
- **Fail-open when absent** (D-AS2).
- **Replay needs the route loaded.** An approval replays through the same route, so a module that authored a
  change must still be present when the approver decides; an approval of a change from an unloaded module has
  nowhere to go. State this as a precondition in the SPI's contract text.
- **Personal edition** has no authenticator, so a policy requiring approval is refused at save there; adopters
  must not assume approval is available.
- **Mixed-state tree.** Do the work in a clean worktree under `.claude/worktrees/`, not the shared checkout;
  a peer's uncommitted edits to `ControlApi.java` would be swept into the same commit.

## 7. Not verified

Whether `ControlApi` is the only `HostContext` implementer in practice; the exact contents of
`JobWriteGuard` and `PublicationApproval` beyond how `hold` calls them; whether any `la-*` route already writes
config-shaped content suitable for D-AS4; the edition bundles' module lists (`tools/bundle-modules.mjs`).
