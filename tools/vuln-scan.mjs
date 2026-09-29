#!/usr/bin/env node
// Offline vulnerability scan over the SBOM or the dependency lock (ASSURE-OPERABILITY-1, D-P3).
//
// WHAT IT IS: a zero-dependency matcher. It reads a component list — a CycloneDX SBOM written by
// tools/sbom.mjs, or tools/dependencies.lock — and matches every Maven coordinate against an OSV-format
// vulnerability DATABASE SNAPSHOT that is SUPPLIED at a known path. It never touches the network: the
// snapshot is mirrored on a connected host and carried in (see docs/okf/backend/build-run/operations.md,
// "Offline vulnerability scan"). No scanner binary is installed; this script is the scanner.
//
// Snapshot layout (VULN_DB_DIR, or --db):
//   <dir>/snapshot.json      {"taken":"YYYY-MM-DD","source":"<where it was mirrored from>"}  (required)
//   <dir>/**/*.json          one OSV record each — the unzipped OSV "Maven" ecosystem export
//
// Verdict: exit 1 on any UNWAIVED finding of severity HIGH or CRITICAL — and on UNKNOWN severity, which
// fails closed (a record with no usable severity is not evidence of safety). Exit 0 otherwise. Exit 2 when
// the scan could not run: no snapshot, no snapshot.json, zero records, an empty component list, or a
// snapshot older than --max-age-days (default 30). ⛔ Exit 2 is NEVER a pass.
//
// Waivers (compliance/vuln-waivers.json): [{"id":"GHSA-…|CVE-…","package":"group:artifact","reason":"…",
// "expires":"YYYY-MM-DD"}]. A waiver needs all four fields; an expired or malformed one waives nothing.
//
//   node tools/vuln-scan.mjs [--db <dir>] [--sbom <cdx.json> | --lock <dependencies.lock>]
//                            [--waivers <file>] [--max-age-days N] [--today YYYY-MM-DD]
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const arg = (name, dflt) => {
    const i = process.argv.indexOf(name);
    return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : dflt;
};
const cannotRun = (msg) => { console.error(`✖ vuln-scan could not run: ${msg}`); process.exit(2); };

const dbDir = arg('--db', process.env.VULN_DB_DIR);
const sbomPath = arg('--sbom', null);
const lockPath = arg('--lock', sbomPath ? null : join(repoRoot, 'tools', 'dependencies.lock'));
const waiverPath = arg('--waivers', join(repoRoot, 'compliance', 'vuln-waivers.json'));
const maxAgeDays = Number(arg('--max-age-days', '30'));
const today = arg('--today', new Date().toISOString().slice(0, 10));

// ── components ─────────────────────────────────────────────────────────────────────────────────
function components() {
    if (sbomPath) {
        const doc = JSON.parse(readFileSync(sbomPath, 'utf8'));
        return (doc.components || []).filter(c => c.group && c.name && c.version)
            .map(c => ({ pkg: `${c.group}:${c.name}`, version: c.version }));
    }
    return readFileSync(lockPath, 'utf8').split(/\r?\n/)
        .filter(l => l && !l.startsWith('#'))
        .map(l => l.split(':'))
        .filter(p => p.length >= 4)
        .map(p => ({ pkg: `${p[0]}:${p[1]}`, version: p[3] }));
}

// ── Maven version ordering (the ComparableVersion rules that matter for advisories) ───────────────
const QUALIFIERS = ['alpha', 'beta', 'milestone', 'rc', 'snapshot', '', 'sp'];
const ALIASES = { a: 'alpha', b: 'beta', m: 'milestone', cr: 'rc', ga: '', final: '', release: '' };
function tokens(v) {
    return v.toLowerCase().split(/[.\-]|(?<=\d)(?=[a-z])|(?<=[a-z])(?=\d)/).filter(t => t !== '')
        .map(t => /^\d+$/.test(t) ? { n: Number(t) } : { q: ALIASES[t] ?? t });
}
function qRank(q) { const i = QUALIFIERS.indexOf(q); return i >= 0 ? i : QUALIFIERS.length; }
export function compareMaven(a, b) {
    const x = tokens(a), y = tokens(b);
    for (let i = 0; i < Math.max(x.length, y.length); i++) {
        const p = x[i] ?? { q: '' }, q = y[i] ?? { q: '' };
        const pn = 'n' in p, qn = 'n' in q;
        if (pn && qn) { if (p.n !== q.n) return p.n - q.n; continue; }
        if (pn !== qn) {
            // a number beats a qualifier, except a missing trailing part equals 0 / release
            const num = pn ? p : q, other = pn ? q : p;
            if (other.q === '' && num.n === 0) continue;
            return pn ? 1 : -1;
        }
        const r = qRank(p.q) - qRank(q.q) || (qRank(p.q) === QUALIFIERS.length ? p.q.localeCompare(q.q) : 0);
        if (r !== 0) return r;
    }
    return 0;
}

