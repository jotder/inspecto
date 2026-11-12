#!/usr/bin/env node
// ship — the container and air-gap targets of a Product build (PRODUCT-ASSEMBLY-1 PA-8; plan
// docs/superpower/2026-11-10-product-assembly-plan.md §11, D-PA26…D-PA30).
//
//   node tools/ship.mjs chart --plan <assembly-plan.json> --out <dir> [--no-package]
//
// `chart` builds the plan's Product chart (D-PA27, as changed 2026-11-12: HELM-PRODUCT-VALUES-1) into <out>/<image name>/:
// the templates of the ONE source (deploy/helm/inspecto/templates), the Product's committed folder
// deploy/helm/products/<id>/ (its Chart.yaml, and its values.yaml laid over the source's values.yaml, key by key), then
// the build's facts (version = platform version, appVersion = image tag, image.repository, the `product` block); and,
// unless --no-package, <out>/<name>-<version>.tgz (the layout `helm package` writes). The plan must come from
// `assemble.mjs --target container|airgap` (its `ship` field).
//
//   node tools/ship.mjs check
//
// `check` is the guard (CI, the Product guard step): the template source stays single - no Chart.yaml or template
// outside deploy/helm/inspecto, each Product's folder holds Chart.yaml and values.yaml only and agrees with products/<id>.toon,
// and every value it sets is one the source has.
//
//   node tools/ship.mjs image --plan <assembly-plan.json> --bundle-dir <unpacked linux_amd64 bundle> --out <dir>
//
// `image` (D-PA26; package.ps1 -Target container runs it) builds `inspecto-<product>:<tag>` with the bundle's own
// deploy/Dockerfile, checks the image's io.inspecto.product label, saves it as <out>/<image>-<tag>.image.tar and stamps
// and packs the chart beside it.
//
//   node tools/ship.mjs airgap --plan <assembly-plan.json> --bundle-dir <unpacked linux_amd64 bundle> --out <dir>
//                              [--allow-missing-extensions]
//
// `airgap` (D-PA28; package.ps1 -Target airgap runs it) makes <out>/<image>-<tag>-airgap/ and its .tar (+ .sha256):
// bundle/inspecto-deploy/ (the unpacked Linux bundle, for a host install), image/<image>-<tag>.image.tar, chart/<chart>.tgz,
// install-offline.sh (from deploy/airgap/), airgap.json (the manifest) and SHA256SUMS over every other file. The DuckDB
// extensions package.ps1 stages ($duckdbExtNames) must all be in bundle/.../lib/duckdb/linux_amd64/;
// --allow-missing-extensions is for a desk proof only: it records complete: false and the missing names, and the
// installer warns.
//
// REFUSES (exit 1, the reason named): a plan without `ship`; a Product with no committed chart folder; a value the
// Product's folder sets that the chart source does not have (a Product overrides values, it never adds one); a bundle that is not the plan's
// linux_amd64 build (lib/product.json, an ELF runtime/bin/java, the Dockerfile's product label); an image whose label
// disagrees with the plan; an air-gapped bundle missing a DuckDB extension (unless --allow-missing-extensions).

import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, readFileSync, readdirSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { shipOf } from './assemble.mjs';
import { gatherModel } from './products.mjs';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
export const CHART_SOURCE = 'deploy/helm/inspecto';
export const PRODUCT_CHARTS = 'deploy/helm/products';
export const AIRGAP_INSTALLER = 'deploy/airgap/install-offline.sh';
export const AIRGAP_SCHEMA = 'inspecto.airgap/1';
const fail = (msg) => { throw new Error(msg); };

/** The plan's `ship` field, or a refusal that says how to get one. */
export function shipOfPlan(plan) {
    return plan?.ship ?? fail('the Assembly Plan has no `ship` field: assemble it with --target container or --target airgap');
}

/**
 * The `key: value` leaves and `key:` blocks of a values or Chart.yaml file, by dotted path (HELM-PRODUCT-VALUES-1).
 * Only block mappings are read: a value stays the text after `key: ` (a scalar or a flow collection such as `{ a: 1 }`);
 * a list or a multi-line scalar is refused, so a file this cannot read is never stamped by guesswork.
 */
