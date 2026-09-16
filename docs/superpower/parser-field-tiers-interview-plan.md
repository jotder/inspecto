> ✅ **BACK IN THE ACTIVE TIER 2026-09-16 — the gate has fired and this kit is being run.**
> It was archived 2026-09-09 as an unexecuted research instrument; a real onboarding user is now
> available, so the work is in flight and the plan lives here again (docs lifecycle tier 2). It returns
> to `plans-archive/` when the session has been run and its deliverable landed.
>
> ✅ **RE-GROUNDED 2026-09-16 against today's UI**, as the board row demanded before scheduling. The
> drift was worse than "inventory and task script": the kit's own **premise** and its **second question**
> were both false, and half its deliverable was impossible. Every correction is marked 🔴 **RE-GROUNDED**
> in place below. What did NOT drift: every per-lane **tier** assignment, the session protocol, the
> capture marks (except **F**), the plugin-lane caveat and the three deliberate no-default fields.
>
> ✅ **The load-bearing half stays in the current tier**, not here: the two questions, the
> **pre-agreed analysis rule**, the capture marks and the deliverable are in
> [`okf/frontend/features/grammar-config.md`](../okf/frontend/features/grammar-config.md)
> §"The D13 field-tier session". ⛔ **That rule's entire evidentiary force comes from being agreed
> BEFORE the session — re-grounding a refuted PREMISE is not re-deriving the RULE, and the rule below is
> untouched.**

# Parser field tiers — interview #2 observation-session kit (D13)

**Status:** READY TO RUN — awaiting a scheduled session with a real onboarding user. ·
**Prepared:** 2026-08-28 · **Owner decision of record:** D13 (2026-07-25) parked the tier
assignment until "a real onboarding-observation session (interview #2); explicitly NOT an
engineering guess". This kit is that session, prepared: protocol, tasks, per-field capture
sheets, and the analysis rule that converts observations into tier assignments.
**Backlog row of record:** `BACKLOG.md` §7 *Parser required-vs-advanced field tiers*.

## What the session must decide (two questions, not one)

1. **Tier placement** — for every parser/grammar field. Today's placements are engineering guesses
   recorded in `parsing-attributes.ts` `tier:` values.
   🔴 **RE-GROUNDED 2026-09-16 — this question's framing was FALSE on the pane the session observes.**
   It used to read *"`required` (top, always visible) vs `optional` (second disclosure) vs `advanced`
   (collapsed)"*. On the Parse pane **tier no longer controls disclosure at all — only ORDER**: the pane
   renders `<inspecto-schema-form [flat]="true">` per section
   (`grammar-editor.component.html:141-148`), and *"every tier renders in one single column, in tier
   order"* (`schema-form.component.ts:79-80`). **All three tiers are always visible**; the only
   collapsing is the section accordion. The tiered, non-flat path survives in job/alert dialogs only.
   ⇒ the session still decides ORDER and grouping, and ⚠ the analysis rule's *"advanced tier: never
   touched"* bucket now scores a field the participant **can always see** — score it as written, and
   record that fact against the result rather than adjusting the rule.
2. **What "required" means** — 🔴 **RESTATED 2026-09-16; the 2026-08-28 grounding was REFUTED.**
   It claimed *"every `tier:'required'` field carries `required: false` validators … the lone hard
   default is `transform.route`'s `mode`"*. The derivation is
   `return spec.required ?? spec.tier === 'required';` (`attribute-spec.ts:131`) — so an **omitted**
   `required:` key means **validator-ENFORCED**, and several fields omit it:
   **`text_regex__pattern`** (`parsing-attributes.ts:689-695`) among the parser fields, and **nine node
   attributes** (`transform.sql.sql`, `transform.route.mode`, `transform.dedup.keys`,
   `transform.summarize.group_by`/`measures`, `transform.join.reference`/`on`,
   `transform.lookup.column`/`mappings`). ⇒ **"required" today means enforced for SOME fields and
   visual-only for others, with no stated principle** — which is a sharper question than the original,
   not a softer one. The session must observe whether users submit with a top-tier field untouched
   **and get a bad outcome**; that, not intuition, decides whether the tier's meaning should be made
   uniform, and in which direction.
   ⚠ **Score both kinds.** A field that is already enforced cannot produce an **M** (the validator stops
   the submit) — so an enforced field's evidence is **S**/**A** at the point of refusal, and an M on an
   unenforced top-tier field is the evidence for adding one.

## Session protocol

- **Participant:** one real onboarding user (data-ops operator unfamiliar with this product's
  editor; NOT a teammate who built it). 60–90 min, screen-shared or in person.
- **Environment:** a fresh bundle (`inspecto-deploy.zip`), `seed-inbox` run so all four
  format-example feeds are present (csv / fixedwidth / excel / json — the pack under
  `spaces/default/config/`), server via `serve.sh`. ⚠ Do NOT pre-open any dialog for them.
- **Facilitator rule:** think-aloud, no steering. Answer a direct question only after logging
  it as an **ASKED** mark (the ask itself is data).
