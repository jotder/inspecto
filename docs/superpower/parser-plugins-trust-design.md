# Design: parser plugins without a rebuild — trust model + the per-vendor decode profile

> **State, 2026-09-25: slices P1 (the T1 trust gate), P2 (pack parser registration) and P3 (pack
> ingester resolution + ingest-time pin) SHIPPED. P0 is half done (its staged-bytes fix is `f90ddcf25`;
> the server-owned staging dir is still open). P4, P5 and C1–C4 are not built.** Operator decisions 2026-09-25 (all ten answered): D1 = fifth Job Pack kind, D2 = T1
> required (T2 not built), D3 = refuse, D4 = the Pipeline-authoring capability for pack parsers only,
> D7 = no edition gate, D8 = defer. As-built facts are in the owner concept's *Drop-in parser jars* section.
>
> *Original header:* **State: DESIGN ONLY, 2026-09-24 — nothing built.** Owner concept:
> [`okf/backend/engine/parser-plugins.md`](../okf/backend/engine/parser-plugins.md). BACKLOG row:
> §3.3 "Parsing (Stage-1)". Grounded against master at `7bb36309c`; every `file:line` below was read on
> that commit. Decisions owed are in §7 — nothing in §5 starts before its decision is signed.

The row has two open halves, and they are **independent**:

1. **A per-vendor transform config home** — its prerequisite (a stored, path-jailed `.asn` grammar file)
   shipped 2026-09-23. It needs no trust decision and can ship first.
2. **A drop-in jar directory** so a customer parser deploys without a rebuild. This one loads
   operator-supplied bytecode into the server JVM, and needs a trust decision first.

---

## 1. As-built baseline (what the code does today)

### 1.1 Parser discovery is classpath-only and frozen at class-load

- `Parsers.REGISTRY` is a `static final` map built once (`inspecto-engine/src/main/java/com/gamma/parse/Parsers.java:28`)
  from the six built-ins plus `ServiceLoader.load(ParserPlugin.class)` on the **application** loader (`:35`).
  Duplicate ids and invalid ids throw at class-init (`:37-45`). There is no `register`/`deregister`, so a
  parser cannot arrive after boot, from any directory.
- A plugin parser loads rows by naming a `StreamingFileIngester` FQCN (`ParserPlugin.ingesterClass()`,
  `ParserPlugin.java:100`), which the Pipeline stores as `parsing.plugin.ingester`
  (`inspecto-etl/src/main/java/com/gamma/etl/PipelineConfigParser.java:1142`). The ingest engine then does
  `Class.forName(cfg.schemas().ingesterClass())` — the one-argument form, i.e. the **engine's own loader**
  (`inspecto-engine/src/main/java/com/gamma/inspector/GenerationModeIngester.java:150`,
  `UnionModeIngester.java:187`). ⚠ **A class living in a pack loader is invisible there**, so making the
  catalog dynamic alone would give a parser that previews and then fails at its first ingest.
- `POST /parsers/{id}/preview` is exempt from capability gating as read-shaped
  (`inspecto/src/main/java/com/gamma/control/CapabilityManifest.java:307`); it calls the plugin's
  `preview(...)` over caller-supplied bytes (`ParserRoutes.java:36,61`). Today that is only first-party
  code. A pack parser would make it **third-party code over caller-supplied bytes, reachable by any
  authenticated user**.

### 1.2 The precedent: Job Packs (`JobPackManager`)

- **Off unless `-Djobs.packs.dir` is set** (`inspecto-engine/src/main/java/com/gamma/job/JobPackManager.java:128,137`;
  wired at `JobService.java:367`). Every `*.jar` in the directory is a candidate (`:168`).
- **One `URLClassLoader` per jar, parent = the engine loader** (`:200`) — parent-first, so SPI/API types
  resolve from the engine and pack-private shaded deps stay private. Each of the four `ServiceLoader`
  loops keeps only providers whose class came from *this* loader (`:203-220`).
- **All-or-nothing registration**, owner-keyed by jar filename, rolled back across all four registries on
  any failure, `LinkageError` included (`:239-248`, `:265-275`). The owner-keyed overlay pattern is
  `PipelineNodeTypes.register/deregister` (`inspecto-engine/src/main/java/com/gamma/pipeline/PipelineNodeTypes.java:69,82`):
  built-in collision refused, first pack wins.
