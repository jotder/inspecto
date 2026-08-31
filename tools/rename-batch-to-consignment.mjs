#!/usr/bin/env node
/**
 * Phase 7 codemod — `Batch` → `Consignment` (pipeline spec Wave 2, row 1; GLOSSARY §13, D5).
 *
 * D5 requires this as ONE commit with a codemod, never drip-fed, so this script does the whole sweep:
 * renames the type-bearing FILES (git mv, so history follows) and rewrites every identifier, in one run.
 *
 *   node tools/rename-batch-to-consignment.mjs --dry-run     # report only, touches nothing
 *   node tools/rename-batch-to-consignment.mjs               # do it
 *
 * ⚠ Written 2026-08-31 because the authoring session's sandbox refused `git mv` (both batched and
 * single) and an unbounded source rewrite. Every DECISION below is already made and grounded — see
 * `docs/superpower/pipeline-waves-drain-plan.md` §2.3. This file exists so the sweep is one command for
 * whoever has the permissions, not a re-derivation.
 *
 * ── Why the mapping looks like this ────────────────────────────────────────────────────────────────
 *
 * 🔴 `BatchProcessor` does NOT become `ConsignmentProcessor`: that name is TAKEN by the third-party
 *    post-sync SPI (`com.gamma.consignment.ConsignmentProcessor`) which `packs-dev/*` and
 *    `tools/templates/processor` implement. Reusing it would break plugin authors and the GLOSSARY's
 *    "one word → one concept" rule. It becomes `ConsignmentIngestor`, pairing with
 *    `ConsignmentIngestStrategy` and matching its own doc ("a thin coordinator: selects a strategy,
 *    runs it, drives commit + writeAudit").
 *
 * ⛔ The GROUPING sense of "batch" is NOT renamed — `batch_max_files`/`batch_max_bytes` config keys,
 *    `BatchedOperations` telemetry, JDBC's `addBatch()`. Word-boundary matching makes that automatic:
 *    `\bBatch\b` cannot match inside `addBatch`, `BatchedOperations`, or lowercase `batch_max_*`. The
 *    exclusions are a property of the regex, not a skip list someone has to maintain.
 *
 * ⛔ `PipelineConfigBatchTest` / `…TestRef` are DELIBERATELY NOT RENAMED, and this is the single biggest
 *    scoping decision in the sweep. It is a shared TEST FIXTURE (`miniSchema()`, `miniSchemaMap()`,
 *    `writePipeline()`) imported by **165 test classes** — over half the sweep's whole blast radius — and
 *    it is not "the entity with an id and a status" that GLOSSARY §13 says to rename. Renaming it is
 *    churn across 165 files for no vocabulary gain. Excluding it cuts the sweep from 304 files to ~139.
 *    This is what "scope by CONCEPT, not by string" means in practice.
 *
 * ⛔ DOCS ARE NOT SWEPT. `docs/archived-documents/` is never-maintained by policy, and the canon carries
 *    deliberate history ("Was `Run ⊇ Batch ⊇ File` until 2026-08-03") that a rename would falsify.
 *    Update `docs/GLOSSARY.md`'s Consignment entry + its §13 row BY HAND to record the rollout.
 *
 * ⚠ WIRE AND PERSISTED SPELLINGS ARE OUT OF SCOPE. This renames Java *types*. `batch_id` on disk already
 *    has its read-alias (`Csv.LEGACY_HEADERS`), and the two served routes (`GET /runs/{n}/batches`,
 *    `GET /provenance/batches`) keep answering — add the `consignments` spelling alongside them in the
 *    same commit if you want the served surface renamed too, but do NOT hard-break either.
 *
 * ── After running, in this order ───────────────────────────────────────────────────────────────────
 *   1. mvn -o -pl inspecto-engine -am test -Dtest='NodeAttributesContractTest,StepTypesContractTest'
 *      → regenerate BOTH committed contracts if they move:
 *        -Dnode.attributes.write=true   and the step-types equivalent.
 *      🔴 Regenerate BOTH or the FULL reactor goes red after a green targeted run.
 *   2. node tools/check-vocabulary.mjs      (the guard is the arbiter of the canonical word)
 *   3. mvn -o clean test                    (the full reactor — baseline 3841 / 0 / 0 / 5)
 *   4. inspecto-ui: npx ng test --no-watch   (UI mirrors, if any moved)
 */

import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';

