#!/usr/bin/env node
// OPENAPI-SKELETON-SUMMARIES-1 (2026-11-17) - every public operation in docs/api/openapi-v1.json carries a real summary.
// OpenApiPathsContractTest gives a live route with no operation a generated skeleton: the summary
// "<METHOD> <path> (generated skeleton)" and a stock description. That keeps the contract structurally complete but
// tells a caller nothing. This guard fails when an operation still carries either marker, unless it is in ALLOWED below.
// ALLOWED is a ratchet: an entry whose operation is gone or now has a real summary also fails, so the list only shrinks.
// "Public" = every /api/v1 operation registered by shipped (main) code: the server has no internal or UI-only route
// class, and GET /openapi.json serves the whole document. A route registered only by test code is not public.
// Usage: node tools/check-openapi-summaries.mjs     (OPENAPI_SUMMARIES_ROOT re-roots it for the negative fixtures)
// Fix:   write the summary and description in the operation's openapi.fragment.json, then node tools/openapi-merge.mjs --write
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const METHODS = ['get', 'put', 'post', 'delete', 'patch'];
export const SKELETON_SUMMARY = '(generated skeleton)';
export const STOCK_DESCRIPTION = 'Generated from the live route table by OpenApiPathsContractTest';

/** Operations allowed to stay skeletons, each with its reason. Only remove entries; never add a public route. */
export const ALLOWED = new Map([
  ['GET /test-discovered/ping', 'registered only by the test RouteModule TestDiscoveredRoutes (inspecto/src/test), '
    + 'which OpenApiPathsContractTest sees on its classpath; not a shipped route'],
]);

/** Problems in the parsed document: skeleton operations not allowed, and allow-list entries that are stale. */
export function problems(doc, allowed = ALLOWED) {
  const out = [];
  const skeletons = new Set();
  for (const [path, item] of Object.entries(doc.paths ?? {})) {
    for (const m of METHODS) {
      const op = item?.[m];
      if (!op) continue;
      const key = `${m.toUpperCase()} ${path}`;
      const summary = String(op.summary ?? '').trim();
      const skeleton = !summary || summary.endsWith(SKELETON_SUMMARY) || String(op.description ?? '').includes(STOCK_DESCRIPTION);
      if (!skeleton) continue;
      skeletons.add(key);
      if (!allowed.has(key)) out.push(`${key} has no real summary (summary "${summary}") - write its summary and description in its openapi.fragment.json`);
    }
  }
  for (const key of allowed.keys())
    if (!skeletons.has(key)) out.push(`${key} is in ALLOWED but is no longer a skeleton (or is gone) - remove it from ALLOWED`);
  return { out, skeletons: skeletons.size };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const root = process.env.OPENAPI_SUMMARIES_ROOT ?? join(dirname(fileURLToPath(import.meta.url)), '..');
  const doc = JSON.parse(readFileSync(join(root, 'docs/api/openapi-v1.json'), 'utf8'));
  const { out, skeletons } = problems(doc);
  if (out.length) {
    console.error(`✗ OpenAPI summaries guard: ${out.length} problem(s)\n  ${out.join('\n  ')}`);
    process.exit(1);
  }
  console.log(`✓ OpenAPI summaries: every public operation has a real summary (${skeletons} allow-listed skeleton(s) left)`);
}
