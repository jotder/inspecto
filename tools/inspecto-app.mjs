#!/usr/bin/env node
// inspecto-app - the App authoring kit (PRODUCT-ASSEMBLY-1 PA-6a, decisions D-PA18 and D-PA19; plan
// docs/superpower/2026-11-10-product-assembly-plan.md §8 and §11). A partner makes an App the way the core team does,
// in a folder of their own (D-PA18):
//
//   <app-root>/app.toon                    the App definition (as apps/<id>/app.toon: id, title, version, platform range,
//                                          industries, requires.addons, templates, nav, docs)
//   <app-root>/templates/<id>/             its Space Templates (template.toon, config/, data/samples/)
//   <app-root>/conformance.toon            its golden data's expectations: the template, the corpus day, the Jobs to run,
//                                          and per findings Dataset the exact keys it must hold
//   <app-root>/docs/index.md               its docs
//
//   node tools/inspecto-app.mjs new <id> --dir <app-root> [--title "T"] [--industry <id>] [--addons a,b]
//       scaffold an App that passes validate and test as created (a feed, its Pipeline and Dataset, a sql.template
//       detection Job, its findings Dataset, planted golden data and the expectations)
//   node tools/inspecto-app.mjs validate --app-root <dir>
//       the Product guard's App rules (tools/products.mjs checkModel) plus the layout and conformance.toon
//   node tools/inspecto-app.mjs test --app-root <dir> [--bundle <built bundle dir>]
//       the conformance kit: validate, then for each Edition where the App's Add-ons are built, assemble the Platform
//       product carrying it and check the plan and descriptor (tools/check-product-build.mjs). With --bundle (D-PA19) also
//       boot that bundle on loopback with a Space from the conformance template, trigger its Pipelines and the listed
//       Jobs for the corpus day, and hold each findings Dataset to its expected keys exactly.
//   node tools/inspecto-app.mjs package --app-root <dir> [--out <dir>] [--key <private.pem> [--key-id <id>]]
//       <id>-<version>.zip of the App folder with MANIFEST.sha256; with --key also MANIFEST.sig, an Ed25519 signature over the
//       manifest naming its key id (PA-7c). A server installs only a signed package (PA-7d)
//   node tools/inspecto-app.mjs verify --package <zip> [--trust <keys.properties>]...
//       every file against the manifest, and the manifest's signature against a trusted key: the issuer keys the core jar
//       carries (first-party Apps) plus each --trust file (an install's conf/app-signers.properties lists partner keys)
//   node tools/inspecto-app.mjs keygen --key-id <id> --out <dir>
//       a partner's Ed25519 signing key: <id>.pem (keep it private) and <id>.properties, the line an install adds to its
//       conf/app-signers.properties to trust that partner
//
// Exit 1 with every finding named when something does not hold.

import { spawn } from 'node:child_process';
import { createHash, generateKeyPairSync, verify as edVerify } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { crc32, deflateRawSync, inflateRawSync } from 'node:zlib';
import { assemble } from './assemble.mjs';
import { KEYS_FILE, publicResource, signBytes, trustedKeys } from './entitle.mjs';
import { gather } from './check-offerings.mjs';
import { checkPlan } from './check-product-build.mjs';
import { builtAddons, carryPartnerApps, checkModel, gatherModel } from './products.mjs';
import { parseToon } from './toon-subset.mjs';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
/** The Platform product a partner App is assembled into (it carries every Add-on; `apps.optional` takes the App). */
export const PLATFORM_PRODUCT = 'inspecto';
const ID = /^[a-z][a-z0-9-]{1,40}$/;
const list = (x) => (Array.isArray(x) ? x : []);
const read = (f) => parseToon(readFileSync(f, 'utf8'));
const under = (s) => s.replace(/-/g, '_');

/** Every file under `dir`, `/`-separated and relative to it, in name order. */
function walk(dir, rel = '') {
    return readdirSync(join(dir, rel), { withFileTypes: true }).sort((a, b) => (a.name < b.name ? -1 : 1)).flatMap((d) => {
        const r = rel ? `${rel}/${d.name}` : d.name;
        return d.isDirectory() ? walk(dir, r) : [r];
    });
}

// ── new ──

/** The starter's golden data: three accounts over the threshold (the planted findings), five look-alikes at or under it. */
export const STARTER = {
    day: '2026-07-01',
    threshold: 1000,
    planted: ['A-001', 'A-002', 'A-003'],
    events: [
        ['A-001', 600], ['A-001', 650], ['A-002', 1200], ['A-003', 400], ['A-003', 400], ['A-003', 300],
        ['A-101', 999], ['A-102', 500], ['A-102', 500], ['A-103', 10], ['A-104', 1000], ['A-105', 250], ['A-105', 250],
    ],
};

