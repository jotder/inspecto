// Records a LIVE walk through an Inspecto LA investigation: headless Chrome drives the real product (default
// http://127.0.0.1:8097, a Basic install with the showcase Spaces) with a visible cursor and the screen is captured
// with the DevTools screencast, then encoded to 1920x1080 / 30 fps.
//
//   node tools/record-la-live.mjs <scenario> [--base URL] [--out DIR] [--probe]
//   --probe  also saves a PNG after every step (to check the click path before a real take)
// Scenarios live in this file (SCENARIOS). Read-only against the product: it only opens, hovers, clicks and types.
import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, existsSync, readdirSync, rmSync, readFileSync, copyFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';

// ffmpeg: on PATH, else the winget Gyan.FFmpeg install
function findFfmpeg() {
  if (!spawnSync('ffmpeg', ['-version']).error) return 'ffmpeg';
  const b = join(process.env.LOCALAPPDATA ?? '', 'Microsoft/WinGet/Packages');
  for (const d of existsSync(b) ? readdirSync(b).filter((x) => x.startsWith('Gyan.FFmpeg')) : []) for (const v of readdirSync(join(b, d))) { const e = join(b, d, v, 'bin/ffmpeg.exe'); if (existsSync(e)) return e; }
  throw new Error('ffmpeg not found');
}
// The bounding box [x, y, w, h] (pixels of the images) of what differs between two PNGs, or null when nothing does.
// ffmpeg subtracts them, thresholds the difference and lets bbox find the box: no image library needed.
export function brightBox(png, region, min = 165) {
  const crop = region ? `crop=${region[2]}:${region[3]}:${region[0]}:${region[1]},` : '';
  const fc = `[0:v]${crop}format=gray,lut=y='if(gt(val,${min}),255,0)',bbox=min_val=128`;
  const r = spawnSync(findFfmpeg(), ['-hide_banner', '-i', png, '-filter_complex', fc, '-f', 'null', '-'], { encoding: 'utf8' });
  const m = [...(r.stderr || '').matchAll(/x1:(\d+) x2:(\d+) y1:(\d+) y2:(\d+)/g)].pop();
  if (!m) return null;
  const [x1, x2, y1, y2] = m.slice(1).map(Number);
  return [x1 + (region ? region[0] : 0), y1 + (region ? region[1] : 0), x2 - x1 + 1, y2 - y1 + 1];
}
export function diffBox(a, b, region) {
  const crop = region ? `crop=${region[2]}:${region[3]}:${region[0]}:${region[1]},` : '';
  const fc = `[0:v]${crop}format=gray[a];[1:v]${crop}format=gray[b];[a][b]blend=all_mode=difference,lut=y='if(gt(val,28),255,0)',bbox=min_val=128`;
  const r = spawnSync(findFfmpeg(), ['-hide_banner', '-i', a, '-i', b, '-filter_complex', fc, '-f', 'null', '-'], { encoding: 'utf8' });
  const m = [...(r.stderr || '').matchAll(/x1:(\d+) x2:(\d+) y1:(\d+) y2:(\d+)/g)].pop();
  if (!m) return null;
  const [x1, x2, y1, y2] = m.slice(1).map(Number);
  const [x, y, w, h] = [x1, y1, x2 - x1 + 1, y2 - y1 + 1];
  if (w < 8 || h < 8) return null;
  return [x + (region ? region[0] : 0), y + (region ? region[1] : 0), w, h];
}

const argv = process.argv.slice(2);
const opt = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const scenarioName = argv[0];
const base = opt('--base', 'http://127.0.0.1:8099');   // the Basic install of 2026-11-16 (C:/sandbox/la-basic-20261116)
const outRoot = resolve(opt('--out', 'C:/sandbox/la-video-out/_live'));
const probe = argv.includes('--probe');
const DPR = Number(opt('--dpr', '1.2'));
const PORT = Number(opt('--port', '9333')), VW = 1600, VH = 900;
const takeName = opt('--take', scenarioName);                       // output folder / file name of the take
const cfg = opt('--config') ? JSON.parse(readFileSync(resolve(opt('--config')), 'utf8')) : {};
const timing = opt('--timing') ? JSON.parse(readFileSync(resolve(opt('--timing')), 'utf8')) : {};

