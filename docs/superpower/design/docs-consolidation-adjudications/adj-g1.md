# Adjudication — `docs/archived-documents/plans-archive/docs-consolidation-plan.md` §5.8.1 Group 1 (Parse pane / SQL-first)

Read-only pass, 2026-09-08. Rows 1.1–1.7, 1.9, 1.10 (1.8 already settled).
Docs under adjudication:
- `C:\sandbox\inspecto-clean\docs\okf\frontend\features\pipeline-editor.md`
- `C:\sandbox\inspecto-clean\docs\okf\frontend\features\grammar-config.md`
- `C:\sandbox\inspecto-clean\docs\okf\frontend\features\schema-mapping-authoring.md`
- `C:\sandbox\inspecto-clean\docs\okf\frontend\features\index.md` (the fourth account)

---

## Verdict table

| row | bucket | which side is right | key evidence |
|---|---|---|---|
| 1.1 | UNDISPUTED-DOC | `schema-mapping-authoring.md` (`{sql, fields}`); `pipeline-editor.md:404-409` is wrong **twice over** | `pipeline-transform-sql-definition.component.ts:613-627`, `:388`, `:74-75`; `RowShaper.java:78-79,631,692-696`; `PipelineEditable.java:85-89` |
| 1.2 | UNDISPUTED-DOC | `schema-mapping-authoring.md` §0 | `pipeline-transform-sql-definition.component.ts:91-98`, `:41`, `:18`; `sql-functions.ts:95`; `pipeline-transform-sql-definition.component.html:12-43,60` |
| 1.3 | UNDISPUTED-DOC | neither doc — `schema-mapping-authoring.md` §3 contradicts its own §0 | 3 CodeMirror hosts: `sql-editor.component.ts:29`, `enrichment-editor.component.ts:62`, `pipeline-transform-sql-definition.component.ts:96` |
| 1.4 | UNDISPUTED-DOC | `schema-mapping-authoring.md:171,179-181` (DELETED) beats its own `:25-26` | `BuiltinNodeType.java:103`; `PipelineEditable.java:97`; deleted in `42fa41fe` |
| 1.5 | UNDISPUTED-DOC | the code — no Use column | `schema-fields-editor.component.html:46-47`, `:49-103` (no `Use` `<th>`); `schema-fields-editor.component.ts:132-145` |
| 1.6 | UNDISPUTED-DOC | `schema-mapping-authoring.md:350` (BUILT same day); `grammar-config.md:22-27` is wrong on 3 counts | `schema-fields-editor.component.ts:132`; `pipeline-transform-sql-definition.component.ts:414-417`; `docs/BACKLOG.md:36`; `schema-fields-editor.component.html:21-33` |
| 1.7 | UNDISPUTED-DOC — **FALSE POSITIVE, 0 edits** | **both** docs right; there are **three** pagers, not two | `grammar-editor.component.ts:396-397` (10/25/50/100 @10); `schema-fields-editor.component.ts:285` + `.html:309` (10/25/50/100 @50); `pipeline-transform-sql-definition.component.ts:38,179` (10/20/100 @10) |
| 1.9 | UNDISPUTED-DOC | `pipeline-editor.md:726` is the current list (2 cases) | `pipeline-editor.component.ts:2267-2276`, `:2140-2144` |
| 1.10 | UNDISPUTED-DOC | **neither** — the register's own verdict is also partly wrong | `parsing-attributes.ts:100-104` (label is *How values are understood*); metadata grid absent from Parse |

---

## Row 1.1 — `pipeline-editor.md:404-409`: `{sql}` only / "do not add `fields`"

**Register claim:** persisted config is `{sql}` only and *"do not add `fields`"*; `submit()` writes both and
`readFields` rehydrates. **The prohibition would delete a live contract.**

**Grounded — the register is right, and the defect is worse than stated.**

`inspecto-ui/src/app/modules/admin/pipelines/pipeline-transform-sql-definition.component.ts:613-627`

```
    submit(): void {
        if (!this.canApply()) return;
        const n = this.node();
        // `fields` is the authoring artifact; `sql` is what the engine reads. Rows are persisted only
        // when they were authored: a Step applied from the SQL view writes `fields: []` and reopens as
        // SQL, and the reconciler offers Fields again from there — never a grid invented behind the
        // author's back.
        const config: Record<string, unknown> = {
            sql: this.generatedSql(),
            fields: this.view() === 'fields' ? this.fields() : [],
        };
```

`fields` is written on **every** Apply — `[]` from the SQL view, the rows from the Fields view. Never omitted.

Rehydration: `:388` `const storedFields = readFields(node.config?.['fields']);` → `:396-399` sets the Fields
view from them. The component's own contract, `:74-75`: *"**Persisted shape.** `{ sql, fields }`."*

**The doc's second sentence is wrong on the engine too.** `pipeline-editor.md:408` says *"the engine reads
nothing else"* and *"its presence no longer means anything"*. Refuted:

- `inspecto-engine/src/main/java/com/gamma/pipeline/exec/RowShaper.java:78-79`
  `public static final Set<String> MAP_NODE_CONFIG_KEYS = Set.of("columns", "rules", "fields", "schema", "csv");`
- `RowShaper.java:695-696` — `fields` is read and **executed** as the mapping:
  `if (node.cfg("fields") instanceof List<?> fields && RecordTransform.isFieldList(fields)) return Map.of("raw", …, "mapping", Map.of("fields", fields));`
  (also `:631`, `:692`)
