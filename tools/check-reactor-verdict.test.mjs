// tools/check-reactor-verdict.mjs, driven end to end over a synthetic log and surefire reports.
// Run: node --test tools/check-reactor-verdict.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, utimesSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const GUARD = join(dirname(fileURLToPath(import.meta.url)), 'check-reactor-verdict.mjs');

/** A one-module green reactor log ending at `finished` after `total`, and one report written at `reportAt`. */
function run(total, finished, reportAt) {
  const root = mkdtempSync(join(tmpdir(), 'verdict-'));
  try {
    const log = [
      '[INFO] Reactor Build Order:',
      '[INFO] ',
      '[INFO] demo-module                                                        [jar]',
      '[INFO] ------------------------------------------------------------------------',
      '[INFO] Reactor Summary for demo 1.0:',
      '[INFO] ',
      '[INFO] demo-module 1.0 ................................... SUCCESS [  1.000 s]',
      '[INFO] ------------------------------------------------------------------------',
      '[INFO] BUILD SUCCESS',
      '[INFO] ------------------------------------------------------------------------',
      `[INFO] Total time:  ${total}`,
      `[INFO] Finished at: ${finished}`,
      '',
    ].join('\n');
    const logPath = join(root, 'build.log');
    writeFileSync(logPath, log);
    const dir = join(root, 'demo-module', 'target', 'surefire-reports');
    mkdirSync(dir, { recursive: true });
    const report = join(dir, 'com.demo.DemoTest.txt');
    writeFileSync(report, 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\n');
    const t = new Date(reportAt);
    utimesSync(report, t, t);
    const r = spawnSync(process.execPath, [GUARD, logPath, '--root', root], { encoding: 'utf8' });
    return { status: r.status, out: r.stdout + r.stderr };
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

test('an hour-long run prints hh:mm h: a report written in its dropped seconds is fresh', () => {
  // 01:13 h may be up to 1:13:59; finished 15:14:40, so the run may have started at 14:00:41.
  const r = run('01:13 h', '2026-11-09T15:14:40+05:30', '2026-11-09T14:01:00+05:30');
  assert.equal(r.status, 0, r.out);
});

test('a report older than even the widest window is still stale', () => {
  const r = run('01:13 h', '2026-11-09T15:14:40+05:30', '2026-11-09T13:59:00+05:30');
  assert.equal(r.status, 1, r.out);
  assert.match(r.out, /PREDATE this build/);
});

test('a minutes-long run keeps whole-second precision: no extra slack', () => {
  // 02:00 min, finished 10:02:00 -> started 10:00:00 (2 s clock slack); a report at 09:59:30 is stale.
  const r = run('02:00 min', '2026-11-09T10:02:00+05:30', '2026-11-09T09:59:30+05:30');
  assert.equal(r.status, 1, r.out);
  const ok = run('02:00 min', '2026-11-09T10:02:00+05:30', '2026-11-09T10:00:30+05:30');
  assert.equal(ok.status, 0, ok.out);
});
