# Link Analysis showcase: Postgres store and Personal variants

**Purpose:** run the `la-showcase` demo (Space Template `spaces/_templates/la-showcase`, seeded by
`tools/seed-la-showcase.mjs`) on two non-default variants. The default variant (Enterprise or Professional
bundle, filesystem Investigation store) is described in
[`okf/capabilities/spaces/spaces.md`](../okf/capabilities/spaces/spaces.md) section 3.5; this page only adds what differs.

Status: the steps below are derived from code and config, cited per line. Neither variant has been driven
end to end in a browser by this page's author (see "Not verified").

## 0. The default demo: unzip, run, sign in (since 2026-10-12)

The `-Edition Enterprise -DemoAuth -Ui la-app` bundle (`inspecto-demo-<platform>.zip`) ships `la-showcase` **pre-seeded** under
`spaces\la-showcase` (operator decision 2026-10-12; the only bundle that ships a Space; `docs/okf/backend/build-run/build-test.md`).
Pre-flight is: unzip, run `serve-demo.bat` / `./serve-demo.sh` (`PORT` overrides 8080), open the URL, pick a Demo User. No node, no scaffold, no seed.
It carries the Investigation, members, Drafts, Entity Lists, identity assertion, Case, Alert Rule and Jobs; `DEMO-BUILD.txt` in the zip lists the five Demo Users.
**Reset:** stop the server, delete `spaces\la-showcase`, restore it from the zip. `package.ps1 -NoBakeDemo` builds the bundle without the Space
(then scaffold and seed as in the sections below).

## A. Postgres Investigation store

What it is: the optional module `la/inspecto-la-store-pg` keeps the sealed Investigation log, Working Set sets,
members and Drafts in PostgreSQL, one `space_<id>` schema per Space
(`docs/okf/backend/modules/link-analysis.md` section 4, `docs/okf/backend/config/configuration.md` row "Link Analysis Investigations").

Prerequisites (verified):

1. An Enterprise or Preview bundle. The module is built only under `-Pedition-enterprise` / `-Pedition-preview`
   (`pom.xml` profiles) and staged by `inspecto/package.ps1`; `offerings/enterprise.toon` lists `la-store-pg`.
   Professional and Standard do not carry it, so `db` there is a 503.
2. A reachable PostgreSQL. The bundle does not start one for this store; the sidecar is the platform's own
   operational database, not this store's. Supply a database yourself.
3. JVM properties (read by `InvestigationStores`): `-Dinvestigations.backend=db`,
   `-Dinvestigations.db.url=jdbc:postgresql://<host>:5432/<db>`, and `-Dinvestigations.db.user` / `.password`;
   each connection property falls back to `-Dinspecto.db.url` / `.user` / `.password`.
   Pass them with the launcher's operator hook: `INSPECTO_JAVA_OPTS` (`inspecto/package.ps1`; honoured by `serve.*` and `serve-demo.*`, fallback `EXTRA_JAVA_OPTS`). Not `JAVA_TOOL_OPTIONS`.
   Do not put the password in a committed file.

Steps:

1. Build and launch the Enterprise bundle as for the default variant, with the properties above in `INSPECTO_JAVA_OPTS`.
2. Create the Space (scaffold or `POST /spaces {"id":"la-showcase","template":"la-showcase"}`), then
   `node tools/seed-la-showcase.mjs --base <url> --apply`.
   The seed also pre-stages three demo scenes, each idempotent, soft-failing (a warning, exit code unchanged) and skipped
   by its flag: `--no-pending` (one pending four-eyes expand on `mule-hub-case`, left undecided for `fm.manager`),
   `--no-alert` (one `la_detect` run until the `mule-pass-through` Alert exists) and `--no-draft` (one open Draft with
   one step, left unpromoted). It ends with a line naming the pending request, the Alert and the Draft.
3. Confirm: `GET /settings/link-analysis` echoes `investigationStoreInForce: "db"`
   (`inspecto/src/main/java/com/gamma/control/SettingsRoutes.java`), and `/health/details` shows
   `live.investigations` UP for the Space.

Failure behaviour (by design): an unknown value, `db` without the module, a missing or non-PostgreSQL URL, or an
unreachable database is **503 `CAPABILITY_UNAVAILABLE`** on the Investigation routes; it never falls back to the
filesystem and never refuses the boot (WARN plus `live.investigations` DOWN). The connection properties are read
once: a change needs a restart. A Space that switches backend starts empty (no importer).

## B. Personal edition

Truth, from code and config: **Personal does not include Link Analysis or the geo map.**

- `pom.xml` lists `la/inspecto-la-core`, `-la-api`, `-geo-link`, `-la-storage` only inside the
  `edition-professional`, `-standard`, `-preview` and `-enterprise` profiles; Personal is the default reactor
  (`docs/okf/capabilities/editions/editions.md`: "There is no `edition-personal` profile").
- `offerings/personal.toon` declares `modules[1]: connectors` and no Link Analysis addon.
- `docs/okf/backend/modules/link-analysis.md` section 8 table: all LA modules are "-" for Personal.

What a Personal bundle does when the demo is pointed at it: every Link Analysis route is a stub that answers
**503 `CAPABILITY_UNAVAILABLE`** (`inspecto/src/main/java/com/gamma/control/AbsentModuleRoutes.java`, a module
that is not installed). So `tools/seed-la-showcase.mjs --apply` cannot seed Investigations there, and there is no
Link Analysis demo on Personal. The launch config `inspecto-personal` (`.claude/launch.json`, port 8098) exists to
show exactly that refusal, e.g. `GET /inv/investigations` -> 503.

If you need the demo, use an Enterprise or Professional bundle (filesystem store), or Enterprise for variant A.

## Not verified

- Variant A was RUN LIVE 2026-10-13 (la-seed-ingest lane): PostgreSQL 18 from `dev-infra/docker-compose.yml`, the contract test
  `PgInvestigationStoreContractTest` 45 run / 0 failed / 0 skipped (recipe in its javadoc; add `&loginTimeout=10` to the URL), then an
  Enterprise `-Ui la-app -NoBakeDemo` bundle with `INSPECTO_JAVA_OPTS="-Dla.embed.enabled=true -Dinvestigations.backend=db
  -Dinvestigations.db.url=jdbc:postgresql://localhost:5432/postgres?loginTimeout=10 -Dinvestigations.db.user=... -Dinvestigations.db.password=..."`;
  `seed-la-showcase.mjs --scaffold <bundle>\spaces` before start, `--apply` after: 0 failed, `la-demo-smoke` 17/17 (also after a
  restart: the log reads back, the manifest verifies, a tamper is detected), `investigationStoreInForce: db`, `space_la_showcase`
  holding la_log 9 / la_set 9 / la_member 3 / la_draft 1 / la_pending 1. No store defect found. Tips: `--scaffold` takes the spaces
  root as its own argument (it is not a flag of the `--apply` run; it now refuses a missing root); drop the throwaway schema after
  (`drop schema space_la_showcase cascade`). A browser pass on the `db` variant was not repeated (the SPA calls the same routes).
- `serve-demo.*` honouring `INSPECTO_JAVA_OPTS` is proved by the static launcher guard
  (`tools/check-launchers.mjs`), not by a live demo-bundle launch.
- The Personal 503 for `/inv/*`: proved over real HTTP on the Personal build by `NoGeoLinkShipsInThePersonalBuildTest` (every `la-api`/`geo-link` stub path, 503 naming the module; 3/3, 2026-10-13) plus `AbsentModuleRoutesTest` 3/3. Not re-probed on a built Personal *bundle* (same classpath, so no new information expected).
