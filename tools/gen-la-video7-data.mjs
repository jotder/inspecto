// The two prepared files of LA video 7, "One graph for any data" (docs/okf/product/module/la-core/brochure-video-scripts.md):
// calls.csv (caller, callee, start_time, duration_s) and transfers.csv (sender_number, receiver_number, time, amount).
// Synthetic and seeded (the same bytes every run); numbers are invented in the 999… range no subscriber can have. They are
// filming props, not a Space's data, so nothing is committed: write them where the filming install can read them.
// Planted: the suspect 99979100001; the number 99979100042 calls the suspect (7 calls planted, one of them loses its callee
// to the mess, so 6 reach the suspect) AND receives 4 transfers from the suspect. Mess: that number written with spaces in transfers.csv; callers with a leading +, blank callees and receivers,
// one repeated row, some start times in a second format (dd/mm/yyyy HH:MM).
// Usage: node tools/gen-la-video7-data.mjs <out dir>          Test: node --test tools/gen-la-video7-data.test.mjs
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const out = process.argv[2];
mkdirSync(out, { recursive: true });
let seed = 20261109;
const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
const pick = (a) => a[Math.floor(rnd() * a.length)];
const num = (n) => `999791${String(n).padStart(5, '0')}`;
const SUSPECT = num(1), LEAD = num(42);
const people = Array.from({ length: 60 }, (_, i) => num(100 + i));
const pad = (n) => String(n).padStart(2, '0');
const iso = (d) => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`;
const alt = (d) => `${pad(d.getUTCDate())}/${pad(d.getUTCMonth() + 1)}/${d.getUTCFullYear()} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
const when = () => new Date(Date.UTC(2026, 9, 1) + Math.floor(rnd() * 28 * 86400000));

// calls.csv
const calls = [];
for (let i = 0; i < 260; i++) { const a = pick(people); let b = pick(people); if (b === a) b = pick(people); calls.push([a, b, when(), 20 + Math.floor(rnd() * 600)]); }
for (let i = 0; i < 18; i++) calls.push([pick(people.slice(0, 12)), SUSPECT, when(), 30 + Math.floor(rnd() * 300)]);   // the suspect's ordinary callers
for (let i = 0; i < 7; i++) calls.push([LEAD, SUSPECT, when(), 40 + Math.floor(rnd() * 200)]);                          // the lead
calls.sort((x, y) => x[2] - y[2]);
const callRows = calls.map(([a, b, t, d], i) => [i % 23 === 5 ? `+${a}` : a, i % 61 === 7 ? '' : b, i % 47 === 3 ? alt(t) : iso(t), d]);
callRows.splice(40, 0, [...callRows[39]]);                                                                                // one repeated row
writeFileSync(join(out, 'calls.csv'), ['caller,callee,start_time,duration_s', ...callRows.map((r) => r.join(','))].join('\n') + '\n');

// transfers.csv
const spaced = (n) => `${n.slice(0, 3)} ${n.slice(3, 7)} ${n.slice(7)}`;
const tx = [];
for (let i = 0; i < 180; i++) { const a = pick(people); let b = pick(people); if (b === a) b = pick(people); tx.push([a, b, when(), (5 + rnd() * 200).toFixed(2)]); }
for (let i = 0; i < 4; i++) tx.push([SUSPECT, spaced(LEAD), when(), (400 + rnd() * 600).toFixed(2)]);                     // the money, to the lead
tx.sort((x, y) => x[2] - y[2]);
const txRows = tx.map(([a, b, t, m], i) => [a, i % 53 === 9 ? '' : b, iso(t), m]);
writeFileSync(join(out, 'transfers.csv'), ['sender_number,receiver_number,time,amount', ...txRows.map((r) => r.join(','))].join('\n') + '\n');
console.log(`calls.csv ${callRows.length} rows, transfers.csv ${txRows.length} rows; suspect ${SUSPECT}, lead ${LEAD} (spaced in transfers: ${spaced(LEAD)})`);
