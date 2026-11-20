// The audio stack of the Inspecto LA case films v2 (plan docs/superpower/2026-11-19-la-case-videos-v2-plan.md §4.2).
// Pure builders: each returns an ffmpeg filter string or an argument list, so they are tested without running ffmpeg;
// only the measurement helpers (measureLoudness, silenceScan, audioGate) run it. The renderer
// (tools/render-la-demo-video.mjs) imports them; the export names below are the contract, keep them stable.
//
//   TARGET                         { lufs: -16, truePeak: -1.5, bedLufs: -30, duckDb: 12, tickGainDb: -31, silenceMax: 2.5 }
//   masterFilter(opts)             the film master chain: high-pass 80 Hz, de-esser 6-8 kHz, the warm tone of D-TR1, loudnorm to -16 LUFS / -1.5 dB TP
//   masterArgs(inFile, outFile)    ffmpeg arguments that apply masterFilter, 44.1 kHz stereo
//   INDUSTRY_FAMILY, bedFamily(industry)   industry name (Telecom, Banking, ...) -> one of five bed families
//   BED                            the parameter set of each family (telecom, finance, public, health, enterprise)
//   ambientArgs(family, seconds, outFile)  a quiet original ambient bed synthesised from lavfi sine and noise layers, about -30 LUFS
//   bedWindowFilter(cards)         volume filter that silences the bed outside the card windows [{from, to}] (seconds)
//   duckFilter(voiceWindows, db)   volume filter that ducks the bed by db (12) while the narration speaks [{from, to}]
//   mixBedArgs({voice, bed, out, cards, voiceWindows})  voice plus gated, ducked bed, no renormalisation
//   uiSoundEnabled(argv)           false when the command line holds --no-ui-sound
//   tickArgs(times, outFile, seconds)      soft UI ticks (about -34 LUFS) at the given seconds
//   parseEbur128(text), parseSilence(text) pure parsers of ffmpeg's ebur128 and silencedetect output
//   measureLoudness(file), silenceScan(file, opts), audioGate(file, opts)  the render gate: LUFS within 1 LU of -16, true peak below -1.5 dB,
//                                  no silence over 2.5 s outside the card windows
import { spawnSync } from 'node:child_process';
import { WARM, ffmpegPath } from './la-video-voice.mjs';

export const TARGET = { lufs: -16, truePeak: -1.5, bedLufs: -30, duckDb: 12, tickGainDb: -31, silenceMax: 2.5 };

/** The film master: high-pass, de-ess, the warm tone, then loudness. WARM already ends in a -18 LUFS loudnorm (training series):
 *  the case master replaces that stage, so only the EQ and compressor of WARM are reused. */
export function masterFilter({ lufs = TARGET.lufs, truePeak = TARGET.truePeak } = {}) {
  const warmTone = WARM.split(',').filter((f) => !f.startsWith('loudnorm')).join(',');
  return ['highpass=f=80', 'deesser=i=0.3:m=0.5:f=0.5:s=o', warmTone, `loudnorm=I=${lufs}:TP=${truePeak}:LRA=9`].join(',');
}
export function masterArgs(inFile, outFile, opts = {}) {
  return ['-y', '-hide_banner', '-loglevel', 'error', '-i', inFile, '-af', masterFilter(opts), '-ar', '44100', '-ac', '2', outFile];
}

export const INDUSTRY_FAMILY = {
  Telecom: 'telecom', Banking: 'finance', PublicSector: 'public', LawEnforcement: 'public', Insurance: 'health', Healthcare: 'health',
  Enterprise: 'enterprise', Logistics: 'enterprise', Utilities: 'enterprise',
};
export const bedFamily = (industry) => INDUSTRY_FAMILY[industry] ?? 'enterprise';

