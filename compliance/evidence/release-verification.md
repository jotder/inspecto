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

## 4. Signing is routine — the release pipeline signs, and only it can

**Wired 2026-09-02 (COMPLY-2).** `.github/workflows/release.yml` runs on every `v*` tag: it imports
the release key from the CI secret store (`INSPECTO_SIGNING_KEY_ASC` / `_ID`), packages every edition
with `package.ps1 -Sign`, **refuses to publish** if any zip lacks its `.sha256` or `.asc`, verifies
both with the public half exactly as §1–§2 describe, and only then creates the GitHub release with the
zips, checksums, signatures and SBOMs attached. `package.ps1 -Sign` itself became **fail-closed** the
same day: a missing `gpg` or key is now a hard error, where before it downgraded to a warning and
shipped an unsigned artifact under a log line that said `-Sign`. A release with no `.asc` therefore
means "not cut by the pipeline", which is the honest reading. Accepted consequences, restated for a
verifier: a release cut **outside CI cannot be signed at all**, and key custody (who can read the
secret, rotation, CI-provider compromise) is a **CC6 access-control** item, not a personal one.

*(History, kept for provenance:)* signing used to depend on whoever ran `package.ps1` supplying a key, so a release could ship
unsigned without anything failing. The key-custody question (compliance plan §6 Q3, gap **G3**) is
now **ANSWERED — 2026-08-30, operator: the release key lives in the CI secret store**, held by no
individual locally. Signing therefore becomes a **mandatory step of the release pipeline**, and what
remains is engineering, not a decision.

⚠ Standing rule for a verifier (the wiring shipped 2026-09-02): treat a missing `.asc` as
"this release was not signed", never as "signatures are not offered". 🔴 One accepted consequence of
the custody choice, worth stating to a verifier: a release cut **outside CI cannot be signed at
all**.

### 4a. Per-jar signatures (when the bundle was packaged with `-SignJars`)

Beyond the zip's `.asc`, a bundle packaged with `package.ps1 -SignJars` carries a standard JAR signature on **every first-party
jar** (`inspecto*.jar`; vendor jars such as `postgresql.jar` keep their own). Verify with the JDK: `jarsigner -verify inspecto.jar`
must print `jar verified.`, and `keytool -printcert -jarfile inspecto.jar` shows the signer certificate - its SHA-256 fingerprint
must be the one the publisher announces and **identical across all jars**. `node tools/check-jar-signatures.mjs <unzipped bundle>
[--expect-fingerprint <sha256>]` does both for the whole bundle and fails on an unsigned, tampered or differently-signed jar. The
per-module SBOMs (`sbom/<jar>.sbom.cdx.json`) record the SHA-256 of the SIGNED jar bytes. A signature without a trusted timestamp
stops validating when the certificate expires; production releases are timestamped (`-TsaUrl`).

How a customer verifies a jar-signed release: unzip, run `keytool -printcert -jarfile inspecto.jar` and compare the SHA-256
fingerprint with the one stated in the release notes (published by the operator with the release; this document does not carry one),
then `jarsigner -verify` on each jar or `node tools/check-jar-signatures.mjs <unzipped bundle> --expect-fingerprint <sha256>`. The
release pipeline signs jars only when the repository holds the jar-signing secrets, and runs the same check on every published zip.

## 5. SBOM — what each bundle attests it contains (G1)

**Generated per packaged bundle since 2026-09-02 (COMPLY-1).** `package.ps1` runs `tools/sbom.mjs`
after the jars are staged and before the zip, so every bundle carries `sbom/inspecto-<edition>.cdx.json`
(CycloneDX 1.5) **and** `sbom/inspecto-<edition>.spdx.json` (SPDX 2.3), covered by the zip's checksum and
signature and also attached loose to the GitHub release. Both documents are rendered from **one**
component list produced by **one** `mvn dependency:list` over the modules that edition ships — they
cannot drift, because neither is derived from the other or from a second resolution. Each third-party
component carries its purl, the **SHA-256 of the resolved artifact**, and the licence declared in its POM
chain (`NOASSERTION` when none is declared); the bundle's own jars are listed as first-party components
hashed **as shipped**. ⚠ Per bundle, never per reactor: the reactor resolves the optional AI stack a
Personal bundle does not carry (CC9). ⚠ `tools/dependencies.lock` remains the *review* baseline
(coordinates only, every module, diffed in CI) — it is not the SBOM and is not offered as one.

**Per-jar SBOMs (P3f, 2026-10-08).** Because the bundle is a set of separately verifiable jars, every jar on `modules.list`
(thin core jars, optional modules, sidecars, `inspecto.jar`, `postgresql.jar`) also has its own
`sbom/<jar-basename>.sbom.cdx.json` (CycloneDX 1.5): the jar as root component (purl, version, build id, SHA-256 as shipped)
plus its third-party components with SHA-256 and licence. They come from the same resolution as the combined documents and
are verified at package time (`tools/sbom-modules.mjs --verify`): hashes equal the staged jars and every component also
appears in the combined SBOM. They ship inside the zip, covered by its checksum and signature. Per-jar *signatures* are not
yet produced.
