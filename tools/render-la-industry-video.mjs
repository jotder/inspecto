// Draft renderer for the LA industry videos (docs/okf/product/module/la-core/brochure-video-industry-scripts.md).
// One `## <name>` section of that file becomes <out>/<name>.mp4: the stills with a slow push-in, lower-third text,
// the "Example · synthetic data" corner tag, Windows offline text-to-speech and burned-in captions. No music.
// Needs ffmpeg (winget Gyan.FFmpeg).
//
//   node tools/render-la-industry-video.mjs <name|all> [--out DIR] [--voice "Microsoft Mark"] [--rate 2]
import { spawnSync } from 'node:child_process';
import { readFileSync, mkdirSync, writeFileSync, existsSync, copyFileSync, readdirSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const scriptsFile = join(root, 'docs/okf/product/module/la-core/brochure-video-industry-scripts.md');
const assets = join(root, 'docs/okf/product/assets/la-video');

const argv = process.argv.slice(2);
const opt = (name, dflt) => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : dflt; };
const which = argv[0];
const outRoot = resolve(opt('--out', 'C:/sandbox/la-video-out'));
const voice = opt('--voice', 'Microsoft Mark');
const rate = opt('--rate', '2');
if (!which) { console.error('usage: render-la-industry-video.mjs <name|all> [--out DIR] [--voice NAME] [--rate N]'); process.exit(2); }

