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

// Preview is not a customer-facing tier (operator decision 2026-09-21): it exists for testing/
// incubation and bundles EVERY optional module unconditionally — see bundleModules() below, which
// gives it a dedicated branch instead of a 'from' floor so a brand-new module needs no edit here to
// be included in Preview the day it's added to MODULES.
export const EDITIONS = ['Personal', 'Professional', 'Enterprise', 'Preview'];

/**
 * artifactId → { dir, bundleFile, from }.
 *
 * `dir` is the reactor directory, which is what Maven's `-pl` takes; `inspecto-processor` is the one
 * module whose directory (`inspecto/`) differs from its artifactId. `bundleFile` is the canonical name
 * the jar is staged under, which is what the bundle carries and what gets hashed.
 *
 * `from` is the edition floor: 'all' ships everywhere, 'professional' means Professional and above (Enterprise
 * is a superset of Professional, never a replacement for it), 'enterprise' means Enterprise only.
 */
const MODULES = [
    // Every edition. The shaded fat jar is the product; the connector sidecar is NOT edition-gated
    // (EDITIONS SP-ACQ-02 marks SFTP shipped in all three) and brings sshj/BouncyCastle, commons-net
    // into a deployment — all deliberately absent from the lean core.
    { artifactId: 'inspecto-processor', dir: 'inspecto', bundleFile: 'inspecto.jar', from: 'all' },
    { artifactId: 'inspecto-connectors', dir: 'providers/inspecto-connectors', bundleFile: 'inspecto-connectors.jar', from: 'all' },

    // Professional and above. inspecto-oidc (ex inspecto-security) is the original non-Personal sidecar; the seven below are
    // EDG-01 cells 1–7, added 2026-09-07 and absent from the generator until this file existed.
    { artifactId: 'inspecto-oidc', dir: 'providers/inspecto-oidc', bundleFile: 'inspecto-oidc.jar', from: 'professional' },                      // D-MR6: was inspecto-security (OIDC Authenticator + TokenRelay)
    { artifactId: 'inspecto-secrets', dir: 'providers/inspecto-secrets', bundleFile: 'inspecto-secrets.jar', from: 'professional' },            // D-MR6: the file-keystore SecretsProvider
    { artifactId: 'inspecto-geo-country', dir: 'providers/inspecto-geo-country', bundleFile: 'inspecto-geo-country.jar', from: 'professional' },  // D-MR6: the MaxMind GeoCountryResolver
    { artifactId: 'inspecto-connectors-kafka', dir: 'providers/inspecto-connectors-kafka', bundleFile: 'inspecto-connectors-kafka.jar', from: 'professional' },  // MODULE-REORG-1 P7: the Kafka stream connector (premium, NOT Personal)
    { artifactId: 'inspecto-telecom-asn1', dir: 'providers/inspecto-telecom-asn1', bundleFile: 'inspecto-telecom-asn1.jar', from: 'professional' },  // MODULE-REORG-1 P7: the Telecom industry-pack ASN.1 decoder (NOT Personal)
    // EDG-01 cell 1 (CP-15). Brings javax.mail — SmtpEmailChannel needs it, and it came here FROM
    // inspecto-connectors, whose pom no longer declares it.
    { artifactId: 'inspecto-notify-channels', dir: 'providers/inspecto-notify-channels', bundleFile: 'inspecto-notify-channels.jar', from: 'professional' },
    { artifactId: 'inspecto-backup', dir: 'features/inspecto-backup', bundleFile: 'inspecto-backup.jar', from: 'professional' },        // cell 2 (OPS-06)
    { artifactId: 'inspecto-entity-list', dir: 'features/inspecto-entity-list', bundleFile: 'inspecto-entity-list.jar', from: 'professional' },  // SEP-08 (Entity Lists + the shared fact log; geo-link depends on it)
    { artifactId: 'inspecto-la-graph', dir: 'la/inspecto-la-graph', bundleFile: 'inspecto-la-graph.jar', from: 'professional' },  // LA separation D-4 step 4 (the ported graph algorithms - la-core's InMemoryGraphEngine calls them, so they ship)
    { artifactId: 'inspecto-la-storage', dir: 'la/inspecto-la-storage', bundleFile: 'inspecto-la-storage.jar', from: 'professional' },  // LA separation D-3 step 2 (the index store skeleton; la-api depends on it from a later step, staged now so the jar and the classpaths are consistent)
    { artifactId: 'inspecto-la-core', dir: 'la/inspecto-la-core', bundleFile: 'inspecto-la-core.jar', from: 'professional' },    // LA separation D-1 step 5b (host-free Link Analysis model + the Dataset/Case ports)
    { artifactId: 'inspecto-la-api', dir: 'la/inspecto-la-api', bundleFile: 'inspecto-la-api.jar', from: 'professional' },       // LA separation D-1 step 5b (the Link Analysis routes, written against the ports)
    { artifactId: 'inspecto-geo-link', dir: 'la/inspecto-geo-link', bundleFile: 'inspecto-geo-link.jar', from: 'professional' },  // cell 3b (CP-09) - since D-1 step 5b the BRIDGE: implements the ports, holds the Alert Rule routes
    { artifactId: 'inspecto-exchange', dir: 'features/inspecto-exchange', bundleFile: 'inspecto-exchange.jar', from: 'professional' },  // cell 4 (SEC-10)
    { artifactId: 'inspecto-observability', dir: 'features/inspecto-observability', bundleFile: 'inspecto-observability.jar', from: 'professional' },  // cells 5+6 (CP-13: /metrics + /events*), merged MODULE-REORG-1 P7
    { artifactId: 'inspecto-ops', dir: 'features/inspecto-ops', bundleFile: 'inspecto-ops.jar', from: 'professional' },                 // cell 7 (CP-11)
    { artifactId: 'inspecto-reconciliation', dir: 'features/inspecto-reconciliation', bundleFile: 'inspecto-reconciliation.jar', from: 'professional' },  // MODULE-REORG-1 P7: the Reconciliation add-on (recon routes + recon.run; NOT Personal)
    { artifactId: 'inspecto-scoring', dir: 'features/inspecto-scoring', bundleFile: 'inspecto-scoring.jar', from: 'professional' },  // MODULE-REORG-1 P7: the Scoring add-on (risk-score routes + risk.score + save checks; NOT Personal)
    { artifactId: 'inspecto-case-management', dir: 'features/inspecto-case-management', bundleFile: 'inspecto-case-management.jar', from: 'professional' },  // MODULE-REORG-P7: the Case Management add-on (Case Rules, merge/split, from-entities, caserule.evaluate; requires ops; NOT Personal)
    { artifactId: 'inspecto-action-requests', dir: 'features/inspecto-action-requests', bundleFile: 'inspecto-action-requests.jar', from: 'professional' },  // MODULE-REORG-P7: the Action Requests add-on (/action-requests* + invoke-api; reaches subjects through LinkedSubjectProvider; NOT Personal)
    { artifactId: 'inspecto-regulatory-reporting', dir: 'features/inspecto-regulatory-reporting', bundleFile: 'inspecto-regulatory-reporting.jar', from: 'professional' },  // REGULATORY-REPORTING-1: the Regulatory Reporting add-on (/regulatory-reports*; requires ops; NOT Personal)
    // PKG-5 (2026-09-12): the assist agent ships Professional and above, as an OPTIONAL component. NB it is
    // a DEFAULT-reactor module, unlike the gated ones around it — package.ps1 lists it in $modules only so
    // that pass builds its shaded `sidecar` artifact. The staged file is the sidecar, never the thin jar.
    { artifactId: 'inspecto-agent', dir: 'features/inspecto-agent', bundleFile: 'inspecto-agent.jar', from: 'professional' },             // CP-14

    // Enterprise only.
    { artifactId: 'inspecto-policy', dir: 'providers/inspecto-policy', bundleFile: 'inspecto-policy.jar', from: 'enterprise' },
    // LA-INVESTIGATION-STORE-DESIGN-1 S6 (D-IS7): the optional PostgreSQL Investigation store, so two pods can serve one Space.
    // A THIN jar over plain JDBC (the driver is postgresql.jar); `investigations.backend=db` selects it, the default stays the filesystem.
    { artifactId: 'inspecto-la-store-pg', dir: 'la/inspecto-la-store-pg', bundleFile: 'inspecto-la-store-pg.jar', from: 'enterprise' },
    // ASSURE-INTELLIGENCE-BUNDLE-1 (D-P2, 2026-09-29): the /agent/* intelligence agent, Enterprise first. Like
    // inspecto-agent a DEFAULT-reactor module staged as its shaded `sidecar`; it carries onnxruntime natives.
    { artifactId: 'inspecto-intelligence', dir: 'features/inspecto-intelligence', bundleFile: 'inspecto-intelligence.jar', from: 'enterprise' }, // CP-14, SP-ENR-08
];