/** The files of a new App, as { path: text }. */
export function starterFiles(id, { title = null, industry = null, addons = [] } = {}) {
    if (!ID.test(id)) throw new Error(`App id '${id}' must be lower-case letters, digits and hyphens, 2 to 41 characters, starting with a letter`);
    const t = title ?? id.split('-').map((w) => w[0].toUpperCase() + w.slice(1)).join(' ');
    const tpl = id;
    const feed = `${under(id)}_events`;
    const job = `${under(id)}_detect`;
    const sink = `${under(id)}_findings`;
    const csv = ['event_id,event_ts,account_id,amount',
        ...STARTER.events.map(([acct, amt], i) => `E${String(i + 1).padStart(4, '0')},${STARTER.day} ${String(8 + i).padStart(2, '0')}:00:00,${acct},${amt.toFixed(2)}`)].join('\n') + '\n';
    const fields = [['event_id', 'VARCHAR', 'Event identifier'], ['event_ts', 'TIMESTAMP', 'Event time'], ['account_id', 'VARCHAR', 'Account (synthetic)'], ['amount', 'DOUBLE', 'Amount']];
    return {
        'app.toon': [
            `id: ${id}`, `title: ${t}`, 'version: 0.1.0', 'platform: >=4.0 <5',
            industry ? `industries[1]: ${industry}` : 'industries[0]:',
            'requires:', `  addons[${addons.length}]:${addons.length ? ` ${addons.join(',')}` : ''}`,
            `templates[1]: ${tpl}`,
            'nav:', '  findings:', '    path: data-browser', `    label: ${t} findings`, '    icon: heroicons_outline:magnifying-glass',
            `    blurb: Browse what ${t} found.`,
            'docs: docs/index.md', '',
        ].join('\n'),
        [`templates/${tpl}/template.toon`]: [
            `name: "${t}"`,
            `tagline: "${t}: one feed, one detection Job and its findings"`,
            `description: "Made with the Inspecto App kit (tools/inspecto-app.mjs new). One daily ${feed} feed lands through its Pipeline into a Dataset; the ${job} Job flags each account whose amounts in a day exceed a threshold (${STARTER.threshold}) into ${sink}. Synthetic data only: the samples in data/samples/${feed} plant three accounts over it and five look-alikes at or under it."`,
            'minEdition: basic', 'icon: heroicons_outline:puzzle-piece',
            'contents[3]: "Pipeline + schema", "Dataset", "Detection Job + findings Dataset"', '',
        ].join('\n'),
        [`templates/${tpl}/config/${feed}/${feed}_pipeline.toon`]: [
            `name: ${feed}`, 'active: true', '', 'dirs:',
            ...[['poll', `data/inbox/${feed}`], ['database', `data/${feed}/database`], ...['backup', 'temp', 'errors', 'quarantine', 'markers'].map((d) => [d, `data/${feed}/${d}`]),
                ['status_dir', `data/${feed}/status`], ['log_dir', `data/${feed}/logs`]].map(([k, v]) => `  ${`${k}:`.padEnd(12)}${v}`),
            '', 'output:', '  format: PARQUET', '  compression: snappy', '',
            'processing:', '  threads: 1', '  file_pattern: "glob:**/*.csv"', '  duplicate_check:', '    enabled: true', '    marker_extension: .processed',
            '    retention_days: 30', `  schema_file: ${feed}_schema.toon`, '  csv_settings:', '    delimiter: ","', '    timestamp_formats[1]: "%Y-%m-%d %H:%M:%S"', '',
        ].join('\n'),
        [`templates/${tpl}/config/${feed}/${feed}_schema.toon`]: [
            'raw:', `  name: ${feed.toUpperCase()}`, '  format: CSV', `  fields[${fields.length}]{name,selector,type,description,unit,classification}:`,
            ...fields.map(([n, ty, d], i) => `    ${n},"${i}",${ty},"${d}","","INTERNAL"`),
            'mapping:', `  canonicalName: ${feed}`, `  rawName: ${feed.toUpperCase()}`, `  fields[${fields.length}]:`,
            ...fields.flatMap(([n]) => [`    - name: ${n}`, `      from: ${n}`, '      fn: keep']), '',
        ].join('\n'),
        [`templates/${tpl}/config/registry/datasets/${feed}.toon`]: `name: ${feed}\nphysicalRef: ${feed}/database\n`,
        [`templates/${tpl}/config/registry/datasets/${sink}.toon`]: [`name: ${sink}`, `physicalRef: ${sink}`,
            'columns[4]{name,type,classification}:', '  account_id,string,', '  events,number,', '  total,number,', '  window_date,date,', ''].join('\n'),
        [`templates/${tpl}/config/jobs/${job}_job.toon`]: [
            'job:', `  name: ${job}`, '  type: sql.template',
            `  description: "${t}: each account whose amounts in the window exceed the threshold"`,
            `  sql: "SELECT account_id, CAST($window_start AS DATE) AS window_date, count(*) AS events, sum(amount) AS total FROM ${feed} WHERE event_ts >= CAST($window_start AS TIMESTAMP) AND event_ts < CAST($window_end AS TIMESTAMP) GROUP BY account_id HAVING sum(amount) > CAST($threshold AS DOUBLE)"`,
            `  sources: ${feed}`, `  sink_dataset: ${sink}`, '  cron: "0 2 * * *"', '  window_start: "$yesterday"', '  window_end: "$today"',
            `  threshold: "${STARTER.threshold}"`, '  enabled: true', '',
        ].join('\n'),
        [`templates/${tpl}/data/samples/${feed}/${feed.toUpperCase()}_${STARTER.day.replace(/-/g, '')}.csv`]: csv,
        'conformance.toon': [
            `template: ${tpl}`, `day: ${STARTER.day}`, `jobs[1]: ${job}`, 'expectations:', `  ${sink}:`, '    key: account_id',
            `    expected[${STARTER.planted.length}]: ${STARTER.planted.join(',')}`, '',
        ].join('\n'),
        'docs/index.md': `# ${t}\n\nAn Inspecto App made with the App kit (\`node tools/inspecto-app.mjs new ${id}\`).\n\n`
            + `- Template \`${tpl}\`: the \`${feed}\` feed, the \`${job}\` Job, its findings in \`${sink}\`.\n`
            + '- Check it: `node tools/inspecto-app.mjs validate --app-root <this folder>`, then `test` (add `--bundle <a built bundle>` to boot one).\n'
            + '- Ship it: `node tools/inspecto-app.mjs package --app-root <this folder>`; build a bundle with it: `pwsh -File inspecto/package.ps1 -AppRoot <this folder> ...`.\n',
    };
}

