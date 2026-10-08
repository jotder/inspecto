#!/usr/bin/env node
// PER-MODULE SBOMs for a packaged bundle (MODULE-REORG-P3f).
//
// The combined bundle SBOM (tools/sbom.mjs) answers "what is in this bundle". Since P3d stage 2 the bundle is a set of
// separately-signable jars (14 thin core jars, optional module jars, sidecars, the processor jar, postgresql.jar), and an
// auditor/customer who takes ONE jar needs ITS bill of materials: the jar itself as the root component (purl, version,
// build id, SHA-256 as shipped) and the third-party components that jar carries or needs.
//
// Layout: <bundle>/sbom/<jar-basename>.sbom.cdx.json  (CycloneDX 1.5, beside the combined inspecto-<edition>.cdx.json,
// so the zip's checksum + signature cover them exactly as they cover the combined documents).
//
// OFFLINE DERIVATION. Not from dependencies.lock (that is a flat reactor-wide baseline with no module attribution) and not by
// re-reading poms: tools/sbom.mjs already runs ONE `mvn -o package dependency:list -am` whose per-module banner blocks give each
// module's resolved runtime closure (the dependency plugin is in ~/.m2: it is the same run the combined SBOM uses). A thin jar's
// list is its own closure; a shaded sidecar's / the processor's list is what its shade carries. Per-module components are the SAME
// objects the combined SBOM was rendered from (hash + licence included), so subset-consistency holds by construction - and is
// re-checked from the shipped files by `--verify` / check-sbom-modules.mjs --bundle.
//
//   node tools/sbom-modules.mjs --verify <bundle>      exit 1 on: a jar on modules.list without its SBOM, an SBOM for no listed jar,
//                                                      a root hash that is not the staged jar's sha-256, a third-party component
//                                                      (or the root) that the combined SBOM does not carry / carries with another hash
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

export const SBOM_SUFFIX = '.sbom.cdx.json';
/** `inspecto-api.jar` -> `inspecto-api.sbom.cdx.json` */
export const sbomName = (jar) => jar.replace(/\.jar$/, '') + SBOM_SUFFIX;
const DEMO_JAR = 'inspecto-demo-auth.jar';

export const sha256File = (path) => createHash('sha256').update(readFileSync(path)).digest('hex');

/** One CycloneDX component from a sbom.mjs component object (shared with the combined document). */
export function cdxComponent(c) {
    return {
        type: 'library',
        'bom-ref': c.purl,
        group: c.group,
        name: c.artifact,
        version: c.version,
        scope: 'required',
        purl: c.purl,
        ...(c.sha256 ? { hashes: [{ alg: 'SHA-256', content: c.sha256 }] } : {}),
        ...(c.license ? { licenses: [{ license: { name: c.license.name, ...(c.license.url ? { url: c.license.url } : {}) } }] } : {}),
    };
}

/**
 * Write one SBOM per jar. `jars`: [{ bundleFile, group, artifact, version, depPurls: [purl] }] - the root is the jar, depPurls its
 * third-party components, all of which must be in `componentsByPurl` (purl -> sbom.mjs component). Throws on a missing staged jar or an
 * unknown dependency purl: a per-module SBOM that cannot be fully described is a packaging failure, never a partial file.
 */
export function generatePerModule({ bundleDir, jars, componentsByPurl, buildId = null, toolVersion = 'unknown', now = new Date().toISOString() }) {
    const outDir = join(bundleDir, 'sbom');
    mkdirSync(outDir, { recursive: true });
    const written = [];
    for (const j of jars) {
        const jarPath = join(bundleDir, j.bundleFile);
        if (!existsSync(jarPath)) throw new Error(`per-module SBOM: ${j.bundleFile} is not in the bundle (${bundleDir}) - run this AFTER the jars are staged`);
        const rootPurl = `pkg:maven/${j.group}/${j.artifact}@${j.version}`;
        const root = {
            type: 'library', 'bom-ref': rootPurl, group: j.group, name: j.artifact, version: j.version, purl: rootPurl,
            hashes: [{ alg: 'SHA-256', content: sha256File(jarPath) }],
            ...(componentsByPurl.get(rootPurl)?.license ? { licenses: cdxComponent(componentsByPurl.get(rootPurl)).licenses } : {}),
            properties: [
                { name: 'inspecto:bundleFile', value: j.bundleFile },
                ...(buildId ? [{ name: 'inspecto:buildId', value: buildId }] : []),
            ],
        };
        const deps = [...new Set(j.depPurls)].sort().map((p) => {
            const c = componentsByPurl.get(p);
            if (!c) throw new Error(`per-module SBOM: ${j.bundleFile} depends on ${p}, which is not in the resolved component set`);
            return c;
        });
        const doc = {
            bomFormat: 'CycloneDX', specVersion: '1.5', serialNumber: `urn:uuid:${randomUUID()}`, version: 1,
            metadata: { timestamp: now, tools: [{ vendor: 'inspecto', name: 'tools/sbom-modules.mjs', version: toolVersion }], component: root },
            components: deps.map(cdxComponent),
            dependencies: [{ ref: rootPurl, dependsOn: deps.map((c) => c.purl) }],
        };
        const path = join(outDir, sbomName(j.bundleFile));
        writeFileSync(path, JSON.stringify(doc, null, 2) + '\n');
        written.push(path);
    }
    return written;
}

