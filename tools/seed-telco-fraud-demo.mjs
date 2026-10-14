#!/usr/bin/env node
// Demo bootstrap for the telco-fraud pack (TELCO-FRAUD-DEMO-BOOTSTRAP-1, TELCO-FRAUD-DEMO-DATE-1). File based, no server needed.
//   node tools/seed-telco-fraud-demo.mjs --spaces inspecto-demo/spaces [--today 2026-10-14]
// 1. Writes <spaces>/telco-demo/config/demo-users.toon: a fresh -DemoAuth bundle ships no Space, so the sign-in picker is empty and a
//    Space Template cannot carry demo-users.toon (import-reserved). Sign in as `admin` (admin;super: the template's Alert Rules need
//    canAuthorAlertRules), create the Space "telco-fraud" from the gallery, then run this script AGAIN.
// 2. Once <spaces>/telco-fraud exists: copies the same roster into it and drops the sample feeds into data/inbox/<feed>, every date
//    shifted so the sample day is YESTERDAY (the detection Jobs roll to $yesterday), file names included.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROSTER = `users[4]{id,displayName,title,roles}:
  admin,Demo Admin,Platform administrator,admin;super
  fm.analyst,Demo FM Analyst,Fraud analyst,operations
  fm.manager,Demo FM Manager,Fraud manager,power
  ra.viewer,Demo Viewer,Non-privileged viewer,business
`;
const BASE = Date.UTC(2026, 6, 1);
const DAY = 86400000;
const iso = (ms) => new Date(ms).toISOString().slice(0, 10);

/** Shift every 2026-07-DD in `text` so 2026-07-01 becomes `yesterday` (an ISO date); a day outside July is left alone. */
export function shiftDates(text, yesterday) {
  const delta = Date.parse(yesterday + 'T00:00:00Z') - BASE;
  return text.replace(/2026-07-(\d\d)/g, (m, d) => iso(Date.UTC(2026, 6, +d) + delta))
    .replace(/202607(\d\d)/g, (m, d) => iso(Date.UTC(2026, 6, +d) + delta).replaceAll('-', ''));
}

export function run(spaces, today = iso(Date.now()), template = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'spaces', '_templates', 'telco-fraud')) {
  const boot = path.join(spaces, 'telco-demo', 'config');
  fs.mkdirSync(boot, { recursive: true });
  fs.writeFileSync(path.join(boot, 'demo-users.toon'), ROSTER);
  const space = path.join(spaces, 'telco-fraud');
  if (!fs.existsSync(path.join(space, 'config'))) return { step: 'bootstrap' };
  fs.writeFileSync(path.join(space, 'config', 'demo-users.toon'), ROSTER);
  const yesterday = iso(Date.parse(today + 'T00:00:00Z') - DAY);
  const files = [];
  for (const feed of ['cdr', 'payments', 'subscriber_events']) {
    const from = path.join(template, 'data', 'samples', feed);
    const to = path.join(space, 'data', 'inbox', feed);
    fs.mkdirSync(to, { recursive: true });
    for (const f of fs.readdirSync(from)) {
      const name = shiftDates(f, yesterday);
      fs.writeFileSync(path.join(to, name), shiftDates(fs.readFileSync(path.join(from, f), 'utf8'), yesterday));
      files.push(name);
    }
  }
  return { step: 'seeded', yesterday, files };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const a = process.argv.slice(2);
  const get = (k) => (a.includes(k) ? a[a.indexOf(k) + 1] : undefined);
  if (!get('--spaces')) { console.error('usage: seed-telco-fraud-demo.mjs --spaces <dir> [--today YYYY-MM-DD]'); process.exit(2); }
  const r = run(get('--spaces'), get('--today'));
  console.log(r.step === 'bootstrap'
    ? 'Bootstrap roster written. Sign in as Demo Admin, create the Space "telco-fraud" from the Telecom fraud template, then run this again.'
    : `Seeded ${r.files.join(', ')} (sample day = ${r.yesterday}). The feeds ingest within a poll (60 s); run the Jobs, then Evaluate / wait for 02:30.`);
}