- **Content hash = SHA-256 of the jar** (`:440-450`), used as the reload trigger and audit fingerprint,
  surfaced by `GET /jobs/packs` (`:381-385`, `JobRoutes.java:83`). `POST /jobs/packs/rescan` is gated
  `canOperateRuns` (`CapabilityManifest.java:134`, `JobRoutes.java:84`).
- **Signature: integrity only, no trust anchor.** `-Djobs.packs.requireSignature` (default off, `:133`)
  makes `verifySignature` reject a jar with any unsigned non-`META-INF` entry (`:468-490`). It **never
  looks at who signed** — a self-signed certificate minted a minute ago passes. The class Javadoc says so:
  matching the signer against `-Djobs.packs.trustStore` "is the SEC-7 sign-off gate and is not yet
  enforced" (`:61-63`, `:465-466`). No allowlist exists.
- **Unload quiesce covers Job Runs only.** `acquireRun`/`releaseRun` (`:313-332`) are called from
  `JobService.runJob` (`JobService.java:1348,1358`); nothing pins a pack during a Pipeline ingest.

🔴 **Found while grounding — a TOCTOU in the existing loader.** `rescan` hashes the *watched* jar
(`:182`); `load` then verifies the signature of the *watched* jar (`:198`) and only afterwards copies it to
the staging dir (`:199`, `stage` at `:432-437`), and the loader reads the copy. A jar replaced between those
reads is loaded with bytes that were **neither hashed nor verified**. Harmless while the hash is only a
reload trigger; **fatal the moment the hash becomes the trust decision** (option T1 below). Slice P0 fixes
it before anything relies on it. (The staging dir is also `Files.createTempDirectory` under the system
temp, `:433` — a second, weaker writer path to the bytes that are actually loaded.)

### 1.3 Edition gating and path jailing

- `EditionFeatures` gates *use* of code that ships in every build: a feature is present when an
  `EditionFeatureProvider` on the classpath names it, absent otherwise, and an unloadable provider reads as
  absent (`inspecto-etl/src/main/java/com/gamma/etl/EditionFeatures.java:53-76,130-138`). Job Packs are
  **not** gated today; the EDITIONS board marks custom plugin ingesters (`SP-PRS-08`) ✅ in all three
  editions (`docs/EDITIONS.md:240`).
- `PathJail.resolveConfigRef` resolves a config's reference to another config file beside the referring
  file (`inspecto-config/src/main/java/com/gamma/config/safety/PathJail.java:193`); `requireUnderAny` +
  `allowedRoots()` is the jail (`:119,130`). The grammar file uses exactly that pair, resolved in the
  parser (`PipelineConfigParser.java:1157`) and jailed once in `Asn1GrammarSource` (`Asn1GrammarSource.java:76`).

### 1.4 The legacy per-vendor transform config (what "a home for it" has to absorb)

The pre-Pipeline decoder took one **JSON file per vendor** (`asn-parser/src/main/java/com/gamma/skybase/transformer2/Transformer.java:25-32`)
that did three different jobs:

| Job | Legacy directive | Today's home |
|---|---|---|
| Bind a record kind to an output and pick leaves | `@keepSource`, field paths (`:93-112`) | ✅ `asn1.segments` → segment schema files with dotted `raw.fields[].selector` (parser-plugins.md, *Loading to Tables*) |
| Per-field casts and derived columns | `@transform`, `@derivedFields`, reflective `invokeDynamic` over `TransformUtils` (`:476-506`) | ✅ the segment schema / projection slot (see `record-transformer-replaces-map-plan.md`) |
| Tree operations on **repeated** children | `flattenList` (`:508`), `reduce` (`:540`), `cartesianJoin` (`:606`) | ❌ **none** — a repeated field yields `NULL` by design; "give it its own segment" |

So the missing *home* is not a transform language — two of the three jobs already have one. What is
missing is **a place to keep one vendor's decode settings once** so N Pipelines (one per switch of the same
vendor) share them. Today every Pipeline repeats `grammar_file`, `root_type`, `strictness`, framing lengths,
`max_value_bytes` and the whole `segments` map (see `spaces/demo/config/msc/msc_cdr_pipeline.toon`,
`parsing.asn1`). The third job (tree operations) is a separate, measured decision (D9).

