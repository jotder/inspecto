#!/usr/bin/env node
/**
 * Generate a Space Template's synthetic corpus into a Space's DATA folder (`SPACES-CONSOLIDATION-1`, D-SC4/D-SC6).
 *
 * The repository keeps a Space's configuration, not its data: a Template ships no corpus, and the corpus is produced
 * here, on implementation, by the Template's own deterministic generator(s) (`tools/gen-*-data.*`), into the folder
 * the Space reads — `<data root>/data`, i.e. `inspecto_spaces/<id>/data` — never into `spaces/`.
 *
 *   node tools/seed-space-data.mjs --list
 *   node tools/seed-space-data.mjs <template> --data <data folder>
 *
 * The generators are idempotent (a file whose bytes are unchanged is left alone) and, with `--out`, delete nothing.
 * Python is required for the `la-*` Templates; a missing interpreter is a named failure, never a silent skip.
 * A Template with no entry here ships hand-written samples or none (`docs/okf/product/module/config/spec-space-data-layout.md`).
 */
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const TOOLS = dirname(fileURLToPath(import.meta.url));

/** Template id → the generator file(s) in tools/ that write its data folder. Checked against the generators by the test. */
export const GENERATORS = {
  'la-audit': ['gen-la-audit-data.py'],
  'la-benefits': ['gen-la-benefits-data.py'],
  'la-card': ['gen-la-card-data.py'],
  'la-claims': ['gen-la-claims-data.py'],
  'la-crypto': ['gen-la-crypto-data.py'],
  'la-health': ['gen-la-health-data.py'],
  'la-insider': ['gen-la-insider-data.py'],
  'la-logistics': ['gen-la-logistics-data.py'],
  'la-mfs': ['gen-la-mfs-data.py'],
  'la-mobile-money': ['gen-la-mm-data.py'],
  'la-ownership': ['gen-la-ownership-data.py'],
  'la-police': ['gen-la-police-data.py'],
  'la-procurement': ['gen-la-procurement-data.py'],
  'la-showcase': ['gen-la-showcase-data.py', 'gen-la-telecom-data.py'],
  'la-telco-ra': ['gen-la-telco-ra-data.py'],
  'la-trade': ['gen-la-trade-data.py'],
  'la-utility': ['gen-la-utility-data.py'],
  'order-to-cash': ['gen-order-to-cash-data.mjs'],
};

export function interpreter(file) {
  if (file.endsWith('.mjs')) return process.execPath;
  const found = ['python3', 'python'].find((p) => spawnSync(p, ['--version']).status === 0);
  if (!found) throw new Error('Python is required to generate this Template\'s data and none was found (tried python3, python)');
  return found;
}

/** Run every generator of `template` into `dataFolder`; returns what landed. */
export function seed(template, dataFolder) {
  const files = GENERATORS[template];
  if (!files) throw new Error(`'${template}' has no generator: it ships hand-written samples or none. Known: ${Object.keys(GENERATORS).join(', ')}`);
  const out = resolve(dataFolder);
  mkdirSync(out, { recursive: true });
  for (const f of files) {
    const run = spawnSync(interpreter(f), [join(TOOLS, f), '--out', out], { encoding: 'utf8' });
    if (run.status !== 0) throw new Error(`${f} failed (exit ${run.status}):\n${(run.stderr || run.stdout || '').trim()}`);
  }
  let n = 0, bytes = 0;
  (function walk(d) {
    for (const e of readdirSync(d, { withFileTypes: true })) {
      const p = join(d, e.name);
      if (e.isDirectory()) walk(p); else if (!e.name.startsWith('.')) { n++; bytes += statSync(p).size; }
    }
  })(out);
  return { template, dataFolder: out, files: n, bytes };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  try {
    if (args.includes('--list') || args.length === 0) {
      for (const [t, f] of Object.entries(GENERATORS)) console.log(`${t.padEnd(18)} ${f.join(', ')}`);
    } else {
      const at = args.indexOf('--data');
      const data = at >= 0 ? args[at + 1] : undefined;
      const template = args.find((a, i) => !a.startsWith('--') && i !== at + 1);
      if (!template || !data) throw new Error('usage: node tools/seed-space-data.mjs <template> --data <data folder>   |   --list');
      if (!existsSync(dirname(resolve(data)))) throw new Error(`the data root ${dirname(resolve(data))} does not exist: create or mount it first`);
      const r = seed(template, data);
      console.log(`${r.template}: ${r.files} files, ${r.bytes} bytes in ${r.dataFolder}`);
    }
  } catch (e) {
    console.error(e.message);
    process.exit(1);
  }
}
