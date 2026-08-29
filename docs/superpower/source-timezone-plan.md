# The source time zone for temporal data — grounded plan

**Status:** IN FLIGHT (opened 2026-08-29). Board row: `BACKLOG.md` §4 `Source-timezone for temporal data`
(2026-08-22, operator-requested). Concept home on completion:
`okf/backend/engine/duckdb.md`.

**Operator's ask, unchanged:** a per-column zone policy — (i) wall-clock as-is (default = today's
behaviour) · (ii) fixed IANA zone `fields[].timezone` · (iii) per-row zone column
`fields[].timezone_column` — plus a pipeline-level `source_timezone` in the `parsing:` block.
Precedence **row-column > column > pipeline > none**.

---

## 1. What the live probe settled (run against DuckDB 1.5.2.1, the version in `~/.m2`)

The board row required a live probe before building ("run DuckDB, don't assume"). It was run; the
transcript below is measured, not reasoned.

| # | Question | Measured answer |
|---|---|---|
| 1 | Does a named IANA zone need the **ICU** extension loaded? | **No — `icu` is already `loaded=true` on a bare `jdbc:duckdb:` connection** (statically bundled). This was the biggest build risk and it is not one. |
| 2 | Does it survive the `SqlSandbox` seal? | **Yes.** With `autoload_known_extensions=false` + `enable_external_access=false` + `lock_configuration=true`, `timezone('UTC', timezone('Asia/Kolkata', …))` still evaluates. Nothing needs loading, so the lockdown is irrelevant to this feature. |
| 3 | Session `TimeZone` | `Asia/Calcutta` — **the host**, confirming `duckdb-session-timezone-is-the-host`. |
| 4 | `TRY_STRPTIME(naive, naive-fmt)` type | `TIMESTAMP` (naive). |
| 5 | `TRY_STRPTIME(text, '…%z')` type | **`TIMESTAMP WITH TIME ZONE`** — an offset in the data **does** win, as the row hoped. |
| 6 | 🔴 naive `::TIMESTAMPTZ` | `2026-08-29 10:00:00+05:30` — **interpreted in the session zone. The row's live trap is real and reproduced.** |
| 7 | `timezone('UTC', timezone(Z, naive))` | `TIMESTAMP` naive-UTC. **The row's compile shape works.** |
| 8 | Per-row zone **column** (non-constant 1st arg) | **Works** — option (iii) is feasible with no UDF. |
| 9 | An unknown zone name | **Hard error, not NULL** (`Not implemented Error: Unknown TimeZone 'X'!`). ⇒ validation must be fail-closed at config load or one bad value kills a whole batch. |
| 10 | DST-ambiguous / non-existent local times | **No error** — silently resolved (Berlin `02:30` on both switch days → `01:30` UTC). Nothing to guard; worth documenting. |

### 1a. Two constraints the row did not know

- 🔴 **Offset-form zones are REJECTED by DuckDB.** `timezone('+05:30', …)` and `timezone('Z', …)`
  both raise *Unknown TimeZone*. Option (ii) is **IANA region ids only** (`Asia/Kolkata`,
  `Etc/GMT+5`, `GMT0` are fine). Java's `ZoneId.of` accepts `+05:30` and `Z`, so **`ZoneId.of`
  alone is not a valid gate** — it would pass values DuckDB then dies on.
- ⚠ **The two engines disagree in both directions.** DuckDB accepts `utc` (case-insensitive);
  `ZoneId.of("utc")` throws. Measured containment: **every one of Java's 604 zone ids is present in
  DuckDB's 638-row `pg_timezone_names()` (java-only = 0)**. ⇒ the gate as built is **membership in
  `ZoneId.getAvailableZoneIds()`** — a proven-safe subset that also excludes the offset forms and the
  lower-case spellings, pinned against the live engine by `everyZoneTheGateAdmitsIsOneDuckDbAccepts`
  rather than trusted. (A `ZoneId.of` + `!(instanceof ZoneOffset)` gate was the first cut and is
  *nearly* right, but admits `UT`, which is a region id Java knows and DuckDB does not.)