---

## 2. Threat model — a drop-in parser jar

**Asset under attack: the server JVM.** A pack class runs with the full authority of the Inspecto process:
every Space's data, the operational DB credentials, the IAM client secret, the keystores, outbound network,
`System.exit`, and reflection into engine internals. There is **no in-JVM sandbox to fall back on** —
the Security Manager is permanently disabled from JDK 24 (JEP 486) and this build targets JDK 27. ⇒
**Loading a jar is granting remote code execution to whoever controlled its bytes.** Every option below is
about *who* may do that and *how it is audited*, never about containing the code afterwards.

| # | Threat | Vector | Today (Job Packs) |
|---|---|---|---|
| A1 | A hostile jar is loaded | Anyone who can write into the packs dir: a compromised deploy account, a writable shared volume, a mis-scoped container mount | Loaded — no allowlist, no signer check |
| A2 | A trusted jar is swapped after vetting | Replace the file; the watcher reloads it on change | Reloaded by hash change (`:184-187`); TOCTOU of §1.2 |
| A3 | The trust list itself is edited | If the allowlist lived in an API-writable config, a config writer + a dir writer = RCE | n/a (no list) |
| A4 | A trusted parser is fed hostile bytes | `POST /parsers/{id}/preview` with any sample, any authenticated user; every inbox file at ingest | n/a for packs; first-party parsers already hardened (BER caps, XXE off) |
| A5 | A pack shadows engine behaviour | Register an id a built-in or another pack owns; ship a class in an engine package | Id collisions refused; parent-first loading means an engine class always wins over a same-named pack class |
| A6 | A pack pulls the rug mid-run | Unload/reload while an ingest is executing pack code | Only Job Runs are pinned (§1.2) |
| A7 | Denial of service | A parser that loops, allocates without bound, or never returns | Nothing bounds pack code; a `LinkageError` is contained (`:265`) but a hang is not |

**Classloader isolation limits — stated so nobody over-reads it.** A per-jar `URLClassLoader` isolates
*dependency versions* and gives a clean unload. It does **not** isolate privilege, memory, threads, CPU, the
filesystem or the network, and it cannot stop reflection into engine classes the parent loader exposes.
The only real containment is **out of process** (a separate JVM or container running the parser over a
pipe). That is a much larger design and is deliberately out of scope (D8).

**What an authorisation decision can buy, therefore:** that only bytes an operator deliberately approved
are loaded (A1, A2, A3), that the approval is auditable, and that the preview surface is not wider than
the ingest surface (A4). A5 and A6 are correctness work, not trust.

---

## 3. Trust options

| Option | Mechanism | Stops | Cost / weakness |
|---|---|---|---|
| **T0 — off by default** (today) | No `-Djobs.packs.dir` ⇒ no dynamic code at all | Everything, by not having the feature | Customer parsers need a rebuild — the thing the row exists to end |
| **T1 — SHA-256 allowlist** | An operator-owned file of `sha256  filename  note` lines; a jar loads only if the hash **of the staged bytes** is listed | A1, A2 (a swap changes the hash), A3 if the file sits outside every API write root | One edit per release of the jar; no statement about *who built* it |
| **T2 — signer anchored to an operator keystore** (the SEC-7 gate) | `-Djobs.packs.trustStore` holds the accepted signing certificates; every non-`META-INF` entry must be signed by one of them (extends `verifySignature`, `:468`) | A1, A3; A2 only if the attacker lacks the key | Key management, expiry/revocation; any jar the key ever signed stays loadable — including old vulnerable ones |
| **T3 — T1 + T2 (both must pass)** | Signed by an anchor **and** hash-listed | The union | Two operator steps per release |
| **T4 — edition gate** | An `EditionFeatures` feature (e.g. `parser.packs`) required to load a pack | Nothing an attacker does — it is a **licensing** control | Makes a security feature look like a paid tier |
| **T5 — out-of-process parser host** | Parser runs in a child JVM/container, bytes over a pipe | A7 and most of the RCE blast radius | A new runtime, IPC contract, lifecycle; weeks, not days |

**Recommendation: T1 as the required gate, T2 optional on top (T3 when both are configured), fail closed.**
Concretely:

- Packs dir set **and no allowlist and no trust store** ⇒ **every jar is refused** with
  `job.pack.rejected` and cause `not trusted: no jobs.packs.allowlist or jobs.packs.trustStore configured`.
  This is a behaviour change for Job Packs too (today they load unconditionally); breaking changes are
  acceptable here — nothing after 3.x is in production.
- The allowlist path is a **boot `-D` property** naming a file that must **not** resolve under any
  `PathJail.allowedRoots()` root (refuse boot otherwise), so no control-plane write route can reach it (A3).
  It is re-read on every `rescan`, so approving a new jar is: edit the file, drop the jar.
- The hash that is checked is the hash of the **staged copy the loader reads** (after slice P0), and the
  inventory reports that hash, so `GET /jobs/packs` is the audit trail of exactly what is loaded.
- ⛔ Not a UI/API route to approve a jar. Approval stays a host-level operator act by design; an API route
  would put A3 back.

Why T1 over T2 as the base: T1 needs no PKI, pins the **exact bytes** (so a later vulnerable build of a
trusted vendor is not automatically trusted), and is trivially auditable (`sha256sum` on the host matches
the inventory). T2 is the right *addition* for a vendor that ships often.

**One mechanism, not two.** The row's wording ("a drop-in `plugins/` jar directory") would create a second
dynamic-code door beside `-Djobs.packs.dir`, with its own trust gate to keep in step. **Recommend a parser
is a fifth pack kind** in the existing Job Pack loader — the loader already carries four kinds that are not
Jobs (`:202-220`) and keeps its `jobs.` property name for that reason (`:49-50`). Directory, trust gate,
inventory, signals, quiesce and rescan are then shared (D1).

---

## 4. The per-vendor decode profile (the config home)

**Shape: a satellite TOON file whose one top-level block is `asn1:` — byte-for-byte the same keys the
Pipeline's `parsing.asn1` block already takes.** Not a new registry kind (the same call the operator made
for the grammar file on 2026-09-23), not a new key vocabulary.

```
spaces/<space>/config/vendors/ericsson_msc/
  ericsson_msc.asn                 # the grammar module (already supported)
  ericsson_msc.decode.toon         # the decode profile (new)
  mo_call_schema.toon              # segment schemas the profile names
  mt_call_schema.toon
```

`ericsson_msc.decode.toon`:

```
asn1:
  grammar_file: ericsson_msc.asn
  root_type: CallEventRecord
  strictness: BER
  record_header_length: 0
  segments:
    moCallRecord: mo_call_schema.toon
    mtCallRecord: mt_call_schema.toon
```

A Pipeline for one switch of that vendor:

```
parsing:
  frontend: asn1
  asn1:
    profile_file: ../vendors/ericsson_msc/ericsson_msc.decode.toon
    max_value_bytes: 4194304        # a per-Pipeline override
```

Semantics (each one a test in slices C1–C3):

- **Resolution.** `profile_file` resolves beside the Pipeline's config via `PathJail.resolveConfigRef`
  (`PathJail.java:193`), is jailed with `requireUnderAny(allowedRoots())`, and must end `.toon`. Every
  relative ref **inside** the profile (`grammar_file`, segment schema paths) resolves **beside the
  profile** — the existing "a relative config reference resolves beside its own config file" reading —
  never beside the Pipeline.
- **Overlay.** Scalar keys in the Pipeline's `asn1:` block **win** over the profile's, key by key — the
  "parsing: keys win" convention the ingester already applies. `segments` is **replaced whole**, never
  merged (a merge makes "which record kinds does this Pipeline load?" unanswerable from either file — D5).
- **One resolver.** The overlay happens in `asn1PluginBlock` (`PipelineConfigParser.java:1809-1839`)
  *before* its required-key checks, so a profile-backed Pipeline passes the same `grammar`/`root_type`/
  `segments` refusals with no second validator. Preview takes the same profile through the drawer's
  existing `subdir` context (parser-plugins.md, *A grammar is either pasted TEXT or a stored `.asn` FILE*).
- **No nesting.** A profile may not name a `profile_file` (refused at load).
- **Save round-trip.** A guided Save writes back `profile_file` plus only the keys the Pipeline overrides
  — never the merged result, or the first save silently forks the Pipeline off its vendor profile.
