// Presentation helpers of the v2 case films (docs/superpower/2026-11-19-la-case-videos-v2-plan.md, section 4.3 and 5), used by
// tools/render-la-demo-video.mjs. Pure functions only (no ffmpeg, no files), so they are tested without a render:
//   - the Voice line without its [pause N] markers (the markers are an audio instruction, never captions),
//   - caption cues (40 characters a line, two lines at most) and their SRT text, chapter metadata for the MP4,
//   - the per-scene overlay lines of a spec: "- Intro:", "- Story:", "- Casefile:",
//   - the ffmpeg filter fragments of the intro card, the story card ("Who is who") and the case-file card.
// Nothing here runs unless a spec scene carries one of those lines or the renderer is given --captions / --srt / --chapters.

/** The industry accent colours of plan section 4.3 (the Industry_ prefix of a case id). */
export const ACCENTS = {
  Telecom: '0x14b8a6', Banking: '0x3b82f6', Insurance: '0x8b5cf6', Utilities: '0xf59e0b', LawEnforcement: '0x64748b',
  PublicSector: '0x6366f1', Healthcare: '0x22c55e', Logistics: '0xf97316', Enterprise: '0x9ca3af',
};
export const accentFor = (caseName) => ACCENTS[String(caseName || '').split('_')[0]] ?? '0x3b82f6';
const hex = (c) => (String(c).startsWith('#') ? '0x' + String(c).slice(1) : String(c));

