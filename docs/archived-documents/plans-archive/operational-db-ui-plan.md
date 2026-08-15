# Plan — PG-1 Open 2: UI configurability of the operational database

**Status: ✅ Stage 1 SHIPPED 2026-08-15** — all three §5 questions answered as recommended (Stage 1 only ·
`-D` wins, moot · references only). **Stage 2 (persistence) is deliberately NOT built and is not owed:**
Q1 chose read + validate as the *end state*, so §4 is a sketch retained for reasoning, not a plan of record.
As-built: `okf/backend/engine/db-layer.md` §5.0-a.

Written 2026-08-15 by grounding BACKLOG §6's PG-1 row, whose
Open 2 reads *"Workable shape: the UI reads the current selection, validates a proposed connection (a real
test-connection round-trip), writes it to server config, and it applies at next restart. Design-first."*

**Every one of those four clauses lands on something that does not exist.** §1 is therefore the plan.

---

## 1. What exists today (grounded; the negatives ARE the design)

| The row assumes | Reality | Evidence |
|---|---|---|
| the UI can *read* the current selection | **No route exposes it.** `GET /health/details` reports `jobRunsProjection` UP/DOWN and never which backend, URL or user | `HealthDetails.java` |
| a *real* test-connection round-trip exists to reuse | **It does not.** `ConnectionTester` is a plain TCP `Socket` connect + a check that `${…}` secret refs resolve — no JDBC login, and it takes a `ConnectionProfile` (host/port/tunnel/proxy), **not** a JDBC URL | `ConnectionTester.java`, `ConnectionRoutes:40,51` |
| there is a *server config* to write to | **There is none.** No settings/server/bootstrap file is read at boot by `ControlApi.main`, `SpaceManager` or `ServiceBootstrap`. `space.toon` holds `display_name`/`description`/`created_at` only. **`-D` is the entire configuration surface** | `SpaceContext.SpaceManifest:56-71`; BACKLOG §6 |
| "applies at next restart" | **Correct, and unavoidable** — there is no reload/restart/re-discover endpoint anywhere; `main()` wires a shutdown hook and nothing else | `ControlApi:340-371` |

Two more facts the row does not mention, both of which shape the build:

- 🔴 **`OperationalDb` is package-private in `com.gamma.service`**, while routes live in `com.gamma.control`.
  A read route needs an accessor or a widened type — a deliberate choice, not an oversight to paper over.
- 🔴 **The ten families have NO roster.** Their property names are string literals at ten call sites
  (`ServiceStores:65,87,124,146,190,213,235,257,290`, `SpaceBootstrap:37`). Any "what is my deployment
  using" view must enumerate them — and ⛔ **a hand-copied roster is precisely the mirror bug this codebase
  keeps paying for** (the batches ledger header's five mirrors; the `REQUEST_SCOPED_ATTRS` completeness
  guard). The roster must be the one the stores themselves consult, or it must be proven complete by a test.
- ⚠ **URL grain ≠ credential grain.** The four `objects.*` families each carry their own `*.db.url` but
  share **one** `objects.db.user`/`objects.db.password` (`ServiceStores:190-260`). A UI that renders
  "family → url/user/password" uniformly would misrepresent the model.

---

## 2. The real question: does the UI write server config at all?

The bootstrap problem the row names ("the UI is served by the process that needs the database") is real but
is *not* the hard part — "applies at next restart" answers it. The hard part is that persisting from the UI
**invents a second declaration of the same fact**. Then `-Dinspecto.db.url=A` and a stored `B` both exist
and something must decide. This codebase has refused that shape before, explicitly: an enrichment's
companion file is the truth and the pipeline node carries only a reference, *"never a mirror; the D7
split-brain lesson"*.

There is also a plain division-of-ownership argument: which database a service process talks to is normally
owned by deployment tooling — a systemd unit, a container env, `serve.sh` — not by the application's own
admin screen. An app that rewrites its own infrastructure config is the thing that makes a deployment
irreproducible from its manifest.

**Recommendation: split the work, and let the second half be a separate decision.**

---

## 3. Stage 1 — read + validate (invents no configuration surface)

This is most of the operator value and none of the split-brain risk: *"what is this deployment actually
using?"* and *"will these settings work before I restart into them?"*

1. **`GET /system/operational-db`** — the **effective** configuration: the selected backend, and per family
   its resolved URL plus **the source of that value** (per-family property · shared property · space
   default). ⛔ The password is never returned, in any form — not even redacted-with-length. The user is
   returned as configured (it is not a secret, and "which user am I connecting as" is the question being
   asked). Gated `canConfigureAccess` (the existing admin-shaped capability; no `/system` family exists yet).