- **Bundles.** The profile, its grammar and its segment schemas join `referencedFiles()`. Export must
  rewrite refs **inside the profile** as well as in the Pipeline (`PipelineBundleRoutes.rewriteSatelliteRefs`,
  `:465`), because satellites travel flattened under their basenames — or export inlines the profile
  (D6).
- **ASN.1 only.** XML's `ingester_config` has the same per-vendor shape but no customer asking; a
  `profile_file` for it is a later, separate row, not speculative scope here.
- **Not covered, stated:** the legacy tree operations on repeated children (§1.4 row 3). The profile gives
  them nowhere to live on purpose (D9).

---

## 5. Slices (ordered; each lands green on its own)

| Slice | What | Needs | Test (house style; unit level per change) |
|---|---|---|---|
| **C1** | `asn1.profile_file`: resolve + jail + overlay in `asn1PluginBlock`; nested profile refused | D5 | new cases in the `PipelineConfigParser` asn1 tests: profile-only Pipeline loads; Pipeline scalar wins; `segments` replaced whole; `../../outside.toon` → `PathJail.Escape`; `.txt` refused before any read; profile naming a profile refused |
| **C2** | Preview honours `profile_file` through `subdir` | C1 | `ControlApiParsersTest`: profile-backed preview tree == inline preview tree; escaping profile → 403 |
| **C3** | Bundle export/import carries the profile + its satellites | C1, D6 | `ControlApiPipelineBundleTest`: export → import → preview equals inline; two satellites with one basename are refused or renamed, never silently collapsed |
| **C4** | Demo proof | C1 | `DemoCorpusIngestTest`: `msc_cdr` split into profile + Pipeline ingests the same rows per segment as the committed inline original (the pattern of `mscCdrWithItsGrammarInAnAsnFilePreviewsAndIngestsIdenticallyToInline`) |
| **P0** — ◐ staged-bytes half ✅ `f90ddcf25`; server-owned staging dir ❌ open | Hash **and** verify the staged copy; stage under a server-owned dir, not system temp | nothing — a fix | `JobPackManagerTest`: the inventory hash equals SHA-256 of the file the loader opened; a jar mutated after staging is not what is loaded |
| **P1** — ✅ SHIPPED 2026-09-25 (T1 only; `PackAllowlist`, `JobPackTrustTest`, mutation-checked; allowlist also refused inside the packs dir, under `assist.write.root` / `spaces.root`; revocation unloads on rescan; refused jars listed in `GET /jobs/packs`) | Trust gate T1 (+ T2 if D3 chooses it), fail closed | D2, D3 | `JobPackManagerTest`: no allowlist ⇒ every jar rejected with the named cause; listed hash loads; one flipped byte ⇒ rejected; allowlist under a write root ⇒ boot refuses. **Mutation check:** revert the gate and confirm those tests go red on the *rejection* assertions, not on setup |
| **P2** — ✅ SHIPPED 2026-09-25 (`Parsers` overlay also refuses a second parser naming a taken ingester FQCN; `GET /parsers` gains `source`; `JobPackParserTest`) | `Parsers` becomes an owner-keyed overlay (`register(ParserPlugin, owner)` / `deregister(owner)`, built-in collision refused, first pack wins, catalog order kept — never `Map.copyOf`); `JobPackManager.load` gains the fifth `ServiceLoader` loop and the fifth rollback | D1 | `ParsersTest`: pack parser appears after built-ins; colliding with a built-in refused; deregister restores the catalog. `JobPackManagerTest`: a parser-only pack loads; a pack whose parser collides is rejected whole, other kinds rolled back |
| **P3** — ✅ SHIPPED 2026-09-25 (`PluginIngesters` + `PackRunLeases.acquire(owner)`; mutation-checked) | Ingester resolution through the owning loader: replace the two `Class.forName(name)` sites with one resolver that looks the FQCN up via the registered plugin's class loader; pin the pack for the duration of an ingest (the `acquireRun`/`releaseRun` pair, extended beyond Jobs) | P2 | ingest a fixture through a pack-loaded ingester end to end; unload mid-ingest defers the loader close; a Pipeline naming an unloaded parser fails its next Run with a named error, not `ClassNotFoundException` |
| **P4** | Preview gating for pack parsers | D4 | real-HTTP: a viewer is refused / allowed per the decision; a built-in's preview is unchanged |
| **P5** | Docs: OKF `parser-plugins.md` (as-built), `jobs.md` (trust gate now applies to Job Packs too), EDITIONS row if D7 gates; archive this plan | all | doc guards |

