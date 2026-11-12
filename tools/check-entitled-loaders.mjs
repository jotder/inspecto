#!/usr/bin/env node
/**
 * Entitled-loader guard (ENTITLEMENT-SPI-NARROWING-1, 2026-11-12).
 *
 * WHY THIS EXISTS. A signed entitlement (PRODUCT-ASSEMBLY-1 PA-7a) leaves modules out. Their routes, features and Job
 * types were closed, but every other extension point found its providers with a raw `ServiceLoader.load`, so a Decision
 * Rule could still fire the `invoke-api` Consequence of an Action Requests module the file left out. Every loader now goes
 * through `com.gamma.module.EntitledServices` (or, fail-soft, asks `EntitledServices.entitled` per provider). Nothing kept
 * the next new loader from bypassing it: this guard does.
 *
 * WHAT IT CHECKS, over every tracked `src/main/java` file:
 *   1. No code line calls `ServiceLoader.load(` / `ServiceLoader.loadInstalled(`, except in the files listed in OPEN below,
 *      each with the reason it stays open and the exact number of sites it holds.
 *   2. Each OPEN entry still holds exactly that many sites (a stale entry is a silent exemption).
 *   3. Each OPEN entry marked `filtered` (a fail-soft loader keeping its own iterator) calls `EntitledServices.entitled`.
 *   4. `OptionalSpi.allInstalled` (discovery WITHOUT the entitlement filter) is called only by the files listed in
 *      UNFILTERED_CALLERS, with the exact count.
 *
 * Usage: node tools/check-entitled-loaders.mjs [--root <dir>]   (--root is for tools/check-entitled-loaders.test.mjs).
 */

import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** The loaders that stay open, or filter per provider themselves. Path → { sites, filtered?, why }. */
export const OPEN = {
    'platform/inspecto-util/src/main/java/com/gamma/module/EntitledServices.java': {
        sites: 2, why: 'the helper itself',
    },
    'spi/inspecto-auth-spi/src/main/java/com/gamma/spi/OptionalSpi.java': {
        sites: 1, filtered: true,
        why: 'fail-soft: keeps its own iterator to skip an unloadable jar, and asks EntitledServices.entitled per provider',
    },
    'platform/inspecto-engine/src/main/java/com/gamma/decision/Consequences.java': {
        sites: 1, filtered: true,
        why: 'fail-soft: keeps its own iterator to skip a broken provider, and asks EntitledServices.entitled at load and at each find',
    },
    'spi/inspecto-auth-spi/src/main/java/com/gamma/spi/auth/SpiSlot.java': {
        sites: 1,
        why: 'the fail-closed safety slots (Authenticator, AccessDecider, the durable Investigation store): leaving the module out '
            + 'would serve every route unauthenticated or undecided; an entitlement never widens access. Non-safety slots go '
            + 'through OptionalSpi, which is narrowed',
    },
    'spi/inspecto-audit-spi/src/main/java/com/gamma/audit/EventSink.java': {
        sites: 1, why: 'the audit trail: an entitlement may never stop actions being recorded',
    },
    'platform/inspecto-engine/src/main/java/com/gamma/job/ReportRowMasking.java': {
        sites: 1, why: 'masking of exported rows: without the provider rows would leave unmasked; an entitlement never reveals more',
    },
    'platform/inspecto-entity-store/src/main/java/com/gamma/entitystore/MaskingDefault.java': {
        sites: 1, why: 'the Link Analysis masking default: without the provider a sign-in install would lose its masking default',
    },
    'platform/inspecto-etl/src/main/java/com/gamma/etl/EditionFeatures.java': {
        sites: 1,
        why: 'the census of the features the BUILD declares, cached once per JVM; the entitlement is applied per use '
            + '(Entitlements.featureEntitled), so a renewal reaches it at once and a refusal names the entitlement',
    },
    'providers/asn-parser/asn-decoders/asn-transform/src/main/java/com/gamma/asn/transform/FunctionRegistry.java': {
        sites: 1,
        why: 'the ASN.1 library\'s own transform functions, internal to it, in a jar with no Inspecto dependency; the library '
            + 'reaches Inspecto only through the Telecom module\'s ParserPlugin, which is narrowed',
    },
};

