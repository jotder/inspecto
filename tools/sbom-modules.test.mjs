// Fixtures for tools/sbom-modules.mjs (P3f per-module SBOMs). Run: node --test tools/sbom-modules.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { generatePerModule, verifyPerModule, sbomName } from './sbom-modules.mjs';

const sha = (s) => createHash('sha256').update(s).digest('hex');
const comp = (artifact, extra = {}) => ({
    group: 'org.x', artifact, version: '1.0', purl: `pkg:maven/org.x/${artifact}@1.0`, sha256: sha(artifact), license: { name: 'Apache-2.0', url: null }, ...extra,
});
const FIRST = (a) => `pkg:maven/com.gamma.inspector/${a}@9.9.9`;

/** A fake staged bundle: a thin core jar (one dep), a shaded sidecar (two deps), the processor, postgresql.jar; + the combined SBOM. */
function fixture() {
    const dir = mkdtempSync(join(tmpdir(), 'sbom-modules-'));
    const jars = { 'inspecto.jar': 'proc', 'inspecto-util.jar': 'util', 'inspecto-connectors.jar': 'conn', 'postgresql.jar': 'pg' };
    for (const [f, body] of Object.entries(jars)) writeFileSync(join(dir, f), body);
    writeFileSync(join(dir, 'modules.list'), Object.keys(jars).join('\n') + '\n');
    writeFileSync(join(dir, 'edition.properties'), 'edition=Professional\n');
    const a = comp('a'), b = comp('b'), c = comp('c');
    const pg = comp('postgresql', { group: 'org.postgresql', purl: 'pkg:maven/org.postgresql/postgresql@42.0' , version: '42.0', sha256: sha('pg') });
    const byPurl = new Map([a, b, c, pg].map((x) => [x.purl, x]));
    const specs = [
        { bundleFile: 'inspecto.jar', group: 'com.gamma.inspector', artifact: 'inspecto-processor', version: '9.9.9', depPurls: [a.purl, b.purl, c.purl] },
        { bundleFile: 'inspecto-util.jar', group: 'com.gamma.inspector', artifact: 'inspecto-util', version: '9.9.9', depPurls: [a.purl] },
        { bundleFile: 'inspecto-connectors.jar', group: 'com.gamma.inspector', artifact: 'inspecto-connectors', version: '9.9.9', depPurls: [b.purl, c.purl] },
        { bundleFile: 'postgresql.jar', group: 'org.postgresql', artifact: 'postgresql', version: '42.0', depPurls: [] },
    ];
    for (const s of specs) byPurl.set(`pkg:maven/${s.group}/${s.artifact}@${s.version}`, byPurl.get(`pkg:maven/${s.group}/${s.artifact}@${s.version}`) ?? {
        group: s.group, artifact: s.artifact, version: s.version, purl: `pkg:maven/${s.group}/${s.artifact}@${s.version}`, sha256: sha(jars[s.bundleFile]), license: null,
    });
    generatePerModule({ bundleDir: dir, jars: specs, componentsByPurl: byPurl, buildId: 'abc1234', toolVersion: '9.9.9' });
    const combined = {
        components: [...byPurl.values()].map((x) => ({ purl: x.purl, hashes: [{ alg: 'SHA-256', content: x.sha256 }] })),
    };
    writeFileSync(join(dir, 'sbom', 'inspecto-professional.cdx.json'), JSON.stringify(combined));
    return { dir, a, b, c };
}
const read = (dir, jar) => JSON.parse(readFileSync(join(dir, 'sbom', sbomName(jar)), 'utf8'));
const write = (dir, jar, doc) => writeFileSync(join(dir, 'sbom', sbomName(jar)), JSON.stringify(doc));
const withFixture = (fn) => { const f = fixture(); try { fn(f); } finally { rmSync(f.dir, { recursive: true, force: true }); } };

test('GREEN: thin jar, shaded sidecar, processor and the pg driver each get an SBOM that verifies', () => withFixture(({ dir, a }) => {
    assert.deepEqual(verifyPerModule(dir), []);
    const thin = read(dir, 'inspecto-util.jar');
    assert.equal(thin.metadata.component.purl, FIRST('inspecto-util'));
    assert.equal(thin.metadata.component.hashes[0].content, sha('util'));
    assert.deepEqual(thin.metadata.component.properties, [{ name: 'inspecto:bundleFile', value: 'inspecto-util.jar' }, { name: 'inspecto:buildId', value: 'abc1234' }]);
    assert.deepEqual(thin.components.map((c) => c.purl), [a.purl]);
    assert.equal(thin.components[0].licenses[0].license.name, 'Apache-2.0'); // licence carried per module
    assert.equal(read(dir, 'inspecto-connectors.jar').components.length, 2);   // the sidecar's shaded set
}));

