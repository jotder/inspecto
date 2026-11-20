// node --test tools/la-video-voice.test.mjs - the v2 lexicon, pace markers and pause parsing (no Piper needed).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { speakable, speakableV2, numberWords, parsePauses, applyBeat, paceFor, PACE, concatArgs } from './la-video-voice.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

// every id and figure form of the Lexicon section of spec-case-videos-insurance-utilities-law.md, and its v2 spoken form
const CASES = {
  'HUB-01': 'hub oh one', 'MULE-HUB-01': 'mule hub oh one', 'STRUCT-01': 'struct oh one', 'STRUCT-25': 'struct twenty-five',
  'RELAY-1': 'relay one', 'AG-18': 'A G eighteen', 'FARM-1': 'farm one', 'FARM-9': 'farm nine', 'S-41': 'ess forty-one', 'S-72': 'ess seventy-two',
  'DL-112': 'dee ell one one two', 'DL-047': 'dee ell oh four seven', 'POS-0008': 'pee oh ess, zero zero zero eight', 'TILL-06': 'till oh six',
  'CASHER-01': 'casher oh one', 'PER-03904': 'person zero three nine zero four', 'SL-0137': 'list entry zero one three seven',
  'CO-04431': 'company zero four four three one', 'CO-00941': 'company zero zero nine four one', 'CUS-00528': 'customer zero zero five two eight',
  'M-317': 'merchant three one seven', 'CL-0134': 'claimant zero one three four', 'CL-01125': 'claimant zero one one two five',
  'LAW-02': 'the lawyer', 'GAR-03': 'the garage', 'CLI-04': 'the clinic', 'SUP-07': 'the supplier',
  'C-14': 'see fourteen', 'C-22': 'see twenty-two', 'C-27': 'see twenty-seven', 'TX-007': 'tee ex oh oh seven', 'TX-019': 'tee ex oh one nine',
  'PRM-0737': 'premise zero seven three seven', 'PRM-0773': 'premise zero seven seven three', 'P-00476': 'pee zero zero four seven six',
  'P-05167': 'pee zero five one six seven', 'P-00001': 'pee zero zero zero zero one', 'DLR-04': 'dealer oh four', 'DLR-09': 'dealer oh nine',
  'ADR-1377': 'address one three seven seven', 'INC-01023': 'incident zero one zero two three', 'AD-03141': 'address zero three one four one',
  'PR-1170': 'provider one one seven zero', 'PR-1392': 'provider one three nine two', 'OW-0434': 'owner zero four three four',
  'DRV-0892': 'driver zero eight nine two', 'DRV-0893': 'driver zero eight nine three', 'CAR-022': 'vehicle zero two two', 'V-117': 'vendor one one seven',
  'VEN-0264': 'vendor zero two six four', 'BA-9031': 'account nine zero three one', 'E-31': 'ee thirty-one', 'E-15': 'ee fifteen',
  'EMP-0007': 'employee zero zero zero seven', 'EMP-0482': 'employee zero four eight two', 'DEV-FARM-01': 'device farm oh one',
  'ZX 9117 X': 'zed ex nine one one seven ex', 'ZX 3465 D': 'zed ex three four six five dee',
  '99979100001': 'nine nine nine, seven nine one, zero zero zero, zero one', '99891000001': 'nine nine eight, nine one zero, zero zero zero, zero one',
  '+447700900897': 'plus four four seven, seven zero zero, nine zero zero, eight nine seven',
  'SHA-256': 'shaw two fifty-six',
  'SIM': 'sim', 'MSISDN': 'em ess eye ess dee en', 'KYC': 'kay why see', 'AP': 'ay pee', 'IBAN': 'eye bee ay en', 'ANPR': 'plate reader camera',
  'GP': 'gee pee', 'IP': 'eye pee',
  '4 750': 'four thousand seven hundred and fifty', '5 000': 'five thousand', '40–70 %': 'forty to seventy percent', '97 %': 'ninety-seven percent',
  '36 hours': 'thirty-six hours', '2 km': 'two kilometres', '400 m': 'four hundred metres', '30 minutes': 'thirty minutes',
  '3.4x': 'about three times', '02:00–04:00': 'from two in the morning to four', '2026-09-04': 'the fourth of September',
};

