#!/usr/bin/env node
// SBOM generator for a PACKAGED BUNDLE (compliance matrix gap G1 / workstream C3 — COMPLY-1).
//
// WHAT IT IS: one resolution of the runtime dependency graph of the modules a bundle actually
// ships, rendered as BOTH a CycloneDX 1.5 and an SPDX 2.3 JSON document from the SAME in-memory
// component list, in the same run. The two cannot drift because neither is derived from the other
// or from a second resolution — they are two serialisations of one array.
//
// WHY PER BUNDLE, NOT PER REACTOR: the reactor resolves ~94 third-party artifacts dominated by the
// optional AI stack (controls-matrix CC9). A reactor SBOM attests a set no customer installs. The
// bundle set is: inspecto (→ inspecto.jar, the shaded fat jar) plus, for Standard/Enterprise,
// inspecto-security (+ inspecto-policy) and the postgresql.jar sidecar package.ps1 stages.
//
// ⛔ WHAT IT IS NOT: tools/dependencies.lock. That is the REVIEW baseline (coordinates only, every
// reactor module, diffed in CI). This carries what an SBOM must: per-component SHA-256 of the
// resolved artifact and its declared licence (read from the artifact's POM, walking parent POMs),
// plus purls.
//
// Zero dependencies (pure Node), like tools/check-dependencies.mjs — whose `dependency:list`
// parsing this mirrors on purpose (one grammar for reading Maven's output).
//
//   node tools/sbom.mjs --edition Personal|Standard|Enterprise --bundle <dir> [--version X.Y.Z]
//
// Writes <bundle>/sbom/inspecto-<edition>.cdx.json and <bundle>/sbom/inspecto-<edition>.spdx.json.
// Env: MVN_CMD overrides the Maven binary; MVN_OFFLINE=1 adds `-o`; M2_REPO overrides ~/.m2/repository.
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { homedir } from 'node:os';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');

function arg(name, dflt) {
    const i = process.argv.indexOf(name);
    return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : dflt;
}
const edition = arg('--edition', 'Personal');
const bundleDir = arg('--bundle', null);
if (!bundleDir) {
    console.error('usage: node tools/sbom.mjs --edition <Personal|Standard|Enterprise> --bundle <dir>');
    process.exit(2);
}
if (!['Personal', 'Standard', 'Enterprise'].includes(edition)) {
    console.error(`✖ SBOM: unknown edition '${edition}'`);
    process.exit(2);
}

// ── the shipped module set per edition — the SAME table package.ps1 stages from ──────────────
// artifactId → bundle file. `inspecto/` is artifactId inspecto-processor (the one dir≠artifactId).
const SHIPPED = { 'inspecto-processor': 'inspecto.jar' };
const plModules = ['inspecto'];
let profile = null;
if (edition !== 'Personal') {
    SHIPPED['inspecto-security'] = 'inspecto-security.jar';
    plModules.push('inspecto-security');
    profile = 'edition-standard';
}
if (edition === 'Enterprise') {
    SHIPPED['inspecto-policy'] = 'inspecto-policy.jar';
    plModules.push('inspecto-policy');
    profile = 'edition-enterprise';
}

const M2 = process.env.M2_REPO || join(homedir(), '.m2', 'repository');
const rootPom = readFileSync(join(repoRoot, 'pom.xml'), 'utf8');
const projectVersion =
    arg('--version', null) ||
    // the first <version> that is NOT inside <parent> — the reactor's own version
    (rootPom.replace(/<parent>[\s\S]*?<\/parent>/, '').match(/<version>([^<]+)<\/version>/) || [])[1] ||
    'unknown';
const pgVersion = (rootPom.match(/<postgresql\.version>([^<]+)<\/postgresql\.version>/) || [])[1];

// ── resolve ONCE ─────────────────────────────────────────────────────────────────────────────
const MODULE_BANNER = /^\[INFO\] --- \S+ \([^)]*\) @ ([\w.-]+) ---/;
// g:a:type[:classifier]:version:scope — the same grammar check-dependencies.mjs reads
const ENTRY = /^\[INFO\] {4,}([a-zA-Z][\w.-]*:[\w.-]+:[a-z]+(?::[\w.-]+)?:[^:\s]+:(?:compile|runtime))/;

function mvn() {
    if (process.env.MVN_CMD) return process.env.MVN_CMD;
    return process.platform === 'win32' ? 'mvn.cmd' : 'mvn';
}

