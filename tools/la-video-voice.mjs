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
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

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

export function ffmpegPath() {
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

// =====================================================================================================================
// v2 (case films, plan docs/superpower/2026-11-19-la-case-videos-v2-plan.md §4.1): a pronunciation lexicon, a pace
// profile per beat and "[pause N]" markers. Nothing above changes: say() and speakable() keep the training series sound.
//   speakableV2(text, {exceptions})   text -> the words Piper is given (ids by family, abbreviations, figures, times)
//   PACE, paceFor(beat)               length scale and sentence silence per beat (cold, steady, reveal, close)
//   parsePauses(text)                 "a [pause 0.6] b" -> [{text:'a'},{pause:0.6},{text:'b'}]
//   applyBeat(text, beat)             the reveal beat holds 0.7 s before its last sentence
//   sayV2(text, out, {beat, exceptions, leadIn, tail})   one scene's narration with the pace, pauses, lead-in and tail
// =====================================================================================================================
const LETTER_NAME = { A: 'ay', B: 'bee', C: 'see', D: 'dee', E: 'ee', F: 'eff', G: 'gee', H: 'aitch', I: 'eye', J: 'jay', K: 'kay', L: 'ell', M: 'em',
  N: 'en', O: 'oh', P: 'pee', Q: 'cue', R: 'ar', S: 'ess', T: 'tee', U: 'you', V: 'vee', W: 'double you', X: 'ex', Y: 'why', Z: 'zed' };
const ONES = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve', 'thirteen', 'fourteen', 'fifteen',
  'sixteen', 'seventeen', 'eighteen', 'nineteen'];
const TENS = ['', '', 'twenty', 'thirty', 'forty', 'fifty', 'sixty', 'seventy', 'eighty', 'ninety'];
const ORDINAL = ['', 'first', 'second', 'third', 'fourth', 'fifth', 'sixth', 'seventh', 'eighth', 'ninth', 'tenth', 'eleventh', 'twelfth', 'thirteenth',
  'fourteenth', 'fifteenth', 'sixteenth', 'seventeenth', 'eighteenth', 'nineteenth', 'twentieth', 'twenty-first', 'twenty-second', 'twenty-third',
  'twenty-fourth', 'twenty-fifth', 'twenty-sixth', 'twenty-seventh', 'twenty-eighth', 'twenty-ninth', 'thirtieth', 'thirty-first'];
