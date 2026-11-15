#!/usr/bin/env node
// Generates the two SYNTHETIC files of the Link Analysis full-cycle import test (LA-FULL-CYCLE-TEST-1 phase 3):
//   inspecto/src/test/resources/onboard/full-cycle/transfers.csv   461 rows, events between accounts
//   inspecto/src/test/resources/onboard/full-cycle/accounts.csv    138 rows, details about accounts
// Every value is invented here from a fixed seed; nothing is copied from real data. The four dirty rows of
// transfers.csv are named in DIRTY below and in the README beside the files.
//
//   node tools/gen-la-import-fixture.mjs           write both files
//   node tools/gen-la-import-fixture.mjs --check   exit 1 when the committed files differ from what this writes

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'inspecto', 'src', 'test', 'resources', 'onboard', 'full-cycle');
const TRANSFERS = 461;
const ORDINARY = 135; // ACC-1001 .. ACC-1135
const HUB = 'MULE-HUB-01';
const MERCHANT = 'MERCH-001';
const UNUSED = 'ACC-1999'; // in accounts.csv only: the 1 account of 138 no transfer names

/**
 * The four dirty rows of transfers.csv: each lands on the first ordinary payment at or after row `near`. The README the
 * generator writes beside the files names the rows they landed on.
 */
const DIRTY = [
    { near: 42, what: 'from_account is blank: the row is kept but draws no link', apply: (r) => (r.from = '') },
    { near: 117, what: "booked_at is 'not-a-date': the value is left blank", apply: (r) => (r.at = 'not-a-date') },
    { near: 203, what: "amount is 'abc': the value is left blank", apply: (r) => (r.amount = 'abc') },
    {
        near: 310,
        what: "from_account is ' acc-1003 ' (stray spaces, lower case): read as the account ACC-1003",
        apply: (r) => (r.from = ' acc-1003 '),
    },
];

