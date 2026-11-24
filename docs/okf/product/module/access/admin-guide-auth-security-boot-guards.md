---
type: Concept
title: Auth & Security — Fail-closed boot, Space and config guards
description: Part 2 of 3 of Auth & Security: An undeclared mutating route does not boot either (ROUTE-UNGATED-DEFAULT-1, 2026-09-16); A Space a signed-in person creates is closed (LA-FULL-CYCLE-TEST-1 finding 32, decision D5, 2026-11-15); A Standard/Advanced bundle does not boot without OIDC configuration; A Space's config cannot name another Space's directory (CROSS-SPACE-JAIL-1, 2026-09-24); The …
tags: [auth, security, spi, oidc, keycloak, wso2, bff, write-gate, rbac, abac, policy-engine, edition-standard, edition-advanced]
timestamp: 2026-07-24T00:00:00Z
item: module.access
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-access
---

# Auth & Security — Fail-closed boot, Space and config guards

Part 2 of 3 of [Auth & Security](admin-guide-auth-security.md) · previous: [Advanced ABAC and the 2026-07-25 decisions](admin-guide-auth-security-abac-decisions.md) · next: [Imports, identity files and maker-checker](admin-guide-auth-security-imports-maker-checker.md).

## An undeclared mutating route does not boot either (`ROUTE-UNGATED-DEFAULT-1`, 2026-09-16)

Route gating used to be **fail-OPEN**: `withCapability` was opt-in, so a route that declared nothing was
simply open to every authenticated caller. The audit that opened this found **83 ungated mutating routes**
and — the finding that mattered more — that only 12 could be *expressed* with the capability vocabulary that
existed: there was no name meaning "administrator", so `DELETE /spaces/{id}` could not be gated even in
principle. `canAdminister` closed the vocabulary gap; this closes the mechanism one.

**The rule.** A mutating route (`POST`/`PUT`/`PATCH`/`DELETE`) must declare a posture — a capability, or a
recorded `CapabilityManifest` exemption carrying a **category and a reason**. One that declares neither
**fails `ControlApi`'s construction**, naming the route and both ways out. ⛔ **No warn-only switch, by
decision**: a control with an off switch is not a control. CI catches an undeclared route before it can
refuse a boot in a customer's bundle; the boot check is what makes the guarantee true of the *deployed*
artifact rather than of the repository.

**Reads are open BY POLICY**, and that is a stated position rather than an omission: confidentiality sits at
the Space/ABAC layer, where a caller's data scopes decide what a read can see — ⚠ **which exists on Advanced
only** (`inspecto-policy`, seed `space-isolation`, active only with an IdP `space` claim; `AccessDeciders` reads
absence as ALLOW), so on Standard an authenticated subject reads every hosted Space (stated 2026-09-17). Affirmed as the **compliance**
position 2026-09-16 knowing it becomes an auditor-facing claim (`controls-matrix.md` CC6). ⚠ If reads are
ever gated, that matrix line moves with the code. **Narrowed 2026-11-15:** "every hosted Space" now means every
*open* Space; a Space a signed-in person creates is closed (the next section but one), and the matrix line moved.

**Three enforcement points, answering different questions** — none replaces another:

| Mechanism | Sees | Where |
|---|---|---|
| Boot refusal | what was **deployed**, incl. modules discovered from other jars | `ControlApi.requireDeclaredPosture` |
| CI scan | **any module's source**, before it can refuse anyone's boot | `CapabilityManifestTest` · `tools/route-gating-report.mjs --check` |
| Runtime inventory | what a **specific running server** registered, digested once per boot | `GET /audit/route-inventory` · the `route.inventory.snapshot` audit event |

⚠ The scan and the inventory genuinely disagree in count, and that is correct: the test classpath carries no
optional modules, so only the scan sees all source, and only the server knows what it loaded.

