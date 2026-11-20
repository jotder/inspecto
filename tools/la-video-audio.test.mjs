// node --test tools/la-video-audio.test.mjs - the audio builders and parsers (ffmpeg is run only for the short smoke tests).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { ffmpegPath } from './la-video-voice.mjs';
import {
  TARGET, masterFilter, masterArgs, bedFamily, BED, ambientArgs, bedWindowFilter, duckFilter, mixBedArgs, uiSoundEnabled, tickArgs,
  parseEbur128, parseSilence, gateVerdict, measureLoudness, silenceScan,
} from './la-video-audio.mjs';

test('the master chain: high-pass 80 Hz, de-esser, warm tone, loudness -16 LUFS / -1.5 dB true peak', () => {
  const f = masterFilter();
  assert.ok(f.startsWith('highpass=f=80,deesser='));
  assert.ok(f.includes('equalizer=f=180'));
  assert.ok(f.endsWith('loudnorm=I=-16:TP=-1.5:LRA=9'));
  assert.equal(f.split('loudnorm').length - 1, 1, 'the training series -18 LUFS stage is not repeated');
  assert.ok(masterFilter({ lufs: -18 }).endsWith('loudnorm=I=-18:TP=-1.5:LRA=9'));
  const a = masterArgs('in.wav', 'out.wav');
  assert.deepEqual([a.at(-3), a.at(-2), a.at(-1)], ['-ac', '2', 'out.wav']);
});

test('industry names map to the five bed families', () => {
  assert.equal(bedFamily('Telecom'), 'telecom');
  assert.equal(bedFamily('Banking'), 'finance');
  assert.equal(bedFamily('LawEnforcement'), 'public');
  assert.equal(bedFamily('PublicSector'), 'public');
  assert.equal(bedFamily('Healthcare'), 'health');
  assert.equal(bedFamily('Insurance'), 'health');
  assert.equal(bedFamily('Logistics'), 'enterprise');
  assert.equal(bedFamily('Utilities'), 'enterprise');
  assert.equal(bedFamily('Unknown'), 'enterprise');
  assert.deepEqual(Object.keys(BED).sort(), ['enterprise', 'finance', 'health', 'public', 'telecom']);
});

test('the ambient bed is built from lavfi layers and loudnormed to -30 LUFS', () => {
  const a = ambientArgs('telecom', 20, 'bed.wav');
  assert.equal(a.filter((x) => x === 'lavfi').length, BED.telecom.partials.length + 1);
  const graph = a[a.indexOf('-filter_complex') + 1];
  assert.ok(graph.includes('anoisesrc') === false, 'noise is an input, not part of the graph');
  assert.ok(graph.includes('loudnorm=I=-30'));
  assert.ok(graph.includes('afade=t=in') && graph.includes('afade=t=out:st=18'));
  assert.equal(a.at(-1), 'bed.wav');
  assert.ok(a.some((x) => String(x).startsWith('anoisesrc=color=pink')));
});

test('gating and ducking filters', () => {
  assert.equal(bedWindowFilter([{ from: 0, to: 4 }, { from: 100, to: 108 }]), "volume=volume=0:enable='not(gt(between(t,0,4)+between(t,100,108),0))'");
  assert.equal(bedWindowFilter([]), "volume=volume=0:enable='not(0)'");
  assert.equal(duckFilter([{ from: 1, to: 3 }]), "volume=volume=-12dB:enable='gt(between(t,1,3),0)'");
  assert.equal(TARGET.duckDb, 12);
  const m = mixBedArgs({ voice: 'v.wav', bed: 'b.wav', out: 'o.wav', cards: [{ from: 0, to: 4 }], voiceWindows: [{ from: 1, to: 3 }] });
  assert.ok(m[m.indexOf('-filter_complex') + 1].includes('amix=inputs=2:duration=first:dropout_transition=0:normalize=0'));
});

