# Adjudication — docs-consolidation-plan §5.8.1, Group 2 (rows 2.1, 2.3–2.5) + Group 3 (rows 3.1–3.5)

Repo: `C:\sandbox\inspecto-clean` @ `master` / `db2b24c0`. Read-only pass; nothing edited.
Every verdict below was settled against source, `META-INF/services`, `pom.xml`, or committed fixtures.
sshj sources inspected from `~/.m2/repository/com/hierynomus/sshj/0.39.0/sshj-0.39.0-sources.jar`
(the version `inspecto-connectors/pom.xml:31` pins).

---

## Verdict table

| row | bucket | which side is right | evidence |
|---|---|---|---|
| 2.1 | UNDISPUTED-DOC | `framework.md` (implicitly); `data-acquisition-framework.md` wrong. Plan's line cite is off: the route is at **`:45`**, not `:36`. **Plus a second error on the same line the plan missed** — `audited as source.notified` at `:47`. | `inspecto/src/main/java/com/gamma/control/AcquisitionRoutes.java:22` (`GET /collectors`), `:27` (`POST /collectors/([^/]+)/notify`); `inspecto/src/main/java/com/gamma/control/AuditTrail.java:151`; `inspecto/src/test/java/com/gamma/control/ControlApiCollectorNotifyTest.java:103` asserts `"collector.notified"` |
| 2.3 | UNDISPUTED-DOC | `pipeline-editor.md:248` right (`stability.size_checks`); `framework.md:48` wrong (`stability.sizeChecks`) | `inspecto-etl/src/main/java/com/gamma/etl/PipelineConfigParser.java:422` reads `"size_checks"`; `inspecto-etl/src/main/java/com/gamma/etl/PipelineConfig.java:766` accessor `sizeChecks`; `inspecto-engine/src/main/java/com/gamma/pipeline/PipelineCompiler.java:239` writes `"size_checks"`; fixture `spaces/ucc/config/voucher/voucher_pipeline.toon:44` |
| 2.4 | UNDISPUTED-DOC | neither doc; the branch is gone. Two files carry it. | `docs/BRANCHING.md:19` — "`4.x` **DELETED 2026-08-17** … the `v4.0.0` and `v4.0.0-RC1` tags were deleted with it"; `git tag` tops out at `v3.12.0`; `git branch -a` = `master` + `origin/1.x` only |
| 2.5 | UNDISPUTED-DOC (2 of 3 sub-claims); third sub-claim **REFUTED** | doc wrong at `:93` and `:130`. The "SFTP+FTP-only delivered list" (`:20-21`) is **not** a defect — it is the phase-**E** line of a phase-by-phase banner that goes on to list FTPS `:31`, s3 `:36`, kafka `:51`, azure `:64`, gcs `:72`. | tunnel: `SshTunnel.java` used by `SftpConnector`, `FtpConnector`, `DbExportConnector`, `DbConnections`, `DbConnectionWorkbench`. proxy: `ConnectionProfile.java:62` `Proxy` record; `SocksProxySocketFactory`, `HttpProxySocketFactory`, `ProxyArg` all in `src/main`. event notification: `AcquisitionRoutes.java:27` |
| 3.1 | UNDISPUTED-DOC | `acquisition/connectors.md:50-60` right (7-row table); `integrations.md:21` and `modules/connectors.md:16` wrong | `inspecto-connectors/src/main/resources/META-INF/services/com.gamma.acquire.CollectorConnectorFactory:3-10` = 8 factories; `scheme()` returns `sftp`/`ftp`/`ftps`/`db`/`s3`/`kafka`/`azure`/`gcs`. NFS/SMB **declined**: `data-acquisition-framework.md:108-115` + `inspecto-config/.../ConfigSafetyValidator.java:438` and `PathJail.java:118` reject UNC |
| 3.2 | UNDISPUTED-DOC | **`integrations.md` is RIGHT** (`:40`, `:88`, `:92-94`) — but the plan's cite `:79` is off by 9 (`:79` is the Connections-pane sentence). **The "newer file" is NOT wrong — it is silent**: `acquisition/connectors.md:62` names `host_key` without a format. The only wrong file is **`docs/FEATURE_INVENTORY.md:147`**. | `HostKeyPolicy.java:35` field is literally `fingerprint`; `:49` reads `options.host_key` into it; `:71` `client.addHostKeyVerifier(fingerprint)`; `:75-76` refusal text says "no host_key **fingerprint** or known_hosts". sshj `SSHClient.java:186-188` → `FingerprintVerifier.getInstance`, whose `:50-68` accepts only `SHA1:`/`SHA256:`/`MD5:`-prefixed or bare MD5 colon-hex and otherwise **throws** `SSHRuntimeException("Invalid MD5 fingerprint: …")`. `host_key: "ssh-rsa AAAA…"` cannot work. |
| 3.3 | UNDISPUTED-DOC | plan right on both halves. `modules/connectors.md:12-13` stale by two; `acquisition/connectors.md` mis-frames gson — at **`:93`**, not `:95`. | `inspecto-connectors/pom.xml`: sshj `:67`, commons-net `:74`, postgresql `:82`, **kafka-clients `:90`**, **gson `:99`** — all compile scope (only `inspecto-processor` `:37` and `slf4j-api` `:49` are `provided`; sshd/ftpserver/junit are `test`) |
| 3.4 | UNDISPUTED-DOC | `acquisition/connectors.md:153-162` right; `integrations.md` wrong — at **`:26-27`**, not `:18` | `inspecto-acquire/src/main/java/com/gamma/acquire/SecretResolver.java:12-21` documents 5 forms; `:46-54` implements `ENV`/`SYS`/`FILE`/`KEYSTORE`/bare `${NAME}`; `:49` routes FILE+KEYSTORE to `provided()`; `:79-88` throws the edition refusal when no `SecretsProvider` serves them. Provider registered only by `inspecto-security/src/main/resources/META-INF/services/com.gamma.acquire.SecretsProvider` |
| 3.5 | UNDISPUTED-DOC | plan right; `integrations.md` wrong — the `CollectorProcessor` claim is at **`:227`**, not `:218` | `inspecto-etl/src/main/java/com/gamma/etl/DuckLakeRegistrar.java:40` `register(...)`; `:23` javadoc "Extracted from `CollectorProcessor#registerInDuckLake`"; caller is `inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestor.java:288`. Seal reconciliation: `:54-55` opens its **own** throwaway DuckDB via `DuckDbUtil.tempDbFile("duckdb_lake_")` + `DriverManager`, `:58` installs there, `:81` deletes it — it never touches a sealed connection. `inspecto-sql/src/main/java/com/gamma/sql/SqlSandbox.java:93-97` `seal()` = `enable_external_access=false` + `lock_configuration=true`. Non-fatal arm `:83-85` is `log.warn`, **not stderr** (extra drift the row did not name) |

