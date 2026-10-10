#!/usr/bin/env node
// Scripted OSV snapshot refresh for tools/vuln-scan.mjs (ASSURE-OPERABILITY-1, D-P3; operator decision
// 2026-10-10: "scripted refresh you approve per run"). Zero dependencies, Node built-ins only.
//
//   node tools/vuln-db-refresh.mjs [--db <dir>]                  DRY RUN (default): prints what it WOULD fetch; no network
//   node tools/vuln-db-refresh.mjs [--db <dir>] --confirm        really fetch (the ONLY mode that touches the network)
//   node tools/vuln-db-refresh.mjs --source <dir|file://dir>     read <dir>/<Ecosystem>/all.zip instead of the network
//   node tools/vuln-db-refresh.mjs --check [--max-age-days N]    exit 0 = snapshot present, intact, fresh; 1 = not (no network)
//   other: --ecosystems Maven[,npm] (default Maven: the only ecosystem vuln-scan matches) · --today YYYY-MM-DD
//
// --db defaults to $VULN_DB_DIR. Layout written (what vuln-scan.mjs reads):
//   <db>/snapshot.json                 {taken, source, fetchedAt, files:[{ecosystem,url,etag,bytes,sha256,treeSha256,records}]}
//   <db>/<Ecosystem>/<id>.json         one OSV record each (unzipped export)
//   <db>/_archives/<Ecosystem>.zip     the downloaded archive, kept so its sha256 can be re-verified
// Writes are atomic: everything is built in a sibling temp dir and renamed over <db>; a failure leaves the old snapshot.
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { inflateRawSync } from 'node:zlib';

export const OSV_BASE = 'https://osv-vulnerabilities.storage.googleapis.com';
const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');

// ── minimal ZIP reader (stored + deflate) ──────────────────────────────────────────────────────
export function readZip(buf) {
    let eocd = -1;
    for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65_557); i--) if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    if (eocd < 0) throw new Error('not a zip: no end-of-central-directory record');
    const count = buf.readUInt16LE(eocd + 10);
    let p = buf.readUInt32LE(eocd + 16);
    const out = [];
    for (let n = 0; n < count; n++) {
        if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('corrupt zip central directory');
        const method = buf.readUInt16LE(p + 10), csize = buf.readUInt32LE(p + 20);
        const nlen = buf.readUInt16LE(p + 28), xlen = buf.readUInt16LE(p + 30), clen = buf.readUInt16LE(p + 32);
        const lho = buf.readUInt32LE(p + 42), name = buf.toString('utf8', p + 46, p + 46 + nlen);
        p += 46 + nlen + xlen + clen;
        if (name.endsWith('/')) continue;
        if (name.includes('..') || name.startsWith('/') || name.includes('\\') || name.includes(':')) throw new Error(`unsafe zip entry name: ${name}`);
        const start = lho + 30 + buf.readUInt16LE(lho + 26) + buf.readUInt16LE(lho + 28);
        const data = buf.subarray(start, start + csize);
        if (method !== 0 && method !== 8) throw new Error(`zip entry ${name}: unsupported method ${method}`);
        out.push({ name, data: method === 0 ? data : inflateRawSync(data) });
    }
    return out;
}

function treeHash(dir) {
    const lines = [];
    const walk = (d, rel) => {
        for (const n of readdirSync(d).sort()) {
            const p = join(d, n);
            if (statSync(p).isDirectory()) walk(p, `${rel}${n}/`);
            else lines.push(`${rel}${n}\0${sha256(readFileSync(p))}`);
        }
    };
    walk(dir, '');
    return { sha: sha256(lines.join('\n')), records: lines.length };
}

