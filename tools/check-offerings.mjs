#!/usr/bin/env node
// check-offerings — holds offerings/<id>.toon (module-architecture plan §2.3, §6 P6a) against the module manifests and the
// edition build tables. NOT wired into ci.yml yet (follow-up); check-module-architecture.mjs prints a one-line status.
//
//   node tools/check-offerings.mjs [--root .]      exit 1 on any finding
//
// FORMAT  offerings/<id>.toon — id, title, tier, includes[N], modules[N], contentPacks[N], addons{name{status, modules[N]|hostedBy[N]}},
//         defaultSpaceSettings (empty placeholder). `includes` is resolved transitively; modules and add-on modules UNION, an
//         add-on's status is the including Offering's. No '#' lines (TOON has no comments).
//
// THE ALWAYS-PRESENT RULE (derived from how bundle-modules.mjs works): bundleModules() has a row per shipped jar, and
// `inspecto-processor` — the shaded host — is the only row whose manifest says offeringRole `base`; every other staged jar
// (including `connectors`, which ships in every edition) is optional/provider. Foundation, contract and platform modules are
// shaded INTO the processor jar and never staged, so they have no row. Therefore the rule is on offeringRole, not buildRole:
//   * a module whose manifest has offeringRole base or internal is ALWAYS PRESENT — never required in `modules`, and
//     ignored on both sides of the comparison with the bundle;
//   * every other module (optional, provider) must be listed, directly or via includes, exactly when the bundle ships it.
// (A platform/foundation module that is offeringRole optional — la-core, la-graph — is listed; its role is not "base".)
//
// CHECKS  1 every module id named has a manifest   2 resolved module set == bundleModules(edition) == the edition pom profile
//         (profile modules = bundle set minus the default-reactor modules)   3 requires.modules closure of the resolved set
//         4 a `built` add-on names existing modules that are in the resolved set; a `planned` one's hostedBy modules exist
//         5 contentPacks exist under spaces/_templates   6 includes resolve, no cycle, id equals file name.

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bundleModules } from './bundle-modules.mjs';

/** Parse the TOON subset used by manifests and offerings: `k: v`, `k[N]: a,b`, `k:` + indented children. */
export function parseToon(text) {
    const lines = text.split(/\r?\n/).filter((l) => l.trim() && l.trim() !== '---');
    const root = {};
    const stack = [{ indent: -1, obj: root }];
    for (const raw of lines) {
        const indent = raw.length - raw.trimStart().length;
        const m = /^([A-Za-z_][\w.]*)(?:\[(\d+)\])?:\s*(.*)$/.exec(raw.trim());
        if (!m) throw new Error(`unparseable line: ${raw}`);
        while (stack.length > 1 && indent <= stack.at(-1).indent) stack.pop();
        const parent = stack.at(-1).obj;
        const [, key, n, val] = m;
        if (n !== undefined) {
            const items = val === '' ? [] : val.split(',').map((s) => s.trim());
            if (items.length !== Number(n)) throw new Error(`${key}[${n}] has ${items.length} items`);
            parent[key] = items;
        } else if (val === '') { const o = {}; parent[key] = o; stack.push({ indent, obj: o }); }
        else parent[key] = val;
    }
    return root;
}

/** artifactId or module directory -> manifest id (`inspecto-processor` -> processor, `inspecto-ops` -> ops). */
export const idOf = (artifactId) => artifactId.replace(/^inspecto-/, '');

const EDITION_OF = { personal: 'Personal', professional: 'Professional', enterprise: 'Enterprise', preview: 'Preview' };
const PROFILE_OF = { professional: 'edition-professional', enterprise: 'edition-enterprise', preview: 'edition-preview' };

/** pom.xml -> { defaults: [ids], profiles: { profileId: [ids] } }. */
export function parsePom(xml) {
    const x = xml.replace(/<!--[\s\S]*?-->/g, '');
    const ids = (s) => [...s.matchAll(/<module>([^<]+)<\/module>/g)].map((m) => idOf(m[1].trim().split('/').pop()));
    const at = x.indexOf('<profiles>');
    const profiles = {};
    for (const p of x.slice(at).matchAll(/<profile>([\s\S]*?)<\/profile>/g)) {
        const id = /<id>([^<]+)<\/id>/.exec(p[1])?.[1];
        if (id) profiles[id] = ids(p[1]);
    }
    return { defaults: ids(x.slice(0, at)), profiles };
}

const setDiff = (a, b) => [[...a].filter((x) => !b.has(x)), [...b].filter((x) => !a.has(x))];

/**
 * Pure check. in: { offerings: {id: parsed}, manifests: {id: {offeringRole, requires?}}, bundle: (edition) => [artifactId],
 * pom: {defaults, profiles}, templates: [ids] }. Returns a list of findings (empty = green).
 */
