// The narration voice of the Inspecto LA videos (training series and case demos; operator 2026-11-16, D-TR1): a local
// neural voice, Piper with the en_US "hfc_female" model, warm tone, a touch faster than normal. Nothing leaves the machine.
//
//   node tools/la-video-voice.mjs <items.json>        items: [{file, text}] -> one WAV per item (44.1 kHz mono)
//   node tools/la-video-voice.mjs --say "<text>" <out.wav>
//
// Engine and model live outside the repo (one-time download, operator-approved 2026-11-16):
//   C:\sandbox\la-voice\piper\piper.exe   and   C:\sandbox\la-voice\voices\en_US-hfc_female-medium.onnx(.json)
// Override with LA_VOICE_DIR. The model's .onnx.json had five two-character phoneme keys removed (aɪ aʊ ɔɪ eɪ oʊ): the
// Windows build 2023.11.14-2 refuses multi-codepoint keys, and espeak never emits them; the original is kept as .orig.
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, existsSync, readdirSync, mkdtempSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

const DIR = process.env.LA_VOICE_DIR || 'C:/sandbox/la-voice';
const PIPER = join(DIR, 'piper', 'piper.exe');
const MODEL = join(DIR, 'voices', 'en_US-hfc_female-medium.onnx');
// take "D" of the 2026-11-16 voice trial: pace and variation for Piper, then the warm tone
export const VOICE = { lengthScale: 0.98, noiseScale: 0.72, noiseW: 0.85, sentenceSilence: 0.30 };
export const WARM = 'equalizer=f=180:t=q:w=1:g=3,equalizer=f=3500:t=q:w=1.2:g=-1.5,equalizer=f=7500:t=q:w=1:g=-3,'
  + 'acompressor=threshold=-20dB:ratio=2:attack=20:release=200,loudnorm=I=-18:TP=-1.5';

// ---- pronunciation: ids are read the same way in every episode ----
const DIGIT = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine'];
// alphabetic id parts said as words; anything else is spelled letter by letter
const WORDS = new Set(['HUB', 'RELAY', 'STRUCT', 'SMURF', 'FARM', 'MULE', 'TILL', 'CASHER', 'OFFSHORE', 'MER', 'TELECOM']);
const digits = (s) => [...s].map((d) => DIGIT[+d]).join(' ');
const letters = (s) => (WORDS.has(s) ? s.toLowerCase() : [...s].join(' '));
export function speakable(text) {
  return text
    // ids such as HUB-01, SL-0137, MULE-HUB-01, CO-04431, DEV-00086, TX-007, P-00476, CL-0134
    .replace(/\b([A-Z]{1,8}(?:-[A-Z]{2,8})*)-(\d{1,6})\b/g, (_, a, n) => `${a.split('-').map(letters).join(' ')} ${digits(n)}`)
    // long numbers that are identifiers (phone numbers), not quantities: read digit by digit
    .replace(/\b\d{7,}\b/g, (n) => digits(n))
    // amounts written with a space for thousands (4 750, 5 000) are one number
    .replace(/\b(\d{1,3}) (\d{3})\b/g, '$1$2')
    // number plates such as ZX 9117 X
    .replace(/\b([A-Z]{2}) (\d{3,4}) ([A-Z])\b/g, (_, a, n, b) => `${letters(a)} ${digits(n)} ${b}`);
}

function ffmpegPath() {
  if (!spawnSync('ffmpeg', ['-version']).error) return 'ffmpeg';
  const base = join(process.env.LOCALAPPDATA ?? '', 'Microsoft/WinGet/Packages');
  for (const d of existsSync(base) ? readdirSync(base).filter((x) => x.startsWith('Gyan.FFmpeg')) : []) {
    for (const v of readdirSync(join(base, d))) { const e = join(base, d, v, 'bin/ffmpeg.exe'); if (existsSync(e)) return e; }
  }
  throw new Error('ffmpeg not found');
}

export function say(text, out) {
  if (!existsSync(PIPER) || !existsSync(MODEL)) throw new Error(`Piper or the voice model is missing under ${DIR}`);
  const tmp = mkdtempSync(join(tmpdir(), 'la-voice-'));
  try {
    const raw = join(tmp, 'raw.wav');
    const p = spawnSync(PIPER, ['--model', MODEL, '--length_scale', String(VOICE.lengthScale), '--noise_scale', String(VOICE.noiseScale),
      '--noise_w', String(VOICE.noiseW), '--sentence_silence', String(VOICE.sentenceSilence), '--output_file', raw],
      { input: speakable(text), encoding: 'utf8' });
    if (p.status !== 0 || !existsSync(raw)) throw new Error(`piper failed: ${(p.stderr || '').slice(-300)}`);
    const f = spawnSync(ffmpegPath(), ['-y', '-loglevel', 'error', '-i', raw, '-af', WARM, '-ar', '44100', '-ac', '1', out], { encoding: 'utf8' });
    if (f.status !== 0) throw new Error(`ffmpeg failed: ${f.stderr}`);
  } finally { rmSync(tmp, { recursive: true, force: true }); }
}

const args = process.argv.slice(2);
if (args[0] === '--say') say(args[1], args[2]);
else if (args[0]) for (const it of JSON.parse(readFileSync(args[0], 'utf8'))) say(it.text, it.file);