// one quiet parameter set per family: a base pitch and its partials, a noise bed and its low-pass, a slow tremolo
export const BED = {
  telecom: { base: 110, partials: [1, 1.5, 2.01], noise: 0.010, lowpass: 900, tremolo: 0.10 },
  finance: { base: 98, partials: [1, 1.25, 1.5], noise: 0.008, lowpass: 700, tremolo: 0.11 },
  public: { base: 87.3, partials: [1, 1.33, 2], noise: 0.012, lowpass: 800, tremolo: 0.12 },
  health: { base: 130.8, partials: [1, 1.5, 2.25], noise: 0.006, lowpass: 1000, tremolo: 0.13 },
  enterprise: { base: 73.4, partials: [1, 1.5, 2], noise: 0.009, lowpass: 650, tremolo: 0.11 },
};

/** ffmpeg arguments for the bed of one family: layered sines and pink noise, slow tremolo, soft fades, about -30 LUFS, stereo. */
export function ambientArgs(family, seconds, outFile) {
  const p = BED[family] ?? BED.enterprise;
  const args = ['-y', '-hide_banner', '-loglevel', 'error'];
  p.partials.forEach((r) => args.push('-f', 'lavfi', '-i', `sine=frequency=${(p.base * r).toFixed(2)}:duration=${seconds}`));
  args.push('-f', 'lavfi', '-i', `anoisesrc=color=pink:amplitude=${p.noise}:duration=${seconds}`);
  const n = p.partials.length + 1;
  const gains = p.partials.map((_, i) => (i === 0 ? 0.5 : 0.28 / i));
  const labels = [...gains.map((g, i) => `[${i}:a]volume=${g.toFixed(3)}[s${i}]`), `[${n - 1}:a]lowpass=f=${p.lowpass}[ns]`];
  const mixIn = [...gains.map((_, i) => `[s${i}]`), '[ns]'].join('');
  const fade = Math.min(2, seconds / 4);
  const graph = `${labels.join(';')};${mixIn}amix=inputs=${n}:normalize=0,tremolo=f=${p.tremolo}:d=0.35,aecho=0.7:0.5:90:0.25,`
    + `afade=t=in:st=0:d=${fade},afade=t=out:st=${Math.max(0, seconds - fade)}:d=${fade},loudnorm=I=${TARGET.bedLufs}:TP=-8:LRA=5[bed]`;
  args.push('-filter_complex', graph, '-map', '[bed]', '-ar', '44100', '-ac', '2', outFile);
  return args;
}

const between = (w) => `between(t,${w.from},${w.to})`;
const anyOf = (ws) => (ws.length ? `gt(${ws.map(between).join('+')},0)` : '0');

/** Bed audible only inside the card windows: every other second is silent. */
export function bedWindowFilter(cards) {
  return `volume=volume=0:enable='not(${anyOf(cards)})'`;
}
/** Duck the bed while narration speaks (the instant it starts). */
export function duckFilter(voiceWindows, db = TARGET.duckDb) {
  return `volume=volume=-${db}dB:enable='${anyOf(voiceWindows)}'`;
}
/** Voice plus bed. The voice is expected mastered already; the bed is gated to the cards and ducked under narration. */
export function mixBedArgs({ voice, bed, out, cards, voiceWindows }) {
  const graph = `[1:a]${bedWindowFilter(cards)},${duckFilter(voiceWindows)}[b];[0:a][b]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[a]`;
  return ['-y', '-hide_banner', '-loglevel', 'error', '-i', voice, '-i', bed, '-filter_complex', graph, '-map', '[a]', '-ar', '44100', '-ac', '2', out];
}

export const uiSoundEnabled = (argv = process.argv) => !argv.includes('--no-ui-sound');

