---
type: Concept
title: Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles — XML, ASN.1, drop-in jars, Decode Profiles and BER
description: Part 3 of 3 of Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles: XML and ASN.1 — hierarchical parsers that ingest; Drop-in parser jars — trust gate, staging dir, pack parsers, ingest and preview gate SHIPPED (2026-09-25); Decode Profile — one vendor's decode settings, kept once (2026-09-25, slices C1–C4); Deferred by decision (the trust design's open …
tags: [elt, engine, ingestion, spi, streaming, consignment, ingester, duckdb, seam, partitions, contract-test, plugins, segments, quarantine, route, branch, arming, fail-closed, lanes, addressing, revisions, watermark, selector, event-time, concurrency, scheduler, priority, backpressure, operations, parsing, grammar, csv, plugin, read-csv, frontend, options, asn1, xml, decode-profile]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-etl
---

# Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles — XML, ASN.1, drop-in jars, Decode Profiles and BER

Part 3 of 3 of [Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles](spec-parsing.md) · previous: [Format profiles, decision matrix and parser plugins](spec-parsing-profiles-plugins.md).

## P.10 XML and ASN.1 — hierarchical parsers that ingest

ASN.1 (the operator's target format) is **served as of 2026-07-31** via `Asn1ParserPlugin` — it appears in
the Onboarding Parsing stage's toggle and the Pipelines Parser dialog with zero UI change, exactly as
designed. ⚠ It was preview-only until 2026-07-31; it now names `com.gamma.telecom.asn1.Asn1RecordIngester`
via `ingesterClass()`, so `Parsers.ingestable()` (`Parsers.java`) is **true**.

### The tree→segments bridge — `XmlRecordIngester` (2026-08-30)

**This is what closed "Parsing Stage-1 (b)"**, the slice that kept every hierarchical parser
preview-only. There was never a structural blocker: `Parsers.ingestable()` is a *display* flag
derived from `ParserPlugin.ingesterClass()`, and nothing in the config, validation or ingest-dispatch
path ever consulted it. XML was preview-only because no ingester existed, full stop.

`com.gamma.ingester.XmlRecordIngester` (engine) loads through the same `parsing.plugin` machinery as
ASN.1 (`frontend: plugin` + `plugin.ingester`/`segments`/`ingester_config`) and follows its rules
verbatim — segment key = the record element's local name, `raw.fields[].selector` is a dotted path,
undeclared records are `sink.junk()`, a trailing derived `EVENT_TYPE` carries the segment key, and a
malformed document fails the file (`QUARANTINED_UNREADABLE`) rather than loading its prefix.

🔴 **The load-bearing decision is that preview and ingest share ONE walker**, `com.gamma.parse
.XmlRecordReader`. An operator authors a selector against the labels they saw in the preview tree; a
second StAX walker that labelled nodes even slightly differently would resolve those selectors to
`NULL` at load while the preview kept looking correct — a silent, per-column data loss with no error
anywhere. `XmlRecordIngesterTest.previewLabelsAreTheSelectorsThatResolve` pins this by asserting the
selector vocabulary against the *plugin's own* preview output, not a hand-written list.

The selector vocabulary is therefore the preview's labels: a child element by name, an attribute as
`@id`, and an element carrying both text and children as `#text`.

⚠ **A selector must name a leaf that occurs ONCE.** A container yields `NULL` (the ASN.1 rule), and
so does a step matching *repeated* sibling elements — XML has no first-one-wins rule that is not a
guess, and silently taking one of five `<line>` elements would be a lie dressed as a decoded value.
A repeated element is not a column; give it its own segment by naming it as the `record_element`.

**Several record kinds in one document** load by leaving `record_element` blank (every direct child
of the root is then a record) and declaring one segment per kind; an undeclared kind is junk, not a
silent drop. With an explicit `record_element` the other kinds are simply never records.

`ingester_config`: `record_element` · `namespace_aware` · `encoding` (`max_records` is preview-only
and ignored here).

⚠ **A plugin ingester with no segments is refused at CONFIG LOAD** (`PipelineConfigParser
.parsePlugin`), before the ingester is constructed — so the ingester's own segment guard is
unreachable through a loaded config and exists only for the public SPI's direct callers. A test that
tries to reach it through `PipelineConfig.load` is testing the parser, not the ingester.

### Loading to Tables — `Asn1RecordIngester` (2026-07-31)

ASN.1 loads through the **existing `parsing.plugin` machinery**, not a new path: `frontend: plugin`
+ `plugin.ingester` + `plugin.segments` + `plugin.ingester_config`. ⚠ `asn1` is a *catalog id for
preview/authoring*, **and also a first-class `parsing.frontend` value**: `PipelineConfigParser`'s
`FRONTENDS` set has ten tokens —
`{delimited, fixedwidth, fixed_width, json, text_regex, xlsx, excel, parquet, asn1, plugin}` — and
`frontend: asn1` synthesizes this exact plugin wiring (`parsePlugin`), refusing a co-present
`parsing.plugin` / `processing.ingester`. Writing the wiring out by hand under `frontend: plugin`
remains equivalent.

`com.gamma.telecom.asn1.Asn1RecordIngester` (module `inspecto-telecom-asn1`, package `com.gamma.telecom.asn1`, owned by that module alone):

- **Segment key = the decoded record's own name.** For the corpus's union-style vendor grammars
  (a SET/SEQUENCE whose tagged components are the record types) `SchemaBinder.bind` names a record
  by the *matched alternative* — e.g. `moCallRecord` — so that name is the segment key. A
  single-type grammar yields the root type name. An undeclared record type is `sink.junk()`,
  mirroring how `TypedRecordIngester` treats an unknown type prefix.
- **`raw.fields[].selector` is a DOTTED PATH**, not a positional index — the one real divergence
  from the text ingesters. `party.number` walks the `RecordMapper.toMap` record map.
- ⚠ **A selector must name a leaf.** A container (sub-record, or a repeated field's list) yields
  `NULL`, not a stringified subtree — deliberately matching the legacy transform engine, which also
  drops lists of scalars rather than inventing a join. A repeated field is not one column; give it
  its own segment — or, since 2026-10-12, a `derive` / `children` block (§P.13, D9).
- A trailing derived **`EVENT_TYPE`** column carries the segment key (the `TypedRecordIngester`
  convention), so schemas can partition by record type without redeclaring it.
- ⚠ **Any parse error fails the whole file** → `QUARANTINED_UNREADABLE`. These framings carry no
  length prefix, so `SKIP_RECORD` cannot resync; half-ingesting a CDR file is worse than
  quarantining it. Input is memory-mapped (`ByteSource.map`), so files >2 GB are fine.

`ingester_config`: `grammar` (path to the `.asn` module) or `grammar_text` (inline module; wins when
both are set) — one of the two required · `root_type` (required) ·
`strictness` · `file_header_length` · `record_header_length` · `max_value_bytes`.

### ASN.1 status items (tracked in BACKLOG §4 "Parsing (Stage-1)")

- ~~**Declarative decode profile — the grammar source.**~~ **CLOSED 2026-09-23.** Framing was served
  earlier; the grammar source is now too. *(This bullet said until 2026-09-23 that the plugin takes
  only pasted module text. That was already half-wrong — the ingester accepted a jailed `.asn` path
  under `ingester_config.grammar` all along — and is now wholly wrong: preview, `frontend: asn1` and
  the drawer all take `asn1.grammar_file`, see §P.9.)* A per-vendor module is stored as a `.asn` file in
  the Space's config (the corpus keeps them that way, e.g. `mtnOCC.asn`). One vendor's decode settings
  have a home since 2026-09-25: the **Decode Profile** (§P.12, [spec-parsing-decode-profile.md](spec-parsing-decode-profile.md)).
- ~~**The Maven coordinate split**~~ **RESOLVED 2026-08-01.** The root `pom.xml` now aggregates
  `providers/asn-parser/asn-decoders`, so `com.gamma.asn:asn-facade` resolves from the reactor and the manual
  `mvn install` is gone (verified with the local repo's `com/gamma/asn` deleted: 23 modules,
  asn-facade [7/23] before inspecto-engine, `mvn -o clean test` green, 2178 tests). Aggregation
  only — that tree keeps its own parent and inherits nothing from `inspecto-parent`. *(First
  documented as done 2026-07-31, but that pom edit was never committed — the `<modules>` entry was
  lost across a shift and re-landed 2026-08-01; a fresh `~/.m2` still needed the manual install in
  between.)* The OLD `asn-parser-v2:1.2.1`
  (`providers/asn-parser/pom.xml`) is **deleted**: zero consumers, and its parent
  `com.gamma.asn.decoders:asn-decoders:1.1.3-dev` existed nowhere, so it could not build.
  ⚠ **`providers/asn-parser/src/main/java` survives the deletion and must not be cleaned up as an orphan** —
  `legacy-code/pom.xml` compiles it via `<sourceDirectory>../../src/main/java</sourceDirectory>`
  (45 files, confirmed in the build log). It retires with `legacy-code` after Phase 4.
- ⚠ **`providers/asn-parser/src/test/` is a tree of manual scratch programs, NOT tests** (operator decision
  2026-09-24, `LEGACY-ASN-SRC-TREE-UNBUILT-1`: rename, don't wire). No `testSourceDirectory` points
  at it, so its 21 files compile on no build path. The six that carried `*Test` names were renamed
  to the `*Harness` convention so nothing claims coverage it lacks: `Test` → `SchemaCsvHarness`,
  `ASNFileReaderTest` → `ASNFileReaderHarness`, `BERDecoderTest` → `BERDecoderHarness`,
  `SbinHuaMscAsnTest` → `SbinHuaMscAsnHarness`, `FixedLengthFileReaderTest` →
  `FixedLengthFileReaderHarness`, `RTDMS_ASN_Test` → `RTDMS_ASN_Harness`. `asn-golden`'s
  `GoldenCapture` cites `RTDMS_ASN_Harness.<method>` as provenance for 7 of its 9 golden cases —
  rename that file again only together with those comments. Real ASN.1 coverage lives in the
  `providers/asn-parser/asn-decoders` modules.
- ⚠ **Corpus-backed tests are opt-in AND data-gated** (DATA-GOV-1). `RealGrammarsTest` (asn-schema)
  and `ParityCheckTest` (asn-golden) `assumeTrue` on **both** `-Dasn.corpus.tests=true` **and** the
  operator data being present on disk, so by default — and on any corpus-less checkout, including a
  `git worktree` (the corpus is gitignored, so worktrees never receive it) — they SKIP and the build
  is green; *skipped* is the expected state, not a regression. Exercise them where the corpus lives
  (the main checkout) with `mvn test -Dasn.corpus.tests=true`. The property alone does nothing
  without the data; the data alone no longer runs them without the property (2026-08-01).
- **Drop-in parser jars** and the **segments editor** (unlock guided Save for ingestable custom parsers)
  — apply to any custom parser, not ASN.1-specific. The drop-in half's design is
  [`parser-plugins-trust-design.md`](../../../../archived-documents/plans-archive/parser-plugins-trust-design.md) (every slice
  shipped 2026-09-25). What shipped is §P.11; this page is now the current knowledge, the design is provenance.

---

## P.11 Drop-in parser jars — trust gate, staging dir, pack parsers, ingest and preview gate SHIPPED (2026-09-25)

Operator decisions 2026-09-25: **D1** a parser arrives as a **fifth Job Pack kind** through the existing
`JobPackManager` loader (`-Djobs.packs.dir`). There is no second `plugins/` directory. **D2** T1 SHA-256
allowlist is required; signer anchoring (T2) is not built. **D3** fail closed for Job Packs too. **D4**
`POST /parsers/{id}/preview` needs the Pipeline-authoring capability for pack parsers only. **D7** no
edition gate. **D8** no out-of-process host.

**As built (slice P1, the gate every pack kind now passes):**
- `-Djobs.packs.allowlist=<file>` (`PackAllowlist`, `inspecto-engine` `com.gamma.job`). Format: one
  `<64-hex sha256>  <file>  [note]` per line, so `sha256sum` output works as-is. Only the hash decides.
  Blank lines and `#` lines are skipped.
- **Fail closed:** packs dir set with no allowlist ⇒ **every** jar is refused, cause `not trusted: no
  jobs.packs.allowlist configured`. An unreadable file, or a single malformed line, refuses every jar
  (the cause names the file or the line). An unlisted hash is refused with `not trusted: sha256 <hash> is
  not in jobs.packs.allowlist`.
- **Verify before load, no TOCTOU:** the hash that is checked is the SHA-256 of the **staged copy the
  `URLClassLoader` reads**. It is taken after staging and before any class is defined. That is the same
  staged-bytes rule the earlier verify/load TOCTOU fix (`f90ddcf25`) set up for the signature check.
  `JobPackTrustTest` pins both swap windows. A jar swapped before staging is refused under its own hash.
  A jar swapped after staging loads only the vetted bytes, and is then refused on the next rescan.
- **Rescan re-reads the allowlist.** Approve = add the line and rescan (`POST /jobs/packs/rescan` or any
  change in the packs dir). Revoke = remove the line and rescan, which **unloads** the loaded pack and then
  refuses it.
- **A3, boot refusal:** the constructor throws, so the Space does not load, when the allowlist lies inside
  the packs dir or under `assist.write.root`, `spaces.root` or any `PathJail.allowedRoots()` root. By
  design there is no API or UI route that approves a jar.
- **Signals / log / inventory:** each refusal emits `job.pack.rejected {file, hash, cause}` and a WARN
  `[PACKS] rejected …` line. `GET /jobs/packs` lists refused jars as `{file, hash, state: "rejected",
  cause}` next to the `state: "loaded"` rows, and a row is dropped when its jar leaves the dir. The
  startup log line names the allowlist, or says it is `NOT CONFIGURED`.
- `-Djobs.packs.requireSignature` is unchanged. It is an **integrity** check on top of the allowlist, and
  it still never looks at who signed.

**As built (slice P2, a parser is the fifth pack kind):**
- `JobPackManager.load` runs a fifth `ServiceLoader` loop for `ParserPlugin` over the pack's loader, keeping
  only providers that loader defined. A pack carrying **only** a parser is valid. Its parsers register
  through `Parsers.register(p, <jar filename>)` after the other four kinds.
- **Atomic refusal.** A parser id that a built-in or classpath parser holds, that another pack owns, or
  whose ingester class another registered parser already names, throws inside `load`. The catch
  deregisters **all five** kinds, so the pack is rejected whole: its Job Types, tokens, node types and
  executors never stay registered. The cause names the id (`job.pack.rejected`, `GET /jobs/packs`).
- **Unload / revoke** (`unload`: jar removed, hash changed, or hash no longer listed) calls
  `Parsers.deregister(owner)`. The parser leaves `GET /parsers` and `POST /parsers/{id}/preview` 404s.
- **Provenance.** `GET /parsers` rows carry `source: "pack:<jar filename>"` (the Job Type vocabulary). The
  pack's manifest id/version stay in `GET /jobs/packs`.
- ⚠ **One process-wide registry, one packs dir.** `-Djobs.packs.dir` is JVM-wide and every Space's
  `JobService` builds a manager over it, so `register` under the **same** owner replaces rather than
  refuses. Consequence (shared with the node-type overlay): one Space's unload deregisters the parser for
  all of them. A pack is normally replaced, not removed.
- Proof: `JobPackParserTest` (real jars compiled at test time, off the classpath: parser-only pack loads
  with `pack:` provenance; jar removal and hash revocation unregister; a pack whose parser is `delimited`
  is rejected and its Job Type rolled back, paired with the same mixed pack under a free id loading both
  kinds; a second pack claiming a taken id is rejected and the first keeps it). `ParsersTest` pins the
  overlay rules; `ControlApiParsersTest.catalogNamesEachParsersProvenanceIncludingAPacksParser` the wire.

**As built (slice P3, a pack parser ingests):**
- **One resolver**, `com.gamma.inspector.PluginIngesters.open(cfg)`, replaced the two one-argument
  `Class.forName(ingesterClass)` sites (`GenerationModeIngester`, `UnionModeIngester`). They now receive the
  ingester from `StreamingPluginIngestStrategy`. The Pipeline still stores only the FQCN
  (`parsing.plugin.ingester`). The resolver finds the registered parser naming that class
  (`Parsers.forIngester`). A pack-owned one is loaded through **the parser's own class loader**. Anything
  else resolves on the engine loader, as before.
- **The pack is pinned for the whole batch.** `PackRunLeases.acquire(owner)` is the same per-owner counter
  a Job Run and a pipeline-graph walk take, released when the strategy's `try` closes. An unload or
  revocation mid-ingest deregisters the parser at once, but the loader close is **deferred** until the
  ingest ends, so the batch finishes on the vetted bytes it started with. The resolver re-checks the
  registration after taking the pin, so an unload that won the race is reported, not half-used.
- **Named failure.** A Pipeline whose ingester no loaded parser names and the engine loader cannot find
  throws `IllegalStateException` "streaming ingester <fqcn> is not on the classpath and no loaded parser
  names it; if a Job Pack provided it, that pack is not loaded (removed, revoked or refused, see GET
  /jobs/packs)". There is no `ClassNotFoundException` in the cause chain. It fails the Run the same way the
  old "Cannot instantiate" error did (out of `ConsignmentIngestor.process`). That message remains for a
  class that is found but cannot be constructed.
- Proof: `JobPackParserTest` — a pack ingester ingests two CALL rows end to end, paired with the probe that
  `Class.forName(fqcn)` on the engine loader throws `ClassNotFoundException`. An unload while the ingester
  is blocked leaves the pack draining until the ingest ends. **Mutation-checked**: with the pin removed,
  that test goes red on the `isDraining` assertion. A Pipeline naming an unloaded pack's ingester gets the
  named error.

**As built (slice P4, D4 preview gate):**
- `ParserRoutes.preview` checks `Parsers.ownerOf(id)` after the 404 and, for a pack parser, calls
  `ApiContext.requireCapability(ex, "canAuthorWorkbench")` (a literal, like every gate) **before** any pack
  code runs. `canAuthorWorkbench` is the capability that authors Pipelines (`PUT /pipelines/{id}/graph`),
  so previewing third-party code over caller-chosen bytes has the same bar as configuring its ingest.
  Built-in and classpath parsers stay open.
- **Recorded, not silent.** The gate depends on the parser, and the manifest can only say all-or-nothing
  per route, so the route stays in `CapabilityManifest.EXEMPTIONS`. Its category is the new
  **`provenance-gated`**, and the reason names the in-handler gate. That is the same shape as `POST /spaces`
  (`recovery-route`). The route-gating evidence (`compliance/evidence/route-gating.md`) shows that category.
- ⚠ Like every gate, it is a **no-op without a Subject** (Personal). D7 (no edition gate) means the trust
  gate itself is identical in every edition; this preview gate only bites where an authenticator runs.
- Proof (real HTTP, forced authenticator): `ControlApiPackParserPreviewTest` — a viewer gets 403 and the
  pack's `preview` is never called; the same request as an author gets 200; the same viewer previews
  `delimited`; an unknown id is still 404; no authenticator ⇒ 200. The refusal test was red before the gate.

**As built (slice P0, where the loaded bytes live):**
- The staged copy is what the `URLClassLoader` reads **and** what the allowlist hash is taken of, so it no
  longer sits in the system temp dir, which any local process can write. `JobService` passes
  `JobPackManager.stagingRootFor(auditDir)`: `-Djobs.packs.stagingDir` when set, else
  `<auditDir>/job-packs-staging` (the Space's own server-written state dir). Each manager creates one
  unique owner-only `job-packs-*` dir inside it, so Spaces sharing a JVM-wide root never delete each
  other's copies.
- **A staging root inside the packs dir refuses boot** (`IllegalStateException` naming both paths): a
  packs-dir writer could otherwise rewrite staged bytes after they were hashed.
- The 4- and 5-argument constructors are test conveniences that stage in the sibling `<packs dir>.staging`.
  Production never uses them.
- **Orphaned staging dirs are swept at the next use (2026-10-06).** Each manager holds an OS file lock on
  the sibling `<job-packs-*>.lock` for its whole life; the OS drops it when the process dies. Before a
  manager creates its own dir, `JobPackManager.sweepOrphans(root)` removes a `job-packs-*` dir only when
  that lock can be taken now — so a live owner (this JVM or another process) is never touched. A dir with
  **no** lock file (made before this change, or a live manager mid-creation) cannot be proven orphaned
  and is **kept** — delete those by hand. Containment: a symlink entry is skipped (`NOFOLLOW_LINKS`); a
  dir whose real path is not `<real root>/<name>` (a Windows junction) is skipped; only regular files
  directly inside are deleted, never followed or recursed, and a dir holding anything else stays.
  Proof: `JobPackStagingSweepTest` (orphan removed with its lock; in-JVM lock kept; a lock held by a
  **child process** kept, then swept once it exits; no-lock dir kept; symlink/junction escape untouched;
  a second manager sweeps a crashed dir but not the first manager's). **Mutation-checked**: ignoring the
  lock turns three tests red; dropping the `NOFOLLOW` and real-path checks turns the junction test red.
- Proof: `JobPackStagingTest` (the copy's parent is the given root; a root inside the packs dir refuses
  boot; the root is the audit dir unless overridden). **Mutation-checked**: back on `createTempDirectory`,
  the first test goes red on the parent assertion.

---

## P.12 Decode Profile — one vendor's decode settings, kept once (2026-09-25, slices C1–C4)

Moved to its own page: **[spec-parsing-decode-profile.md](spec-parsing-decode-profile.md)** — the operator decisions, how a Pipeline's keys win over the profile, bundle export, the effective-value read-back and its tests.

---

## P.13 Deferred by decision (the trust design's open items)

- **D8 — out-of-process parser host (T5).** The only real containment for parser code, deferred until a
  customer requires running parser code nobody has reviewed. Classloader isolation is not a security claim.
- **D9 — tree operations on repeated children.** **Measured 2026-10-11** (main checkout, read-only; DATA-GOV-1
  respected: grammars, tx configs and legacy code only, no captures read). 24 of the 31 operator ASN.1 grammars
  (gitignored `providers/asn-parser/config/`) parse; they hold 1266 repeated-child occurrences (595 distinct
  element types). In the grammars that keep OPTIONAL, 39% of occurrences have a mandatory component; 7 grammars
  have OPTIONAL stripped, so the grammar alone cannot say. **Prevalence is many.** Operator use settles it:
  every vendor family with a real legacy tx config (Ericsson OCC/CCN, GGSN/PGW, Huawei GMSC, Huawei/Aftel IMS,
  TAP, NRTRDE) derives core columns from a repeated child (subscriber identity ← `servedSubscriptionIDs`,
  DA charge ← `dedicatedAccounts`, volume / duration ← `usedServiceUnits`, PGW billable volume ←
  `listOfServiceData` group-by + sum) or runs flatten / reduce / delimit on one (23 directive uses in 9 configs).
  ⚠ "Give it its own segment" is **not available for ASN.1 today**: `Asn1RecordIngester` keys segments by record
  name only (~:124) and NULLs any `List` (~:189), so those columns load NULL. The "cheaper before C1" window has
  passed (C1–C4 shipped 2026-09-25). The design states no numeric threshold. **Operator decided 2026-10-11 to design it —
  ✅ designed and operator-approved 2026-10-11; slices 1, 2 and 3 SHIPPED** (`derive` select / reduce, `children` explode, drawer list):
  [`docs/archived-documents/plans-archive/parsing-repeated-child-design.md`](../../../../archived-documents/plans-archive/parsing-repeated-child-design.md).
  - **`derive` (slice 1).** An optional `derive` block under `asn1:` (segment key → column → operation; closed vocabulary, no expression)
    is compiled by `com.gamma.etl.DeriveSpec` (validated on `DecodeProfile.read` and at config load, `ASN1-DERIVE-*`
    codes; replaced whole on overlay) and evaluated per record by `Asn1RecordIngester`: VARCHAR derived columns
    after `raw.fields`, before `EVENT_TYPE`; NULL for missing path / empty list / no match / non-numeric (never 0);
    row cardinality unchanged; no `derive` = byte-identical rows. ⚠ As-built gotchas: a `SEQUENCE OF X` decodes as
    `{field: {X: [..]}}`, so the list path is `field.X` (explicit, no auto-unwrap); TOON has no flow syntax, so
    write `where:` as a nested block. `max_list_elements` (default 10 000,
    ceiling 1 000 000) over-cap fails the file. **`group_by` was REMOVED from the `derive` vocabulary 2026-10-12**
    (operator D6): it never changed the scalar; a config that still names it is refused with
    `ASN1-DERIVE-UNKNOWN-OP` ("group_by was removed ... use a children explode"). One row per group is the explode.
    `select` equality matches the **stringified** leaf (numbers and enum names alike). The per-file counters
    `derive_non_numeric` / `derive_no_match` are logged at INFO, not written to a sink summary.
  - **`children` (slice 2, SHIPPED 2026-10-12)** — `children:` (parent segment key → list path → child segment key; validated by
    `com.gamma.etl.ChildSpec` on `DecodeProfile.read` and at load; replaced whole on overlay): each element of the
    list becomes a row of a DECLARED child segment, besides the record's own row. The child schema's selectors
    resolve relative to the child element, plus `$index` (0-based, as a string) and `$parent.<dotted leaf>`; no
    engine surrogate key (the parent leaf is the join key); any other `$...` selector is refused
    (`ASN1-CHILD-BAD-SELECTOR`). Codes: `ASN1-CHILD-UNKNOWN-SEGMENT` (parent or child not in `segments`),
    `-BAD-PATH`, `-SELF`, `-NESTED` (explode is one level), `-LIMIT`; runtime `ASN1-CHILD-TOO-MANY-ROWS` fails the
    file (never truncates) when one record explodes past `max_child_rows_per_record` (default 10 000, ceiling
    1 000 000, summed over a record's lists). A child segment is not matched by record name (a record carrying its
    name is junk); a `derive` on a child segment runs over the child element. TOON accepts the dotted list path as
    a map key (`usage.usage: Usage`). Tests: `ChildSpecTest`, `Asn1RecordIngesterChildTest` over the synthetic
    `repeated_child` case, and over `repeated_child_nested` (the child element holds a nested list, so a child `derive` yields real sum / count / first values, `PARSING-CHILD-DERIVE-CORPUS-1`).
  - **Slice 3 SHIPPED 2026-10-12** — the Parse drawer lists the effective `derive` read-only
    ("N derived columns on M segments", segment / column / operation; the Pipeline's own `asn1.derive` wins whole
    over the profile's). **Preview rows SHIPPED 2026-10-12** (`PARSING-PREVIEW-DERIVED-1`): the derived columns are
    served beside the record Tree as `ParseResult.Rows` (see `previewRows`, §P.9) and shown as a drawer table.
  - **Still open (deferred by decision, design archived 2026-10-22):** the counters channel stays log-only (operator D5:
    `RecordSink` is not widened); `ASN1-DERIVE-NOT-IN-GRAMMAR` (a list path that is not a `SEQUENCE OF` in the
    compiled grammar) is not implemented — it needs a walk over the compiled grammar's schema, and since 7 of 24
    grammars have OPTIONAL stripped it could check path existence only, never mandatory-ness; slice 4, an optional
    profile editor for `derive` / `children`, is operator-gated (the drawer stays read-only; row
    `PARSING-DERIVE-EDITOR-1`).
- **T2 — signer anchoring** (`-Djobs.packs.trustStore`, SEC-7). Not built. D2 made T1 the required gate.

---

## P.14 BER hostile-input handling — fixed 2026-09-17, value cap 2026-09-24

✅ **`BER-LENGTH-OVERFLOW-1` (P1) is closed.** A long-form 8-byte length of `Long.MAX_VALUE` made
`valueOffset + valueLength` wrap negative, so the `end > limit` guard passed and `BerReader` returned a Tlv
with a **negative `endOffset`** that `RecordReader` counted as `recordsOk++` and used as its cursor — a
malformed record recorded as successfully parsed. Both sites now compare against a remaining-bytes budget
(`valueLength > limit - valueOffset`), where both operands are non-negative and the subtraction cannot
overflow. ✅ **`BER-FRAMING-UNCHECKED-READ-1` too**: `Framing.Fixed.recordLength` bounds its header read, and
`RecordReader` takes the framing calls inside the recovery `try`.

⚠ **A truncated TAIL reports `ParseError(STOP_FILE)` even under `RecoveryPolicy.SKIP_RECORD`** — a header that
cannot be read yields no boundary to resync to. Records already read are still delivered.

✅ **`BER-VALID-BUT-HUGE-ALLOCATION-1` is closed (2026-09-24) with a configurable single-value cap** —
operator decision: a cap, not a streaming accessor, so `Tlv.value()` keeps its `byte[]` return type. The
hazard was a length that is *valid* but enormous: measured with a 3 GB stub source, `04 84 95 02 F9 00`
parsed cleanly, and just under 2 GB `Tlv.value()` would have allocated a `byte[]` sized by
attacker-controlled input. No bounds check can reject that, because the length is legitimate.

- **Where it lives:** `BerReader.read(src, offset, limit, strictness, maxValueBytes)` refuses a
  **primitive** value whose declared length exceeds the cap, as a `BerParseException`:
  `value of [APPLICATION 3] declares 5 bytes, over the max_value_bytes cap of 4 (at offset 2)`. The check runs
  at parse time, after the in-bounds check (a truncated value still reports truncation), so no Tlv exists
  for anything to allocate from. The cap passes through `RecordReader` (6-arg constructor) and
  `Asn1Decoder.decode(…, maxValueBytes)`. The old signatures still exist and use the default. A
  refusal is an ordinary record failure: it reaches the `ErrorListener` as a `ParseError`, and
  `SKIP_RECORD` skips a length-prefixed record the way it skips any other bad record.
- **Constructed values are not capped.** Their length only bounds where children are parsed, and no
  decoder copies a constructed value. A record far larger than the cap decodes as long as each leaf is under it.
- **Default `BerReader.DEFAULT_MAX_VALUE_BYTES` = 64 MiB.** Measured 2026-09-24 over every shipped
  sample (`MSC01_20260801_0800.ber`, `CDR_20260801.ber`, `corpus-synthetic`) and the operator corpus
  (Huawei IMS/MSC, Ericsson CCN/OCC/SDP, IMS and SGSN CDRs): the largest primitive value is **128 bytes** and
  the largest record about 9 KB. 64 MiB therefore refuses no real file and still caps the worst case at an
  allocation any JVM that runs the engine can afford.
- **Pipeline config:** `asn1.max_value_bytes` (a served `grammarSchema` field, so the preview and the Parse
  drawer honour it) and `ingester_config.max_value_bytes`. `frontend: asn1` carries it across the same way
  as the framing knobs (`PipelineConfigParser.asn1PluginBlock`). One parser, `Asn1ParserPlugin.maxValueBytes`,
  serves both spellings, so the cap a preview uses is the cap an ingest uses. Unset or blank means the
  default. Anything that is not a whole number ≥ 1 is a config error naming the key. Because of the
  *any parse error fails the file* behaviour (§P.10), an over-cap value quarantines the file `QUARANTINED_UNREADABLE`.
- Not a `ConfigSpecs` / accepted-key census entry: `ConfigSpecs` declares no `parsing.asn1.*` key,
  and the pipeline census stops at the top-level `parsing` block. The parser's served
  `grammarSchema` is the declaration for `asn1.*`.

---

## References

- [architecture-elt.md](architecture-elt.md) — engine architecture, the M..N multiplexer, back-pressure, the
  Consignment status flow, the format-specific configuration principle; acquisition (Collectors, fetch,
  discovery — getting files to the inbox)
- [spec-pipeline-execution.md](spec-pipeline-execution.md) — [node types, the token model, transform seams](spec-pipeline-execution.md) ·
  [execution lanes, Build→Test→Run containment, post-sync step chains](spec-pipeline-execution.md) ·
  [the graph-lane sink kinds; quarantine](spec-sinks.md)
- [spec-db-layer.md §3.9](../engine/spec-db-layer.md) — the catalog's DDL and its two readers
- [operations-reference.md](../../layer/platform/admin-guide-operations-reference.md) — `retire_superseded`
- [HA/DR drill runbook](../../layer/platform/admin-guide-ha-dr-drill.md)
- [admin-guide-configuration-reference.md](../config/admin-guide-configuration-reference.md) — every config key, incl. the fixed-width frontend
- [user-guide-grammar-config.md](../config/user-guide-grammar-config.md) — authoring Grammars, the Parse drawer
- [spec-component-registry.md](../processor/spec-component-registry.md) — Grammar Templates as components
- [ELT plan](plan-elt.md) — the ING capability spec (requirements of record)
- Archived designs: [delimited-grammar-design.md](../../../../archived-documents/plans-archive/delimited-grammar-design.md) ·
  [parser-plugins-trust-design.md](../../../../archived-documents/plans-archive/parser-plugins-trust-design.md) ·
  [parsing-repeated-child-design.md](../../../../archived-documents/plans-archive/parsing-repeated-child-design.md)
- [GLOSSARY.md](../../../../GLOSSARY.md) §2 *Consignment*, §5 *Parser*, *Grammar*, *Grammar Template*, *Decode Profile*, §6-B
