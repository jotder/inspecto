# Inspecto LA (Link Analysis) showcase: Postgres store and Basic variants

**Purpose:** run the `la-showcase` demo (Space Template `spaces/_templates/la-showcase`, seeded by
`tools/seed-la-showcase.mjs`) on two non-default variants. The default variant (Advanced or Standard
bundle, filesystem Investigation store) is described in
[`okf/product/module/config/plan-spaces.md`](../../module/config/plan-spaces.md) section 3.5; this page only adds what differs.

Status: the steps below are derived from code and config, cited per line. Neither variant has been driven
end to end in a browser by this page's author (see "Not verified").

## 0. The default demo: unzip, run, sign in (since 2026-10-12)

The `-Edition Advanced -DemoAuth -Ui la-app` bundle (`inspecto-demo-<platform>.zip`) ships `la-showcase` **pre-seeded** under
`spaces\la-showcase` (operator decision 2026-10-12; the only bundle that ships a Space; `docs/okf/product/layer/platform/spec-build-test.md`).
Pre-flight is: unzip, run `serve-demo.bat` / `./serve-demo.sh` (`PORT` overrides 8080), open the URL, pick a Demo User. No node, no scaffold, no seed.
It carries the Investigation, members, Drafts, Entity Lists, identity assertion, Case, Alert Rule and Jobs; `DEMO-BUILD.txt` in the zip lists the five Demo Users.
**Reset:** stop the server, delete `spaces\la-showcase`, restore it from the zip. `package.ps1 -NoBakeDemo` builds the bundle without the Space
(then scaffold and seed as in the sections below).

## A. Postgres Investigation store

What it is: the optional module `la/inspecto-la-store-pg` keeps the sealed Investigation log, Working Set sets,
members and Drafts in PostgreSQL, one `space_<id>` schema per Space
(`docs/okf/product/module/la-core/architecture.md` section 3.2, `docs/okf/product/module/config/admin-guide-configuration-reference.md` row "Link Analysis Investigations").

Prerequisites (verified):

1. An Advanced or Preview bundle. The module is built only under `-Pedition-advanced` / `-Pedition-preview`
   (`pom.xml` profiles) and staged by `inspecto/package.ps1`; `offerings/advanced.toon` lists `la-store-pg`.
   Basic and Standard do not carry it, so `db` there is a 503.
2. A reachable PostgreSQL. The bundle does not start one for this store; the sidecar is the platform's own
   operational database, not this store's. Supply a database yourself.
3. JVM properties (read by `InvestigationStores`): `-Dinvestigations.backend=db`,
   `-Dinvestigations.db.url=jdbc:postgresql://<host>:5432/<db>`, and `-Dinvestigations.db.user` / `.password`;
   each connection property falls back to `-Dinspecto.db.url` / `.user` / `.password`.
   Pass them with the launcher's operator hook: `INSPECTO_JAVA_OPTS` (`inspecto/package.ps1`; honoured by `serve.*` and `serve-demo.*`, fallback `EXTRA_JAVA_OPTS`). Not `JAVA_TOOL_OPTIONS`.
   Do not put the password in a committed file.

Steps:

1. Build and launch the Advanced bundle as for the default variant, with the properties above in `INSPECTO_JAVA_OPTS`.
2. Create the Space (scaffold or `POST /spaces {"id":"la-showcase","template":"la-showcase"}`), then
   `node tools/seed-la-showcase.mjs --base <url> --apply`.
   The seed also pre-stages four demo scenes, each idempotent, soft-failing (a warning, exit code unchanged) and skipped
   by its flag: `--no-pending` (one pending four-eyes expand on `mule-hub-case`, left undecided for `fm.manager`),
   `--no-alert` (one `la_detect` run until the `mule-pass-through` Alert exists), `--no-coverage` (a second
   Investigation, `delivery-gap-case`, whose window reaches past the landed files) and `--no-draft` (one open Draft with
   one step, left unpromoted). It ends with a line naming the pending request, the Alert, the coverage case and the Draft.
