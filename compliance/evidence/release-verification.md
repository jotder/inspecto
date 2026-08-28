# Release verification — checking an Inspecto artifact's integrity and authenticity

**Audience:** the deploying organization (customer) and any auditor sampling release integrity.
**Control:** SOC 2 **CC8** (authorized, tracked changes) · ISO 27001 **8.24** (cryptography) ·
NIST **SI-2** / **SR** (supply-chain integrity). Matrix row:
[`../controls-matrix.md`](../controls-matrix.md) → CC8 (release integrity).
**Closes:** compliance plan C3 gap **G2**.

Every Inspecto release bundle ships with a checksum, and — when the publisher signs — a detached
signature. **Integrity** answers "did these bytes arrive intact?"; **authenticity** answers "did
they come from Gamma Analytics?" They are different questions and need different files.

| File | Always present? | Answers |
|---|---|---|
| `<artifact>.sha256` | **yes** — written unconditionally at package time | integrity |
| `<artifact>.asc` | only when the publisher ran `package.ps1 -Sign` | authenticity |

⚠ **A checksum alone proves nothing about origin.** Anyone who can replace the bundle can replace
the `.sha256` beside it. Use it to detect a truncated or corrupted download; use the **signature**
to decide whether to trust the source. If your threat model includes a compromised mirror, verify
the `.asc` or do not install.

## 1. Verify integrity (always available)

The `.sha256` is written in `sha256sum` format — `<hash>␣␣<filename>`, with the **bare** filename,
so the checksum file resolves the artifact when both sit in the same directory.

**Linux / macOS**

```bash
sha256sum -c inspecto-enterprise-4.0.0.zip.sha256
```

Expected output ends in `: OK`. Any other result — `FAILED`, `No such file or directory` — means
stop and re-download; do not unzip it.

**Windows (PowerShell)**

```powershell
(Get-FileHash inspecto-enterprise-4.0.0.zip -Algorithm SHA256).Hash.ToLower()
```

Compare the printed hash to the first field of the `.sha256` file. It is lower-case hex on both
sides, so a case-sensitive comparison is fine.

## 2. Verify authenticity (when a `.asc` is published)

```bash
gpg --verify inspecto-enterprise-4.0.0.zip.asc inspecto-enterprise-4.0.0.zip
```

A trustworthy result needs **both**: `Good signature from …` **and** the key fingerprint matching
the publisher's, obtained through a channel independent of the download.

⚠ `gpg` reports `Good signature` for any key in your keyring, including one an attacker supplied.
"Good signature" plus an unverified fingerprint is not authenticity. And a **`WARNING: This key is
not certified with a trusted signature`** alongside `Good signature` is the normal state for a key
you have imported but not signed — it is not a failure, but it does mean the fingerprint check is
doing all the work.

## 3. What the publisher does (for auditors tracing the control)

`inspecto/package.ps1`:

- **always** writes `<artifact>.sha256` — `Get-FileHash … -Algorithm SHA256`, lower-cased, emitted
  in `sha256sum` format (`package.ps1:845-856`);
- with `-Sign`, additionally emits the detached `<artifact>.asc` (`:869`). The signing key is
  supplied at invocation via `-SigningKey` or `$env:INSPECTO_SIGNING_KEY` and is **never baked into
  the script or the repository** — a deliberate constraint, and the reason signing is opt-in rather
  than automatic.
- Both the Windows and the Linux runtime bundles get the same treatment (`:884-885`).

**Evidence an auditor can sample:** the `.sha256` (and `.asc`) files published beside each release
artifact, and the `package.ps1` source above.

## 4. Known limitation — signing is possible, not yet routine

Signing today depends on whoever runs `package.ps1` supplying a key, so a release can ship
unsigned without anything failing. Making it routine requires a decision about **where the
organization's signing key lives** (compliance plan §6 Q3, gap **G3**) — a key-custody question,
not an engineering one. Until it is answered, treat a missing `.asc` as "this release was not
signed", never as "signatures are not offered".
