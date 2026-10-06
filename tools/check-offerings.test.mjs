// Fixture test for tools/check-offerings.mjs. Run: node --test tools/check-offerings.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { check, gather, parsePom, parseToon } from './check-offerings.mjs';

const mf = (offeringRole, requires) => ({ offeringRole, requires: requires ? { modules: requires } : undefined });
const manifests = () => ({ processor: mf('base'), connectors: mf('provider'), ops: mf('optional'), la: mf('optional', ['core']), core: mf('optional') });
// Personal ships processor+connectors; Professional adds ops, la, core.
const BUNDLE = { Personal: ['inspecto-processor', 'inspecto-connectors'], Professional: ['inspecto-processor', 'inspecto-connectors', 'inspecto-ops', 'inspecto-la', 'inspecto-core'] };
BUNDLE.Enterprise = BUNDLE.Professional; BUNDLE.Preview = BUNDLE.Professional;
const pom = () => ({ defaults: ['processor', 'connectors'], profiles: { 'edition-professional': ['ops', 'la', 'core'], 'edition-enterprise': ['ops', 'la', 'core'], 'edition-preview': ['ops', 'la', 'core'] } });
const green = () => ({
    offerings: {
        personal: { id: 'personal', tier: 'personal', modules: ['connectors'], contentPacks: ['t1'] },
        professional: { id: 'professional', tier: 'professional', includes: ['personal'], modules: ['ops', 'la', 'core'],
            addons: { linkAnalysis: { status: 'built', modules: ['la', 'core'] }, incidents: { status: 'planned', hostedBy: ['ops'] } } },
    },
    manifests: manifests(), bundle: (e) => BUNDLE[e], pom: pom(), templates: ['t1'],
});
const run = (mutate) => { const g = green(); mutate?.(g); return check(g); };

test('a consistent Offering set is GREEN', () => assert.deepEqual(run(), []));
test('a module id with no manifest is RED', () => assert.match(run((g) => g.offerings.professional.modules.push('ghost')).join('\n'), /module 'ghost' has no manifest/));
test('an Offering listing a module the bundle does not ship is RED', () => {
    assert.match(run((g) => { g.offerings.personal.modules.push('ops'); }).join('\n'), /personal: lists ops but bundle-modules.mjs does not ship it/);
});
test('an Offering missing a module the bundle ships is RED', () => {
    assert.match(run((g) => { g.offerings.professional.modules = ['ops', 'la']; g.offerings.professional.addons.linkAnalysis.modules = ['la']; }).join('\n'), /ships core .* does not list it/);
});
test('a base module is exempt: listing it or omitting it is GREEN', () => {
    assert.deepEqual(run((g) => g.offerings.personal.modules.push('processor')), []);
});
test('closure: an included module whose required module is absent is RED', () => {
    const f = run((g) => { g.manifests.la = mf('optional', ['core', 'ops2']); g.manifests.ops2 = mf('optional'); });
    assert.match(f.join('\n'), /module 'la' requires 'ops2'/);
});
test('a built add-on naming a missing module is RED; a planned add-on hosted by a missing module is RED', () => {
    assert.match(run((g) => g.offerings.professional.addons.linkAnalysis.modules.push('nope')).join('\n'), /module 'nope' has no manifest/);
    assert.match(run((g) => { g.offerings.professional.addons.incidents.hostedBy = ['nope']; }).join('\n'), /module 'nope' has no manifest/);
});
test('a built add-on whose module is not in the Offering is RED', () => {
    const f = run((g) => { g.manifests.extra = mf('optional'); g.offerings.professional.addons.linkAnalysis.modules.push('extra'); });
    assert.match(f.join('\n'), /built add-on 'linkAnalysis' names 'extra'/);
});
test('the pom profile drifting from the bundle is RED', () => {
    assert.match(run((g) => { g.pom.profiles['edition-professional'] = ['ops', 'la']; }).join('\n'), /bundle ships core but pom profile edition-professional does not build it/);
});
test('a missing content pack, an unknown include and a cycle are RED', () => {
    assert.match(run((g) => g.offerings.personal.contentPacks.push('zz')).join('\n'), /content pack 'zz'/);
    assert.match(run((g) => { g.offerings.professional.includes = ['nobody']; }).join('\n'), /includes unknown Offering 'nobody'/);
    assert.match(run((g) => { g.offerings.personal.includes = ['professional']; }).join('\n'), /includes cycle/);
});

test('parseToon reads arrays, nesting, empty keys and rejects a wrong count', () => {
    assert.deepEqual(parseToon('id: x\na[2]: p,q\nb:\n  c:\n    status: built\nempty:\n'), { id: 'x', a: ['p', 'q'], b: { c: { status: 'built' } }, empty: {} });
    assert.throws(() => parseToon('a[3]: p,q'));
});
test('parsePom separates default modules from profile modules, ignoring comments', () => {
    const p = parsePom('<modules><module>inspecto-a</module><!-- <module>inspecto-x</module> --></modules><profiles><profile><id>p1</id><modules><module>inspecto-b</module></modules></profile></profiles>');
    assert.deepEqual(p, { defaults: ['a'], profiles: { p1: ['b'] } });
});
test('the real repository offerings are GREEN', () => assert.deepEqual(check(gather('.')), []));
