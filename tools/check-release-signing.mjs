#!/usr/bin/env node
// MODULE-REORG-1 P3h: static guard for the per-jar signing wiring in .github/workflows/release.yml (text level, no YAML lib).
//   node tools/check-release-signing.mjs [path/to/release.yml]
// Asserts: signing is opt-in (HAS_JARSIGN job env built from the three secrets); every package.ps1 call ends in $SIGN_ARGS and
// its StorePass secret arrives via env:; no jar-signing secret is interpolated inside a run: block; the decode step, the zip
// verify step and the cleanup step are each guarded by HAS_JARSIGN; cleanup is always() and removes the keystore file.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export function check(text) {
    const problems = [];
    const lines = text.replace(/\r\n/g, '\n').split('\n');
    const guard = "env.HAS_JARSIGN == 'true'";
    if (!/^ {6}HAS_JARSIGN: \$\{\{ secrets\.JARSIGN_KEYSTORE_B64 != '' && secrets\.INSPECTO_JARSIGN_STOREPASS != '' && secrets\.JARSIGN_ALIAS != '' \}\}/m.test(lines.join('\n')))
        problems.push('job env HAS_JARSIGN must require all three secrets');
    // split into steps
    const steps = []; let cur = null;
    for (const l of lines) {
        if (/^ {6}- (name|uses):/.test(l)) { cur = { lines: [] }; steps.push(cur); }
        if (cur) cur.lines.push(l);
    }
    const stepText = (s) => s.lines.join('\n');
    const named = (re) => steps.find((s) => re.test(s.lines[0]));
    // run: bodies must not interpolate jar-signing secrets
    for (const s of steps) {
        const t = stepText(s); const i = t.search(/^ {8}run:/m);
        if (i >= 0 && /\$\{\{\s*secrets\.(JARSIGN|INSPECTO_JARSIGN)/.test(t.slice(i))) problems.push('secret interpolated in run: of ' + s.lines[0].trim());
    }
    const pk = steps.filter((s) => s.lines.some((l) => /^ {8}run: .*package\.ps1/.test(l)));
    if (pk.length !== 3) problems.push('expected 3 package.ps1 steps, found ' + pk.length);
    for (const s of pk) {
        const run = s.lines.find((l) => /^ {8}run: .*package\.ps1/.test(l)) || '';
        if (!/ \$SIGN_ARGS$/.test(run)) problems.push('package.ps1 call does not end in $SIGN_ARGS: ' + s.lines[0].trim());
        // removing the suffix must leave the pre-wiring command line
        if (/-SignJars|-JarKeystore/.test(run)) problems.push('signing flags hard-coded in ' + s.lines[0].trim());
        if (!/INSPECTO_JARSIGN_STOREPASS: \$\{\{ secrets\.INSPECTO_JARSIGN_STOREPASS \}\}/.test(stepText(s))) problems.push('StorePass not passed via env: in ' + s.lines[0].trim());
    }
    const dec = named(/Decode jar-signing keystore/);
    if (!dec || !stepText(dec).includes(guard)) problems.push('decode step missing or not guarded by HAS_JARSIGN');
    else {
        const t = stepText(dec);
        if (!/JARSIGN_KEYSTORE_B64: \$\{\{ secrets\.JARSIGN_KEYSTORE_B64 \}\}/.test(t)) problems.push('keystore secret not passed through env:');
        if (!/chmod 600/.test(t) || !/SIGN_ARGS=/.test(t)) problems.push('decode step must chmod 600 and write SIGN_ARGS');
    }
    if (steps.some((s) => s !== dec && /SIGN_ARGS=/.test(stepText(s)))) problems.push('SIGN_ARGS set outside the guarded decode step');
    const ver = named(/Verify jar signatures/);
    if (!ver || !stepText(ver).includes(guard) || !/check-jar-signatures\.mjs/.test(stepText(ver))) problems.push('verify step missing, unguarded or not running check-jar-signatures');
    const clean = named(/Remove jar-signing keystore/);
    if (!clean || !/always\(\)/.test(stepText(clean)) || !stepText(clean).includes(guard) || !/rm -f "\$RUNNER_TEMP\/jarsign\.p12"/.test(stepText(clean))) problems.push('cleanup step missing or not always()+guarded+rm -f');
    return problems;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
    const p = process.argv[2] || '.github/workflows/release.yml';
    const problems = check(readFileSync(p, 'utf8'));
    if (problems.length) { console.error('check-release-signing FAILED:\n - ' + problems.join('\n - ')); process.exit(1); }
    console.log('check-release-signing OK');
}