export function yamlPaths(text, file) {
    const stack = [];
    const leaves = new Map();
    const blocks = new Set();
    text.split('\n').forEach((raw, i) => {
        const line = raw.replace(/\r$/, '');
        if (!line.trim() || line.trim().startsWith('#')) return;
        const m = line.match(/^( *)([A-Za-z0-9_./-]+):(?: +(.*))?$/);
        if (!m) fail(`${file}:${i + 1}: only 'key: value' and 'key:' lines are understood here (no lists, no multi-line values): ${line.trim()}`);
        while (stack.length && stack[stack.length - 1].indent >= m[1].length) stack.pop();
        const path = [...stack.map((s) => s.key), m[2]].join('.');
        if (!m[3] || m[3].startsWith('#')) { blocks.add(path); stack.push({ indent: m[1].length, key: m[2] }); }
        else leaves.set(path, { i, indent: m[1], key: m[2], value: m[3] });
    });
    return { leaves, blocks };
}

/** `text` with each override's leaf line set to its value. A path the text has no leaf for is refused (never added). */
function overlay(text, overrides, file) {
    const { leaves, blocks } = yamlPaths(text, file);
    const lines = text.split('\n');
    for (const { path, value, from } of overrides) {
        const at = leaves.get(path);
        if (!at) fail(blocks.has(path)
            ? `${from}: '${path}' is a block in ${file}; override its keys one by one`
            : `${from}: '${path}' is not a value in ${file} - a Product overrides the source's values, it never adds one (a map value such as env is written in flow style: env: { KEY: value })`);
        lines[at.i] = `${at.indent}${at.key}: ${value}`;
    }
    return lines.join('\n');
}

