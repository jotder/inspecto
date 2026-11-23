// node --test tools/la-video-mix.test.mjs - the seams between the case-film renderer and the audio stack.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { beatFor, industryOf, familyOf, mixPlan, masterFilmAudio } from './la-video-mix.mjs';
import { measureLoudness, audioGate } from './la-video-audio.mjs';
import { ffmpegPath } from './la-video-voice.mjs';

test('the first scene is the cold open, the last the close, a reveal scene is marked, an explicit Beat wins', () => {
  const sc = (title, beat) => ({ title, beat });
  assert.equal(beatFor(sc('The cold open'), 0, 10), 'cold');
  assert.equal(beatFor(sc('The reveal'), 5, 10), 'reveal');
  assert.equal(beatFor(sc('What we found'), 5, 10), 'reveal');
  assert.equal(beatFor(sc('The data'), 3, 10), 'steady');
  assert.equal(beatFor(sc('The case file'), 9, 10), 'close');
  assert.equal(beatFor(sc('The data', 'cold'), 3, 10), 'cold');
  assert.equal(beatFor(sc('The reveal', 'nonsense'), 5, 10), 'reveal');   // an unknown Beat is ignored
});

test('a film name gives its industry and its bed family', () => {
  assert.equal(industryOf('Telecom_SimSwap'), 'Telecom');
  assert.equal(familyOf('Telecom_SimSwap'), 'telecom');
  assert.equal(familyOf('Banking_CardCompromise'), 'finance');
  assert.equal(familyOf('Unknown_Case'), 'enterprise');
});

test('the mix plan puts the bed windows on the cards and the ducking windows on the narration, in film time', () => {
  const plan = mixPlan([
    { t0: 0, length: 20, voiceStart: 0.35, voiceLen: 15, introSecs: 4, ticks: [] },
    { t0: 20, length: 30, voiceStart: 20.35, voiceLen: 25, ticks: [22.5, 30] },
    { t0: 50, length: 25, voiceStart: 50.35, voiceLen: 14, casefile: true, ticks: [] },
    { t0: 75, length: 6, voiceStart: 75, voiceLen: 0, cardSecs: 5 },
  ]);
  assert.deepEqual(plan.cards, [{ from: 0, to: 4 }, { from: 67, to: 75 }, { from: 75, to: 80 }]);
  assert.deepEqual(plan.voiceWindows, [{ from: 0.35, to: 15.35 }, { from: 20.35, to: 45.35 }, { from: 50.35, to: 64.35 }]);
  assert.deepEqual(plan.ticks, [22.5, 30]);
});

test('the audio stage masters a synthetic film to -16 LUFS, keeps the chapters and passes the gate', { timeout: 120000 }, () => {
  const ff = ffmpegPath();
  const dir = mkdtempSync(join(tmpdir(), 'la-mix-'));
  try {
    const film = join(dir, 'film.mp4'), out = join(dir, 'final.mp4');
    const mk = spawnSync(ff, ['-y', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'color=c=0x101820:s=320x180:d=14:r=15',
      '-f', 'lavfi', '-i', 'anoisesrc=color=pink:amplitude=0.25:duration=14:seed=7,bandpass=f=1200:w=900,tremolo=f=4:d=0.8',
      '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-shortest', film], { encoding: 'utf8' });
    assert.equal(mk.status, 0, mk.stderr);
    const plan = mixPlan([{ t0: 0, length: 14, voiceStart: 0.35, voiceLen: 12.5, introSecs: 3, ticks: [2, 6] }]);
    masterFilmAudio({ film, out, work: dir, seconds: 14, family: 'telecom', plan, ui: true });
    assert.ok(existsSync(out));
    const m = measureLoudness(out);
    assert.ok(Math.abs(m.lufs - -16) <= 1, `loudness ${m.lufs}`);
    assert.ok(m.truePeak <= -1.5, `true peak ${m.truePeak}`);
    const gate = audioGate(out, { cards: plan.cards });
    assert.ok(gate.ok, gate.problems.join('; '));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