/** A soft tick (a 1.8 kHz sine burst of 50 ms) at each time in seconds, on a silent track of the film's length. */
export function tickArgs(times, outFile, seconds) {
  const args = ['-y', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-t', String(seconds), '-i', 'anullsrc=r=44100:cl=stereo'];
  if (!times.length) return [...args, outFile];
  times.forEach(() => args.push('-f', 'lavfi', '-i', 'sine=frequency=1800:duration=0.05'));
  const parts = times.map((t, i) => {
    const ms = Math.round(t * 1000);
    return `[${i + 1}:a]afade=t=out:st=0.015:d=0.035,volume=${TARGET.tickGainDb}dB,pan=stereo|c0=c0|c1=c0,adelay=${ms}|${ms}[t${i}]`;
  });
  const mixIn = ['[0:a]', ...times.map((_, i) => `[t${i}]`)].join('');
  args.push('-filter_complex', `${parts.join(';')};${mixIn}amix=inputs=${times.length + 1}:duration=first:normalize=0[a]`, '-map', '[a]', '-ar', '44100', outFile);
  return args;
}

// ---- measurement -----------------------------------------------------------------------------------------
const last = (re, s) => { let m, r = null; while ((m = re.exec(s))) r = m; return r ? +r[1] : null; };
/** ffmpeg -af ebur128=peak=true summary -> { lufs, lra, truePeak } (the last values printed). */
export function parseEbur128(text) {
  return { lufs: last(/I:\s+(-?[\d.]+)\s+LUFS/g, text), lra: last(/LRA:\s+([\d.]+)\s+LU/g, text), truePeak: last(/Peak:\s+(-?[\d.]+)\s+dBFS/g, text) };
}
/** ffmpeg silencedetect output -> [{start, end, duration}] (an open silence ends at `end: null`). */
export function parseSilence(text) {
  const out = [];
  for (const line of text.split('\n')) {
    const s = /silence_start:\s*(-?[\d.]+)/.exec(line);
    if (s) out.push({ start: +s[1], end: null, duration: null });
    const e = /silence_end:\s*(-?[\d.]+)\s*\|\s*silence_duration:\s*([\d.]+)/.exec(line);
    if (e && out.length) { const o = out[out.length - 1]; o.end = +e[1]; o.duration = +e[2]; }
  }
  return out;
}
function runFfmpeg(args) {
  const r = spawnSync(ffmpegPath(), args, { encoding: 'utf8', maxBuffer: 1 << 26 });
  return `${r.stdout || ''}\n${r.stderr || ''}`;
}
export const measureLoudness = (file) => parseEbur128(runFfmpeg(['-nostats', '-hide_banner', '-i', file, '-af', 'ebur128=peak=true', '-f', 'null', '-']));
export const silenceScan = (file, { min = TARGET.silenceMax, noise = '-45dB' } = {}) =>
  parseSilence(runFfmpeg(['-nostats', '-hide_banner', '-i', file, '-af', `silencedetect=noise=${noise}:d=${min}`, '-f', 'null', '-'])).filter((s) => s.duration === null || s.duration >= min);

/** The render gate of plan §7: LUFS within `tol` of the target, true peak under -1.5 dB, no silence over 2.5 s outside the card windows. */
export function gateVerdict({ lufs, truePeak, silences }, { cards = [], target = TARGET.lufs, tol = 1 } = {}) {
  const problems = [];
  if (lufs === null || Math.abs(lufs - target) > tol) problems.push(`loudness ${lufs} LUFS is not within ${tol} LU of ${target}`);
  if (truePeak === null || truePeak > TARGET.truePeak) problems.push(`true peak ${truePeak} dB is above ${TARGET.truePeak}`);
  for (const s of silences) {
    const inCard = cards.some((c) => s.start >= c.from - 0.05 && (s.end ?? s.start) <= c.to + 0.05);
    if (!inCard) problems.push(`silence of ${s.duration ?? 'an open'} s at ${s.start} s outside a card`);
  }
  return { ok: problems.length === 0, problems, lufs, truePeak, silences };
}
export function audioGate(file, { cards = [], tol = 1 } = {}) {
  const m = measureLoudness(file);
  return gateVerdict({ ...m, silences: silenceScan(file) }, { cards, tol });
}