export function scaffold(id, dir, opts = {}) {
    const root = resolve(dir);
    if (existsSync(root) && readdirSync(root).length) throw new Error(`${root} is not empty - a new App goes into an empty or new folder`);
    const files = starterFiles(id, opts);
    for (const [rel, text] of Object.entries(files)) {
        const f = join(root, ...rel.split('/'));
        mkdirSync(dirname(f), { recursive: true });
        writeFileSync(f, text);
    }
    return Object.keys(files).sort();
}

// ── validate ──

/** The model with the App at `appRoot` loaded and carried by the Platform product as an optional App. */
export function kitModel(appRoot, { repo = REPO } = {}) {
    const model = carryPartnerApps(gatherModel(repo, { appRoots: [appRoot] }), PLATFORM_PRODUCT);
    return { model, id: Object.keys(model.partnerApps)[0] };
}

/** Every finding about the App at `appRoot` (an empty list = it holds). */
export function validate(appRoot, { repo = REPO } = {}) {
    const root = resolve(appRoot);
    const out = [];
    if (!existsSync(join(root, 'app.toon'))) return [`${root}: no app.toon`];
    const { model, id } = kitModel(root, { repo });
    out.push(...checkModel(model));
    if (!id) return out;
    const app = model.apps[id];
    if (!ID.test(id)) out.push(`${root}/app.toon: id '${id}' must be lower-case letters, digits and hyphens`);
    if (!list(app.templates).length) out.push(`${root}/app.toon: names no template - conformance needs one with golden data`);
    for (const t of list(app.templates)) {
        const td = join(root, 'templates', t);
        if (!existsSync(join(td, 'template.toon'))) { out.push(`${td}: no template.toon`); continue; }
        const meta = readFileSync(join(td, 'template.toon'), 'utf8');
        for (const k of ['name', 'description']) if (!new RegExp(`^${k}:\\s*\\S`, 'm').test(meta)) out.push(`${td}/template.toon: no ${k}`);
        if (!existsSync(join(td, 'config'))) out.push(`${td}: no config/`);
    }
    const cf = join(root, 'conformance.toon');
    if (!existsSync(cf)) { out.push(`${root}: no conformance.toon - the golden expectations conformance holds the App to`); return out; }
    let c;
    try { c = read(cf); } catch (e) { out.push(`${cf}: ${e.message}`); return out; }
    if (!list(app.templates).includes(c.template)) out.push(`${cf}: template '${c.template}' is not one of the App's templates`);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(c.day ?? '')) out.push(`${cf}: day '${c.day}' is not a YYYY-MM-DD date (the corpus day the Jobs run for)`);
    const td = join(root, 'templates', c.template ?? '');
    for (const j of list(c.jobs)) if (!existsSync(join(td, 'config', 'jobs', `${j}_job.toon`))) out.push(`${cf}: Job '${j}' has no config/jobs/${j}_job.toon in template '${c.template}'`);
    const exp = c.expectations && typeof c.expectations === 'object' ? Object.entries(c.expectations) : [];
    if (!exp.length) out.push(`${cf}: no expectations - name each findings Dataset, its key column and the keys it must hold`);
    for (const [ds, e] of exp) {
        if (!existsSync(join(td, 'config', 'registry', 'datasets', `${ds}.toon`))) out.push(`${cf}: expectation '${ds}' names no Dataset config/registry/datasets/${ds}.toon`);
        if (!e?.key) out.push(`${cf}: expectation '${ds}' has no key column`);
        if (!Array.isArray(e?.expected)) out.push(`${cf}: expectation '${ds}' has no expected[N] list (an empty one, expected[0]:, says "finds nothing")`);
    }
    const sd = join(td, 'data', 'samples');
    const feeds = existsSync(sd) ? readdirSync(sd, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name) : [];
    if (!feeds.length) out.push(`${td}: no data/samples/<feed>/ - conformance ingests the golden data from there`);
    for (const f of feeds) if (!readdirSync(join(sd, f)).length) out.push(`${sd}/${f}: an empty feed folder - put the feed's golden data file(s) in it`);
    return out;
}

