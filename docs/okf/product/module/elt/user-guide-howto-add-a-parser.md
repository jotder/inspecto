---
type: How-to
title: "Add a parser through an extension point"
description: "Write a Java ParserPlugin for a file format Inspecto does not read, register it with ServiceLoader, load it as a Job Pack without a restart, and use it from a Pipeline."
item: module.elt
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [platform/inspecto-engine/src/main/java/com/gamma/parse, platform/inspecto-etl]
---

# Add a parser through an extension point

## Goal

Teach Inspecto a file format it does not read yet: write a **parser** in Java, load it into a running server, and
let a Pipeline land that format's files into a Dataset.

## Before you start

- **Edition:** any — the parsing engine is in every bundle. Built in are `delimited`, `fixedwidth`, `json`,
  `text_regex`, `xlsx` and `parquet`; check first that none of them (with its grammar) already reads your format.
- **Who:** a Java developer; loading a pack needs an operator (`canOperateRuns`), and previewing a pack's parser needs
  `canAuthorWorkbench`.
- **What you need:** a JDK and your build tool, the Inspecto engine and ETL jars to compile against, and a server
  started with `-Djobs.packs.dir=<folder>` and `-Djobs.packs.allowlist=<file>` (the allowlist outside the packs folder
  and every write root).

## Steps

1. **Implement `com.gamma.parse.ParserPlugin`** (a public API since 4.0.0), with a public no-argument constructor:
   - `id()` — lower-case letters, digits and underscores, unique among all parsers;
   - `label()`, `hierarchical()` (true for nested records, false for flat rows);
   - `grammarSchema()` — the fields of its grammar; the Pipeline editor draws its form from them;
   - `preview(byte[] sample, Map<String,Object> grammar)` — parse a sample for the editor's preview.
   - Optional: `suggest(byte[])` to propose a grammar from a sample, `previewRows(…)`, and `ingesterClass()`.
2. **To load rows, not just preview them,** return from `ingesterClass()` the name of a class that extends
   `com.gamma.etl.StreamingFileIngester` (a public API since 3.10.0). A parser without one is preview-only.
3. **Register it** — in your jar, `META-INF/services/com.gamma.parse.ParserPlugin` holds one line, the parser's fully
   qualified class name.
4. **Load it without a restart** — as a Job Pack:
   - put the jar in the packs folder;
   - add its SHA-256 to the allowlist file, one line per jar: `<64 hex digits>  <name>  [note]` (`sha256sum` output
     works);
   - rescan: `POST /api/v1/spaces/<space>/jobs/packs/rescan` (or wait for the folder watcher).

   Alternatively put the jar on the server's classpath and restart.
5. **Use it in a Pipeline** — its parsing section names the plugin and the ingester:

   ```toon
   parsing:
     frontend: plugin
     plugin:
       ingester: com.example.parse.MyFormatIngester
   ```

   plus the `segments` and `ingester_config` your ingester reads. The guided parse editor offers your parser's grammar
   form once it is loaded.

## Check it worked

The packs list shows your jar as `loaded` (or `rejected` with its `cause`), and the parser catalog lists your
parser — here with the id `my_format` — beside the built-in ones, such as `delimited`:

```check
GET /spaces/{space}/jobs/packs
as admin
expect status 200
```

```check
GET /spaces/{space}/parsers
as admin
expect status 200
expect data[*].id has delimited
```

Then preview a sample through it: `POST /api/v1/spaces/<space>/parsers/my_format/preview` with
`{"grammar": {…}, "sample_text": "…"}` answers a table (`columns`, `rowCount`) or a tree.

## What can go wrong

- The pack is listed with `state: rejected` and a `cause` — most often its hash is not on the allowlist, or the
  allowlist is missing or unreadable (then every jar is refused).
- `no JobTypeProvider, ExpressionProvider, PipelineNodeType, StepExecutor, ParserPlugin or ServiceProvider in
  META-INF/services` — the registration file is missing or misnamed.
- `parser id 'x' is a built-in or classpath parser and cannot be replaced by a pack` — choose another id.
- `duplicate parser id 'x'` — two classpath jars claim the same id; the server does not start.
- 422 from preview — your `preview` threw; the message is the exception's.
- The parser previews but no rows land — `ingesterClass()` returns nothing, so it is preview-only.

## Next

- [Pipeline and Steps](user-guide-concept-pipeline.md)