const DRY = process.argv.includes('--dry-run');

/**
 * Longest key first — `BatchEventBus` must be replaced before `BatchEvent`, and every compound before
 * bare `Batch`. The alternation below is built in this order for exactly that reason.
 */
const MAP = {
    // the collision-avoiding four (decided; see the plan §2.3)
    BatchProcessorPluginDeepTest: 'ConsignmentIngestorPluginDeepTest',
    BatchProcessorPluginTest: 'ConsignmentIngestorPluginTest',
    BatchProcessorSinksTest: 'ConsignmentIngestorSinksTest',
    BatchProcessorTest: 'ConsignmentIngestorTest',
    BatchProcessor: 'ConsignmentIngestor',
    StreamingPluginBatchStrategyTest: 'StreamingPluginIngestStrategyTest',
    StreamingPluginBatchStrategy: 'StreamingPluginIngestStrategy',
    CsvBatchStrategy: 'CsvIngestStrategy',
    PipelineBatchSignalTest: 'PipelineConsignmentSignalTest',
    PipelineBatchSignal: 'PipelineConsignmentSignal',
    // the unambiguous ten, plus the test classes that follow their subjects
    BatchProcessingException: 'ConsignmentProcessingException',
    BatchIngestStrategy: 'ConsignmentIngestStrategy',
    BatchGraphRunnerLiftEngagementTest: 'ConsignmentGraphRunnerLiftEngagementTest',
    BatchGraphRunnerFinalizeTest: 'ConsignmentGraphRunnerFinalizeTest',
    BatchGraphRunnerTest: 'ConsignmentGraphRunnerTest',
    BatchGraphRunner: 'ConsignmentGraphRunner',
    BatchAuditWriterTest: 'ConsignmentAuditWriterTest',
    BatchAuditWriter: 'ConsignmentAuditWriter',
    BatchAuditReport: 'ConsignmentAuditReport',
    BatchEventBusTest: 'ConsignmentEventBusTest',
    BatchEventBus: 'ConsignmentEventBus',
    BatchEvent: 'ConsignmentEvent',
    BatchManifest: 'ConsignmentManifest',
    BatchProvenanceTest: 'ConsignmentProvenanceTest',
    BatchRecordsTest: 'ConsignmentRecordsTest',
    BatchRow: 'ConsignmentRow',
    Batch: 'Consignment',
};

const keys = Object.keys(MAP).sort((a, b) => b.length - a.length);
const RE = new RegExp(`\\b(${keys.join('|')})\\b`, 'g');

const git = (...args) => execFileSync('git', args, { encoding: 'utf8' }).trim();

/** Every tracked .java file. The sweep is repo-wide on purpose: a missed reference does not compile. */
const files = git('ls-files', '*.java').split('\n').filter(Boolean);

// ── 1. rename the files whose basename carries a mapped type ────────────────────────────────────────
let renamed = 0;
for (const f of files) {
    const base = f.slice(f.lastIndexOf('/') + 1, -'.java'.length);
    const to = MAP[base];
    if (!to) continue;
    const target = `${f.slice(0, f.lastIndexOf('/') + 1)}${to}.java`;
    console.log(`rename  ${base}.java -> ${to}.java`);
    if (!DRY) git('mv', f, target);
    renamed++;
}

// ── 2. rewrite identifiers everywhere (re-list: step 1 moved paths) ─────────────────────────────────
let touched = 0;
let occurrences = 0;
for (const f of DRY ? files : git('ls-files', '*.java').split('\n').filter(Boolean)) {
    const src = readFileSync(f, 'utf8');
    let n = 0;
    const out = src.replace(RE, (m) => {
        n++;
        return MAP[m];
    });
    if (!n) continue;
    occurrences += n;
    touched++;
    if (!DRY) writeFileSync(f, out);
}

console.log(
    `\n${DRY ? '[dry-run] would rename' : 'renamed'} ${renamed} file(s); ` +
        `${DRY ? 'would rewrite' : 'rewrote'} ${occurrences} occurrence(s) across ${touched} file(s).`,
);
console.log(
    '\nNext: regenerate BOTH committed contracts, then `node tools/check-vocabulary.mjs`, then the FULL' +
        ' reactor (`mvn -o clean test`, baseline 3841/0/0/5). Update docs/GLOSSARY.md by hand — this' +
        ' script deliberately does not sweep docs.',
);
