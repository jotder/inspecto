import { test } from 'node:test';
import assert from 'node:assert/strict';
import { check, mentions } from './check-doc-flags.mjs';

const java = { 'A.java': 'static final String P = "inspecto.real"; Integer.getInteger("control.port", 8080);\n'
    + 'String g(String k) { return System.getProperty("auth.oidc." + k); } g("issuer");\n'
    + 'System.setProperty("ingest.retry.max", "5");' };
const xmls = ['<logger level="${ui.static.log:-INFO}"/>'];
const ps1 = ['param([switch]$DemoAuth)'];
const run = (doc) => check({ docs: { 'd.md': doc }, java, xmls, ps1 });

test('a flag no source names fails with its file:line — the probe a read flag would pass', () => {
    assert.deepEqual(run('ok `-Dinspecto.real=1`').failures, []);
    assert.deepEqual(run('line one\nset `-Dinspecto.fake=1`').failures, ['d.md:2  -Dinspecto.fake']);
});

test('each pass reason is counted apart', () => {
    const { failures, counts } = run([
        '`-Dcontrol.port=9`',                // read
        '`-Dauth.oidc.issuer=x`',            // prefix helper "auth.oidc." + "issuer"
        '`-Dingest.retry.*` globals',        // family
        '`-Dui.static.log=DEBUG`',           // logback substitution
        '`-Dfile.encoding=UTF-8 -DskipTests`', // standard
        'package.ps1 -DemoAuth',             // PowerShell switch
        '`-Dold.flag` was removed',          // marked
        'origin -DECLARES-> step',           // edge label, not counted
    ].join('\n'));
    assert.deepEqual(failures, []);
    assert.deepEqual(counts, { read: 3, maven: 1, standard: 2, pwsh: 1, placeholder: 0, marked: 1, total: 8 });
});

test('a family marker does not rescue a name no literal extends', () => {
    assert.deepEqual(run('`-Dnope.*`').failures, ['d.md:1  -Dnope']);
});

test('a flag inside a URL or a longer word is not a mention; trailing prose punctuation is dropped', () => {
    assert.deepEqual(mentions('https://x/2024-07-01-Data-and-AI and foo-Dbar'), []);
    assert.equal(mentions('use -Dcontrol.port.')[0].name, 'control.port');
});
