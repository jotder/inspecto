#!/usr/bin/env node
// Canonical-vocabulary guard. FOUR independent passes, deliberately kept in one script so CI runs one step:
//
//   1. USER-FACING DOCS — prose must not use a ⛔ banned synonym (below). Curated set, no allowlist.
//   2. TOON CONFIG KEYS — the committed config corpus must not grow a banned *key* (§3.2 of the archived
//      docs/archived-documents/plans-archive/vocabulary-and-config-contract-plan.md). Cheapest, highest-value surface: a bad
//      key caught here costs nothing, while the same key caught after operators author data costs a
//      migration.
//   3. KNOWLEDGE TREES — `docs/okf/**` + `docs/superpower/**`, same rules as pass 1 but allowlisted per
//      `<path>::<ruleId>` (DOC_ALLOW). Added 2026-08-04; `docs/archived-documents/**` stays excluded.
//   4. JAVA + TS SOURCE IDENTIFIERS — the surface §3.2 said to land LAST, added 2026-08-04. Bans `flow`
//      only where it is WELDED to another word (`flowStore`, `FLOW_CONSERVATION`), never the bare word.
//      See SOURCE_RULES for why that restraint is what makes the pass possible at all.
//
// Makes the glossary enforceable instead of aspirational: fails the build when a user-facing doc uses a
// ⛔ banned synonym or commits a known concept-confusion. Born from the 2026-07-07 USER_GUIDE audit, whose
// worst finding (A2: "Alert Rule watches a *measure* against a threshold") this guard catches automatically.
//
// PASS 1'S SCOPE IS DELIBERATELY NARROW: only the curated user-facing docs below, with no allowlist. Add a
// doc to USER_FACING only once its vocabulary is pristine.
//
// The design/architecture/OKF tree is covered by pass 3 INSTEAD, because it legitimately discusses internal
// names the rename program keeps on purpose (the physical `Store`, the observability `Metric`, the `flows/`
// storage dir — see GLOSSARY §13). Banning those words outright would be false-positive noise, and a noisy
// guard gets disabled — so pass 3 carries a *reasoned* per-file allowlist rather than a narrower scope.
// The glossary itself and the archive remain unscanned.
//
// Zero dependencies (pure Node). Run via `node tools/check-vocabulary.mjs`; wired into CI (ci.yml).
// Escape hatch: append `vocab-allow` in a comment on the offending line for a justified exception.