- `inspecto-engine/src/main/java/com/gamma/pipeline/PipelineEditable.java:85-89` — the allow-list comment
  states the exact failure mode the doc's advice would cause:
  `// `fields` joined 2026-09-05 with the Record Transformer projection: it is executable in RowShaper`
  `// (MAP_NODE_CONFIG_KEYS), so it MUST be lowerable too — a key that becomes executable without`
  `// joining this allow-list is silently dropped on save`
  `static final Set<String> MAP_AUTHORED = Set.of("columns", "rules", "fields");`
- `PipelineLift.java:311` `if (!authored.fields().isEmpty()) mapCfg.put("fields", authored.fields());`

Only ONE clause of `:408` survives: `sql` **is** the single `NodeAttributes` entry
(`NodeAttributes.java:395-400`; UI mirror `node-attributes.ts:381-391`). That is a statement about the
*declared attribute schema*, not about what the engine reads — the doc conflates the two.

**Bucket: UNDISPUTED-DOC.** Edits A + B below.

---

## Row 1.2 — `pipeline-editor.md:399-403`: one `<textarea>` + shared fields editor

**Register claim:** it is a bespoke grid + function catalog + `Fields|SQL` switch + CodeMirror, and
`schema-fields-editor` is **not imported**. **Right on every clause.**

- `<inspecto-schema-fields-editor>` is **not** in the pane's `imports` — `pipeline-transform-sql-definition.component.ts:91-98`
  holds only `MatButtonModule, MatIconModule, StepPreviewResultComponent, InspectoAlertComponent,
  SqlCodemirrorComponent, StepWorkbenchComponent`. Repo-wide, the only non-spec hosts of that selector are
  `pipeline-parse-definition.component.ts:335` (the **Parse** pane) and the design-system showcase.
- Bespoke grid: `pipeline-transform-sql-definition.component.html` — body rows over `SqlField`, columns
  `# · Field name · From · What to do · Comes out as · Sample · ×` (`:3`).
- Function catalog: `sql-functions.ts:95` `SQL_FUNCTIONS` — **24 functions in 7 categories** (Keep, Text,
  Numbers, Dates, Logic, Convert, Custom); surfaced by `sqlFunctionsByCategory()` (`:305`).
- `Fields | SQL` switch: `TransformView = 'fields' | 'sql'` (`.ts:41`); segmented control at
  `.html:12-43`.
- CodeMirror: `.ts:18` + `:96` import `SqlCodemirrorComponent`; rendered at `.html:60`
  `<inspecto-sql-codemirror [value]="sqlText()" (valueChange)="onSqlEdited($event)" />`.
- "one SQL `<textarea>`" is doubly stale: the SQL view is CodeMirror, and it is **editable** —
  `.ts:289-290`: *"⚠ The SQL view is NOT a refusal. It was, while the hand-written SQL was read-only; once
  CodeMirror made it editable the drawer's Apply armed on the first keystroke."*

⚠ **The same file already corrects itself 14 lines above.** `pipeline-editor.md:385-392` carries a
*"2026-09-05 — Fields and SQL are two peer views of one Step"* banner that describes the switch and the
reconciler accurately. The defect is confined to the two bullets at `:399-409`, which were never updated
when that banner was added — so Edit A deliberately does **not** restate the banner.

⚠ Also stale, same section, not one of the ten rows: the heading at `:383` still reads *"SQL-first rebuild
2026-09-04"* — that is the middle state the operator reversed the same day.

**Bucket: UNDISPUTED-DOC.** Folded into Edit A (same bullet as 1.1a).

---

## Row 1.3 — `schema-mapping-authoring.md:243-246`: CodeMirror "never here"

**Register claim:** stale — three importers including this pane. **Right.**

Three non-spec hosts of `SqlCodemirrorComponent`:
1. `inspecto/data-table/sql/sql-editor.component.ts:29` (SQL query workbench)
2. `inspecto/enrichment/enrichment-editor.component.ts:62` (enrichment editor)
3. `modules/admin/pipelines/pipeline-transform-sql-definition.component.ts:96` (**this** pane)

The two neighbouring bullets in the same §3 are equally refuted and must go in the same edit, or §3 keeps
contradicting §0 of its own file:

- `:240-242` *"No dedicated expression-builder UI exists anywhere — no function picker…"* — refuted by
  `sql-functions.ts` + the per-row function picker (`.html:87-98` toolbar, per-row `What to do` select).
- `:247-248` *"No SQL function catalog is surfaced to end users…"* — refuted by the same. (Narrowly read it
  says *"on the mapping or join authoring surfaces"*, and the mapping surface it meant — the Load pane — is
  deleted; either way the sentence now misinforms.)
- `:241-242`'s stated **reason** ("confirmed by a codebase-wide search for monaco/codemirror/…") is the
  stale evidence, not a live rule. Nothing here is a rule that must survive.

⚠ Also stale, same file, not one of the ten rows: `:31-32` cites the routing arm at
`pipeline-editor.component.html:755-767`; it is now **`:743`**.

**Bucket: UNDISPUTED-DOC.** Edit C.

---

## Row 1.4 — `transform.map`: "NOT removed" vs "DELETED"

**Register claim:** `:25-26` says NOT removed / legacy path; `:171,180` say DELETED. It **is** deleted.
⚠ `pipeline-editor.md:486-502` mirrors the problem. **Right.**

- `inspecto-engine/src/main/java/com/gamma/pipeline/BuiltinNodeType.java:103`
  `// transform.map was DELETED 2026-09-05: the projection slot is always a Record Transformer`
  — no `TRANSFORM_MAP` constant remains.
