# Parser framework — self-describing Parser plugins, grammar-driven, tree-capable preview

> **ARCHIVED 2026-08-30 — FULLY DELIVERED.** P1–P5 shipped 2026-07-30/31 (framework, `GET /parsers`,
> `POST /parsers/{id}/preview`, UI adoption, segments editor, the OKF concept, the GLOSSARY *Grammar*
> entry); the last gating item, **(b) the tree→segments ingest bridge**, shipped 2026-08-30 as
> `XmlRecordIngester` (`8f7bee75`), so hierarchical parsers are no longer preview-only.
>
> 🔴 **`docs/INDEX.md` carried this as "NOT APPROVED, NOTHING BUILT" until 2026-08-30** — wrong on both
> counts, and wrong for over a month while every one of its deliverables sat in the tree. The 2026-08-28
> pass that added the row was correcting a real problem (the plan was unlisted) and introduced a worse
> one by inferring status from the FILE's missing header rather than from the code. A plan carrying no
> status header means its status is unknown, not that it is unbuilt — check for its deliverables.
>
> Durable as-built facts live in `docs/okf/backend/engine/parser-plugins.md`. Its recorded follow-ons
> (drop-in `plugins/` dir; the ASN.1 declarative decode profile's grammar-source half) are BACKLOG §4
> rows. ⚠ Everything below is the ORIGINAL plan text, including statements the as-built refuted — most
> notably "hierarchical parsers are preview-only until the flatten DSL" and the §Verification
> expectation that XML serves `ingestable:false`. Read it for rationale, never as current behaviour.

## Context — the operator's framework, mapped onto what exists

The operator's design (the E of ELT): any file loads into one or more **Tables** (CSV/Parquet); a
file type is parsed by providing options — a **Grammar**; internally there are two fully
transparent parser engines (**DuckDB-native** and **custom Java**), unified behind a wrapper; the
wrapper exposes helper interfaces (a name, preview part of the contents, clues, "how do I parse
this", or a full grammar like an ASN.1 module) so any new parser can be **deployed and configured
as a plugin** (`getPreviewText()`, `getConfigSchema()`); non-tabular data is **tree-shaped** — for
now, produce the tree and visualize it (table or tree); the flatten DSL comes later.

Exploration confirms the seams already exist — the framework is mostly *unification*, not invention:

- **Ingest SPI exists**: `com.gamma.etl.StreamingFileIngester` (`@PublicApi`) — plugin emits
  records; the framework owns DuckDB, transform, partitioned CSV/Parquet write (= point 1's
  "load to Tables", unchanged). But discovery is `Class.forName` on a config FQCN
  ([UnionModeIngester.java:169](../../inspecto-engine/src/main/java/com/gamma/inspector/UnionModeIngester.java)),
  there is **no metadata, no config schema, no preview** (plugin preview hard-422s in
  [ComponentPreview.java:143](../../inspecto-engine/src/main/java/com/gamma/pipeline/exec/ComponentPreview.java)).
- **ServiceLoader house pattern**: `CollectorConnectorFactory`/`CollectorConnectors`,
  `NotificationChannel`, and `PipelineNodeTypes` (builtin enum merged with ServiceLoader — the
  exact registry shape needed). Drop-in-jar classloading precedent: `JobPackManager`.
- **Served form schema exists**: `FieldSpec` (inspecto-config) is already the dynamic-form
  contract served by `GET /config/spec/{type}` and `/bootstrap`; `GET /findings/{type}` proves the
  serve-spec→`<inspecto-schema-form>` loop end-to-end (UI mapper precedent:
  [mail-model.ts:171](../../inspecto-ui/src/app/modules/admin/objects/mail-model.ts) `findingsAttributes`).
- **Tree UI exists**: `ParserPreview` union (`kind: 'table' | 'tree'`, `ParserTreeNode`) +
  shared `app-parser-tree` + the onboarding Table|Tree toggle — but the only tree source today is
  client-side JSON. The Pipelines Parser dialog's `/components/grammar/preview` is **mock-only**;
  this plan realizes that exact contract server-side.
- **Dependency fact**: inspecto-etl does NOT depend on inspecto-config today; config builds before
  etl, so adding the dependency is cycle-free. Engine depends on both.
- **asn-parser** is NOT plugin-ready (no facade; `RecordMapper` package-private; decode tuple
  hardcoded in `GoldenCapture.CASES`; two conflicting Maven projects) and is another shift's live
  workstream — adopt later via this SPI, don't touch it now. **No XML ingest exists anywhere.**

**Decisions taken** (stated, since the scope questions were superseded by the framework brief):
type list = Delimited · Fixed width · Text/regex (kept — engine-real today) · JSON · XML · Custom
(discovered plugins); **ASN.1 is not faked** — it appears in the catalog the day its plugin jar
exists (backlog carries the adoption prerequisites for that shift); discovery is classpath
ServiceLoader now, drop-in `plugins/` dir is a recorded follow-on; hierarchical parsers are
**preview-only until the flatten DSL** (their tree can't honestly load to Tables yet) and the UI
says so; Custom ingestable parsers reuse the existing `parsing.plugin` TOON shape — **zero config
format change**. Vocabulary: **Parser** (canonical, GLOSSARY §6) consumes a **Grammar** (the
authored options; already the `grammar` component kind in the UI) — add Grammar to the GLOSSARY.

## P1 — The SPI + registry + builtin adapters (backend)

- **inspecto-etl** (new pkg `com.gamma.etl.parse`, plugin authors depend only on etl+config):
  - `ParserPlugin` (`@PublicApi`): `String id()` (`[a-z0-9_]+`), `String label()`,
    `boolean hierarchical()`, `List<FieldSpec> grammarSchema()`,
    `ParseResult preview(byte[] sample, Map<String,Object> grammar) throws Exception`,
    `default Map<String,Object> suggest(byte[] sample)` = `Map.of()` (clues),
    `default Optional<String> ingesterClass()` = empty — the FQCN of its `StreamingFileIngester`
    when the format can load to Tables today (`ingestable` = present OR builtin).
  - `ParseResult` (sealed): `Table(columns, rows, rowCount, rejectedRows)` |
    `Tree(recordCount, nodes)` with `Node(label, type, value, children)` — mirrors the UI's
    `ParserPreview`/`ParserTreeNode` exactly.
  - pom: add `inspecto-config` dependency.
- **inspecto-engine** `com.gamma.parse.Parsers` registry (PipelineNodeTypes precedent): four
  builtin adapters — `delimited`, `fixedwidth`, `json`, `text_regex` — id/label/hints from today's
  catalog, `grammarSchema()` = FieldSpec translations of
  [parsing-attributes.ts](../../inspecto-ui/src/app/inspecto/grammar/parsing-attributes.ts)
  keys as dotted paths (`delimited.delimiter`, …), `preview()` delegating to the existing
  `ComponentPreview` per-frontend logic (byte[]→UTF-8 text for these) — merged with
  `ServiceLoader.load(ParserPlugin)`; duplicate id ⇒ fail loud at startup.
- Tests: registry merge + duplicate-id failure + discovery via a test-scope
  `META-INF/services` fixture; adapter schema sanity (every key engine-real).

## P2 — Catalog + preview routes (control plane; apply the `endpoint` skill)

New `ParserRoutes` (registered in `ControlApi`, + `CapabilityManifest` entries):

- `GET /parsers` → `[{id, label, hierarchical, ingestable, grammarSchema: [FieldSpec…]}]`.
- `POST /parsers/{id}/preview` `{grammar, sample_text | sample_b64}` (text cap 1MB as today;
  b64 cap ~4MB — binary formats need bytes) → `ParseResult` JSON
  `{kind:'table'|'tree', …}`; 404 unknown id; parse/grammar failures → 422 with the message
  (mirrors `/config/preview/parsing`'s contract). Gating mirrors the existing preview routes.
- `/config/preview/parsing` stays byte-identical (onboarding's draft-true path for builtins).
- Real-HTTP test class per the endpoint skill covering both routes' gates, caps, 404/422, and
  a tree-shaped response.

## P3 — XML: the first real Java plugin (proves the whole path)

- `XmlParserPlugin` in inspecto-engine, registered via `META-INF/services` (NotificationChannel
  precedent; a standalone drop-in module is the follow-on). StAX from the JDK — **no new
  dependency** — XXE-hardened like
  [S3Connector.java:272](../../inspecto-connectors/src/main/java/com/gamma/acquire/connectors/S3Connector.java).
- Grammar: `record_element` (local name or slash-path), `namespace_aware`, `encoding`,
  `max_records`; `suggest()` proposes `record_element` from the root's repeated child.
- `preview()` → `Tree`: element→node, attributes as `@attr` children, text as leaf values,
  type tags element/attr/text. `hierarchical()=true`, `ingesterClass()` empty — **preview-only
  until the flatten DSL** (that is the honest state of tree data, per the brief).
- Tests: nested/attributes/mixed content → tree shape; malformed → 422-able exception; XXE
  blocked; record cap.

## P4 — UI adoption (apply the `angular-ui` skill)

- `ParsersService` (`inspecto/api` + barrel): `list()`, `preview(id, grammar, sampleText)`;
  new `ParserDef` type; reuse the existing `ParserPreview` union.
- Shared `fieldSpecsToAttributes()` mapper (FieldSpec → AttributeSpec: dotted path → `__`,
  type mapping with the skip-unknown guard — `findingsAttributes` precedent).
- **Onboarding Parsing pane**: the type toggle renders the served catalog (local four as offline/
  old-server fallback). Builtins: unchanged flow (draft preview). Non-ingestable served types
  (XML): options form from served `grammarSchema`, Test parse → `/parsers/{id}/preview`, tree
  renders in the existing Table|Tree region, **Save disabled** with the honest note ("previews
  today — loading to Tables arrives with the flatten configuration"). Custom ingestable plugins:
  discovery + options form + preview now; **Save stays TOON-managed this slice** (the existing
  plugin banner — a segments editor is a recorded follow-on, so the UI cannot write a broken
  plugin config).
- **Pipelines Parser dialog**: replace the hardcoded `PARSER_TYPES` catalog + mock preview with
  `GET /parsers` + `POST /parsers/{id}/preview` — its mock contract equals the new real one, so
  this finally closes the "mock-only prototype" caveat. Grammar-component persistence unchanged.
- **Mock handlers**: new `parsers.handler` (catalog = builtins + xml; preview parity including
  404/422/caps) + align `components.handler`'s grammar preview; handler specs pin strictness
  (**a mock must never be more lenient than the server**).
- Specs + axe for every touched component.

## P5 — Docs, glossary, backlog (same change)

- New OKF concept `docs/okf/backend/engine/parser-plugins.md` (framework: two transparent
  engines behind one SPI, discovery, grammar, preview, tree-now/flatten-next; cross-link
  `plugins.md`); update `okf/frontend/features/onboarding.md`.
- `docs/GLOSSARY.md`: add **Grammar** (the authored, reusable parsing options for one file
  format, consumed by a Parser; persists as the `grammar` component kind) + touchpoint row.
- `docs/BACKLOG.md` §4 "Parsing (Stage-1)": flatten DSL is now ALSO the tree→segments ingest
  bridge for hierarchical parsers · asn-parser adoption prerequisites for the ASN shift (public
  facade; promote `RecordMapper` to public; declarative decode profile replacing
  `GoldenCapture.CASES`; coordinate cleanup) · drop-in `plugins/` dir (JobPackManager classloader
  precedent) · segments editor to unlock Custom Save · Parser-dialog convergence: DONE.
- `docs/INDEX.md` for the new OKF doc; persist this plan to `docs/superpower/`.

## Verification

1. Reactor gate per `build-verify`: `mvn -o clean test -Pedition-enterprise` — suffix-anchored
   module sum, confirm the NEW test classes appear in the log; baseline re-derived, never quoted.
2. UI gate: `lint:tokens` + full `ng test` (exit code) + production build.
3. Live: SMOKE-style boot of `ControlApi` → `GET /parsers` shows 5 entries (4 builtins + xml,
   xml `ingestable:false`), `POST /parsers/xml/preview` returns a tree for a nested sample,
   unknown id 404s, oversized sample 422s. Offline preview walk: onboarding type list served,
   XML tree + disabled-Save note, builtin flow unchanged; Parser dialog runs on the real contract
   (mock parity offline).
4. Commits per `release-workflow` (`feat(etl)`/`feat(engine)`/`feat(ui)`, master-only). ⚠ NO push
   without an explicit ask — 7 unpushed ASN.1 commits sit beneath master's HEAD.
