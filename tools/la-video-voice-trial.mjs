// Voice trial for the case films v2 (plan §4.1-§4.2, decisions D-CV1, D-CV2): three takes of one paragraph, the cold open of
// Telecom_SimSwap (docs/okf/product/module/la-core/spec-case-videos-telecom.md, scene S01), at three paces, each mastered to
// -16 LUFS, plus one take with the Telecom ambient bed under a 3 s title and 2 s outro and ducked under the narration.
//
//   node tools/la-video-voice-trial.mjs [outDir]       default C:/sandbox/la-video-out/_voice-trial
//
// The takes are for listening: A = the training series pace (steady), B = the v2 cold-open pace of the plan, C = slower and airier.
import { readFileSync, mkdirSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { sayV2, ffmpegPath } from './la-video-voice.mjs';
import { masterArgs, ambientArgs, mixBedArgs, measureLoudness, TARGET } from './la-video-audio.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const outDir = resolve(process.argv[2] ?? 'C:/sandbox/la-video-out/_voice-trial');
mkdirSync(outDir, { recursive: true });

const md = readFileSync(join(root, 'docs/okf/product/module/la-core/spec-case-videos-telecom.md'), 'utf8');
const caseStart = md.indexOf('## Telecom_SimSwap');
const sceneStart = md.indexOf('### S01', caseStart);
const voiceLine = /^- Voice: (.+)$/m.exec(md.slice(sceneStart, sceneStart + 2000));
if (caseStart < 0 || sceneStart < 0 || !voiceLine) throw new Error('the S01 voice of Telecom_SimSwap was not found in the telecom spec');
const text = voiceLine[1];

const ff = (args) => { const r = spawnSync(ffmpegPath(), args, { encoding: 'utf8' }); if (r.status !== 0) throw new Error(r.stderr); };
const seconds = (file) => {
  const r = spawnSync(ffmpegPath(), ['-hide_banner', '-i', file], { encoding: 'utf8' });
  const m = /Duration: (\d+):(\d+):([\d.]+)/.exec(r.stderr);
  return +m[1] * 3600 + +m[2] * 60 + +m[3];
};

const TAKES = [
  ['A-steady', { beat: 'steady' }],
  ['B-cold-open', { beat: 'cold' }],
  ['C-slower', { beat: 'cold', lengthScale: 1.10, sentenceSilence: 0.55 }],
];
let bVoice = null;
for (const [name, opt] of TAKES) {
  const raw = join(outDir, `take-${name}-raw.wav`), mastered = join(outDir, `take-${name}.wav`);
  sayV2(text, raw, opt);
  ff(masterArgs(raw, mastered));
  const m = measureLoudness(mastered);
  console.log(`take-${name}.wav  ${seconds(mastered).toFixed(1)} s  ${m.lufs} LUFS  true peak ${m.truePeak} dB`);
  if (name === 'B-cold-open') bVoice = mastered;
}

// the bed take: 3 s title (bed alone), the narration (bed ducked 12 dB), a 2 s outro (bed alone again)
const len = seconds(bVoice);
const total = 3 + len + 2;
const padded = join(outDir, 'take-B-padded.wav'), bed = join(outDir, 'bed-telecom.wav'), withBed = join(outDir, 'take-B-with-bed.wav');
ff(['-y', '-hide_banner', '-loglevel', 'error', '-i', bVoice, '-af', 'adelay=3000|3000,apad=pad_dur=2', padded]);
ff(ambientArgs('telecom', Math.ceil(total), bed));
ff(mixBedArgs({ voice: padded, bed, out: withBed, cards: [{ from: 0, to: 3 }, { from: 3 + len, to: total }], voiceWindows: [{ from: 3, to: 3 + len }] }));
const wb = measureLoudness(withBed), bl = measureLoudness(bed);
console.log(`take-B-with-bed.wav  ${total.toFixed(1)} s  ${wb.lufs} LUFS  true peak ${wb.truePeak} dB  (bed alone ${bl.lufs} LUFS, target ${TARGET.bedLufs})`);
console.log(`files in ${outDir}`);