function resolve() {
    const args = ['-B', 'dependency:list', '-DincludeScope=runtime', '-pl', plModules.join(','), '-am'];
    if (profile) args.push(`-P${profile}`);
    if (process.env.MVN_OFFLINE === '1') args.unshift('-o');
    let out;
    try {
        out = execFileSync(mvn(), args, {
            cwd: repoRoot,
            encoding: 'utf8',
            maxBuffer: 64 * 1024 * 1024,
            shell: process.platform === 'win32',
        });
    } catch (err) {
        console.error('\n✖ SBOM: CANNOT RUN — `mvn dependency:list` failed.\n');
        console.error(String(err.stdout || '').split('\n').filter((l) => l.includes('[ERROR]')).slice(0, 20).join('\n'));
        process.exit(2);
    }
    // one block per reactor module; keep only the SHIPPED modules' blocks — an upstream sibling
    // (inspecto-engine, …) is shaded INTO inspecto.jar, so its runtime deps already appear in
    // inspecto-processor's own (transitive) list.
    const inRepo = new Set();
    const perModule = new Map();
    let current = null;
    for (const line of out.split(/\r?\n/)) {
        const b = MODULE_BANNER.exec(line.trimEnd());
        if (b) {
            current = b[1];
            inRepo.add(current);
            if (!perModule.has(current)) perModule.set(current, new Set());
            continue;
        }
        const e = ENTRY.exec(line.trimEnd());
        if (e && current) perModule.get(current).add(e[1]);
    }
    const coords = new Set();
    for (const mod of Object.keys(SHIPPED)) for (const c of perModule.get(mod) ?? []) coords.add(c);
    if (coords.size === 0) {
        console.error('✖ SBOM: `dependency:list` produced no resolved artifacts for ' + Object.keys(SHIPPED).join(', '));
        process.exit(2);
    }
    // in-repo artifacts are derived from the run's banners, never hand-listed (the reactor builds
    // com.gamma.inspector:* AND com.gamma.asn:*; a group constant would miss one)
    return [...coords]
        .map(parseCoord)
        .filter((c) => !inRepo.has(c.artifact))
        .sort((a, b) => `${a.group}:${a.artifact}:${a.version}`.localeCompare(`${b.group}:${b.artifact}:${b.version}`));
}

function parseCoord(s) {
    const p = s.split(':');
    // g:a:type:version:scope | g:a:type:classifier:version:scope
    return p.length === 6
        ? { group: p[0], artifact: p[1], type: p[2], classifier: p[3], version: p[4], scope: p[5] }
        : { group: p[0], artifact: p[1], type: p[2], classifier: null, version: p[3], scope: p[4] };
}

// ── per-component evidence: SHA-256 of the resolved artifact + declared licence ──────────────
function m2Dir(c) {
    return join(M2, ...c.group.split('.'), c.artifact, c.version);
}
function artifactFile(c) {
    const ext = c.type === 'bundle' || c.type === 'maven-plugin' ? 'jar' : c.type;
    return join(m2Dir(c), `${c.artifact}-${c.version}${c.classifier ? '-' + c.classifier : ''}.${ext}`);
}
function sha256(path) {
    return createHash('sha256').update(readFileSync(path)).digest('hex');
}
function xmlText(xml, tag) {
    const m = new RegExp(`<${tag}>([^<]*)</${tag}>`).exec(xml);
    return m ? m[1].trim() : null;
}
/** The first declared <licenses><license><name> in the artifact's POM, walking <parent> up to 4 levels. */
function licenseOf(c) {
    let pom = join(m2Dir(c), `${c.artifact}-${c.version}.pom`);
    for (let depth = 0; depth < 5 && existsSync(pom); depth++) {
        const xml = readFileSync(pom, 'utf8').replace(/<!--[\s\S]*?-->/g, '');
        const lic = /<licenses>([\s\S]*?)<\/licenses>/.exec(xml);
        if (lic) {
            const name = xmlText(lic[1], 'name');
            const url = xmlText(lic[1], 'url');
            if (name || url) return { name: name || url, url };
        }
        const parent = /<parent>([\s\S]*?)<\/parent>/.exec(xml);
        if (!parent) break;
        const pg = xmlText(parent[1], 'groupId'), pa = xmlText(parent[1], 'artifactId'), pv = xmlText(parent[1], 'version');
        if (!pg || !pa || !pv) break;
        pom = join(M2, ...pg.split('.'), pa, pv, `${pa}-${pv}.pom`);
    }
    return null;
}
function purl(c) {
    const q = [];
    if (c.type && c.type !== 'jar') q.push(`type=${c.type}`);
    if (c.classifier) q.push(`classifier=${c.classifier}`);
    return `pkg:maven/${c.group}/${c.artifact}@${c.version}${q.length ? '?' + q.join('&') : ''}`;
}

const thirdParty = resolve();
// the PostgreSQL sidecar rides the Standard/Enterprise bundle from package.ps1, test-scoped in the
// reactor — so dependency:list (runtime scope) never lists it; add it from the same pom property
// package.ps1 reads, and only when the sidecar is actually there.
if (edition !== 'Personal' && pgVersion && existsSync(join(bundleDir, 'postgresql.jar'))) {
    thirdParty.push({ group: 'org.postgresql', artifact: 'postgresql', type: 'jar', classifier: null, version: pgVersion, scope: 'runtime' });
}