test('RED (generation): a jar that is not staged is a failure, not a partial SBOM', () => withFixture(({ dir }) => {
    assert.throws(() => generatePerModule({ bundleDir: dir, componentsByPurl: new Map(), jars: [{ bundleFile: 'inspecto-nope.jar', group: 'g', artifact: 'nope', version: '1', depPurls: [] }] }), /inspecto-nope\.jar is not in the bundle/);
}));

test('RED (generation): a dependency the resolved set does not know is a failure', () => withFixture(({ dir }) => {
    assert.throws(() => generatePerModule({ bundleDir: dir, componentsByPurl: new Map(), jars: [{ bundleFile: 'inspecto.jar', group: 'g', artifact: 'p', version: '1', depPurls: ['pkg:maven/x/y@1'] }] }), /not in the resolved component set/);
}));

test('RED: a jar on modules.list without a per-module SBOM', () => withFixture(({ dir }) => {
    rmSync(join(dir, 'sbom', sbomName('inspecto-util.jar')));
    assert.match(verifyPerModule(dir).join('\n'), /inspecto-util\.jar is on modules\.list but has no per-module SBOM/);
}));

test('RED: a hash mismatch (the jar changed after its SBOM was written)', () => withFixture(({ dir }) => {
    writeFileSync(join(dir, 'inspecto-util.jar'), 'tampered');
    assert.match(verifyPerModule(dir).join('\n'), /inspecto-util\.sbom\.cdx\.json: root SHA-256 .* is not the staged inspecto-util\.jar's/);
}));

test('RED: a third-party component that the combined SBOM does not carry', () => withFixture(({ dir }) => {
    const doc = read(dir, 'inspecto-util.jar');
    const rogue = { ...doc.components[0], purl: 'pkg:maven/org.rogue/r@1', 'bom-ref': 'pkg:maven/org.rogue/r@1' };
    doc.components.push(rogue);
    doc.dependencies[0].dependsOn.push(rogue.purl);
    write(dir, 'inspecto-util.jar', doc);
    assert.match(verifyPerModule(dir).join('\n'), /third-party component pkg:maven\/org\.rogue\/r@1 is not in the combined SBOM/);
}));

test('RED: a component whose hash differs from the combined SBOM', () => withFixture(({ dir }) => {
    const doc = read(dir, 'inspecto-util.jar');
    doc.components[0].hashes[0].content = 'f'.repeat(64);
    write(dir, 'inspecto-util.jar', doc);
    assert.match(verifyPerModule(dir).join('\n'), /hash differs from the combined SBOM/);
}));

test('RED: a per-module SBOM for a jar that is not on modules.list (stale file)', () => withFixture(({ dir }) => {
    writeFileSync(join(dir, 'sbom', sbomName('inspecto-ghost.jar')), '{}');
    assert.match(verifyPerModule(dir).join('\n'), /inspecto-ghost\.sbom\.cdx\.json is a per-module SBOM for a jar that is not on modules\.list/);
}));

test('RED: a dependencies entry that does not match the components', () => withFixture(({ dir }) => {
    const doc = read(dir, 'inspecto.jar');
    doc.dependencies[0].dependsOn.pop();
    write(dir, 'inspecto.jar', doc);
    assert.match(verifyPerModule(dir).join('\n'), /dependencies entry does not match its components/);
}));

test('the demo variant needs no SBOM for the swapped-in demo jar', () => withFixture(({ dir }) => {
    writeFileSync(join(dir, 'inspecto-demo-auth.jar'), 'demo');
    writeFileSync(join(dir, 'modules.list'), readFileSync(join(dir, 'modules.list'), 'utf8') + 'inspecto-demo-auth.jar\n');
    assert.match(verifyPerModule(dir).join('\n'), /inspecto-demo-auth\.jar is on modules\.list but has no per-module SBOM/);
    writeFileSync(join(dir, 'edition.properties'), 'edition=Professional\nvariant=demo\n');
    assert.deepEqual(verifyPerModule(dir), []);
}));

test('RED: modules.list or the combined SBOM is missing', () => withFixture(({ dir }) => {
    rmSync(join(dir, 'sbom', 'inspecto-professional.cdx.json'));
    assert.match(verifyPerModule(dir).join('\n'), /combined SBOM .* is missing/);
    rmSync(join(dir, 'modules.list'));
    assert.match(verifyPerModule(dir).join('\n'), /modules\.list is missing/);
}));