import { existsSync, readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');

// `git ls-files` failing has two causes and only ONE of them is benign: a source tarball with no `.git`
// (skip the git-backed passes), versus git refusing to read a real checkout — `detected dubious ownership`,
// which is the default for any operator whose Windows profile differs from the checkout owner. Both used to
// land in the same silent skip, so three of four passes scanned NOTHING and the guard exited 0 while the
// repo was red. Probe once, up front, and fail closed on the second case.
const gitAccess = (() => {
    try {
        execFileSync('git', ['rev-parse', '--show-toplevel'], { cwd: repoRoot, stdio: ['ignore', 'pipe', 'pipe'] });
        return { ok: true };
    } catch (err) {
        if (!existsSync(join(repoRoot, '.git'))) return { ok: false, tarball: true };
        return { ok: false, reason: String(err.stderr ?? '').trim() || 'git rev-parse failed' };
    }
})();

if (!gitAccess.ok && !gitAccess.tarball) {
    console.error('\n✖ Vocabulary guard: CANNOT RUN — this is a git checkout, but git refuses to read it.\n');
    console.error(`  ${gitAccess.reason.replace(/\n/g, '\n  ')}\n`);
    console.error('Three of the four passes scan the committed corpus via `git ls-files`. Without it they scan');
    console.error('nothing, and a green result would mean only that the guard was blindfolded. Fix git access,');
    console.error('then re-run. For a checkout owned by a different user than the current profile:\n');
    console.error(`  git config --global --add safe.directory ${repoRoot.replace(/\\/g, '/')}\n`);
    process.exit(2);
}

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

// Pass 3 (§3.2, added 2026-08-04): the KNOWLEDGE trees — `docs/okf/**` (current knowledge) and
// `docs/superpower/**` (active plans). Same rules as pass 1, but allowlisted per `<path>::<ruleId>` via
// DOC_ALLOW, because these docs legitimately name things the rename program keeps on purpose.
//
// `docs/archived-documents/**` is EXCLUDED PERMANENTLY, not pending: CLAUDE.md defines that tier as
// "kept for provenance, never maintained, never linked as current", and it holds most of the repo's raw
// `flow` hits. Linting a tree nobody may edit would be unfixable-by-design noise.
const DOC_TREES = ['docs/okf', 'docs/superpower'];

// Keyed `<path>::<ruleId>`, exactly like CONFIG_ALLOW, so exempting one known keep never blanket-exempts a
// file from the other rules. Two legitimate shapes only:
//   1. the doc's SUBJECT MATTER is the banned term (a rename plan, a historical log); or
//   2. the doc uses a different, sanctioned sense of the word (the physical Store, a GraphSource).
// Anything else is a stale doc and must be FIXED — that is how this pass found the `sources.md`/
// "Source Connectors" residue of the shipped Source→Collector rename.
const DOC_ALLOW = {
    'docs/okf/backend/pipeline-graph/pipeline-graph-design.md::bare-flow':
        'Historical design narrative: "Flow IR"/"Flow document"/"Flows pane" record the pre-rename design and its shipped phase log. The IR types themselves are already `PipelineGraph`/`PipelineNode`; rewriting the narrative would falsify the history. Residual `*_flow.toon`/`flows/` mentions are Tier-3 debt (plan §4).',
    'docs/okf/backend/pipeline-graph/pipeline-graph-design.md::data-store':
        'Sanctioned sense: "the two share the data store" is the physical backend, not a Dataset (GLOSSARY §6-B).',
    'docs/okf/backend/engine/db-layer.md::data-store':
        'Sanctioned sense: "Business-data stores" read via a DuckDB sandbox is the physical backend, not a Dataset.',
    // The vocabulary-and-config-contract plan's two entries were RETIRED 2026-08-04 when the plan shipped
    // and moved to docs/archived-documents/plans-archive/ (a permanently unscanned tier). The
    // stale-allowlist rule named both the moment the file left this scope — the self-retirement working as
    // designed, rather than two exemptions quietly outliving the debt they described.
    'docs/okf/frontend/features/link-analysis.md::source-acquisition-entity':
        'Different concept: link-analysis "Sources" are `GraphSource` renderer feeds, not acquisition entities.',
    'docs/okf/backend/integrations.md::source-acquisition-entity':
        'Sanctioned sense: "Remote Sources" are data origins (Stream/Reference axis, GLOSSARY §3), not collection tasks.',
    'docs/okf/backend/log.md::bare-flow':
        'Historical changelog: the entry records the `flow-graph`→`pipeline-graph` directory rename itself.',
};

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
        id: 'cube-noun',
        // Cube→Matrix (GLOSSARY §13, plan §5, additive UI label — the model stays NodeKind.DERIVED_TABLE).
        // Case-sensitive on purpose: the sanctioned VERB sense is always lowercase in existing prose
        // ("aggregates (cubes)...", "a Transform or cube/rollup") and must keep passing; only a
        // capitalized "Cube" noun is the asset-name confusion this rule catches. The `heroicons_outline:cube`
        // icon id is backticked and already stripped by stripInlineCode above.
        test: (line) => { const m = line.match(/\bCube\b/); return m ? m[0] : null; },
        msg: 'The summary Derived Table\'s user-facing name is **Matrix** (GLOSSARY §13) — "Cube" stays a lowercase **verb** (the Transform action), never the asset\'s noun.',
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
        //
        // The trailing-noun exemption covers the *data-origin* axis, which is NOT the acquisition entity:
        //   · `[-\s]` not `\s` — "Source-of-truth" is the same sense as "source of truth" and was a live
        //     false positive (docs/okf/frontend/architecture.md) until the hyphen was allowed here;
        //   · path/format/size describe the origin artefact ("Source path", "Source size equals local
        //     size"), never the configured collection task.
        // ⚠ Do NOT add `connector` here: the SPI is `CollectorConnector`, so "Source Connector" is a real
        // violation, not a sense — it is one of the stale-doc hits this rule was extended to catch.
        test: (line) => {
            const m = line.match(/\bSources?\b(?![-\s]+(?:of|files?|code|data|system|path|format|size))/);
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

// ── pass 4: Java + TS source identifiers (plan §3.2, the surface it said to land LAST) ────────────
//
// ⚠ This pass bans `flow` ONLY where it is glued to another word in an identifier (`flowStore`,
// `openFlowStore`, `FLOW_CONSERVATION_IMBALANCE`). It deliberately does NOT ban the bare word, and that
// restraint is the whole reason it can exist: a measurement before writing it found ~223 files matching
// bare `flow`, of which the overwhelming majority are one of three legitimate things —
//
//   1. `Workflow` / `overflow` — `flow` as a SUBSTRING of an unrelated canonical word. `Workflow` is the
//      Incident/Case state machine and is canonical; a naive /flow/i would have flagged all of it.
//   2. the sanctioned lowercase "flow of value" sense — link analysis genuinely computes **max-flow**
//      (`flowFrom`/`flowTo`, the `circular-flow` motif), and prose says "control flow", "back-pressure".
//   3. citations of the retired `*_flow.toon` format, which is grandfathered on purpose (W5).
//
// A compound identifier has none of that ambiguity: nobody writes `flowStore` meaning fluid dynamics. So
// this rule is precise where a word-level rule would be noise — and per this file's own header, a noisy
// guard gets disabled. Bare `flow` as a standalone local, parameter or javadoc word is left to a later
// sweep; it needs per-occurrence judgement, not a regex.
//
// Scope is `src/main/**` + the UI app, NOT test sources: test METHOD NAMES are a sentence
// (`aFlowJobSuccessChainsADownstreamJob`), so they read as prose and carry no contract. Renaming them is
// cosmetic and would have tripled this change for no reader benefit.
const SOURCE_GLOBS = ['*/src/main/java/*.java', 'inspecto-ui/src/app/*.ts', 'inspecto-ui/src/app/*.html'];
/** `*.spec.ts` is the TS half of the "test names are prose" exclusion above — `src/main/**` does it for Java. */
const SOURCE_SKIP = /\.spec\.ts$/;

const SOURCE_RULES = [
    {
        id: 'flow-identifier',
        // `flow` welded to another word: flowStore | FlowStore | openFlowStore | FLOW_CONSERVATION |
        // liftedFlows. The middle alternative needs the trailing `[A-Za-z]*` — an earlier `[a-z]+Flows?\b`
        // silently MISSED `openFlowStore`, because `\b` cannot sit between `Flow` and `Store`. A probe
        // caught it; without proving the rule red on a word-Flow-word name it would have shipped blind.
        // `[a-z]+Flow` requires the capital F, so `workflow`/`overflow` can never match, and the
        // `flow[A-Z_]` lookahead means bare `flow`/`flows`/`flowing` do not match either.
        re: /\b(?:[Ff]low(?=[A-Z_])[A-Za-z_]*|[a-z]+Flow[A-Za-z]*|FLOW_[A-Z_]+)\b/g,
        msg: 'The authored DAG is a **Pipeline**, never a "Flow" (GLOSSARY §5). Rename the identifier. If this is the sanctioned lowercase "flow of value" sense (link-analysis max-flow) or a citation of the retired `*_flow.toon` format, put `vocab-allow` on the line or allowlist the file.',
    },
];

// Same `<path>::<ruleId>` keying and the same self-retirement as CONFIG_ALLOW: an entry that stops
// suppressing anything is debt that has been PAID and must be deleted.
const SOURCE_ALLOW = {
    'inspecto-event/src/main/java/com/gamma/event/EventType.java::flow-identifier':
        'Deliberate Tier-2 read-alias: FLOW_CONSERVATION_IMBALANCE_LEGACY must keep the pre-rename spelling — it exists to match events already persisted under the old type. Renaming it would defeat its purpose.',
    'inspecto-engine/src/main/java/com/gamma/ops/EventObjectBridge.java::flow-identifier':
        'Reads the Tier-2 legacy alias above so pre-rename events still promote — the whole point of the alias.',

    // ── the sanctioned lowercase "flow of value" sense: NOT the Pipeline entity ────────────────────
    'inspecto-engine/src/main/java/com/gamma/query/ExpressionGuard.java::flow-identifier':
        'FLOW_KEYWORDS is the SQL **control-flow** keyword set (CASE/WHEN/…) — English sense, nothing to do with a Pipeline.',
    'inspecto-ui/src/app/modules/admin/studio/datasets/calculated-column-guard.ts::flow-identifier':
        'The TS twin of ExpressionGuard.FLOW_KEYWORDS — same SQL control-flow sense.',
    'inspecto-ui/src/app/inspecto/graph/graph-analysis.ts::flow-identifier':
        'maxFlow is the **max-flow/min-cut** graph algorithm — the mathematical sense, and the only correct name for it.',
    'inspecto-ui/src/app/modules/admin/studio/link-analysis/link-analysis-toolbox.component.html::flow-identifier':
        'The template half of the max-flow toolbox below (flowFrom/flowTo/runFlow) — same algorithmic sense.',
    'inspecto-intelligence/src/main/java/com/gamma/intelligence/pack/InspectoTools.java::flow-identifier':
        'Tier-3 debt, NOT an internal rename: `flowSchemaJson` builds the schema for an agent tool ARGUMENT named `flow`, which is an external contract the model is prompted against. Renaming it needs dual-accept on the tool arg (BACKLOG), so the helper keeps the argument\'s name until then.',
    'inspecto-intelligence/src/main/java/com/gamma/intelligence/pack/ArgumentDeriver.java::flow-identifier':
        'Tier-3 debt: `constrainedFlow` constrains that same `flow` tool argument — see InspectoTools above.',
    'inspecto-intelligence/src/main/java/com/gamma/intelligence/InspectoIntelligenceAgent.java::flow-identifier':
        'Tier-3 debt: calls `constrainedFlow` — see ArgumentDeriver above.',
    'inspecto-ui/src/app/modules/admin/studio/link-analysis/link-analysis-toolbox.component.ts::flow-identifier':
        'NOT a Pipeline: this is graph **max-flow** analysis (`flowFrom`/`flowTo`/`runFlow`), the sanctioned lowercase "flow of value" sense GLOSSARY permits — the same sense as the `circular-flow` motif pattern packs.',
};

/** Committed paths matching a pathspec (repo-relative, forward slashes), or null outside a git checkout. */
function trackedFiles(pattern) {
    try {
        return execFileSync('git', ['ls-files', '-z', pattern], { cwd: repoRoot, encoding: 'utf8' })
            .split('\0')
            .filter(Boolean);
    } catch (err) {
        // Reachable only for a source tarball: a checkout git cannot read already exited 2 above. A failure
        // here despite working git is a real fault, never a reason to quietly narrow the scan to nothing.
        if (gitAccess.ok) throw err;
        return null; // not a git checkout (e.g. a source tarball) — skip the pass rather than fail the build
    }
}

const configViolations = [];
const usedAllow = new Set(); // an allowlist entry that never fires is debt that has already been paid
const toonFiles = trackedFiles('*.toon');
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

/** Run the prose RULES over one markdown file. Shared by pass 1 and pass 3 so the two can never drift. */
function scanProse(rel) {
    let text;
    try {
        text = readFileSync(join(repoRoot, rel), 'utf8');
    } catch {
        return []; // a listed doc may not exist on every branch — skip silently
    }
    const hits = [];
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
            if (hit) hits.push({ rel, line: i + 1, rule: rule.id, msg: rule.msg, hit, src: raw.trim() });
        }
    });
    return hits;
}