/** Characters that break an ffmpeg drawtext value in a filter graph are replaced by a space (the renderer's own rule). */
export const clean = (t) => String(t).replace(/['"\\:%]/g, ' ').replace(/\s+/g, ' ').trim();

/** The Voice line as a person would read it: [pause 0.6] markers removed, white space collapsed. */
export function stripPauses(text) {
  return String(text).replace(/\[pause[^\]]*\]/gi, ' ').replace(/\s+/g, ' ').replace(/\s+([.,;:!?])/g, '$1').trim();
}

/** Split into sentences, keeping the end mark. */
export function sentences(text) {
  const out = []; let cur = '';
  for (const word of String(text).split(' ').filter(Boolean)) {
    cur = cur ? cur + ' ' + word : word;
    if (/[.!?]["')]?$/.test(word)) { out.push(cur); cur = ''; }
  }
  if (cur) out.push(cur);
  return out;
}

/** Word-wrap one sentence to lines of at most maxChars (a longer single word keeps its own line). */
export function wrapLines(text, maxChars = 40) {
  const lines = []; let cur = '';
  for (const w of String(text).split(' ').filter(Boolean)) {
    if (!cur) cur = w;
    else if ((cur + ' ' + w).length <= maxChars) cur += ' ' + w;
    else { lines.push(cur); cur = w; }
  }
  if (cur) lines.push(cur);
  return lines;
}

/**
 * Caption cues for one scene: the voice runs from startSec for durSec; each cue holds at most maxLines lines of maxChars,
 * and gets a share of the time proportional to its characters. Cues never overlap and fill the voice exactly.
 */
export function captionCues(voiceText, startSec, durSec, { maxChars = 40, maxLines = 2 } = {}) {
  const lines = sentences(stripPauses(voiceText)).flatMap((s) => wrapLines(s, maxChars));
  if (!lines.length || !(durSec > 0)) return [];
  const groups = [];
  for (let i = 0; i < lines.length; i += maxLines) groups.push(lines.slice(i, i + maxLines));
  const weight = groups.map((g) => g.join(' ').length);
  const total = weight.reduce((a, b) => a + b, 0);
  let t = startSec;
  return groups.map((g, k) => {
    const d = (durSec * weight[k]) / total;
    const cue = { start: +t.toFixed(3), end: +(t + d).toFixed(3), text: g.join('\n') };
    t += d;
    return cue;
  });
}

export function srtTime(sec) {
  const ms = Math.round(sec * 1000);
  const p = (n, w) => String(n).padStart(w, '0');
  return `${p(Math.floor(ms / 3600000), 2)}:${p(Math.floor(ms / 60000) % 60, 2)}:${p(Math.floor(ms / 1000) % 60, 2)},${p(ms % 1000, 3)}`;
}

export function toSrt(cues) {
  return cues.map((c, i) => `${i + 1}\n${srtTime(c.start)} --> ${srtTime(c.end)}\n${c.text}\n`).join('\n');
}

/** FFMETADATA1 text with one chapter per scene: [{ title, startSec, endSec }]. */
export function chaptersMeta(chapters) {
  const esc = (s) => String(s).replace(/([=;#\\\n])/g, '\\$1');
  return ';FFMETADATA1\n' + chapters.map((c) => `[CHAPTER]\nTIMEBASE=1/1000\nSTART=${Math.round(c.startSec * 1000)}\nEND=${Math.round(c.endSec * 1000)}\ntitle=${esc(c.title)}\n`).join('');
}

// ---------- the overlay lines of a spec scene ----------
const field = (body, key) => body.match(new RegExp(`^- ${key}:[ \\t]*(.*)$`, 'm'))?.[1]?.trim();

/**
 * "- Intro: #14b8a6 | Title | Space name"   (accent optional: the first part may be left out)
 * "- Story: the hub wallet = HUB-01 | a relay = RELAY-1"   or   "- Story: off"
 * "- Casefile: Lead: ... | Evidence: ... | Next: a; b; c | Try it: ..."
 * Returns only what the scene carries.
 */
export function parseOverlays(body) {
  const o = {};
  const intro = field(body, 'Intro');
  if (intro) {
    const parts = intro.split('|').map((x) => x.trim());
    let accent = null;
    if (/^#?[0-9a-fA-F]{6}$/.test(parts[0])) accent = hex('#' + parts.shift().replace('#', ''));
    o.intro = { accent, title: parts[0] ?? '', space: parts[1] ?? '' };
  }
  const story = field(body, 'Story');
  if (story !== undefined) {
    o.story = story.toLowerCase() === 'off' ? { off: true } : { items: story.split('|').map((x) => x.split('=').map((y) => y.trim())).filter((p) => p[0]) };
  }
  const cf = field(body, 'Casefile');
  if (cf) {
    const c = {};
    for (const part of cf.split('|')) {
      const i = part.indexOf(':');
      if (i < 0) continue;
      const k = part.slice(0, i).trim().toLowerCase(); const v = part.slice(i + 1).trim();
      if (k === 'lead') c.lead = v; else if (k === 'evidence') c.evidence = v; else if (k === 'next') c.next = v.split(';').map((x) => x.trim()).filter(Boolean); else if (k === 'try it' || k === 'try') c.tryIt = v;
    }
    o.casefile = c;
  }
  return o;
}

// ---------- ffmpeg filter fragments (DT = the renderer's drawtext prefix with the font) ----------
const en = (a, b) => `enable='between(t,${a},${b})'`;
const txt = (DT, text, size, color, x, y, e, box = '') => `${DT}:text='${clean(text)}':fontsize=${size}:fontcolor=${color}:${box}x=${x}:y=${y}:${e}`;

/** Full-screen intro card over the scene's first secs: accent bar, "Inspecto LA · Case file", the title, the Space, the edition chip. */
export function introCardFilters(card, { secs = 4, accent, edition = 'Standard', DT }) {
  const e = `enable='lt(t,${secs})'`;
  const colour = card.accent ?? accent ?? '0x3b82f6';
  return [
    `drawbox=x=0:y=0:w=iw:h=ih:color=0x0b0f14@1:t=fill:${e}`,
    `drawbox=x=360:y=330:w=14:h=330:color=${colour}@1:t=fill:${e}`,
    txt(DT, 'Inspecto LA · Case file', 40, '0xa9b4c8', 410, 330, e),
    txt(DT, card.title, 84, '0xffffff', 410, 410, e),
    txt(DT, card.space, 44, '0xa9b4c8', 410, 540, e),
    txt(DT, edition, 32, '0xffffff', 410, 620, e, 'box=1:boxcolor=0x1d4ed8@0.9:boxborderw=12:'),
  ];
}

/** The story card ("Who is who"): top left, from t0 to t1 of the clip. items = [[role, id], ...]. */
export function storyCardFilters(items, { t0 = 1, t1, DT }) {
  const e = en(t0, t1);
  const h = 70 + items.length * 50;
  const out = [`drawbox=x=36:y=70:w=720:h=${h}:color=0x0b0f14@0.78:t=fill:${e}`, `drawbox=x=36:y=70:w=8:h=${h}:color=0xffd60a@1:t=fill:${e}`,
    txt(DT, 'Who is who', 30, '0xffd60a', 66, 84, e)];
  items.forEach((it, k) => out.push(txt(DT, it[1] ? `${it[0]}  =  ${it[1]}` : it[0], 34, '0xffffff', 66, 128 + k * 50, e)));
  return out;
}

/** The case-file card over the clip's last secs: Lead, Evidence, Next step (three bullets), Try it. */
export function caseFileFilters(card, { clipLen, secs = 8, accent, DT }) {
  const a = Math.max(0, clipLen - secs); const e = en(a.toFixed(2), clipLen.toFixed(2));
  const colour = accent ?? '0x3b82f6';
  const out = [`drawbox=x=0:y=0:w=iw:h=ih:color=0x0b0f14@1:t=fill:${e}`, `drawbox=x=200:y=160:w=14:h=760:color=${colour}@1:t=fill:${e}`,
    txt(DT, 'Case file', 40, '0xa9b4c8', 250, 160, e)];
  let y = 240;
  const block = (label, text) => { out.push(txt(DT, label, 30, '0xffd60a', 250, y, e)); out.push(txt(DT, text, 44, '0xffffff', 250, y + 40, e)); y += 140; };
  if (card.lead) block('Lead', card.lead);
  if (card.evidence) block('Evidence', card.evidence);
  if (card.next?.length) {
    out.push(txt(DT, 'Next step', 30, '0xffd60a', 250, y, e));
    card.next.slice(0, 3).forEach((n, k) => out.push(txt(DT, '-  ' + n, 40, '0xffffff', 250, y + 40 + k * 56, e)));
    y += 40 + Math.min(3, card.next.length) * 56 + 40;
  }
  if (card.tryIt) block('Try it', card.tryIt);
  return out;
}

/** The yellow (lead) or grey (ruled out) look of a mark; the decoy label is prefixed so the eye learns the difference. */
export function markLook(note) {
  return note.decoy ? { color: '0x9ca3af', label: note.label ? `ruled out: ${note.label}` : 'ruled out' } : { color: '0xffd60a', label: note.label };
}
