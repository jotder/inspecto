// Tests for tools/compose-space.mjs - PRODUCT-ASSEMBLY-1 PA-5 (D-PA14). Run: node --test tools/compose-space.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { composeSpace, readTemplateMeta } from './compose-space.mjs';
import { assemble } from './assemble.mjs';
import { gatherModel } from './products.mjs';
import { gather } from './check-offerings.mjs';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');
const committed = join(repo, 'spaces', '_templates');

function walk(dir, rel = '') {
    return readdirSync(join(dir, rel), { withFileTypes: true }).flatMap((d) => {
        const r = rel ? `${rel}/${d.name}` : d.name;
        return d.isDirectory() ? walk(dir, r) : [r];
    });
}

/** A scratch templates folder holding copies of the named committed templates. */
function stage(names) {
    const dir = mkdtempSync(join(tmpdir(), 'compose-space-'));
    for (const n of names) cpSync(join(committed, n), join(dir, n), { recursive: true });
    return dir;
}

test('every committed template.toon reads with the item counts it declares (quoted items hold commas)', () => {
    for (const t of readdirSync(committed)) {
        const f = join(committed, t, 'template.toon');
        if (!existsSync(f)) continue;
        const m = readTemplateMeta(readFileSync(f, 'utf8'));
        assert.ok(m.name, `${t}: name`);
        assert.ok(Array.isArray(m.contents), `${t}: contents is a list`);
    }
    assert.equal(readTemplateMeta('name: Outer\nextra:\n  name: Inner\n').name, 'Outer', 'a nested key never overwrites a top-level one');
});

test('Inspecto Telco x Standard: the plan composes RA and FM into one Space, every part file side by side', () => {
    const plan = assemble(gatherModel(repo), gather(repo), { product: 'telco', edition: 'standard' });
    assert.deepEqual(plan.space.parts.map((p) => p.template), ['telco-ra', 'telco-fraud', 'telco-bypass']);
    const dir = stage(plan.templates);
    try {
        const r = composeSpace(plan.space, dir);
        const out = join(dir, 'telco');
        const files = new Set(walk(out));
        for (const t of plan.space.parts.map((p) => p.template)) {
            for (const f of walk(join(dir, t))) {
                if (f === 'template.toon' || f === 'config/examples/sla-escalation-examples.md') continue;
                assert.ok(files.has(f), `${t}/${f} is in the composed Space`);
                assert.deepEqual(readFileSync(join(out, f)), readFileSync(join(dir, t, f)), `${t}/${f} is copied byte for byte`);
            }
        }
        assert.equal(r.files, files.size);
        const meta = readTemplateMeta(readFileSync(join(out, 'template.toon'), 'utf8'));
        assert.equal(meta.name, 'Inspecto Telco');
        assert.equal(meta.minEdition, 'standard');
        const partContents = plan.space.parts.reduce((n, p) => n + readTemplateMeta(readFileSync(join(dir, p.template, 'template.toon'), 'utf8')).contents.length, 0);
        assert.equal(meta.contents.length, partContents, 'each part\'s contents, under its App\'s name');
        assert.ok(meta.contents.every((c) => /^(Revenue Assurance|Fraud Management): /.test(c)));
        const sla = readFileSync(join(out, 'config/examples/sla-escalation-examples.md'), 'utf8');
        for (const t of ['telco-ra', 'telco-fraud', 'telco-bypass']) assert.match(sla, new RegExp(`^## .* \\(${t}\\)$`, 'm'));
        assert.equal((sla.match(/^# /gm) ?? []).length, 1, 'one first-level heading: the parts\' own are dropped');
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test('refuses a file two parts both ship, a missing part and an existing target', () => {
    const dir = mkdtempSync(join(tmpdir(), 'compose-space-'));
    try {
        for (const t of ['a', 'b']) {
            mkdirSync(join(dir, t, 'config'), { recursive: true });
            writeFileSync(join(dir, t, 'template.toon'), `name: ${t}\nminEdition: basic\ncontents[1]: "x, y"\n`);
            writeFileSync(join(dir, t, 'config', 'shared.toon'), `k: ${t}\n`);
        }
        const space = (parts) => ({ id: 'ab', title: 'AB', parts: parts.map((t) => ({ app: t, appTitle: t.toUpperCase(), template: t })) });
        assert.throws(() => composeSpace(space(['a', 'b']), dir), /templates 'a' and 'b' both ship config\/shared\.toon/);
        rmSync(join(dir, 'ab'), { recursive: true, force: true });
        assert.throws(() => composeSpace(space(['a', 'ghost']), dir), /part 'ghost' .* is not a staged template/);
        rmSync(join(dir, 'ab'), { recursive: true, force: true });
        mkdirSync(join(dir, 'ab'));
        assert.throws(() => composeSpace(space(['a']), dir), /already exists/);
        assert.throws(() => composeSpace({ id: 'none', title: 'N', parts: [] }, dir), /has no parts/);
        // Two paths that differ only in case are one file on Windows: refused, never silently overwritten.
        rmSync(join(dir, 'ab'), { recursive: true, force: true });
        rmSync(join(dir, 'b', 'config', 'shared.toon'));
        writeFileSync(join(dir, 'b', 'config', 'Shared.toon'), 'k: b\n');
        assert.throws(() => composeSpace(space(['a', 'b']), dir), /templates 'a' and 'b' both ship config\/Shared\.toon/);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});
