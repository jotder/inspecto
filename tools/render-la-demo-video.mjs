// Narrated product demo from docs/okf/product/module/la-core/brochure-video-demo-scripts.md:
//   1. synthesise each scene's voice-over (Windows offline voice) -> its length paces the recording,
//   2. record the real product live (tools/record-la-live.mjs, scenario <scenarioName>) with that timing,
//   3. cut the take per scene, add only the script's zoom / ring / box notes, lay the voice over, concatenate.
// Output C:\sandbox\la-video-out\<name>.mp4, 1920x1080, plain full-screen product.
//
//   node tools/render-la-demo-video.mjs <name> [--scenario mule_demo] [--reuse] [--rate 2] [--voice "Microsoft Mark"] [--out DIR]
//   --reuse  skip the voice and the recording, re-cut the last take (to try new notes quickly)
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, existsSync, copyFileSync, readdirSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const defaultScripts = 'docs/okf/product/module/la-core/brochure-video-demo-scripts.md';
const argv = process.argv.slice(2);
const opt = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const name = argv[0];
const scriptsFile = resolve(root, opt('--script', defaultScripts));
const port = opt('--port', '9333');
const reuse = argv.includes('--reuse');
const retake = argv.includes('--retake');   // new take, same voice and timing
const rate = opt('--rate', '2');
const voice = opt('--voice', 'Microsoft Mark');
const outRoot = resolve(opt('--out', 'C:/sandbox/la-video-out'));
if (!name) { console.error('usage: render-la-demo-video.mjs <name> [--scenario s] [--reuse]'); process.exit(2); }