### 1b. A latent defect found in passing (pre-existing, not caused by this work)

`COALESCE(TRY_STRPTIME(c,'…%z'), TRY_STRPTIME(c,'…'))` unifies to **`TIMESTAMP WITH TIME ZONE`** —
so the moment a format list mixes a `%z` format with a plain one, the plain branch is
session-zone-interpreted. **No shipped config uses `%z` today** (checked `spaces/`), so it is latent,
not live. Recorded here; not in this slice's scope beyond not making it worse.

---

## 2. Where the naive chain actually is — the row's "ONE choke point" is wrong

The row says *"ONE choke point: apply inside `dateExpr`/`castSql`"*. Grounding found **four**
value-producing temporal sites, not two:

| # | Site | Produces | In the row? |
|---|---|---|---|
| 1 | `SchemaFieldTypes.castSql` → `SqlBuilder.appendCoalesce` | the mapped column (DIRECT rule) | yes |
| 2 | `TransformCompiler.dateExpr` → `SqlBuilder.buildCastExpr` | `DATE_*` partition columns **and** `__event_time` | yes |
| 3 | `TransformCompiler.concatDt` | a TIMESTAMP built from a date column ‖ a time column | **no — missed** |
| 4 | `TransformCompiler.filenameDate` | a DATE lifted out of the filename | **no — missed** |

⇒ Fixing only 1 and 2 would leave a `CONCAT_DT` timestamp un-normalised beside a normalised DIRECT
sibling in the same row. This is the repo's recurring *one concept, N drifting sites* shape.

**Decisions on the two extra sites:**
- **(3) `concatDt` — IN.** It yields a wall-clock instant exactly like a DIRECT TIMESTAMP.
- **(4) `filenameDate` — OUT, deliberately.** It extracts a `%Y%m%d` **date** from a filename. A date
  has no instant to shift; applying a zone would move a file dated `20260829` into the previous day
  for any negative-offset zone. Documented at the method, not silently skipped.

**Not sites:** `ComponentPreview:732-734` (an `IS NOT NULL` validity probe, produces no value) and
the `EXPR` rule (author-owned verbatim SQL — out of scope by the same rule that leaves it
un-sandboxed).

**Dead code found:** `SqlBuilder.buildPartitionExpr` has **no production caller** (only
`buildCastExpr` at `TransformCompiler:209` does). Not touched — flagged, per the "mention, don't
delete" rule.

---

## 3. Grounded config facts

- `date_formats` / `timestamp_formats` are authored under **`parsing.delimited:`** and land in
  `PipelineConfig.CsvSettings` via `PipelineConfigParser.mergeParsing` (`:1487`), which overlays the
  `delimited` map wholesale plus an **explicit scalar allow-list** — `frontend, encoding,
  compression, fixedwidth, json, text_regex, xlsx, parquet, asn1`. A pipeline-level
  `source_timezone` is format-agnostic, so it belongs at **`parsing:` level ⇒ add it to that
  allow-list**, not inside `delimited:`.
- `raw.fields[]` entries are validated fail-closed by **`Identifiers.validateSchema`**
  (`inspecto-etl`), which today checks `name` and `type`. That is the gate for `timezone` /
  `timezone_column`.
- `fieldTypes` (name → type) is rebuilt **three times** in `DataTransformer` (`:97`, `:166`, `:250`)
  from the same `raw.fields[]` list. The zone map must not become a fourth and fifth copy — one
  factory, called at those same three points.
- **`meta.domain.timezone` stays display-only.** It exists with a `domain-timezone-resolvable`
  ConfigSpec rule, and `OperationsZone`'s javadoc explicitly says it is *not* the operations zone.
  Activating it as the pipeline default would reverse a recorded decision — the row says prefer the
  new key, and this plan does.
- **No shipped config declares `TIMESTAMPTZ`** (the UI type picker offers it;
  `schema-fields-editor.component.ts:44`). So the fail-closed refusal below breaks **no existing
  config**.