// ── OSV matching ───────────────────────────────────────────────────────────────────────────────
function inRange(version, events) {
    let affected = false;
    for (const e of events) {
        if (e.introduced !== undefined && (e.introduced === '0' || compareMaven(version, e.introduced) >= 0)) affected = true;
        if (e.fixed !== undefined && compareMaven(version, e.fixed) >= 0) affected = false;
        if (e.last_affected !== undefined && compareMaven(version, e.last_affected) > 0) affected = false;
    }
    return affected;
}
export function affects(record, pkg, version) {
    return (record.affected || []).some(a => {
        if (a.package?.ecosystem !== 'Maven' || a.package?.name !== pkg) return false;
        if ((a.versions || []).includes(version)) return true;
        return (a.ranges || []).some(r => r.type === 'ECOSYSTEM' && inRange(version, r.events || []));
    });
}
export function severityOf(record) {
    const s = String(record.database_specific?.severity || '').toUpperCase();
    if (s === 'MODERATE') return 'MEDIUM';
    return ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].includes(s) ? s : 'UNKNOWN';
}

function* walk(dir) {
    for (const name of readdirSync(dir)) {
        const p = join(dir, name);
        if (statSync(p).isDirectory()) yield* walk(p);
        else if (name.endsWith('.json') && name !== 'snapshot.json') yield p;
    }
}

function loadWaivers() {
    if (!existsSync(waiverPath)) return [];
    const list = JSON.parse(readFileSync(waiverPath, 'utf8'));
    if (!Array.isArray(list)) cannotRun(`${waiverPath} must be a JSON array`);
    return list.filter(w => w.id && w.package && w.reason && /^\d{4}-\d{2}-\d{2}$/.test(w.expires || '')
        && w.expires >= today);
}

// ── main ───────────────────────────────────────────────────────────────────────────────────────
if (process.argv[1] === fileURLToPath(import.meta.url)) {
    if (!dbDir) cannotRun('no snapshot — pass --db <dir> or set VULN_DB_DIR');
    if (!existsSync(dbDir)) cannotRun(`snapshot dir ${dbDir} does not exist`);
    const metaPath = join(dbDir, 'snapshot.json');
    if (!existsSync(metaPath)) cannotRun(`${metaPath} missing — a snapshot must record when it was taken`);
    const meta = JSON.parse(readFileSync(metaPath, 'utf8'));
    if (!/^\d{4}-\d{2}-\d{2}$/.test(meta.taken || '')) cannotRun('snapshot.json has no "taken": "YYYY-MM-DD"');
    const ageDays = (Date.parse(today) - Date.parse(meta.taken)) / 86_400_000;
    if (ageDays > maxAgeDays) cannotRun(`snapshot taken ${meta.taken} is ${ageDays} days old (> ${maxAgeDays}) — refresh it`);

    const comps = components();
    if (comps.length === 0) cannotRun('the component list is empty');
    const byPkg = new Map();
    for (const c of comps) (byPkg.get(c.pkg) ?? byPkg.set(c.pkg, new Set()).get(c.pkg)).add(c.version);

    const waivers = loadWaivers();
    let records = 0;
    const findings = [];
    for (const f of walk(dbDir)) {
        let rec;
        try { rec = JSON.parse(readFileSync(f, 'utf8')); } catch { continue; }
        if (!rec.id) continue;
        records++;
        for (const a of rec.affected || []) {
            const versions = byPkg.get(a.package?.name);
            if (!versions) continue;
            for (const v of versions) {
                if (!affects({ affected: [a] }, a.package.name, v)) continue;
                const ids = [rec.id, ...(rec.aliases || [])];
                const waived = waivers.some(w => ids.includes(w.id) && w.package === a.package.name);
                findings.push({ id: rec.id, pkg: a.package.name, version: v, severity: severityOf(rec), waived });
            }
        }
    }
    if (records === 0) cannotRun(`snapshot ${dbDir} holds zero OSV records`);

    const seen = new Set();
    const uniq = findings.filter(f => { const k = `${f.id}|${f.pkg}|${f.version}`; return !seen.has(k) && seen.add(k); });
    const blocking = uniq.filter(f => !f.waived && ['HIGH', 'CRITICAL', 'UNKNOWN'].includes(f.severity));
    console.log(`vuln-scan: ${comps.length} components vs ${records} OSV records (snapshot ${meta.taken}, ${meta.source || 'source unrecorded'})`);
    for (const f of uniq) console.log(`  ${f.waived ? 'WAIVED ' : ''}${f.severity.padEnd(8)} ${f.id}  ${f.pkg}:${f.version}`);
    if (blocking.length) {
        console.error(`✖ ${blocking.length} unwaived HIGH/CRITICAL/UNKNOWN finding(s) — fix the version or add a dated waiver to ${waiverPath}`);
        process.exit(1);
    }
    console.log(`✓ no unwaived HIGH/CRITICAL finding (${uniq.length} finding(s) total)`);
}
