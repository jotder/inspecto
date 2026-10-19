---
name: no-data-or-client-files-in-commits
description: "Never commit data payloads or client-specific material (ASN.1/BER captures, CDRs, parity corpora, vendor .asn, real names/identifiers); fixtures must be obviously synthetic"
metadata:
  type: feedback
---

Commits carry code, config schemas and docs — not payloads. The ASN.1 parity corpus is real operator CDR data
(DATA-GOV-1); once pushed, data is unrecallable.

**How to apply:** before staging, scan the file list for `.asn .ber .der .dat .cdr .csv .parquet` and grep the
diff for operator names and long numeric identifiers. Fixtures are hand-written (`imsi="42"`), never trimmed from
real captures. Telecom vocabulary is fine; real values are not. The deliberate exception un-ignored in
`.gitignore` is `spaces/*/data/samples/**` + `inspecto/examples/**/samples/**` — do not extend it.


