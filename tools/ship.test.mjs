// Tests for tools/ship.mjs — PRODUCT-ASSEMBLY-1 PA-8. Run: node --test tools/ship.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync, cpSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assemble } from './assemble.mjs';
import { gatherModel } from './products.mjs';
import { gather } from './check-offerings.mjs';
import { buildImage, CHART_SOURCE, packChart, stampChart } from './ship.mjs';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');
const model = gatherModel(repo);
const g = gather(repo);
const tmp = () => mkdtempSync(join(tmpdir(), 'ship-test-'));

test('chart: Inspecto Telco x Standard is stamped with its image, version and Product, and packs like helm package', () => {
    const plan = assemble(model, g, { product: 'telco', edition: 'standard', target: 'container' });
    const out = tmp();
    try {
        const s = stampChart(plan, out);
        assert.equal(s.name, 'inspecto-telco');
        const chart = readFileSync(join(s.dir, 'Chart.yaml'), 'utf8');
        assert.match(chart, /^name: inspecto-telco$/m);
        assert.match(chart, new RegExp(`^version: "${plan.platformVersion}"$`, 'm'));
        assert.match(chart, new RegExp(`^appVersion: "${plan.platformVersion}-standard"$`, 'm'));
        assert.match(chart, /^ {2}inspecto\.io\/product: "telco"$/m);
        const values = readFileSync(join(s.dir, 'values.yaml'), 'utf8');
        assert.match(values, /^ {2}repository: inspecto-telco$/m);
        assert.match(values, /^ {2}id: "telco"$/m);
        assert.match(values, /^ {2}title: "Inspecto Telco"$/m);
        assert.match(values, /^ {2}edition: "standard"$/m);
        assert.deepEqual(readdirSync(join(s.dir, 'templates')).sort(), readdirSync(join(repo, CHART_SOURCE, 'templates')).sort(), 'every template is copied');
        const tgz = packChart(s);
        assert.ok(tgz.endsWith(`inspecto-telco-${plan.platformVersion}.tgz`));
        const listed = execFileSync('tar', ['-tzf', `inspecto-telco-${plan.platformVersion}.tgz`], { cwd: out, encoding: 'utf8' }).split('\n').map((l) => l.replace(/\\/g, '/'));
        assert.ok(listed.includes('inspecto-telco/Chart.yaml') && listed.includes('inspecto-telco/templates/statefulset.yaml'), listed.join(', '));
    } finally { rmSync(out, { recursive: true, force: true }); }
});

test('chart: the Platform product keeps the name inspecto; a trial build is stamped trial', () => {
    const out = tmp();
    try {
        const s = stampChart(assemble(model, g, { product: 'inspecto', edition: 'basic', distribution: 'trial', target: 'airgap' }), out);
        assert.equal(s.name, 'inspecto');
        assert.match(readFileSync(join(s.dir, 'values.yaml'), 'utf8'), /^ {2}distribution: "trial"$/m);
        assert.match(readFileSync(join(s.dir, 'Chart.yaml'), 'utf8'), /^appVersion: ".*-basic-trial"$/m);
    } finally { rmSync(out, { recursive: true, force: true }); }
});

/** A fake unpacked bundle: lib/product.json from the plan, a runtime/bin/java with the given magic, a labelled Dockerfile. */
function fakeBundle(plan, { magic = '\x7fELF', productId = plan.product.id, label = plan.product.id } = {}) {
    const dir = tmp();
    mkdirSync(join(dir, 'lib'), { recursive: true });
    mkdirSync(join(dir, 'runtime', 'bin'), { recursive: true });
    mkdirSync(join(dir, 'deploy'), { recursive: true });
    writeFileSync(join(dir, 'lib', 'product.json'), JSON.stringify({ ...plan.descriptor, id: productId }));
    writeFileSync(join(dir, 'runtime', 'bin', 'java'), Buffer.from(magic + 'rest', 'latin1'));
    writeFileSync(join(dir, 'deploy', 'Dockerfile'), `FROM x\nLABEL io.inspecto.product=${JSON.stringify(label)}\n`);
    return dir;
}

test('image: builds with the bundle Dockerfile, checks the label, saves the image and the chart beside it', () => {
    const plan = assemble(model, g, { product: 'telco', edition: 'standard', target: 'container' });
    const bundle = fakeBundle(plan);
    const out = tmp();
    const calls = [];
    const docker = (cmd, args, opts) => { calls.push({ args, cwd: opts?.cwd }); return args[1] === 'inspect' ? 'telco\n' : null; };
    try {
        const r = buildImage(plan, bundle, out, { docker });
        const ref = `inspecto-telco:${plan.platformVersion}-standard`;
        assert.equal(r.ref, ref);
        assert.deepEqual(calls[0], { args: ['build', '-f', 'deploy/Dockerfile', '-t', ref, '.'], cwd: bundle });
        assert.equal(calls[1].args[1], 'inspect');
        assert.deepEqual(calls[2].args, ['save', '-o', join(out, `inspecto-telco-${plan.platformVersion}-standard.image.tar`), ref]);
        assert.ok(r.chart.endsWith(`inspecto-telco-${plan.platformVersion}.tgz`));
    } finally { rmSync(bundle, { recursive: true, force: true }); rmSync(out, { recursive: true, force: true }); }
});

test('REFUSED image: a Windows bundle, another Product\'s bundle, an unlabelled Dockerfile, an image labelled otherwise', () => {
    const plan = assemble(model, g, { product: 'telco', edition: 'standard', target: 'container' });
    const never = () => assert.fail('docker must not run for a refused bundle');
    const cases = [
        [{ magic: 'MZ\x90\x00' }, /runtime\/bin\/java is not a Linux \(ELF\) executable/],
        [{ productId: 'la' }, /lib\/product\.json is Product 'la', the plan 'telco'/],
        [{ label: 'la' }, /deploy\/Dockerfile carries no io\.inspecto\.product label for 'telco'/],
    ];
    for (const [opts, re] of cases) {
        const b = fakeBundle(plan, opts);
        try { assert.throws(() => buildImage(plan, b, tmp(), { docker: never }), re); } finally { rmSync(b, { recursive: true, force: true }); }
    }
    const b = fakeBundle(plan);
    try {
        assert.throws(() => buildImage(plan, b, tmp(), { docker: (c, args) => (args[1] === 'inspect' ? 'la' : null) }), /labelled io\.inspecto\.product='la', the plan 'telco'/);
    } finally { rmSync(b, { recursive: true, force: true }); }
});

test('REFUSED: a plan from a zip target has no ship field', () => {
    assert.throws(() => stampChart(assemble(model, g, { product: 'la', edition: 'basic' }), tmp()), /no `ship` field: assemble it with --target container/);
});

test('REFUSED: a chart source whose stamped line moved', () => {
    const fake = tmp();
    try {
        cpSync(join(repo, CHART_SOURCE), join(fake, CHART_SOURCE), { recursive: true });
        const vf = join(fake, CHART_SOURCE, 'values.yaml');
        writeFileSync(vf, readFileSync(vf, 'utf8').replace('  repository: inspecto', '  repository: other'));
        const plan = assemble(model, g, { product: 'la', edition: 'basic', target: 'container' });
        assert.throws(() => stampChart(plan, join(fake, 'out'), { repo: fake }), /expected the line ' {2}repository: inspecto' exactly once, found 0/);
    } finally { rmSync(fake, { recursive: true, force: true }); }
});
