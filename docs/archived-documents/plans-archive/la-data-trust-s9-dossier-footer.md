# S9 design check: a data-trust footer on the Dossier (`LA-DATA-TRUST-1`)

**Status: BUILT 2026-10-14 (S9), exactly in the shape below; the as-built facts live in `docs/okf/frontend/features/link-analysis.md` (Dossier, data-state footer). Deviation: `format=steps|method` do NOT carry the footer (the method text cites the custody hash).**
Part of [la-data-trust-plan.md](la-data-trust-plan.md) (slice S9).

## Question

Can a footer carrying *data-as-of*, *index version* and *days missing at export time* be added to the Dossier OUTSIDE the
hashed root, without breaking determinism or replay verification?

## How the Dossier is sealed today (read in `la/inspecto-la-api`)

| Thing | What it covers | Where |
|---|---|---|
| Manifest `root` | SHA-256 of the canonical manifest body only: `algorithm`, `investigation`, `at`, `snapshots`, raw-byte hashes of `header.json`, every `log.jsonl#N`, every `sets/N.json`, every snapshot, plus canonical `entities`/`links`/`excluded`/`scores` hashes | `GraphDossierBuilder.manifest`, `root` |
| `POST …/dossier/verify` | Rebuilds the manifest from the store and diffs it entry by entry; checks the submitted `root` against its own body | `GraphDossierBuilder.verify`, `DossierRoutes.verifyManifest` |
| Integrity section | Re-checks the hashes written at append time (replay hash per step, set-file hash, sealed-read fingerprints) | `GraphDossierBuilder.build` |
| Bundle `seal` | SHA-256 of the canonical bundle with ONLY `seal` and `generatedAt` removed. **Every other bundle field is sealed**, including the whole embedded `dossier` map | `DossierBundleRoutes.seal` |
| `generatedAt` | Already a time-varying field that sits OUTSIDE the seal. This is the precedent | `DossierBundleRoutes.export` |

Two facts decide the answer:

1. The Dossier JSON map is **not** itself hashed. Only the manifest inside it is. Anything added beside `manifest` cannot move `root`
   and cannot affect `/dossier/verify` or replay (both rebuild the manifest from the store and never read other sections).
2. The bundle seal DOES cover the embedded `dossier` and any new top-level key. A footer inside `dossier`, or a new bundle key
   left in the seal, would make two exports of the same Investigation seal differently the moment the data moved. That breaks
   export determinism.

The existing Dossier already says the quiet part: its `negativeSpace.coverage` note states that missing days are NOT sealed
because they are time-varying and "the Dossier root is deterministic". The footer is the honest answer to that note.

## Verdict: BUILD, as an unsealed, labelled, time-varying footer

It can be kept out of the hash. It is the same shape as `generatedAt`.

### Design

* Compute the footer **in the route, after** `DossierRoutes.maskedDossier(...)` returns, never inside `GraphDossierBuilder.build`
  (that function is the deterministic one and also feeds `/dossier/verify`). Fields:
  `dataFooter: {asOf: <data-as-of of the Dataset's time column>, indexVersion, indexStale, missingDays: [..], window,
  assessed, generatedAt, note}`.
* `GET …/dossier` (json): add `dataFooter` as a top-level sibling of `manifest`. It is not in the manifest, so `root` is unchanged.
* `GET …/dossier/bundle`: add `dataFooter` as a top-level bundle key **and add it to the removal list in
  `DossierBundleRoutes.seal`** next to `generatedAt`. Without that one-line change the bundle seal becomes non-deterministic.
* `format=html` and `format=method|steps`: render the footer as the last block, clearly headed "Data state at export time, not
  part of the custody hash" (HTML takes the map from the same response, so it comes for free once the section is added).
* Data sources are the reads S1/S2 and the coverage route already make: data-as-of from the Dataset freshness read, index version
  and stale from the `GET /inv/index` logic (`IndexRoutes`), missing days from the coverage computation (today private in
  `InvestigationCoverageRoutes`; extract the day arithmetic into a shared helper rather than calling the route).
* Masking: the footer carries days, versions and Pipeline-independent counts only. No entity ids, no File names, so nothing to mask.
* Say in the footer note that it is **evidence of the data's state when exported, not evidence about the Investigation**, and that
  `verify` ignores it by design. Do not let the HTML page place it inside the "Manifest and root" block.

### What it must NOT do

* Never enter `GraphDossierBuilder.manifest` / `root`, `integrity`, or the method-statement text that "cites the custody hash".
* Never be read by `/dossier/verify`, `/dossier/bundle/verify` or replay.
* Never be presented as signed. An edited footer in a bundle will NOT fail verification (that is the price of leaving it out of
  the seal); the note says so. If an auditor needs a tamper-evident data state, that is a different, sealed feature (record the
  freshness read into the op log as a sealed read, which then moves the root on purpose) and is NOT proposed here.

### What would change

| File | Change |
|---|---|
| `la/inspecto-la-api/.../DossierRoutes.java` | assemble `dataFooter` after `maskedDossier`, add to the json answer |
| `la/inspecto-la-api/.../DossierBundleRoutes.java` | add `dataFooter` to the bundle, and to `seal()`'s removal list |
| `la/inspecto-la-api/.../DossierHtml.java` | render the footer block (escaped like every value) |
| `la/inspecto-la-api/.../GraphDossierBuilder.java` | none to `build`/`manifest`; optionally reword the `negativeSpace.coverage` note to point at the footer |
| `la/inspecto-la-api/.../InvestigationCoverageRoutes.java` | extract expected/missing-day arithmetic into a shared helper (pure move) |
| a port or reuse of S1/S2 reads | data-as-of and index version, behind the same read gate as the Dossier |
| `docs/okf/frontend/features/link-analysis.md` | one paragraph (the footer, and that it is unsealed) |
| `link-analysis-dossier.component.ts` (UI) | show the footer; labelled "not part of the custody hash" |

### Tests that must prove it

1. **Root unchanged:** build the Dossier twice with the data state changed in between (a different freshness / index version);
   `manifest.root` and every artefact hash are equal, `dataFooter` differs. (`ControlApiDossierTest`)
2. **Seal unchanged:** export the bundle twice with a different footer; `seal.value` equal, and the bundle still verifies.
   Mutation-check: remove `dataFooter` from `seal()`'s removal list and the test must go red. (`ControlApiDossierBundleTest`)
3. **Verify ignores it:** edit `dataFooter` in a submitted bundle; `bundle/verify` still passes (documents the trade-off).
4. **Replay / `/dossier/verify` untouched:** the existing verify and replay tests stay green with no edit.
5. **HTML:** the footer is escaped and sits outside the manifest block (`ControlApiDossierTest` html tests).
6. **Gates:** same 404 for a non-owner as the Dossier; a Dataset with no time column or no attribution gives `assessed:false`,
   never "no gaps" (the coverage rule).

### Cost and ordering

Small: one route-level assembly, one seal-list line, one HTML block, one UI block, about six tests. The only real work is
extracting the missing-day arithmetic. It depends on S1/S2 (the freshness and index reads), so build it after them. Risk is low
**provided** the footer is assembled in the route and excluded from `seal()`; the single way to get this wrong is to put it
inside `dossier` or to forget the seal exclusion.
