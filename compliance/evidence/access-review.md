# Access-review runbook — answering the effective-grants question (G8, CC6)

**What this is:** how an auditor (or a periodic internal review) answers *"who can do what, and
which policy grants it"* from the shipped surfaces. Grounded 2026-08-28 against the R5 Settings ▸
Access view and the `/access/*` routes.

## 1. The three-layer answer, and where each layer lives

| Layer | Question it answers | Surface | Extractable artifact |
|---|---|---|---|
| **Subject → role** | which people/service identities hold which role | **The IdP, not the product** — role assignment is deliberately IdP-owned (same boundary as account lifecycle, CC6) | the IdP's own group/role-membership export |
| **Role → effective capabilities** | what each role can effectively do — its granted capabilities with any denial its Access Profile imposes overlaid | Settings ▸ Access ▸ **Roles** tab (`AccessRolesComponent` — a denied capability renders struck through, attributed to the profile) | `GET /access/roles` + `GET /access/profiles` (JSON) |
| **Policy layer** | which Access Policies exist (authored + engine seed, tagged by source), and why a given request would be denied | Settings ▸ Access ▸ **Policies** tab, incl. the `GET /access/explain` dry-run for one hypothetical request | `GET /access/policies` (JSON) |

The **Lenses** tab is not part of the access review: Lenses are UI visibility, not security
(its CSV export is a menu-visibility matrix, not a grants report).

## 2. The extraction (one review = four artifacts)

Against the deployment under review (space-scoped where multi-space):

```bash
curl -s http://<host>:<port>/api/access/roles     > access-roles.json
curl -s http://<host>:<port>/api/access/profiles  > access-profiles.json
curl -s http://<host>:<port>/api/access/policies  > access-policies.json
# 4th artifact: the IdP's role-membership export, from the IdP's own admin tooling
```

The effective-grants report per role = `access-roles.json` grants minus the capabilities its
profile (in `access-profiles.json`) denies — exactly the overlay the Roles tab renders. Joining the
IdP membership export onto it yields the per-subject view.

## 3. Known limits (stated, not hidden)

- **No per-subject view in the product UI** — by design: role assignment lives in the IdP, so a
  subject-level report is always a join with IdP evidence (§2). Do not present the Roles tab alone
  as "who can do what".
- **Roles/Policies tabs have no CSV export** (only the Lenses visibility matrix exports) — the JSON
  routes in §2 are the artifact path.
- **The Roles→Policies link is an attribution flag, not a citation** — a denied capability names
  its profile, not the exact policy row; `GET /access/explain` answers per-request "why", one
  hypothetical at a time.

These limits are recorded here rather than filed as product gaps: the review is fully answerable
with §2's artifacts. File UI work only if a review in practice finds the JSON path insufficient.
