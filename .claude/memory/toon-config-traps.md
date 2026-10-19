---
name: toon-config-traps
description: "TOON configs can parse while losing your edit: a # comment line truncates the file, no single-quote strings, Windows paths in array rows, tabular arrays defeat grep, DIRECT coerces few types; verify by decode + load + run"
metadata:
  type: reference
---

1. **A raw `#` comment line truncates the rest of the file silently** (`PipelineConfig.load` accepts the shorter
   map; a pipeline then "succeeds" quarantining every row). A quoted `"#"` value is fine. Never write `#` lines in
   `.toon`.
2. **No single-quote delimiter:** `quote: '"'` is the 3-char string; write `quote: "\""`.
3. **An absolute Windows path starting an ARRAY ROW parses as a key** (`C: ...`) -> "Array length mismatch".
4. **Arrays are tabular:** `fields[3]{name,from,fn}:` — grep `fields\[`, not `fields:` (a 0-of-24 probe was
   really 24-of-24). When a count is 0 or "all", open one file.
5. **`DIRECT` coerces only DATE / TIMESTAMP / DOUBLE**; a declared INTEGER lands VARCHAR — use VARCHAR + `EXPR`
   `TRY_CAST`. Schema field types fail closed on unknown DuckDB types; a narrow UI dropdown may mirror an engine
   switch — read the switch before widening.
6. **Workbench graph API:** `GET /pipelines/{n}/graph` is a display projection, not a valid PUT body — use
   `/graph/raw`. Save is key-preserving and fail-closed; the writer's unquoted scalars (`delimiter: |`) are not
   corruption.
7. Free-form node config must not round-trip through `JSON.stringify` strings (destroyed route branches once).

**Verify:** after editing a `.toon`, decode it and compare the top-level key list, load it through the real
parser, and for data-shaped config RUN it (`CollectorProcessor <pipeline.toon>` with
`--enable-native-access=ALL-UNNAMED -Dassist.safety.roots=<repo>`), then `DESCRIBE` the parquet output.
Grammar components are templates you copy, never live bindings.
