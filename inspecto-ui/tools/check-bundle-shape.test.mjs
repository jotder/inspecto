// Falsifies tools/check-bundle-shape.mjs on a synthetic esbuild metafile: a healthy shape passes, and each failure class
// (heavy module in main, in an eagerly-imported chunk, oversized main, a library route no longer lazy, chunk-count drift,
// missing stats file) is flagged by name. Run from inspecto-ui/: node --test tools/check-bundle-shape.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { checkShape, runGuard } from './check-bundle-shape.mjs';

const ROUTES = 'projects/link-analysis/src/link-analysis/link-analysis.routes.ts';
const GEO_ROUTES = 'projects/link-analysis/src/geo-map/geo-map.routes.ts';
const cfg = {
    maxMainBytes: 1000,
    maxInitialBytes: 2000,
    initialFiles: { expected: 4, tolerance: 0 },
    lazyChunks: { expected: 2, tolerance: 0 },
    forbiddenInInitial: [
        { pattern: 'node_modules/maplibre-gl/', why: 'MapLibre is lazy' },
        { pattern: '^projects/link-analysis/src/(?!la-host/|public-api\\.ts$|[a-z-]+/[a-z-]+\\.viz\\.ts$)', why: 'library widgets are lazy' },
    ],
    requiredLazyEntries: [ROUTES, GEO_ROUTES],
};
const healthy = () => ({
    'main-A.js': {
        bytes: 900,
        inputs: { 'src/main.ts': {}, 'projects/link-analysis/src/public-api.ts': {}, 'projects/link-analysis/src/geo-map/geo-map.viz.ts': {} },
        imports: [{ kind: 'import-statement', path: 'chunk-shared.js' }, { kind: 'dynamic-import', path: 'chunk-routes.js' }],
        entryPoint: 'src/main.ts',
    },
    'chunk-shared.js': { bytes: 300, inputs: { 'node_modules/@angular/core/x.mjs': {} }, imports: [] },
    'polyfills-P.js': { bytes: 50, inputs: {}, imports: [] },
    'styles-S.css': { bytes: 100, inputs: {}, imports: [] },
    'chunk-routes.js': { bytes: 500, inputs: { [ROUTES]: {} }, imports: [], entryPoint: ROUTES },
    'chunk-geo.js': { bytes: 900, inputs: { [GEO_ROUTES]: {}, 'node_modules/maplibre-gl/dist/maplibre-gl.js': {} }, imports: [], entryPoint: GEO_ROUTES },
});

test('healthy shape passes', () => assert.deepEqual(checkShape('gamma', healthy(), cfg), []));

test('MapLibre in main is flagged by module and chunk', () => {
    const o = healthy();
    o['main-A.js'].inputs['node_modules/maplibre-gl/dist/maplibre-gl.js'] = {};
    const bad = checkShape('gamma', o, cfg);
    assert.ok(bad.some((b) => b.includes('maplibre-gl.js landed in main main-A.js')), bad.join('\n'));
});

test('library widget in an eagerly imported chunk is flagged', () => {
    const o = healthy();
    o['chunk-shared.js'].inputs['projects/link-analysis/src/link-analysis/link-view-widget.component.ts'] = {};
    const bad = checkShape('gamma', o, cfg);
    assert.ok(bad.some((b) => b.includes('link-view-widget.component.ts landed in eager chunk chunk-shared.js')), bad.join('\n'));
});

test('the host seam, the barrel and the viz registrations are allowed in main', () => {
    const o = healthy();
    o['main-A.js'].inputs['projects/link-analysis/src/la-host/la-host.ts'] = {};
    o['main-A.js'].inputs['projects/link-analysis/src/link-analysis/link-analysis.viz.ts'] = {};
    assert.deepEqual(checkShape('gamma', o, cfg), []);
});

test('an oversized main is flagged with sizes', () => {
    const o = healthy();
    o['main-A.js'].bytes = 1100;
    const bad = checkShape('gamma', o, cfg);
    assert.ok(bad.some((b) => /main chunk main-A\.js is 1\.1 kB .* over the ceiling 1\.0 kB/.test(b)), bad.join('\n'));
});

test('a library route that is no longer its own lazy chunk is flagged', () => {
    const o = healthy();
    delete o['chunk-routes.js'];
    assert.ok(checkShape('gamma', o, cfg).some((b) => b.includes(`no chunk has entryPoint ${ROUTES}`)));
    const o2 = healthy();
    o2['main-A.js'].imports.push({ kind: 'import-statement', path: 'chunk-routes.js' });
    assert.ok(checkShape('gamma', o2, cfg).some((b) => b.includes('sits in the INITIAL set')));
});

test('initial-file and lazy-chunk count drift beyond tolerance is flagged', () => {
    const o = healthy();
    o['chunk-extra.js'] = { bytes: 10, inputs: {}, imports: [] };
    assert.ok(checkShape('gamma', o, cfg).some((b) => b.includes('3 lazy') || b.includes('lazy JS chunks')));
    const o2 = healthy();
    o2['main-A.js'].imports.push({ kind: 'import-statement', path: 'chunk-geo.js' });
    assert.ok(checkShape('gamma', o2, cfg).some((b) => b.includes('initial files')));
});

test('a missing stats.json fails with the build command, not a crash', () => {
    const lines = [];
    const failures = runGuard(os.tmpdir(), { apps: { 'la-app': { stats: 'no-such-dir/stats.json' } } }, (l) => lines.push(l));
    assert.equal(failures, 1);
    assert.match(lines[0], /build first with: npm run build -- la-app --stats-json/);
});

test('the committed budget file parses and every app block is complete', () => {
    const budget = JSON.parse(fs.readFileSync(path.join(import.meta.dirname, 'bundle-budget.json'), 'utf8'));
    for (const [app, c] of Object.entries(budget.apps))
        for (const k of ['stats', 'maxMainBytes', 'maxInitialBytes', 'initialFiles', 'lazyChunks', 'forbiddenInInitial', 'requiredLazyEntries'])
            assert.ok(c[k] !== undefined, `${app} lacks ${k}`);
});
