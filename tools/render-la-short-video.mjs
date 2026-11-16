// Vertical (1080 x 1920) collage-style short from docs/okf/product/module/la-core/brochure-video-short-scripts.md.
// Real product stills as torn-paper prints on a grainy noir backdrop, the camera panning across each print,
// three-word serif captions on a cream strip, Windows offline text-to-speech. No music. Needs ffmpeg.
//
//   node tools/render-la-short-video.mjs <name> [--out DIR] [--voice "Microsoft Mark"] [--rate 1]
import { spawnSync } from 'node:child_process';
import { readFileSync, mkdirSync, writeFileSync, existsSync, copyFileSync, readdirSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const scriptsFile = join(root, 'docs/okf/product/module/la-core/brochure-video-short-scripts.md');
const assets = join(root, 'docs/okf/product/assets/la-video');
const argv = process.argv.slice(2);
const opt = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const name = argv[0];
const outRoot = resolve(opt('--out', 'C:/sandbox/la-video-out'));
const voice = opt('--voice', 'Microsoft Mark');
const rate = opt('--rate', '1');
if (!name) { console.error('usage: render-la-short-video.mjs <name>'); process.exit(2); }

function findFfmpeg() {
  if (!spawnSync('ffmpeg', ['-version']).error) return 'ffmpeg';
  const base = join(process.env.LOCALAPPDATA ?? '', 'Microsoft/WinGet/Packages');
  for (const d of existsSync(base) ? readdirSync(base).filter((x) => x.startsWith('Gyan.FFmpeg')) : []) {
    for (const v of readdirSync(join(base, d))) { const e = join(base, d, v, 'bin/ffmpeg.exe'); if (existsSync(e)) return e; }
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
const ff = (a, cwd) => run(FF, ['-y', '-hide_banner', '-loglevel', 'error', ...a], cwd);
const duration = (f) => parseFloat(run(FFPROBE, ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', f]));

// ---------- parse ----------
const md = readFileSync(scriptsFile, 'utf8');
const secs = [...md.matchAll(/^## (\S+)$/gm)];
const si = secs.findIndex((s) => s[1] === name);
if (si < 0) throw new Error(`no video "${name}"`);
const sec = md.slice(secs[si].index, si + 1 < secs.length ? secs[si + 1].index : md.length);
const hs = [...sec.matchAll(/^### (.+)$/gm)];
const shots = hs.map((h, k) => {
  const body = sec.slice(h.index + h[0].length, k + 1 < hs.length ? hs[k + 1].index : sec.length);
  const f = (l) => (body.match(new RegExp(`^- ${l}:[ \\t]*(.*)$`, 'm'))?.[1] ?? '').trim();
  const st = f('Still');
  const m = st.match(/^(\S+) @(\d+),(\d+),(\d+),(\d+),(\d+)$/);
  let still = m ? { path: join(assets, m[1]), S: +m[2], x0: +m[3], y0: +m[4], x1: +m[5], y1: +m[6] } : null;
  // `- Live: <take> <mark>[+s] <mark>[+s]` plus `- Window: S,x0,y0,x1,y1` (video pixels): a segment of a live recording
  const lv = f('Live').match(/^(\S+) (\w+)([+-][\d.]+)? (\w+)([+-][\d.]+)?$/);
  if (lv) {
    const take = join(outRoot, '_live', lv[1]);
    const marks = JSON.parse(readFileSync(join(take, 'marks.json'), 'utf8'));
    const w = f('Window').split(',').map(Number);
    still = { live: join(take, `${lv[1]}-live.mp4`), from: marks[lv[2]] + Number(lv[3] ?? 0), to: marks[lv[4]] + Number(lv[5] ?? 0), S: w[0], x0: w[1], y0: w[2], x1: w[3], y1: w[4] };
  }
  return { name: h[1], still, text: f('Text'), voice: f('Voice') };
});

// ---------- assets: torn-paper mask, newsprint scraps, bg ----------
const W = 1080, H = 1920, FPS = 30, PW = 1040;
const work = join(outRoot, '_work', name);
mkdirSync(work, { recursive: true });
copyFileSync('C:/Windows/Fonts/georgia.ttf', join(work, 'font.ttf'));
const wob = (v, p) => `(4*sin(${v}*0.11+${p})+3*sin(${v}*0.37+${p * 2})+2*random(1))`;
const tornMask = `geq=lum='if(lt(min(min(X+${wob('Y', 1)},${PW - 1}-X+${wob('Y', 2)}),min(Y+${wob('X', 3)},${PW - 1}-Y+${wob('X', 4)})),11),0,255)'`;
ff(['-f', 'lavfi', '-i', `color=c=white:s=${PW}x${PW},format=gray,${tornMask},gblur=sigma=2.5,geq=lum='if(gt(lum(X,Y),128),255,0)'`, '-frames:v', '1', 'mask.png'], work);
const scrap = (out, flip) => ff(['-f', 'lavfi', '-i',
  `color=c=0xc7c1b2:s=${W}x300,noise=alls=28:allf=u,drawgrid=w=iw:h=17:t=4:c=black@0.16,format=rgba,` +
  `geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='if(lt(Y,236+12*sin(X*0.05)+8*sin(X*0.21)+4*random(1)),255,0)'${flip ? ',vflip' : ''}`,
  '-frames:v', '1', out], work);
scrap('scrap_top.png', false);
scrap('scrap_bot.png', true);

// ---------- narration ----------
writeFileSync(join(work, 'items.json'), JSON.stringify(shots.map((s, n) => ({ file: join(work, `v${n}.wav`), text: s.voice }))), 'utf8');
run('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', join(root, 'tools/la-video-tts.ps1'), '-Items', join(work, 'items.json'), '-Voice', voice, '-Rate', rate]);

const enc = ['-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20', '-pix_fmt', 'yuv420p'];
const bg = `color=c=0x0d0d0d:s=${W}x${H}:r=${FPS},noise=alls=18:allf=t+u,vignette=angle=0.9`;
const DT = 'drawtext=fontfile=font.ttf:expansion=none';
const angles = [-2.2, 1.6, -1.2, 2.0, -1.8, 1.3, -2.0, 1.1, -1.4, 1.8, -1.0];
let t = 0; const v = [], a = [], srt = [];

shots.forEach((s, n) => {
  const pre = /conclusion|reveal|close/i.test(s.name) ? 0.8 : 0.3;
  const aud = duration(join(work, `v${n}.wav`));
  const D = Math.max(4.5, pre + aud + 0.5);
  // captions: three words at a time, timed by character count across the spoken part
  const words = s.voice.split(/\s+/);
  const chunks = []; for (let i = 0; i < words.length; i += 3) chunks.push(words.slice(i, i + 3).join(' '));
  const total = chunks.reduce((q, c) => q + c.length, 0); let at = pre;
  const caps = chunks.map((c, k) => {
    const len = aud * c.length / total; const r = { c, a: at, b: at + len }; at += len;
    writeFileSync(join(work, `c${n}_${k}.txt`), c.toLowerCase(), 'utf8'); return r;
  });
  const capF = caps.map((r, k) => `${DT}:textfile=c${n}_${k}.txt:fontsize=68:fontcolor=0x1a1a1a:box=1:boxcolor=0xe6dfce:boxborderw=22:x=(w-text_w)/2:y=1500:enable='between(t,${r.a.toFixed(2)},${r.b.toFixed(2)})'`).join(',');
  const out = join(work, `s${n}.mp4`);
  const ang = angles[n % angles.length];
  const inputs = ['-f', 'lavfi', '-i', bg];
  let fc;
  if (s.still) {
    const { S, x0, y0, x1, y1 } = s.still;
    let pre = '';
    if (s.still.live) {
      // the live clip is stretched (0.75x to 1.5x) to the narration, then its last frame is held if it still runs short
      const seg = s.still.to - s.still.from, f = Math.min(1.5, Math.max(0.75, D / seg));
      inputs.push('-ss', s.still.from.toFixed(2), '-t', seg.toFixed(2), '-i', s.still.live);
      pre = `setpts=PTS*${f.toFixed(3)},fps=${FPS},tpad=stop_mode=clone:stop_duration=${D.toFixed(2)},`;
    } else inputs.push('-framerate', String(FPS), '-loop', '1', '-i', s.still.path);
    inputs.push('-loop', '1', '-i', join(work, 'mask.png'), '-loop', '1', '-i', join(work, 'scrap_top.png'), '-loop', '1', '-i', join(work, 'scrap_bot.png'));
    fc = `[1:v]${pre}crop=${S}:${S}:'${x0}+(${x1}-${x0})*min(t/${D.toFixed(2)},1)':'${y0}+(${y1}-${y0})*min(t/${D.toFixed(2)},1)',scale=1000:1000:flags=lanczos,pad=${PW}:${PW}:20:20:color=0xe6dfce,format=rgba[pp];` +
      `[2:v]format=gray[m];[pp][m]alphamerge,rotate=a=${ang}*PI/180:c=none:ow=rotw(${ang}*PI/180):oh=roth(${ang}*PI/180),split[ra][rb];` +
      `[ra]colorchannelmixer=rr=0:gg=0:bb=0:aa=0.6,boxblur=20[sh];` +
      `[0:v][sh]overlay=x=(main_w-overlay_w)/2+16:y=330+24:shortest=1[b1];[b1][rb]overlay=x=(main_w-overlay_w)/2:y=330[b2];` +
      `[b2][3:v]overlay=0:0[b3];[b3][4:v]overlay=0:main_h-overlay_h[b4];[b4]${capF},noise=alls=7:allf=t,format=yuv420p`;
  } else {
    const lines = s.text.split(' · ');
    lines.forEach((l, k) => writeFileSync(join(work, `k${n}_${k}.txt`), l, 'utf8'));
    inputs.push('-loop', '1', '-i', join(work, 'scrap_top.png'), '-loop', '1', '-i', join(work, 'scrap_bot.png'));
    const lineF = lines.map((l, k) => `${DT}:textfile=k${n}_${k}.txt:fontsize=${k === 0 ? 96 : 64}:fontcolor=0xe6dfce:x=(w-text_w)/2:y=${760 + k * 150}`).join(',');
    fc = `[0:v][1:v]overlay=0:0[b1];[b1][2:v]overlay=0:main_h-overlay_h[b2];[b2]${lineF},${capF},noise=alls=7:allf=t,format=yuv420p`;
  }
  ff([...inputs, '-filter_complex', fc, '-t', D.toFixed(2), '-r', String(FPS), ...enc, out], work);
  ff(['-i', join(work, `v${n}.wav`), '-af', `adelay=${Math.round(pre * 1000)}:all=1,apad`, '-t', D.toFixed(2), '-ar', '44100', '-ac', '1', join(work, `a${n}.wav`)], work);
  v.push(`file 's${n}.mp4'`); a.push(`file 'a${n}.wav'`);
  t += D; console.log(`  ${n + 1}. ${s.name}: ${D.toFixed(1)}s`);
});
writeFileSync(join(work, 'v.txt'), v.join('\n') + '\n');
writeFileSync(join(work, 'a.txt'), a.join('\n') + '\n');
ff(['-f', 'concat', '-safe', '0', '-i', 'v.txt', '-c', 'copy', 'video.mp4'], work);
ff(['-f', 'concat', '-safe', '0', '-i', 'a.txt', '-c', 'copy', 'audio.wav'], work);
const final = join(outRoot, `${name}.mp4`);
ff(['-i', 'video.mp4', '-i', 'audio.wav', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '160k', '-shortest', final], work);
console.log(`${name} → ${final} (${Math.floor(t / 60)}:${String(Math.round(t % 60)).padStart(2, '0')})`);
