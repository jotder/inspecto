// The seams between the case-film renderer (tools/render-la-demo-video.mjs --case-film) and the audio stack
// (tools/la-video-voice.mjs, tools/la-video-audio.mjs): which pace a scene is narrated at, where the cards and the
// narration sit on the film's clock, and the master/bed/tick stage that turns the concatenated clips into the final audio.
// Plan docs/superpower/2026-11-19-la-case-videos-v2-plan.md §4.1-§4.2. Everything but masterFilmAudio is pure.
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';
import { ffmpegPath } from './la-video-voice.mjs';
import { INDUSTRY_FAMILY, bedFamily, masterArgs, ambientArgs, mixBedArgs, tickArgs, uiSoundEnabled } from './la-video-audio.mjs';

const BEATS = new Set(['cold', 'steady', 'reveal', 'close']);

/** Pace profile of a scene: an explicit "- Beat:" line wins; else the first scene is the cold open, the last the close,
 *  and a scene titled reveal / the find / found holds before its last sentence. */
export function beatFor(scene, index, count) {
  const explicit = String(scene?.beat ?? '').trim().toLowerCase();
  if (BEATS.has(explicit)) return explicit;
  if (index === 0) return 'cold';
  if (index === count - 1) return 'close';
  if (/\b(reveal|the find|found)\b/i.test(scene?.title ?? '')) return 'reveal';
  return 'steady';
}

/** Industry of a film name (Telecom_SimSwap -> Telecom) and its ambient bed family. */
export const industryOf = (name) => String(name || '').split('_')[0];
export const familyOf = (name) => bedFamily(industryOf(name));

/**
 * Where things sit on the film's clock. clips: [{ t0, length, voiceStart, voiceLen, introSecs, cardSecs, casefile, caseSecs, ticks }]
 * (all seconds; ticks are film-clock times of computed impact marks). Returns the card windows (the bed is audible only there),
 * the narration windows (the bed ducks there) and the tick times.
 */
export function mixPlan(clips, { caseSecs = 8 } = {}) {
  const cards = [], voiceWindows = [], ticks = [];
  for (const c of clips) {
    if (c.introSecs) cards.push({ from: +c.t0.toFixed(3), to: +(c.t0 + c.introSecs).toFixed(3) });
    if (c.cardSecs) cards.push({ from: +c.t0.toFixed(3), to: +(c.t0 + c.cardSecs).toFixed(3) });
    if (c.casefile) cards.push({ from: +(c.t0 + Math.max(0, c.length - (c.caseSecs ?? caseSecs))).toFixed(3), to: +(c.t0 + c.length).toFixed(3) });
    if (c.voiceLen > 0) voiceWindows.push({ from: +c.voiceStart.toFixed(3), to: +(c.voiceStart + c.voiceLen).toFixed(3) });
    for (const t of c.ticks ?? []) ticks.push(+t.toFixed(3));
  }
  return { cards, voiceWindows, ticks };
}

function run(args, what) {
  const r = spawnSync(ffmpegPath(), args, { encoding: 'utf8', maxBuffer: 1 << 26 });
  if (r.status !== 0) throw new Error(`${what} failed:\n${(r.stderr || '').split('\n').slice(-20).join('\n')}`);
}

/**
 * The film's audio stage: take the concatenated clips (narration only), master it to -16 LUFS, lay the ambient bed under the
 * card windows with ducking under the narration, add the soft ticks when enabled, and put the result back under the video
 * with the chapters kept. Returns the output file. work: a scratch folder.
 */
export function masterFilmAudio({ film, out, work, seconds, family, plan, ui = true }) {
  const f = (n) => join(work, n);
  run(['-y', '-hide_banner', '-loglevel', 'error', '-i', film, '-vn', '-ac', '1', '-ar', '44100', f('voice_raw.wav')], 'extract the narration');
  run(masterArgs(f('voice_raw.wav'), f('voice_master.wav')), 'master the narration');
  run(ambientArgs(family, Math.ceil(seconds) + 1, f('bed.wav')), 'synthesise the ambient bed');
  run(mixBedArgs({ voice: f('voice_master.wav'), bed: f('bed.wav'), out: f('mix.wav'), cards: plan.cards, voiceWindows: plan.voiceWindows }), 'mix the bed');
  let audio = f('mix.wav');
  if (ui && plan.ticks.length) {
    run(tickArgs(plan.ticks, f('ticks.wav'), Math.ceil(seconds) + 1), 'make the ticks');
    run(['-y', '-hide_banner', '-loglevel', 'error', '-i', f('mix.wav'), '-i', f('ticks.wav'), '-filter_complex', 'amix=inputs=2:duration=first:normalize=0[a]',
      '-map', '[a]', '-ar', '44100', '-ac', '2', f('mix_ticks.wav')], 'add the ticks');
    audio = f('mix_ticks.wav');
  }
  run(['-y', '-hide_banner', '-loglevel', 'error', '-i', film, '-i', audio, '-map', '0:v', '-map', '1:a', '-map_chapters', '0', '-map_metadata', '0',
    '-c:v', 'copy', '-c:a', 'aac', '-b:a', '192k', '-ar', '44100', out], 'put the audio back under the video');
  return out;
}

export { INDUSTRY_FAMILY, uiSoundEnabled };