const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));

/** Problems between a staged bundle directory's per-module SBOMs, its modules.list and its combined SBOM. */
export function verifyPerModule(bundleDir) {
    const problems = [];
    const listPath = join(bundleDir, 'modules.list');
    if (!existsSync(listPath)) return [`${listPath} is missing - cannot tell which jars need a per-module SBOM`];
    const jars = readFileSync(listPath, 'utf8').split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
    const props = existsSync(join(bundleDir, 'edition.properties')) ? readFileSync(join(bundleDir, 'edition.properties'), 'utf8') : '';
    const edition = (/^edition=(\w+)/m.exec(props) || [])[1];
    const demo = /^variant=demo/m.test(props);
    if (!edition) return [`${join(bundleDir, 'edition.properties')} is missing or names no edition`];
    const combinedPath = join(bundleDir, 'sbom', `inspecto-${edition.toLowerCase()}.cdx.json`);
    if (!existsSync(combinedPath)) return [`the combined SBOM ${combinedPath} is missing`];
    const combined = new Map(readJson(combinedPath).components.map((c) => [c.purl, c]));
    const sbomDir = join(bundleDir, 'sbom');
    const expected = new Set();
    for (const jar of jars) {
        if (demo && jar === DEMO_JAR) continue; // the demo build swaps the OIDC trio for this jar AFTER the SBOMs are written; it is not part of any edition's bill of materials
        expected.add(sbomName(jar));
        const file = join(sbomDir, sbomName(jar));
        if (!existsSync(file)) { problems.push(`${jar} is on modules.list but has no per-module SBOM (${sbomName(jar)})`); continue; }
        let doc;
        try { doc = readJson(file); } catch (e) { problems.push(`${sbomName(jar)} is not valid JSON: ${e.message}`); continue; }
        const root = doc.metadata?.component;
        const jarPath = join(bundleDir, jar);
        const want = existsSync(jarPath) ? sha256File(jarPath) : null;
        const got = root?.hashes?.find((h) => h.alg === 'SHA-256')?.content;
        if (!root?.purl) problems.push(`${sbomName(jar)} has no root component purl`);
        if (want === null) problems.push(`${jar} is on modules.list but not staged`);
        else if (got !== want) problems.push(`${sbomName(jar)}: root SHA-256 ${got} is not the staged ${jar}'s ${want}`);
        const cRoot = combined.get(root?.purl);
        if (root?.purl && !cRoot) problems.push(`${sbomName(jar)}: root ${root.purl} is not a component of the combined SBOM`);
        else if (cRoot) {
            const ch = cRoot.hashes?.find((h) => h.alg === 'SHA-256')?.content;
            if (ch && ch !== got) problems.push(`${sbomName(jar)}: root hash differs from the combined SBOM's hash for ${root.purl}`);
        }
        for (const c of doc.components ?? []) {
            const cc = combined.get(c.purl);
            if (!cc) { problems.push(`${sbomName(jar)}: third-party component ${c.purl} is not in the combined SBOM`); continue; }
            const h1 = c.hashes?.find((h) => h.alg === 'SHA-256')?.content, h2 = cc.hashes?.find((h) => h.alg === 'SHA-256')?.content;
            if (h1 !== h2) problems.push(`${sbomName(jar)}: component ${c.purl} hash differs from the combined SBOM`);
        }
        const dep = (doc.dependencies ?? []).find((d) => d.ref === root?.purl);
        const listed = new Set(dep?.dependsOn ?? []), have = new Set((doc.components ?? []).map((c) => c.purl));
        if (!dep || listed.size !== have.size || [...have].some((p) => !listed.has(p))) problems.push(`${sbomName(jar)}: its dependencies entry does not match its components`);
    }
    for (const f of existsSync(sbomDir) ? readdirSync(sbomDir) : []) {
        if (f.endsWith(SBOM_SUFFIX) && !expected.has(f)) problems.push(`${f} is a per-module SBOM for a jar that is not on modules.list`);
    }
    return problems;
}

function main() {
    const i = process.argv.indexOf('--verify');
    if (i < 0 || !process.argv[i + 1]) { console.error('usage: node tools/sbom-modules.mjs --verify <bundle-dir>'); process.exit(2); }
    const problems = verifyPerModule(process.argv[i + 1]);
    if (problems.length) {
        console.error(`✗ per-module SBOMs: ${problems.length} problem(s):\n  - ${problems.join('\n  - ')}`);
        process.exit(1);
    }
    const n = readFileSync(join(process.argv[i + 1], 'modules.list'), 'utf8').split(/\r?\n/).filter((s) => s.trim()).length;
    console.log(`✓ per-module SBOMs: every jar on modules.list (${n}) has one; roots match the staged jars; components ⊆ the combined SBOM`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
