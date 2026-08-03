#!/usr/bin/env node
// Canonical-vocabulary guard. TWO independent passes, deliberately kept in one script so CI runs one step:
//
//   1. USER-FACING DOCS — prose must not use a ⛔ banned synonym (below).
//   2. TOON CONFIG KEYS — the committed config corpus must not grow a banned *key* (§3.2 of
//      docs/superpower/vocabulary-and-config-contract-plan.md). Cheapest, highest-value surface: a bad
//      key caught here costs nothing, while the same key caught after operators author data costs a
//      migration.
//
// Makes the glossary enforceable instead of aspirational: fails the build when a user-facing doc uses a
// ⛔ banned synonym or commits a known concept-confusion. Born from the 2026-07-07 USER_GUIDE audit, whose
// worst finding (A2: "Alert Rule watches a *measure* against a threshold") this guard catches automatically.
//
// SCOPE IS DELIBERATELY NARROW. It scans only the curated user-facing docs below — NOT the design/
// architecture/OKF tree, the glossary itself, or the archive. Those legitimately discuss internal names the
// rename program keeps on purpose (the `FlowGraph` IR, the physical `Store`, the observability `Metric`,
// the `flows/` storage dir — see GLOSSARY §13). Banning those words everywhere would be false-positive
// noise, and a noisy guard gets disabled. Add a doc to USER_FACING only once its vocabulary is pristine.
//
// Zero dependencies (pure Node). Run via `node tools/check-vocabulary.mjs`; wired into CI (ci.yml).
// Escape hatch: append `vocab-allow` in a comment on the offending line for a justified exception.

import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');

// The curated set of user-facing docs whose canonical vocabulary must stay pristine.
const USER_FACING = [
    'docs/USER_GUIDE.md',
    'docs/operations.md',
    'docs/troubleshooting.md',
    'docs/configuration.md',
    'docs/integrations.md',
    'docs/plugins.md',
    'docs/performance.md',
    'docs/parsing-options-reference.md',
    'docs/api-stability.md',
];

// Each rule: a per-line matcher returning the matched text (or null), plus a message. Rules run against
// prose only — fenced code blocks, inline `code` spans, ⛔-citation lines, and ~~strikethrough~~ (used to
// show a banned term being retired) are stripped/skipped first, so citing a ban is never itself a
// violation. Extend cautiously: only add a term that is unambiguous in this scanned set.
const RULES = [
    {
        id: 'measure-threshold',
        // A2 is specifically *alerting* on a Measure. A **KPI** legitimately is "a single-number Measure
        // with a target/threshold" (GLOSSARY §8) — so only fire when the line is also about alerting.
        test: (line) =>
            /measure/i.test(line) && /threshold/i.test(line) && /\balert(s|ing)?\b/i.test(line)
                ? 'measure … threshold … alert'
                : null,
        msg: 'An Alert Rule watches an observability **Metric** against a threshold, never a BI **Measure** (GLOSSARY §4/§8). This is the A2 confusion.',
    },
    {
        id: 'data-store',
        test: (line) => { const m = line.match(/\bdata stores?\b/i); return m ? m[0] : null; },
        msg: '"Data Store" is banned for a relation — use **Dataset** (GLOSSARY §6-B). "Store/Storage" alone is fine for the physical backend.',
    },
    {
        id: 'bare-flow',
        // The authored DAG is a **Pipeline** (⛔ "Flow"). "Workflow" and lowercase "flow" (of data/control)
        // are allowed; only a standalone capitalized "Flow" noun is flagged.
        test: (line) => { const m = line.replace(/workflow/gi, '').match(/\bFlow(s)?\b/); return m ? m[0] : null; },
        msg: 'The authored DAG is a **Pipeline**, never a "Flow" (GLOSSARY §5).',
    },
    {
        id: 'source-acquisition-entity',
        // Flipped 2026-07-14 (GLOSSARY §2/§3, rename map §13 row "Source (acquisition entity)"): the
        // acquisition entity is a **Collector**; "Source" now belongs to the data-origin axis only.
        // Case-sensitive and word-bounded on purpose, so the deliberate keeps still pass:
        //   · CamelCase type names — `Source` followed by a word char is not a match (SourceStoreReader,
        //     SourceConfigIntegrationTest, SourceFinalize) — see BACKLOG "Collector rename residual";
        //   · the TOON config key `source:` and any other backticked term (inline code is stripped above);
        //   · lowercase prose senses — "source files", "graph source", "single source of truth".
        test: (line) => {
            const m = line.match(/\bSources?\b(?!\s+(?:of|files?|code|data|system))/);
            return m ? m[0] : null;
        },
        msg: 'The configured collection task is a **Collector** (⛔ "Source" as the acquisition entity — GLOSSARY §2/§3, flipped 2026-07-14). A *data origin* is a **Stream** or **Reference**.',
    },
];

