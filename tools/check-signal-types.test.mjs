// Fixture test for tools/check-signal-types.mjs: the real repo is green, and each planted hole turns it red.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { check, PENDING } from './check-signal-types.mjs';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');

const PROVIDER = 'core/src/main/java/com/gamma/signal/CoreSignalTypes.java';
const CONSTANTS = 'mod/src/main/java/com/gamma/mod/ModSignals.java';
const providerText = `package com.gamma.signal;
public final class CoreSignalTypes implements SignalTypeProvider {
    public java.util.List<SignalTypeDecl> signalTypes() {
        return java.util.List.of(decl("job.run.started"), decl(ModSignals.MOD_DONE), decl("exchange.*"));
    }
}`;
const constantsText = 'package com.gamma.mod; public final class ModSignals { public static final String MOD_DONE = "mod.done"; }';

/** A git repo holding the provider and constants class above, plus `extra` files. */
function fixture(extra = {}) {
    const dir = mkdtempSync(join(tmpdir(), 'signal-types-'));
    execFileSync('git', ['init', '-q'], { cwd: dir });
    for (const [f, text] of Object.entries({ [PROVIDER]: providerText, [CONSTANTS]: constantsText, ...extra })) {
        mkdirSync(dirname(join(dir, f)), { recursive: true });
        writeFileSync(join(dir, f), text);
    }
    execFileSync('git', ['add', '-A'], { cwd: dir });
    return dir;
}

const run = (dir) => { try { return check(dir); } finally { rmSync(dir, { recursive: true, force: true }); } };
const poster = (body) => ({ 'x/src/main/java/a/Poster.java': `class Poster { void p() { ${body} } }` });

test('this repo is green', () => {
    assert.deepEqual(check(repo), []);
});

test('declared literals, a declared constant and a family member are green', () => {
    assert.deepEqual(run(fixture(poster(
        'ctx.signals().emit("job.run.started", Severity.INFO, m); emit("mod.done", com.gamma.signal.Severity.WARN, m);'
        + ' el.emit(new Signal(null, "exchange.acme.done", null, s, r, null, c, null, null, null, t, p, 1).toEvent());'))), []);
});

test('an undeclared literal in new Signal(, emit( and emitSignal( is refused, with file and line', () => {
    const p = run(fixture(poster(
        'el.emit(new Signal(null, "made.up", null, s, r, null, c, null, null, null, t, p, 1).toEvent());\n'
        + 'ctx.signals().emit("also.made.up", Severity.INFO, m);\nctx.emitSignal("third.made.up", "src", m, null);')));
    assert.equal(p.length, 3, p.join('\n'));
    assert.match(p.join('\n'), /Poster\.java:1: Signal type 'made\.up'/);
    assert.match(p.join('\n'), /Poster\.java:2: Signal type 'also\.made\.up'/);
    assert.match(p.join('\n'), /Poster\.java:3: Signal type 'third\.made\.up'/);
});

test('a fully qualified Signal and an id built by a call are still sites', () => {
    const p = run(fixture(poster(
        'el.emit(new com.gamma.signal.Signal(null, "fq.made.up", null, s, r, null, c, null, null, null, t, p, 1).toEvent());\n'
        + 'el.emit(new Signal(UUID.randomUUID().toString(), "call.made.up", null, s, r, null, c, null, null, null, t, p, 1).toEvent());')));
    assert.equal(p.length, 2, p.join('\n'));
    assert.match(p.join('\n'), /'fq\.made\.up'/);
    assert.match(p.join('\n'), /'call\.made\.up'/);
});

test('a non-Signal emit, a non-dotted literal, a comment and test code are not sites', () => {
    assert.deepEqual(run(fixture({
        ...poster('AuditWriteSignal.emit("jobs_runs.csv", job, e); ctx.signals().emit("{{id}}.completed", Severity.INFO, m);'
            + ' // emit("made.up", Severity.INFO, m)'),
        'x/src/test/java/a/T.java': 'class T { void t() { emit("made.up", Severity.INFO, m); } }',
    })), []);
});

test('a PENDING entry lets its literal through once, and goes red when stale or declared', () => {
    PENDING['mod.pending'] = { file: 'x/src/main/java/a/Poster.java', why: 'fixture' };
    PENDING['mod.done'] = { file: 'x/src/main/java/a/Poster.java', why: 'fixture: already declared' };
    try {
        const p = run(fixture(poster('emit("mod.pending", Severity.INFO, m);')));
        assert.equal(p.length, 1, p.join('\n'));
        assert.match(p[0], /PENDING 'mod\.done' is declared now/);
        const stale = run(fixture(poster('int x = 1;')));
        assert.match(stale.join('\n'), /PENDING 'mod\.pending' is no longer posted/);
    } finally {
        delete PENDING['mod.pending'];
        delete PENDING['mod.done'];
    }
});