// Pass 1: the curated user-facing docs. No allowlist by design — this set is kept pristine.
const violations = [];
for (const rel of USER_FACING) violations.push(...scanProse(rel));

// Pass 3: the knowledge trees, filtered through DOC_ALLOW. Committed files only (`git ls-files`), for the
// same local==CI reason as pass 2.
const usedDocAllow = new Set();
const treeViolations = [];
const treeMarkdown = trackedFiles('*.md');
const treeDocs = (treeMarkdown ?? []).filter(
    (p) => DOC_TREES.some((t) => p.startsWith(`${t}/`)),
);
for (const rel of treeDocs) {
    for (const hit of scanProse(rel)) {
        const allow = `${rel}::${hit.rule}`;
        if (DOC_ALLOW[allow]) { usedDocAllow.add(allow); continue; }
        treeViolations.push(hit);
    }
}

// Same self-retiring discipline as CONFIG_ALLOW: an entry that stops suppressing anything is a lie that
// would silently forgive a future regression at that path.
if (treeDocs.length) {
    for (const allow of Object.keys(DOC_ALLOW)) {
        if (usedDocAllow.has(allow)) continue;
        const [path, ruleId] = allow.split('::');
        treeViolations.push({
            rel: path,
            line: 0,
            rule: 'stale-allowlist',
            hit: ruleId,
            src: '(DOC_ALLOW entry)',
            msg: `This allowlist entry no longer suppresses anything — the ${ruleId} exemption at this path is unused. DELETE the entry from DOC_ALLOW.`,
        });
    }
}