- `PipelineEditable.java:97` *"{@code transform.map} is gone"*; `PipelineLift.java:318`
  *"transform.map no longer exists"*; `PipelineValidator.java:287`; `DataTransformer.java:260`.
- UI: `pipeline-load-definition.component.ts` does not exist. Deleted in `42fa41fe`
  *"feat(pipelines)!: delete transform.map — the projection slot is always a Record Transformer"*.
- `pipeline-editable.ts:39` `'transform.sql', // the projection slot (id map / map_<key>) … transform.map is gone`.

Three doc sites carry it:
1. **`schema-mapping-authoring.md:25-26`** — *"the `transform.map` rule grid and the `transform.join` form
   were NOT removed; `transform.map` is now the legacy mapping path"*. ⚠ Note the other two items in that
   sentence **are** live: `SchemaEditorDialog` (`modules/admin/components/schema-editor.dialog.ts`) and the
   `transform.join` form (`node-attributes.ts:326`). Only the `transform.map` clause is wrong — do not
   delete the whole sentence.
2. **The same file's frontmatter** — `description:` advertises "the legacy transform.map rule grid", and
   `resource:` points at the deleted `pipeline-load-definition.component.ts`.
3. **`features/index.md:29`** — the fourth account; see row-1.2/1.4 combined edit F.