// ── test ──

/** The static conformance: validate, then one assembled Platform product per Edition where the App's Add-ons are built. */
export function staticConformance(appRoot, { repo = REPO } = {}) {
    const findings = validate(appRoot, { repo });
    if (findings.length) return { findings, editions: [] };
    const { model, id } = kitModel(appRoot, { repo });
    const p = model.products[PLATFORM_PRODUCT];
    const app = model.apps[id];
    const g = gather(repo);
    const editions = list(p.editions).filter((e) => list(app.requires?.addons).every((a) => builtAddons(e, model.offerings).has(a)));
    if (!editions.length) findings.push(`App '${id}': no Edition builds all of its Add-ons (${list(app.requires?.addons).join(', ')})`);
    for (const e of editions) {
        try {
            const plan = assemble(model, g, { product: PLATFORM_PRODUCT, edition: e, apps: [id] });
            findings.push(...checkPlan(plan, { ...p, apps: { required: list(p.apps?.required), optional: [id] } }, { repo }));
            for (const t of list(app.templates)) if (!plan.partnerTemplates.some((x) => x.id === t)) findings.push(`${e}: template '${t}' is not staged from the App's folder`);
        } catch (err) {
            findings.push(`${e}: ${err.message}`);
        }
    }
    return { findings, editions };
}

const freePort = () => new Promise((res, rej) => {
    const s = createServer();
    s.listen(0, '127.0.0.1', () => { const { port } = s.address(); s.close(() => res(port)); });
    s.on('error', rej);
});

/** A Space folder for `template` under `spacesRoot`, as a Space created from the template, with its samples in the inboxes. */
export function scaffoldSpace(spacesRoot, spaceId, templateDir) {
    const base = join(spacesRoot, spaceId);
    for (const sub of ['config', 'data', 'audit', 'duckdb']) mkdirSync(join(base, sub), { recursive: true });
    cpSync(join(templateDir, 'config'), join(base, 'config'), { recursive: true });
    if (existsSync(join(templateDir, 'data'))) cpSync(join(templateDir, 'data'), join(base, 'data'), { recursive: true });
    const samples = join(base, 'data', 'samples');
    const feeds = existsSync(samples) ? readdirSync(samples).sort() : [];
    for (const f of feeds) cpSync(join(samples, f), join(base, 'data', 'inbox', f), { recursive: true });
    writeFileSync(join(base, 'space.toon'), `display_name: ${spaceId}\ndescription: App conformance Space\ncreated_at: ${new Date().toISOString()}\n`);
    return feeds;
}

/**
 * Hold a booted Space to conformance.toon over HTTP: every Pipeline, then the listed Jobs for the corpus day, then each
 * expectation's keys, exactly. `fetchFn` reaches `<base>/api/v1/spaces/<space>`; returns the findings.
 */