⛔ **Two traps this cost, both cheap to repeat.** (1) `CapabilityManifestTest` matches the capability as a
string literal at the registration site — passing `Roles.CAN_…` compiles and then reports drift. (2) A
capability gate **wraps** the data-scope guard, so adding one to a scoped route turns an out-of-scope request
from **404 into 403**: existence-hiding now answers second. Tests that exercise the scope guard must carry the
new capability, or they silently start asserting the gate instead.

🔴 **A `self-service` exemption must be scoped IN THE STORE, not in the reason text** (SEC review F2,
2026-09-24). `POST /notifications/read-all`, `POST /notifications/{id}/read`, `DELETE /notifications/{id}` and
`PUT /notifications/preferences` were exempt as "the caller's own" feed and preferences — but
`NotificationStore` and `NotificationPreferences` hold **one** state per Space with no recipient, so any
authenticated user could silence a category's email/webhook delivery, or mark read and archive the feed,
for everyone. All four are now `canAdminister` (fail closed; reads stay open), pinned by
`CapabilityManifestTest.sharedNotificationStateWritesStayAdminGated` and exercised WITH a Subject in
`ControlApiNotificationsTest`. ⚠ Consequence: a non-admin's bell could no longer mark read or dismiss.
**Resolved for READ state, 2026-09-25 (operator):** read state is now per reader — `NotificationReadState`,
keyed by `ApiContext.actor` (the Subject id; on Basic the `appUser` fallback) — so `POST
/notifications/read-all`, `POST /notifications/{id}/read` and the new `POST /notifications/{id}/unread` are
`self-service` exemptions again, this time scoped in the store. Delete (archive) stays `canAdminister`: it
still writes the one shared feed. See [events-metrics](../event/spec-events-metrics.md) §Notifications
for the one shared side effect a read keeps (the dedupe acknowledgement).
**Resolved for preferences, 2026-09-25 (operator; ses-sns-adapter-design §7):** `PUT
/notifications/preferences` now writes ONLY the calling Subject's sparse override
(`NotificationPreferenceOverrides`, keyed by the stable Subject id, one per-deployment file) and is a
`self-service` exemption again; the shared deployment-default grid moved to the new **`PUT
/notifications/preferences/default`, `canAdminister`** (literal capability). The pin is now "exactly these
four may be exempt". Basic (no Subject) has no override layer — the PUT keeps writing the one grid.
⛔ **The notification email address is `Subject.email()` — the IdP's `email` claim ONLY when `email_verified`
is `true`** (`OidcAuthenticator.verifiedEmail`). It is never taken from a request body and never
user-editable: an editable address would let any user route notifications, security ones included, to an
arbitrary mailbox. A Subject with no verified claim can turn on in-app delivery only. ⚠ An IdP that does not
put `email_verified` in the ACCESS token therefore yields no personal email at all — fail closed, by design.
Tests: `ControlApiSubjectPreferencesTest` (real HTTP, with Subjects), `OidcAuthenticatorTest.onlyAVerifiedEmailClaimBecomesTheSubjectsEmail`.

🔴 **The client IP is the socket peer unless a trusted proxy vouches otherwise** (SEC review F3,
2026-09-24). `ApiContext.ip` — the audit trail's `ip` and the throttle key for callers with no Subject —
took the first `X-Forwarded-For` entry from ANY caller, so both were caller-chosen: forge the audit IP, or
rotate the header to get a fresh rate-limit bucket per request. It now reads the value `ControlApi`
resolves once per request against `-Dcontrol.trustedProxies` (`TrustedProxies`: default empty ⇒ ignore
XFF; with a list, only from a listed peer, right-most untrusted hop). Operator detail in
[operations reference](../../layer/platform/admin-guide-operations-reference.md).

**The sign-in row names the verified Subject and, with GeoIP, its country** (D12, 2026-09-28). A successful
`POST /auth/exchange` audits `auth.exchange` with the actor the edition's `Authenticator` resolves from the
MINTED access token (`unknown` if it cannot), and — only when an operator-supplied `-Dgeoip.db` resolves the
F3 client IP — `geo_country` plus `geo_db_build`, never a city. Trigger T5 alerts on a new country per subject.
As built: [events & metrics](../event/spec-events-metrics.md) § *Security triggers*.