- **Tasks** (each starts from the Pipelines list, ends at a green test run —
  Build → Test is the journey of record, `okf/backend/engine/pipeline-test-run.md`):
  1. Onboard the **csv** sample (delimited lane — the richest field set).
     🔴 **RE-GROUNDED: "4 tabs" is wrong.** The pane shows **three collapsible sections**, the first
     expanded (`grammar-editor.component.ts:236-248`), labelled *"How the file is written" / "How values
     are understood" / "When a row looks wrong"* (`parsing-attributes.ts:100-104`). The only real tabs
     left are **Sample | Parsed** (`grammar-editor.component.html:47-98`) — a different control the kit
     never mentioned. Per-format first sections: xlsx *"Sheet & range"*, json *"Format & records"*,
     fixedwidth *"Record layout"*.
  2. Onboard the **fixedwidth** sample (slice table + robustness defaults differ).
  3. Onboard the **excel** sample (sheet/range/header — no encoding/compression).
  4. Onboard the **json** sample (format select drives `dependsOn` visibility).
  5. (Stretch) point the csv feed at a *deliberately dirty* copy (a junk header line +
     one short row) — this is what exercises the *"When a row looks wrong"* section for real.
     ⚠ **Confound to note, not remove:** that section now carries an inline `inspecto-alert`
     (`grammar-editor.component.html:155-162`) telling the user rejected rows land in the rejects
     ledger. It may itself change behaviour; log whether the participant reads it.
- **Recording:** one capture sheet per lane (below); mark each field the first time each
  applies. 🔴 **RE-GROUNDED: there are no tab switches to timestamp.** The equivalent observable events
  are **section open/close** and the per-section **"N changed" badge**
  (`grammar-editor.component.html:126-136`). Note every hesitation > ~5 s on a visible field.
  ⚠ **The pane's dominant surface has no capture row and needs one:** the single *"Columns that come
  out"* table (`schema-fields-editor.component.html`) — Use · # · Name · Type · Sample value · Also
  known as, plus a search box and a *"Detect column types"* property. Much of a 60–90 min session will
  be spent there; the column-metadata grid left this pane (D2 → Transform).

## Capture sheet — marks

Per field: **T** touched (changed the value) · **D** left at default and outcome fine ·
**S** stumbled (hesitation, wrong value then corrected, or visible confusion) ·
**A** asked the facilitator · **M** missed-but-needed (left untouched and the test run
failed/was wrong because of it) · **F** found-late (needed it, looked in the wrong **section** first).
🔴 **RE-GROUNDED: "tab" → "section".** `AttributeSpec.tab` was renamed `.section` (frontend-only) in
`d012f721`; `grammarTabsFor` still exists under that name but now returns the SECTION shell. The mark
is unchanged in meaning.

## Analysis rule (how marks become tiers — agreed BEFORE the session so the data decides)

- **required tier:** any field marked **M** by the participant, or **T** in ≥3 of 4 lanes'
  happy paths. If a required-tier field is ever **M**, that is the evidence for adding a real
  validator (question 2) — one observation suffices to file it; two decide it.
- **optional tier:** **T** or **S** only on the dirty-data task (task 5), or lane-specific
  touches (e.g. `xlsx__range`).
- **advanced tier:** never touched in any task, and no **A** — regardless of what an engineer
  thinks its importance is.
- A field marked **F** stays in its evidence-assigned tier but files a *section placement* note —
  section membership (`grammarTabsFor`) is a separate, cheaper fix than tiering.

## Field inventory (the served truth to score against)

Grounded 2026-08-28 from `parsing-attributes.ts:1-637` (the five built-in lanes; UI-owned) and
`node-attributes.ts` (fallback mirror of `GET /pipelines/node-types`). ⚠ For plugin lanes
(ASN.1 etc.) the form renders from the **served** `GET /parsers` `grammarSchema` — score those
against the live payload, not any file. ⚠ Three fields deliberately have **no default**
(`delimited__strict_mode`, `delimited__engine`, `xlsx__stop_at_empty`): a spec default would
materialize into stored grammar copies on save — the session must not "fix" that.

### Delimited — sections: *How the file is written* / *How values are understood* / *When a row looks wrong*

🔴 **RE-GROUNDED 2026-09-16.** No tier changed. What changed: the `files` section was dissolved, one
field was **deleted**, one field the kit never listed is present on **every** lane, the robustness keys
are `delimited__*`-prefixed (the kit wrote them bare), and **nine defaults** were wrong.