function findFfmpeg() {
  if (!spawnSync('ffmpeg', ['-version']).error) return 'ffmpeg';
  const base = join(process.env.LOCALAPPDATA ?? '', 'Microsoft/WinGet/Packages');
  if (existsSync(base)) {
    for (const d of readdirSync(base).filter((x) => x.startsWith('Gyan.FFmpeg'))) {
      for (const v of readdirSync(join(base, d))) {
        const exe = join(base, d, v, 'bin/ffmpeg.exe');
        if (existsSync(exe)) return exe;
      }
    }
  }
  throw new Error('ffmpeg not found (winget install Gyan.FFmpeg)');
}
const FF = findFfmpeg();
const FFPROBE = FF.replace(/ffmpeg(\.exe)?$/, 'ffprobe$1');
function run(cmd, args, cwd) {
  const r = spawnSync(cmd, args, { cwd, encoding: 'utf8', maxBuffer: 1 << 28 });
  if (r.status !== 0) throw new Error(`${cmd} failed:\n${(r.stderr || '').split('\n').slice(-25).join('\n')}`);
  return r.stdout;
}
const ff = (args, cwd) => run(FF, ['-y', '-hide_banner', '-loglevel', 'error', ...args], cwd);
const duration = (f) => parseFloat(run(FFPROBE, ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', f]));

// ---------- parse ----------
const md = readFileSync(scriptsFile, 'utf8');
const sections = [...md.matchAll(/^## (\S+)$/gm)];
function parse(name) {
  const i = sections.findIndex((s) => s[1] === name);
  if (i < 0) throw new Error(`no video "${name}" in ${scriptsFile}`);
  const sec = md.slice(sections[i].index, i + 1 < sections.length ? sections[i + 1].index : md.length);
  const hs = [...sec.matchAll(/^### (.+)$/gm)];
  return hs.map((h, k) => {
    const body = sec.slice(h.index + h[0].length, k + 1 < hs.length ? hs[k + 1].index : sec.length);
    const f = (l) => (body.match(new RegExp(`^- ${l}:[ \\t]*(.*)$`, 'm'))?.[1] ?? '').trim();
    const stills = f('Still') === 'none' ? [] : f('Still').split(' + ').map((t) => {
      const cols = / @cols$/.test(t);
      return { path: join(assets, t.replace(/ @cols$/, '').trim()), cols };
    });
    return { name: h[1], stills, style: f('Style'), text: f('Text'), voice: f('Voice') };
  });
}

// ---------- render ----------
const W = 1920, H = 1080, FPS = 30, BG = '0x0b0f14';
const DT = 'drawtext=fontfile=font.ttf:expansion=none';
const lowerThird = (file) => `${DT}:textfile=${file}:fontsize=40:fontcolor=white:box=1:boxcolor=black@0.6:boxborderw=18:x=(w-text_w)/2:y=h-250`;
const tag = `${DT}:text=Example · synthetic data:fontsize=26:fontcolor=white@0.85:box=1:boxcolor=black@0.5:boxborderw=8:x=40:y=36`;
const enc = ['-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20'];

function stillClip(work, st, dur, out, { lower, blur, title }) {
  const frames = Math.max(1, Math.round(dur * FPS));
  const f = [];
  if (st.cols) f.push('crop=1700:956:760:118'); // the Columns panel; the Discover row counts stay out of frame
  f.push(`scale=${W * 2}:${H * 2}`, `zoompan=z='min(1+0.00015*on,1.08)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=${frames}:s=${W}x${H}:fps=${FPS}`);
  if (blur) f.push('boxblur=18:2', 'eq=brightness=-0.15');
  if (title) f.push(`${DT}:textfile=${title}:fontsize=72:fontcolor=white:line_spacing=22:text_align=center:x=(w-text_w)/2:y=(h-text_h)/2`);
  if (lower) f.push(lowerThird(lower));
  if (st.cols) f.push('drawbox=x=0:y=1050:w=iw:h=30:color=0x0b0f14:t=fill'); // hides the first Discover line at the crop edge
  f.push(tag, 'format=yuv420p');
  ff(['-loop', '1', '-i', st.path, '-vf', f.join(','), '-frames:v', String(frames), '-r', String(FPS), ...enc, out], work);
}
function cardClip(work, dur, out, textFile, size) {
  const frames = Math.max(1, Math.round(dur * FPS));
  const vf = `${DT}:textfile=${textFile}:fontsize=${size}:fontcolor=white:line_spacing=22:text_align=center:x=(w-text_w)/2:y=(h-text_h)/2,${tag},format=yuv420p`;
  ff(['-f', 'lavfi', '-i', `color=c=${BG}:s=${W}x${H}:r=${FPS}`, '-vf', vf, '-frames:v', String(frames), ...enc, out], work);
}
function splitClip(work, a, b, dur, out) {
  const frames = Math.max(1, Math.round(dur * FPS));
  const fc = `[0:v]scale=960:540,pad=${W}:${H}:0:270:${BG}[l];[1:v]scale=960:540[r];[l][r]overlay=960:270,${tag},format=yuv420p`;
  ff(['-loop', '1', '-i', a.path, '-loop', '1', '-i', b.path, '-filter_complex', fc, '-frames:v', String(frames), '-r', String(FPS), ...enc, out], work);
}
const srtTime = (t) => {
  const ms = Math.round(t * 1000); const p = (v, w = 2) => String(v).padStart(w, '0');
  return `${p(Math.floor(ms / 3600000))}:${p(Math.floor(ms / 60000) % 60)}:${p(Math.floor(ms / 1000) % 60)},${p(ms % 1000, 3)}`;
};

function render(name) {
  const shots = parse(name);
  const work = join(outRoot, '_work', name);
  mkdirSync(work, { recursive: true });
  copyFileSync('C:/Windows/Fonts/arial.ttf', join(work, 'font.ttf'));

  const items = shots.map((s, n) => ({ file: join(work, `v${n}.wav`), text: s.voice }));
  writeFileSync(join(work, 'items.json'), JSON.stringify(items), 'utf8');
  run('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', join(root, 'tools/la-video-tts.ps1'), '-Items', join(work, 'items.json'), '-Voice', voice, '-Rate', rate]);

  let t = 0; const srt = []; const v = []; const a = [];
  shots.forEach((s, n) => {
    const pre = /conclusion|reveal/i.test(s.name) ? 1.0 : 0.3;
    const audioLen = duration(join(work, `v${n}.wav`));
    const dur = Math.max(4, pre + audioLen + 0.6);
    const out = join(work, `s${n}.mp4`);
    const lines = s.text.split(' · ').filter(Boolean);
    writeFileSync(join(work, `t${n}.txt`), lines.join('\n'), 'utf8');
    writeFileSync(join(work, `l${n}.txt`), lines.join('  ·  '), 'utf8');
    const lower = s.text ? `l${n}.txt` : null;
    if (s.style === 'blur') stillClip(work, s.stills[0], dur, out, { blur: true, title: `t${n}.txt` });
    else if (s.stills.length === 0) cardClip(work, dur, out, `t${n}.txt`, /End card/.test(s.name) ? 64 : 56);
    else if (s.stills.length === 2) splitClip(work, s.stills[0], s.stills[1], dur, out);
    else stillClip(work, s.stills[0], dur, out, { lower });
    ff(['-i', join(work, `v${n}.wav`), '-af', `adelay=${Math.round(pre * 1000)}:all=1,apad`, '-t', String(dur), '-ar', '44100', '-ac', '1', join(work, `a${n}.wav`)], work);
    v.push(`file 's${n}.mp4'`); a.push(`file 'a${n}.wav'`);
    srt.push(`${n + 1}\n${srtTime(t + pre)} --> ${srtTime(t + pre + audioLen)}\n${s.voice}\n`);
    t += dur;
    console.log(`  ${n + 1}. ${s.name}: ${dur.toFixed(1)}s`);
  });
  writeFileSync(join(work, 'v.txt'), v.join('\n') + '\n');
  writeFileSync(join(work, 'a.txt'), a.join('\n') + '\n');
  writeFileSync(join(work, 'case.srt'), srt.join('\n'), 'utf8');
  ff(['-f', 'concat', '-safe', '0', '-i', 'v.txt', '-c', 'copy', 'video.mp4'], work);
  ff(['-f', 'concat', '-safe', '0', '-i', 'a.txt', '-c', 'copy', 'audio.wav'], work);
  const final = join(outRoot, `${name}.mp4`);
  ff(['-i', 'video.mp4', '-i', 'audio.wav', '-vf', "subtitles=case.srt:force_style='FontName=Arial,FontSize=9,BorderStyle=3,Outline=2,Shadow=0,MarginV=24'", ...enc, '-c:a', 'aac', '-b:a', '160k', '-shortest', final], work);
  console.log(`${name} → ${final} (${Math.floor(t / 60)}:${String(Math.round(t % 60)).padStart(2, '0')})`);
}

for (const s of which === 'all' ? sections.map((x) => x[1]) : [which]) { console.log(s); render(s); }