function stripInlineCode(s) {
    return s.replace(/`[^`]*`/g, ''); // drop `inline code` spans so backticked terms never trip a rule
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Pass 2: TOON config keys.
//
// SCANS THE COMMITTED CORPUS ONLY (`git ls-files`), not the working tree. Three reasons, all load-bearing:
//   · local == CI, so the guard cannot pass here and fail there (or the reverse);
//   · it honours .gitignore for free, so it never reads `spaces/**` runtime state — which per
//     DATA-GOV-1 can be REAL OPERATOR DATA that must never enter the repo and is none of a linter's
//     business; and
//   · a scratch config a dev has not committed yet is not a vocabulary regression.
//
// ⚠ The plan predicted "no allowlist needed — config keys should be pristine". That was WRONG, and the
// exception is the interesting part: `flow:` is a **deliberate legacy keep** awaiting the Flow→Pipeline
// Tier-3 migration (plan §4), not an oversight. So the allowlist below is not a list of sins to forgive
// — it is the Tier-3 debt, made executable: when that migration lands, these entries must be DELETED,
// and the guard then proves the rename is complete. Adding a new entry means adding new debt; prefer
// fixing the key.
const CONFIG_KEY_RULES = [
    {
        id: 'flow-key',
        test: (k) => /^flows?$/i.test(k),
        msg: 'The authored DAG is a **Pipeline**, never a "Flow" (GLOSSARY §5). The surviving `flow:` keys are pre-Tier-3 legacy and allowlisted below — do not author new ones.',
    },
    {
        id: 'data-store-key',
        test: (k) => /^data_?stores?$/i.test(k),
        msg: '"Data Store" is banned for a relation — use **Dataset** (GLOSSARY §6-B). A plain `store:` key is fine: that is the physical backend sense.',
    },
    {
        id: 'issue-key',
        test: (k) => /^issues?$/i.test(k),
        msg: 'An operational record is an **Incident**, never an "Issue" (GLOSSARY §7).',
    },
];

// Path-level rule. Narrow on purpose: `_flow.toon` (underscore) is the authored-flow representation W5
// retired the write routes for, while `circular-flow.toon` (hyphen) is a link-analysis MOTIF name — the
// sanctioned lowercase "flow of value" sense. Banning the word outright would flag those three pattern
// packs, i.e. exactly the false-positive noise this file's header warns disables a guard.
const CONFIG_PATH_RULES = [
    {
        id: 'authored-flow-path',
        test: (p) => /(^|\/)config\/flows\//.test(p) || /_flow\.toon$/.test(p),
        msg: 'The authored `*_flow.toon` representation is retired (its write routes are 405 since W5) and renames to `pipelines/` in Tier 3. Grandfathered files are allowlisted; author new graphs as `*_pipeline.toon`.',
    },
];

// Keyed `<path>::<ruleId>` so allowing one known keep never blanket-exempts a file from the other rules.
const CONFIG_ALLOW = {
    'inspecto/examples/06-serve/pipeline-job/rollup_job.toon::flow-key':
        'Tier-3 debt: `flow:` in a `type: pipeline` job is verbatim legacy; renaming it breaks existing configs without a dual-read (plan §4).',
    'spaces/demo/config/jobs/orders_rollup_job.toon::flow-key':
        'Tier-3 debt: as above, the demo-space twin of the example job.',
    'inspecto/examples/06-serve/pipeline-job/write/flows/sales_rollup_flow.toon::authored-flow-path':
        'Tier-3 debt: grandfathered authored flow, still readable/runnable via PipelineJobRunner.',
    'spaces/demo/config/flows/orders_rollup_flow.toon::authored-flow-path':
        'Tier-3 debt: as above, the demo-space twin.',
};

/** Committed `*.toon` paths (repo-relative, forward slashes), or null outside a git checkout. */
function trackedToonFiles() {
    try {
        return execFileSync('git', ['ls-files', '-z', '*.toon'], { cwd: repoRoot, encoding: 'utf8' })
            .split('\0')
            .filter(Boolean);
    } catch {
        return null; // not a git checkout (e.g. a source tarball) — skip pass 2 rather than fail the build
    }
}

const configViolations = [];
const usedAllow = new Set(); // an allowlist entry that never fires is debt that has already been paid
const toonFiles = trackedToonFiles();
for (const rel of toonFiles ?? []) {
    for (const rule of CONFIG_PATH_RULES) {
        if (!rule.test(rel)) continue;
        const allow = `${rel}::${rule.id}`;
        if (CONFIG_ALLOW[allow]) { usedAllow.add(allow); continue; }
        configViolations.push({ rel, line: 0, rule: rule.id, msg: rule.msg, hit: rel, src: '(file path)' });
    }

    let text;
    try {
        text = readFileSync(join(repoRoot, rel), 'utf8');
    } catch {
        continue; // listed by git but absent from the worktree (sparse checkout) — nothing to scan
    }
    text.split(/\r?\n/).forEach((raw, i) => {
        if (raw.includes('vocab-allow')) return;
        const key = raw.replace(/#.*$/, '').match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:\[\d*\])?\s*:/);
        if (!key) return;
        for (const rule of CONFIG_KEY_RULES) {
            if (!rule.test(key[1])) continue;
            const allow = `${rel}::${rule.id}`;
            if (CONFIG_ALLOW[allow]) { usedAllow.add(allow); continue; }
            configViolations.push({ rel, line: i + 1, rule: rule.id, msg: rule.msg, hit: key[1], src: raw.trim() });
        }
    });
}

// The allowlist IS the Tier-3 debt register, so it must not be allowed to rot into a lie. An entry that
// no longer suppresses anything means the file was fixed, renamed or deleted — i.e. the debt was PAID,
// and the guard should say so out loud rather than carry a stale exemption that would silently forgive a
// future regression at that path. This is what makes the rename self-announcing: when Tier 3 removes the
// last `flow:` key, this fails and tells you to delete the entry.
if (toonFiles !== null) {
    for (const allow of Object.keys(CONFIG_ALLOW)) {
        if (usedAllow.has(allow)) continue;
        const [path, ruleId] = allow.split('::');
        configViolations.push({
            rel: path,
            line: 0,
            rule: 'stale-allowlist',
            hit: ruleId,
            src: '(CONFIG_ALLOW entry)',
            msg: `This allowlist entry no longer suppresses anything — the ${ruleId} debt at this path is paid. DELETE the entry from CONFIG_ALLOW.`,
        });
    }
}

const violations = [];
for (const rel of USER_FACING) {
    let text;
    try {
        text = readFileSync(join(repoRoot, rel), 'utf8');
    } catch {
        continue; // a listed doc may not exist on every branch — skip silently
    }
    let inFence = false;
    text.split(/\r?\n/).forEach((raw, i) => {
        const fence = raw.trimStart().startsWith('```');
        if (fence) { inFence = !inFence; return; }
        if (inFence) return;                       // inside a ``` code block
        if (raw.includes('vocab-allow')) return;   // per-line escape hatch
        if (raw.includes('⛔')) return;             // a line explicitly citing a ban
        const line = stripInlineCode(raw).replace(/~~[^~]*~~/g, ''); // drop strikethrough (retired terms)
        for (const rule of RULES) {
            const hit = rule.test(line);
            if (hit) violations.push({ rel, line: i + 1, rule: rule.id, msg: rule.msg, hit, src: raw.trim() });
        }
    });
}

const all = [...violations, ...configViolations];
if (all.length) {
    console.error(`\n✖ Vocabulary guard: ${violations.length} violation(s) in user-facing docs, ${configViolations.length} in TOON config\n`);
    for (const v of all) {
        console.error(`  ${v.rel}:${v.line}  [${v.rule}] ${v.hit}`);
        console.error(`      ${v.src}`);
        console.error(`      → ${v.msg}\n`);
    }
    console.error('Fix by using the canonical term (docs/GLOSSARY.md), or append `vocab-allow` on the line for a justified exception.');
    console.error('A config key needs a deliberate keep? Add `<path>::<ruleId>` to CONFIG_ALLOW WITH a reason — it is tracked debt, not an excuse.\n');
    process.exit(1);
}

const configScope = toonFiles === null
    ? 'TOON pass skipped (not a git checkout)'
    : `${toonFiles.length} committed TOON config(s) clean`;
console.log(`✓ Vocabulary guard: ${USER_FACING.length} user-facing doc(s) + ${configScope} — no banned synonyms or concept-confusion.`);
