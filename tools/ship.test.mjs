// Tests for tools/ship.mjs — PRODUCT-ASSEMBLY-1 PA-8. Run: node --test tools/ship.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync, cpSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assemble } from './assemble.mjs';
import { gatherModel } from './products.mjs';
import { gather } from './check-offerings.mjs';
import { CHART_SOURCE, packChart, stampChart } from './ship.mjs';

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
