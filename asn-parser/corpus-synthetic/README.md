# Synthetic corpus (DATA-GOV-1 — the committed CI complement)

The real carrier corpus (`corpus/`, `config/`, `data/`) is operator data: it never enters git and its parity
harness (`ParityCheckTest`, `RealGrammarsTest`) is opt-in (`-Dasn.corpus.tests=true`) wherever the encrypted
archive is provisioned. **This directory is the small synthetic subset the DATA-GOV-1 decision (2026-09-06) asked
for**: hand-built BER that exercises the decoder's structural machinery on every CI run, with no subscriber
identifier, no carrier file name and no vendor grammar in it.

It is a **complement, not a replacement** — every parity defect so far came from real files. What it pins is
that the new stack still decodes, frames, binds and maps what it did yesterday.

## Layout

One directory per case:

| File | Meaning |
|---|---|
| `grammar.asn` | a small ASN.1 module written for the case (not a vendor grammar) |
| `case.json` | `root` type, `fileHeaderLength`, `recordHeaderLength` (null = none), `padding` bytes |
| `data.hex` | the BER file as hex TEXT — `#` comments and whitespace are ignored. Text, so the repo-wide `*.ber` ban stays intact and the bytes are reviewable in a diff |
| `expected.jsonl` | one `RecordMapper` map per decoded record, JSON, in file order |

## Cases

| Case | Exercises |
|---|---|
| `backtoback` | back-to-back records, no framing; `[APPLICATION n]` root, OPTIONAL absent/present, SEQUENCE OF, CHOICE, multi-byte lengths and INTEGER values |
| `huawei_framed` | the vendor file shape without the vendor: a 50-byte file header, a 4-byte header before every record (`skipOnly`), 0x00 trailing padding; BOOLEAN, OCTET STRING, nested SEQUENCE |

## Running / regenerating

`SyntheticCorpusTest` (asn-golden) runs in the normal reactor — no flag. To regenerate `expected.jsonl` after an
INTENTIONAL decoder change:

```
mvn -o -q test -pl asn-parser/asn-decoders/asn-golden -am -Dtest=SyntheticCorpusTest -Dasn.synthetic.write=true
```

then review the diff — a changed expectation is a behaviour change and needs a reason in the commit.

## Adding a case

Keep it synthetic: invent the grammar, invent the values. If you need a vendor grammar's shape, mimic the shape
(tags, framing, nesting), never copy the grammar or a captured record.
