/**
 * The first-party module set a PACKAGED BUNDLE ships, per edition — one enumeration, read by both
 * `tools/sbom.mjs` (which renders it into the shipped CycloneDX + SPDX documents) and
 * `tools/check-sbom-modules.mjs` (which holds it against `inspecto/package.ps1`).
 *
 * WHY IT IS ITS OWN FILE. It used to be a literal inside sbom.mjs, under a comment claiming it was
 * "the SAME table package.ps1 stages from". It was not, and had not been since EDG-01: the generator
 * knew four modules while package.ps1 staged eleven jars for Standard and twelve for Enterprise. Every
 * Standard and Enterprise bill of materials since then under-declared its own bundle, and javax.mail —
 * which moved to inspecto-notify-channels in EDG-01 cell 1 — appeared in no shipped SBOM at all. The
 * documents are covered by the release checksum and GPG signature, so the gap shipped signed.
 *
 * A prose claim that two lists agree is not a mechanism. This file is the mechanism: one list, two
 * readers, and a guard that fails when it drifts from the script that does the staging.
 *
 * ⛔ NOT the whole bundle. `postgresql.jar` (PG-1) rides Standard/Enterprise too, but it is third-party
 * and test-scoped in the reactor, so sbom.mjs adds it from the parent pom's `postgresql.version`. It is
 * named here as PG_SIDECAR only so the guard can account for every staged jar.
 *
 * AUTHORITY. `inspecto/package.ps1` — the `$modules` assignment, the `Copy-Item … "$bundleDir\*.jar"`
 * staging steps, and the boot-smoke classpath. Its own header comment is stale and names three jars;
 * do not use it. Adding a module means editing this file AND package.ps1; the guard refuses either alone.
 */

/** The third-party JDBC sidecar package.ps1 stages for Standard and Enterprise (PG-1). */
export const PG_SIDECAR = 'postgresql.jar';

export const EDITIONS = ['Personal', 'Standard', 'Enterprise'];

/**
 * artifactId → { dir, bundleFile, from }.
 *
 * `dir` is the reactor directory, which is what Maven's `-pl` takes; `inspecto-processor` is the one
 * module whose directory (`inspecto/`) differs from its artifactId. `bundleFile` is the canonical name
 * the jar is staged under, which is what the bundle carries and what gets hashed.
 *
 * `from` is the edition floor: 'all' ships everywhere, 'standard' means Standard and above (Enterprise
 * is a superset of Standard, never a replacement for it), 'enterprise' means Enterprise only.
 */
const MODULES = [
    // Every edition. The shaded fat jar is the product; the connector sidecar is NOT edition-gated
    // (EDITIONS SP-ACQ-02 marks SFTP shipped in all three) and brings sshj/BouncyCastle, commons-net
    // and kafka-clients into a deployment — all deliberately absent from the lean core.
    { artifactId: 'inspecto-processor', dir: 'inspecto', bundleFile: 'inspecto.jar', from: 'all' },
    { artifactId: 'inspecto-connectors', dir: 'inspecto-connectors', bundleFile: 'inspecto-connectors.jar', from: 'all' },

    // Standard and above. inspecto-security is the original non-Personal sidecar; the seven below are
    // EDG-01 cells 1–7, added 2026-09-07 and absent from the generator until this file existed.
    { artifactId: 'inspecto-security', dir: 'inspecto-security', bundleFile: 'inspecto-security.jar', from: 'standard' },
    // EDG-01 cell 1 (CP-15). Brings javax.mail — SmtpEmailChannel needs it, and it came here FROM
    // inspecto-connectors, whose pom no longer declares it.
    { artifactId: 'inspecto-notify-channels', dir: 'inspecto-notify-channels', bundleFile: 'inspecto-notify-channels.jar', from: 'standard' },
    { artifactId: 'inspecto-backup', dir: 'inspecto-backup', bundleFile: 'inspecto-backup.jar', from: 'standard' },        // cell 2 (OPS-06)
    { artifactId: 'inspecto-geo-link', dir: 'inspecto-geo-link', bundleFile: 'inspecto-geo-link.jar', from: 'standard' },  // cell 3b (CP-09)
    { artifactId: 'inspecto-exchange', dir: 'inspecto-exchange', bundleFile: 'inspecto-exchange.jar', from: 'standard' },  // cell 4 (SEC-10)
    { artifactId: 'inspecto-metrics', dir: 'inspecto-metrics', bundleFile: 'inspecto-metrics.jar', from: 'standard' },     // cell 5 (CP-13, /metrics)
    { artifactId: 'inspecto-events', dir: 'inspecto-events', bundleFile: 'inspecto-events.jar', from: 'standard' },        // cell 6 (CP-13, /events*)
    { artifactId: 'inspecto-ops', dir: 'inspecto-ops', bundleFile: 'inspecto-ops.jar', from: 'standard' },                 // cell 7 (CP-11)
    // PKG-5 (2026-09-12): the assist agent ships Standard and above, as an OPTIONAL component. NB it is
    // a DEFAULT-reactor module, unlike the gated ones around it — package.ps1 lists it in $modules only so
    // that pass builds its shaded `sidecar` artifact. The staged file is the sidecar, never the thin jar.
    { artifactId: 'inspecto-agent', dir: 'inspecto-agent', bundleFile: 'inspecto-agent.jar', from: 'standard' },             // CP-14

    // Enterprise only.
    { artifactId: 'inspecto-policy', dir: 'inspecto-policy', bundleFile: 'inspecto-policy.jar', from: 'enterprise' },
];

/** The Maven profile that activates an edition's extra modules, or null for Personal. */
export function editionProfile(edition) {
    if (edition === 'Enterprise') return 'edition-enterprise';
    if (edition === 'Standard') return 'edition-standard';
    return null;
}

/**
 * The first-party modules staged for `edition`, in package.ps1's staging order.
 * Personal 2, Standard 10, Enterprise 11 — first-party only; add PG_SIDECAR for the jar count.
 */
export function bundleModules(edition) {
    if (!EDITIONS.includes(edition)) throw new Error(`unknown edition '${edition}'`);
    return MODULES.filter(
        (m) =>
            m.from === 'all' ||
            (m.from === 'standard' && edition !== 'Personal') ||
            (m.from === 'enterprise' && edition === 'Enterprise'),
    );
}

/** The modules an edition adds beyond Personal — exactly what package.ps1's `$modules` builds. */
export function editionOnlyModules(edition) {
    return bundleModules(edition).filter((m) => m.from !== 'all');
}
