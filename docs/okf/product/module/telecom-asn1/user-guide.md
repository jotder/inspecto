---
type: Guide
title: Telecom ASN.1 decoder — user guide
description: Previewing an ASN.1 CDR file in the parse drawer and configuring a Pipeline to load it.
resource: providers/inspecto-telecom-asn1/
tags: [module, telecom-asn1, asn1, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Telecom ASN.1 decoder — user guide

Available in Standard and higher Editions. If the **ASN.1 — BER/DER encoded records** parser is not offered,
the module is not installed (see [admin-guide](admin-guide.md)).

## 1. Look at an unknown file first

In the Pipeline's parse drawer pick the ASN.1 parser and load a sample file. Leave the grammar empty: BER is
self-describing, so you get the file's raw structure — one tree per record, nodes labelled by tag
(`[0]`, `[APPLICATION 1]` …), leaves showing their size and the first bytes of the value.

If no records appear, the framing is wrong: set **File header bytes** and/or **Record header bytes**
(e.g. 50 and 4 for Huawei-framed files).

## 2. Bind the vendor grammar

| Field | Key | Notes |
|---|---|---|
| ASN.1 grammar | `asn1.grammar` | Paste the X.680 module text. |
| ASN.1 grammar file | `asn1.grammar_file` | Or name a stored `.asn` / `.asn1` file beside the Pipeline's config (e.g. `msc_cdr.asn`). Pasted text wins if both are set. |
| Decode Profile | `asn1.profile_file` | A vendor profile (`.toon` with one `asn1:` block). Keys set here win over the profile's. |
| Root type | `asn1.root_type` | The record type to bind, e.g. `CallEventRecord`. Required with a grammar. |
| Strictness | `asn1.strictness` | BER (default, permissive), DER or CER. |
| File header bytes / Record header bytes | `asn1.file_header_length` / `asn1.record_header_length` | Framing, default 0. |
| Max value bytes | `asn1.max_value_bytes` | Largest single value accepted (default 64 MiB). |
| Preview records | `asn1.max_records` | Records shown (default 50, max 1000). |

With the grammar bound, the same bytes come back with field names and decoded values (TBCD numbers,
timestamps, addresses).

## 3. Map records to Tables

Ingest needs **segments**: one segment schema per record type, whose `raw.fields[].selector` is a dotted path
to a leaf of the decoded record (e.g. `servedIMSI`, `recordExtensions.chargeAmount`). Every segment gets an
extra `EVENT_TYPE` column carrying the record type.

```yaml
parsing:
  frontend: asn1
  asn1:
    grammar_file: msc_cdr.asn
    root_type: CallEventRecord
    strictness: BER
    segments:
      moCallRecord: config/cdr/mo_call_schema.toon
```

A selector that points at a sub-record or a repeated list gives NULL. To load a repeated element, declare it
as a child segment (`children`) or reduce it to one value (`derive`); when either is declared the drawer shows
the resulting rows under the tree, built by the same code the load runs.

## What happens to a bad file

A file with any malformed record is quarantined as unreadable — never half-loaded. A file where no record
matches a declared segment is quarantined as a mismatch. Check the Pipeline's quarantine list after a run.

## References

- [spec](spec.md) — every key and limit
- [engine spec-parsing-options](../engine/spec-parsing-options.md) — the full `parsing:` block