// ---------- scenarios: [name, async (h) => {...}] ----------
const SCENARIOS = {
  // The narrated demo: every scene is paced by the length of its voice-over (--timing), so the actions land as they are spoken.
  // One config-driven demo for every case: --config {space, view, dataset, entity, entityType?}. Nine narrated scenes S01..S09.
  case_demo: async (h, opts = {}) => {
    const sc = (id, fn) => (!opts.only || opts.only.includes(id) ? h.scene(id, fn) : null);   // opts.only: run just these scenes
    const soft = async (fn) => { try { await fn(); } catch (e) { console.log('  skipped:', String(e.message).slice(0, 90)); } };
    await h.setSpace(cfg.space);
    await h.goto('/'); await h.waitText('Saved views'); await h.pause(600);

    await sc('S01', async () => {                 // the Space and its saved views
      await h.moveTo(800, 360, 1500);
      await h.atFrac(0.72);
      await soft(async () => { await h.clickSel('input[placeholder="Search saved views"]'); await h.type(cfg.view.slice(0, 8), 110); });
      await h.pause(700); await h.atFrac(0.92);
      await soft(() => h.hoverText(cfg.view));
    });
    await sc('S02', async () => {                 // the Dataset the records land in
      await h.clickSel('a[href="/data"]'); await h.pause(1200);
      await h.atFrac(0.25);
      await soft(() => h.clickExact(cfg.dataset));
    });
    await sc('S03', async () => {                 // columns: what each one is
      await h.moveTo(760, 520, 1500); await h.atFrac(0.4); await h.moveTo(900, 570, 1600); await h.atFrac(0.7); await h.moveTo(1100, 600, 1600);
    });
    await sc('S04', async () => {                 // the saved view draws
      await h.goto(`/link-analysis?view=${cfg.view}`); if (probe) { console.log('S04 URL', await h.ev('location.href')); await h.shot('s04-after-goto'); } await h.waitText('Legend'); await h.pause(2500);
      await soft(() => h.clickLabel('Minimise the legend'));
      await h.moveTo(700, 420, 1600); await h.atFrac(0.5); await h.moveTo(900, 560, 2200); await h.atFrac(0.8); await h.moveTo(640, 480, 2000);
    });
    await sc('S05', async () => {                 // first sweep: busiest nodes
      await h.atFrac(0.18); await soft(() => h.clickText('Busiest nodes'));
      await h.atFrac(0.50); await soft(() => h.hoverNth('takes part', 0));
      await h.atFrac(0.74); await soft(() => h.hoverNth('takes part', 1));
      await h.atFrac(0.90); await soft(() => h.hoverNth('takes part', 0));
    });
    await sc('S06', async () => {                 // start a graph from one entity
      await h.goto('/link-analysis?view=x'); await h.waitText('Start from'); await h.pause(800);
      await h.atFrac(0.18); await soft(async () => { await h.clickRightOf('Dataset'); await h.pause(700); await h.clickExact(cfg.dataset); });
      if (cfg.entityType) { await h.pause(600); await soft(async () => { await h.clickRightOf('Entity Type'); await h.pause(700); await h.clickExact(cfg.entityType); }); }
      const INPUT = "document.querySelector('input[type=text],input:not([type]),input[type=search]')";
      await soft(() => h.waitFor('!!' + INPUT, 12000));
      await h.atFrac(0.42);
      await soft(async () => { const p = await h.locate(INPUT); await h.click(p.x, p.y); await h.type(cfg.entity, 85); });
      // the suggestion list (an option per matching entity) opens under the input; pick the first, which is the typed id
      await soft(() => h.waitFor("!!document.querySelector('[role=option],mat-option,.mat-mdc-option') || /links, first seen/.test(document.body.textContent)", 9000));
      const OPT = "document.querySelector('[role=option],mat-option,.mat-mdc-option')";
      await h.atFrac(0.72); await soft(async () => { const p = await h.locate(OPT); await h.moveTo(p.x, p.y, 800); });
      await h.atFrac(0.84); await soft(async () => { const p = await h.locate(OPT); await h.click(p.x, p.y); });
      await h.pause(500); await h.atFrac(0.94);
      await soft(() => h.clickButton('Draw'));
      // if the graph did not draw (the list still covered Draw), submit from the keyboard
      await h.pause(2500);
      if (!(await h.ev("/Drew \\d+ entity/.test(document.body.textContent)"))) { await soft(async () => { const p = await h.locate(INPUT); await h.click(p.x, p.y); await h.key('Enter'); }); }
    });
    await sc('S07', async () => {                 // the reveal
      await h.pause(2200);
      await soft(async () => { await h.clickLabel('Display options'); await h.pause(700); await h.clickText('Node labels'); await h.escape(); });
      await h.atFrac(0.30); await h.moveTo(667, 420, 1400);
      await h.atFrac(0.50); await h.moveTo(450, 330, 1600);
      await h.atFrac(0.72); await h.moveTo(560, 650, 1600);
      await h.atFrac(0.86); await h.moveTo(865, 605, 1500);
    });
    await sc('S08', async () => {                 // the table behind the graph
      await h.atFrac(0.15); await soft(() => h.clickLabel('Show the table'));
      await h.pause(1500); await h.moveTo(400, 760, 1400);
      await h.atFrac(0.55); await h.moveTo(700, 800, 1400); await h.atFrac(0.78); await h.moveTo(400, 840, 1400);
    });
    await sc('S09', async () => {                 // conclusion: back on the graph, slow drift
      await h.moveTo(600, 560, 2200); await h.atFrac(0.5); await h.moveTo(440, 700, 2500);
    });
  },
  // The Standard part of a combined Basic + Standard video (render-la-demo-video.mjs records it as a second take).
  std_ext: async (h) => SCENARIOS.case_demo_std(h, { signInOnly: true }),
  // A declarative episode (training series, LA-TRAINING-SERIES-1): --config {steps:[...]} (docs: tools/record-la-live.mjs header).
  // Steps run in order. A step is one object with one verb:
  //   {space:"la-showcase"} {signin:"Demo Analyst"} {open:"/link-analysis?view=x"} {wait:1500} {waitText:"Legend"}
  //   {click:"Busiest nodes"} {exact:"Open…"} {label:"Display options"} {button:"Draw"} {sel:"input[type=search]"} {rightOf:"Dataset"}
  //   {type:"HUB-01"} {key:"G"} {escape:true} {hover:"takes part"} {move:[x,y]}
  //   {impact:"expand", steps:[...], region:[x,y,w,h], text:"label"} runs its steps and marks what changed (image diff before/after)
  //   {shot:"after-expand"} {dump:"toolbox"} (a screenshot / the visible text + buttons, saved beside the take)
  //   {scene:"S02", steps:[...]} runs its steps, then holds until the scene's narration length (--timing) has passed.
  // A verb may carry {soft:true} (a missing control is logged, not fatal) and {note:"..."} (free text, ignored).
  episode: async (h) => {
    const run = async (steps) => {
      for (const st of steps) {
        const go = async () => {
          if (st.scene) return h.scene(st.scene, () => run(st.steps || []));
          if ('space' in st) return h.setSpace(st.space);
          if ('signin' in st) { await h.goto('/sign-in'); await h.waitText('Choose a user'); await h.clickExact(st.signin); return h.pause(2500); }
          if ('open' in st) { await h.goto(st.open); return h.pause(st.settle ?? 1200); }
          if ('wait' in st) return h.pause(st.wait);
          if ('waitText' in st) return h.waitText(st.waitText);
          if ('click' in st) return h.clickText(st.click);
          if ('exact' in st) return h.clickExact(st.exact, st.nth ?? 0);
          if ('label' in st) return h.clickLabel(st.label);
          if ('hoverLabel' in st) return h.hoverLabel(st.hoverLabel);
          if ('hoverSel' in st) return h.hoverSel(st.hoverSel);
          if ('log' in st) { console.log('LOG', JSON.stringify(await h.ev(st.log))); return; }   // print a page expression (to find selectors)
          if ('button' in st) return h.clickButton(st.button);
          if ('sel' in st) return h.clickSel(st.sel);
          if ('rightOf' in st) return h.clickRightOf(st.rightOf);
          if (st.selectAll) { await h.ev('document.activeElement && document.activeElement.select && document.activeElement.select()'); return; }   // select the focused field's text before typing over it
          if ('type' in st) return h.type(st.type, st.perChar ?? 85);
          if ('key' in st) return h.key(st.key);
          if ('kb' in st) return h.kb(st.kb, st.mod ?? 0, st.repeat ?? 1);   // a key with modifiers (2 = Ctrl, 8 = Shift), e.g. ArrowLeft, +, a
          if ('drag' in st) return h.drag(st.drag, st.shift ? 8 : 0);       // press at the first CSS point, move through the rest, release
          if (st.escape) return h.escape();
          if ('reveal' in st) return h.reveal(st.reveal);
          if ('download' in st) return h.download(st.download, st);
          if ('upload' in st) return h.upload(st.upload, st.file);
          if (st.undo) return h.undo();
          if ('hover' in st) return h.hoverText(st.hover);
          if ('move' in st) return h.moveTo(st.move[0], st.move[1], st.ms ?? 900);
          if ('pick' in st) return h.pick(st.pick, st);
          if ('at' in st) return h.at(st.at, st);
          if ('impact' in st) return h.impact(st.impact, () => run(st.steps || []), st);
          if ('mark' in st) return h.markBox(st.mark, st.box, st.text);
          if ('shot' in st) return h.shot(st.shot);
          if ('dump' in st) return h.dump(st.dump);
          if ('note' in st) return undefined;
          throw new Error('unknown episode step: ' + JSON.stringify(st).slice(0, 80));
        };
        try { await go(); } catch (e) {
          if (!st.soft) { await h.shot('FAIL').catch(() => {}); throw new Error(`step ${JSON.stringify(st).slice(0, 100)}: ${e.message}`); }
          console.log('  skipped:', JSON.stringify(st).slice(0, 80), String(e.message).slice(0, 80));
        }
      }
    };
    await run(cfg.steps || []);
  },
  // Standard / Preview (demo build, fake login): sign in, the Basic intro (S01..S05), then the analyst's Investigation:
  // the start, the sealed step log, the rows, members and four-eyes, the Dossier (build, verify, download), the Entity List.
  // --config adds {user, invTitle, listTitle}.
  case_demo_std: async (h, opts = {}) => {
    const soft = async (fn) => { try { await fn(); } catch (e) { console.log('  skipped:', String(e.message).slice(0, 90)); } };
    await h.goto('/sign-in'); await h.waitText('Choose a user'); await h.pause(800);
    if (opts.signInOnly) {   // the Standard part of a combined video: sign in off camera, no sign-in scene, no Basic intro
      await h.clickExact(cfg.user); await h.pause(3000); await h.setSpace(cfg.space);
    } else {
    await h.scene('S00', async () => {                 // sign in: who you are decides what you may do
      await h.moveTo(700, 400, 1500); await h.atFrac(0.55); await soft(() => h.hoverText(cfg.user)); await h.atFrac(0.8);
      await h.clickExact(cfg.user); await h.pause(2500);
    });
    await SCENARIOS.case_demo(h, { only: ['S01', 'S02', 'S03', 'S04', 'S05'] });
    }
    await h.scene('S10', async () => {                 // open the analyst's Investigation
      await h.goto('/link-analysis'); await soft(() => h.waitFor("!!document.querySelector('[aria-label=File]')", 20000)); await h.pause(800);
      await h.atFrac(0.25); await soft(() => h.clickLabel('File')); await h.pause(900);
      await soft(() => h.clickExact('Open…')); await h.pause(1200);
      await h.atFrac(0.45); await soft(() => h.clickExact('Investigations')); await h.pause(1200);
      await h.atFrac(0.6); await soft(() => h.clickText(cfg.invTitle)); await h.pause(800);
      await h.atFrac(0.72); await soft(() => h.clickButton('Open'));
    });
    await h.scene('S11', async () => {                 // the drawn Working Set, ids on
      await h.pause(2500);
      await soft(async () => { await h.clickLabel('Display options'); await h.pause(700); await h.clickText('Node labels'); await h.escape(); });
      await h.atFrac(0.35); await h.moveTo(667, 420, 1400); await h.atFrac(0.6); await h.moveTo(450, 330, 1600); await h.atFrac(0.8); await h.moveTo(800, 560, 1500);
    });
    await h.scene('S12', async () => {                 // the sealed step log, then the rows
      await h.atFrac(0.12); await soft(() => h.clickExact('Step log')); await h.pause(1500);
      await h.moveTo(500, 780, 1400); await h.atFrac(0.55); await h.moveTo(700, 820, 1400);
      await h.atFrac(0.7); await soft(() => h.clickExact('Annotations')); await h.pause(1200); await h.moveTo(500, 800, 1200);
    });
    await h.scene('S13', async () => {                 // members, roles and the four-eyes rule
      await h.atFrac(0.15); await soft(() => h.clickLabel('Investigation actions')); await h.pause(800);
      await soft(() => h.clickExact('Members…')); await h.pause(1500);
      await h.moveTo(700, 450, 1500); await h.atFrac(0.85); await h.escape();
    });
    await h.scene('S14', async () => {                 // the Dossier: build, verify, download the sealed bundle
      await h.atFrac(0.08); await soft(() => h.clickLabel('Investigation actions')); await h.pause(800);
      await soft(() => h.clickExact('Build Dossier…')); await h.pause(1500);
      await h.atFrac(0.3); await soft(() => h.clickButton('Build Dossier'));
      // a large Investigation takes a while to pack: wait for the build to finish (Rebuild appears) before verifying
      await soft(() => h.waitFor("[...document.querySelectorAll('button')].some(b => b.offsetParent && b.innerText.trim() === 'Rebuild')", 30000)); await h.pause(800);
      await h.atFrac(0.55); await soft(() => h.clickButton('Verify this Dossier'));
      await soft(() => h.waitFor("/Log intact|Verified/.test(document.body.innerText)", 20000)); await h.pause(800);
      await h.atFrac(0.78); await soft(() => h.clickButton('Download Dossier (sealed)'));
    });
    await h.scene('S15', async () => {                 // the Entity List kept for next time
      await h.escape(); await h.goto('/entity-lists'); await h.pause(2000);
      await h.atFrac(0.3); await soft(() => h.clickText(cfg.listTitle)); await h.pause(1500);
      await h.atFrac(0.65); await h.moveTo(700, 500, 1600);
    });
    await h.scene('S16', async () => {                 // conclusion
      await h.moveTo(600, 560, 2200); await h.atFrac(0.5); await h.moveTo(440, 700, 2500);
    });
  },
  // Standard discovery 2: open the seeded Investigation (File > Open...), read its tab, then the Dossier.
  std_probe2: async (h) => {
    const buttons = async (tag) => console.log('BUTTONS', tag, JSON.stringify(await h.ev(`[...document.querySelectorAll('button,a,[role=tab]')].filter(b => b.offsetParent).map(b => (b.getAttribute('aria-label') || b.innerText || '').trim().replace(/\\s+/g, ' ')).filter(Boolean).slice(0, 120)`)));
    const tb = async (tag) => console.log('TOOLBOX', tag, JSON.stringify(await h.ev(`(() => { const t = [...document.querySelectorAll('*')].filter(e => e.children.length && (e.innerText||'').startsWith('TOOLBOX')).pop(); return (t ? t.innerText : '').slice(0, 2500); })()`)));
    await h.goto('/sign-in'); await h.waitText('Choose a user'); await h.clickExact(cfg.user); await h.pause(3000);
    await h.ev(`localStorage.setItem('inspecto.currentSpace', ${JSON.stringify(cfg.space)})`);
    await h.goto('/link-analysis'); await h.pause(3000);
    await h.waitFor("!!document.querySelector('[aria-label=File]')", 20000); await h.pause(800); await h.clickLabel('File'); await h.pause(800); await h.shot('file-menu');
    await h.clickExact('Open…'); await h.pause(1500); await h.clickExact('Investigations'); await h.pause(2000); await h.shot('open-dialog');
    console.log('DIALOG', JSON.stringify(await h.ev("(document.querySelector('[role=dialog],mat-dialog-container')||{}).innerText||''")));
    await h.clickText(cfg.invTitle); await h.pause(1500);
    await h.shot('inv-picked'); await buttons('inv-picked'); try { await h.clickButton('Open'); } catch { /* single click may open */ }
    await h.pause(7000); await h.shot('inv-open'); await buttons('inv-open'); await tb('inv-open');
    try { await h.clickLabel('Investigation actions'); await h.pause(1000); await h.shot('inv-actions'); console.log('MENU', JSON.stringify(await h.ev("[...document.querySelectorAll('[role=menuitem]')].map(e => e.innerText.trim())"))); await h.clickText('Dossier'); await h.pause(3000); await h.shot('dossier'); await buttons('dossier'); console.log('DOSSIERTEXT', JSON.stringify((await h.ev('document.body.innerText')).slice(-2500))); } catch (e) { console.log('no dossier', e.message.slice(0, 90)); }
  },
  // Standard-edition discovery on a demo build: sign in, draw, start an Investigation, look for the Dossier.
  std_probe: async (h) => {
    const buttons = async (tag) => console.log('BUTTONS', tag, JSON.stringify(await h.ev(`[...document.querySelectorAll('button,a,[role=tab]')].filter(b => b.offsetParent).map(b => (b.getAttribute('aria-label') || b.innerText || '').trim().replace(/\\s+/g, ' ')).filter(Boolean).slice(0, 90)`)));
    await h.goto('/sign-in'); await h.waitText('Choose a user'); await h.shot('signin');
    await h.clickExact(cfg.user); await h.pause(3000); await h.shot('after-signin');
    await h.ev(`localStorage.setItem('inspecto.currentSpace', ${JSON.stringify(cfg.space)})`);
    await h.goto('/link-analysis'); await h.pause(3000);
    await h.clickText('Start from the Investigation tab'); await h.pause(2000); await h.shot('inv-tab'); await buttons('inv-tab');
    await h.clickRightOf('Dataset'); await h.pause(700); await h.clickExact(cfg.dataset); await h.pause(900);
    const INPUT = "document.querySelector('input[type=text],input:not([type]),input[type=search]')";
    const p = await h.locate(INPUT); await h.click(p.x, p.y); await h.type(cfg.entity, 60);
    try { await h.waitFor("!!document.querySelector('[role=option],mat-option,.mat-mdc-option')", 9000); const o = await h.locate("document.querySelector('[role=option],mat-option,.mat-mdc-option')"); await h.click(o.x, o.y); } catch { console.log('no option'); }
    await h.pause(600); await h.clickButton('Start'); await h.pause(7000); await h.shot('started'); await buttons('started');
    console.log('TOOLBOX1', JSON.stringify(await h.ev("(() => { const t = [...document.querySelectorAll('*')].find(e => e.children.length && (e.innerText||'').startsWith('TOOLBOX')); return (t ? t.innerText : '').slice(0, 1800); })()")));
    try { await h.clickButton('Save'); await h.pause(1500); await h.shot('save-dialog'); await buttons('save-dialog'); console.log('DIALOG', JSON.stringify(await h.ev("(document.querySelector('[role=dialog],mat-dialog-container')||{}).innerText||''"))); } catch (e) { console.log('no save', e.message.slice(0, 90)); }
  },
  // Which earlier step stops a saved view from opening? Runs the demo's first scenes' actions one at a time.
  check2: async (h) => {
    const loaded = async (label) => { await h.goto(`/link-analysis?view=${cfg.view}`); await h.pause(7000); console.log('LOADED', label, await h.ev(`document.body.textContent.includes('Legend')`)); };
    await h.setSpace(cfg.space);
    await loaded('fresh');
    await h.goto('/'); await h.clickSel('input[placeholder="Search saved views"]'); await h.type(cfg.view.slice(0, 8), 40);
    await loaded('after home search');
    await h.goto('/'); await h.clickSel('a[href="/data"]'); await h.pause(1200);
    await loaded('after data page');
    await h.goto('/data'); await h.pause(1000); await h.clickExact(cfg.dataset); await h.pause(800);
    await loaded('after dataset click');
  },
  // Opens cfg.space / cfg.view and reports what the page shows (to find out why a case does not load).
  check: async (h) => {
    await h.setSpace(cfg.space); await h.goto(`/link-analysis?view=${cfg.view}`); await h.pause(9000);
    await h.shot('view');
    console.log('PAGE', JSON.stringify((await h.ev('document.body.innerText')).slice(0, 700)));
    await h.goto('/'); await h.pause(2500);
    console.log('HOME', JSON.stringify((await h.ev('document.body.innerText')).slice(0, 900)));
    await h.shot('home');
  },
  // Finds, for each case in --config {cases:[{id,space,dataset,entity}]}, the Entity Type in which the start entity is found.
  discover: async (h) => {
    const dlgOptions = () => h.ev(`(() => [...document.querySelectorAll('[role=dialog] *, mat-dialog-container *, .cdk-overlay-pane *')].filter(e => e.children.length === 0 && e.offsetParent && e.innerText && e.innerText.trim()).map(e => e.innerText.trim()).filter(t => !['Cancel', 'Dataset', 'Entity Type'].includes(t)))()`);
    const found = {};
    for (const c of cfg.cases) {
      try {
        await h.setSpace(c.space); await h.goto('/link-analysis?view=x'); await h.waitText('Start from'); await h.pause(900);
        await h.clickRightOf('Dataset'); await h.pause(500); await h.clickExact(c.dataset); await h.pause(700);
        await h.clickRightOf('Entity Type'); await h.pause(500);
        const opts = [...new Set(await dlgOptions())];
        await h.escape(); await h.pause(300);
        let hit = null;
        for (const o of opts) {
          await h.clickRightOf('Entity Type'); await h.pause(500); await h.clickExact(o); await h.pause(500);
          await h.ev(`(() => { const i = document.querySelector('input[type=text],input:not([type]),input[type=search]'); if (i) { i.value = ''; i.dispatchEvent(new Event('input', { bubbles: true })); } })()`);
          const p = await h.locate(`document.querySelector('input[type=text],input:not([type]),input[type=search]')`);
          await h.click(p.x, p.y); await h.type(c.entity, 30); await h.pause(c.wait ?? 1400);
          if (await h.ev(`document.body.textContent.includes('first seen')`)) { hit = o; break; }
        }
        found[c.id] = { entityType: hit, options: opts };
      } catch (e) { found[c.id] = { error: String(e.message).slice(0, 100) }; }
      console.log('DISCOVER', c.id, JSON.stringify(found[c.id]));
    }
    mkdirSync(join(outRoot, takeName), { recursive: true }); writeFileSync(join(outRoot, takeName, 'discover.json'), JSON.stringify(found, null, 1));
  },
  mule_demo: async (h) => {
    const soft = async (fn) => { try { await fn(); } catch (e) { console.log('  skipped:', String(e.message).slice(0, 90)); } };
    await h.setSpace('la-showcase');
    await h.goto('/'); await h.waitText('Saved views'); await h.pause(600);

    await h.scene('S01', async () => {                 // the Space and its saved views
      await h.moveTo(800, 360, 1500);
      await h.atFrac(0.72);
      await soft(async () => { await h.clickSel('input[placeholder="Search saved views"]'); await h.type('mule', 110); });
      await h.pause(700);
      await h.atFrac(0.92);
      await soft(() => h.hoverText('mule_layering_ring'));
    });
    await h.scene('S02', async () => {                 // the Dataset
      await h.clickSel('a[href="/data"]'); await h.pause(1200);
      await h.atFrac(0.2);
      await soft(() => h.clickText('mule_transfers_dataset'));
    });
    await h.scene('S03', async () => {                 // columns and mapping
      await h.atFrac(0.30); await soft(() => h.hoverText('PAYER_ACCOUNT'));
      await h.atFrac(0.45); await soft(() => h.hoverText('PAYEE_ACCOUNT'));
      await h.atFrac(0.58); await soft(() => h.hoverText('BOOKED_DATE'));
      await h.atFrac(0.74); await soft(() => h.hoverText('Carried'));
    });
    await h.scene('S04', async () => {                 // the saved view draws
      await h.goto('/link-analysis?view=mule_layering_ring'); await h.waitText('Graph counts'); await h.pause(1500);
      await soft(() => h.clickLabel('Minimise the legend'));
      await h.moveTo(700, 420, 1600); await h.atFrac(0.5); await h.moveTo(900, 560, 2200); await h.atFrac(0.8); await h.moveTo(640, 480, 2000);
    });
    await h.scene('S05', async () => {                 // the funnel in the corner
      await h.moveTo(560, 600, 1600); await h.atFrac(0.45); await h.moveTo(442, 667, 1400);
      await h.atFrac(0.7); await h.moveTo(470, 640, 1500);
    });
    await h.scene('S06', async () => {                 // busiest nodes
      await h.atFrac(0.18); await h.clickText('Busiest nodes');
      await h.atFrac(0.50); await soft(() => h.hoverText('MULE-HUB-01 takes part'));
      await h.atFrac(0.72); await soft(() => h.hoverText('ACC-1033 takes part'));
      await h.atFrac(0.88); await soft(() => h.hoverText('MULE-HUB-01 takes part'));
    });
    await h.scene('S07', async () => {                 // start a graph from one account
      await h.goto('/link-analysis?view=x'); await h.waitText('Start from'); await h.pause(800);
      await h.atFrac(0.22); await soft(() => h.clickText('telecom_links_dataset'));
      await h.pause(700); await soft(() => h.clickText('mule_transfers_dataset'));
      await h.atFrac(0.45); await soft(async () => { const p = await h.locate(`document.querySelector('input[type=text],input:not([type]),input[type=search]')`); await h.click(p.x, p.y); await h.type('MULE-HUB-01', 85); });
      await h.atFrac(0.72); await soft(() => h.hoverText('first seen'));
      await h.atFrac(0.84); await soft(() => h.clickText('first seen'));
      await h.pause(500); await h.atFrac(0.94); await soft(() => h.clickButton('Draw'));
    });
    await h.scene('S08', async () => {                 // the reveal
      await h.pause(2200);
      await soft(async () => { await h.clickLabel('Display options'); await h.pause(700); await h.clickText('Node labels'); await h.escape(); });
      await h.atFrac(0.30); await h.moveTo(667, 420, 1400);
      await h.atFrac(0.50); await h.moveTo(450, 330, 1600);
      await h.atFrac(0.72); await h.moveTo(560, 650, 1600);
      await h.atFrac(0.86); await h.moveTo(865, 605, 1500);
    });
    await h.scene('S09', async () => {                 // the table behind the graph
      await h.atFrac(0.15); await soft(() => h.clickLabel('Show the table'));
      await h.pause(1500); await h.moveTo(400, 760, 1400);
      await h.atFrac(0.55); await h.moveTo(700, 800, 1400); await h.atFrac(0.78); await h.moveTo(400, 840, 1400);
    });
    await h.scene('S10', async () => {                 // search for the exit
      await h.goto('/link-analysis?view=mule_layering_ring'); await h.waitText('Graph counts'); await h.pause(1000);
      await soft(() => h.clickLabel('Minimise the legend'));
      await h.atFrac(0.30); await soft(() => h.clickLabel('Search nodes'));
      await h.pause(500); await soft(() => h.type('OFFSHORE', 110));
      await h.pause(900); await h.atFrac(0.48); await soft(() => h.key('Enter'));
    });
    await h.scene('S11', async () => {                 // conclusion: hold on the spotlighted route
      await h.moveTo(600, 560, 2200); await h.atFrac(0.5); await h.moveTo(440, 700, 2500);
    });
  },
  probe4: async (h) => {
    await h.openSpaceView('la-showcase', 'x'); await h.pause(2000);
    await h.step('open-dataset', async () => { await h.clickText('telecom_links_dataset'); });
    await h.pause(800);
    await h.step('pick-dataset', async () => { await h.clickText('mule_transfers_dataset'); });
    await h.pause(1000);
    await h.step('open-type', async () => { await h.clickNthText('MSISDN', 0); });
    await h.pause(800);
    await h.step('pick-type', async () => { await h.clickText('Billing account'); });
    await h.pause(800);
    await h.step('type-id', async () => {
      const p = await h.locate(`document.querySelector('input[type=text],input:not([type]),input[type=search]')`);
      await h.click(p.x, p.y); await h.type('MULE-HUB-01', 70);
    });
    await h.pause(1800);
    await h.step('suggestion', async () => { await h.clickText('first seen'); });
    await h.pause(1000);
    await h.step('draw', async () => { await h.clickText('Draw'); });
    await h.pause(3500);
  },
  probe3: async (h) => {
    await h.openSpaceView('la-showcase', 'x'); // /link-analysis?view=x is not a saved view: lands on Start a graph
    await h.pause(2000);
    await h.step('start-form', async () => {});
    await h.step('type-id', async () => {
      const p = await h.locate(`document.querySelector('input[type=text],input:not([type]),input[type=search]')`);
      await h.click(p.x, p.y); await h.type('MULE-HUB-01', 70);
    });
    await h.pause(1800);
    await h.step('suggestion', async () => { await h.clickText('first seen'); });
    await h.pause(1200);
    await h.step('draw', async () => { await h.clickText('Draw'); });
    await h.pause(3500);
  },
  probe2: async (h) => {
    await h.openSpaceView('la-showcase', 'mule_layering_ring');
    await h.waitText('Graph counts'); await h.pause(1500);
    await h.step('search-open', async () => { await h.clickLabel('Search nodes'); });
    await h.pause(800);
    await h.step('search-typed', async () => { await h.type('OFFSHORE'); });
    await h.pause(1500);
    await h.step('search-enter', async () => { await h.key('Enter'); });
    await h.pause(2500);
    await h.step('file-menu', async () => { await h.clickLabel('File'); });
    await h.pause(1000);
  },
  mule: async (h) => {
    await h.openSpaceView('la-showcase', 'mule_layering_ring');
    await h.waitText('Graph counts');
    await h.pause(1500);
    await h.step('prepare', async () => {
      await h.clickLabel('Minimise the legend');
      await h.pause(600);
      await h.clickLabel('Display options');          // ids on every node, as in the stills
      await h.pause(900);
      await h.clickText('Node labels');
      await h.escape();
    });
    await h.pause(800);
    h.mark('tangle');                                  // the whole saved view
    await h.moveTo(700, 420, 1400); await h.pause(1200); await h.moveTo(300, 600, 1400); await h.pause(1500);
    h.mark('sweep');
    await h.step('busiest-nodes', async () => { await h.clickText('Busiest nodes'); });
    await h.pause(2200);
    await h.step('hover-hub-card', async () => { await h.hoverText('MULE-HUB-01 takes part'); });
    await h.pause(2200);
    h.mark('narrow');
    await h.step('start-from-hub', async () => { await h.clickNthText('Start from these', 0); });
    h.mark('reveal');
    await h.pause(3500);
    await h.moveTo(800, 480, 1200); await h.pause(1500); await h.moveTo(560, 330, 1200); await h.pause(2000);
    h.mark('conclusion');
    await h.moveTo(1000, 650, 1200); await h.pause(1500); await h.moveTo(560, 690, 1400); await h.pause(2200);
    await h.moveTo(900, 520, 1200); await h.pause(1500);
    h.mark('evidence');
    await h.step('show-table', async () => { await h.clickLabel('Show the table'); });
    await h.pause(6000);
    h.mark('end');
  },
};