// ── args / sources ─────────────────────────────────────────────────────────────────────────────
export function parseArgs(argv, env = process.env) {
    const val = (n, d) => { const i = argv.indexOf(n); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
    const src = val('--source', null);
    return {
        db: val('--db', env.VULN_DB_DIR), confirm: argv.includes('--confirm'), check: argv.includes('--check'),
        source: src && src.startsWith('file://') ? fileURLToPath(src) : src,
        ecosystems: val('--ecosystems', 'Maven').split(',').map(s => s.trim()).filter(Boolean),
        maxAgeDays: Number(val('--max-age-days', '30')), today: val('--today', new Date().toISOString().slice(0, 10)),
    };
}
const urlFor = (o, eco) => o.source ? join(resolve(o.source), eco, 'all.zip') : `${OSV_BASE}/${eco}/all.zip`;

async function fetchArchive(o, eco) {
    const url = urlFor(o, eco);
    if (o.source) return { url, etag: null, buf: readFileSync(url) };
    const res = await fetch(url);                                   // the only network call in this tool
    if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
    return { url, etag: res.headers.get('etag'), buf: Buffer.from(await res.arrayBuffer()) };
}

// ── refresh ────────────────────────────────────────────────────────────────────────────────────
export async function refresh(o, log = console.log) {
    if (!o.db) throw new Error('no target: pass --db <dir> or set VULN_DB_DIR');
    const db = resolve(o.db);
    if (!o.source && !o.confirm) {                              // DRY RUN: no network, no writes
        log(`DRY RUN - nothing is fetched or written. Re-run with --confirm to perform it.`);
        for (const e of o.ecosystems) log(`  would GET ${urlFor(o, e)}  (size not queried: a dry run makes no network request)`);
        log(`  would write ${db}  (snapshot.json, <Ecosystem>/*.json, _archives/<Ecosystem>.zip) atomically via ${db}.tmp-<pid>`);
        return { dryRun: true };
    }
    if (o.source) for (const e of o.ecosystems) log(`  ${e}: ${urlFor(o, e)}  ${statSync(urlFor(o, e)).size} bytes (local source, no network)`);
    const tmp = `${db}.tmp-${process.pid}`, old = `${db}.old-${process.pid}`;
    rmSync(tmp, { recursive: true, force: true });
    try {
        mkdirSync(join(tmp, '_archives'), { recursive: true });
        const files = [];
        for (const e of o.ecosystems) {
            const { url, etag, buf } = await fetchArchive(o, e);
            const entries = readZip(buf).filter(x => x.name.endsWith('.json'));
            if (entries.length === 0) throw new Error(`${url}: archive holds zero .json records`);
            for (const x of entries) {
                JSON.parse(x.data.toString('utf8'));                // a corrupt record fails the fetch, not the scan
                const dest = join(tmp, e, x.name);
                mkdirSync(dirname(dest), { recursive: true });
                writeFileSync(dest, x.data);
            }
            writeFileSync(join(tmp, '_archives', `${e}.zip`), buf);
            const t = treeHash(join(tmp, e));
            files.push({ ecosystem: e, url, etag, bytes: buf.length, sha256: sha256(buf), treeSha256: t.sha, records: t.records });
        }
        writeFileSync(join(tmp, 'snapshot.json'), JSON.stringify({
            taken: o.today, source: `osv.dev ${o.ecosystems.join('+')} all.zip`, fetchedAt: new Date().toISOString(), files }, null, 2));
        const v = verify(tmp); if (!v.ok) throw new Error(`self-verification failed: ${v.problems.join('; ')}`);
        rmSync(old, { recursive: true, force: true });
        if (existsSync(db)) renameSync(db, old);
        try { renameSync(tmp, db); } catch (e) { if (existsSync(old)) renameSync(old, db); throw e; }
        rmSync(old, { recursive: true, force: true });
        log(`snapshot written: ${db} (taken ${o.today}, ${files.map(f => `${f.ecosystem}: ${f.records} records`).join(', ')})`);
        return { dryRun: false, files };
    } catch (e) { rmSync(tmp, { recursive: true, force: true }); throw e; }
}

// ── verify / freshness ─────────────────────────────────────────────────────────────────────────
export function verify(db) {
    const problems = [], meta = join(db, 'snapshot.json');
    if (!existsSync(meta)) return { ok: false, problems: [`${meta} missing`] };
    let m; try { m = JSON.parse(readFileSync(meta, 'utf8')); } catch { return { ok: false, problems: ['snapshot.json unparseable'] }; }
    if (!Array.isArray(m.files) || m.files.length === 0) return { ok: false, problems: ['snapshot.json lists no files (not made by vuln-db-refresh)'], meta: m };
    for (const f of m.files) {
        const a = join(db, '_archives', `${f.ecosystem}.zip`);
        if (!existsSync(a) || sha256(readFileSync(a)) !== f.sha256) problems.push(`${f.ecosystem}: archive sha256 mismatch or missing`);
        const d = join(db, f.ecosystem);
        if (!existsSync(d) || treeHash(d).sha !== f.treeSha256) problems.push(`${f.ecosystem}: extracted records do not match the manifest`);
    }
    return { ok: problems.length === 0, problems, meta: m };
}

export function check(o, log = console.log) {
    if (!o.db) { log('FAIL: no snapshot dir (--db / VULN_DB_DIR)'); return 1; }
    const v = verify(resolve(o.db));
    const bad = [...v.problems];
    if (v.meta) {
        const age = (Date.parse(o.today) - Date.parse(v.meta.taken)) / 86_400_000;
        if (!(age >= 0)) bad.push(`taken ${v.meta.taken} is in the future or invalid`);
        else if (age > o.maxAgeDays) bad.push(`taken ${v.meta.taken} is ${age} days old (> ${o.maxAgeDays})`);
    }
    log(bad.length ? `FAIL: ${bad.join('; ')}` : `OK: snapshot ${v.meta.taken} intact and fresh (<= ${o.maxAgeDays} days)`);
    return bad.length ? 1 : 0;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
    const o = parseArgs(process.argv.slice(2));
    try {
        if (o.check) process.exit(check(o));
        await refresh(o);
    } catch (e) { console.error(`vuln-db-refresh failed: ${e.message}`); process.exit(2); }
}
