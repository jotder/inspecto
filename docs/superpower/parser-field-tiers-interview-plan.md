# Parser field tiers — interview #2 observation-session kit (D13)

**Status:** READY TO RUN — awaiting a scheduled session with a real onboarding user. ·
**Prepared:** 2026-08-28 · **Owner decision of record:** D13 (2026-07-25) parked the tier
assignment until "a real onboarding-observation session (interview #2); explicitly NOT an
engineering guess". This kit is that session, prepared: protocol, tasks, per-field capture
sheets, and the analysis rule that converts observations into tier assignments.
**Backlog row of record:** `BACKLOG.md` §7 *Parser required-vs-advanced field tiers*.

## What the session must decide (two questions, not one)

1. **Tier placement** — for every parser/grammar field: `required` (top, always visible) vs
   `optional` (second disclosure) vs `advanced` (collapsed). Today's placements are engineering
   guesses recorded in `parsing-attributes.ts` `tier:` values.
2. **What "required" means** — grounded 2026-08-28: every `tier:'required'` field in
   `parsing-attributes.ts` and `node-attributes.ts` carries `required: false` validators —
   "required" currently means *shown in the top disclosure tier*, never *validator-enforced*
   (the lone hard default is `transform.route`'s `mode: 'case'`). The session must observe
   whether users ever submit with a top-tier field untouched **and get a bad outcome** — that,
   not intuition, decides whether the tier should gain real validation.

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
  1. Onboard the **csv** sample (delimited lane — the richest field set, 4 tabs).
  2. Onboard the **fixedwidth** sample (slice table + robustness defaults differ).
  3. Onboard the **excel** sample (sheet/range/header — no encoding/compression).
  4. Onboard the **json** sample (format select drives `dependsOn` visibility).
  5. (Stretch) point the csv feed at a *deliberately dirty* copy (a junk header line +
     one short row) — this is what exercises the Robustness tab for real.
- **Recording:** one capture sheet per lane (below); mark each field the first time each
  applies. Timestamp tab switches; note every hesitation > ~5 s on a visible field.

## Capture sheet — marks

Per field: **T** touched (changed the value) · **D** left at default and outcome fine ·
**S** stumbled (hesitation, wrong value then corrected, or visible confusion) ·
**A** asked the facilitator · **M** missed-but-needed (left untouched and the test run
failed/was wrong because of it) · **F** found-late (needed it, looked in the wrong tab first).

## Analysis rule (how marks become tiers — agreed BEFORE the session so the data decides)

- **required tier:** any field marked **M** by the participant, or **T** in ≥3 of 4 lanes'
  happy paths. If a required-tier field is ever **M**, that is the evidence for adding a real
  validator (question 2) — one observation suffices to file it; two decide it.
- **optional tier:** **T** or **S** only on the dirty-data task (task 5), or lane-specific
  touches (e.g. `xlsx__range`).
- **advanced tier:** never touched in any task, and no **A** — regardless of what an engineer
  thinks its importance is.
- A field marked **F** stays in its evidence-assigned tier but files a *tab placement* note —
  tab membership (`grammarTabsFor`) is a separate, cheaper fix than tiering.

## Field inventory (the served truth to score against)

Grounded 2026-08-28 from `parsing-attributes.ts:1-637` (the five built-in lanes; UI-owned) and
`node-attributes.ts` (fallback mirror of `GET /pipelines/node-types`). ⚠ For plugin lanes
(ASN.1 etc.) the form renders from the **served** `GET /parsers` `grammarSchema` — score those
against the live payload, not any file. ⚠ Three fields deliberately have **no default**
(`delimited__strict_mode`, `delimited__engine`, `xlsx__stop_at_empty`): a spec default would
materialize into stored grammar copies on save — the session must not "fix" that.

### Delimited (tabs: Dialect / Types / Robustness / Files)
| Field | Today's tier | Default |
|---|---|---|
| `delimited__delimiter` | required | `,` |
| `delimited__has_header` | required | true |
| `delimited__quote` / `escape` / `comment` | optional | — |
| `delimited__skip_header_lines` / `skip_junk_lines` / `skip_tail_lines` / `skip_tail_columns` | optional | — |
| `encoding` (shared key) | advanced | — |
| `delimited__date_formats` / `timestamp_formats` / `null_strings` | optional | — |
| `delimited__strict_mode` / `delimited__engine` | optional | *(none, deliberate)* |
| `ignore_errors` / `null_padding` / `store_rejects` | optional | padding=false here |
| `rejects_table` / `rejects_scan` / `rejects_limit` | advanced | — |
| `include_prefixes` / `include_regex` / `exclude_prefixes` / `exclude_regex` / `filter_target_column` | advanced | — |
| `where` (pre-parse SQL) | optional | — |
| `compression` | optional | — |

### Fixedwidth
`delimited__has_header` (required, true) · slice table (own editor, not a spec field) ·
`fixedwidth__min_record_length` (optional) · `fixedwidth__trim` (BOTH) · shared date/timestamp
formats · robustness set with `null_padding` defaulting **true** (same key, different engine
default — help text carries the difference) · `encoding`/`compression` (advanced).

### Excel
`xlsx__sheet` (required, none = first sheet) · `xlsx__header` (required, true) · `xlsx__range`
(optional, A1-notation validated) · `xlsx__normalize_names` (optional) · shared date/timestamp
formats · `xlsx__stop_at_empty` (optional, no default — deliberate) · `xlsx__ignore_errors`
(optional). No encoding/compression.

### JSON
`json__format` (required, `newline`) · `json__records_path` (optional, `$`, hidden on newline)
· `delimited__skip_header_lines` (advanced) · shared date/timestamp formats ·
`json__ignore_errors` (optional, auto-format only) · `json__maximum_object_size` (advanced,
hidden on newline) · `compression` (advanced).

### text_regex (flat, one tab)
`text_regex__pattern` (required, no default, named capture groups) ·
`delimited__skip_header_lines` (advanced) · robustness set (padding true) · `encoding` (advanced).

## Deliverable of the session

One PR: updated `tier:` values in `parsing-attributes.ts` (and `node-attributes.ts` where the
session reached node config), each change annotated with the observation that earned it; plus a
decision note on question 2 (validator or visual-only) into
`okf/frontend/features/grammar-config.md`. Then this plan archives per the docs lifecycle and
the §7 row closes.