// ---------- chrome + CDP ----------
const chrome = ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(existsSync);
if (!chrome) throw new Error('no Chrome/Edge found');
const profile = join(tmpdir(), `la-live-${Date.now()}`);
const proc = spawn(chrome, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check', '--hide-scrollbars', `--window-size=${VW},${VH}`, 'about:blank'], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function targetWs() {
  for (let i = 0; i < 50; i++) {
    try { const l = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json(); const p = l.find((t) => t.type === 'page'); if (p) return p.webSocketDebuggerUrl; } catch { /* not up yet */ }
    await sleep(200);
  }
  throw new Error('chrome did not start');
}

async function main() {
  const out = join(outRoot, takeName);
  const framesDir = join(out, 'frames');
  rmSync(out, { recursive: true, force: true });
  mkdirSync(framesDir, { recursive: true });
  const ws = new WebSocket(await targetWs());
  await new Promise((r) => (ws.onopen = r));
  let id = 0; const pending = new Map(); const listeners = [];
  ws.onmessage = (m) => {
    const d = JSON.parse(m.data);
    if (d.id && pending.has(d.id)) { const { res, rej } = pending.get(d.id); pending.delete(d.id); d.error ? rej(new Error(d.error.message)) : res(d.result); }
    else if (d.method) listeners.forEach((f) => f(d));
  };
  const send = (method, params = {}) => new Promise((res, rej) => { const i = ++id; pending.set(i, { res, rej }); ws.send(JSON.stringify({ id: i, method, params })); });
  const ev = async (expr) => (await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })).result.value;

  await send('Page.enable'); await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: VW, height: VH, deviceScaleFactor: DPR, mobile: false });
  // a visible cursor and click ripple (headless screencasts have no pointer)
  await send('Page.addScriptToEvaluateOnNewDocument', { source: `(() => {
    const mk = () => { if (document.getElementById('__cur')) return;
      const c = document.createElement('div'); c.id = '__cur';
      c.style.cssText = 'position:fixed;left:0;top:0;width:26px;height:26px;z-index:2147483647;pointer-events:none;transform:translate(-100px,-100px);';
      c.innerHTML = '<svg width="26" height="26" viewBox="0 0 26 26"><path d="M3 2 L3 21 L8.5 16.5 L12 24 L15.5 22.5 L12 15 L19 15 Z" fill="#fff" stroke="#000" stroke-width="1.6" stroke-linejoin="round"/></svg>';
      document.documentElement.appendChild(c);
      addEventListener('mousemove', (e) => { c.style.transform = 'translate(' + e.clientX + 'px,' + e.clientY + 'px)'; }, true);
      addEventListener('mousedown', (e) => { const r = document.createElement('div');
        r.style.cssText = 'position:fixed;z-index:2147483646;pointer-events:none;border:3px solid #7c8cff;border-radius:50%;width:12px;height:12px;left:' + (e.clientX - 6) + 'px;top:' + (e.clientY - 6) + 'px;transition:all .45s ease-out;opacity:.95';
        document.documentElement.appendChild(r); requestAnimationFrame(() => { r.style.width = '56px'; r.style.height = '56px'; r.style.left = (e.clientX - 28) + 'px'; r.style.top = (e.clientY - 28) + 'px'; r.style.opacity = '0'; });
        setTimeout(() => r.remove(), 600); }, true); };
    document.addEventListener('DOMContentLoaded', mk); if (document.readyState !== 'loading') mk(); })();` });

  // the Graph counts pill states the size of the data, which the videos do not quote: keep it out of the take
  await send('Page.addScriptToEvaluateOnNewDocument', { source: `setInterval(() => {
    const w = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT); let n;
    while ((n = w.nextNode())) { if (!n.nodeValue.trim().startsWith('Graph counts') || n.parentElement.closest('.cdk-overlay-container,[role=menu],[role=dialog]')) continue;
      let e = n.parentElement; while (e.parentElement && e.parentElement.getBoundingClientRect().width < 500 && e.parentElement.getBoundingClientRect().height < 450) e = e.parentElement;
      e.style.visibility = 'hidden'; }
    // the Data page's Discover block states row counts ("5,732 link rows", entity counts): hide that text and its siblings
    const w2 = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT); let m;
    while ((m = w2.nextNode())) { if (!/\\d[\\d,]*\\s+link rows/.test(m.nodeValue)) continue;
      const p = m.parentElement; const box = p.parentElement && p.parentElement.getBoundingClientRect().height < 700 ? p.parentElement : p;
      box.style.visibility = 'hidden'; } }, 150);` });

  // screencast
  const frames = []; let t0 = null;
  listeners.push(async (d) => {
    if (d.method !== 'Page.screencastFrame') return;
    const { data, metadata, sessionId } = d.params;
    if (t0 === null) t0 = metadata.timestamp;
    const f = join(framesDir, `f${String(frames.length).padStart(6, '0')}.jpg`);
    frames.push({ file: f, t: metadata.timestamp - t0 });
    writeFileSync(f, Buffer.from(data, 'base64'));
    send('Page.screencastFrameAck', { sessionId }).catch(() => {});
  });
  await send('Page.startScreencast', { format: 'jpeg', quality: 88, maxWidth: Math.round(VW * DPR), maxHeight: Math.round(VH * DPR), everyNthFrame: 1 });
  const startedAt = Date.now();

  // ---------- helpers ----------
  let mx = 800, my = 450, stepNo = 0;
  const marks = {};
  const h = {
    pause: sleep,
    async key(k) { const code = { Enter: 13, Escape: 27, Tab: 9, g: 71, G: 71 }[k]; const text = k.length === 1 ? k : undefined; for (const type of ['keyDown', 'keyUp']) await send('Input.dispatchKeyEvent', { type, key: k, code: k.length === 1 ? 'Key' + k.toUpperCase() : k, windowsVirtualKeyCode: code, ...(type === 'keyDown' && text ? { text } : {}) }); },
    async kb(k, mod = 0, repeat = 1) { const vk = { ArrowLeft: 37, ArrowUp: 38, ArrowRight: 39, ArrowDown: 40, '+': 187, '-': 189, '=': 187 }[k] ?? k.toUpperCase().charCodeAt(0); for (let i = 0; i < repeat; i++) { for (const type of ['keyDown', 'keyUp']) await send('Input.dispatchKeyEvent', { type, key: k, code: k.length === 1 ? (/[a-z]/i.test(k) ? 'Key' + k.toUpperCase() : k) : k, windowsVirtualKeyCode: vk, modifiers: mod, ...(type === 'keyDown' && k.length === 1 && !(mod & 2) ? { text: k } : {}) }); await sleep(220); } },
    async drag(pts, mod = 0) { await h.moveTo(pts[0][0], pts[0][1], 600); await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: pts[0][0], y: pts[0][1], button: 'left', clickCount: 1, modifiers: mod }); await sleep(120); for (const p of pts.slice(1)) { const n = 14; for (let i = 1; i <= n; i++) { const x = mx + (p[0] - mx) * i / n, y = my + (p[1] - my) * i / n; await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, button: 'left', buttons: 1, modifiers: mod }); await sleep(35); } mx = p[0]; my = p[1]; } await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: mx, y: my, button: 'left', clickCount: 1, modifiers: mod }); await sleep(900); },
    async undo() { for (const type of ['keyDown', 'keyUp']) await send('Input.dispatchKeyEvent', { type, key: 'z', code: 'KeyZ', windowsVirtualKeyCode: 90, modifiers: 2 }); },   // Ctrl+Z
    async escape() { for (const type of ['keyDown', 'keyUp']) await send('Input.dispatchKeyEvent', { type, key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 }); },
    // a narrated scene: it runs `fn`, then holds until the narration's length (timing[id], from the TTS pass) has passed
    sceneStart: 0, sceneDur: 8,
    async scene(id, fn) {
      h.sceneStart = Date.now(); h.sceneDur = (timing[id] ?? 8) * 1000; h.mark(id);
      await fn();
      const left = h.sceneDur - (Date.now() - h.sceneStart);
      if (left > 0) await sleep(left); else console.log(`  ${id} ran ${(-left / 1000).toFixed(1)}s over its ${(h.sceneDur / 1000).toFixed(1)}s`);
    },
    async atFrac(f) { const left = h.sceneStart + h.sceneDur * f - Date.now(); if (left > 0) await sleep(left); },
    ev,
    // an element whose own text is exactly `text` (an option in a dialog, a list item): click it
    async clickExact(text, nth = 0) {
      const p = await h.locate(`(() => { const hits = []; const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); let n; while ((n = w.nextNode())) { if (n.nodeValue.trim() !== ${JSON.stringify(text)}) continue; const e = n.parentElement; const b = e.getBoundingClientRect(); if (b.width > 8 && b.height > 8 && getComputedStyle(e).visibility !== 'hidden') hits.push(e); } return hits[${nth}]; })()`);
      await h.click(p.x, p.y);
    },
    // a select field is just to the right of its label: click there to open it
    async clickRightOf(label, dx = 130) {
      const p = await h.locate(`(() => { const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); let n; while ((n = w.nextNode())) { if (n.nodeValue.trim() === ${JSON.stringify(label)} && n.parentElement.getBoundingClientRect().width > 8) return n.parentElement; } })()`);
      const r = await ev(`(() => { const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); let n; while ((n = w.nextNode())) { if (n.nodeValue.trim() === ${JSON.stringify(label)} && n.parentElement.getBoundingClientRect().width > 8) { const b = n.parentElement.getBoundingClientRect(); return { x: b.right, y: b.top + b.height / 2 }; } } })()`);
      await h.click(r.x + dx, r.y);
    },
    async hoverNth(text, nth) { const p = await h.locate(h.textEl(text, nth)); await h.moveTo(p.x, p.y, 900); },
    async setSpace(space) { await send('Page.navigate', { url: `${base}/` }); await sleep(1500); await ev(`localStorage.setItem('inspecto.currentSpace', ${JSON.stringify(space)})`); },
    async goto(path) { await send('Page.navigate', { url: `${base}${path}` }); await sleep(1800); },
    async clickButton(label) { const p = await h.locate(`[...document.querySelectorAll('button')].find(b => b.offsetParent && b.innerText.trim() === ${JSON.stringify(label)})`); await h.click(p.x, p.y); },
    async clickSel(sel) { const p = await h.locate(`document.querySelector(${JSON.stringify(sel)})`); await h.click(p.x, p.y); },
    mark(label) { marks[label] = +((Date.now() - startedAt) / 1000).toFixed(2); },
    async shot(label) { const r = await send('Page.captureScreenshot', { format: 'png' }); writeFileSync(join(out, `probe-${String(stepNo).padStart(2, '0')}-${label}.png`), Buffer.from(r.data, 'base64')); },
    // Run `fn` and mark what it changed: a screenshot before, one after (cursor hidden), and the bounding box of the difference is
    // stored in marks.impacts with the time of the 'after' (the renderer draws it as a yellow box from then on). opts: region [x,y,w,h]
    // in CSS px to look only at the canvas (default the whole page), settle ms to wait before the 'after' (default 1400).
    async impact(label, fn, opts = {}) {
      const snap = async (tag) => { const r = await send('Page.captureScreenshot', { format: 'png' }); const p = join(out, `impact-${label}-${tag}.png`); writeFileSync(p, Buffer.from(r.data, 'base64')); return p; };
      const cur = (v) => ev(`(() => { const c = document.getElementById('__cur'); if (c) c.style.visibility = '${v}'; })()`);
      await cur('hidden'); const a = await snap('a'); await cur('visible');
      await fn();
      await sleep(opts.settle ?? 1400);
      await cur('hidden'); const b = await snap('b'); const t = +((Date.now() - startedAt) / 1000).toFixed(2); await cur('visible');   // t before the diff: ffmpeg takes seconds
      const region = opts.region ? opts.region.map((v) => Math.round(v * DPR)) : null;
      const box = diffBox(a, b, region);
      if (!box) { console.log(`  impact ${label}: nothing changed`); return; }
      (marks.impacts ||= []).push({ label, t, box, text: opts.text || label });
      console.log(`  impact ${label}: box ${box.join(',')} at ${t}s`);
    },
    // A yellow mark on a rectangle [x, y, w, h] in CSS px, held 3 s from now: for what an action changed when the picture
    // before and after differs everywhere (the layout re-fits on every expand), so a diff would mark the whole canvas.
    markBox(label, box, text) { (marks.impacts ||= []).push({ label, t: +((Date.now() - startedAt) / 1000).toFixed(2), box: box.map((v) => Math.round(v * DPR)), text: text || label }); },
    // Click a node by its label. The canvas is not DOM, so: Search nodes (everything else dims), take the bounding box of the bright
    // pixels below the search box, and click its centre (a single or a double click). opts: dbl, region [x,y,w,h] in CSS px.
    async pick(name, opts = {}) {
      await h.clickLabel('Search nodes'); await sleep(500);
      await h.type(name, 60); await h.key('Enter'); await sleep(1300);
      const cur = (v) => ev(`(() => { const c = document.getElementById('__cur'); if (c) c.style.visibility = '${v}'; })()`);
      await cur('hidden');
      const r = await send('Page.captureScreenshot', { format: 'png' });
      const p = join(out, `pick-${name.replace(/[^A-Za-z0-9]/g, '_')}.png`); writeFileSync(p, Buffer.from(r.data, 'base64'));
      await cur('visible');
      const reg = (opts.region || [80, 175, 1160, 660]).map((v) => Math.round(v * DPR));
      const b = brightBox(p, reg);
      if (!b) throw new Error('pick: ' + name + ' not found on the canvas');
      const x = (b[0] + b[2] / 2) / DPR, y = (b[1] + b[3] / 2) / DPR;
      await h.moveTo(x, y, 600);
      for (let n = 1; n <= (opts.dbl ? 2 : 1); n++) {
        const modifiers = opts.shift ? 8 : 0;
        await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: n, modifiers }); await sleep(70);
        await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: n, modifiers }); await sleep(opts.dbl ? 90 : 500);
      }
      await sleep(600);
    },
    // Click (or double-click) the canvas at CSS-pixel [x, y]: a node of a deterministic layout, read from a probe screenshot.
    async at(xy, opts = {}) {
      const [x, y] = xy;
      await h.moveTo(x, y, 600);
      for (let n = 1; n <= (opts.dbl ? 2 : 1); n++) {
        await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: n }); await sleep(70);
        await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: n }); await sleep(opts.dbl ? 90 : 500);
      }
      await sleep(600);
    },
    // Press a button that downloads a file; the file lands in <out>/downloads, and a copy goes to st.as when given (an absolute path),
    // so a later episode can edit it off camera. {download:"Download manifest", as:"C:/.../manifest.json"}
    async download(label, opts = {}) {
      const dir = join(out, 'downloads'); mkdirSync(dir, { recursive: true });
      await send('Page.setDownloadBehavior', { behavior: 'allow', downloadPath: dir });
      const before = new Set(readdirSync(dir));
      await h.clickButton(label);
      let got = null;
      for (let i = 0; i < 60 && !got; i++) { await sleep(500); got = readdirSync(dir).find((n) => !before.has(n) && !n.endsWith('.crdownload')); }
      if (!got) throw new Error('download: no file after ' + label);
      console.log('  downloaded', got);
      if (opts.as) { mkdirSync(resolve(opts.as, '..'), { recursive: true }); copyFileSync(join(dir, got), opts.as); }
    },
    // Press a button that opens the file picker and hand the picker a local file (the picker itself is intercepted, so nothing native shows).
    async upload(label, file) {
      await send('Page.setInterceptFileChooserDialog', { enabled: true });
      const done = new Promise((res) => { const l = async (d) => { if (d.method !== 'Page.fileChooserOpened') return; listeners.splice(listeners.indexOf(l), 1); await send('DOM.setFileInputFiles', { files: [resolve(file)], backendNodeId: d.params.backendNodeId }); res(); }; listeners.push(l); });
      await h.clickButton(label).catch(async () => { await h.clickText(label); });
      await Promise.race([done, sleep(8000).then(() => { throw new Error('upload: no file chooser opened by ' + label); })]);
      await sleep(1500);
    },
    // the visible text and the visible buttons/links/tabs of the page, as JSON beside the take (for walking the UI)
    async dump(label) {
      const d = await ev(`(() => { const R = [...document.querySelectorAll('[role=dialog],mat-dialog-container,.cdk-overlay-pane')].filter((e) => e.offsetWidth > 40 && e.offsetHeight > 40 && (e.innerText || '').trim()).pop() || document.body; return ({ url: location.href, scoped: R !== document.body, text: R.innerText, controls: [...R.querySelectorAll('button,a,[role=tab],[role=menuitem],input,select')].filter(b => b.offsetParent).map(b => ({ tag: b.tagName.toLowerCase(), label: (b.getAttribute('aria-label') || b.innerText || b.placeholder || '').trim().split('\\n').join(' ').split(' ').filter(Boolean).join(' ').slice(0, 90), disabled: !!b.disabled || b.getAttribute('aria-disabled') === 'true' })).filter((c) => c.label) }); })()`);
      writeFileSync(join(out, `dump-${label}.json`), JSON.stringify(d, null, 1));
    },
    async step(label, fn) { stepNo++; try { await fn(); } catch (e) { await h.shot('FAIL-' + label); throw e; } if (probe) { await sleep(900); await h.shot(label); } },
    async openSpaceView(space, view) {
      await send('Page.navigate', { url: `${base}/` }); await sleep(1500);
      await ev(`localStorage.setItem('inspecto.currentSpace', ${JSON.stringify(space)})`);
      await send('Page.navigate', { url: `${base}/link-analysis?view=${view}` }); await sleep(1500);
    },
    async waitFor(expr, ms = 10000) { const t = Date.now(); while (Date.now() - t < ms) { if (await ev(expr)) return; await sleep(250); } throw new Error('timeout waiting for ' + expr.slice(0, 60)); },
    async waitText(text, ms = 20000) {
      const t = Date.now();
      while (Date.now() - t < ms) { if (await ev(`document.body.textContent.includes(${JSON.stringify(text)})`)) return; await sleep(300); }
      throw new Error(`timeout waiting for "${text}"`);
    },
    async locate(js) { const r = await ev(`(() => { const el = (${js}); if (!el) return null; el.scrollIntoView({block:'center'}); const b = el.getBoundingClientRect(); return {x: b.x + b.width/2, y: b.y + b.height/2}; })()`); if (!r) throw new Error(`not found: ${js}`); if (probe) console.log('locate', JSON.stringify(await ev('(() => { const e = (' + js + '); return e.tagName + "." + e.className + " " + JSON.stringify(e.getBoundingClientRect()) + " " + e.innerText.slice(0,40); })()'))); return r; },
    async moveTo(x, y, ms = 700) {
      const n = Math.max(8, Math.round(ms / 25));
      for (let i = 1; i <= n; i++) {
        const k = i / n, e = k < 0.5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2;
        await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: mx + (x - mx) * e, y: my + (y - my) * e });
        await sleep(ms / n);
      }
      mx = x; my = y;
    },
    async click(x, y) { if (probe) console.log("click", Math.round(x), Math.round(y));
      await h.moveTo(x, y);
      await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 }); await sleep(90);
      await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 }); await sleep(500);
    },
    async hoverSel(sel) { const p = await h.locate(`document.querySelector(${JSON.stringify(sel)})`); await h.moveTo(p.x, p.y, 700); await sleep(900); },
    async hoverLabel(label) { const p = await h.locate(`[...document.querySelectorAll('[aria-label=${JSON.stringify(label)}]')].find((e) => e.offsetParent && e.getBoundingClientRect().width > 0)`); await h.moveTo(p.x, p.y, 700); await sleep(900); },   // the tooltip shows
    async clickLabel(label) { const p = await h.locate(`[...document.querySelectorAll('[aria-label=${JSON.stringify(label)}]')].find((e) => e.offsetParent && e.getBoundingClientRect().width > 0)`); await h.click(p.x, p.y); },
    // smallest visible element whose own text contains `text`
    // the nth visible element whose own text node contains `text` (text-node walk: finds the real label element)
    textEl: (text, nth = 0) => `(() => { const hits = []; const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); let n; while ((n = w.nextNode())) { if (!n.nodeValue.includes(${JSON.stringify(text)})) continue; const e = n.parentElement; const b = e.getBoundingClientRect(); const cs = getComputedStyle(e); if (b.width > 8 && b.height > 8 && b.right > 0 && b.left < innerWidth && b.bottom > 0 && b.top < innerHeight && cs.visibility !== 'hidden' && cs.display !== 'none') hits.push(e); } return hits[${nth}]; })()`,
    // scroll the first element whose text contains `text` to the middle of its scroll pane (the toolbox is longer than the screen)
    async reveal(text) {
      const ok = await ev(`(() => { const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); let n; while ((n = w.nextNode())) { if (n.nodeValue.includes(${JSON.stringify(text)})) { n.parentElement.scrollIntoView({ block: 'center' }); return true; } } return false; })()`);
      if (!ok) throw new Error('reveal: ' + text + ' not found');
      await sleep(900);
    },
    async clickText(text) { const p = await h.locate(h.textEl(text)); await h.click(p.x, p.y); },
    async clickNthText(text, nth) { const p = await h.locate(h.textEl(text, nth)); await h.click(p.x, p.y); },
    async hoverText(text) { const p = await h.locate(h.textEl(text)); await h.moveTo(p.x, p.y, 900); },
    async type(text, perChar = 90) { for (const ch of text) { await send('Input.insertText', { text: ch }); await sleep(perChar); } },
  };

  try {
    if (!SCENARIOS[scenarioName]) throw new Error(`unknown scenario "${scenarioName}"`);
    try { await SCENARIOS[scenarioName](h); } catch (e) { await h.shot('SCENARIO-FAIL').catch(() => {}); console.log('PAGETEXT', JSON.stringify(String(await ev('document.body.innerText').catch(() => '')).slice(0, 500))); throw e; }
  } finally {
    await send('Page.stopScreencast').catch(() => {});
    ws.close(); proc.kill();
  }

  writeFileSync(join(out, 'marks.json'), JSON.stringify(marks, null, 1));
  console.log('marks', JSON.stringify(marks));
  // ---------- encode: each frame held until the next ----------
  console.log(`captured ${frames.length} frames over ${((Date.now() - startedAt) / 1000).toFixed(1)} s`);
  const lines = [];
  frames.forEach((f, i) => { const dur = i + 1 < frames.length ? Math.max(0.001, frames[i + 1].t - f.t) : Math.max(1, (Date.now() - startedAt) / 1000 - f.t); lines.push(`file '${f.file.replace(/\\/g, '/')}'`, `duration ${dur.toFixed(4)}`); });
  lines.push(`file '${frames.at(-1).file.replace(/\\/g, '/')}'`);
  writeFileSync(join(out, 'frames.txt'), lines.join('\n') + '\n');
  const ffmpeg = (() => { if (!spawnSync('ffmpeg', ['-version']).error) return 'ffmpeg'; const b = join(process.env.LOCALAPPDATA, 'Microsoft/WinGet/Packages'); for (const d of readdirSync(b).filter((x) => x.startsWith('Gyan.FFmpeg'))) for (const v of readdirSync(join(b, d))) { const e = join(b, d, v, 'bin/ffmpeg.exe'); if (existsSync(e)) return e; } throw new Error('ffmpeg not found'); })();
  const mp4 = join(out, `${takeName}-live.mp4`);
  const r = spawnSync(ffmpeg, ['-y', '-hide_banner', '-loglevel', 'error', '-f', 'concat', '-safe', '0', '-i', 'frames.txt', '-vf', `scale=${Math.round(VW * DPR)}:${Math.round(VH * DPR)}:flags=bicubic,fps=30,format=yuv420p`, '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '19', mp4], { cwd: out, encoding: 'utf8' });
  if (r.status !== 0) throw new Error(r.stderr);
  console.log(`→ ${mp4}`);
  try { rmSync(profile, { recursive: true, force: true }); } catch { /* Windows may still hold a lock on the profile: harmless */ }
}
main().catch((e) => { console.error(e); proc.kill(); process.exit(1); });