**A machine call names its client** (`OIDC-CC-MACHINE-ACTOR-1`, 2026-11-01). A client-credentials Subject's id is
`client:<client id>` and `RequestAttrs.actorType` says `service`; `AuditTrail` adds the IdP's `sub` as `idp_subject`.
Which tokens count as a machine's: [oidc admin guide](../oidc/admin-guide-oidc.md) section 5.

**Evidence** is `compliance/evidence/route-gating.md`, whose inventory table is **generated** by
`tools/route-gating-report.mjs` and CI-enforced in `--check` mode — the document cannot say something the
code does not. Plan of record: `archived-documents/plans-archive/route-gating-compliance-plan.md`.

## A Space a signed-in person creates is closed (`LA-FULL-CYCLE-TEST-1` finding 32, decision D5, 2026-11-15)

**What was found.** Until this change every signed-in person could read every hosted Space (the position stated
above under *Reads are open*). Reproduced over real HTTP on `2959afca1`: a Demo Admin created a Space and added a
file; a Demo User listed only in ANOTHER Space's `demo-users.toon` then read the new Space's Dataset list and
definitions, its rows, its arrivals, its settings and its role table (all 200), and the inbox's absolute path on the
server through `GET /pipelines/authored/{id}/inbox`. With a built-in role (`operations`) the same person also read
`GET /datasets/{id}/feed`, which carried the same path.