---

## 4. The build

### S1 — engine + config (this slice)

1. **`SourceZones`** (new, `inspecto-etl`) — the one home for the concept:
   - `validate(zone, origin)` — region-based `ZoneId` only; rejects blank, offset forms (`+05:30`,
     `Z`) and unknown names with a message naming the fix. Pinned by a test asserting Java's zone set
     ⊆ DuckDB's `pg_timezone_names()`.
   - `resolve(field)` → row-column > column > pipeline > none.
   - `wrap(naiveExpr, zone)` → `timezone('UTC', timezone(<zone>, <naive>))`, the measured shape.
2. `CsvSettings` gains `sourceTimezone` (nullable); parser reads `parsing.source_timezone` and adds
   it to the `mergeParsing` allow-list; validated at load.
3. `raw.fields[].timezone` / `.timezone_column` parsed + validated in `Identifiers.validateSchema`.
   A `timezone_column` naming a field that does not exist is a load error.
4. Applied at sites **1, 2 and 3**; site 4 documented as deliberately exempt.
5. **`TIMESTAMPTZ` fail-closed:** with a zone source it compiles to `timezone(Z, naive)` and keeps
   the real instant; **without one it is refused at config load** — closing the trap in §1 #6 rather
   than importing server-local time.
6. Tests: SQL-shape units at each site + a **real-DuckDB end-to-end** asserting values (not SQL
   strings), incl. the precedence ladder and that the default path is byte-identical to today.

**S1 as-built (shipped this session).** New `SourceZones` (`inspecto-etl`); `CsvSettings.sourceTimezone`
(+ a 25-arg compat constructor, so callers on the older arity keep both compiling and their exact
behaviour); `parsing.source_timezone` added to `mergeParsing`'s scalar allow-list and validated at
load; `raw.fields[].timezone` / `.timezone_column` validated in `Identifiers.validateSchema`; the
TIMESTAMPTZ refusal in `PipelineConfigParser.requireZoneForTimestampTz`, called at all three
schema-resolution points (single, multi, segment). Applied at sites 1, 2 and 3; site 4 documented as
exempt. `SourceZonesTest` — 18 tests, of which 7 assert values on a real DuckDB.

**Three things the build found that the plan above did not predict:**

1. 🔴 **`TRY()` does not catch an unknown zone.** *Not implemented* errors are outside what `TRY`
   intercepts, so one bad value in a `timezone_column` would kill the whole batch with no soft
   failure available. Fixed by resolving the row's zone through a `pg_timezone_names()` lookup, which
   yields NULL for an unknown or NULL zone — the same "bad value becomes NULL" contract every other
   coercion here has, and already counted by the cast-failure audit. Measured cost ~2µs/row on 200k
   rows, and only a configured `timezone_column` pays it; the fixed-zone form is a literal.
2. 🔴 **The zone-column reference needed `CAST(… AS VARCHAR)`.** `lower()` binds only to VARCHAR, so a
   non-text zone column was a binder error that killed the batch — precisely the failure the lookup
   exists to prevent. Found by a test, not by inspection.
3. ⚠ **A blank cell in TOON's tabular field form is ABSENT, not empty.** `fields[N]{…}` declares one
   header for every column, so a schema that gives *any* field a zone writes `""` for all the others.
   The first cut's null-only checks would have refused every such schema at load.

### S2 — surfaces (next slice)
`ConfigSpecs` attribute specs, the tab-2 `Source timezone` select, the metadata-grid zone column,
and offline-mock parity. ⚠ Per `async-spec-swap-drops-seed` and `ui-s7-two-committed-contracts`, the
node-vocabulary contracts must be regenerated together when the specs change.

## 5. Non-goals
The `%z`-mixed-COALESCE latent defect (§1b) · `filenameDate` · the `EXPR` rule · re-opening
`meta.domain.timezone` · any change to the naive-downstream posture (partitions, BI grains and dedup
stay naive and consistent, which is precisely what normalising *to naive UTC* preserves).