function findFfmpeg() {
  if (!spawnSync('ffmpeg', ['-version']).error) return 'ffmpeg';
  const base = join(process.env.LOCALAPPDATA ?? '', 'Microsoft/WinGet/Packages');
  for (const d of existsSync(base) ? readdirSync(base).filter((x) => x.startsWith('Gyan.FFmpeg')) : []) {
    for (const v of readdirSync(join(base, d))) { const e = join(base, d, v, 'bin/ffmpeg.exe'); if (existsSync(e)) return e; }
  }
  throw new Error('ffmpeg not found (winget install Gyan.FFmpeg)');
}
const FF = findFfmpeg(), FFPROBE = FF.replace(/ffmpeg(\.exe)?$/, 'ffprobe$1');
function run(cmd, args, cwd, inherit = false) {
  const r = spawnSync(cmd, args, { cwd, encoding: 'utf8', maxBuffer: 1 << 28, stdio: inherit ? 'inherit' : 'pipe' });
  if (r.status !== 0) throw new Error(`${cmd} failed:\n${(r.stderr || '').split('\n').slice(-60).join('\n')}`);
  return r.stdout;
}
const ff = (a, cwd) => run(FF, ['-y', '-hide_banner', '-loglevel', 'error', ...a], cwd);
const duration = (f) => parseFloat(run(FFPROBE, ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', f]));

// ---------- parse ----------
const md = readFileSync(scriptsFile, 'utf8');
const secs = [...md.matchAll(/^## (\S+)$/gm)];
const si = secs.findIndex((s) => s[1] === name);
if (si < 0) throw new Error(`no video "${name}"`);
const sec = md.slice(secs[si].index, si + 1 < secs.length ? secs[si + 1].index : md.length);
// A section with `- Config: {...}` is a config-driven case (scenario case_demo, take named after the section);
// without it, the hand-built scenario of --scenario (default mule_demo) is used.
const cfgLine = sec.match(/^- Config:[ \t]*(\{.*\})$/m);
// a training episode's recording spec: tools/la-training/<name>.episode.json (--episode), else a `- Config:` line of the section
const episodeFile = opt('--episode', null);
const caseCfg = episodeFile ? JSON.parse(readFileSync(resolve(episodeFile), 'utf8')) : (cfgLine ? JSON.parse(cfgLine[1]) : null);
const scenario = caseCfg ? (caseCfg.scenario || 'case_demo') : opt('--scenario', 'mule_demo');
const takeName = caseCfg ? name : scenario;
const hs = [...sec.matchAll(/^### (\S+) · (.+)$/gm)];
const scenes = hs.map((h, k) => {
  const body = sec.slice(h.index + h[0].length, k + 1 < hs.length ? hs[k + 1].index : sec.length);
  return {
    id: h[1], title: h[2],
    voice: (body.match(/^- Voice:[ \t]*(.*)$/m)?.[1] ?? '').trim(),
    chip: (body.match(/^- Chip:[ \t]*(.*)$/m)?.[1] ?? '').replace(/[*`]/g, '').trim(),
    notes: [...body.matchAll(/^- Note:[ \t]*(\{.*\})$/gm)].map((m) => JSON.parse(m[1])),
  };
});

const work = join(outRoot, '_work', name);
mkdirSync(work, { recursive: true });
const takeDir = join(outRoot, '_live', takeName);
const PRE = 0.5, TAIL = 0.7;

// ---------- 1 + 2: voice, then a take paced by it ----------
if (!reuse) {
  if (!retake) {
  writeFileSync(join(work, 'items.json'), JSON.stringify(scenes.map((s) => ({ file: join(work, `${s.id}.wav`), text: s.voice }))), 'utf8');
  // the series voice (tools/la-video-voice.mjs: local Piper, warm female, D-TR1); --voice windows keeps the old SAPI voice
  if (opt('--voice', 'piper') === 'piper') run('node', [join(root, 'tools/la-video-voice.mjs'), join(work, 'items.json')]);
  else run('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', join(root, 'tools/la-video-tts.ps1'), '-Items', join(work, 'items.json'), '-Voice', voice, '-Rate', rate]);
  const timing = {};
  for (const s of scenes) timing[s.id] = +(PRE + duration(join(work, `${s.id}.wav`)) + TAIL).toFixed(2);
  writeFileSync(join(work, 'timing.json'), JSON.stringify(timing, null, 1));
  console.log('voice lengths', JSON.stringify(timing));
  }
  const recArgs = [join(root, 'tools/record-la-live.mjs'), scenario, '--dpr', '1.6', '--timing', join(work, 'timing.json'), '--out', join(outRoot, '_live'), '--take', takeName, '--port', port];
  if (caseCfg) { writeFileSync(join(work, 'config.json'), JSON.stringify(caseCfg)); recArgs.push('--config', join(work, 'config.json')); if (caseCfg.base) recArgs.push('--base', caseCfg.base); }
  run('node', recArgs, root, true);
  if (caseCfg?.std) {   // the Standard part: a second take on a build with sign-in (signed in before the first scene, off camera)
    writeFileSync(join(work, 'config-std.json'), JSON.stringify(caseCfg.std));
    run('node', [join(root, 'tools/record-la-live.mjs'), 'std_ext', '--dpr', '1.6', '--timing', join(work, 'timing.json'), '--out', join(outRoot, '_live'),
      '--take', takeName + '_std', '--port', String(Number(port) + 100), '--config', join(work, 'config-std.json'), '--base', caseCfg.std.base], root, true);
  }
}
const timing = JSON.parse(readFileSync(join(work, 'timing.json'), 'utf8'));
// which take each scene is in, and where it starts there
const takes = [{ dir: takeDir, file: join(takeDir, `${takeName}-live.mp4`) }];
if (caseCfg?.std) { const d = join(outRoot, '_live', takeName + '_std'); takes.push({ dir: d, file: join(d, `${takeName}_std-live.mp4`) }); }
const at = {};
const impacts = {};   // take file -> computed impact boxes (record-la-live.mjs h.impact), in take pixels
for (const t of takes) { const m = JSON.parse(readFileSync(join(t.dir, 'marks.json'), 'utf8')); impacts[t.file] = m.impacts || []; for (const [k, v] of Object.entries(m)) if (scenes.some((x) => x.id === k)) at[k] = { take: t.file, start: v }; }

// ---------- 3: cut, annotate, voice ----------
copyFileSync('C:/Windows/Fonts/arial.ttf', join(work, 'font.ttf'));
const rings = new Set();
const ringPng = (r) => {
  const f = `ring${r}.png`;
  if (!rings.has(r)) {
    const s = 2 * r + 12;
    ff(['-f', 'lavfi', '-i', `color=c=0xffd60a:s=${s}x${s},format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='if(lt(abs(hypot(X-${s / 2},Y-${s / 2})-${r}),3.5),235,0)'`, '-frames:v', '1', f], work);
    rings.add(r);
  }
  return f;
};
const en = (a, b) => `enable='between(t,${a},${b})'`;
const DT = 'drawtext=fontfile=font.ttf:expansion=none';

function noteFilters(notes, sceneIdx) {
  // returns { pre: filter_complex fragments before scaling (source pixels), zooms: [] }
  const overlays = [], texts = [], zooms = [];
  notes.forEach((nt, k) => {
    if (nt.type === 'ring') {
      overlays.push({ png: ringPng(nt.r), x: nt.x - nt.r - 6, y: nt.y - nt.r - 6, en: en(nt.t0, nt.t1) });
      if (nt.label) texts.push(`${DT}:text='${String(nt.label).replace(/['"\:%]/g, ' ').trim()}':fontsize=44:fontcolor=white:box=1:boxcolor=black@0.7:boxborderw=12:x=${nt.x + nt.r + 14}:y=${nt.y - 24}:${en(nt.t0, nt.t1)}`);
    } else if (nt.type === 'box') {
      texts.push(`drawbox=x=${nt.x}:y=${nt.y}:w=${nt.w}:h=${nt.h}:color=0xffd60a@0.95:t=6:${en(nt.t0, nt.t1)}`);
      if (nt.label) texts.push(`${DT}:text='${String(nt.label).replace(/['"\:%]/g, ' ').trim()}':fontsize=44:fontcolor=white:box=1:boxcolor=black@0.7:boxborderw=12:x=${nt.x}:y=${nt.ly ?? Math.max(10, nt.y - 70)}:${en(nt.t0, nt.t1)}`);
    } else if (nt.type === 'zoom') zooms.push(nt);
  });
  return { overlays, texts, zooms };
}
// smooth 0..1 weight for a zoom window with 0.9 s ease in and out
const wExpr = (z) => { const e = 0.9; const m = `min(clip((t-${z.t0})/${e},0,1),clip((${z.t1}-t)/${e},0,1))`; return `(${m}*${m}*(3-2*${m}))`; };

const clipFiles = [];
scenes.forEach((s, i) => {
  const { take, start } = at[s.id];
  const nx = i + 1 < scenes.length ? at[scenes[i + 1].id] : null;
  const seg = nx && nx.take === take ? nx.start - start : timing[s.id];
  // a scene whose footage ran longer than its narration (a slow load, a long draw) is sped up to fit, at most 4x:
  // no long stretches without a voice
  const speed = seg > timing[s.id] + 0.6 ? Math.min(4, seg / timing[s.id]) : 1;
  const L = Math.max(timing[s.id], seg / speed);
  // computed impact marks that fall inside this scene: a yellow box on what the action changed, held 3 s (+12 px margin)
  const computed = impacts[take].filter((m) => m.t >= start && m.t < start + L * speed).map((m) => ({ type: 'box', t0: +((m.t - start) / speed).toFixed(2), t1: +((m.t - start) / speed + 3).toFixed(2), x: Math.max(0, m.box[0] - 12), y: Math.max(0, m.box[1] - 12), w: m.box[2] + 24, h: m.box[3] + 24, label: m.text }));
  const notes = [...computed, ...s.notes].map((n) => (n.f0 !== undefined ? { ...n, t0: +(n.f0 * L).toFixed(2), t1: +(n.f1 * L).toFixed(2) } : n));
  const { overlays, texts, zooms } = noteFilters(notes, i);
  // training series look (plan section 6.2): the edition chip in the corner of every scene, the control's name and key as a lower
  // third for the scene's first 5 s, and an intro / outro card over the scene's first seconds (the narration plays over it)
  const clean = (t) => String(t).replace(/['"\\:%]/g, ' ').trim();
  if (caseCfg?.edition) texts.push(`${DT}:text='${clean(caseCfg.edition)}':fontsize=30:fontcolor=white:box=1:boxcolor=0x1d4ed8@0.85:boxborderw=10:x=w-text_w-40:y=36`);
  if (s.chip) texts.push(`${DT}:text='${clean(s.chip)}':fontsize=40:fontcolor=white:box=1:boxcolor=black@0.7:boxborderw=16:x=(w-text_w)/2:y=h-170:enable='between(t,0.6,5.6)'`);
  const cardSpec = (caseCfg?.steps || []).find((x) => x.scene === s.id)?.card;
  if (cardSpec) {
    const secs = cardSpec.secs ?? 4;
    const cardTexts = [`drawbox=x=0:y=0:w=iw:h=ih:color=0x0b0f14@1:t=fill:enable='lt(t,${secs})'`];
    (cardSpec.lines || []).forEach((l, k) => cardTexts.push(`${DT}:text='${clean(l)}':fontsize=${k === 0 ? 84 : 46}:fontcolor=${k === 0 ? '0xffffff' : '0xa9b4c8'}:x=(w-text_w)/2:y=${400 + k * 130 + (k > 0 ? 30 : 0)}:enable='lt(t,${secs})'`));
    texts.unshift(...cardTexts);
  }
  const inputs = ['-ss', start.toFixed(3), '-t', (L * speed).toFixed(3), '-i', take, '-i', join(work, `${s.id}.wav`)];
  let fc = `[0:v]setpts=PTS/${speed.toFixed(3)},tpad=stop_mode=clone:stop_duration=${L.toFixed(2)}[b0]`; let cur = 'b0';
  overlays.forEach((o, k) => { inputs.push('-loop', '1', '-i', o.png); fc += `;[${cur}][${k + 2}:v]overlay=x=${o.x}:y=${o.y}:${o.en}[o${k}]`; cur = `o${k}`; });
  if (texts.length) { fc += `;[${cur}]${texts.join(',')}[tx]`; cur = 'tx'; }
  if (zooms.length) {
    const Z = `(1+${zooms.map((z) => `${(z.z - 1).toFixed(3)}*${wExpr(z)}`).join('+')})`;
    const CX = `(1920/2+${zooms.map((z) => `${(z.x * 0.75 - 960).toFixed(1)}*${wExpr(z)}`).join('+')})`;
    const CY = `(1080/2+${zooms.map((z) => `${(z.y * 0.75 - 540).toFixed(1)}*${wExpr(z)}`).join('+')})`;
    fc += `;[${cur}]scale=w='1920*${Z}':h='1080*${Z}':eval=frame:flags=bicubic,crop=1920:1080:x='clip(${CX}*${Z}-960,0,1920*${Z}-1920)':y='clip(${CY}*${Z}-540,0,1080*${Z}-1080)'[v]`;
  } else fc += `;[${cur}]scale=1920:1080:flags=lanczos[v]`;
  fc += `;[1:a]adelay=${Math.round(PRE * 1000)}:all=1,apad[a]`;
  const out = join(work, `clip_${s.id}.mp4`);
  ff([...inputs, '-filter_complex', fc, '-map', '[v]', '-map', '[a]', '-t', L.toFixed(3), '-r', '30', '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '19', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '160k', '-ar', '44100', '-ac', '1', out], work);
  clipFiles.push(`file 'clip_${s.id}.mp4'`);
  console.log(`  ${s.id} ${s.title}: ${L.toFixed(1)}s, ${notes.length} notes${speed > 1 ? `, footage x${speed.toFixed(1)}` : ''}`);
});
writeFileSync(join(work, 'clips.txt'), clipFiles.join('\n') + '\n');
const final = join(outRoot, `${name}.mp4`);
ff(['-f', 'concat', '-safe', '0', '-i', 'clips.txt', '-c', 'copy', final], work);
const total = duration(final);
console.log(`${name} → ${final} (${Math.floor(total / 60)}:${String(Math.round(total % 60)).padStart(2, '0')})`);
