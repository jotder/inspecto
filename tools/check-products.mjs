#!/usr/bin/env node
// check-products — the guard over products/, apps/ and industries/ (PRODUCT-ASSEMBLY-1 PA-1). The rules live in
// tools/products.mjs `checkModel`, shared with tools/assemble.mjs.
//
//   node tools/check-products.mjs [--root .]      exit 1 on any finding

import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkModel, gatherModel } from './products.mjs';

const a = process.argv.slice(2);
const i = a.indexOf('--root');
const root = i >= 0 ? resolve(a[i + 1]) : join(dirname(fileURLToPath(import.meta.url)), '..');
const model = gatherModel(root);
const findings = checkModel(model);
const n = (o) => Object.keys(o).length;
if (findings.length) {
    console.error(`✗ Product guard: ${findings.length} finding(s)`);
    for (const f of findings) console.error(`  - ${f}`);
    process.exit(1);
}
console.log(`✓ Product guard: ${n(model.products)} Product(s), ${n(model.apps)} App(s), ${n(model.industries)} Industry(ies); every template under spaces/_templates is shipped by exactly one path per Product.`);