| Field | Today's tier | Default | Label the participant sees |
|---|---|---|---|
| `delimited__delimiter` | required | `,` | Column separator |
| `delimited__has_header` | required | true | First row is the header |
| `delimited__quote` | optional | `"` | Text quote |
| `delimited__escape` / `comment` | optional | — | / Ignore lines starting with |
| `delimited__skip_header_lines` / `skip_junk_lines` / `skip_tail_lines` / `skip_tail_columns` | optional | `0` (all four) | Skip lines at the top / Skip unreadable lines at the top (up to) / Skip lines at the bottom / Drop extra columns on the right |
| `encoding` (shared key) | advanced | `utf-8` | — |
| `source_timezone` | optional | — | "Source time zone" (~418 options) <!-- vocab-allow: quotes the label the participant actually sees on screen --> |
| `delimited__date_formats` / `timestamp_formats` / `null_strings` | optional | — | … / Words that mean "no value" |
| `delimited__strict_mode` / `delimited__engine` | optional | *(none, deliberate)* | Strict CSV rules (RFC-4180) / Reader |
| `delimited__ignore_errors` / `__null_padding` / `__store_rejects` | optional | `true` / false / `true` | Rows that cannot be read go to the review bin / Fill missing columns with empty / Keep rejected rows for review |
| `delimited__rejects_table` / `__rejects_scan` / `__rejects_limit` | advanced | `reject_errors` / `reject_scans` / — | Review bin table / / Stop keeping bad rows after |
| `include_prefixes` / `include_regex` / `exclude_prefixes` / `exclude_regex` / `filter_target_column` | advanced | — | — |
| `compression` | optional | `auto` | Compressed file |

🔴 **`where` (pre-parse SQL) is GONE** — deleted by `d012f721` (D3: *"the row filter property is gone …
`transform.filter` is the Step"*). Do not score it.
🔴 **`source_timezone` is on ALL FOUR scripted lanes** (`parsing-attributes.ts:268, :429, :546, :658`)
and the kit never mentioned it — the largest single inventory gap. A session that never shows it
observes nothing about it.
⚠ **Where the dissolved `files` section's content went:** `encoding` and `compression` moved into the
FIRST section; the collection pointer is now **read-only text** on Parse (*"Reads: `<value>` — from the
`<source>` collector step"*) and is edited on the **Collector** pane; partitioning moved to the **Sink**
pane. A script step sending a participant to a *"Files & MetaData"* tab sends them nowhere.

### Fixedwidth
`delimited__has_header` (required, true) · slice table (own editor, not a spec field) ·
`fixedwidth__min_record_length` (optional) · `fixedwidth__trim` (BOTH) · shared date/timestamp
formats · the `delimited__*` robustness set with `__null_padding` defaulting **true** (same key,
different engine default — help text carries the difference) · `encoding`/`compression` (advanced)
· 🔴 **`source_timezone` (optional)** — add to the sheet.

### Excel
`xlsx__sheet` (required, none = first sheet) · `xlsx__header` (required, true) · `xlsx__range`
(optional, A1-notation validated) · `xlsx__normalize_names` (optional) · shared date/timestamp
formats · `xlsx__stop_at_empty` (optional, no default — deliberate) · `xlsx__ignore_errors`
(optional) · 🔴 **`source_timezone` (optional)** — add to the sheet. No encoding/compression.

### JSON
`json__format` (required, `newline`, label *"Document shape"*) · `json__records_path` (optional, `$`,
hidden on newline) · `delimited__skip_header_lines` (advanced) · shared date/timestamp formats ·
`json__ignore_errors` (optional, auto-format only) · `json__maximum_object_size` (advanced,
hidden on newline) · `compression` (advanced) · 🔴 **`source_timezone` (optional)** — add to the sheet.

### text_regex (flat — its specs declare no `section`, so no accordion renders)
`text_regex__pattern` (required, no default, named capture groups) ·
`delimited__skip_header_lines` (advanced) · the `delimited__*` robustness set (padding true) ·
`encoding` (advanced).

🔴 **RE-GROUNDED — and this lane is the sharpest gap in the whole kit.** `text_regex__pattern` is the
ONE parser field that is genuinely **validator-enforced** (it omits `required:`, so
`spec.required ?? spec.tier === 'required'` resolves true) — i.e. it is the only lane that can show the
participant what question 2 is actually about. ⛔ **And there is no `text_regex` sample pack under
`spaces/default/config/`**, so the four-task script never reaches it. Either add a fifth sample before
the session, or record explicitly that question 2 was observed on node attributes only — do not let the
gap pass unnamed, or the session will answer question 2 from the lanes where nothing is enforced.

## Deliverable of the session

One PR: updated `tier:` values in `parsing-attributes.ts`, each change annotated with the observation
that earned it; plus a decision note on question 2 into `okf/frontend/features/grammar-config.md`.
Then this plan returns to `plans-archive/` per the docs lifecycle and the board row closes.

🔴 **RE-GROUNDED: the `node-attributes.ts` half is IMPOSSIBLE and is struck.** Since NODE-TYPE-MIRRORS-1
(2026-09-15) that file is ~35 lines importing a generated contract
(`app/inspecto/contracts/node-attributes.contract.json`) and holds **zero** `tier:` entries — node tiers
are authored in **Java** (`NodeAttributes.java`) and regenerated into the JSON. A PR editing
`node-attributes.ts` would change nothing at all. ⇒ if the session reaches node config, the change lands
in `NodeAttributes.java` and the contract is regenerated; ⛔ do not hand-edit the JSON either.