**`pipeline-editor.md:486-502` — partially mitigated, one bullet genuinely stale.** The banner at
`:468-481` carries an explicit re-reading rule (`:478-481`: *"The facts below that name `transform.map` now
read as 'the projection slot (`transform.sql`, id `map`)'"*), which covers `:485-490`. It does **not** cover
`:495-502`, which describes *"The Load pane"* in the present tense — a pane that no longer exists.

🔴 **The rule inside `:495-502` is still live and must survive the re-framing.** The `|`-delimited
positional packing it documents is still executed on the read path:
- `MappingRules.java:92-94` — `if (!"EVENT_DATE".equals(target)) … "FILENAME_DATE is only supported for the EVENT_DATE column"` (server-side refusal, still armed)
- `SqlBuilder.java:90-93` — same guard
- `inspecto-util/src/main/java/com/gamma/util/MappingCsv.java` — still present
- `RecordTransform.fromMappingRules` maps CONCAT_DT → `date.concat_parts`, FILENAME_DATE → `date.from_filename`

So: change the **subject** from "The Load pane authors…" to "a stored `mapping.rules[]` still carries…";
keep every constraint.

**Bucket: UNDISPUTED-DOC.** Edits D (frontmatter), E (`:25-26`), F (`index.md:29`), G (`pipeline-editor.md:495-502`).

---

## Row 1.5 — `grammar-config.md:80`: a "Use" column on the Parse table

**Register claim:** D8 removed include-control and the code comment says so. **Right.**

- `inspecto/schema/schema-fields-editor.component.html:46-47`
  `D8 (2026-09-04): there is no Use/include column — Parse settles what a column IS and emits`
  `every row; leaving a field out of the output is transform.sql's job, and only its job.`
- The `<thead>` (`:49-103`) has **no** `Use` header: `#`(w-16 selector) · Name · Type · Sample value ·
  Also known as, plus conditional `Source zone` and `Selector`.
- Body rows (`:107-248`) have exactly 5 base `<td>` + 2 conditional — first cell is the `#`, no checkbox.
- `schema-fields-editor.component.ts:132-145`
  `⛔ LEGACY, no longer honoured by this grid (D8, 2026-09-04) … Kept on the interface so a Grammar CSV`
  `exported with an include column still round-trips … nothing reads it to decide what is emitted.`
- `:521` `// D8: every row the grid holds is emitted, so "at least one" is a row count, not an include count.`

⚠ **Two code leftovers found while grounding this (flagged, not part of the row's bucket):**
1. `schema-fields-editor.component.html:37` — the R11 mockup comment still lists the column order as
   `Use · # · Name · Type · Sample value · Also known as`, directly above the D8 comment that contradicts
   it. That stale comment is plausibly what fed `grammar-config.md:80`.
2. `schema-fields-editor.component.html:253-296` — the synthetic filename row still renders a **leading
   disabled checkbox `<td>`** (`:255-260`, aria-label `'Use ' + f.name + …'`), giving that row **8** cells
   against the header's and body's **7**. A real (if cosmetic) misalignment left behind by D8.

**Bucket: UNDISPUTED-DOC** (the doc is wrong; the behaviour is unambiguous). Edit H.

---

## Row 1.6 — `grammar-config.md:22-27`: D8–D10 "decided, NOT built"

**Register claim:** they were built the same day. ⚠ Neither doc gets Parse's filter right — it is a type
**dropdown**, not counted chips. **Right on both.**

D8–D10 as-built:
- **D8** — `schema-fields-editor.component.ts:132` + `.html:46-47` (above). Built.
- **D9** — Parse: search (`.html:10-20`, over name + selector + synonym, `.ts:304-312`), type filter
  (`.html:21-33`), paginator `[10, 25, 50, 100]` default 50 (`.html:305-313`, `.ts:285`), `#` = position in
  the full list (`.html:109-118`). Transform: `PAGE_SIZES = [10, 20, 100]` default 10
  (`.ts:38,179`), counted chips (`.html:87-98`). Built.
- **D10** — `pipeline-transform-sql-definition.component.ts:414-417`
  `// D10: land on Changed when there IS something changed … ⛔ Never on a Step that changes nothing`
  → `this.filter.set(this.allRows().some((r) => r.changed) ? 'changed' : 'all');`. Built.

The cross-doc corroboration: `schema-mapping-authoring.md:350` heads §7
*"(D8–D10, decided 2026-09-04 — BUILT the same day, verified 2026-09-06)"*, and `:354` names the commits
`4e64fe4a`, `c769719d`.

`AUTHORING-WIDE-1` is **closed**: `docs/BACKLOG.md:36` lists it among rows that
*"were already shipped and only the row was stale"*. It appears nowhere in the open §3/§4 queues.

**Three separate errors in the six lines of `:22-27`:**
1. "decided, NOT built" — false.
2. "filter chips with counts" — Parse's filter is a **`mat-select` type dropdown with no counts**
   (`schema-fields-editor.component.html:21-33`; `.ts:281` `typeFilter = signal<string>('all')`,
   `.ts:314` filters on base type). Counted chips are the **Transform** grid's idiom only.
3. "10/20/100 paging" — that is the **Transform** grid's option set. Parse ships
   `[10, 25, 50, 100]` defaulting to 50.
4. The `AUTHORING-WIDE-1` citation — closed.

🔴 **What must survive:** the D8 *rule* itself — Parse settles existence/name/type/synonym and exclusion is
`transform.sql`'s job — is live and is exactly what `schema-fields-editor.component.ts:132-140` enforces.
Re-frame the banner as as-built; never delete the rule.

⚠ The same "counted chips on Parse" error is in **`schema-mapping-authoring.md:386`** (D9: *"Both field
tables … view-only filter chips **with counts**"*). Same class, second file — Edit J.

**Bucket: UNDISPUTED-DOC.** Edits I + J.

---

## Row 1.7 — pager default 10 vs 50

**Register claim:** two different pagers; Parse is 50, Transform is 10; a merge to one number would be
wrong either way.

**Verdict: the register is right, and there are in fact THREE pagers. No doc is wrong. ZERO edits.**

| pager | code | options | default | doc statement | correct? |
|---|---|---|---|---|---|
| Grammar editor's **parsed sample rows** (Sample \| Parsed tab) | `inspecto/grammar/grammar-editor.component.ts:396-397` | `[10, 25, 50, 100]` | **10** | `grammar-config.md:78-79` *"the parsed rows get a 10 · 25 · 50 · 100 page size (default 10)"* | ✅ |
| **Parse columns** table | `inspecto/schema/schema-fields-editor.component.ts:285`; `.html:309` | `[10, 25, 50, 100]` | **50** | `schema-mapping-authoring.md:357` *"Parse `[10, 25, 50, 100]` defaulting to 50"* | ✅ |
| **Transform fields** grid | `pipeline-transform-sql-definition.component.ts:38,179` | `[10, 20, 100]` | **10** | `schema-mapping-authoring.md:59,356` | ✅ |

`schema-mapping-authoring.md:387-389` even states the asymmetry as deliberate, with the reason
(*"Transform pages at 10 because its rows are tall … Parse at 50 because its rows are dense one-liners"*) —
matching the code exactly.

The **only** wrong pager number in the group is `grammar-config.md:26`'s "10/20/100 paging" attributed to
the Parse columns table — and that lives inside row 1.6's span, so it is repaired by Edit I and is **not**
duplicated here (overlapping OLD strings would break mechanical application).

**Bucket: UNDISPUTED-DOC — confirmed non-defect. 0 edits.**

---

## Row 1.9 — `GrammarEditorDialog` custody: three incompatible lists

**Register claim:** three incompatible lists, two inside `pipeline-editor.md`; `:726-729` is the current
one. **Right — and there are four lists, not three.**

**Ground truth = TWO dialog cases.** `pipeline-editor.component.ts:2267-2276`:

```
    private isDrawerParse(node: AuthoredNode): boolean {
        const effectiveType = node.type === 'parser' ? this.retypedParserType(node) : node.type;
        if (!effectiveType || !(effectiveType in PARSE_NODE_FRONTENDS)) return false;   // ← generic parser that maps to nothing
        if (this.isBinaryFixedWidth(node)) return false;                                 // ← binary fixed width
        // PARSE-HOME-1 (2026-09-06): a per-format node whose grammar binding DANGLES opens the drawer too
        return true;                                                                     // ← dangling → DRAWER
    }
```

Corroborated by the routing comment at `:2140-2144`: *"The ONE surface still on a popup is the Grammar
editor, for the two parse shapes the drawer cannot represent: binary fixed-width and a generic `parser` that
maps to no frontend … and since 2026-09-06 (PARSE-HOME-1) so does a DANGLING binding."* Router:
`:2148` `if (!isParseNodeType(node.type) || this.isDrawerParse(node)) { void this.openDefinition(node); return; }`
else `:2153-2158` opens `GrammarEditorDialog`.

The four doc lists:

| site | list | verdict |
|---|---|---|
| `pipeline-editor.md:313-315` | dangling `use:` + binary FW + config-less generic `parser` | ❌ stale (dangling moved to the drawer) |
| `pipeline-editor.md:726` | binary FW + unmappable generic `parser` | ✅ **current** |
| `grammar-config.md:118-121` | grammar-**bound** node + dangling + binary FW | ❌ worst — a grammar-bound per-format node has opened the **drawer** as an inline copy since D4 |
| `grammar-config.md:242-244` | dangling + binary FW (+ generic `parser` has no drawer pane) | ❌ stale on dangling |

⚠ **Also a drifted code mirror (flagged; does not change the bucket, because the code *body* is
unambiguous):** `pipeline-editor.component.ts:2259-2265`, the javadoc directly above `isDrawerParse`, still
reads *"Two exceptions stay on the dialog: … a **dangling** binding … **binary** fixed-width"* — the very
case the body six lines later routes to the drawer. `openNodeConfig`'s comment (`:2140-2144`) is the
correct mirror. Worth a one-line code fix in the same change.

**Bucket: UNDISPUTED-DOC.** Edits K, L, M.

---

## Row 1.10 — "Types & columns tab" / "Column metadata list"

**Register claim:** split verdict — the metadata grid **is** gone, but "Types & columns" is a real current
*section* (expansion panel, not a `MatTab`). Stale only in the word "tab".

**Verdict: NEITHER side is fully right — the register's own resolution is also wrong.** The section exists
by **id**, but its user-facing **label** is not "Types & columns".

- `inspecto/grammar/parsing-attributes.ts:100-104`:
  ```
  export const GRAMMAR_TABS: { id: string; label: string }[] = [
      { id: 'dialect', label: 'How the file is written' },
      { id: 'types', label: 'How values are understood' },
      { id: 'robustness', label: 'When a row looks wrong' },
  ];
  ```
  So the reader-visible name is **"How values are understood"**. "Types & columns" survives **only** in code
  comments (`parsing-attributes.ts:242,392,632`; `grammar-editor.component.ts:704`;
  `pipeline-parse-definition.component.ts:303,884`) — never on screen.
- Not a `MatTab`: `grammar-editor.component.html:106-166` renders `<mat-accordion multi="true">` with one
  `<mat-expansion-panel>` per section; `:152-155` slots the host's `[tabTypes]` content into the `types`
  panel. `showTab(id)` is now *expand a section*: `grammar-editor.component.ts:709-713`
  `if (sections.some((t) => t.id === id)) this.setExpanded(id, true);` — its own javadoc (`:704`) says
  *"Expand a sectioned spec set's named section (S4)"*.
- The **only** surviving `mat-tab-group` in the grammar editor is Sample | Parsed
  (`grammar-editor.component.html:47-98`) — exactly as `grammar-config.md:76` already says.
- **Metadata grid gone from Parse:** repo-wide, `<inspecto-schema-metadata-grid>` appears outside its own
  files only in the design-system showcase (`design-system.component.html:312`,
  `design-system.component.ts:337`). `pipeline-parse-definition.component.ts:314-316`:
  *"The metadata grid left this pane (D2: description / unit / classification belong to Transform)."*

The two live doc defects are both in `pipeline-editor.md`; neither is under a superseded banner:
- `:338` *"the FIRST derivation steers to the Types & columns tab"*
- `:368` *"shown read-only in the Types tab and Column metadata list"*

`grammar-config.md:135` also says *"four tabs — Dialect / parsing · Types & columns · …"* but it sits under
an explicit ⚠ **Superseded** banner at `:131-133` and is correctly labelled history — **leave it alone**.
`grammar-config.md:48` already has the current label right (*"`types` = **How values are understood**"*).

**Bucket: UNDISPUTED-DOC.** Edits N + O.

---

# EDIT BLOCKS

(Ordered; no two OLD strings overlap. All paths repo-relative to `C:\sandbox\inspecto-clean`.)

## Edit A — rows 1.1 + 1.2

FILE: docs/okf/frontend/features/pipeline-editor.md
OLD:
```
- **The pane (SQL-first, operator instruction 2026-09-04 — supersedes D5/D6):** one SQL `<textarea>`
  seeded for a new Step with an explicit column list over the upstream sample columns (else `SELECT *
  FROM input`), "Columns that come out" in the same `<inspecto-schema-fields-editor>` the Parse pane
  uses (fed from the test run's DESCRIBE `columnTypes`, read-only types), and Test this Step over
  `ComponentsService.previewTransform` (the existing path — `transform.sql` qualifies by prefix). The
  persisted config is **`{ sql }` only**; a legacy `fields[]` from the retired Simple grid is dropped on
  the next Apply. Full as-built, what the grid did and why it went:
```
NEW:
```
- **The pane (bespoke — ⛔ NOT the shared fields editor):** `PipelineTransformSqlDefinitionComponent` does
  not import `<inspecto-schema-fields-editor>` (`pipeline-transform-sql-definition.component.ts:91-98`). Its
  **Fields** view is its own grid (`# · Field name · From · What to do · Comes out as · Sample · ×`) over the
  `SQL_FUNCTIONS` catalog — 24 functions in 7 categories (`sql-functions.ts:95`) — with a control per
  declared parameter; its **SQL** view is an *editable* `<inspecto-sql-codemirror>` (`.html:60`), never a
  `<textarea>` and ⚠ no longer a refusal (`.ts:289-290`: the drawer's Apply arms on the first keystroke).
  A new Step seeds an explicit column list over the upstream sample columns (else `SELECT * FROM input`), and
  Test this Step posts over `ComponentsService.previewTransform` (the existing path — `transform.sql`
  qualifies by prefix). The persisted config is **`{ sql, fields }`**: `submit()` writes both on every Apply
  (`.ts:613-627` — `fields: []` from the SQL view) and `seedFrom` rehydrates the grid from `fields`
  (`.ts:388`). Full as-built, what the grid did and why it went:
```

## Edit B — row 1.1 (the prohibition)

FILE: docs/okf/frontend/features/pipeline-editor.md
OLD:
```
- ⚠ `sql` is the single `NodeAttributes` entry; the engine reads nothing else. Do not add an authoring
  artifact beside it — the retired `fields[]` is exactly that, and its presence no longer means anything.
```
NEW:
```
- ⚠ `sql` is the single `NodeAttributes` entry (`NodeAttributes.java:395-400`) — but that is the DECLARED
  attribute schema, **not** the set of keys the engine reads. 🔴 `fields` is a live executable contract:
  `RowShaper.MAP_NODE_CONFIG_KEYS` (`RowShaper.java:78-79`) includes it and `:695-696` runs it as the
  mapping, and `PipelineEditable.MAP_AUTHORED` (`:89`) allow-lists it precisely so `lower` cannot drop it —
  *"a key that becomes executable without joining this allow-list is silently dropped on save"* (`:85-88`).
  ⛔ Do not "clean up" `fields` off a `transform.sql` node: that deletes the author's grid and, on the
  projection slot, its mapping.
```

## Edit C — row 1.3

FILE: docs/okf/frontend/features/schema-mapping-authoring.md
OLD:
```
- **No dedicated expression-builder UI exists anywhere** — no function picker, no column-picker-into-EXPR,
  no live-preview-as-you-type editor. Confirmed by a codebase-wide search for `monaco`/`codemirror`/
  `ace-builds`/`ace-editor`.
- **CodeMirror is present** (`@codemirror/*` in `package.json`) but wired only to two unrelated surfaces:
  the SQL query workbench (`inspecto/data-table/sql/sql-codemirror.component.ts`) and the enrichment editor
  (`inspecto/enrichment/enrichment-editor.component.ts`) — never to the mapping `EXPR` field. Monaco/ace are
  absent entirely (no package, no references).
- No SQL function catalog is surfaced to end users on the mapping or join authoring surfaces — only free
  hints in tooltips/captions.
```
NEW:
```
> ⚠ **Superseded 2026-09-04/05 by §0 — this section's premises are gone.** The three bullets below described
> the pre-redesign state; they are restated here as as-built.

- **An expression-building surface DOES exist** — the Transform pane's function picker per row over
  `SQL_FUNCTIONS` (24 functions in 7 categories, `sql-functions.ts:95`), with a form control per declared
  parameter and "Try it on the sample" (§0).
- **CodeMirror has THREE hosts**, not two: the SQL query workbench
  (`inspecto/data-table/sql/sql-editor.component.ts:29`), the enrichment editor
  (`inspecto/enrichment/enrichment-editor.component.ts:62`) and **the Transform pane's SQL view**
  (`pipeline-transform-sql-definition.component.ts:96`, rendered at `.html:60`). Monaco/ace are still
  absent entirely (no package, no references).
- The mapping `EXPR` field itself is gone with the Load pane (§2): an EXPR rule read from a stored
  `mapping.rules[]` now arrives as a **`custom`** row in the Transform grid
  (`RecordTransform.fromMappingRules`).
```

## Edit D — row 1.4 (frontmatter)

FILE: docs/okf/frontend/features/schema-mapping-authoring.md
OLD:
```
description: End-user authoring path for a dataset's schema (the redesigned Parse pane), the SQL-first transform.sql pane, the legacy transform.map rule grid, and transform.join lookup config — with the 2026-09-03 gap list annotated by what the redesign closed, absorbed or dropped.
resource: inspecto-ui/src/app/modules/admin/pipelines/pipeline-load-definition.component.ts
```
NEW:
```
description: End-user authoring path for a dataset's schema (the redesigned Parse pane), the Record Transformer transform.sql pane (Fields grid over a function catalog, or SQL), and transform.join lookup config — with the 2026-09-03 gap list annotated by what the redesign closed, absorbed or dropped, and §2 kept as history of the deleted transform.map Load pane.
resource: inspecto-ui/src/app/modules/admin/pipelines/pipeline-transform-sql-definition.component.ts
```

## Edit E — row 1.4 (`:25-26`)

FILE: docs/okf/frontend/features/schema-mapping-authoring.md
OLD:
```
> that still exist — `SchemaEditorDialog`, the `transform.map` rule grid and the `transform.join` form
> were NOT removed; `transform.map` is now the legacy mapping path beside `transform.sql`. §6's gap list
```
NEW:
```
> that still exist — `SchemaEditorDialog` and the `transform.join` form were NOT removed. ⛔ The
> `transform.map` rule grid WAS: `transform.map` and its Load pane were **deleted 2026-09-05** (§2;
> `BuiltinNodeType.java:103`, commit `42fa41fe`), and the projection slot is always a Record Transformer
> (`transform.sql`). A stored `mapping.rules[]` still LOADS, as `fields[]` (§2). §6's gap list
```

## Edit F — rows 1.2 + 1.4 (the fourth account)

FILE: docs/okf/frontend/features/index.md
OLD:
```
* [Schema, Mapping & Transformation authoring](schema-mapping-authoring.md) - §0 the `transform.sql` Transform pane as built 2026-09-04 (Simple fields grid with five verbs that GENERATES the SQL, Advanced SQL that locks the Step, `{ sql, fields? }`, preview reuse — and what was deliberately not built), then the schema editor, the legacy `transform.map` rule grid and `transform.join` form; the 2026-09-03 gap list annotated with what the redesign closed/absorbed/dropped. The redesigned Parse pane itself is in [grammar-config](grammar-config.md).
```
NEW:
```
* [Schema, Mapping & Transformation authoring](schema-mapping-authoring.md) - §0 the `transform.sql` Transform pane as built (a bespoke **Fields** grid over the 24-function `SQL_FUNCTIONS` catalog that GENERATES the SQL, and an **editable CodeMirror SQL** view as its peer — `Fields | SQL`, reconciled both ways, never a lock; persisted `{ sql, fields }`; preview reuse — and what was deliberately not built), then the schema editor and the `transform.join` form; §2 is kept as HISTORY of the `transform.map` Load pane, **deleted 2026-09-05**. The 2026-09-03 gap list is annotated with what the redesign closed/absorbed/dropped. The redesigned Parse pane itself is in [grammar-config](grammar-config.md).
```

## Edit G — row 1.4 (the `pipeline-editor.md` mirror; the RULE survives)

FILE: docs/okf/frontend/features/pipeline-editor.md
OLD:
```
- The Load pane authors all four `TransformCompiler` types; a transform type it has never heard of
  (a hand-authored `LOOKUP`) is preserved and shown — *not offering* must never become
  *destroying*. The two specialised types pack parameters into `sourceExpression` as `|`-delimited
```
NEW:
```
- 🔴 **Read path only since 2026-09-05** (the Load pane is deleted; nothing AUTHORS these any more) — but
  every constraint below is still armed on a stored `mapping.rules[]`, which `RecordTransform.fromMappingRules`
  converts to `fields[]`. A transform type nothing has heard of (a hand-authored `LOOKUP`) is preserved,
  never dropped — *not offering* must never become *destroying*. The two specialised types pack parameters
  into `sourceExpression` as `|`-delimited
```

## Edit H — row 1.5

FILE: docs/okf/frontend/features/grammar-config.md
OLD:
```
- **ONE columns table — "Columns that come out"** (R11; `schema-fields-editor.component.html`): Use · `#` ·
  Name · Type · **Sample value** (first parsed row) · Also known as (the synonym), a search box; Selector
```
NEW:
```
- **ONE columns table — "Columns that come out"** (R11; `schema-fields-editor.component.html`): `#` ·
  Name · Type · **Sample value** (first parsed row) · Also known as (the synonym), a search box; ⛔ **no
  Use/include column** — D8 removed include-control, so every row the grid holds is emitted
  (`schema-fields-editor.component.html:46-47`; `SchemaFieldRow.include` survives only so a Grammar CSV
  carrying the column still round-trips, `schema-fields-editor.component.ts:132-145`); Selector
```

## Edit I — rows 1.6 (+ the one wrong pager number of 1.7)

FILE: docs/okf/frontend/features/grammar-config.md
OLD:
```
> ⚠ **Amended 2026-09-04 by D8–D10 (decided, NOT built)** — see
> [schema-mapping-authoring.md §7](schema-mapping-authoring.md). In short: **Parse does not drop
> columns** (it settles existence/name/type/synonym; exclusion is `transform.sql`'s job only, because
> excluding here edits the schema and `SchemaCompatibility` gates that BACKWARD), and the columns table
> must carry the wide-feed treatment (search · filter chips with counts · 10/20/100 paging · `#` = the
> position in the FULL file, never the filtered row index). Tracked as BACKLOG `AUTHORING-WIDE-1`.
```
NEW:
```
> ⚠ **Amended 2026-09-04 by D8–D10 — decided and BUILT the same day** (`4e64fe4a`, `c769719d`; verified
> 2026-09-06, and `AUTHORING-WIDE-1` is CLOSED — `docs/BACKLOG.md:36`). See
> [schema-mapping-authoring.md §7](schema-mapping-authoring.md). In short: **Parse does not drop
> columns** (it settles existence/name/type/synonym; exclusion is `transform.sql`'s job only, because
> excluding here edits the schema and `SchemaCompatibility` gates that BACKWARD — the rule is enforced at
> `schema-fields-editor.component.ts:132-140`), and the columns table carries the wide-feed treatment:
> a search box over name · selector · synonym, a **type dropdown** (`mat-select`, no counts — counted
> filter chips are the *Transform* grid's idiom, not Parse's), a paginator of **`[10, 25, 50, 100]`
> defaulting to 50** (Transform's is `[10, 20, 100]` defaulting to 10 — deliberately different, see §7 D9),
> and `#` = the position in the FULL file, never the filtered row index.
```

## Edit J — row 1.6 (same error, second file)

FILE: docs/okf/frontend/features/schema-mapping-authoring.md
OLD:
```
Search over name and synonym; view-only filter chips **with counts**; a page-size choice that reaches
down to 10. ⚠ The two grids deliberately **do not** share a default:
```
NEW:
```
Search over name and synonym (Parse also matches the selector); a page-size choice that reaches
down to 10. ⚠ The **filter idiom differs by grid**: Transform has view-only filter chips **with counts**
(All · Changed · Calculated · Needs attention, `pipeline-transform-sql-definition.component.html:87-98`),
Parse has a **type dropdown with no counts** (`schema-fields-editor.component.html:21-33`) — a type sweep
is the question a wide schema asks. ⚠ The two grids deliberately **do not** share a default:
```

## Edit K — row 1.9 (`pipeline-editor.md`, the stale list)

FILE: docs/okf/frontend/features/pipeline-editor.md
OLD:
```
- **Custody split**: a per-format parse Step (`PARSE_NODE_FRONTENDS`) defines in the right-dock
  Parse drawer; `GrammarEditorDialog` (`grammar-editor.dialog.ts`) survives ONLY for a dangling
  `use: grammar/<id>`, binary fixed-width, and a config-less generic `parser` — deliberate keeps.
```
NEW:
```
- **Custody split**: a per-format parse Step (`PARSE_NODE_FRONTENDS`) defines in the right-dock
  Parse drawer; `GrammarEditorDialog` (`grammar-editor.dialog.ts`) survives ONLY for **two** shapes —
  binary fixed-width and a generic `parser` whose config maps to no frontend
  (`isDrawerParse`, `pipeline-editor.component.ts:2267-2276`; routing comment `:2140-2144`). ⚠ Since
  2026-09-06 (PARSE-HOME-1) a **dangling** `use: grammar/<id>` opens the DRAWER, with the Grammar section
  flagged "template missing" — see the decision note at the end of this file, which is the current list.
```

## Edit L — row 1.9 (`grammar-config.md:118-121`)

FILE: docs/okf/frontend/features/grammar-config.md
OLD:
```
- **Dialog custody unchanged** (R7): `openNodeConfig` (`pipeline-editor.component.ts:2126-2154`) already
  sends every drawer-capable delimited node to the drawer; `GrammarEditorDialog` remains ONLY for a
  grammar-bound (`use: grammar/x`) node, a dangling binding, or binary fixed-width. Removing it is a
  Components-registry decision — BACKLOG, not made here.
```
NEW:
```
- **Dialog custody unchanged** (R7): `openNodeConfig` (`pipeline-editor.component.ts:2131-2160`) already
  sends every drawer-capable delimited node to the drawer. ⚠ **Superseded since:** `GrammarEditorDialog`
  now remains ONLY for **binary fixed-width** and a **generic `parser` that maps to no frontend**
  (`isDrawerParse`, `:2267-2276`). A grammar-**bound** per-format node opens the drawer as an inline COPY
  (D4), and a **dangling** binding does too since 2026-09-06 (PARSE-HOME-1). Removing the dialog is a
  Components-registry decision — BACKLOG, not made here.
```

## Edit M — row 1.9 (`grammar-config.md:242-244`)

FILE: docs/okf/frontend/features/grammar-config.md
OLD:
```
`GrammarEditorDialog` is left with two jobs: a **dangling** `use: grammar/<id>` whose component does not
exist, and **binary fixed width**. The plain `parser` type still has no drawer pane either — the plugin
subtype covers only nodes with an *authored* `parsing.plugin` block, not the unbound generic type.
⚠ "P3d retires the dialog entirely" was always false and stays false:
the dangling and binary cases are deliberate keeps. The dangling case is deliberate: with nothing to resolve there is no faithful copy
to migrate to, and seeding the drawer with defaults would replace the operator's broken reference with a
silently invented Grammar. The binary case (`record: bytes`, detected by `isBinaryFixedWidth`) lifts to
```
NEW:
```
`GrammarEditorDialog` is left with two jobs: **binary fixed width**, and the plain `parser` type, which has
no drawer pane — the plugin subtype covers only nodes with an *authored* `parsing.plugin` block, not the
unbound generic type. ⚠ **The dangling case moved OFF the dialog on 2026-09-06** (PARSE-HOME-1): a
per-format node whose `use: grammar/<id>` no longer resolves opens the drawer with the Grammar section
flagged "template missing", seeded with a blank Grammar of the node's own frontend while `use:` is kept on
the draft — showing the reader the broken reference beat hiding it in a dialog
(`isDrawerParse`, `pipeline-editor.component.ts:2271-2275`).
⚠ "P3d retires the dialog entirely" was always false and stays false:
the binary case is a deliberate keep. The binary case (`record: bytes`, detected by `isBinaryFixedWidth`) lifts to
```

## Edit N — row 1.10 (`:338`)

FILE: docs/okf/frontend/features/pipeline-editor.md
OLD:
```
  `Parse sample` lives in the sample strip; the FIRST derivation steers to the Types & columns tab;
```
NEW:
```
  `Parse sample` lives in the sample strip; the FIRST derivation **expands the `types` section** —
  labelled *"How values are understood"* (`parsing-attributes.ts:100-104`), a `mat-expansion-panel`, not a
  tab — via `showTab('types')`, which since the sectioned redesign means "expand"
  (`grammar-editor.component.ts:709-713`);
```

## Edit O — row 1.10 (`:368`)

FILE: docs/okf/frontend/features/pipeline-editor.md
OLD:
```
read-only in the Types tab and Column metadata list (it IS an output column, stamped at write
```
NEW:
```
read-only in the `types` section's columns table — ⛔ never a "Column metadata list", which left Parse
entirely with the metadata grid (D2) — (it IS an output column, stamped at write
```

---

# Non-row findings worth folding into the same change

1. **Stale code javadoc, `inspecto-ui/src/app/modules/admin/pipelines/pipeline-editor.component.ts:2259-2265`** —
   lists a dangling binding as a dialog exception; the body at `:2271-2275` routes it to the drawer, and
   `openNodeConfig`'s comment at `:2140-2144` is correct. One-line fix.
2. **Stale code comment, `inspecto-ui/src/app/inspecto/schema/schema-fields-editor.component.html:37`** —
   still lists the column order as `Use · # · Name · …`, contradicting the D8 comment nine lines below.
   Plausibly the origin of `grammar-config.md:80`.
3. **Cosmetic code defect, `schema-fields-editor.component.html:253-296`** — the synthetic filename row
   renders a leading disabled `Use` checkbox `<td>` (`:255-260`), giving it **8** cells where the header
   and body rows have **7**. A D8 leftover.
4. **Stale line citation, `schema-mapping-authoring.md:31-32`** — the `transform.sql` routing arm is at
   `pipeline-editor.component.html:743`, not `:755-767`.
5. **`transform.map` naming across `pipeline-editor.md:485-490`** — covered by the banner's explicit
   re-reading rule at `:478-481`, so it needs **no** edit. Only `:495-502` (Edit G) fell outside it.