const q = (s) => JSON.stringify(String(s)); // a JSON string is a valid YAML double-quoted scalar
const unq = (v) => { const s = String(v ?? '').replace(/\s+#.*$/, '').trim(); return /^".*"$/.test(s) ? JSON.parse(s) : s.replace(/^'(.*)'$/, '$1'); };

/**
 * The committed chart folder a Product build uses (D-PA27 as changed 2026-11-12): deploy/helm/products/<id>/, or the
 * Platform product's for a picked Platform product (product-map/private/products/, which commits no folder).
 */
export function productChartFolder(plan, repo = REPO) {
    const own = `${PRODUCT_CHARTS}/${plan.product.id}`;
    if (existsSync(join(repo, own, 'Chart.yaml'))) return own;
    if (plan.product.shape === 'platform' && existsSync(join(repo, PRODUCT_CHARTS, 'inspecto', 'Chart.yaml'))) return `${PRODUCT_CHARTS}/inspecto`;
    return fail(`Product '${plan.product.id}' has no committed chart folder ${own}/ (Chart.yaml and values.yaml over ${CHART_SOURCE}): add one`);
}

/**
 * Build the chart for one Product build into <out>/<chart name>/ (HELM-PRODUCT-VALUES-1): the templates of the ONE source
 * (deploy/helm/inspecto/templates), the Product's committed Chart.yaml, and the source's values.yaml with the Product's
 * committed values.yaml over it; then the build's facts (name, version, appVersion, image, Product, Edition, Distribution).
 * Returns { dir, name, version, folder }.
 */
export function stampChart(plan, out, { repo = REPO } = {}) {
    const { image, chart } = shipOfPlan(plan);
    const edition = String(plan.edition).toLowerCase();
    const folder = productChartFolder(plan, repo);
    const dir = join(out, chart.name);
    rmSync(dir, { recursive: true, force: true });
    mkdirSync(out, { recursive: true });
    cpSync(join(repo, CHART_SOURCE, 'templates'), join(dir, 'templates'), { recursive: true });

    const build = (from) => (path, value) => ({ path, value, from });
    const fact = build('the build');
    const chartFile = `${folder}/Chart.yaml`;
    writeFileSync(join(dir, 'Chart.yaml'),
        `# Generated by tools/ship.mjs from ${chartFile} and ${CHART_SOURCE}/templates - edit those, never this copy.\n`
        + overlay(readFileSync(join(repo, chartFile), 'utf8'), [
            fact('name', chart.name), fact('version', q(chart.version)), fact('appVersion', q(chart.appVersion)),
            fact('annotations.inspecto.io/product', q(plan.product.id)), fact('annotations.inspecto.io/edition', q(edition)),
            fact('annotations.inspecto.io/distribution', q(plan.distribution)),
        ], chartFile));

    const valuesFile = `${folder}/values.yaml`;
    const own = [...yamlPaths(readFileSync(join(repo, valuesFile), 'utf8'), valuesFile).leaves].map(([path, l]) => build(valuesFile)(path, l.value));
    writeFileSync(join(dir, 'values.yaml'),
        `# Generated by tools/ship.mjs: ${CHART_SOURCE}/values.yaml with ${valuesFile} over it, and the build's Product, Edition and\n`
        + '# Distribution. Edit those, never this copy.\n'
        + overlay(readFileSync(join(repo, CHART_SOURCE, 'values.yaml'), 'utf8'), [
            ...own,
            fact('image.repository', image.repository), fact('product.id', q(plan.product.id)), fact('product.title', q(plan.product.title)),
            fact('product.edition', q(edition)), fact('product.distribution', q(plan.distribution)),
        ], `${CHART_SOURCE}/values.yaml`));
    return { dir, name: chart.name, version: chart.version, folder };
}

/**
 * The guard (HELM-PRODUCT-VALUES-1): the template source stays single. `files` are the repo's tracked paths (POSIX).
 * Every Chart.yaml or file under a templates/ folder of a chart lives in deploy/helm/inspecto; deploy/helm/products/<id>/
 * holds Chart.yaml and values.yaml only, one per Product in products/; each folder's Chart.yaml and values.yaml name
 * the Product, its chart and its image as products/<id>.toon does, and its values override only what the source has.
 * Returns the findings (empty = good).
 */
export function checkChartFolders({ repo = REPO, products, files }) {
    const findings = [];
    const isChart = (f) => /(^|\/)Chart\.yaml$/.test(f);
    for (const f of files) {
        if (f.startsWith(`${CHART_SOURCE}/`)) continue;
        const m = f.match(new RegExp(`^${PRODUCT_CHARTS}/([^/]+)/(.+)$`));
        if (m) { if (!['Chart.yaml', 'values.yaml'].includes(m[2])) findings.push(`${f}: a Product's chart folder holds Chart.yaml and values.yaml only - templates live in ${CHART_SOURCE}/templates`); continue; }
        if (isChart(f)) findings.push(`${f}: a second chart - the one template source is ${CHART_SOURCE}; a Product's chart is ${PRODUCT_CHARTS}/<id>/`);
        else if (f.startsWith('deploy/helm/')) findings.push(`${f}: deploy/helm holds the chart source and ${PRODUCT_CHARTS}/<id>/ only`);
    }
    const folders = new Set(files.map((f) => f.match(new RegExp(`^${PRODUCT_CHARTS}/([^/]+)/`))?.[1]).filter(Boolean));
    for (const id of folders) if (!products[id]) findings.push(`${PRODUCT_CHARTS}/${id}/: no Product products/${id}.toon`);
    const sourceValues = readFileSync(join(repo, CHART_SOURCE, 'values.yaml'), 'utf8');
    for (const [id, p] of Object.entries(products)) {
        const dir = `${PRODUCT_CHARTS}/${id}`;
        if (!folders.has(id)) { findings.push(`${dir}/: Product '${id}' has no chart folder (Chart.yaml and values.yaml over ${CHART_SOURCE})`); continue; }
        const { repository } = shipOf(id, 'standard', 'production', '0').image;
        try {
            const chart = yamlPaths(readFileSync(join(repo, dir, 'Chart.yaml'), 'utf8'), `${dir}/Chart.yaml`).leaves;
            const values = yamlPaths(readFileSync(join(repo, dir, 'values.yaml'), 'utf8'), `${dir}/values.yaml`).leaves;
            const want = (file, leaves, path, expected) => {
                const got = unq(leaves.get(path)?.value);
                if (got !== expected) findings.push(`${dir}/${file}: ${path} is '${got}', products/${id}.toon makes it '${expected}'`);
            };
            want('Chart.yaml', chart, 'name', repository);
            want('Chart.yaml', chart, 'annotations.inspecto.io/product', id);
            want('values.yaml', values, 'image.repository', repository);
            want('values.yaml', values, 'product.id', id);
            want('values.yaml', values, 'product.title', p.title);
            overlay(sourceValues, [...values].map(([path, l]) => ({ path, value: l.value, from: `${dir}/values.yaml` })), `${CHART_SOURCE}/values.yaml`);
        } catch (e) { findings.push(e.message); }
    }
    return findings;
}

/**
 * Pack a stamped chart as <out>/<name>-<version>.tgz, the archive `helm package` writes (one top folder, the chart name).
 * tar runs inside <out> with relative names: GNU tar (Git for Windows) reads `C:\...` as a remote host.
 */
export function packChart({ dir, name, version }) {
    const out = dirname(dir);
    const file = `${name}-${version}.tgz`;
    rmSync(join(out, file), { force: true });
    execFileSync('tar', ['-czf', file, name], { cwd: out, stdio: ['ignore', 'ignore', 'inherit'] });
    if (!existsSync(join(out, file))) fail(`tar wrote no ${file}`);
    return join(out, file);
}

/**
 * Check that an unpacked bundle is the plan's Product build before an image is made of it: its lib/product.json names the
 * plan's Product, Edition and Distribution, it carries a Linux JVM (runtime/bin/java, an ELF file), and its Dockerfile has
 * the plan's OCI product label. Returns the findings (empty = good).
 */
export function checkBundleForImage(plan, bundleDir) {
    const findings = [];
    const read = (rel) => { try { return readFileSync(join(bundleDir, rel)); } catch { findings.push(`the bundle has no ${rel}`); return null; } };
    const desc = read('lib/product.json');
    if (desc) {
        const d = JSON.parse(desc.toString('utf8'));
        if (d.id !== plan.product.id) findings.push(`lib/product.json is Product '${d.id}', the plan '${plan.product.id}'`);
        if (String(d.edition).toLowerCase() !== String(plan.edition).toLowerCase()) findings.push(`lib/product.json is the ${d.edition} Edition, the plan ${plan.edition}`);
        if (d.distribution !== plan.distribution) findings.push(`lib/product.json is the ${d.distribution} distribution, the plan ${plan.distribution}`);
    }
    const java = read('runtime/bin/java');
    if (java && !(java[0] === 0x7f && java.subarray(1, 4).toString('latin1') === 'ELF')) findings.push('runtime/bin/java is not a Linux (ELF) executable: the image needs the linux_amd64 bundle');
    const df = read('deploy/Dockerfile');
    if (df && !df.toString('utf8').includes(`io.inspecto.product=${JSON.stringify(plan.product.id)}`)) findings.push(`deploy/Dockerfile carries no io.inspecto.product label for '${plan.product.id}'`);
    return findings;
}

const run = (cmd, args, opts = {}) => execFileSync(cmd, args, { stdio: ['ignore', 'inherit', 'inherit'], ...opts });

/**
 * Build the plan's image from an unpacked linux_amd64 bundle with the bundle's own Dockerfile (D-PA26), check its
 * io.inspecto.product label, save it as <out>/<image>-<tag>.image.tar and stamp the chart beside it.
 * `docker` is injectable for tests. Returns { ref, imageTar, chart }.
 */
export function buildImage(plan, bundleDir, out, { docker = run, chartOut = out } = {}) {
    const { image } = shipOfPlan(plan);
    const findings = checkBundleForImage(plan, bundleDir);
    if (findings.length) fail(`the bundle at ${bundleDir} is not this plan's linux_amd64 build:\n  ${findings.join('\n  ')}`);
    const ref = `${image.repository}:${image.tag}`;
    mkdirSync(out, { recursive: true });
    docker('docker', ['build', '-f', 'deploy/Dockerfile', '-t', ref, '.'], { cwd: bundleDir });
    const label = String(docker('docker', ['image', 'inspect', '--format', '{{ index .Config.Labels "io.inspecto.product" }}', ref], { stdio: ['ignore', 'pipe', 'inherit'], encoding: 'utf8' }) ?? '').trim();
    if (label !== plan.product.id) fail(`image ${ref} is labelled io.inspecto.product='${label}', the plan '${plan.product.id}'`);
    const imageTar = join(out, `${image.repository}-${image.tag}.image.tar`);
    docker('docker', ['save', '-o', imageTar, ref]);
    const stamped = stampChart(plan, chartOut);
    return { ref, imageTar, chart: packChart(stamped), chartDir: stamped.dir };
}

/** The DuckDB extension names package.ps1 stages, read from it so the list lives once (as fetch-duckdb-extensions does). */
export function duckdbExtensionNames(repo = REPO) {
    const m = readFileSync(join(repo, 'inspecto', 'package.ps1'), 'utf8').match(/\$duckdbExtNames\s*=\s*@\(([^)]*)\)/);
    if (!m) fail('inspecto/package.ps1 has no $duckdbExtNames list');
    const names = [...m[1].matchAll(/'([^']+)'/g)].map((x) => x[1]);
    if (!names.length) fail('inspecto/package.ps1 $duckdbExtNames is empty');
    return names;
}

/** Every file under `root` as a sorted POSIX path relative to it. */
function filesUnder(root, dir = root) {
    return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
        const p = join(dir, e.name);
        return e.isDirectory() ? filesUnder(root, p) : [relative(root, p).split(sep).join('/')];
    }).sort();
}