// Pass 4: Java + TS source identifiers. Committed files only, same local==CI reason as pass 2.
const sourceViolations = [];
const usedSourceAllow = new Set();
const sourceFiles = SOURCE_GLOBS.every((g) => trackedFiles(g) !== null)
    ? [...new Set(SOURCE_GLOBS.flatMap((g) => trackedFiles(g)))].filter((p) => !SOURCE_SKIP.test(p))
    : null;
for (const rel of sourceFiles ?? []) {
    let text;
    try {
        text = readFileSync(join(repoRoot, rel), 'utf8');
    } catch {
        continue; // listed by git but absent from the worktree (sparse checkout)
    }
    text.split(/\r?\n/).forEach((raw, i) => {
        if (raw.includes('vocab-allow')) return;
        for (const rule of SOURCE_RULES) {
            for (const m of raw.match(rule.re) ?? []) {
                const allow = `${rel}::${rule.id}`;
                if (SOURCE_ALLOW[allow]) { usedSourceAllow.add(allow); continue; }
                sourceViolations.push({ rel, line: i + 1, rule: rule.id, msg: rule.msg, hit: m, src: raw.trim() });
            }
        }
    });
}

// Self-retiring, exactly as CONFIG_ALLOW/DOC_ALLOW: most SOURCE_ALLOW entries are Tier-3 debt, so when
// that debt is paid the guard says so instead of carrying an exemption that would forgive a regression.
if (sourceFiles !== null) {
    for (const allow of Object.keys(SOURCE_ALLOW)) {
        if (usedSourceAllow.has(allow)) continue;
        const [path, ruleId] = allow.split('::');
        sourceViolations.push({
            rel: path,
            line: 0,
            rule: 'stale-allowlist',
            hit: ruleId,
            src: '(SOURCE_ALLOW entry)',
            msg: `This allowlist entry no longer suppresses anything — the ${ruleId} exemption at this path is unused. DELETE the entry from SOURCE_ALLOW.`,
        });
    }
}