/**
 * The CORE first-party libraries the processor used to shade into inspecto.jar (MODULE-REORG-P3d stage 2, 2026-10-08): now their own
 * THIN jars, staged in EVERY edition next to inspecto.jar and named on modules.list in front of the optional modules. They are the
 * manifests' `base`/`internal` modules (never listed in an Offering - always present), so they are NOT rows of MODULES: adding them
 * there would change the edition counts and every guard that reads "the modules an edition adds". `inspecto/pom.xml`'s shade
 * `artifactSet` excludes exactly these artifactIds (tools/check-sbom-modules.mjs holds the two equal).
 * Listed in dependency order (a library before what imports it); the jars have disjoint packages, so order is cosmetic.
 */
export const CORE_MODULES = [
    { artifactId: 'inspecto-api', dir: 'platform/inspecto-api' },
    { artifactId: 'inspecto-util', dir: 'platform/inspecto-util' },
    { artifactId: 'inspecto-config', dir: 'platform/inspecto-config' },
    { artifactId: 'inspecto-sql', dir: 'platform/inspecto-sql' },
    { artifactId: 'inspecto-etl', dir: 'platform/inspecto-etl' },
    { artifactId: 'inspecto-audit-spi', dir: 'spi/inspecto-audit-spi' },
    { artifactId: 'inspecto-auth-spi', dir: 'spi/inspecto-auth-spi' },
    { artifactId: 'inspecto-access', dir: 'platform/inspecto-access' },
    { artifactId: 'inspecto-http-spi', dir: 'spi/inspecto-http-spi' },
    { artifactId: 'inspecto-entity-store', dir: 'platform/inspecto-entity-store' },
    { artifactId: 'inspecto-event', dir: 'platform/inspecto-event' },
    { artifactId: 'inspecto-workflow', dir: 'platform/inspecto-workflow' },
    { artifactId: 'inspecto-acquire', dir: 'platform/inspecto-acquire' },
    { artifactId: 'inspecto-engine', dir: 'platform/inspecto-engine' },
].map((m) => ({ ...m, bundleFile: `${m.artifactId}.jar`, from: 'all', kind: 'base' }));