C1–C4 need none of the trust decisions and can start as soon as D5/D6 are signed. P0 is a defect fix and
needs no signature at all.

---

## 6. What this design refuses

- ⛔ **A second dynamic-code directory** (unless D1 says otherwise) — two doors, two gates, one of them
  eventually stale.
- ⛔ **"Classloader isolation" as a security claim** in any doc or UI text.
- ⛔ **An API or UI route that approves a jar** (reopens A3).
- ⛔ **A generic `decode_profile` registry kind** with its own CRUD routes — a satellite file already
  travels, previews and jails.

---

## 7. Decisions owed (operator)

1. **D1 — One dynamic-code door or two?** Parsers as a fifth Job Pack kind in `-Djobs.packs.dir`, or a
   separate `plugins/` directory as the BACKLOG row words it. **Recommend: the existing Job Pack loader.**
   Same trust gate, inventory, signals, quiesce and rescan; nothing to keep in step.
2. **D2 — The base trust gate.** T1 SHA-256 allowlist · T2 signer against an operator trust store (SEC-7)
   · T3 both. **Recommend: T1 required; T2 optional on top (T3 when both are configured).** T1 pins exact
   bytes with no PKI; T2 alone keeps every build the key ever signed loadable.
3. **D3 — Fail closed for Job Packs too?** With the packs dir set and no allowlist/trust store: refuse
   every jar (breaking for today's Job Pack behaviour) or load as today. **Recommend: refuse.** An
   unconfigured trust gate that loads everything is the A1 exposure with extra steps; breaking changes are
   free on this surface.
4. **D4 — Who may preview a pack parser?** Today `POST /parsers/{id}/preview` is exempt as read-shaped.
   Keep it exempt for pack parsers, or require a capability (e.g. the one that may author Pipelines).
   **Recommend: require the Pipeline-authoring capability for pack-contributed parsers only;** built-ins
   stay exempt. Keeps third-party code over caller-chosen bytes behind the same bar as configuring its
   ingest.
5. **D5 — Profile overlay for `segments`.** Replace whole, or merge key-by-key. **Recommend: replace
   whole.** A merged record-kind set cannot be read off either file, and "an undeclared kind is junk" would
   then depend on two files at once.
6. **D6 — Bundle export of a profile-backed Pipeline.** Carry the profile as a satellite and rewrite the
   refs inside it, or inline the profile into the exported Pipeline. **Recommend: carry and rewrite** — an
   inlined profile imports as N un-shared copies, which is the exact condition this feature exists to end;
   add a basename-collision refusal in the same slice.
7. **D7 — Edition gate on loading packs.** Gate behind an `EditionFeatures` feature (Professional+) or
   leave every edition able to load packs as today. **Recommend: no edition gate.** The board already marks
   custom plugin ingesters ✅ in all three editions (`SP-PRS-08`); and an edition gate is a licensing
   control that stops no attacker — the trust gate must be identical in every edition.
8. **D8 — Out-of-process parser host (T5).** Build it now, or record it as the only real containment and
   defer. **Recommend: defer, and record it in BACKLOG** with the trigger "a customer requires running
   parser code we have not reviewed". Weeks of runtime work; T1 + operator review covers the stated need.
9. **D9 — Legacy tree operations on repeated children** (`flattenList` / `reduce` / `cartesianJoin`).
   Build an `explode`-style segment option now, or defer. **Recommend: defer until measured** — count
   repeated fields that carry required columns across the operator's vendor grammars first (the corpus is
   gitignored and absent from worktrees, so this is a main-checkout task). A design for a prevalence of
   zero is waste; a prevalence of many changes the profile shape and is cheaper to learn before C1.
10. **D10 — Name.** "Decode Profile" for the satellite file (already used informally in
    `parser-plugins.md`). **Recommend: adopt it and add it to `GLOSSARY.md`** in slice C1, with ⛔ not
    *Access Profile* (a different concept) and ⛔ not *transform config* (it holds no transforms).
