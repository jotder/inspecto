// Generate docs/api/openapi-v1.json from the core fragment + every module's openapi.fragment.json.
// Usage: node tools/openapi-merge.mjs [--write | --check]    (default / --check: exit 1 when the file differs)
// To add a route: add its entry to YOUR module's fragment, then run this with --write.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadTree, mergeFragments } from './openapi-fragments.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const docFile = join(root, 'docs/api/openapi-v1.json');
const tree = loadTree(root);
const merged = mergeFragments(tree.core, tree.fragments);
if (process.argv.includes('--write')) {
  writeFileSync(docFile, merged);
  console.log(`wrote docs/api/openapi-v1.json (${tree.fragments.length} module fragment(s) + core)`);
} else if (readFileSync(docFile, 'utf8') !== merged) {
  console.error('docs/api/openapi-v1.json is not the merge of the fragments; run: node tools/openapi-merge.mjs --write');
  process.exit(1);
} else console.log('docs/api/openapi-v1.json == merge of the fragments');