/** OptionalSpi's unfiltered discovery: callers allowed, each with its reason. */
export const UNFILTERED_CALLERS = {
    'inspecto/src/main/java/com/gamma/control/ControlApi.java': {
        sites: 1,
        why: 'RouteModule discovery: a left-out module still registers and its routes answer 503 per request '
            + '(requireModuleEnabled), so a renewed entitlement opens them without a restart',
    },
    'inspecto/src/main/java/com/gamma/control/ApprovalPolicy.java': {
        sites: 1,
        why: 'governable kinds (maker-checker): a left-out module\'s kind stays governable, so a renewal that opens its routes '
            + 'never opens writes without approval',
    },
    'inspecto/src/main/java/com/gamma/control/ComponentRoutes.java': {
        sites: 3,
        why: 'component validators and delete hooks only refuse or clean up: a left-out module\'s kinds stay checked, its '
            + 'store names reserved, and its state is deleted with the component',
    },
    'inspecto/src/main/java/com/gamma/service/OperationalDb.java': {
        sites: 1,
        why: 'the operational store\'s family roster is storage layout, not capability: a left-out module\'s tables stay as '
            + 'they are, read correctly when it returns',
    },
};

// a call, a method reference, or a static import of load (then a bare `load(` would be a site the guard cannot see)
const SITE = /\bServiceLoader\s*(?:\.\s*load(?:Installed)?\s*\(|::\s*load(?:Installed)?\b|\.\s*(?:load(?:Installed)?|\*)\s*;)/g;
const UNFILTERED = /\bOptionalSpi\s*\.\s*allInstalled\s*\(/g;

/** Code lines only: block and line comments stripped, string literals blanked. */
export function codeOf(text) {
    return text
        .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
        .replace(/"(?:\\.|[^"\\\n])*"/g, '""')
        .replace(/\/\/.*$/gm, '');
}

export function check(root) {
    const problems = [];
    const files = execFileSync('git', ['ls-files', '-z', '--', '*.java'], { cwd: root, encoding: 'utf8' })
        .split('\0').filter((f) => f && /(^|\/)src\/main\/java\//.test(f));
    const seen = new Set();
    for (const f of files) {
        const code = codeOf(readFileSync(join(root, f), 'utf8'));
        const n = (code.match(SITE) || []).length;
        const open = OPEN[f];
        if (open) {
            seen.add(f);
            if (n !== open.sites) problems.push(`${f}: listed as open with ${open.sites} ServiceLoader site(s), found ${n} - update OPEN in tools/check-entitled-loaders.mjs`);
            if (open.filtered && !/EntitledServices\s*(::|\.)\s*entitled\b/.test(code))
                problems.push(`${f}: a fail-soft loader listed as filtered no longer asks EntitledServices.entitled per provider`);
        } else if (n > 0) {
            problems.push(`${f}: ${n} raw ServiceLoader.load site(s) - use com.gamma.module.EntitledServices (load / stream / first) so a module the signed entitlement leaves out is skipped; or list it in OPEN with its reason`);
        }
        const u = (code.match(UNFILTERED) || []).length;
        const allowed = UNFILTERED_CALLERS[f];
        if (allowed) {
            seen.add(f);
            if (u !== allowed.sites) problems.push(`${f}: listed with ${allowed.sites} OptionalSpi.allInstalled call(s), found ${u} - update UNFILTERED_CALLERS in tools/check-entitled-loaders.mjs`);
        } else if (u > 0) {
            problems.push(`${f}: ${u} OptionalSpi.allInstalled call(s) - it ignores the signed entitlement; use OptionalSpi.all, or list the file in UNFILTERED_CALLERS with its reason`);
        }
    }
    for (const f of [...Object.keys(OPEN), ...Object.keys(UNFILTERED_CALLERS)])
        if (!seen.has(f)) problems.push(`${f}: listed in OPEN or UNFILTERED_CALLERS but not a tracked main source file - remove the entry`);
    return problems;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const at = process.argv.indexOf('--root');
    const root = at > 0 ? resolve(process.argv[at + 1]) : join(dirname(fileURLToPath(import.meta.url)), '..');
    const problems = check(root);
    if (problems.length) {
        for (const p of problems) console.error(`✗ entitled-loader guard: ${p}`);
        process.exit(1);
    }
    console.log(`✓ entitled-loader guard: every ServiceLoader site goes through EntitledServices or is listed open (${Object.keys(OPEN).length} open)`);
}
