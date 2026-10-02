// Bundle-shape guard (D-5 step 3 follow-up). Angular budgets (angular.json) cap SIZE; they cannot see WHICH MODULE LANDED IN
// WHICH CHUNK. A library barrel that re-exported widget components once moved MapLibre (1.2 MB) and the Link Analysis widgets
// from their lazy chunks into `main` - found only by a manual stats.json comparison. This guard reads the esbuild metafile that
// `ng build --stats-json` writes (dist/<app>/stats.json: per output chunk, its input MODULE list and its imports), not the
// minified JS - a grep of minified output lies (renamed symbols), module names do not.
//
// Run from inspecto-ui/ AFTER `npm run build -- --stats-json` (and `... la-app --stats-json`): `npm run check:bundle-shape`
// Thresholds live in ONE file, bundle-budget.json beside this tool (re-baseline procedure in its `_doc`).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const kb = (n) => `${(n / 1000).toFixed(1)} kB`;
const inputsOf = (out) => Object.keys(out.inputs ?? {});

/** Measures one app's esbuild-metafile `outputs`: the main chunk, the initial set and the lazy-chunk count. */
export function measure(outputs) {
    const names = Object.keys(outputs);
    const mains = names.filter((n) => /^main[-.]/.test(n) && n.endsWith('.js'));
    if (mains.length !== 1) return { error: `expected exactly one main-*.js output, found ${mains.length} (${mains.join(', ')})` };
    const main = mains[0];
    // Initial set = main + everything main reaches through STATIC imports (those chunks load before the app starts)
    // + polyfills + global styles. Dynamic imports are the lazy boundary and are not followed.
    const closure = new Set([main]);
    const queue = [main];
    while (queue.length) {
        for (const imp of outputs[queue.pop()].imports ?? []) {
            if (imp.kind === 'dynamic-import' || !outputs[imp.path] || closure.has(imp.path)) continue;
            closure.add(imp.path);
            queue.push(imp.path);
        }
    }
    const initial = new Set(closure);
    for (const n of names) if (/^(polyfills|styles)[-.]/.test(n)) initial.add(n);
    return {
        main,
        mainBytes: outputs[main].bytes,
        staticClosure: [...closure],
        initial,
        initialBytes: [...initial].reduce((s, n) => s + outputs[n].bytes, 0),
        lazy: names.filter((n) => n.endsWith('.js') && !initial.has(n)).length,
    };
}

/** Pure check of one app's esbuild-metafile `outputs` against its budget block. Returns a list of violation strings. */
export function checkShape(app, outputs, cfg) {
    const m = measure(outputs);
    if (m.error) return [`${app}: ${m.error}`];
    const { main, mainBytes, initial, initialBytes, lazy } = m;
    const bad = [];

    if (mainBytes > cfg.maxMainBytes)
        bad.push(`${app}: main chunk ${main} is ${kb(mainBytes)} (${mainBytes} B), over the ceiling ${kb(cfg.maxMainBytes)}`);
    if (initialBytes > cfg.maxInitialBytes)
        bad.push(`${app}: initial set (main + static chunks + polyfills + styles = ${initial.size} files) is ${kb(initialBytes)}, over the ceiling ${kb(cfg.maxInitialBytes)}`);
    if (Math.abs(initial.size - cfg.initialFiles.expected) > cfg.initialFiles.tolerance)
        bad.push(`${app}: ${initial.size} initial files, expected ${cfg.initialFiles.expected} +/- ${cfg.initialFiles.tolerance}: ${[...initial].join(', ')}`);
    if (Math.abs(lazy - cfg.lazyChunks.expected) > cfg.lazyChunks.tolerance)
        bad.push(`${app}: ${lazy} lazy JS chunks, expected ${cfg.lazyChunks.expected} +/- ${cfg.lazyChunks.tolerance}`);

    // Marker modules must stay out of main AND out of the statically-reached chunks (both load at startup).
    for (const rule of cfg.forbiddenInInitial) {
        const re = new RegExp(rule.pattern);
        for (const chunk of m.staticClosure)
            for (const mod of inputsOf(outputs[chunk]).filter((i) => re.test(i)))
                bad.push(`${app}: ${mod} landed in ${chunk === main ? 'main' : 'eager chunk'} ${chunk} (${kb(outputs[chunk].bytes)}) - ${rule.why}`);
    }

    // The library must stay a lazy boundary: each route entry is the entryPoint of its own non-initial chunk.
    for (const entry of cfg.requiredLazyEntries) {
        const hit = Object.keys(outputs).find((n) => outputs[n].entryPoint === entry);
        if (!hit) bad.push(`${app}: no chunk has entryPoint ${entry} - the library stopped being a lazy chunk (inlined into another chunk or dropped)`);
        else if (initial.has(hit)) bad.push(`${app}: ${entry} sits in the INITIAL set (${hit}) - it must be lazy`);
    }
    return bad;
}

export function runGuard(root, budget, log = console.log) {
    let failures = 0;
    for (const [app, cfg] of Object.entries(budget.apps)) {
        const file = path.join(root, cfg.stats);
        if (!fs.existsSync(file)) {
            log(`FAIL ${app}: ${cfg.stats} not found - build first with: npm run build -- ${app === 'gamma' ? '' : app + ' '}--stats-json`);
            failures++;
            continue;
        }
        const bad = checkShape(app, JSON.parse(fs.readFileSync(file, 'utf8')).outputs, cfg);
        for (const b of bad) log(`FAIL ${b}`);
        if (!bad.length) log(`ok   ${app}`);
        failures += bad.length;
    }
    return failures;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const here = path.dirname(fileURLToPath(import.meta.url));
    const budget = JSON.parse(fs.readFileSync(path.join(here, 'bundle-budget.json'), 'utf8'));
    const failures = runGuard(path.join(here, '..'), budget);
    if (failures) {
        console.log(`\n${failures} bundle-shape violation(s). If the growth is deliberate, re-baseline tools/bundle-budget.json in the same change (see its _doc).`);
        process.exit(1);
    }
}
