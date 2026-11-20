// node --test tools/la-video-present.test.mjs - the pure presentation helpers of the v2 case films.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  stripPauses, sentences, wrapLines, captionCues, srtTime, toSrt, chaptersMeta, parseOverlays, accentFor,
  introCardFilters, storyCardFilters, caseFileFilters, markLook, clean,
} from './la-video-present.mjs';

test('pause markers are removed from the Voice line and never reach the captions', () => {
  assert.equal(stripPauses('One. [pause 0.6] Two [pause 1] , three.'), 'One. Two, three.');
  assert.equal(stripPauses('No markers here.'), 'No markers here.');
});

test('captions wrap at 40 characters, two lines a cue, and fill the voice exactly', () => {
  const voice = 'A mobile-money operator moves cash for millions of wallets. [pause 0.5] Every single deposit is legal, because it is under the cap. So where is the laundering?';
  const cues = captionCues(voice, 0.5, 12);
  assert.ok(cues.length >= 3);
  for (const c of cues) {
    const lines = c.text.split('\n');
    assert.ok(lines.length <= 2, c.text);
    for (const l of lines) assert.ok(l.length <= 40, `"${l}" is ${l.length}`);
  }
  assert.equal(cues[0].start, 0.5);
  assert.ok(Math.abs(cues.at(-1).end - 12.5) < 0.01);
  for (let i = 1; i < cues.length; i++) assert.ok(cues[i].start >= cues[i - 1].end - 0.001);
  assert.ok(!cues.some((c) => c.text.includes('pause')));
});

test('a very long word keeps its own line and an empty voice gives no cues', () => {
  assert.deepEqual(wrapLines('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa b', 40), ['aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'b']);
  assert.deepEqual(captionCues('', 0, 5), []);
  assert.deepEqual(sentences('One. Two? Three'), ['One.', 'Two?', 'Three']);
});

test('SRT times and text', () => {
  assert.equal(srtTime(3723.456), '01:02:03,456');
  assert.equal(srtTime(0.5), '00:00:00,500');
  const srt = toSrt([{ start: 0.5, end: 2, text: 'Hello\nworld' }, { start: 2, end: 3, text: 'Next' }]);
  assert.equal(srt, '1\n00:00:00,500 --> 00:00:02,000\nHello\nworld\n\n2\n00:00:02,000 --> 00:00:03,000\nNext\n');
});

test('chapter metadata has one chapter per scene in milliseconds', () => {
  const m = chaptersMeta([{ title: 'The problem', startSec: 0, endSec: 12.5 }, { title: 'A=B; C', startSec: 12.5, endSec: 30 }]);
  assert.ok(m.startsWith(';FFMETADATA1\n'));
  assert.match(m, /START=0\nEND=12500\ntitle=The problem/);
  assert.match(m, /START=12500\nEND=30000\ntitle=A\\=B\\; C/);
});

test('overlay lines of a scene are parsed, and a scene without them gives nothing', () => {
  const body = '- Voice: x\n- Intro: #14b8a6 | Telecom structuring | Mobile Money\n- Story: the hub wallet = HUB-01 | a relay = RELAY-1\n- Casefile: Lead: one agent | Evidence: the rows | Next: review AG-18; hold the hub; start a list | Try it: open the view\n';
  const o = parseOverlays(body);
  assert.deepEqual(o.intro, { accent: '0x14b8a6', title: 'Telecom structuring', space: 'Mobile Money' });
  assert.deepEqual(o.story.items, [['the hub wallet', 'HUB-01'], ['a relay', 'RELAY-1']]);
  assert.deepEqual(o.casefile, { lead: 'one agent', evidence: 'the rows', next: ['review AG-18', 'hold the hub', 'start a list'], tryIt: 'open the view' });
  assert.deepEqual(parseOverlays('- Voice: only a voice\n'), {});
  assert.deepEqual(parseOverlays('- Story: off\n').story, { off: true });
  assert.equal(parseOverlays('- Intro: Title only | Space\n').intro.accent, null);
});

test('industry accents come from the case id prefix', () => {
  assert.equal(accentFor('Telecom_SimSwap'), '0x14b8a6');
  assert.equal(accentFor('LawEnforcement_BurglarySeries'), '0x64748b');
  assert.equal(accentFor('Unknown_X'), '0x3b82f6');
});

test('card filters carry the text, the accent and a time window; the decoy mark is grey and labelled', () => {
  const DT = 'drawtext=fontfile=font.ttf:expansion=none';
  const intro = introCardFilters({ title: 'SIM swap', space: 'Telecom', accent: null }, { secs: 4, accent: '0x14b8a6', DT }).join(',');
  assert.match(intro, /0x14b8a6@1/); assert.match(intro, /Case file/); assert.match(intro, /lt\(t,4\)/); assert.match(intro, /SIM swap/);
  const story = storyCardFilters([['the login', 'S-41'], ['the decoy', 'S-72']], { t0: 1, t1: 30, DT }).join(',');
  assert.match(story, /Who is who/); assert.match(story, /the login = S-41/); assert.match(story, /between\(t,1,30\)/);
  const cf = caseFileFilters({ lead: 'one login', evidence: 'the rows', next: ['a', 'b', 'c', 'd'], tryIt: 'try' }, { clipLen: 40, DT }).join(',');
  assert.match(cf, /between\(t,32\.00,40\.00\)/); assert.match(cf, /Next step/); assert.ok(!cf.includes('-  d'));
  assert.deepEqual(markLook({ decoy: true, label: 'S-72' }), { color: '0x9ca3af', label: 'ruled out: S-72' });
  assert.deepEqual(markLook({ label: 'S-41' }), { color: '0xffd60a', label: 'S-41' });
  assert.equal(clean(`a 'b': "c" 50%`), 'a b c 50');
});