2. **`POST /system/operational-db/test`** — a genuine JDBC round-trip (`DriverManager.getConnection` +
   `SELECT 1`) against a **supplied** URL/user/password-reference, with the driver probed by name exactly as
   `OperationalDb.verifySelectable` does. Named outcomes, not a boolean: `DRIVER_MISSING` · `UNREACHABLE` ·
   `AUTH_FAILED` · `OK`. The distinction matters — `DRIVER_MISSING` means "drop `postgresql.jar` beside
   `inspecto.jar`", which is a completely different action from bad credentials.
3. **The roster becomes real** — one enumeration of the ten families that both `ServiceStores` and the route
   consult, or a completeness test that fails when a call site names a family the roster omits.

### Verifiable success criteria

1. A deployment with no `-D` set → the route reports `duckdb` and, per family, the **space default** as the
   source → verify: real-HTTP test (the `endpoint` skill's mandatory test class), asserting sources not just values.
2. `-Djobs.db.url=X` with `-Dinspecto.db.url=Y` → jobs reports `X` sourced *per-family*, others report `Y`
   *shared* → verify: the precedence in `urlFor` is observable, not just asserted in a unit test.
3. `POST …/test` with a URL to nothing → `UNREACHABLE`, not a 500 and not a stack trace → verify: named outcome.
4. ⚠ **Falsify the redaction**: set a password property, call the read route, and assert the value appears
   **nowhere** in the response body. A redaction test that only checks the `password` field passes while the
   secret leaks through a URL that embeds it (`jdbc:postgresql://user:pw@host/db` is legal) — assert on the
   whole body, and handle the credential-in-URL case deliberately.

---

## 4. Stage 2 — persistence (only if Q1 says so)

If the UI does persist, the destination must be **discoverable at boot without a new bootstrap property**,
and precedence must be stated, not emergent. Sketch, not a commitment: a file beside the spaces root
(already resolved before any store opens), read by `SpaceManager.discover` immediately before
`verifySelectable`, with **`-D` winning over the file** so an operator's explicit launch flag is never
silently overridden by something a browser wrote. ⚠ `-Dspaces.root` is read in `com.gamma.service`; note
that the *engine* cannot read it (path-containment lesson) — this file therefore belongs to the control
plane, not the engine.

**The password stays a `SecretResolver` reference** (`${ENV:…}`, `${KEYSTORE:alias}`, `${FILE:…}`), which
already ships. That means **the UI never handles the secret at all** — it writes a *reference*, the operator
provisions the value out of band. This dodges "an admin screen writes credentials to disk" entirely, using
a mechanism that exists.

---

## 5. Open questions — the operator's call

**Q1 — Does the UI persist, or is Stage 1 the end state?** (a) **Stage 1 only: read + validate, and show
the operator the exact flags to set in their own deployment tooling** *(recommended — invents no second
declaration, and infra config stays owned by the deployment manifest)* · (b) Stage 1 now, Stage 2 as a
follow-on · (c) Stage 1 + Stage 2 together, persistence included.

**Q2 — If it persists (Q1 = b/c), what wins when both a `-D` and the stored file declare the database?**
(a) **`-D` wins** *(recommended — an explicit launch flag must never be overridden by something a browser
wrote; the file is the default, the flag is the override)* · (b) the stored file wins *(the UI's writes
would otherwise appear to do nothing on a `-D`-launched deployment — arguably less confusing for the
operator, and honest about who "owns" the setting)*.

**Q3 — May the test endpoint accept a literal password, or references only?** (a) **References only**
*(recommended — a literal in a form post is a credential in transit and in any access log; the operator
provisions the secret first, then tests)* · (b) allow a literal for the test, never storing or echoing it
*(better first-run ergonomics: test the connection before wiring up a keystore entry)*.

⚠ Q3(b) has a real ergonomic case — provisioning a keystore alias before you know the credentials work is
awkward. I recommend (a) anyway; it is the safer default, and Q3 can be revisited from evidence.

---

## 6. Related

- BACKLOG §6 PG-1 · as-built for the shipped halves: `okf/backend/engine/db-layer.md` §5.0.
- The `endpoint` skill governs both new routes: gate order (write-root 503 → spec 422 → jail 403 → 409 →
  act atomically) and a mandatory real-HTTP test class covering **every** gate.
- ⛔ Do not let the read route grow into a general `/system` config dump — the value it exposes is
  credential-adjacent, and every field added is a field to prove redacted.