// mulberry32: a small seeded generator, so the files are the same on every run
function prng(seed) {
    let a = seed >>> 0;
    return () => {
        a = (a + 0x6d2b79f5) >>> 0;
        let t = a;
        t = Math.imul(t ^ (t >>> 15), t | 1);
        t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}
const rnd = prng(20261115);
const pick = (list) => list[Math.floor(rnd() * list.length)];
const between = (lo, hi) => lo + Math.floor(rnd() * (hi - lo + 1));
const pad = (n, w) => String(n).padStart(w, '0');
const acc = (i) => `ACC-${1000 + i}`;

const CHANNELS = ['mobile', 'online', 'branch', 'atm'];
const CITIES = ['Northport', 'Eastvale', 'Southmere', 'Westbrook', 'Midhaven', 'Oakridge', 'Riverton', 'Stonefield'];
const TYPES = ['personal', 'personal', 'personal', 'business'];

function timestamp(dayOfYear, minuteOfDay) {
    const d = new Date(Date.UTC(2026, 0, 1 + dayOfYear, 0, minuteOfDay, between(0, 59)));
    return d.toISOString().slice(0, 19); // 2026-03-01T10:15:42
}
const money = (cents) => `${Math.floor(cents / 100)}.${pad(cents % 100, 2)}`;

function transfers() {
    const rows = [];
    const add = (from, to, cents, day, channel) => rows.push({ from, to, cents, day, minute: between(0, 1439), channel });
    // the mule chain: six sources pay twelve layer accounts, which pay the hub, which pays four cash-out accounts
    const sources = [1, 2, 3, 4, 5, 6].map(acc);
    const layer = Array.from({ length: 12 }, (_, i) => acc(10 + i));
    const cashOut = [30, 31, 32, 33].map(acc);
    for (let i = 0; i < 36; i++) add(pick(sources), layer[i % layer.length], between(180000, 950000), 60 + (i % 9), 'online');
    for (let i = 0; i < 48; i++) add(layer[i % layer.length], HUB, between(150000, 900000), 62 + (i % 9), 'mobile');
    for (let i = 0; i < 28; i++) add(HUB, cashOut[i % cashOut.length], between(400000, 1900000), 64 + (i % 9), 'atm');
    // the busy merchant: many small payments from many accounts
    for (let i = 0; i < 120; i++)
        add(acc(1 + ((i * 7) % ORDINARY)), MERCHANT, between(450, 9900), between(0, 119), pick(['mobile', 'online']));
    // every ordinary account pays one other at least once, so all of them appear in the file
    for (let i = 1; i <= ORDINARY; i++)
        add(acc(i), acc(1 + ((i * 11 + 3) % ORDINARY)), between(1000, 250000), between(0, 119), pick(CHANNELS));
    // the rest: ordinary payments
    while (rows.length < TRANSFERS) {
        const from = between(1, ORDINARY);
        let to = between(1, ORDINARY);
        if (to === from) to = 1 + (to % ORDINARY);
        add(acc(from), acc(to), between(1000, 250000), between(0, 119), pick(CHANNELS));
    }
    rows.sort((a, b) => a.day - b.day || a.minute - b.minute);
    const out = rows.map((r, i) => ({
        id: `T${pad(i + 1, 4)}`,
        from: r.from,
        to: r.to,
        amount: money(r.cents),
        at: timestamp(r.day, r.minute),
        channel: r.channel,
    }));
    // the four dirty rows: each is the first ordinary payment at or after its place, so the chain and the merchant stay whole
    const chain = new Set([HUB, MERCHANT, ...sources, ...layer, ...cashOut]);
    const dirty = [];
    for (const d of DIRTY) {
        let i = d.near - 1;
        while (chain.has(out[i].from) || chain.has(out[i].to) || dirty.some((x) => x.id === out[i].id)) i++;
        d.apply(out[i]);
        dirty.push({ id: out[i].id, line: i + 2, what: d.what });
    }
    return { rows: out, dirty };
}

function accounts() {
    const rows = [];
    const add = (id, type) =>
        rows.push({
            id,
            holder: `Sample Holder ${pad(rows.length + 1, 3)}`,
            city: pick(CITIES),
            opened: new Date(Date.UTC(2019 + between(0, 6), between(0, 11), between(1, 28))).toISOString().slice(0, 10),
            type,
        });
    for (let i = 1; i <= ORDINARY; i++) add(acc(i), pick(TYPES));
    add(HUB, 'personal');
    add(MERCHANT, 'merchant');
    add(UNUSED, 'personal');
    return rows;
}

function csv(header, rows) {
    return [header.join(','), ...rows.map((r) => r.join(','))].join('\n') + '\n';
}

export function build() {
    const { rows: t, dirty } = transfers();
    const a = accounts();
    const named = new Set();
    for (const r of t) for (const v of [r.from, r.to]) if (v.trim()) named.add(v.trim().toUpperCase());
    const present = a.filter((r) => named.has(r.id)).length;
    if (t.length !== TRANSFERS || a.length !== 138 || present !== 137)
        throw new Error(
            `fixture shape drifted: ${t.length} transfers, ${a.length} accounts, ${present} of them named by a transfer`,
        );
    return {
        'transfers.csv': csv(
            ['transfer_id', 'from_account', 'to_account', 'amount', 'booked_at', 'channel'],
            t.map((r) => [r.id, r.from, r.to, r.amount, r.at, r.channel]),
        ),
        'accounts.csv': csv(
            ['account_id', 'holder_name', 'city', 'opened_on', 'type'],
            a.map((r) => [r.id, r.holder, r.city, r.opened, r.type]),
        ),
        'README.md': readme(dirty),
    };
}

function readme(dirty) {
    return [
        '# Full-cycle import fixture (synthetic)',
        '',
        'Written by `node tools/gen-la-import-fixture.mjs` from a fixed seed; every value is invented. Do not edit by hand:',
        'change the generator and run it again (`--check` fails when these files differ from what it writes).',
        '',
        '- `transfers.csv`: 461 events between accounts. A mule chain runs through `MULE-HUB-01` (six sources, twelve layer',
        '  accounts, four cash-out accounts) and `MERCH-001` is one busy merchant.',
        '- `accounts.csv`: 138 accounts. 137 of them are named by a transfer; `ACC-1999` is not.',
        '',
        '## The four dirty rows of `transfers.csv`',
        '',
        '| transfer_id | File line | What is wrong |',
        '|---|---|---|',
        ...dirty.map((d) => `| ${d.id} | ${d.line} | ${d.what} |`),
        '',
        'Used by `ControlApiOnboardFullCycleTest` (LA-FULL-CYCLE-TEST-1 phase 3).',
        '',
    ].join('\n');
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
    const files = build();
    if (process.argv.includes('--check')) {
        let stale = 0;
        for (const [name, text] of Object.entries(files)) {
            let onDisk = null;
            try {
                onDisk = readFileSync(join(OUT, name), 'utf8');
            } catch {
                // missing counts as stale
            }
            if (onDisk !== text) {
                console.error(`${name} differs from the generator: run node tools/gen-la-import-fixture.mjs`);
                stale++;
            }
        }
        process.exit(stale ? 1 : 0);
    }
    mkdirSync(OUT, { recursive: true });
    for (const [name, text] of Object.entries(files)) writeFileSync(join(OUT, name), text);
    console.log(`wrote ${Object.keys(files).join(', ')} to ${OUT}`);
}
