import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { check } from './check-release-signing.mjs';

const real = readFileSync(new URL('../.github/workflows/release.yml', import.meta.url), 'utf8');

test('release.yml passes', () => assert.deepEqual(check(real), []));
test('unset == unchanged: stripping $SIGN_ARGS restores the original command lines', () => {
    const stripped = real.replace(/ \$SIGN_ARGS$/gm, '');
    assert.ok(!/SIGN_ARGS\n/.test(stripped.split('\n').filter((l) => /package\.ps1/.test(l)).join('\n')));
});
test('missing SIGN_ARGS suffix is caught', () => assert.ok(check(real.replace(' $SIGN_ARGS\n', '\n')).length > 0));
test('inline secret in run: is caught', () => assert.ok(check(real.replace('chmod 600 "$RUNNER_TEMP/jarsign.p12"', 'echo ${{ secrets.JARSIGN_ALIAS }}')).length > 0));
test('unguarded decode is caught', () => assert.ok(check(real.replace("if: ${{ env.HAS_JARSIGN == 'true' }}\n        env:\n          JARSIGN_KEYSTORE_B64", "env:\n          JARSIGN_KEYSTORE_B64")).length > 0));
test('missing always() cleanup is caught', () => assert.ok(check(real.replace('always() && ', '')).length > 0));
