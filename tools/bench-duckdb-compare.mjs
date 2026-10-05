#!/usr/bin/env node
// Side-by-side comparison of two tools/bench-duckdb.ps1 runs: node tools/bench-duckdb-compare.mjs a.json b.json
// Per metric: median of each side, % delta (B vs A), verdict. Direction comes from the name suffix:
// _ms = lower is better, _rps/_qps = higher is better. A delta is "within noise" when |delta| <= max(5%, either side's
// spread), spread = (p95 - median) / median; a single-sample side has no spread estimate and is marked "n=1".
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const sorted = (xs) => [...xs].sort((a, b) => a - b);
const p95 = (xs) => { const s = sorted(xs); return s[Math.min(s.length - 1, Math.ceil(0.95 * s.length) - 1)]; };
const median = (xs) => { const s = sorted(xs), m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };
const NOISE_FLOOR_PCT = 5;

export function compare(a, b) {
  const rows = [];
  for (const w of Object.keys(a.workloads)) {
    const wb = b.workloads[w];
    if (!wb) continue;
    for (const [name, xs] of Object.entries(a.workloads[w].metrics)) {
      const ys = wb.metrics[name];
      if (!ys) continue;
      const ma = median(xs), mb = median(ys);
      const spread = (s, m) => (s.length > 1 && m ? ((p95(s) - m) / m) * 100 : 0);
      const delta = ma ? ((mb - ma) / ma) * 100 : NaN;
      const higherBetter = /_(rps|qps)$/.test(name);
      const noise = Math.max(NOISE_FLOOR_PCT, spread(xs, ma), spread(ys, mb));
      let verdict = Math.abs(delta) <= noise ? 'within noise' : (delta > 0) === higherBetter ? 'B better' : 'B worse';
      if (xs.length === 1 || ys.length === 1) verdict += ' (n=1)';
      rows.push({ w, name, ma, mb, delta, verdict });
    }
  }
  return rows;
}

const fmt = (v) => (Math.abs(v) >= 1000 ? v.toLocaleString('en-US', { maximumFractionDigits: 0 }) : v.toFixed(2));

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const [fa, fb] = process.argv.slice(2);
  if (!fa || !fb) { console.error('usage: node tools/bench-duckdb-compare.mjs <a.json> <b.json>'); process.exit(2); }
  const load = (f) => JSON.parse(readFileSync(f, 'utf8').replace(/^﻿/, ''));
  const a = load(fa), b = load(fb);
  console.log(`A: ${a.label}  DuckDB ${a.duckdbVersion}  java ${a.java}  ${a.host}  git ${a.gitHead}`);
  console.log(`B: ${b.label}  DuckDB ${b.duckdbVersion}  java ${b.java}  ${b.host}  git ${b.gitHead}`);
  if (JSON.stringify(a.params) !== JSON.stringify(b.params)) console.log(`WARNING: params differ  A=${JSON.stringify(a.params)}  B=${JSON.stringify(b.params)}`);
  const rows = compare(a, b);
  const head = ['workload', 'metric', 'A median', 'B median', 'delta %', 'verdict'];
  const lines = rows.map((r) => [r.w, r.name, fmt(r.ma), fmt(r.mb), (r.delta >= 0 ? '+' : '') + r.delta.toFixed(1), r.verdict]);
  const wd = head.map((h, i) => Math.max(h.length, ...lines.map((l) => l[i].length)));
  const row = (l) => l.map((c, i) => (i < 2 || i === 5 ? c.padEnd(wd[i]) : c.padStart(wd[i]))).join('  ');
  console.log('\n' + row(head) + '\n' + wd.map((n) => '-'.repeat(n)).join('  '));
  lines.forEach((l) => console.log(row(l)));
  for (const w of Object.keys(a.workloads)) {
    const ra = a.workloads[w].peakRssMb, rb = b.workloads[w]?.peakRssMb;
    if (ra || rb) console.log(`peak RSS ${w}: A ${ra} MB  B ${rb} MB`);
  }
  console.log('\nlower is better for _ms, higher for _rps/_qps; noise = max(5%, either side p95 spread)');
}
