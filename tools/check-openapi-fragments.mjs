// Guard: docs/api/openapi-v1.json is GENERATED from the contract fragments (openapi-fragments.mjs) and nobody
// hand-edits it. Fails when (1) the document is not byte-equal to merge(core + module fragments), (2) a path
// is in two fragments, (3) a fragment's paths do not match its module's manifest provides.routes (or a module
// that declares routes ships no fragment, or core documents a module's route).
// Usage: node tools/check-openapi-fragments.mjs        (OPENAPI_FRAGMENTS_ROOT re-roots it for the negative fixtures)
// Fix:   node tools/openapi-merge.mjs --write         (after editing a fragment — never the document)
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadTree, mergeFragments, ownershipProblems } from './openapi-fragments.mjs';

const root = process.env.OPENAPI_FRAGMENTS_ROOT ?? join(dirname(fileURLToPath(import.meta.url)), '..');
const fail = (m) => { console.error(`✗ OpenAPI fragments guard: ${m}`); process.exit(1); };

const tree = loadTree(root);
const problems = ownershipProblems(tree);
if (problems.length) fail(problems.join('\n  '));
let merged;
try { merged = mergeFragments(tree.core, tree.fragments); } catch (e) { fail(e.message); }
if (readFileSync(join(root, 'docs/api/openapi-v1.json'), 'utf8') !== merged) {
  fail('docs/api/openapi-v1.json is not the merge of the fragments — it was hand-edited or a fragment changed. Run: node tools/openapi-merge.mjs --write');
}
console.log(`✓ OpenAPI fragments: docs/api/openapi-v1.json == core + ${tree.fragments.length} module fragment(s)`);