export async function goldenConformance({ fetchFn = fetch, base, space, conformance, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), pollTries = 600 }) {
    const findings = [];
    const api = `${base}/api/v1/spaces/${space}`;
    const call = async (method, p, body) => {
        const r = await fetchFn(`${api}${p}`, { method, headers: { 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
        let data = null;
        try { const j = await r.json(); data = j && typeof j === 'object' && 'data' in j ? j.data : j; } catch { /* empty */ }
        return { status: r.status, data };
    };
    const wait = async (poll) => {
        let st = null;
        for (let i = 0; i < pollTries && !(st && !['RUNNING', 'QUEUED', 'PENDING'].includes(String(st.status).toUpperCase())); i++) {
            await sleep(i ? 500 : 100);
            const g = await call('GET', poll);
            if (g.status === 200) st = g.data;
        }
        return st;
    };
    const pl = await call('GET', '/pipelines');
    if (pl.status === 401 || pl.status === 403) return [`the bundle asks for sign-in (${pl.status}): boot conformance needs a bundle without it - a Basic bundle, or one started with no identity provider configured`];
    const pipelines = (Array.isArray(pl.data) ? pl.data : pl.data?.items ?? []).map((x) => (typeof x === 'string' ? x : x.name ?? x.id)).filter(Boolean).sort();
    for (const name of pipelines) {
        const t = await call('POST', `/runs/${name}/trigger`);
        if (t.status >= 300) { findings.push(`Pipeline ${name}: trigger answered ${t.status}`); continue; }
        const st = await wait(`/runs/runs/${t.data.runId}`);
        if (String(st?.status).toUpperCase() !== 'SUCCESS' || st.failed > 0) findings.push(`Pipeline ${name}: ${st ? `${st.status} ${st.message ?? ''}`.trim() : 'no finished run'}`);
    }
    const next = new Date(Date.parse(`${conformance.day}T00:00:00Z`) + 86400000).toISOString().slice(0, 10);
    const params = { window_start: `${conformance.day} 00:00:00`, window_end: `${next} 00:00:00`, day: conformance.day };
    for (const job of list(conformance.jobs)) {
        const t = await call('POST', `/jobs/${job}/trigger`, { params });
        if (t.status >= 300) { findings.push(`Job ${job}: trigger answered ${t.status} ${JSON.stringify(t.data)}`); continue; }
        const st = await wait(`/jobs/runs/${t.data.runId}`);
        if (String(st?.status).toUpperCase() !== 'SUCCESS') findings.push(`Job ${job}: ${st ? `${st.status} ${st.message ?? ''}`.trim() : 'no finished run'}`);
    }
    for (const [ds, e] of Object.entries(conformance.expectations ?? {})) {
        const r = await call('GET', `/datasets/${ds}/rows?limit=5000`);
        if (r.status !== 200) { findings.push(`Dataset ${ds}: rows answered ${r.status}`); continue; }
        const cols = (r.data?.columns ?? []).map((c) => c.name);
        if (cols.length && !cols.includes(e.key)) { findings.push(`Dataset ${ds}: has no column '${e.key}' (it has ${cols.join(', ')})`); continue; }
        const keys = (r.data?.rows ?? []).map((row) => String(Array.isArray(row) ? row[cols.indexOf(e.key)] : row[e.key]));
        const got = [...new Set(keys)].sort();
        const want = [...new Set(list(e.expected))].sort();
        if (got.join() !== want.join()) findings.push(`Dataset ${ds}: ${e.key} holds ${got.join(', ') || 'nothing'}; conformance expects exactly ${want.join(', ') || 'nothing'}`);
    }
    return findings;
}

/** The bundle's OIDC Authenticator jar (`lib/inspecto-oidc.jar`), as a modules.list entry names it. */
export const isOidcJar = (entry) => /(^|[\\/])inspecto-oidc(-[^\\/]*)?\.jar$/.test(entry);

/** Boot `bundle` on loopback with a Space from the App's conformance template and hold it to its golden data. */
export async function bootConformance(appRoot, bundle, { log = () => {}, readyTries = 240 } = {}) {
    const root = resolve(appRoot);
    const c = read(join(root, 'conformance.toon'));
    const bundleDir = resolve(bundle);
    const modules = join(bundleDir, 'lib', 'modules.list');
    if (!existsSync(modules)) return [`${bundleDir}: not a built bundle (no lib/modules.list)`];
    const work = mkdtempSync(join(tmpdir(), 'inspecto-app-test-'));
    const space = `kit-${c.template}`;
    const sep = process.platform === 'win32' ? ';' : ':';
    const plat = process.platform === 'win32' ? 'windows_amd64' : 'linux_amd64';
    let proc = null;
    try {
        scaffoldSpace(join(work, 'spaces'), space, join(root, 'templates', c.template));
        const port = await freePort();
        // A Standard or Advanced bundle carries inspecto-oidc.jar, whose Authenticator needs an identity provider; conformance
        // tests the App, not sign-in, so this loopback boot over a scratch Space leaves it off and runs without sign-in.
        const jars = readFileSync(modules, 'utf8').split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
        const cp = jars.filter((j) => !isOidcJar(j)).map((j) => join(bundleDir, j)).join(sep);
        if (jars.some(isOidcJar)) log('left inspecto-oidc.jar off the classpath: the conformance boot runs without sign-in');
        const javaExe = join(bundleDir, 'runtime', 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
        const ext = join(bundleDir, 'lib', 'duckdb', plat);
        const args = ['--enable-native-access=ALL-UNNAMED', '-Dcontrol.bind=127.0.0.1', `-Dcontrol.port=${port}`, '-Dspaces.root=spaces',
            ...(existsSync(ext) ? [`-Dduckdb.extension.dir=${ext}`] : []), '-cp', cp, 'com.gamma.control.ControlApi'];
        log(`booting ${bundleDir} on 127.0.0.1:${port} with the Space ${space}`);
        proc = spawn(existsSync(javaExe) ? javaExe : 'java', args, { cwd: work, stdio: ['ignore', 'ignore', 'ignore'] });
        const base = `http://127.0.0.1:${port}`;
        let up = false;
        for (let i = 0; i < readyTries && !up && proc.exitCode === null; i++) {
            try { up = (await fetch(`${base}/health`)).status === 200; } catch { /* not yet */ }
            if (!up) await new Promise((r) => setTimeout(r, 500));
        }
        if (!up) return [`the bundle did not answer /health on :${port} (it ${proc.exitCode === null ? 'is still starting' : `exited ${proc.exitCode}`})`];
        return await goldenConformance({ base, space, conformance: c });
    } finally {
        if (proc && proc.exitCode === null) proc.kill();
        await new Promise((r) => setTimeout(r, 1500));
        rmSync(work, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 });
    }
}

// ── package ──

/** One fixed entry time (2026-01-01 00:00, DOS format), so the same App always packages to the same bytes. */
const DOS_TIME = (((2026 - 1980) << 9 | 1 << 5 | 1) << 16) >>> 0;

/** A ZIP archive (deflate) of `entries` ({ name, data }), written with Node alone. */
export function zip(entries) {
    const local = [];
    const central = [];
    let offset = 0;
    for (const { name, data } of entries) {
        const n = Buffer.from(name, 'utf8');
        const body = deflateRawSync(data);
        const crc = crc32(data) >>> 0;
        const h = Buffer.alloc(30);
        h.writeUInt32LE(0x04034b50, 0); h.writeUInt16LE(20, 4); h.writeUInt16LE(0x0800, 6); h.writeUInt16LE(8, 8);
        h.writeUInt32LE(DOS_TIME, 10); h.writeUInt32LE(crc, 14); h.writeUInt32LE(body.length, 18); h.writeUInt32LE(data.length, 22);
        h.writeUInt16LE(n.length, 26); h.writeUInt16LE(0, 28);
        local.push(h, n, body);
        const c = Buffer.alloc(46);
        c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(20, 4); c.writeUInt16LE(20, 6); c.writeUInt16LE(0x0800, 8); c.writeUInt16LE(8, 10);
        c.writeUInt32LE(DOS_TIME, 12); c.writeUInt32LE(crc, 16); c.writeUInt32LE(body.length, 20); c.writeUInt32LE(data.length, 24);
        c.writeUInt16LE(n.length, 28); c.writeUInt32LE(offset, 42);
        central.push(c, n);
        offset += h.length + n.length + body.length;
    }
    const cd = Buffer.concat(central);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10);
    end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
    return Buffer.concat([...local, cd, end]);
}

/**
 * Write `<out>/<id>-<version>.zip` (the App folder plus MANIFEST.sha256); refuses an App that does not validate. With `key` (a
 * PEM Ed25519 private key) and `keyId` it also signs the manifest: `MANIFEST.sig` holds `keyId=<id>` and `signature=<base64>`.
 */
export function packageApp(appRoot, out, { repo = REPO, key = null, keyId = null } = {}) {
    const root = resolve(appRoot);
    const findings = validate(root, { repo });
    if (findings.length) throw new Error(`the App does not validate - fix it first:\n  ${findings.join('\n  ')}`);
    const app = read(join(root, 'app.toon'));
    // The App's own files only: no dot-entry (.git, .idea, ...), no node_modules, no dist/ output, no archive.
    const skip = (f) => f.split('/').some((s) => (s.startsWith('.') && s !== '.gitkeep') || s === 'node_modules') || f.startsWith('dist/') || f === 'MANIFEST.sha256' || f === 'MANIFEST.sig' || f.endsWith('.zip') || f.endsWith('.pem');
    const files = walk(root).filter((f) => !skip(f));
    const entries = files.map((f) => ({ name: `${app.id}/${f}`, data: readFileSync(join(root, ...f.split('/'))) }));
    const manifest = entries.map((e) => `${createHash('sha256').update(e.data).digest('hex')}  ${e.name}`).join('\n') + '\n';
    entries.push({ name: `${app.id}/MANIFEST.sha256`, data: Buffer.from(manifest) });
    if (key) {
        if (!keyId || !/^[a-z][a-z0-9-]{1,63}$/.test(keyId)) throw new Error(`a signing key needs a key id (lower-case letters, digits and hyphens), was '${keyId ?? ''}'`);
        entries.push({ name: `${app.id}/MANIFEST.sig`, data: Buffer.from(`keyId=${keyId}\nsignature=${signBytes(Buffer.from(manifest), key)}\n`) });
    }
    mkdirSync(resolve(out), { recursive: true });
    const file = join(resolve(out), `${app.id}-${app.version}.zip`);
    writeFileSync(file, zip(entries));
    return { file, files: entries.length, bytes: statSync(file).size, signed: Boolean(key) };
}

/**
 * The entries of a ZIP archive written by {@link zip} (stored or deflated), as a null-prototype { name: Buffer } (so an entry
 * named `__proto__` is an ordinary key). Strict, because a package is verified by its entries: every local header must sit
 * where the central directory says, carry the same name, and the local entries must tile the archive up to the central
 * directory with nothing hidden between them. A duplicate name, a data descriptor, an encrypted entry or a name that escapes
 * its folder is refused.
 */
export function unzip(buf) {
    let end = buf.length - 22;
    while (end >= 0 && buf.readUInt32LE(end) !== 0x06054b50) end--;
    if (end < 0) throw new Error('not a ZIP archive (no end record)');
    const count = buf.readUInt16LE(end + 10);
    const cdStart = buf.readUInt32LE(end + 16);
    const out = Object.create(null);
    const byOffset = new Map();
    let p = cdStart;
    for (let i = 0; i < count; i++) {
        if (p + 46 > buf.length || buf.readUInt32LE(p) !== 0x02014b50) throw new Error('a broken ZIP central directory');
        const flags = buf.readUInt16LE(p + 8), method = buf.readUInt16LE(p + 10), size = buf.readUInt32LE(p + 20);
        const nameLen = buf.readUInt16LE(p + 28), extraLen = buf.readUInt16LE(p + 30), commentLen = buf.readUInt16LE(p + 32);
        const local = buf.readUInt32LE(p + 42);
        const name = buf.subarray(p + 46, p + 46 + nameLen).toString('utf8');
        if (!name || name.split('/').some((s) => s === '..' || s === '.' || s === '') || name.startsWith('/') || name.includes('\\') || name.includes(':'))
            throw new Error(`a ZIP entry escapes its folder: ${name}`);
        if (Object.hasOwn(out, name)) throw new Error(`the archive holds ${name} twice`);
        if (flags & 0x0009) throw new Error(`${name}: an encrypted entry or a data descriptor is not accepted`);
        if (local + 30 > cdStart || buf.readUInt32LE(local) !== 0x04034b50) throw new Error(`${name}: its local header is not where the central directory says`);
        const localNameLen = buf.readUInt16LE(local + 26), localExtra = buf.readUInt16LE(local + 28);
        if (buf.subarray(local + 30, local + 30 + localNameLen).toString('utf8') !== name) throw new Error(`${name}: its local header names another file`);
        const at = local + 30 + localNameLen + localExtra;
        if (at + size > cdStart) throw new Error(`${name}: its data runs into the central directory`);
        const body = buf.subarray(at, at + size);
        out[name] = method === 0 ? Buffer.from(body) : method === 8 ? inflateRawSync(body) : (() => { throw new Error(`${name}: compression method ${method} is not supported`); })();
        byOffset.set(local, at + size);
        p += 46 + nameLen + extraLen + commentLen;
    }
    // the local entries must tile [0, central directory): no hidden entry the central directory does not list
    for (let q = 0; q < cdStart;) {
        const next = byOffset.get(q);
        if (next === undefined) throw new Error(`bytes at offset ${q} belong to no listed entry - a hidden entry is not accepted`);
        q = next;
    }
    return out;
}

/** Keys from a `keyId=base64(SPKI)` properties file (an install's conf/app-signers.properties), or {} when there is none. */
export const signerKeys = (file) => {
    if (!existsSync(file)) throw new Error(`${file}: no such signer allowlist`);
    return trustedKeys(file);
};

/** Add `extra` signer keys to `trust`, refusing a key id already trusted (a partner key may never take the issuer's id). */
export function addSigners(trust, extra) {
    for (const [id, key] of Object.entries(extra)) {
        if (Object.hasOwn(trust, id)) throw new Error(`key id '${id}' is trusted already - a signer allowlist never redefines it`);
        trust[id] = key;
    }
    return trust;
}

/**
 * Verify a packaged App (PA-7c): one App folder; every file listed in MANIFEST.sha256 with its hash, and nothing unlisted;
 * MANIFEST.sig names a key in `trust` and its Ed25519 signature matches the manifest. Returns { app, version, keyId, files } or
 * throws naming the first thing that does not hold.
 */
export function verifyPackage(buf, trust) {
    const entries = unzip(buf);
    const roots = [...new Set(Object.keys(entries).map((n) => n.split('/')[0]))];
    if (roots.length !== 1) throw new Error(`a package holds one App folder, found ${roots.join(', ') || 'none'}`);
    const id = roots[0];
    const manifest = entries[`${id}/MANIFEST.sha256`] ?? (() => { throw new Error('no MANIFEST.sha256 - package the App with inspecto-app.mjs package'); })();
    const sig = entries[`${id}/MANIFEST.sig`] ?? (() => { throw new Error('the package is not signed (no MANIFEST.sig) - package it with --key'); })();
    const fields = Object.create(null);
    for (const l of sig.toString('utf8').split(/\r?\n/).filter((x) => x.includes('='))) {
        const k = l.slice(0, l.indexOf('=')).trim();
        if (Object.hasOwn(fields, k)) throw new Error(`MANIFEST.sig names '${k}' twice`);
        fields[k] = l.slice(l.indexOf('=') + 1).trim();
    }
    const key = (Object.hasOwn(trust, fields.keyId ?? '') ? trust[fields.keyId] : null) ?? (() => { throw new Error(`signed by key '${fields.keyId ?? ''}', which is not trusted here (${Object.keys(trust).join(', ') || 'no keys'})`); })();
    if (!edVerify(null, manifest, key, Buffer.from(fields.signature ?? '', 'base64'))) throw new Error('the manifest signature does not match - the package was changed after signing, or signed with another key');
    const listed = new Map(manifest.toString('utf8').split('\n').filter(Boolean).map((l) => [l.slice(66), l.slice(0, 64)]));
    for (const [name, hash] of listed) {
        const data = entries[name] ?? (() => { throw new Error(`${name} is in the manifest but not in the package`); })();
        if (createHash('sha256').update(data).digest('hex') !== hash) throw new Error(`${name} does not match its manifest hash`);
    }
    for (const name of Object.keys(entries))
        if (!listed.has(name) && name !== `${id}/MANIFEST.sha256` && name !== `${id}/MANIFEST.sig`) throw new Error(`${name} is in the package but not in the manifest`);
    const app = parseToon((entries[`${id}/app.toon`] ?? Buffer.from('')).toString('utf8'));
    if (app.id !== id) throw new Error(`the package folder is '${id}' but app.toon names '${app.id ?? ''}'`);
    return { app: id, version: app.version, keyId: fields.keyId, files: listed.size };
}

/** A partner's signing key pair: <out>/<id>.pem (private) and <out>/<id>.properties (the install's trust line). */
export function keygen(keyId, out) {
    if (!/^[a-z][a-z0-9-]{1,63}$/.test(keyId ?? '')) throw new Error(`--key-id '${keyId ?? ''}' must be lower-case letters, digits and hyphens`);
    const pem = join(resolve(out), `${keyId}.pem`), props = join(resolve(out), `${keyId}.properties`);
    if (existsSync(pem) || existsSync(props)) throw new Error(`${pem} or its .properties exists already - a key id is never reused`);
    if (Object.hasOwn(trustedKeys(KEYS_FILE), keyId)) throw new Error(`'${keyId}' is an issuer key id - a partner key takes an id of its own`);
    const { publicKey, privateKey } = generateKeyPairSync('ed25519');
    mkdirSync(resolve(out), { recursive: true });
    writeFileSync(pem, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
    writeFileSync(props, `${keyId}=${publicResource(publicKey)}\n`);
    return { pem, props };
}

// ── command line ──

async function main() {
    const [cmd, ...a] = process.argv.slice(2);
    const val = (k) => { const i = a.indexOf(k); return i >= 0 ? a[i + 1] : undefined; };
    const report = (findings, ok) => {
        if (findings.length) { for (const f of findings) console.error(`✗ ${f}`); process.exit(1); }
        console.log(`✓ ${ok}`);
    };
    const appRoot = () => val('--app-root') ?? (() => { throw new Error('--app-root <folder> is required'); })();
    try {
        if (cmd === 'new') {
            const id = a[0] && !a[0].startsWith('--') ? a[0] : (() => { throw new Error('usage: inspecto-app.mjs new <id> --dir <folder>'); })();
            const dir = val('--dir') ?? (() => { throw new Error('--dir <folder> is required'); })();
            const files = scaffold(id, dir, { title: val('--title') ?? null, industry: val('--industry') ?? null,
                addons: (val('--addons') ?? '').split(',').map((s) => s.trim()).filter(Boolean) });
            console.log(`✓ App '${id}' scaffolded in ${resolve(dir)} (${files.length} files). Next: validate, then test --app-root ${dir}`);
        } else if (cmd === 'validate') {
            report(validate(appRoot()), `${relative(process.cwd(), resolve(appRoot())) || '.'}: the App validates`);
        } else if (cmd === 'test') {
            const { findings, editions } = staticConformance(appRoot());
            if (!findings.length && val('--bundle')) findings.push(...await bootConformance(appRoot(), val('--bundle'), { log: (m) => console.log(`  ${m}`) }));
            report(findings, `conformance: assembled on ${editions.join(', ')}${val('--bundle') ? `; golden data held on ${val('--bundle')}` : ''}`);
        } else if (cmd === 'package') {
            const keyFile = val('--key');
            const r = packageApp(appRoot(), val('--out') ?? join(resolve(appRoot()), 'dist'), keyFile
                ? { key: readFileSync(keyFile, 'utf8'), keyId: val('--key-id') ?? keyFile.split(/[\\/]/).pop().replace(/\.pem$/, '') } : {});
            console.log(`✓ ${r.file} (${r.files} files, ${r.bytes} bytes${r.signed ? ', signed' : ', NOT signed - a server installs only a signed package'})`);
        } else if (cmd === 'verify') {
            const pkg = val('--package') ?? (() => { throw new Error('--package <zip> is required'); })();
            const trust = Object.assign(Object.create(null), trustedKeys(KEYS_FILE));
            a.forEach((x, i) => { if (x === '--trust' && a[i + 1]) addSigners(trust, signerKeys(a[i + 1])); });
            const r = verifyPackage(readFileSync(pkg), trust);
            console.log(`✓ ${pkg}: App '${r.app}' ${r.version}, ${r.files} files, signed by '${r.keyId}'`);
        } else if (cmd === 'keygen') {
            const r = keygen(val('--key-id'), val('--out') ?? '.');
            console.log(`✓ private key ${r.pem} (keep it private; never commit it)\n✓ ${r.props}: give this line to an install, which adds it to conf/app-signers.properties`);
        } else {
            throw new Error('usage: inspecto-app.mjs new <id> --dir <folder> | validate --app-root <folder> | test --app-root <folder> [--bundle <dir>] | package --app-root <folder> [--out <dir>] [--key <pem>] | verify --package <zip> [--trust <keys.properties>] | keygen --key-id <id> --out <dir>');
        }
    } catch (e) {
        console.error(`inspecto-app: ${e.message}`);
        process.exit(1);
    }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
