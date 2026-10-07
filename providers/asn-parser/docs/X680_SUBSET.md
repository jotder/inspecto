# X.680 subset statement

What `asn-schema` parses, what it deliberately does not, and where it knowingly deviates from
X.680 to accept the vendor grammars in `config/`. ([REDESIGN.md](REDESIGN.md) §4.3.)

## Supported

**Module structure** — `DEFINITIONS`, tag-default clauses (`IMPLICIT TAGS`, `EXPLICIT TAGS`,
`AUTOMATIC TAGS`), `BEGIN`/`END`, `IMPORTS`, `EXPORTS`, type assignments.

**Types** — `BOOLEAN`, `INTEGER` (incl. named-number lists), `ENUMERATED`, `REAL`, `BIT STRING`,
`OCTET STRING`, `NULL`, `OBJECT IDENTIFIER`, all the restricted character-string types
(`IA5String`, `NumericString`, `PrintableString`, `GraphicString`, `VisibleString`, `UTF8String`,
`GeneralString`, …), `UTCTime`, `GeneralizedTime`, `SEQUENCE`, `SEQUENCE OF`, `SET`, `SET OF`,
`CHOICE`, `ANY`, tagged types (`[n]`, `[APPLICATION n]`, `[PRIVATE n]`, `[UNIVERSAL n]` with
explicit `IMPLICIT`/`EXPLICIT` overrides), type references, and `COMPONENTS OF`.

**Component modifiers** — `OPTIONAL`, `DEFAULT`, extension markers (`...`) and extension
additions, tolerated and skipped.

**Constraints** — parsed and **discarded**. Size, range, alphabet and inner-subtype constraints
do not affect decoding: a value that violates its constraint still decodes. This is deliberate —
the CDR grammars are riddled with constraints that real vendor output violates, and rejecting
those records would lose data the legacy decoder kept.

## Not supported (out of scope, v1)

- **Information object classes**, object sets, `WITH SYNTAX`, parameterised types — X.681/682/683.
  Where a grammar uses them, the reference decays to `OCTET STRING` with a warning.
- **Value notation** — value assignments are skipped, not evaluated. `DEFAULT` values are
  recorded textually and never synthesised into a decoded record (an absent component stays
  absent, matching legacy).
- **Macros** (deprecated X.208), `ANY DEFINED BY` resolution — `ANY` is decoded as a raw TLV/hex
  subtree rather than dispatched on a companion field.
- **Schema-aware encoding** (the write path) beyond the TLV dump.

## Deliberate deviations — vendor dialect tolerance

The `.asn` files in `config/` are hand-doctored and **not valid ASN.1**. Strict mode
(`Asn1Parser.parse` / `SchemaCompiler.compile`) rejects them with `line:column`. Lenient mode
(`parseLenient` / `compileLenient`) recovers per component and per assignment, reports every
recovery as a warning, and is what the harness uses. Each tolerance below is pinned by a test in
`Asn1ParserTest`:

| Tolerance | Seen in |
|---|---|
| Anonymous module (`DEFINITIONS` with no module name) | several |
| Unterminated `EXPORTS everything` (no `;`) | vendor grammars |
| `_` in identifiers (`input_called_number`) — not X.680 | Huawei |
| Missing `END` at EOF | truncated files |
| Two-word pseudo-types (`HEX STRING`) | vendor |
| Bodyless `ENUMERATED` | vendor |
| Stray identifiers left by `--x, --` comment pairs | hand edits |
| Case-insensitive keywords (lowercased `definitions`, `sequence`) | `nrtrde_2.1.asn` |
| Trailing / missing commas in component lists | hand edits |
| Unresolved type references decay to `OCTET STRING` | `tap 3.12.asn` (botched `Currency` replace) |

Two recovery invariants make the difference between losing a component and losing a whole module,
and both were real bugs: **`expect*` throws without consuming the offending token**, so recovery
restarts exactly at the failure point; and **brace depth never goes negative**, so an orphaned
`]` cannot swallow the rest of the module.

## Binder-side tolerances

Decoding real vendor output needs latitude the grammar cannot express; these live in
`SchemaBinder`, are pinned by `SchemaBinderTest`, and each mirrors legacy tag-map behaviour:

- A record union declared as `SET`/`SEQUENCE` (rather than `CHOICE`) is matched by **component
  tag**, not by the union's own tag.
- `SEQUENCE` and `SET` universal tags (16/17) are interchangeable for constructed values.
- Mandatory components may be **absent** — the ordered cursor scans past them.
- **Repeated or out-of-order tags** wrap around to earlier components without moving the cursor.
- Unmatched nodes are **never dropped**: they keep a tag-path name and an uppercase-hex value.