const all = [...violations, ...treeViolations, ...configViolations, ...sourceViolations];
if (all.length) {
    console.error(`\n✖ Vocabulary guard: ${violations.length} violation(s) in user-facing docs, ${treeViolations.length} in docs/{okf,superpower}, ${configViolations.length} in TOON config, ${sourceViolations.length} in Java/TS source\n`);
    for (const v of all) {
        console.error(`  ${v.rel}:${v.line}  [${v.rule}] ${v.hit}`);
        console.error(`      ${v.src}`);
        console.error(`      → ${v.msg}\n`);
    }
    console.error('Fix by using the canonical term (docs/GLOSSARY.md), or append `vocab-allow` on the line for a justified exception.');
    console.error('A config key needs a deliberate keep? Add `<path>::<ruleId>` to CONFIG_ALLOW WITH a reason — it is tracked debt, not an excuse.');
    console.error('A doc in docs/{okf,superpower} whose SUBJECT is the banned term, or which uses a sanctioned other sense? Add `<path>::<ruleId>` to DOC_ALLOW WITH a reason. A merely STALE doc must be fixed, not allowlisted.');
    console.error('A source identifier using the sanctioned lowercase sense (link-analysis max-flow), or blocked on an external contract? Add `<path>::<ruleId>` to SOURCE_ALLOW WITH a reason, or `vocab-allow` on the line for a one-off citation.\n');
    process.exit(1);
}

const treeScope = treeMarkdown === null
    ? 'docs/{okf,superpower} pass skipped (not a git checkout)'
    : `${treeDocs.length} docs/{okf,superpower} doc(s)`;
const configScope = toonFiles === null
    ? 'TOON pass skipped (not a git checkout)'
    : `${toonFiles.length} committed TOON config(s) clean`;
const sourceScope = sourceFiles === null
    ? 'source pass skipped (not a git checkout)'
    : `${sourceFiles.length} Java/TS source file(s) clean`;
console.log(`✓ Vocabulary guard: ${USER_FACING.length} user-facing doc(s) + ${treeScope} + ${configScope} + ${sourceScope} — no banned synonyms or concept-confusion.`);