test('every form in the spec Lexicon table has its v2 spoken form', () => {
  for (const [input, spoken] of Object.entries(CASES)) assert.equal(speakableV2(input), spoken, `${input}`);
});

test('the Lexicon section of the spec lists no id or figure form that this test does not cover', () => {
  const md = readFileSync(join(root, 'docs/okf/product/module/la-core/spec-case-videos-insurance-utilities-law.md'), 'utf8');
  const sec = md.slice(md.indexOf('## Lexicon'));
  const rows = sec.split('\n').filter((l) => l.startsWith('| ') && !l.startsWith('| Family') && !l.startsWith('|--'));
  assert.ok(rows.length >= 40, 'the table was parsed');
  const missing = [];
  for (const row of rows) {
    const cols = row.split(' | ');
    for (const token of cols[1].split(', ').map((x) => x.trim())) if (!(token in CASES)) missing.push(token);
  }
  assert.deepEqual(missing, []);
});

test('ids inside a sentence, and the per-film exception hook', () => {
  assert.equal(speakableV2('Start from HUB-01, then STRUCT-25.'), 'Start from hub oh one, then struct twenty-five.');
  assert.equal(speakableV2('Cash-ins of 4 750 to 5 000 within 36 hours.'), 'Cash-ins of four thousand seven hundred and fifty to five thousand within thirty-six hours.');
  assert.equal(speakableV2('Look at HUB-01.', { exceptions: { 'HUB-01': 'the hub wallet' } }), 'Look at the hub wallet.');
  assert.equal(speakableV2('Between 02:30–04:00.'), 'Between from two thirty in the morning to four.');
  assert.equal(speakableV2('An id the table does not know: XQ-12.'), 'An id the table does not know: X Q one two.');
});

test('the training series reading is unchanged', () => {
  assert.equal(speakable('HUB-01 and 4 750'), 'hub zero one and 4750');
});

test('number words', () => {
  assert.equal(numberWords(0), 'zero');
  assert.equal(numberWords(21), 'twenty-one');
  assert.equal(numberWords(100), 'one hundred');
  assert.equal(numberWords(1005), 'one thousand and five');
  assert.equal(numberWords(250000), 'two hundred and fifty thousand');
});

test('[pause N] markers split the text into speech and silence', () => {
  assert.deepEqual(parsePauses('One. [pause 0.6] Two.'), [{ text: 'One.' }, { pause: 0.6 }, { text: 'Two.' }]);
  assert.deepEqual(parsePauses('[pause 1] Only after.'), [{ pause: 1 }, { text: 'Only after.' }]);
  assert.deepEqual(parsePauses('No markers.'), [{ text: 'No markers.' }]);
});

test('the pace profile per beat, and the reveal hold before the last sentence', () => {
  assert.equal(paceFor('cold').lengthScale, 1.04);
  assert.equal(paceFor('cold').sentenceSilence, 0.45);
  assert.equal(paceFor('close').lengthScale, 0.96);
  assert.deepEqual(paceFor('unknown'), PACE.steady);
  assert.equal(applyBeat('It is here. It is the hub. And that is the lead.', 'reveal'), 'It is here. It is the hub. [pause 0.7] And that is the lead.');
  assert.equal(applyBeat('Already held. [pause 0.7] The last one.', 'reveal'), 'Already held. [pause 0.7] The last one.');
  assert.equal(applyBeat('A. B.', 'steady'), 'A. B.');
  assert.equal(applyBeat('One sentence only.', 'reveal'), 'One sentence only.');
});

test('concatArgs joins files and silences in order', () => {
  const a = concatArgs([{ silence: 0.35 }, { file: 'a.wav' }, { silence: 0.6 }, { file: 'b.wav' }, { silence: 0.5 }], 'out.wav');
  assert.ok(a.join(' ').includes('concat=n=5:v=0:a=1'));
  assert.equal(a.filter((x) => x === '-i').length, 5);
  assert.ok(a.includes('anullsrc=r=44100:cl=mono'));
  assert.equal(a.at(-1), 'out.wav');
});
