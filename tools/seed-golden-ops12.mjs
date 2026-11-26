#!/usr/bin/env node
// seed-golden-ops12 — set up the fixture golden task ops-12 needs (GOLDEN-OPS12-FIXTURE-1): a by-day Job whose day waits
// on a Reference that never arrives. The fixture lives in agent-kit/golden/data/ops12/:
//   config/ops12/parcel_scans_*.toon   a stream partitioned by SCAN_DATE (each batch carries its record day)
//   config/ops12/carrier_rates_*.toon  a daily Reference (versions: daily) that is never delivered a file
//   data/inbox/parcel_scans/*.csv      one scan file for 2026-10-01
//   parcel_rates_job.json              the by-day Job: input_stream parcel_scans, input carrier_rates (role reference)
//
// Two phases, because Pipelines load when the server starts:
//   node tools/seed-golden-ops12.mjs files --space-dir <the Space folder>     (before the server starts or restarts)
//   node tools/seed-golden-ops12.mjs live --base <url> --space <id> --token-file <person token>
// `live` needs a person who holds canAuthorWorkbench (it creates the Job through POST /jobs, the save gate) and, only if
// the stream has not run yet, canOperateRuns (it triggers parcel_scans). It waits until GET /jobs/backlog lists the day
// WAITING for carrier_rates and prints the suite's variables: backlogJob=parcel_rates_job,waitingDay=2026-10-01.
// Exit 0 = ready, 1 = the day never showed as waiting, 2 = usage or server error.

import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const FIXTURE = fileURLToPath(new URL('../agent-kit/golden/data/ops12/', import.meta.url));
export const JOB = JSON.parse(readFileSync(join(FIXTURE, 'parcel_rates_job.json'), 'utf8'));
export const STREAM = JOB.input_stream;
export const REFERENCE = Object.keys(JOB).map((k) => k.match(/^inputs\.([^.]+)\.role$/)?.[1]).find(Boolean);
export const WAITING_DAY = '2026-10-01';

/** Phase 1: copy the fixture's config and inbox file into a Space folder; returns the files written (relative). */
export function seedFiles(spaceDir) {
    if (!existsSync(join(spaceDir, 'config'))) throw new Error(`${spaceDir} has no config/ folder: not a Space folder`);
    const written = [];
    for (const top of ['config', 'data']) {
        const walk = (rel) => {
            for (const e of readdirSync(join(FIXTURE, top, rel), { withFileTypes: true })) {
                const r = join(rel, e.name);
                if (e.isDirectory()) walk(r);
                else {
                    mkdirSync(dirname(join(spaceDir, top, r)), { recursive: true });
                    cpSync(join(FIXTURE, top, r), join(spaceDir, top, r));
                    written.push(join(top, r).replace(/\\/g, '/'));
                }
            }
        };
        walk('');
    }
    return written;
}

/** The day this Job waits on, from a GET /jobs/backlog answer, or null. */
export function waitingDayOf(backlog, jobName = JOB.name, reference = REFERENCE) {
    const job = (backlog?.data ?? backlog ?? []).find?.((j) => j.jobName === jobName);
    const day = job?.days?.find((d) => d.status === 'WAITING' && String(d.waitingReason ?? '').includes(`waiting for reference ${reference}`));
    return day?.day ?? null;
}

function args(argv) {
    const a = { _: argv[2] };
    for (let i = 3; i < argv.length; i += 2) a[argv[i].replace(/^--/, '')] = argv[i + 1];
    return a;
}

async function live(a) {
    const token = readFileSync(a['token-file'], 'utf8').trim();
    const base = `${a.base.replace(/\/$/, '')}/api/v1/spaces/${encodeURIComponent(a.space)}`;
    const call = async (m, p, body) => {
        const r = await fetch(base + p, { method: m, body: body && JSON.stringify(body),
            headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' } });
        return { status: r.status, body: await r.json().catch(() => null) };
    };
    const runs = await call('GET', '/runs');
    if (runs.status !== 200) { console.error(`seed-golden-ops12: GET /runs answered ${runs.status}`); return 2; }
    const names = (runs.body?.data ?? []).map((r) => r.name);
    for (const p of [STREAM, REFERENCE]) if (!names.includes(p)) {
        console.error(`seed-golden-ops12: Pipeline '${p}' is not registered - run the files phase, then restart the server`);
        return 2;
    }
    if (((runs.body.data.find((r) => r.name === STREAM) ?? {}).committedBatches ?? 0) === 0) {
        const t = await call('POST', `/runs/${STREAM}/trigger`, {});
        if (t.status >= 300) { console.error(`seed-golden-ops12: trigger ${STREAM} answered ${t.status}`); return 2; }
    }
    const j = await call('POST', '/jobs', JOB);
    if (j.status >= 300 && j.status !== 409) {
        console.error(`seed-golden-ops12: POST /jobs answered ${j.status} ${JSON.stringify(j.body).slice(0, 300)}`);
        return 2;
    }
    for (let i = 0; i < 30; i++) {
        const b = await call('GET', '/jobs/backlog');
        const day = waitingDayOf(b.body);
        if (day) { console.log(`backlogJob=${JOB.name},waitingDay=${day}`); return 0; }
        await new Promise((r) => setTimeout(r, 2000));
    }
    console.error(`seed-golden-ops12: GET /jobs/backlog never listed ${JOB.name} waiting for ${REFERENCE}`);
    return 1;
}

export async function main(argv) {
    const a = args(argv);
    if (a._ === 'files' && a['space-dir']) {
        for (const f of seedFiles(a['space-dir'])) console.log(`wrote ${f}`);
        return 0;
    }
    if (a._ === 'live' && a.base && a.space && a['token-file']) {
        try { return await live(a); } catch (e) { console.error(`seed-golden-ops12: ${e.message}`); return 2; }
    }
    console.error('usage: seed-golden-ops12.mjs files --space-dir <dir> | live --base <url> --space <id> --token-file <file>');
    return 2;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) process.exitCode = await main(process.argv);