/** The core thin jars every edition stages (see CORE_MODULES). */
export const coreModules = () => CORE_MODULES.slice();

/** The Maven profile that activates an edition's extra modules, or null for Personal. */
export function editionProfile(edition) {
    if (edition === 'Preview') return 'edition-preview';
    if (edition === 'Enterprise') return 'edition-enterprise';
    if (edition === 'Professional' || edition === 'Standard') return 'edition-professional';
    return null;
}

/**
 * The first-party modules staged for `edition`, in package.ps1's staging order.
 * Personal 2, Professional 24, Enterprise 27, Preview 27 — first-party only; add PG_SIDECAR for the jar count.
 * ⚠ Those three numbers are ASSERTED by tools/check-sbom-modules.mjs against this table — it parses this
 * very line. They said 2/10/11 from EDG-01 until 2026-09-17, missing inspecto-agent (PKG-5, 2026-09-12);
 * the assertion exists so the next module to arrive cannot leave them wrong again.
 */
export function bundleModules(edition) {
    const normalized = edition === 'Standard' ? 'Professional' : edition;
    if (!EDITIONS.includes(normalized)) throw new Error(`unknown edition '${edition}'`);
    // Preview: every module, unconditionally — deliberately not floor-based, so a module added at any
    // 'from' tier (including a future one) needs no edit here to reach Preview.
    if (normalized === 'Preview') return MODULES.slice();
    return MODULES.filter(
        (m) =>
            m.from === 'all' ||
            ((m.from === 'professional' || m.from === 'standard') && normalized !== 'Personal') ||
            (m.from === 'enterprise' && normalized === 'Enterprise'),
    );
}

/** The modules an edition adds beyond Personal — exactly what package.ps1's `$modules` builds. */
export function editionOnlyModules(edition) {
    return bundleModules(edition).filter((m) => m.from !== 'all');
}