3. Confirm: `GET /settings/link-analysis` echoes `investigationStoreInForce: "db"`
   (`inspecto/src/main/java/com/gamma/control/SettingsRoutes.java`), and `/health/details` shows
   `live.investigations` UP for the Space.

Failure behaviour (by design): an unknown value, `db` without the module, a missing or non-PostgreSQL URL, or an
unreachable database is **503 `CAPABILITY_UNAVAILABLE`** on the Investigation routes; it never falls back to the
filesystem and never refuses the boot (WARN plus `live.investigations` DOWN). The connection properties are read
once: a change needs a restart. A Space that switches backend starts empty (no importer).

## B. Basic edition

Truth, from code and config (LA-EDITIONS-1 phase 3, 2026-10-17; this section described the old Personal edition
before): **Basic carries the Link Analysis EXPLORER but no Investigations.**

- `pom.xml` lists `features/inspecto-entity-list`, `la/inspecto-la-core`, `-la-storage`, `-la-api` and `-geo-link` in the
  default reactor (Basic); `offerings/basic.toon` lists them and the `linkAnalysis` add-on.
- The Investigation surface (Investigations, Drafts, Dossier, templates, members, embeds, Graph Run, index builds, Alert
  Rules on Investigations, inbound and outbound integration) answers **503 `CAPABILITY_UNAVAILABLE`** "available in
  Standard and above": no module declares the `investigations` edition feature (only `inspecto-ops` does, Standard and up).
  See `docs/EDITIONS.md`, *Basic — the Link Analysis explorer*.

So on Basic the explorer works with no sign-in (projection, paths, patterns, Value Measures, geo, Entity profile
pages), but `tools/seed-la-showcase.mjs --apply` cannot seed Investigations, e.g. `GET /inv/investigations` -> 503.
The launch config `inspecto-personal` (`.claude/launch.json`, port 8098) keeps its old name.

If you need the full demo, use an Advanced or Standard bundle (filesystem store), or Advanced for variant A.

## Not verified

- Variant A was RUN LIVE 2026-10-13 (la-seed-ingest lane): PostgreSQL 18 from `dev-infra/docker-compose.yml`, the contract test
  `PgInvestigationStoreContractTest` 45 run / 0 failed / 0 skipped (recipe in its javadoc; add `&loginTimeout=10` to the URL), then an
  Advanced `-Ui la-app -NoBakeDemo` bundle with `INSPECTO_JAVA_OPTS="-Dla.embed.enabled=true -Dinvestigations.backend=db
  -Dinvestigations.db.url=jdbc:postgresql://localhost:5432/postgres?loginTimeout=10 -Dinvestigations.db.user=... -Dinvestigations.db.password=..."`;
  `seed-la-showcase.mjs --scaffold <bundle>\spaces` before start, `--apply` after: 0 failed, `la-demo-smoke` 17/17 (also after a
  restart: the log reads back, the manifest verifies, a tamper is detected), `investigationStoreInForce: db`, `space_la_showcase`
  holding la_log 9 / la_set 9 / la_member 3 / la_draft 1 / la_pending 1. No store defect found. Tips: `--scaffold` takes the spaces
  root as its own argument (it is not a flag of the `--apply` run; it now refuses a missing root); drop the throwaway schema after
  (`drop schema space_la_showcase cascade`). A browser pass on the `db` variant was not repeated (the SPA calls the same routes).
- `serve-demo.*` honouring `INSPECTO_JAVA_OPTS` is proved by the static launcher guard
  (`tools/check-launchers.mjs`), not by a live demo-bundle launch.
- The Basic 503 for the Investigation surface: proved over real HTTP by `ControlApiBasicEditionLinkAnalysisTest` (geo-link; one route per Investigation module, plus inbound and integration routes). Not re-probed on a built Basic *bundle*.
