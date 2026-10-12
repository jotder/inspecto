# Link Analysis showcase: Postgres store and Personal variants

**Purpose:** run the `la-showcase` demo (Space Template `spaces/_templates/la-showcase`, seeded by
`tools/seed-la-showcase.mjs`) on two non-default variants. The default variant (Enterprise or Professional
bundle, filesystem Investigation store) is described in
[`okf/capabilities/spaces/spaces.md`](../okf/capabilities/spaces/spaces.md) section 3.5; this page only adds what differs.

Status: the steps below are derived from code and config, cited per line. Neither variant has been driven
end to end in a browser by this page's author (see "Not verified").

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
3. Confirm: `GET /settings/link-analysis` echoes `investigationStoreInForce: "db"`
   (`inspecto/src/main/java/com/gamma/control/SettingsRoutes.java`), and `/health/details` shows
   `live.investigations` UP for the Space.

Failure behaviour (by design): an unknown value, `db` without the module, a missing or non-PostgreSQL URL, or an
unreachable database is **503 `CAPABILITY_UNAVAILABLE`** on the Investigation routes; it never falls back to the
filesystem and never refuses the boot (WARN plus `live.investigations` DOWN). The connection properties are read
once: a change needs a restart. A Space that switches backend starts empty (no importer).

## B. Personal edition

Truth, from code and config: **Personal does not include Link Analysis or the geo map.**

- `pom.xml` lists `la/inspecto-la-core`, `-la-api`, `-geo-link`, `-la-graph`, `-la-storage` only inside the
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

- Variant A has not been run against a live PostgreSQL by this page's author; the property names, the 503
  semantics and the `investigationStoreInForce` echo are from code and `ControlApiSettingsTest` /
  `InvestigationStoresTest`, not from a live boot.
- `serve-demo.*` honouring `INSPECTO_JAVA_OPTS` is proved by the static launcher guard
  (`tools/check-launchers.mjs`), not by a live demo-bundle launch.
- The Personal 503 for `/inv/*` was not re-probed on a built Personal bundle in this pass.
