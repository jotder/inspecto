// LA-FULL-CYCLE-TEST-1 presentability: a showcase saved view's title and description are what the Home page's cards and the
// view pickers show a bank's executives, so they read as plain business sentences. A demo-script number ("DEMO 2c",
// "GEO scene 1"), a ticket id ("LA-14b") or a masked token ("masked:6021…") reached the Home page before; this keeps them
// out of every saved view (Link Analysis and Geo) of every `spaces/_templates/la-*` showcase and of `spaces/demo`, and keeps
// the la-showcase views that `spaces/demo` also carries byte-equal to the template's.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const KINDS = ['link-analysis-views', 'geo-map-views'];
const BANNED = [
    [/\bDEMO \d/i, 'a demo-script number'],
    [/\bGEO scene \d/i, 'a demo-script number'],
    // any other script marker that opens a text: "AUDIT 2 - …", "CLAIMS 1b — …", "Sample — …"
    [/^(title|description): "[A-Z]{2,}(?: [A-Za-z]+)? \d+[a-z]? [-–—]/, 'a demo-script number'],
    [/^(title|description): "Sample [-–—]/, 'a demo-script marker'],
    [/\bLA-\d+/, 'a ticket id'],
    [/masked:/i, 'a masked token'],
];

const registry = (space) => path.join(space, 'config', 'registry');
const templates = fs
    .readdirSync(path.join(repo, 'spaces', '_templates'))
    .filter((d) => d.startsWith('la-'))
    .map((d) => path.join(repo, 'spaces', '_templates', d));
const SPACES = [...templates, path.join(repo, 'spaces', 'demo')];

/** Every saved view file of a Space, as [kind, file name, absolute path]. */
const views = (space) =>
    KINDS.flatMap((k) => {
        const dir = path.join(registry(space), k);
        return fs.existsSync(dir)
            ? fs.readdirSync(dir).filter((f) => f.endsWith('.toon')).map((f) => [k, f, path.join(dir, f)])
            : [];
    });

/** The top-level `title:` and `description:` lines of a view file. */
const texts = (file) =>
    fs
        .readFileSync(file, 'utf8')
        .split(/\r?\n/)
        .filter((l) => /^(title|description): /.test(l));

test('the guard reads the showcases', () => {
    assert.ok(templates.length >= 17, templates.join(', '));
    assert.ok(SPACES.flatMap(views).length >= 60);
});

for (const space of SPACES)
    test(`${path.relative(repo, space)}: saved-view texts carry no demo number, ticket id or masked token`, () => {
        const found = [];
        for (const [kind, name, file] of views(space))
            for (const line of texts(file))
                for (const [re, what] of BANNED) if (re.test(line)) found.push(`${kind}/${name} has ${what}: ${line}`);
        assert.deepEqual(found, []);
    });

test('spaces/demo keeps its la-showcase saved views equal to the template', () => {
    const tpl = path.join(repo, 'spaces', '_templates', 'la-showcase');
    const demo = path.join(repo, 'spaces', 'demo');
    const shared = views(tpl).filter(([k, f]) => fs.existsSync(path.join(registry(demo), k, f)));
    assert.ok(shared.length >= 3, 'the demo Space carries the mule views');
    for (const [k, f, file] of shared)
        assert.equal(fs.readFileSync(path.join(registry(demo), k, f), 'utf8'), fs.readFileSync(file, 'utf8'), `${k}/${f}`);
});