export function check({ offerings, manifests, bundle, pom, templates }) {
    const out = [];
    const exempt = (id) => ['base', 'internal'].includes(manifests[id]?.offeringRole);
    const resolve = (id, seen = []) => {
        const o = offerings[id];
        const empty = { modules: new Set(), addons: {} };
        if (!o) { out.push(`${seen.at(-1) ?? id}: includes unknown Offering '${id}'`); return empty; }
        if (seen.includes(id)) { out.push(`${id}: includes cycle ${[...seen, id].join(' -> ')}`); return empty; }
        const r = { modules: new Set(), addons: {} };
        for (const inc of o.includes ?? []) {
            const s = resolve(inc, [...seen, id]);
            s.modules.forEach((m) => r.modules.add(m));
            for (const [k, v] of Object.entries(s.addons)) r.addons[k] = { ...v, modules: [...(v.modules ?? [])] };
        }
        (o.modules ?? []).forEach((m) => r.modules.add(m));
        for (const [k, v] of Object.entries(o.addons ?? {})) {
            const prev = r.addons[k] ?? {};
            r.addons[k] = { ...prev, ...v, modules: [...new Set([...(prev.modules ?? []), ...(v.modules ?? [])])] };
        }
        return r;
    };
    for (const [file, o] of Object.entries(offerings)) if (o.id !== file) out.push(`${file}.toon: id '${o.id}' differs from the file name`);
    for (const id of Object.keys(offerings)) {
        const o = offerings[id];
        const r = resolve(id);
        const say = (m) => out.push(`${id}: ${m}`);
        const named = [...(o.modules ?? []), ...Object.values(o.addons ?? {}).flatMap((a) => [...(a.modules ?? []), ...(a.hostedBy ?? [])])];
        for (const m of new Set(named)) if (!manifests[m]) say(`module '${m}' has no manifest`);
        const edition = EDITION_OF[o.tier];
        if (edition) {
            const shipped = new Set(bundle(edition).map(idOf).filter((m) => !exempt(m)));
            const listed = new Set([...r.modules].filter((m) => !exempt(m)));
            const [onlyOffering, onlyBundle] = setDiff(listed, shipped);
            if (onlyOffering.length) say(`lists ${onlyOffering.join(',')} but bundle-modules.mjs does not ship it for ${edition}`);
            if (onlyBundle.length) say(`bundle-modules.mjs ships ${onlyBundle.join(',')} for ${edition} but the Offering does not list it`);
            const profile = PROFILE_OF[o.tier];
            const defaults = new Set(pom.defaults);
            const gated = new Set([...shipped].filter((m) => !defaults.has(m)));
            if (profile && !pom.profiles[profile]) say(`pom.xml has no profile ${profile}`);
            else {
                const have = new Set(profile ? pom.profiles[profile] : []);
                const [notInPom, notInBundle] = setDiff(gated, have);
                if (notInPom.length) say(`bundle ships ${notInPom.join(',')} but pom profile ${profile ?? '(default)'} does not build it`);
                if (notInBundle.length) say(`pom profile ${profile ?? '(default)'} builds ${notInBundle.join(',')} but bundle-modules.mjs does not ship it`);
            }
        } else say(`unknown tier '${o.tier}'`);
        for (const m of r.modules) for (const req of manifests[m]?.requires?.modules ?? [])
            if (!r.modules.has(req) && !exempt(req)) say(`module '${m}' requires '${req}', which the Offering does not include`);
        for (const [name, a] of Object.entries(r.addons)) {
            if (!['built', 'planned'].includes(a.status)) { say(`add-on '${name}' status must be built or planned`); continue; }
            if (a.status === 'built') {
                if (!(a.modules ?? []).length) say(`add-on '${name}' is built but names no module`);
                for (const m of a.modules ?? []) if (manifests[m] && !r.modules.has(m)) say(`built add-on '${name}' names '${m}', which the Offering does not include`);
            }
        }
        for (const t of o.contentPacks ?? []) if (!templates.includes(t)) say(`content pack '${t}' not found under spaces/_templates`);
    }
    return out;
}

/** Everything check() needs, read from a repo root. */
export function gather(root) {
    const dir = join(root, 'offerings');
    const offerings = {};
    for (const f of existsSync(dir) ? readdirSync(dir).filter((n) => n.endsWith('.toon')) : []) offerings[f.slice(0, -5)] = parseToon(readFileSync(join(dir, f), 'utf8'));
    const manifests = {};
    const files = execFileSync('git', ['ls-files', '*META-INF/inspecto/module.toon'], { cwd: root, encoding: 'utf8' }).split('\n').filter(Boolean);
    for (const f of files) { const m = parseToon(readFileSync(join(root, f), 'utf8')); manifests[m.id] = m; }
    const tdir = join(root, 'spaces', '_templates');
    return {
        offerings, manifests,
        bundle: (e) => bundleModules(e).map((m) => m.artifactId),
        pom: parsePom(readFileSync(join(root, 'pom.xml'), 'utf8')),
        templates: existsSync(tdir) ? readdirSync(tdir) : [],
    };
}

/** One-line status for check-module-architecture.mjs. */
export function status(root) {
    try {
        const g = gather(root);
        return `${Object.keys(g.offerings).length} Offerings, ${check(g).length} finding(s)`;
    } catch (e) { return `unreadable (${e.message})`; }
}

function main() {
    const a = process.argv.slice(2);
    const i = a.indexOf('--root');
    const g = gather(i >= 0 ? a[i + 1] : '.');
    const findings = check(g);
    for (const f of findings) console.error(`check-offerings: ${f}`);
    console.log(`check-offerings: ${Object.keys(g.offerings).length} Offerings, ${findings.length} finding(s)`);
    if (findings.length) process.exit(1);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