**The decision (D5): fail closed.** A Space is **closed** when its `space.toon` says `admission: closed`.
`POST /spaces` and `POST /spaces/import` write that, together with `created_by` (the caller's Subject id), whenever
the caller is signed in. A closed Space admits:

1. the person who created it;
2. anyone who holds `canAdminister`, judged by that Space's own role table;
3. anyone who holds a role that Space's own `roles.toon` defines;
4. a machine whose client that Space's `roles.toon` names in `identity.machineClients` (unchanged).

Everyone else is answered **404 `no such space '<id>'`**, word for word what an unknown id gets, on every route
under `/spaces/<id>/`: Datasets, rows, arrivals, the inbox, settings, the role table, writes. The Space is left out
of `GET /spaces` and of the `spaces` list in `/bootstrap`. Each refusal is recorded as an access denial (404) in the
closed Space's own audit log. Admission is not a capability: an admitted person still needs the capability a route
asks for (403 stays 403).

**How an administrator admits others.** Open the closed Space, go to Settings, Access, Roles, and define there the
role those people hold: create it, or edit a built-in role (an edited built-in role is saved into the Space's own
table). Over the API that is `PUT /spaces/<id>/access/roles` (`canConfigureAccess`). A role that only the built-in
table defines admits nobody, whatever it grants. *Revert* on the role takes the admission away on the next request.
So a Space Template that ships a `roles.toon` admits the holders of the roles it defines from the first moment; a
blank Space, or one from a template with no `roles.toon`, admits its creator and the administrators only.

**What stays open.** A Space whose `space.toon` has no `admission` key: every Space that existed before this
change, every Space created where nobody signs in, and a Space folder placed on disk. Any signed-in person reads
such a Space, as before. To close one, add `admission: closed` (and, if you wish, `created_by: <subject id>`) to its
`space.toon` and restart; to open a closed one, remove the key and restart. A `space.toon` that exists but does not
read closes the Space until it is repaired. Renaming a Space keeps both keys. Neither key is ever taken from an
imported bundle, and no config route can write `space.toon` (it sits outside `config/` and is a reserved path).

**No sign-in.** With no Authenticator (Basic) nothing is recorded and nothing is checked: every request passes.

**A request with no `/spaces/<id>` prefix** runs in the fallback Space (`default`, else the smallest id). When that
Space is closed to the caller, only the sign-in paths (`/bootstrap`, `/auth/*`, the probes), the Space list and its
create, rename and delete (`/spaces...`) and the Exchange (`/exchange...`) answer; anything else is 404 `not found`.

**The inbox path.** `GET /datasets/{id}/feed` (and the answers of its `PUT`, `DELETE` and of `PUT
/datasets/{id}/watch`) and `GET /pipelines/authored/{id}/inbox` show `inbox` as the absolute path on the server
only to a holder of `canAdminister`, and where nobody signs in. Anyone else sees it relative to the Space's data
folder (`inbox/<dataset>`); a Pipeline inbox that lies outside that folder is left out for them.

**Limits, stated so nobody over-claims.**

- The 404 keeps a signed-in person's ordinary use from confirming that a closed Space exists. It does not hide a
  Space id from a caller who tries: with no credential the same route answers 401 for a Space that exists and 404
  for one that does not; a path no route answers gives `not found` under an existing Space and `no such space`
  under an unknown one; `/spaces/<id>/bootstrap` gives an anonymous caller that Space's pre-sign-in context
  (edition, features, branding); and un-prefixed `/bootstrap` with a closed fallback Space shows its feature flags
  to a signed-in outsider (settings, not data).
- A Space id also appears outside its own routes: metric labels, the owning Space of an Exchange offer, an App
  upgrade preview.
- Admission is by role, not by person, and the creator match compares the Subject id (`sub`), not the issuer. The
  Spaces screen marks a closed Space with a *Closed* badge (the `GET /spaces` row of a closed Space carries
  `admission` and `createdBy`; the create answer does too).
- Code: `SpaceAdmission`, `ServerPaths`, `ControlApi.spaceRefusal` (`inspecto/src/main/java/com/gamma/control/`);
  the two keys live on `SpaceContext.SpaceManifest`. Tests: `ControlApiSpaceAdmissionTest`,
  `DemoSpaceAdmissionHttpTest`, `ControlApiPipelineInboxTest`.

## A Standard/Advanced bundle does not boot without OIDC configuration

Discovered 2026-09-07 by the packaging boot smoke, and worth stating plainly because it is a deployment
precondition, not a runtime one:

* `ControlApi` calls `Authenticators.active()` **during construction**, and `SpiSlot` runs
  `ServiceLoader` **regardless of `-Dauth.mode`**. So the mere PRESENCE of `inspecto-oidc.jar` on the
  classpath makes `OidcAuthenticator`'s constructor mandatory.
* That constructor requires **`-Dauth.oidc.jwksUri`** and **`-Dauth.oidc.issuer`**, and fails closed with
  a named message (`inspecto-oidc requires -Dauth.oidc.jwksUri (Standard edition …)`) otherwise.

⇒ Dropping the security sidecar into a bundle without also supplying the `AUTH_OIDC_*` environment
variables `serve.sh` reads is not a degraded deployment — it is one that **exits at startup**. The
message names the missing property, so the failure is legible; it is the *timing* that surprises, since
nothing about "auth is optional per edition" suggests the process will not start.

⚠ There is no way to run a Standard/Advanced bundle "with the module present but auth off". If that is
ever wanted, it needs a guard around the SPI resolution, not a config flag.

### 🔴 This guarantee was SILENTLY LOST and restored 2026-09-12

The sentence above used to end *"— `SpiSlot.active()` has no try/catch, which is also why a missing
transitive there is a boot failure (SEC-SIDECAR-BOOT-1)"*. **That was true when written (2026-09-07) and
false by the time anyone relied on it.** `PKG-5` later routed `SpiSlot.active()` through
`com.gamma.spi.OptionalSpi`, whose entire purpose is to catch `ServiceConfigurationError` /
`LinkageError` so an unloadable OPTIONAL module is an *absence* rather than a boot failure — correct for
the assistant sidecar compiled against a newer JDK, and the exact opposite of what this SPI needs.

⛔ **The effect: a Standard deployment with a mistyped `-Dauth.oidc.jwksUri` booted WIDE OPEN.**
`ServiceLoader` wraps the constructor's `IllegalStateException` in a `ServiceConfigurationError`;
`OptionalSpi` caught it, logged *"the product runs without it and its routes answer 503"*, and returned
empty — and an empty `Authenticator` means `ControlApi.dispatch` skips authentication for **every** route.
There are no "its routes" for this SPI. Nothing failed; nothing 503'd; the control plane simply served
unauthenticated, while `ControlApi`'s own constructor comment promised it would *"fail to boot instead of
silently accepting traffic"*.

✅ **Fixed:** `SpiSlot` gained a `failClosed` posture and `Authenticators` is the one slot that uses it
(`new SpiSlot<>(Authenticator.class, true)`). ⚠ The distinction it draws is **registered-but-broken** vs
**never-registered** — it has to be, because Basic ships no registration at all and that absence is
legitimate. `OptionalSpi` itself is unchanged and still fail-soft: the defect was never in that helper,
it was in the Authenticator sharing it. Pinned by `SpiSlotFailClosedTest` and
`AuthenticatorDiscoveryFailClosedTest`, mutation-proven both ways.

⚠ **A second defect surfaced while proving the first.** `SpiSlot.forTest(null)` read
`cached = Optional.ofNullable(t)`, which caches an EMPTY Optional rather than clearing the cache — so a
teardown that documented itself as restoring the classpath scan actually **pinned the slot to "no
provider" for the rest of the JVM**. `SpiSlotTest` asserted that contract and passed anyway, because it
probed an SPI with no registration, where pinned-empty and scanned-empty are the same observation. Fixed,
and the test strengthened to re-arm a slot whose provider genuinely resolves.

✅ **`AccessDeciders` FLIPPED TOO — operator decision 2026-09-13.** It had the same shape, and the cost of
absence turned out to be higher than the first reading suggested. Both PEPs read an absent decider as
**allow** — `ControlApi.authorize` returns early and `RowScope.visible` returns `true` —
and `PolicyEngine`'s seeded policies are `space-isolation` / `space-isolation-rows`. So a
registered-but-unloadable `inspecto-policy` would silently stop enforcing the **multi-tenant Space
boundary** at both route and row level, letting a subject bound to one Space address and read another.

⚠ **Its trigger is genuinely different from the Authenticator's, and the decision was made knowing that.**
`PolicyEngine` takes no configuration, so no typo can break it — only a packaging fault or `LinkageError`,
which is exactly what PKG-5 chose to make non-fatal. ⛔ The judgement: a tenancy boundary is not a feature,
so losing it silently is worse than refusing to boot. (`TokenRelays` needs nothing: absence already yields
a 503 at `AuthRoutes`, which is fail-safe.)

## A Space's config cannot name another Space's directory (`CROSS-SPACE-JAIL-1`, 2026-09-24)

The allowed roots every config-path check uses (`SafetyPolicy.defaultPolicy()` → the 422 write gate and
every run-time `PathJail`) were the **union** of all hosted Space bases, so on a multi-Space host a job in
Space `acme` could name `beta`'s data directory by absolute path and pass. Probed live (HTTP 200) and fixed:
a Space's allowed roots are **its own base plus the operator-declared `-Dassist.safety.roots`**, keyed on the
thread's Space binding. This is the path-level twin of the Advanced `space-isolation` policy above — and
unlike it, it holds in **every** edition. Detail, caveats and pinning tests:
[Config safety](../config/spec-config-safety-roots-save-gate.md#a-spaces-allowed-roots-declared--its-own-base-tier-3-2026-08-14-narrowed-2026-09-24).

## The `sql.template` job runs behind SqlGuard and a sealed connection (`SQL-TEMPLATE-SANDBOX-1`, 2026-09-24)

`SqlTemplateJob` ran `CREATE TABLE … AS <authored sql>` on a plain `jdbc:duckdb:` connection. Probed and
confirmed on six vectors, every one of which succeeded: `read_csv` of a file outside the data root, a
`FROM '<file>'` replacement scan, a `; COPY … TO` writing outside the data root, a `; ATTACH` creating a
database outside it, `; LOAD httpfs`, and `read_csv('http://127.0.0.1:<port>/x')` — the local test server
was **hit twice**. The fix reuses both existing layers, it invents neither:

1. **Lexical — `SqlGuard.check`** on the **substituted** text (a `$param` becomes a string literal, and a
   literal in `FROM` is a file read, so only the final text can be judged). The same allow-list
   `transform.sql` (`RowShaper`) applies.
2. **Connection — `SqlSandbox.disableExtensionAutoload`** before the trusted source views are registered,
   then **`SqlSandbox.sealAllowing(conn, [dataDir])`**: `allowed_directories` = the data root (trailing `/`,
   so `data-other` is not admitted), `enable_external_access=false`, `lock_configuration=true`. The sink,
   Decision Rule route and quarantine writes all land under the data root, so they still work.
   *(Since `ENGINE-INMEMORY-UNSEALED-1`, 2026-10-03: the autoload-off + `allowed_directories` half is done by
   `DuckDbUtil.openInMemory(spill, [dataDir])` itself, and the job adds `DuckDbUtil.lockConfiguration` before the
   authored SQL — see `okf/product/module/engine/spec-duckdb.md`.)*

Both layers are mutation-checked independently in `SqlTemplateJobSandboxTest`: removing the guard turns
`aFileLiteralInsideTheDataRootIsStillRefusedByTheGuard` red (the seal admits that file), and removing the
seal turns `aFileFunctionTheGuardMissesIsStoppedByTheConnectionSeal` red.

🔴 **That second probe is also a finding against `SqlGuard` itself:** `parquet_metadata('<any file>')` passes
the lexical check (it is not `read_*`/`*_scan`, and `FROM parquet_metadata(` is not path-like), and it
returns the file's schema, row counts and min/max statistics. Any caller that relies on `SqlGuard` **alone**
on an unsealed connection — `QueryExecutor` documents exactly that posture — was exposed. Fixed the same day
as `SQLGUARD-PARQUET-METADATA-1`, next section.

## SqlGuard refuses every table function except path-free generators (`SQLGUARD-PARQUET-METADATA-1`, 2026-09-24)

`SqlGuard` had a hand-kept block-list (`read_*`, `*_scan`, `glob`, …) and the file **inspectors** were not on
it. The fix makes the function check **fail-closed over table functions**: every function call in the text
— bare **or double-quoted**, because DuckDB resolves `"PARQUET_SCHEMA"('<file>')` as the function (the old
`\bread_csv\s*\(` regex was bypassed by `"read_csv"(…)` too) — is tested by whole name against one pattern,
and the only table functions allowed are the generators in `SqlGuard.SAFE_TABLE_FUNCTIONS`: `range`,
`generate_series`, `unnest`, `repeat`, `repeat_row`, `json_each`, `json_tree`, `pg_timezone_names`,
`icu_calendar_names`. Refused, beyond the old list: `parquet_metadata`/`_schema`/`_file_metadata`/
`_kv_metadata`/`_full_metadata`/`_bloom_probe`, `duckdb_*` and `pragma_*` (settings, databases and
extensions expose paths), `which_secret`, `enable_logging`/`enable_profiling` (take a storage path),
`force_checkpoint`, `truncate_duckdb_logs`, `json_execute_serialized_sql`, `summary`/`histogram*`, and the
autoloadable extensions' surfaces (ducklake, postgres, sqlite, mysql, odbc, iceberg, delta, azure, aws,
excel `read_xlsx`, spatial `st_read*`, vortex, lance, motherduck `md_*`, ui, tpch/tpcds `dbgen`/`dsdgen`).

**Derived, not hand-typed — `SqlGuardFileFunctionContractTest`** (DuckDB `1.5.2.1`): it LOADs every
installed extension best-effort, reads `duckdb_functions()`, and asserts every `table`/`table_macro`
function is refused unless `SAFE_TABLE_FUNCTIONS` names it (and every SAFE entry is still a table function
and still passes), and every scalar whose parameter is *named* like a path (`path`/`file`/`url`/`uri`/
`location`/`directory`) is refused unless it is one of the pure string splitters `parse_path`/
`parse_dirname`/`parse_dirpath`/`parse_filename`. **A DuckDB upgrade that adds a table function fails the
build until it is classified.** Extensions not installed offline (iceberg, delta, spatial, …) cannot be
enumerated, so a short hand-kept list pins them. Mutation-checked: dropping `parquet_\w+` from the pattern
reports exactly the six `parquet_*` inspectors; dropping the quoted-name handling turns
`aQuotedFunctionNameIsStillRefused` red.

⚠ **Known false rejects, accepted:** the `histogram(x)` **aggregate** shares its name with the table
macro and is refused too, as is a CTE with a column list named like a blocked function
(`WITH summary(a) AS …`). A false reject costs a repair round; a false accept is a file read. The
`read_parquet('<registered path>')` masking in `GuardedDerivedTableEmitter` is unaffected — it masks the
call to an identifier before the guard runs.

**Layer two — DuckDB's own parse tree (`SQLGUARD-COMMA-RELATION-1`, closed 2026-09-24).** The
replacement-scan check (`RELATION_REF`) judged only the token after `FROM`/`JOIN`, so
`SELECT b.secret FROM (SELECT 1) a, '<any file>' b` passed the guard and returned the file's rows on an
unsealed connection. Not patched with another regex: text the lexical checks pass is now parsed by DuckDB
itself — `SELECT json_serialize_sql(?::VARCHAR)` on one lazily-opened, in-memory, extension-autoload-off,
sealed connection (`SqlGuard.parseTreeViolations`) — and the tree is walked. Refused: an error from the
parser (it serializes **SELECT only**, so DML, `PIVOT` statements, `EXPLAIN` and syntax errors all refuse),
more than one statement, a relation kind outside `JOIN`/`SUBQUERY`/`BASE_TABLE`/`TABLE_FUNCTION`/`EMPTY`/
`EXPRESSION_LIST`/`PIVOT` (so `SHOW_REF` — `DESCRIBE`/`SUMMARIZE`/`SHOW` — refuses), a `BASE_TABLE` whose
catalog, schema or name contains `/ \ . : * ? [` (a string literal and a quoted identifier are the same
node; the caller's `trustedRelation` is exempt only as an exact unqualified name), a `BASE_TABLE` named
`duckdb_*`/`pragma_*` (the catalog **views** — `FROM duckdb_databases` with no parens listed every attached
database's file path, and the lexical scan only sees a name followed by `(`), a `TABLE_FUNCTION` not in
`SAFE_TABLE_FUNCTIONS`, and any function call `BLOCKED_FUNCTION` names. Because every relation node is
judged where it sits, FROM-list commas, subqueries, CTE bodies, set-op branches, `LATERAL`, scalar and
lambda subqueries and the `FROM 'file'` shorthand are all covered. The lexical layer stays first (defence
in depth, and it keeps its messages for what it already caught). **Cost: ~0.2 ms/call warm** (DuckDB
1.5.2.1; 0.6 ms in the surefire JVM including JIT) — no cache. `SqlGuardParseTreeTest` (mutation-checked:
skipping layer two turns the comma probe, the `"x.csv"` identifier and the trusted-relation cases red).
`SqlTemplateJobSandboxTest.aFileLiteralTheGuardMissesIsStoppedByTheConnectionSeal` now isolates the seal
by swapping the job's guard for a pass-everything one (`SqlTemplateJob.guard`, a test seam) — mutation-checked:
removing `sealAllowing` turns it red.

⚠ `SqlSandbox.open` was not reused for the job: its interactive memory/timeout caps (`assist.sql.*`) do not
fit a batch job. The two static helpers are the same statements `open`/`seal` now call, so there is still
one definition of the lockdown.