**No row in either group is DISPUTED.** Every one was settled by code, a services file, a pom, or a
committed fixture. Two audit premises inside otherwise-correct rows were refuted (2.5's third
sub-claim; 3.2's "the newer file is wrong").

---

## Corrections the register itself needs

| register text | correction |
|---|---|
| 2.1 "`data-acquisition-framework.md:36`" | the route is at `:45`. `:36` is the s3-connector banner line. |
| 3.1 "`integrations.md:12`" | the "future" claim is at `:21`. `:12` is the deep-reference banner. |
| 3.2 "`integrations.md:79` is RIGHT" | right file, wrong line — `:40` (the example) and `:88` (the option table). `:79` is "The Connections pane in the UI lists profiles…". |
| 3.2 "the newer file is wrong" | `acquisition/connectors.md:62` states no format; nothing there is wrong. The single wrong file is `FEATURE_INVENTORY.md:147`. |
| 3.3 "`connectors.md:95`" | gson is at `:93`. |
| 3.4 "`integrations.md:18`" | secret schemes are at `:26-27`. `:18` is the optional-module sentence. |
| 3.5 "`integrations.md:218`" | the `CollectorProcessor` attribution is at `:227`. |
| 2.5 "three '(future)' claims" | two. The third (`:20-21`) is a correct phase-scoped statement. |
| Group-3 merge hazard: "carrying the prose into `connectors.md` fails there because it has no waiver" | **REFUTED** — see answer A. The connectors half produces zero guard hits. |
| Group-2 ⛔ "the mounted-share / UNC-jail security note … exists nowhere else" | substantially right. `docs/REQUIREMENTS.md:82` (ACQ-4) records the *rule*, `docs/okf/backend/config/config-safety.md:17` the *jail behaviour*; the operator **recipe** (`net use` / `mount -t nfs\|cifs`, `-Dassist.safety.roots=<roots>;X:\`) exists only at `data-acquisition-framework.md:108-115`. |

## Adjacent defects found but not in the register

* `data-acquisition-framework.md:92` lists **SSH/SCP** as a remote-filesystem protocol. No `scp` scheme
  exists. Because this file self-declares as "the requirement-of-record … not the as-built" (`:4`), an
  untagged requirement is legitimate — **do not "fix" it into a delivery claim**; it is only the
  `(future)`/delivered *annotations* that are status assertions and can go stale.
* `framework.md:19-20` ends "Authoritative doc: `data-acquisition-framework.md`" → which at `:14-15`
  defers to `docs/archived-documents/superpowers/specs/2026-06-14-…-roadmap.md`. The as-built authority
  chain terminates in the never-maintained tier, exactly as the Group-2 header says. Fixing this is a
  Group-2 header action, not one of the numbered rows.
* `integrations.md:233` says the DuckLake failure is "logged to stderr"; `DuckLakeRegistrar.java:84` is
  `log.warn`. Folded into the 3.5 edit.
* `modules/connectors.md:4` frontmatter `description:` says "Remote source connectors (SFTP/FTP/FTPS/DB)"
  — same omission class as 3.1. Optional companion edit given below.

---

## FILE / OLD / NEW — every UNDISPUTED-DOC row

All OLD strings verified unique (`grep -cF` = 1, multi-line via a Python substring count).

### Row 2.1 — two edits, same file

FILE: `docs/okf/backend/acquisition/data-acquisition-framework.md`
OLD: ``POST /sources/{id}/notify``
NEW: ``POST /collectors/{id}/notify``

FILE: `docs/okf/backend/acquisition/data-acquisition-framework.md`
OLD: ``audited as `source.notified`;``
NEW: ``audited as `collector.notified`;``

*(The `source.discovery: watch` key two lines below is the **row 2.2** config-prefix class, already
settled — sweep it with 2.2, not here.)*

### Row 2.3

FILE: `docs/okf/backend/acquisition/framework.md`
OLD: ``across `stability.sizeChecks` cycles``
NEW: ``across `stability.size_checks` cycles``

### Row 2.4 — two files

FILE: `docs/okf/backend/acquisition/data-acquisition-framework.md`
OLD: ``the framework is built — Phases A–F shipped on `4.x`.**``
NEW: ``the framework is built — Phases A–F have all shipped on `master`.** *(⚠ do not restore a `4.x` attribution: that branch and its `v4.0.0`/`v4.0.0-RC1` tags were deleted 2026-08-17 — `docs/BRANCHING.md:19`.)*``

FILE: `docs/okf/backend/acquisition/index.md`
OLD: ``All six roadmap phases (A–F) ship on `4.x`.``
NEW: `All six roadmap phases (A–F) have shipped.`

### Row 2.5 — two edits (the third sub-claim is refuted; no edit)

FILE: `docs/okf/backend/acquisition/data-acquisition-framework.md`
OLD: `  * SSH Tunneling and Proxy (future)`
NEW: ``  * SSH Tunneling and Proxy — **shipped**: `SshTunnel` for `sftp`/`ftp`/`ftps`/`db`, plus SOCKS5 (2026-07-20) and HTTP `CONNECT` (2026-08-13) proxy dial-through, extended to the PostgreSQL JDBC driver 2026-09-06``

FILE: `docs/okf/backend/acquisition/data-acquisition-framework.md`
OLD: `object version comparison, event notification (future).`
NEW: ``object version comparison, event notification (**shipped** — `POST /collectors/{id}/notify`, ACQ-6).``

### Row 3.1 — two files

FILE: `docs/okf/backend/integrations.md`
OLD:
```
New protocols (S3/GCS/Azure, NFS/SMB) are future connectors that
plug into the same SPI without touching the core engine.
```
NEW:
```
Eight schemes ship in that module today — `sftp`, `ftp`, `ftps`, `db`, `s3`, `kafka`, `azure`, `gcs`
(`META-INF/services/com.gamma.acquire.CollectorConnectorFactory`). NFS/SMB is a **declined** design, not a
pending one: there is deliberately no in-process client and the path jail rejects UNC paths — mount the
share at the OS level and point the built-in `local` connector at it (see
[data acquisition](acquisition/data-acquisition-framework.md) §1). Further protocols plug into the same
SPI without touching the core engine.
```

FILE: `docs/okf/backend/modules/connectors.md`
OLD:
```
Future connectors (S3, NFS/SMB) plug in via the same
SPI without touching the core.
```
NEW:
```
Eight schemes are registered today (`sftp`, `ftp`, `ftps`, `db`, `s3`, `kafka`, `azure`, `gcs`); NFS/SMB is
a **declined** design (OS-mounted share + the `local` connector). Further connectors plug in via the same
SPI without touching the core.
```

Optional companion (same file, same defect class, frontmatter):
FILE: `docs/okf/backend/modules/connectors.md`
OLD: `description: Remote source connectors (SFTP/FTP/FTPS/DB) and all network dependencies, kept out of the core.`
NEW: `description: The eight remote collector connectors (SFTP/FTP/FTPS/DB/S3/Kafka/Azure/GCS) and all network dependencies, kept out of the core.`

### Row 3.2 — root canon

FILE: `docs/FEATURE_INVENTORY.md`
OLD: ``| SSH host-key pinning | `options: { host_key: "ssh-rsa AAAA…" }` or `{ known_hosts: … }` | `okf/backend/acquisition/data-acquisition-framework.md` |``
NEW: ``| SSH host-key pinning | `options: { host_key: "SHA256:<base64>" }` — a **fingerprint** (SHA256/SHA1 base64, or MD5 colon-hex); a raw `ssh-rsa AAAA…` key line is rejected. Use `{ known_hosts: … }` to pin from an OpenSSH file. | `okf/backend/integrations.md#ssh-host-key-pinning-sftp` |``

⚠ Two things in that NEW deliberately: the skeleton, **and** the Doc pointer. The current pointer
(`data-acquisition-framework.md`) only names `options.host_key` at `:33` with no format, so it cannot
settle the very thing the row is about; `integrations.md:81-95` is the table that can. If you want the
minimal edit, keep the old third cell and change only the skeleton.

⚠ `integrations.md:88` and `HostKeyPolicy.java:20` both omit that sshj also accepts `SHA1:<base64>`.
That is an incompleteness in both, not a contradiction — worth adding while the table is open, and not
a reason to call either side wrong.

### Row 3.3 — two files

FILE: `docs/okf/backend/modules/connectors.md`
OLD:
```
artifactId `inspecto-connectors`. Holds **all network dependencies** (sshj + BouncyCastle for SFTP,
Apache commons-net for FTP/FTPS, the PostgreSQL JDBC driver) so the [engine core](engine.md) JAR has none.
```
NEW:
```
artifactId `inspecto-connectors`. Holds **all network dependencies** (sshj + BouncyCastle for SFTP,
Apache commons-net for FTP/FTPS, the PostgreSQL JDBC driver, kafka-clients for the streaming collector,
and gson for the native GCS JSON API) so the [engine core](engine.md) JAR has none.
```

FILE: `docs/okf/backend/acquisition/connectors.md`
OLD: `parsed with gson (parent-managed; already transitively on the classpath — no new fat-JAR jar).`
NEW: ``parsed with gson — a **declared compile dependency** of this module (`inspecto-connectors/pom.xml:99`; version parent-managed). It was already on the classpath transitively, so declaring it adds no new fat-JAR jar.``

### Row 3.4

FILE: `docs/okf/backend/integrations.md`
OLD: ``references, never literals** — `${ENV:VAR}` reads an environment variable, `${SYS:prop}` a JVM system property.``
NEW: ``references, never literals** — `SecretResolver` expands five forms at connect time: `${ENV:VAR}` (environment variable), `${SYS:prop}` (JVM system property), `${FILE:/path}` (a mounted secret file), `${KEYSTORE:alias}` (a `SecretKeyEntry` from the store named by `-Dsecrets.keystore.path`/`.type`/`.password`), and bare `${NAME}` (environment first, then system property). ⚠ **SEC-07: `${FILE}` and `${KEYSTORE}` are Standard + Enterprise only** — they are served by the `inspecto-security` module's `SecretsProvider`; a Personal bundle refuses the scheme by name, which a connection test surfaces as its failure.``

### Row 3.5 — two edits, same file

FILE: `docs/okf/backend/integrations.md`
OLD: `3. **Run the ETL.** After each file is written, CollectorProcessor will:`
NEW: ``3. **Run the ETL.** After each file is written, `DuckLakeRegistrar.register` (called from `ConsignmentIngestor`) will:``

FILE: `docs/okf/backend/integrations.md`
OLD: `the file is still marked processed and the failure is logged to stderr.`
NEW:
```
the file is still marked processed and the failure is logged at WARN.
```
…and append, as its own paragraph immediately after that item:
```
   > **Why `INSTALL ducklake FROM core` does not fight the SQL sandbox.** `SqlSandbox.seal()` sets
   > `enable_external_access=false` + `lock_configuration=true`, which would block an extension install.
   > The registrar never touches a sealed connection: it opens its **own** throwaway DuckDB
   > (`DuckDbUtil.tempDbFile("duckdb_lake_")` + `DriverManager`), installs and attaches there, and deletes
   > the temp DB in a `finally`. The two rules are reconcilable because they are different connections.
```

---

## A. The `integrations.md::source-acquisition-entity` waiver

**Method.** Replicated the guard exactly — `scanProse` (`tools/check-vocabulary.mjs:573-584`) strips
```-fences, `vocab-allow` lines, ⛔ lines, `~~strike~~` and every `` `inline code` `` span
(`stripInlineCode`, `:246-248`) — then applied the `source-acquisition-entity` matcher verbatim
(`:238-241`): `/\bSources?\b(?![-\s]+(?:of|files?|code|data|system|path|format|size))/`, case-sensitive.

**Result — the waiver suppresses exactly ONE hit, and it is the H1:**

| line | text | sense |
|---|---|---|
| **10** | `# Integrations: Remote Sources, DuckLake & Warehouse Query Layer` | **the only guard hit.** "Remote Sources" here is the *heading over the connector section* — the remote SFTP/FTP servers, i.e. the acquisition-entity sense, **not** the Catalog Stream/Reference axis. The waiver's stated justification ("Remote Sources are data origins (Stream/Reference axis, GLOSSARY §3)") does **not** fit this line: §"Remote source connectors" is about `CollectorConnector`s and the `source:`/`collector:` pipeline block, which `GLOSSARY.md:29` and `:799` rename to **Collector**. |

Nothing else in the file trips the rule. The sanctioned/near-miss lines, for the record:

* `:3` frontmatter `title: … remote sources …` — lowercase, no match (rule is case-sensitive).
* `:294` `| View | Source path | …` — matches the rule's **trailing-noun exemption** (`Source path` =
  the origin artefact). Genuinely the sanctioned sense; no waiver needed.
* `:15`, `:146`, `:168-169` — lowercase `source`; no match.
* `:56`, `:66`, `:169`, `:198-199` — `source:` / `source.post_action` / `source.incremental.watermark` /
  `source.duplicate`: **banned acquisition-entity sense** (the parser reads `collector` only — row 2.2),
  but every one is inside a ``` fence or a `` ` `` span, so **the guard never sees them.** They are a
  row-2.2 debt, invisible to the guard, and fixing them changes nothing about the waiver.
* `:214-224`, `:246`, `:289`, `:296-309`, `:329` — `<data_source>` placeholders, lowercase.

**Decision: the waiver CANNOT survive a retitle. Delete it in the same commit.** Its only suppressed
hit is line 10; a retitle to a warehouse name removes that word, the entry then suppresses nothing, and
`:610-623` turns that into a hard `stale-allowlist` violation ("DELETE the entry from DOC_ALLOW") — build
red. Same outcome if the file is *renamed*: the key is path-keyed.

**And the plan's other half of the hazard is REFUTED.** I extracted lines 15–199 (the connectors half)
into a standalone file and ran the same scan: **zero hits.** Carrying that prose into
`acquisition/connectors.md` therefore needs **no** waiver there — every banned-sense use is fenced or
backticked. The warehouse half (201–335) is likewise zero. So the merge hazard is real but inverted: the
work is *deleting* a waiver, not *porting* one.

*(Note `tools/check-vocabulary.mjs:71` still lists `docs/integrations.md` in `USER_FACING` — that path
does not exist (moved to `docs/okf/backend/` on 2026-07-16). `scanProse` returns `[]` silently for a
missing file (`:566-570`), so pass 1 has been scanning nothing for that entry. Unlike `DOC_ALLOW`, the
`USER_FACING` list has no self-retirement check. Adjacent, pre-existing, and worth a line in the same
commit.)*

## B. Splitting `integrations.md`

**The file is 335 lines. The split point is unambiguous** — a `---` rule at `:201`:

| span | subject |
|---|---|
| 1–8 | frontmatter (`title`/`description`/`tags` already name **both** subjects) |
| 10–13 | H1 + the "Moved from `docs/integrations.md`" and docs-index banners |
| **15–199** | **`## Remote source connectors (SFTP / FTP / FTPS)`** — profiles, host-key pinning, FTPS, bastion, DB-export + watermark. The **retire/merge candidate.** |
| 201 | `---` |
| **203–249** | `## DuckLake Integration` — setup, pipeline `output.ducklake`, DBeaver via the ducklake extension |
| 251 | `---` |
| **253–332** | `## Warehouse Query Layer — DBeaver via pg_duckdb` — pg_duckdb install, `warehouse_setup.sql`, views, roles, partition pruning |
| 334–335 | trailing `---` + blank |

So the surviving warehouse doc is **203–332** (with 251 kept as its internal rule), i.e. **201–335**
inclusive of the separators. Nothing in 203–332 mentions a connector; nothing in 15–199 mentions
DuckLake or pg_duckdb. Clean cut.

**Inbound links (live tiers only — `docs/archived-documents/**` and `inspecto-deploy/docs/**` excluded):**

| link | target half | verdict |
|---|---|---|
| `docs/okf/backend/acquisition/data-acquisition-framework.md:18` → `#remote-source-connectors-sftp--ftp--ftps` | **connectors half** | ⚠ the **only** inbound link to the retiring half. Must be re-pointed to `acquisition/connectors.md` in the same commit or it becomes a dead anchor. |
| `docs/okf/backend/engine/stage1-architecture.md:117` → `#warehouse-query-layer--dbeaver-via-pg_duckdb` | warehouse half | survives a retitle **only if the `## Warehouse Query Layer — DBeaver via pg_duckdb` heading text is unchanged** (the anchor is derived from it). Keep that H2 verbatim. |
| `docs/okf/backend/architecture-layers.md:197` — "(`pg_duckdb`) queries it externally — see `integrations.md`" | warehouse half | plain text, no anchor. Survives a retitle; **breaks on a file rename.** |
| `docs/stakeholders/TECHNICAL_ARCHITECTURE.md:84` → whole file | warehouse half ("query the lakehouse via the warehouse layer (pg_duckdb)") | survives a retitle; breaks on a rename. Root-canon tier. |
| `docs/okf/backend/index.md:41` — "Integrations … acquisition connectors + DuckLake/warehouse touchpoints" | **both** | must be rewritten either way: the description will be half-false after the split. |
| `docs/archived-documents/plans-archive/docs-consolidation-plan.md:166`, `:526-543` | the plan itself | in-flight; update with the work. |
| `inspecto/README.md:393`, `:435` → `../docs/integrations.md` | warehouse half (both describe DuckLake/pg_duckdb) | **already dead** — `docs/integrations.md` has not existed since 2026-07-16. Pre-existing breakage; cheap to fix in the same pass. |
| `.claude/ARCHITECTURE_MAP.md:41` → `docs/integrations.md` | — | **already dead**, same cause. |

**Is a split safe? Yes, with three conditions:**
1. Re-point `data-acquisition-framework.md:18` (the sole inbound link to the retiring half).
2. Keep the `## Warehouse Query Layer — DBeaver via pg_duckdb` heading byte-identical, or fix
   `stage1-architecture.md:117`'s anchor with it.
3. Prefer **retitle in place over rename**: three live links and one dead pair address the file by
   *path*. A rename also relocates the `DOC_ALLOW` key (answer A) — which you are deleting anyway, so a
   rename is survivable, just more edits. `docs/INDEX.md` has **no** reference to this file, so the
   tier-1 map needs nothing beyond `okf/backend/index.md:41`.