const components = [];
let unhashed = 0, unlicensed = 0;
for (const c of thirdParty) {
    const file = artifactFile(c);
    const hash = existsSync(file) ? sha256(file) : null;
    const lic = licenseOf(c);
    if (!hash) unhashed++;
    if (!lic) unlicensed++;
    components.push({ ...c, firstParty: false, purl: purl(c), sha256: hash, license: lic });
}
// first-party: the bundle's own jars, hashed AS SHIPPED (the shaded fat jar is what the customer runs)
for (const [artifactId, file] of Object.entries(SHIPPED)) {
    const path = join(bundleDir, file);
    if (!existsSync(path)) {
        console.error(`✖ SBOM: ${file} is not in the bundle (${bundleDir}) — run this AFTER the jars are staged`);
        process.exit(2);
    }
    components.push({
        group: 'com.gamma.inspector', artifact: artifactId, type: 'jar', classifier: null, version: projectVersion,
        scope: 'runtime', firstParty: true, bundleFile: file,
        purl: `pkg:maven/com.gamma.inspector/${artifactId}@${projectVersion}`,
        sha256: sha256(path), license: null,
    });
}

// ── render BOTH from the one list ────────────────────────────────────────────────────────────
const now = new Date().toISOString();
const bundleName = `inspecto-deploy-${edition.toLowerCase()}`;
const uuid = randomUUID();

const cdx = {
    bomFormat: 'CycloneDX',
    specVersion: '1.5',
    serialNumber: `urn:uuid:${uuid}`,
    version: 1,
    metadata: {
        timestamp: now,
        tools: [{ vendor: 'inspecto', name: 'tools/sbom.mjs', version: projectVersion }],
        component: { type: 'application', 'bom-ref': bundleName, name: bundleName, version: projectVersion },
    },
    components: components.map((c) => ({
        type: 'library',
        'bom-ref': c.purl,
        group: c.group,
        name: c.artifact,
        version: c.version,
        scope: 'required',
        purl: c.purl,
        ...(c.sha256 ? { hashes: [{ alg: 'SHA-256', content: c.sha256 }] } : {}),
        ...(c.license ? { licenses: [{ license: { name: c.license.name, ...(c.license.url ? { url: c.license.url } : {}) } }] } : {}),
        ...(c.firstParty ? { properties: [{ name: 'inspecto:bundleFile', value: c.bundleFile }] } : {}),
    })),
    dependencies: [{ ref: bundleName, dependsOn: components.map((c) => c.purl) }],
};

const spdxId = (c) => 'SPDXRef-' + c.purl.replace(/^pkg:maven\//, '').replace(/[^A-Za-z0-9.-]/g, '-');
const spdx = {
    spdxVersion: 'SPDX-2.3',
    dataLicense: 'CC0-1.0',
    SPDXID: 'SPDXRef-DOCUMENT',
    name: bundleName,
    documentNamespace: `https://github.com/jotder/inspecto/sbom/${bundleName}/${projectVersion}/${uuid}`,
    creationInfo: { created: now, creators: [`Tool: inspecto-sbom.mjs-${projectVersion}`] },
    packages: [
        {
            name: bundleName, SPDXID: 'SPDXRef-BUNDLE', versionInfo: projectVersion,
            downloadLocation: 'NOASSERTION', filesAnalyzed: false,
            licenseConcluded: 'NOASSERTION', licenseDeclared: 'NOASSERTION', copyrightText: 'NOASSERTION',
        },
        ...components.map((c) => ({
            name: `${c.group}:${c.artifact}`,
            SPDXID: spdxId(c),
            versionInfo: c.version,
            downloadLocation: 'NOASSERTION',
            filesAnalyzed: false,
            licenseConcluded: 'NOASSERTION',
            licenseDeclared: c.license ? c.license.name : 'NOASSERTION',
            copyrightText: 'NOASSERTION',
            ...(c.sha256 ? { checksums: [{ algorithm: 'SHA256', checksumValue: c.sha256 }] } : {}),
            externalRefs: [{ referenceCategory: 'PACKAGE-MANAGER', referenceType: 'purl', referenceLocator: c.purl }],
        })),
    ],
    relationships: [
        { spdxElementId: 'SPDXRef-DOCUMENT', relationshipType: 'DESCRIBES', relatedSpdxElement: 'SPDXRef-BUNDLE' },
        ...components.map((c) => ({ spdxElementId: 'SPDXRef-BUNDLE', relationshipType: 'DEPENDS_ON', relatedSpdxElement: spdxId(c) })),
    ],
};

const outDir = join(bundleDir, 'sbom');
mkdirSync(outDir, { recursive: true });
const cdxPath = join(outDir, `inspecto-${edition.toLowerCase()}.cdx.json`);
const spdxPath = join(outDir, `inspecto-${edition.toLowerCase()}.spdx.json`);
writeFileSync(cdxPath, JSON.stringify(cdx, null, 2) + '\n');
writeFileSync(spdxPath, JSON.stringify(spdx, null, 2) + '\n');

const third = components.filter((c) => !c.firstParty).length;
console.log(`✓ SBOM (${edition}): ${third} third-party + ${components.length - third} first-party component(s) → sbom/`);
console.log(`  ${cdxPath}\n  ${spdxPath}`);
if (unhashed) console.warn(`  ⚠ ${unhashed} component(s) without a resolvable artifact file in ${M2} — no hash recorded`);
if (unlicensed) console.warn(`  ⚠ ${unlicensed} component(s) declare no licence in their POM chain — recorded as NOASSERTION`);