const sha256 = (file) => createHash('sha256').update(readFileSync(file)).digest('hex');

/** The installer with its @NAME@ values filled in; a value that cannot sit in single quotes is refused. */
export function renderInstaller(template, values) {
    let text = template;
    for (const [k, v] of Object.entries(values)) {
        const s = String(v);
        if (/['\n\r]/.test(s)) fail(`install-offline.sh: the value of ${k} cannot hold a quote or a line break: ${s}`);
        text = text.split(`@${k}@`).join(s);
    }
    const left = text.match(/@[A-Z_]+@/);
    if (left) fail(`install-offline.sh: ${left[0]} has no value`);
    return text;
}

/**
 * The air-gapped bundle (D-PA28): build the image and chart, lay out bundle/ image/ chart/ with the installer, the manifest
 * and SHA256SUMS, then tar the folder. Returns { root, tar, complete, missing }.
 */
export function buildAirgap(plan, bundleDir, out, { docker = run, allowMissingExtensions = false, repo = REPO } = {}) {
    const { image, chart } = shipOfPlan(plan);
    const required = duckdbExtensionNames(repo);
    const missing = required.filter((n) => !existsSync(join(bundleDir, 'lib', 'duckdb', 'linux_amd64', `${n}.duckdb_extension`)));
    if (missing.length && !allowMissingExtensions)
        fail(`the bundle lacks the linux_amd64 DuckDB extension(s) ${missing.join(', ')}, which an offline host cannot INSTALL; populate the cache (node tools/fetch-duckdb-extensions.mjs) and package again`);
    const name = `${image.repository}-${image.tag}-airgap`;
    const root = join(out, name);
    rmSync(root, { recursive: true, force: true });
    mkdirSync(join(root, 'bundle'), { recursive: true });

    const built = buildImage(plan, bundleDir, join(root, 'image'), { docker, chartOut: join(root, 'chart') });
    rmSync(built.chartDir, { recursive: true, force: true }); // only the packed chart travels
    cpSync(bundleDir, join(root, 'bundle', 'inspecto-deploy'), { recursive: true });

    const imageTar = `${image.repository}-${image.tag}.image.tar`;
    const chartTgz = `${chart.name}-${chart.version}.tgz`;
    writeFileSync(join(root, 'install-offline.sh'), renderInstaller(readFileSync(join(repo, AIRGAP_INSTALLER), 'utf8'), {
        TITLE: plan.product.title, EDITION: plan.edition, DISTRIBUTION: plan.distribution,
        IMAGE_REF: built.ref, IMAGE_REPO: image.repository, IMAGE_TAR: imageTar, CHART_TGZ: chartTgz,
        COMPLETE: missing.length ? 'false' : 'true', MISSING: missing.join(' '),
    }));
    const complete = missing.length === 0;
    writeFileSync(join(root, 'airgap.json'), JSON.stringify({
        schema: AIRGAP_SCHEMA,
        product: plan.product.id, title: plan.product.title, edition: plan.edition, distribution: plan.distribution,
        platformVersion: plan.platformVersion,
        image: { ref: built.ref, tar: `image/${imageTar}` },
        chart: `chart/${chartTgz}`,
        bundle: 'bundle/inspecto-deploy',
        extensions: { platform: 'linux_amd64', required, missing },
        complete,
    }, null, 2) + '\n');
    writeFileSync(join(root, 'SHA256SUMS'), filesUnder(root).filter((f) => f !== 'SHA256SUMS').map((f) => `${sha256(join(root, f))}  ${f}\n`).join(''));

    // tar runs inside <out> with relative names (GNU tar reads `C:\...` as a remote host).
    const tar = join(out, `${name}.tar`);
    rmSync(tar, { force: true });
    execFileSync('tar', ['-cf', `${name}.tar`, name], { cwd: out, stdio: ['ignore', 'ignore', 'inherit'] });
    writeFileSync(`${tar}.sha256`, `${sha256(tar)}  ${name}.tar\n`);
    return { root, tar, complete, missing };
}

const USAGE = [
    'usage: node tools/ship.mjs check',
    '       node tools/ship.mjs chart --plan <assembly-plan.json> --out <dir> [--no-package]',
    '       node tools/ship.mjs image --plan <assembly-plan.json> --bundle-dir <unpacked linux_amd64 bundle> --out <dir>',
    '       node tools/ship.mjs airgap --plan <assembly-plan.json> --bundle-dir <unpacked linux_amd64 bundle> --out <dir> [--allow-missing-extensions]',
].join('\n');

function main() {
    const [cmd, ...a] = process.argv.slice(2);
    const val = (k) => { const i = a.indexOf(k); return i >= 0 ? a[i + 1] : undefined; };
    try {
        if (!['check', 'chart', 'image', 'airgap'].includes(cmd)) fail(USAGE);
        if (cmd === 'check') {
            const files = execFileSync('git', ['ls-files'], { cwd: REPO, encoding: 'utf8' }).split('\n').filter(Boolean);
            const findings = checkChartFolders({ products: gatherModel(REPO).products, files });
            if (findings.length) fail(`the Helm charts are not one template source with a folder per Product:\n  ${findings.join('\n  ')}`);
            console.log(`ship: one chart source (${CHART_SOURCE}), one folder per Product under ${PRODUCT_CHARTS}/ - OK`);
            return;
        }
        const plan = JSON.parse(readFileSync(val('--plan') ?? fail('--plan <assembly-plan.json> is required'), 'utf8'));
        const out = resolve(val('--out') ?? fail('--out <dir> is required'));
        if (cmd === 'airgap') {
            const r = buildAirgap(plan, resolve(val('--bundle-dir') ?? fail('--bundle-dir <unpacked linux_amd64 bundle> is required')), out,
                { allowMissingExtensions: a.includes('--allow-missing-extensions') });
            if (!r.complete) console.error(`ship: WARNING - --allow-missing-extensions: the bundle lacks ${r.missing.join(', ')} (airgap.json says complete: false)`);
            console.log(`ship: air-gapped bundle ${r.tar}`);
            return;
        }
        if (cmd === 'image') {
            const r = buildImage(plan, resolve(val('--bundle-dir') ?? fail('--bundle-dir <unpacked linux_amd64 bundle> is required')), out);
            console.log(`ship: image ${r.ref} -> ${r.imageTar}`);
            console.log(`ship: chart ${r.chart}`);
            return;
        }
        const stamped = stampChart(plan, out);
        console.log(`ship: chart ${stamped.name} ${stamped.version} (from ${stamped.folder}) -> ${stamped.dir}`);
        if (!a.includes('--no-package')) console.log(`ship: packaged ${packChart(stamped)}`);
    } catch (e) {
        console.error(`ship: ${e.message}`);
        process.exit(1);
    }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