const MONTH = ['', 'January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

/** A whole number up to 999 999 in words: 4750 -> "four thousand seven hundred and fifty". */
export function numberWords(n) {
  n = Math.floor(Math.abs(n));
  if (n < 20) return ONES[n];
  if (n < 100) return TENS[Math.floor(n / 10)] + (n % 10 ? '-' + ONES[n % 10] : '');
  if (n < 1000) return ONES[Math.floor(n / 100)] + ' hundred' + (n % 100 ? ' and ' + numberWords(n % 100) : '');
  const rest = n % 1000;
  return numberWords(Math.floor(n / 1000)) + ' thousand' + (rest ? (rest < 100 ? ' and ' : ' ') + numberWords(rest) : '');
}

// id families: spoken word(s) and how a zero is said ("oh" for the short operational ids, "zero" for register ids)
const FAMILY = {
  'HUB': ['hub', 'oh'], 'MULE-HUB': ['mule hub', 'oh'], 'STRUCT': ['struct', 'oh'], 'RELAY': ['relay', 'zero'], 'AG': ['A G', 'zero'],
  'FARM': ['farm', 'zero'], 'S': ['ess', 'zero'], 'DL': ['dee ell', 'oh'], 'POS': ['pee oh ess,', 'zero'], 'TILL': ['till', 'oh'], 'CASHER': ['casher', 'oh'],
  'PER': ['person', 'zero'], 'SL': ['list entry', 'zero'], 'CO': ['company', 'zero'], 'CUS': ['customer', 'zero'], 'M': ['merchant', 'zero'],
  'CL': ['claimant', 'zero'], 'C': ['see', 'zero'], 'TX': ['tee ex', 'oh'], 'PRM': ['premise', 'zero'], 'P': ['pee', 'zero'], 'DLR': ['dealer', 'oh'],
  'ADR': ['address', 'zero'], 'INC': ['incident', 'zero'], 'AD': ['address', 'zero'], 'PR': ['provider', 'zero'], 'OW': ['owner', 'zero'],
  'DRV': ['driver', 'zero'], 'CAR': ['vehicle', 'zero'], 'V': ['vendor', 'zero'], 'VEN': ['vendor', 'zero'], 'BA': ['account', 'zero'], 'E': ['ee', 'zero'],
  'EMP': ['employee', 'zero'], 'DEV-FARM': ['device farm', 'oh'],
};
// families said as a role, never by their number (the id stays in the chip)
const ROLE_ONLY = { LAW: 'the lawyer', GAR: 'the garage', CLI: 'the clinic', SUP: 'the supplier' };
// abbreviations: letter by letter unless listed
const ABBREV = { SIM: 'sim', ANPR: 'plate reader camera', 'SHA-256': 'shaw two fifty-six' };
const ABBREV_LETTERS = ['MSISDN', 'IMSI', 'KYC', 'AP', 'IBAN', 'GP', 'IP', 'CCTV', 'ATM'];

const spellLetters = (s) => [...s].map((c) => LETTER_NAME[c] ?? c).join(' ');
const digitWords = (s, zero) => [...s].map((d) => (d === '0' ? zero : DIGIT[+d])).join(' ');
// the number after an id: 1-2 digits are said as a number ("eighteen", "oh one"), 3-6 digit by digit
function idNumber(n, zero) {
  if (n.length <= 2) {
    if (n.length === 2 && n[0] === '0') return `${zero} ${DIGIT[+n[1]]}`;
    return numberWords(+n);
  }
  return digitWords(n, zero);
}
const hour12 = (h) => numberWords(h % 12 === 0 ? 12 : h % 12);
const dayPart = (h) => (h === 0 ? 'at midnight' : h < 12 ? 'in the morning' : h === 12 ? 'at noon' : h < 18 ? 'in the afternoon' : h < 22 ? 'in the evening' : 'at night');
function clock(h, m, withPart) {
  const base = m ? `${hour12(h)} ${m < 10 ? 'oh ' : ''}${numberWords(m)}` : hour12(h);
  return withPart ? `${base} ${dayPart(h)}` : base;
}
const unitWord = (n, u) => {
  if (u === 'km') return n === 1 ? 'kilometre' : 'kilometres';
  if (u === 'm') return n === 1 ? 'metre' : 'metres';
  return u;
};

/** The words Piper is given in a case film. exceptions: { 'HUB-01': 'the hub wallet' } replaces exact strings first (per film). */
export function speakableV2(text, { exceptions = {} } = {}) {
  let t = text;
  for (const [from, to] of Object.entries(exceptions)) t = t.split(from).join(to);
  t = t.replace(/\bSHA-256\b/g, ABBREV['SHA-256']);
  // number plates: ZX 9117 X
  t = t.replace(/\b([A-Z]{2}) (\d{3,4}) ([A-Z])\b/g, (_, a, n, b) => `${spellLetters(a)} ${digitWords(n, 'zero')} ${spellLetters(b)}`);
  // ids: HUB-01, MULE-HUB-01, DEV-FARM-01, ZX-style families are above; unknown families keep the legacy reading
  t = t.replace(/\b([A-Z]{1,8}(?:-[A-Z]{2,8})*)-(\d{1,6})\b/g, (m, fam, n) => {
    if (ROLE_ONLY[fam]) return ROLE_ONLY[fam];
    const f = FAMILY[fam];
    if (!f) return `${fam.split('-').map(letters).join(' ')} ${digits(n)}`;
    return `${f[0]} ${idNumber(n, f[1])}`;
  });
  // phone and account numbers: digit by digit in groups of three, "plus" for the sign
  t = t.replace(/(\+?)\b(\d{7,})\b/g, (_, plus, n) => (plus ? 'plus ' : '') + (n.match(/.{1,3}/g) || []).map((g) => digitWords(g, 'zero')).join(', '));
  // dates and times
  t = t.replace(/\b(\d{4})-(\d{2})-(\d{2})\b/g, (m, y, mo, d) => (+mo >= 1 && +mo <= 12 && +d >= 1 && +d <= 31 ? `the ${ORDINAL[+d]} of ${MONTH[+mo]}` : m));
  t = t.replace(/\b(\d{1,2}):(\d{2})\s*[–-]\s*(\d{1,2}):(\d{2})\b/g, (_, h1, m1, h2, m2) => {
    const a = +h1, b = +h2;
    return `from ${clock(a, +m1, true)} to ${clock(b, +m2, dayPart(a) !== dayPart(b))}`;
  });
  // percentages and ranges, multiples, durations and distances
  t = t.replace(/(\d+)\s*[–-]\s*(\d+)\s*%/g, (_, a, b) => `${numberWords(+a)} to ${numberWords(+b)} percent`);
  t = t.replace(/(\d+)\s*%/g, (_, a) => `${numberWords(+a)} percent`);
  t = t.replace(/\b(\d+(?:\.\d+)?)x\b/g, (_, a) => `about ${numberWords(Math.round(+a))} times`);
  t = t.replace(/\b(\d+)\s*(km|m)\b/g, (_, a, u) => `${numberWords(+a)} ${unitWord(+a, u)}`);
  t = t.replace(/\b(\d+)\s*(hours?|minutes?|seconds?|days?|weeks?)\b/g, (_, a, u) => `${numberWords(+a)} ${u}`);
  // amounts written with a space for thousands: 4 750 -> four thousand seven hundred and fifty
  t = t.replace(/\b(\d{1,3}) (\d{3})\b/g, (_, a, b) => numberWords(+a * 1000 + +b));
  // abbreviations
  t = t.replace(/\b(SIM|ANPR)\b/g, (m) => ABBREV[m]);
  t = t.replace(new RegExp(`\\b(${ABBREV_LETTERS.join('|')})\\b`, 'g'), (m) => spellLetters(m));
  return t;
}

// ---- pace profile: Piper length scale and the silence after a sentence, per beat (plan §4.1) ----
export const PACE = {
  cold: { lengthScale: 1.04, sentenceSilence: 0.45 },
  steady: { lengthScale: 0.98, sentenceSilence: 0.30 },
  reveal: { lengthScale: 0.98, sentenceSilence: 0.30, holdBeforeLast: 0.7 },
  close: { lengthScale: 0.96, sentenceSilence: 0.30 },
};
export const paceFor = (beat) => PACE[beat] ?? PACE.steady;

/** "a [pause 0.6] b" -> [{text:'a'}, {pause:0.6}, {text:'b'}]; empty text segments are dropped. */
export function parsePauses(text) {
  const out = [];
  let last = 0;
  for (const m of text.matchAll(/\[pause\s+(\d+(?:\.\d+)?)\]/g)) {
    const before = text.slice(last, m.index).trim();
    if (before) out.push({ text: before });
    out.push({ pause: +m[1] });
    last = m.index + m[0].length;
  }
  const rest = text.slice(last).trim();
  if (rest) out.push({ text: rest });
  return out;
}

/** The reveal beat holds before its last sentence: the pause marker is added unless the text already has one there. */
export function applyBeat(text, beat) {
  const hold = paceFor(beat).holdBeforeLast;
  if (!hold) return text;
  const body = text.trim();
  const cut = Math.max(body.lastIndexOf('. ', body.length - 2), body.lastIndexOf('? ', body.length - 2), body.lastIndexOf('! ', body.length - 2));
  if (cut < 0) return text;
  const head = body.slice(0, cut + 1), tailSentence = body.slice(cut + 2);
  if (/\[pause\s+[\d.]+\]\s*$/.test(head) || /^\[pause\s+[\d.]+\]/.test(tailSentence)) return text;
  return `${head} [pause ${hold}] ${tailSentence}`;
}

/** ffmpeg arguments that join wav parts (mono 44.1 kHz) and silent gaps into one file. parts: [{file}|{silence: seconds}] */
export function concatArgs(parts, out) {
  const args = ['-y', '-loglevel', 'error'];
  parts.forEach((p) => {
    if (p.file) args.push('-i', p.file);
    else args.push('-f', 'lavfi', '-t', String(p.silence), '-i', 'anullsrc=r=44100:cl=mono');
  });
  const labels = parts.map((_, i) => `[${i}:a]`).join('');
  args.push('-filter_complex', `${labels}concat=n=${parts.length}:v=0:a=1[a]`, '-map', '[a]', '-ar', '44100', '-ac', '1', out);
  return args;
}

function piperTake(text, raw, pace) {
  const p = spawnSync(PIPER, ['--model', MODEL, '--length_scale', String(pace.lengthScale), '--noise_scale', String(VOICE.noiseScale),
    '--noise_w', String(VOICE.noiseW), '--sentence_silence', String(pace.sentenceSilence), '--output_file', raw], { input: text, encoding: 'utf8' });
  if (p.status !== 0 || !existsSync(raw)) throw new Error(`piper failed: ${(p.stderr || '').slice(-300)}`);
}

/** One scene's narration: lexicon, pace of its beat, "[pause N]" silences, 0.35 s lead-in and 0.5 s tail, then the warm tone. */
export function sayV2(text, out, { beat = 'steady', exceptions = {}, leadIn = 0.35, tail = 0.5, lengthScale, sentenceSilence } = {}) {
  if (!existsSync(PIPER) || !existsSync(MODEL)) throw new Error(`Piper or the voice model is missing under ${DIR}`);
  const pace = { ...paceFor(beat), ...(lengthScale ? { lengthScale } : {}), ...(sentenceSilence ? { sentenceSilence } : {}) };
  const tmp = mkdtempSync(join(tmpdir(), 'la-voice2-'));
  try {
    const parts = [{ silence: leadIn }];
    parsePauses(applyBeat(text, beat)).forEach((seg, i) => {
      if (seg.pause) { parts.push({ silence: seg.pause }); return; }
      const raw = join(tmp, `seg${i}.wav`);
      piperTake(speakableV2(seg.text, { exceptions }), raw, pace);
      parts.push({ file: raw });
    });
    parts.push({ silence: tail });
    const joined = join(tmp, 'joined.wav');
    const j = spawnSync(ffmpegPath(), concatArgs(parts, joined), { encoding: 'utf8' });
    if (j.status !== 0) throw new Error(`ffmpeg failed: ${j.stderr}`);
    const f = spawnSync(ffmpegPath(), ['-y', '-loglevel', 'error', '-i', joined, '-af', WARM, '-ar', '44100', '-ac', '1', out], { encoding: 'utf8' });
    if (f.status !== 0) throw new Error(`ffmpeg failed: ${f.stderr}`);
  } finally { rmSync(tmp, { recursive: true, force: true }); }
}

// the command line (the renderer passes items.json: [{file, text}]; an item with "v2": true or a "beat" uses sayV2)
if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  const args = process.argv.slice(2);
  if (args[0] === '--say') say(args[1], args[2]);
  else if (args[0] === '--say-v2') sayV2(args[1], args[2], args[3] ? { beat: args[3] } : {});
  else if (args[0]) for (const it of JSON.parse(readFileSync(args[0], 'utf8'))) (it.v2 || it.beat ? sayV2(it.text, it.file, it) : say(it.text, it.file));
}
