// Tests for tools/render-component-shapes.mjs. Run: node --test tools/render-component-shapes.test.mjs
//
// The kinds read from McpTools' source, the coverage check both ways, the page builder on a small shape, the banner
// docs-health reads, and the committed page being current against the committed shape files.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { GENERATED_BANNER } from './docs-health.mjs';
import { MCP_TOOLS, PAGE, componentShapeKinds, coverage, proposeKinds, readShapes, renderPage, renderShape, shapeKinds }
    from './render-component-shapes.mjs';

test('proposeKinds: crud(...) and Map.entry(...) kinds of CHANGE_ROUTES, nothing outside it', () => {
    const src = 'X = Map.of(crud("nope", 1));\nstatic final Map<String, Map<String, String>> CHANGE_ROUTES = Map.ofEntries(\n'
        + '  crud("job", "POST /jobs", "PUT /jobs/{id}", "DELETE /jobs/{id}"),\n'
        + '  Map.entry("pipeline-restore", Map.of("restore", "POST …")));\nY = crud("after", 2);\n';
    assert.deepEqual(proposeKinds(src), ['job', 'pipeline-restore']);
    assert.ok(proposeKinds(readFileSync(MCP_TOOLS, 'utf8')).includes('entity-list'), 'the real table parses');
});

test('componentShapeKinds / shapeKinds: COMPONENT_SHAPE_KINDS read from source, joined to the propose_change kinds', () => {
    const src = 'static final Set<String> COMPONENT_SHAPE_KINDS = Set.of("dataset", "risk-score");\nZ = Set.of("nope");\n'
        + 'CHANGE_ROUTES = Map.ofEntries(\n  crud("job", "POST /jobs"));\n';
    assert.deepEqual(componentShapeKinds(src), ['dataset', 'risk-score']);
    assert.deepEqual(shapeKinds(src), ['dataset', 'job', 'risk-score']);
    const real = readFileSync(MCP_TOOLS, 'utf8');
    assert.ok(componentShapeKinds(real).includes('reconciliation'), 'the real list parses');
});

test('coverage: a kind with no file, and a file with no kind, are both problems', () => {
    assert.deepEqual(coverage(['a', 'b'], { a: {}, c: {} }), [
        "shape kind 'b' has no docs/api/component-shapes/b.json",
        'docs/api/component-shapes/c.json names no propose_change kind and no COMPONENT_SHAPE_KINDS kind']);
    assert.deepEqual(coverage(['a'], { a: {} }), []);
});

const DOC = { thing: { kind: 'thing', operations: { create: { route: 'POST /things', content: 'content' },
    delete: { route: 'DELETE /things/{id}', content: null } },
shapes: { content: { source: 'Thing.KEYS', unknownKeys: 'refused', extensionPrefix: 'x-',
    fields: [{ path: 'name', type: 'string', required: true, requiredBy: 'spec', description: 'The | name' },
        { path: 'mode', type: 'string', required: true, requiredBy: 'parser', default: 'fast', values: ['fast', 'slow'] },
        { path: 'owner', type: 'any', required: false, setBy: 'server' },
        { path: 'tiles', type: 'array', required: false, specRequired: true }],
    example: { name: 'n', mode: 'fast' }, notes: ['A note.'] } } } };

test('renderPage: banner, index row, the fields table and the example', () => {
    const md = renderPage(DOC);
    assert.ok(GENERATED_BANNER.test(md.split('\n').slice(0, 20).join('\n')), 'docs-health reads the banner');
    assert.ok(md.includes('| [`thing`](#thing) | `create` POST /things · `delete` DELETE /things/{id} |'));
    assert.ok(md.includes('| `name` | string | yes |  |  | The \\| name |'), 'spec-required, pipe escaped');
    assert.ok(md.includes('| `mode` | string | yes¹ | `fast` | `fast` `slow` |  |'), 'parser-required is marked');
    assert.ok(md.includes('| `owner` | any | set by the server |'), 'a server-stamped key says so');
    assert.ok(md.includes('| `tiles` | array | no² |'), 'a spec-required key the save does not enforce says so');
    assert.ok(md.includes('is **refused** (422)') && md.includes('- A note.') && md.includes('"mode": "fast"'));
    assert.ok(md.includes('- `delete` → `DELETE /things/{id}` — no content'));
    assert.ok(!md.includes('Other component kinds'), 'no second index without a non-propose kind');
});

test('renderPage: a kind propose_change does not offer gets its own index and says so', () => {
    const md = renderPage({ ...DOC, gadget: { ...DOC.thing, kind: 'gadget', proposeChange: false } });
    const other = md.indexOf('**Other component kinds (not offered by propose_change)**');
    assert.ok(other > 0 && md.indexOf('| [`gadget`](#gadget)') > other && md.indexOf('| [`thing`](#thing)') < other);
    assert.ok(md.includes('## gadget\n\nNot a `propose_change` kind: written through the component routes.'));
});

test('the committed page is current against the committed shape files', () => {
    assert.equal(readFileSync(PAGE, 'utf8').replace(/\r\n/g, '\n'), renderPage(readShapes('.')));
});

test('renderShape: a refused kind whose parser refuses an x- annotation too says so', () => {
    const shape = { ...DOC.thing.shapes.content };
    delete shape.extensionPrefix;
    assert.ok(renderShape('content', shape).join('\n').includes('is **refused** (422), an `x-…` annotation too.'));
});
