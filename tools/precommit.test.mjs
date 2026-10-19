// Fixture test for tools/precommit.mjs. Run: node --test tools/precommit.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { guardCommands, stepEnv } from './precommit.mjs';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

test('stepEnv drops git repository variables and keeps everything else', () => {
    const env = stepEnv({ PATH: 'p', GIT_DIR: 'd', GIT_INDEX_FILE: 'i', GIT_WORK_TREE: 'w', git_dir: 'x', GIT_SSH_COMMAND: 's', HOME: 'h' });
    assert.deepEqual(Object.keys(env).sort(), ['GIT_SSH_COMMAND', 'HOME', 'PATH']);
});

test('a guard fixture run from a WORKTREE hook cannot rewrite the real repo config (and the raw env would)', () => {
    const base = mkdtempSync(join(tmpdir(), 'precommit-env-'));
    const g = (cwd, ...a) => execFileSync('git', a, { cwd, encoding: 'utf8', env: stepEnv(process.env) }).trim();
    try {
        const main = join(base, 'main'), wt = join(base, 'wt');
        mkdirSync(main);
        g(main, 'init', '-q'); g(main, 'config', 'user.name', 't'); g(main, 'config', 'user.email', 't@x');
        g(main, 'commit', '-q', '--allow-empty', '-m', 'init');
        g(main, 'worktree', 'add', '-q', wt);
        const gitDir = g(wt, 'rev-parse', '--absolute-git-dir');
        // what git exports to a hook in a worktree
        const hookEnv = { ...process.env, GIT_DIR: gitDir, GIT_INDEX_FILE: join(gitDir, 'index') };
        const fixture = (env) => {
            const scratch = mkdtempSync(join(base, 'fixture-'));
            spawnSync('git', ['init', '-q'], { cwd: scratch, env });
            spawnSync('git', ['config', 'user.name', 'guard'], { cwd: scratch, env });
        };
        const before = g(main, 'config', '--list', '--local');
        fixture(stepEnv(hookEnv));
        assert.equal(g(main, 'config', '--list', '--local'), before, 'stepEnv must isolate the fixture');
        fixture(hookEnv);
        assert.notEqual(g(main, 'config', '--list', '--local'), before, 'probe: the raw hook env DOES leak (else this test proves nothing)');
    } finally { rmSync(base, { recursive: true, force: true }); }
});

const YML = `jobs:
  guards:
    runs-on: ubuntu-latest
    steps:
      - name: A
        run: node tools/check-a.mjs
      - name: multi-line steps are not node one-liners
        run: |
          echo hi
      - name: CI-only input
        run: node tools/fetch-duckdb-extensions.mjs --out "$RUNNER_TEMP/x"
      - name: B
        run: node tools/check-b.mjs && node --test tools/check-b.test.mjs
  test:
    steps:
      - run: node tools/not-a-guard.mjs
`;

test('reads only the guards job, in order, and skips CI-only steps', () => {
    const { run, skipped } = guardCommands(YML);
    assert.deepEqual(run, ['node tools/check-a.mjs', 'node tools/check-b.mjs && node --test tools/check-b.test.mjs']);
    assert.equal(skipped.length, 1);
    assert.ok(!run.some((c) => c.includes('not-a-guard')));
});

test('a workflow without a guards job is an error, not a silent pass', () => {
    assert.throws(() => guardCommands('jobs:\n  test:\n    steps: []\n'), /no `guards:` job/);
});

test('the real ci.yml yields every guards-job node step (no hand-kept subset)', () => {
    const yml = readFileSync('.github/workflows/ci.yml', 'utf8');
    const { run, skipped } = guardCommands(yml);
    const block = yml.slice(yml.indexOf('\n  guards:'), yml.indexOf('\n  test:'));
    const expected = (block.match(/^\s+run:\s+node\s/gm) ?? []).length;
    assert.equal(run.length + skipped.length, expected);
    assert.ok(run.length >= 30, `only ${run.length} guard steps found`);
});
