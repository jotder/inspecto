#!/usr/bin/env node
// The Link Analysis demo corpus must keep carrying at least one SPLIT IDENTITY — an entity whose name
// arrives in several spellings while its key stays constant.
//
// WHY THIS EXISTS. Decision D-S4 asks whether Link Analysis should keep projecting graph node ids from
// raw column values (trim only, no case fold, no alias resolution) or build a first-class entity model.
// On 2026-09-22 the question was measured against every dataset this repo ships: 24 candidate entity
// columns across 4 spaces, and the split count was EXACTLY ZERO — every entity came from a canonical
// literal in the generator, so a variant spelling was impossible by construction. The failure was
// structurally possible and empirically unobservable, which is the worst state for a decision to rest in:
// an entity model could not be justified by observed damage, and staying value-projected could not be
// falsified either.
//
// 🔴 AND THE SHIPPED DETECTOR READ ZERO. `splitIdentityGroups` and the working-set notice went in that
// morning. On a corpus that cannot produce a collision they are indistinguishable from a broken feature —
// a guard that always passes teaches nobody, and the next shift's reasonable conclusion is "this doesn't
// work" rather than "there is nothing to find".
//
// So story (e) was planted in `gen-link-analysis-demos.py`: Cinder Wireless is spelled four ways across
// partners while PLMN 00103 never varies. That is how real interconnect feeds behave — the PLMN is the
// contract key, the name is free text typed by whoever built the file — and it makes D-S4's risk
// measurable instead of arguable. This guard exists so a later regeneration cannot quietly return the
// corpus to clean and take the evidence with it.
//
// SCOPE, stated so it can be audited apart from the rule: the two projected name columns of the roaming
// TAP samples (`SENDER_NAME`, `RECIPIENT_NAME`) in `spaces/demo/data/samples/roaming_tap/TAP_*.csv`.
// Other datasets are deliberately NOT required to carry a split — one demonstrable case is the point,
// and requiring it everywhere would make the corpus unrepresentative in the other direction.
//
// Usage:  node tools/check-split-identity-fixture.mjs

import { readFileSync } from 'node:fs';
import { globSync } from 'node:fs';

const GLOB = 'spaces/demo/data/samples/roaming_tap/TAP_*.csv';
const COLUMNS = ['SENDER_NAME', 'RECIPIENT_NAME'];

/** The same comparison key the SPA's `identityKey` uses: case-folded, whitespace-collapsed, de-punctuated. */
const key = (v) => v.toLowerCase().replace(/\s+/g, ' ').replace(/[.,;:]+$/, '').trim();

const files = globSync(GLOB);
if (files.length === 0) {
    console.error(`✗ Split-identity fixture guard: no files matched ${GLOB} — the corpus moved or was deleted.`);
    process.exit(1);
}

let failed = false;
const report = [];

for (const col of COLUMNS) {
    const raw = new Set();
    const groups = new Map();
    for (const file of files) {
        const lines = readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean);
        const header = lines[0].split(',');
        const idx = header.indexOf(col);
        if (idx < 0) {
            console.error(`✗ Split-identity fixture guard: column ${col} is not in ${file}.`);
            process.exit(1);
        }
        for (const line of lines.slice(1)) {
            const v = (line.split(',')[idx] ?? '').trim();
            if (!v) continue;
            raw.add(v);
            const k = key(v);
            if (!groups.has(k)) groups.set(k, new Set());
            groups.get(k).add(v);
        }
    }
    const split = raw.size - groups.size;
    const examples = [...groups.entries()].filter(([, v]) => v.size > 1);
    if (split < 1) {
        failed = true;
        console.error(
            `✗ ${col}: ${raw.size} distinct raw values collapse to ${groups.size} — SPLIT ${split}.\n` +
                `  The corpus no longer demonstrates a split identity, so decision D-S4 has lost its evidence\n` +
                `  and the shipped split-identity notice reads zero again. Restore story (e) in\n` +
                `  spaces/demo/data/samples/gen-link-analysis-demos.py and regenerate.`,
        );
    } else {
        report.push(
            `${col}: ${raw.size} raw → ${groups.size} normalised, split ${split} ` +
                `(${examples.map(([k, v]) => `${k} ← ${v.size} spellings`).join('; ')})`,
        );
    }
}

if (failed) process.exit(1);
console.log(
    `✓ Split-identity fixture guard: the demo corpus still demonstrates D-S4's risk — ` +
        report.join(' · ') +
        ` — scope: ${files.length} roaming TAP sample file(s), columns ${COLUMNS.join('/')}. ` +
        `A split here is DELIBERATE (story (e)): the name varies, the PLMN key does not.`,
);
