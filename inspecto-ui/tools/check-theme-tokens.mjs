// Theme-token guard (2026-10-22). The gamma theme (src/@gamma/styles/themes.scss) once built with NO filled-button fill:
// Material's M2 token functions read a `_mat-system` map the theme merge drops, so `--mat-button-filled-container-color`
// was never declared and every `mat-flat-button color="primary"` rendered transparent - in both apps, in both schemes - while
// the build stayed green. This reads the BUILT global stylesheet of each app and fails when any user theme, in either
// scheme, leaves a palette's filled button without a concrete container colour or label colour.
//
// Run from inspecto-ui/ AFTER `npm run build` and `npm run build -- la-app`: `npm run check:theme-tokens`
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const APPS = ['gamma', 'la-app'];
export const SCHEMES = ['light', 'dark'];
export const VARIANTS = ['primary', 'accent', 'warn'];
export const TOKENS = ['--mat-button-filled-container-color', '--mat-button-filled-label-text-color'];

/** A concrete colour literal - what Material emits for a resolved palette value. An empty slot (`color-mix(in srgb, , x)`) is not. */
const COLOUR = /^(#[0-9a-f]{3,8}|(rgb|hsl)a?\(\s*[^\s,()]+(\s*[,\s/]\s*[^\s,()]+){2,3}\s*\))$/i;

/** Splits a selector list on its top-level commas (a `:not(a, b)` stays whole). */
function selectors(list) {
    const out = [];
    let depth = 0, cur = '';
    for (const ch of list) {
        if (ch === '(') depth++;
        if (ch === ')') depth--;
        if (ch === ',' && depth === 0) {
            out.push(cur.trim());
            cur = '';
        } else cur += ch;
    }
    out.push(cur.trim());
    return out;
}

/** Last declared value of each wanted token per exact selector, in source order (a later empty re-declaration wins, as in the browser). */
export function tokenValues(css, wanted) {
    const values = new Map();
    for (const [, sel, body] of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
        const hits = selectors(sel).filter((s) => wanted.has(s));
        if (!hits.length) continue;
        for (const decl of body.split(';')) {
            const i = decl.indexOf(':');
            const prop = decl.slice(0, i).trim();
            if (i < 0 || !TOKENS.includes(prop)) continue;
            const value = decl.slice(i + 1).replace(/!important/, '').trim();
            for (const s of hits) values.set(`${s}|${prop}`, value);
        }
    }
    return values;
}

/** Pure check of one built global stylesheet. Returns a list of violation strings. */
export function checkCss(app, css) {
    const themes = [...new Set([...css.matchAll(/\.(theme-[a-z0-9-]+)[ .](?:light|dark)\b/g)].map((m) => m[1]))];
    if (!themes.length) return [`${app}: no .theme-* light/dark block found - is this the global styles-*.css?`];
    const wanted = new Map();
    for (const theme of themes)
        for (const scheme of SCHEMES)
            for (const variant of VARIANTS)
                // Both forms the theme emits: the scheme class below the theme class, and on the same element (body).
                for (const s of [`.${theme} .${scheme} .mat-mdc-unelevated-button.mat-${variant}`, `.${theme}.${scheme} .mat-mdc-unelevated-button.mat-${variant}`])
                    wanted.set(s, { theme, scheme, variant });
    const values = tokenValues(css, new Set(wanted.keys()));
    const bad = [];
    for (const [sel, w] of wanted)
        for (const token of TOKENS) {
            const v = values.get(`${sel}|${token}`);
            if (v === undefined) bad.push(`${app}: ${w.theme} ${w.scheme} mat-flat-button color="${w.variant}" - ${token} is never declared for \`${sel}\``);
            else if (!COLOUR.test(v)) bad.push(`${app}: ${w.theme} ${w.scheme} mat-flat-button color="${w.variant}" - ${token} is \`${v}\`, not a colour (\`${sel}\`)`);
        }
    return bad;
}

export function runGuard(root, log = console.log) {
    let failures = 0;
    for (const app of APPS) {
        const dir = path.join(root, 'dist', app, 'browser');
        const file = fs.existsSync(dir) ? fs.readdirSync(dir).find((f) => /^styles-.*\.css$/.test(f)) : undefined;
        if (!file) {
            log(`FAIL ${app}: dist/${app}/browser/styles-*.css not found - build first with: npm run build${app === 'gamma' ? '' : ' -- ' + app}`);
            failures++;
            continue;
        }
        const bad = checkCss(app, fs.readFileSync(path.join(dir, file), 'utf8'));
        for (const b of bad) log(`FAIL ${b}`);
        if (!bad.length) log(`ok   ${app} (${file})`);
        failures += bad.length;
    }
    return failures;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const failures = runGuard(path.join(path.dirname(fileURLToPath(import.meta.url)), '..'));
    if (failures) {
        console.log(`\n${failures} theme-token violation(s): see src/@gamma/styles/themes.scss (the theme merge must keep _mat-system).`);
        process.exit(1);
    }
}