test('UI ticks: one input per tick, delayed to its second, switchable', () => {
  assert.equal(uiSoundEnabled(['node', 'x.mjs']), true);
  assert.equal(uiSoundEnabled(['node', 'x.mjs', '--no-ui-sound']), false);
  const a = tickArgs([1.5, 9.25], 't.wav', 20);
  const graph = a[a.indexOf('-filter_complex') + 1];
  assert.ok(graph.includes('adelay=1500|1500') && graph.includes('adelay=9250|9250'));
  assert.ok(graph.includes(`volume=${TARGET.tickGainDb}dB`));
  assert.equal(tickArgs([], 't.wav', 5).at(-1), 't.wav');
});

test('parsers read ffmpeg ebur128 and silencedetect output', () => {
  const eb = `  Integrated loudness:\n    I:         -16.2 LUFS\n    Threshold: -26.3 LUFS\n\n  Loudness range:\n    LRA:         4.1 LU\n\n  True peak:\n    Peak:        -1.7 dBFS\n`;
  assert.deepEqual(parseEbur128(eb), { lufs: -16.2, lra: 4.1, truePeak: -1.7 });
  const sd = `[silencedetect @ 0x1] silence_start: 3.5\n[silencedetect @ 0x1] silence_end: 7.25 | silence_duration: 3.75\n[silencedetect @ 0x1] silence_start: 20.1\n`;
  assert.deepEqual(parseSilence(sd), [{ start: 3.5, end: 7.25, duration: 3.75 }, { start: 20.1, end: null, duration: null }]);
});

test('the gate: loudness, true peak, and silence outside the cards', () => {
  const good = gateVerdict({ lufs: -16.4, truePeak: -2, silences: [] });
  assert.equal(good.ok, true);
  const loud = gateVerdict({ lufs: -13, truePeak: -0.5, silences: [] });
  assert.equal(loud.ok, false);
  assert.equal(loud.problems.length, 2);
  const inCard = gateVerdict({ lufs: -16, truePeak: -2, silences: [{ start: 1, end: 5, duration: 4 }] }, { cards: [{ from: 0, to: 6 }] });
  assert.equal(inCard.ok, true);
  const outside = gateVerdict({ lufs: -16, truePeak: -2, silences: [{ start: 30, end: 34, duration: 4 }] }, { cards: [{ from: 0, to: 6 }] });
  assert.equal(outside.ok, false);
  assert.match(outside.problems[0], /silence of 4 s at 30 s outside a card/);
});

// ffmpeg smoke tests: the builders produce arguments ffmpeg accepts
const FF = (() => { try { return ffmpegPath(); } catch { return null; } })();
const run = (args) => spawnSync(FF, args, { encoding: 'utf8' });

test('ffmpeg accepts the master, bed, mix and tick builders, and the measurement reads the result', { skip: FF ? false : 'ffmpeg not found' }, () => {
  const tmp = mkdtempSync(join(tmpdir(), 'la-audio-test-'));
  try {
    const voice = join(tmp, 'voice.wav'), mastered = join(tmp, 'mastered.wav'), bed = join(tmp, 'bed.wav'), mix = join(tmp, 'mix.wav'), tick = join(tmp, 'tick.wav');
    let r = run(['-y', '-loglevel', 'error', '-f', 'lavfi', '-i', 'sine=frequency=220:duration=6', '-af', 'volume=-20dB', voice]);
    assert.equal(r.status, 0, r.stderr);
    r = run(masterArgs(voice, mastered)); assert.equal(r.status, 0, r.stderr);
    const m = measureLoudness(mastered);
    assert.ok(Math.abs(m.lufs - TARGET.lufs) < 2, `mastered loudness ${m.lufs}`);
    r = run(ambientArgs('finance', 6, bed)); assert.equal(r.status, 0, r.stderr);
    const b = measureLoudness(bed);
    assert.ok(b.lufs < -24 && b.lufs > -36, `bed loudness ${b.lufs}`);
    r = run(mixBedArgs({ voice: mastered, bed, out: mix, cards: [{ from: 0, to: 2 }], voiceWindows: [{ from: 1, to: 2 }] })); assert.equal(r.status, 0, r.stderr);
    r = run(tickArgs([1, 3.5], tick, 6)); assert.equal(r.status, 0, r.stderr);
    assert.ok(existsSync(mix) && existsSync(tick));
    assert.deepEqual(silenceScan(mastered), []);
  } finally { rmSync(tmp, { recursive: true, force: true }); }
});
