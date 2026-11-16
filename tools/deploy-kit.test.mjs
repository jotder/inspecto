// The deploy kit (DEPLOY-EXPOSURE-KIT-1, MoSCoW S11): deploy/kit/ is staged into every non-demo bundle by package.ps1
// step 6b-4 - bin/preflight, bin/upgrade, bin/verify-bundle, bin/backup-offsite (.sh and .ps1) and deploy/proxy/ (nginx,
// Caddy). These tests run the very files that ship, in throwaway fake bundles: the bash forms always (Git Bash on
// Windows), the PowerShell forms when pwsh is on PATH, and upgrade.ps1 on Windows only (Scheduled Tasks, Win32_Process).
// They do not run nginx or Caddy, systemd or a Scheduled Task: those need a real host.
//
// Usage: node --test tools/deploy-kit.test.mjs
import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, copyFileSync, existsSync, chmodSync, appendFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { crc32 } from 'node:zlib';
import net from 'node:net';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const KIT = join(REPO, 'deploy', 'kit');
const WIN = process.platform === 'win32';

// On Windows `bash` on PATH may be WSL's; take Git's own bash, which is what the kit's users and the pre-commit hook have.
function findBash() {
    if (!WIN) return 'bash';
    const exec = spawnSync('git', ['--exec-path'], { encoding: 'utf8' }).stdout?.trim();
    const candidate = exec ? resolve(exec, '..', '..', '..', 'bin', 'bash.exe') : '';
    return candidate && existsSync(candidate) ? candidate : 'bash';
}
const BASH = findBash();
const HAS_PWSH = !spawnSync('pwsh', ['-NoProfile', '-Command', 'exit 0']).error;

const SCRUB = ['INSPECTO_JAVA_OPTS', 'EXTRA_JAVA_OPTS', 'HTTPS_KEYSTORE', 'HTTPS_KEYSTORE_PASSWORD', 'SPACES_ROOT',
    'SPACES_DATA_ROOT', 'AUTH_OIDC_DISCOVERY_URL', 'AUTH_OIDC_ISSUER', 'AUTH_OIDC_JWKS_URI', 'INSPECTO_DB_URL', 'PORT'];
function env(extra = {}) {
    const e = { ...process.env, MIN_FREE_GB: '0' };
    for (const k of SCRUB) delete e[k];
    return { ...e, ...extra };
}
const LOOPBACK = '-Dcontrol.bind=127.0.0.1 -Dcontrol.trustedProxies=127.0.0.1';

function freePort() {
    return new Promise((ok, ko) => {
        const s = net.createServer();
        s.on('error', ko);
        s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => ok(p)); });
    });
}

/** A fake unpacked bundle: the kit's bin/, lib/edition.properties, spaces/, logs/, optionally a stub runtime/bin/java. */
function makeBundle(dir, { edition = 'Basic', java = '27.0.1', marker = 'old' } = {}) {
    mkdirSync(join(dir, 'bin'), { recursive: true });
    for (const f of readdirSync(join(KIT, 'bin'))) copyFileSync(join(KIT, 'bin', f), join(dir, 'bin', f));
    writeFileSync(join(dir, 'bin', 'serve.sh'), '#!/usr/bin/env bash\necho serve\n');
    writeFileSync(join(dir, 'bin', 'serve.bat'), '@echo off\r\n');
    mkdirSync(join(dir, 'lib'), { recursive: true });
    writeFileSync(join(dir, 'lib', 'edition.properties'), `edition=${edition}\n`);
    writeFileSync(join(dir, 'lib', 'modules.list'), 'lib/inspecto.jar\n');
    writeFileSync(join(dir, 'lib', 'marker'), marker);
    mkdirSync(join(dir, 'spaces', '_templates', 'shipped'), { recursive: true });
    writeFileSync(join(dir, 'spaces', '_templates', 'shipped', 'gallery.txt'), marker);
    mkdirSync(join(dir, 'logs'), { recursive: true });
    writeFileSync(join(dir, 'logs', '.keep'), '');
    if (java) {
        mkdirSync(join(dir, 'runtime', 'bin'), { recursive: true });
        writeFileSync(join(dir, 'runtime', 'bin', 'java'), `#!/bin/sh\necho 'openjdk version "${java}" 2026-09-15' >&2\n`);
        chmodSync(join(dir, 'runtime', 'bin', 'java'), 0o755);
    }
    return dir;
}

function tmp(name) { return mkdtempSync(join(tmpdir(), `deploy-kit-${name}-`)); }
/** Run a kit script; resolves {code, out}. Async, so the tests below run side by side. */
function run(cmd, args, extraEnv) {
    return new Promise((ok) => {
        const c = spawn(cmd, args, { env: env(extraEnv) });
        let out = '';
        c.stdout.on('data', (d) => { out += d; });
        c.stderr.on('data', (d) => { out += d; });
        c.on('close', (code) => ok({ code, out }));
    });
}
const sh = (script, args, extraEnv = {}) => run(BASH, [script, ...args], extraEnv);
const ps = (script, args, extraEnv = {}) => run('pwsh', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', script, ...args], extraEnv);
/** The status of one check row, e.g. row(out, 'bind') -> 'FAIL'. */
function row(out, check, tool = 'preflight') {
    const m = out.match(new RegExp(`^\\[${tool}\\] (PASS|WARN|FAIL)\\s+${check.replace(/[-]/g, '\\-')}\\s`, 'm'));
    return m ? m[1] : `no ${check} row in:\n${out}`;
}
const sha256 = (p) => createHash('sha256').update(readFileSync(p)).digest('hex');
/** The checksum file package.ps1's New-ReleaseIntegrity writes: "<hash>  <name>\n". */
function writeSha(zip) { writeFileSync(`${zip}.sha256`, `${sha256(zip)}  ${zip.split(/[\\/]/).pop()}\n`); }

/** A stored (uncompressed) zip of every file under dir, entries prefixed by `prefix/` - enough for unzip and Expand-Archive. */
function writeZip(zip, dir, prefix) {
    const files = [];
    const walk = (d, rel) => {
        for (const e of readdirSync(d, { withFileTypes: true })) {
            if (e.isDirectory()) walk(join(d, e.name), `${rel}${e.name}/`);
            else files.push({ name: `${prefix}/${rel}${e.name}`, data: readFileSync(join(d, e.name)) });
        }
    };
    walk(dir, '');
    const locals = []; const central = []; let offset = 0;
    for (const f of files) {
        const name = Buffer.from(f.name);
        const crc = crc32(f.data) >>> 0;
        const lh = Buffer.alloc(30);
        lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(20, 4); lh.writeUInt16LE(0, 6); lh.writeUInt16LE(0, 8);
        lh.writeUInt16LE(0, 10); lh.writeUInt16LE(0x21, 12); lh.writeUInt32LE(crc, 14);
        lh.writeUInt32LE(f.data.length, 18); lh.writeUInt32LE(f.data.length, 22); lh.writeUInt16LE(name.length, 26); lh.writeUInt16LE(0, 28);
        const ch = Buffer.alloc(46);
        ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6); ch.writeUInt16LE(0, 8); ch.writeUInt16LE(0, 10);
        ch.writeUInt16LE(0, 12); ch.writeUInt16LE(0x21, 14); ch.writeUInt32LE(crc, 16); ch.writeUInt32LE(f.data.length, 20);
        ch.writeUInt32LE(f.data.length, 24); ch.writeUInt16LE(name.length, 28); ch.writeUInt32LE(offset, 42);
        locals.push(lh, name, f.data); central.push(ch, name);
        offset += lh.length + name.length + f.data.length;
    }
    const cd = Buffer.concat(central);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(files.length, 8); end.writeUInt16LE(files.length, 10);
    end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
    writeFileSync(zip, Buffer.concat([...locals, cd, end]));
}

/** A backup_dir as the backup Job leaves it: <prefix>_<stamp>.zip + <zip>.manifest.json (Jackson pretty-printed). */
function makeBackups(dir) {
    mkdirSync(dir, { recursive: true });
    for (const stamp of ['20261101-020000', '20261102-020000']) {
        const zip = join(dir, `default_${stamp}.zip`);
        writeFileSync(zip, `archive ${stamp}`);
        writeFileSync(`${zip}.manifest.json`, `{\n  "source" : "spaces/default",\n  "archiveSha256" : "${sha256(zip)}",\n  "files" : [ ]\n}\n`);
    }
    return { newest: 'default_20261102-020000.zip', older: 'default_20261101-020000.zip' };
}

describe('the deploy kit', { concurrency: true }, () => {
    /** The two proxy templates with their refusal removed or disarmed: each still names /metrics and /health/details. */
function unfenced(dir) {
    const nginx = readFileSync(join(KIT, 'proxy', 'nginx-inspecto.conf'), 'utf8');
    const caddy = readFileSync(join(KIT, 'proxy', 'Caddyfile'), 'utf8');
    const out = {
        'nginx, deny all removed': nginx.replace(/^\s*deny all;\s*$/m, ''),
        'nginx, deny all commented out': nginx.replace('deny all;', '# deny all;'),
        'Caddy, respond removed': caddy.replace(/^\s*respond @fenced 403\s*$/m, ''),
        'Caddy, respond to another matcher': caddy.replace('respond @fenced 403', 'respond @other 403'),
    };
    return Object.fromEntries(Object.entries(out).map(([name, text], i) => {
        const f = join(dir, `unfenced-${i}.conf`);
        assert.ok(text.includes('metrics') && text.includes('health/details'), name);
        writeFileSync(f, text);
        return [name, f];
    }));
}

// ── packaging ──────────────────────────────────────────────────────────────────────────────────────────────────────────
    test('package.ps1 stages deploy/kit into bin/ and deploy/proxy/ of every non-demo bundle', async () => {
        const pkg = readFileSync(join(REPO, 'inspecto', 'package.ps1'), 'utf8');
        assert.match(pkg, /Join-Path \$sandboxRoot 'deploy\\kit'/);
        assert.match(pkg, /@\('bin', \$binDir\), @\('proxy', \(Join-Path \$deployDir 'proxy'\)\)/);
        assert.match(pkg, /if \(-not \$DemoAuth\) \{\s*\r?\n\s*\$kitSrc/);
    });

    test('every kit script ships for both launchers, and both proxy templates fence the same paths', async () => {
        const bin = readdirSync(join(KIT, 'bin'));
        for (const s of ['preflight', 'upgrade', 'verify-bundle', 'backup-offsite']) {
            assert.ok(bin.includes(`${s}.sh`) && bin.includes(`${s}.ps1`), `${s}: .sh and .ps1`);
        }
        const fence = '^/(api/v1/)?(spaces/[^/]+/)?(metrics|health/details)(/|$)';
        for (const f of ['nginx-inspecto.conf', 'Caddyfile']) {
            assert.ok(readFileSync(join(KIT, 'proxy', f), 'utf8').includes(fence), `${f} carries the fence regex`);
        }
    });

    test('the fence regex catches every route form that reaches /metrics or /health/details, and nothing else', async () => {
        const re = /^\/(api\/v1\/)?(spaces\/[^/]+\/)?(metrics|health\/details)(\/|$)/i;
        for (const p of ['/metrics', '/metrics/acquisition', '/api/v1/metrics', '/api/v1/health/details', '/API/V1/Metrics',
            '/api/v1/spaces/default/metrics', '/api/v1/spaces/la-aml/health/details', '/health/details', '/metrics/']) {
            assert.ok(re.test(p), `fenced: ${p}`);
        }
        for (const p of ['/health', '/ready', '/api/v1/health', '/api/v1/metricsx', '/api/v1/pipelines', '/api/v1/spaces/default/runs',
            '/api/v1/spaces/default/dashboards/metrics-board']) {
            assert.ok(!re.test(p), `open: ${p}`);
        }
    });

    // ── preflight.sh ─────────────────────────────────────────────────────────────────────────────────────────────────────
    const NGINX = join(KIT, 'proxy', 'nginx-inspecto.conf');

    test('preflight.sh: an unset or non-loopback bind FAILs behind a proxy, a loopback bind PASSes', async () => {
        const b = makeBundle(join(tmp('bind'), 'b'));
        const PORT = String(await freePort());
        const unset = (await sh(join(b, 'bin', 'preflight.sh'), ['--proxy-config', NGINX], { PORT }));
        assert.equal(row(unset.out, 'bind'), 'FAIL');
        assert.equal(unset.code, 1, unset.out);
        const wide = (await sh(join(b, 'bin', 'preflight.sh'), ['--proxy-config', NGINX], { PORT, INSPECTO_JAVA_OPTS: '-Dcontrol.bind=0.0.0.0' }));
        assert.equal(row(wide.out, 'bind'), 'FAIL');
        // the last -Dcontrol.bind wins, as in the JVM
        const last = (await sh(join(b, 'bin', 'preflight.sh'), ['--proxy-config', NGINX], { PORT, INSPECTO_JAVA_OPTS: '-Dcontrol.bind=127.0.0.1 -Dcontrol.bind=10.0.0.5' }));
        assert.equal(row(last.out, 'bind'), 'FAIL');
        const lo = (await sh(join(b, 'bin', 'preflight.sh'), ['--proxy-config', NGINX], { PORT, INSPECTO_JAVA_OPTS: LOOPBACK }));
        assert.equal(row(lo.out, 'bind'), 'PASS');
        assert.equal(row(lo.out, 'trusted-proxies'), 'PASS');
        const noTp = (await sh(join(b, 'bin', 'preflight.sh'), ['--proxy-config', NGINX], { PORT, INSPECTO_JAVA_OPTS: '-Dcontrol.bind=127.0.0.1' }));
        assert.equal(row(noTp.out, 'trusted-proxies'), 'WARN');
    });

    test('preflight.sh: a missing keystore FAILs (--direct with none set, or one that names a missing file); a present one PASSes', async () => {
        const t = tmp('tls');
        const b = makeBundle(join(t, 'b'));
        const PORT = String(await freePort());
        const none = (await sh(join(b, 'bin', 'preflight.sh'), ['--direct'], { PORT }));
        assert.equal(row(none.out, 'tls-keystore'), 'FAIL');
        assert.equal(none.code, 1);
        const missing = (await sh(join(b, 'bin', 'preflight.sh'), ['--proxy-config', NGINX], { PORT, INSPECTO_JAVA_OPTS: LOOPBACK, HTTPS_KEYSTORE: join(t, 'nope.p12') }));
        assert.equal(row(missing.out, 'tls-keystore'), 'FAIL');
        const ks = join(t, 'server.p12');
        writeFileSync(ks, 'not really pkcs12');
        const present = (await sh(join(b, 'bin', 'preflight.sh'), ['--direct'], { PORT, HTTPS_KEYSTORE: ks }));
        assert.equal(row(present.out, 'tls-keystore'), 'PASS');
        const viaOpts = (await sh(join(b, 'bin', 'preflight.sh'), ['--direct'], { PORT, INSPECTO_JAVA_OPTS: `-Dhttps.keystore=${ks}` }));
        assert.equal(row(viaOpts.out, 'tls-keystore'), 'PASS');
    });

    test('preflight.sh: the proxy config must exist and fence /metrics and /health/details', async () => {
        const t = tmp('proxy');
        const b = makeBundle(join(t, 'b'));
        const PORT = String(await freePort());
        const proxyRow = async (cfg) => row((await sh(join(b, 'bin', 'preflight.sh'), ['--proxy-config', cfg], { PORT, INSPECTO_JAVA_OPTS: LOOPBACK })).out, 'proxy');
        assert.equal(await proxyRow(NGINX), 'PASS');
        assert.equal(await proxyRow(join(KIT, 'proxy', 'Caddyfile')), 'PASS');
        const open = join(t, 'open.conf');
        writeFileSync(open, 'server { location / { proxy_pass http://127.0.0.1:8080; } }\n');
        assert.equal(await proxyRow(open), 'FAIL');
        assert.equal(await proxyRow(join(t, 'absent.conf')), 'FAIL');
        // the words alone are not a fence: each template with its refusal taken out must FAIL
        for (const [name, cfg] of Object.entries(unfenced(t))) assert.equal(await proxyRow(cfg), 'FAIL', name);
    });

    test('preflight.sh: the edition decides what must be set to start', async () => {
        const t = tmp('edition');
        const PORT = String(await freePort());
        const std = makeBundle(join(t, 'std'), { edition: 'Standard' });
        assert.equal(row((await sh(join(std, 'bin', 'preflight.sh'), [], { PORT })).out, 'sign-in'), 'FAIL');
        assert.equal(row((await sh(join(std, 'bin', 'preflight.sh'), [], { PORT, AUTH_OIDC_DISCOVERY_URL: 'https://idp/.well-known/openid-configuration' })).out, 'sign-in'), 'PASS');
        const adv = makeBundle(join(t, 'adv'), { edition: 'Advanced' });
        assert.equal(row((await sh(join(adv, 'bin', 'preflight.sh'), [], { PORT })).out, 'database'), 'FAIL');
        const basic = makeBundle(join(t, 'basic'));
        assert.equal(row((await sh(join(basic, 'bin', 'preflight.sh'), [], { PORT })).out, 'sign-in'), 'WARN');
        const odd = makeBundle(join(t, 'odd'), { edition: 'Enterprise' });
        assert.equal(row((await sh(join(odd, 'bin', 'preflight.sh'), [], { PORT })).out, 'edition'), 'FAIL');
    });

    test('preflight.sh: a port in use FAILs; a ready host exits 0; an old Java FAILs', async () => {
        const t = tmp('ready');
        const b = makeBundle(join(t, 'b'));
        const server = net.createServer();
        await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
        const busy = String(server.address().port);
        try {
            const r = (await sh(join(b, 'bin', 'preflight.sh'), ['--proxy-config', NGINX], { PORT: busy, INSPECTO_JAVA_OPTS: LOOPBACK }));
            assert.equal(row(r.out, 'port'), 'FAIL', r.out);
        } finally { server.close(); }
        const PORT = String(await freePort());
        const ok = (await sh(join(b, 'bin', 'preflight.sh'), ['--proxy-config', NGINX], { PORT, INSPECTO_JAVA_OPTS: LOOPBACK }));
        assert.equal(ok.code, 0, ok.out);
        assert.match(ok.out, /^\[preflight\] READY$/m);
        assert.equal(row(ok.out, 'runtime'), 'PASS');
        for (const c of ['spaces-root', 'logs', 'port']) assert.equal(row(ok.out, c), 'PASS', c);
        const old = makeBundle(join(t, 'old'), { java: '21.0.4' });
        assert.equal(row((await sh(join(old, 'bin', 'preflight.sh'), ['--proxy-config', NGINX], { PORT, INSPECTO_JAVA_OPTS: LOOPBACK })).out, 'runtime'), 'FAIL');
        const gone = makeBundle(join(t, 'gone'));
        writeFileSync(join(gone, 'spaces.ref'), '../no-such-spaces\n');
        assert.equal(row((await sh(join(gone, 'bin', 'preflight.sh'), [], { PORT })).out, 'spaces-root'), 'FAIL');
    });

    test('preflight.sh: inspecto.env is read as the systemd service reads it, and --endpoint follows the settings', async () => {
        const b = makeBundle(join(tmp('envfile'), 'b'));
        const PORT = String(await freePort());
        writeFileSync(join(b, 'inspecto.env'), `# service settings\nPORT=${PORT}\nINSPECTO_JAVA_OPTS="${LOOPBACK}"\n`);
        const r = (await sh(join(b, 'bin', 'preflight.sh'), ['--proxy-config', NGINX]));
        assert.equal(row(r.out, 'bind'), 'PASS', r.out);
        assert.match(r.out, new RegExp(`port ${PORT} is free`));
        assert.equal((await sh(join(b, 'bin', 'preflight.sh'), ['--endpoint'])).out.trim(), `http://127.0.0.1:${PORT}`);
        rmSync(join(b, 'inspecto.env'));
        assert.equal((await sh(join(b, 'bin', 'preflight.sh'), ['--endpoint'], { PORT: '9443', HTTPS_KEYSTORE: 'x.p12' })).out.trim(), 'https://127.0.0.1:9443');
        // --spaces-root resolves as the server does: SPACES_ROOT, else spaces.ref, else ./spaces
        const spacesRoot = async (extra) => (await sh(join(b, 'bin', 'preflight.sh'), ['--spaces-root'], extra)).out.trim().replace(/\\/g, '/');
        assert.match(await spacesRoot({}), /\/b\/spaces$/);
        writeFileSync(join(b, 'spaces.ref'), '../elsewhere\n');
        assert.match(await spacesRoot({}), /\/b\/\.\.\/elsewhere$/);
        assert.equal(await spacesRoot({ SPACES_ROOT: '/srv/spaces' }), '/srv/spaces');
    });

    // ── verify-bundle.sh ─────────────────────────────────────────────────────────────────────────────────────────────────
    test('verify-bundle.sh: a zip matching its .sha256 passes; a tampered or unaccompanied zip fails', async () => {
        const t = tmp('verify');
        const zip = join(t, 'inspecto-deploy-linux_amd64.zip');
        writeFileSync(zip, 'bundle bytes');
        writeSha(zip);
        const script = join(KIT, 'bin', 'verify-bundle.sh');
        const good = (await sh(script, [zip]));
        assert.equal(good.code, 0, good.out);
        assert.equal(row(good.out, 'sha256', 'verify-bundle'), 'PASS');
        assert.equal(row(good.out, 'signature', 'verify-bundle'), 'WARN');
        assert.equal((await sh(script, [zip, '--require-signature'])).code, 1);
        appendFileSync(zip, 'x');
        const bad = (await sh(script, [zip]));
        assert.equal(bad.code, 1, bad.out);
        assert.equal(row(bad.out, 'sha256', 'verify-bundle'), 'FAIL');
        rmSync(`${zip}.sha256`);
        assert.equal(row((await sh(script, [zip])).out, 'sha256', 'verify-bundle'), 'FAIL');
    });

    // ── backup-offsite.sh ────────────────────────────────────────────────────────────────────────────────────────────────
    test('backup-offsite.sh: copies the newest verified archive and its sidecar; a damaged one copies nothing', async () => {
        const t = tmp('offsite');
        const from = join(t, 'backups');
        const { newest, older } = makeBackups(from);
        const script = join(KIT, 'bin', 'backup-offsite.sh');
        const to = join(t, 'share'); mkdirSync(to);
        const r = (await sh(script, ['--from', from, '--to', to]));
        assert.equal(r.code, 0, r.out);
        assert.deepEqual(readdirSync(to).sort(), [newest, `${newest}.manifest.json`]);
        assert.ok(!readdirSync(to).includes(older));
        assert.equal((await sh(script, ['--from', from, '--verify-only'])).code, 0);
        const viaCmd = join(t, 'cmd'); mkdirSync(viaCmd);
        assert.equal((await sh(script, ['--from', from, '--to', viaCmd, '--cmd', 'cp'])).code, 0);
        assert.equal(readdirSync(viaCmd).length, 2);
        assert.equal((await sh(script, ['--from', from, '--to', 's3://bucket/x/'])).code, 1, 'a URL needs --cmd');
        appendFileSync(join(from, newest), 'x');
        const to2 = join(t, 'share2'); mkdirSync(to2);
        const bad = (await sh(script, ['--from', from, '--to', to2]));
        assert.equal(bad.code, 1, bad.out);
        assert.match(bad.out, /FAIL\s+verify/);
        assert.deepEqual(readdirSync(to2), []);
    });

    // ── upgrade.sh (--no-service: systemd itself is not exercised) ───────────────────────────────────────────────────────
    /**
     * An installed bundle with a Space, a Space kept elsewhere through spaces/<id>.ref, logs, a file of the operator's,
     * a template an App installed at run time (.installed-by) and one only the old bundle ships; and a new bundle zip.
     */
    function upgradeFixture(name, { newEdition = 'Basic', srcHook } = {}) {
        const t = tmp(name);
        const root = makeBundle(join(t, 'inspecto-deploy'));
        mkdirSync(join(root, 'spaces', 'acme', 'config'), { recursive: true });
        writeFileSync(join(root, 'spaces', 'acme', 'config', 'p_pipeline.toon'), 'pipeline');
        writeFileSync(join(root, 'spaces', 'acme', 'space.toon'), 'space');
        mkdirSync(join(root, 'spaces', 'acme', 'data'), { recursive: true });
        writeFileSync(join(root, 'spaces', 'acme', 'data', 'big.parquet'), 'data');
        const ext = join(t, 'ext');
        mkdirSync(join(ext, 'config'), { recursive: true });
        writeFileSync(join(ext, 'config', 'e_pipeline.toon'), 'ext pipeline');
        writeFileSync(join(root, 'spaces', 'ext.ref'), `${ext}\n`);
        mkdirSync(join(root, 'spaces', '_templates', 'app-tpl'), { recursive: true });
        writeFileSync(join(root, 'spaces', '_templates', 'app-tpl', '.installed-by'), 'app=acme-spend\nversion=1.0.0\n');
        mkdirSync(join(root, 'spaces', '_templates', 'retired'), { recursive: true });
        writeFileSync(join(root, 'spaces', '_templates', 'retired', 'gallery.txt'), 'old');
        writeFileSync(join(root, 'logs', 'service.log'), 'old log');
        writeFileSync(join(root, 'my-notes.txt'), 'mine');
        const src = makeBundle(join(t, 'src'), { edition: newEdition, marker: 'new' });
        if (srcHook) srcHook(src);
        const zip = join(t, 'inspecto-deploy-linux_amd64.zip');
        writeZip(zip, src, 'inspecto-deploy');
        writeSha(zip);
        return { t, root, zip };
    }
    const read = (p) => readFileSync(p, 'utf8');
    /** After a successful upgrade: product swapped, templates merged, everything of the install's kept, settings copied. */
    function assertUpgraded({ t, root }) {
        assert.equal(read(join(root, 'lib', 'marker')), 'new');
        assert.equal(read(join(root, 'spaces', '_templates', 'shipped', 'gallery.txt')), 'new');
        assert.ok(existsSync(join(root, 'spaces', '_templates', 'app-tpl', '.installed-by')), "an installed App's template stays");
        assert.ok(!existsSync(join(root, 'spaces', '_templates', 'retired')), 'a template only the old bundle shipped is moved aside');
        assert.equal(read(join(root, 'spaces', 'acme', 'data', 'big.parquet')), 'data');
        assert.ok(existsSync(join(root, 'spaces', 'ext.ref')));
        assert.equal(read(join(root, 'logs', 'service.log')), 'old log');
        assert.equal(read(join(root, 'my-notes.txt')), 'mine');
        const pre = readdirSync(t).find((d) => d.startsWith('inspecto-deploy.pre-upgrade-'));
        assert.ok(pre, readdirSync(t).join(','));
        assert.equal(read(join(t, pre, 'product', 'lib', 'marker')), 'old');
        assert.equal(read(join(t, pre, 'product', 'spaces', '_templates', 'retired', 'gallery.txt')), 'old');
        assert.equal(read(join(t, pre, 'settings', 'spaces', 'acme', 'config', 'p_pipeline.toon')), 'pipeline');
        assert.equal(read(join(t, pre, 'settings', 'spaces', 'acme', 'space.toon')), 'space');
        assert.equal(read(join(t, pre, 'settings', 'spaces', 'ext', 'config', 'e_pipeline.toon')), 'ext pipeline', 'a .ref Space is followed');
        assert.ok(!existsSync(join(t, pre, 'settings', 'spaces', 'acme', 'data')), 'Space data is not copied');
        assert.ok(!readdirSync(t).some((d) => d.startsWith('.inspecto-upgrade-')), 'the staging folder is removed');
    }
    /** After a rollback: the previous bundle is back exactly, the new one left unpacked. */
    function assertRolledBack({ t, root }, out) {
        assert.match(out, /rolled back/);
        assert.equal(read(join(root, 'lib', 'marker')), 'old');
        assert.equal(read(join(root, 'spaces', '_templates', 'shipped', 'gallery.txt')), 'old');
        assert.equal(read(join(root, 'spaces', '_templates', 'retired', 'gallery.txt')), 'old');
        assert.ok(existsSync(join(root, 'spaces', '_templates', 'app-tpl', '.installed-by')));
        assert.equal(read(join(root, 'spaces', 'acme', 'config', 'p_pipeline.toon')), 'pipeline');
        const stage = readdirSync(t).find((d) => d.startsWith('.inspecto-upgrade-'));
        assert.equal(read(join(t, stage, 'inspecto-deploy', 'lib', 'marker')), 'new', 'the new bundle is left unpacked');
    }
    // Windows refuses to move bin/ while Git Bash runs a script from it; the .sh form is for Linux hosts, where CI runs these.
    const LINUX_ONLY = { skip: WIN && 'upgrade.sh swaps bin/ while it runs from it, which Windows forbids (CI runs it on Linux)' };

    test('upgrade.sh: swaps the product, merges the templates, keeps the install, and keeps the previous bundle', LINUX_ONLY, async () => {
        const f = upgradeFixture('up-ok');
        const PORT = String(await freePort());
        const r = (await sh(join(f.root, 'bin', 'upgrade.sh'), [f.zip, '--no-service', '--proxy-config', NGINX], { PORT, INSPECTO_JAVA_OPTS: LOOPBACK }));
        assert.equal(r.code, 0, r.out);
        assertUpgraded(f);
    });

    test('upgrade.sh: a failing preflight puts the previous bundle back', LINUX_ONLY, async () => {
        const f = upgradeFixture('up-rollback');
        const PORT = String(await freePort());
        const r = (await sh(join(f.root, 'bin', 'upgrade.sh'), [f.zip, '--no-service', '--proxy-config', NGINX], { PORT }));   // no loopback bind
        assert.equal(r.code, 1, r.out);
        assertRolledBack(f, r.out);
        assert.ok(existsSync(join(f.root, 'bin', 'upgrade.sh')));
    });

    test('upgrade.sh: a tampered zip or a different edition changes nothing', async () => {
        const PORT = String(await freePort());
        const a = upgradeFixture('up-tamper');
        appendFileSync(a.zip, 'x');
        const r1 = (await sh(join(a.root, 'bin', 'upgrade.sh'), [a.zip, '--no-service'], { PORT, INSPECTO_JAVA_OPTS: LOOPBACK }));
        assert.equal(r1.code, 1, r1.out);
        assert.match(r1.out, /nothing was changed/);
        assert.deepEqual(readdirSync(a.t).sort(), ['ext', 'inspecto-deploy', 'inspecto-deploy-linux_amd64.zip', 'inspecto-deploy-linux_amd64.zip.sha256', 'src']);
        const b = upgradeFixture('up-edition', { newEdition: 'Standard' });
        const r2 = (await sh(join(b.root, 'bin', 'upgrade.sh'), [b.zip, '--no-service'], { PORT, INSPECTO_JAVA_OPTS: LOOPBACK }));
        assert.equal(r2.code, 1, r2.out);
        assert.match(r2.out, /--allow-edition-change/);
        assert.equal(read(join(b.root, 'lib', 'marker')), 'old');
        assert.ok(!readdirSync(b.t).some((d) => d.includes('pre-upgrade')));
    });

    // ── the PowerShell forms ─────────────────────────────────────────────────────────────────────────────────────────────
    test('preflight.ps1: bind and keystore checks match preflight.sh', { skip: !HAS_PWSH && 'pwsh not on PATH' }, async () => {
        const t = tmp('ps-pre');
        const b = makeBundle(join(t, 'b'));
        const PORT = String(await freePort());
        const script = join(b, 'bin', 'preflight.ps1');
        const unset = (await ps(script, ['-ProxyConfig', NGINX], { PORT }));
        assert.equal(row(unset.out, 'bind'), 'FAIL', unset.out);
        assert.equal(unset.code, 1);
        const lo = (await ps(script, ['-ProxyConfig', NGINX], { PORT, INSPECTO_JAVA_OPTS: LOOPBACK }));
        assert.equal(row(lo.out, 'bind'), 'PASS', lo.out);
        assert.equal(row(lo.out, 'proxy'), 'PASS');
        assert.equal(row(lo.out, 'port'), 'PASS');
        assert.equal(row((await ps(script, ['-Direct'], { PORT })).out, 'tls-keystore'), 'FAIL');
        assert.equal(row((await ps(script, ['-ProxyConfig', NGINX], { PORT, INSPECTO_JAVA_OPTS: LOOPBACK, HTTPS_KEYSTORE: join(t, 'nope.p12') })).out, 'tls-keystore'), 'FAIL');
        assert.equal(row((await ps(script, [], { PORT })).out, 'edition'), 'PASS');
        const server = net.createServer();
        await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
        try {
            assert.equal(row((await ps(script, [], { PORT: String(server.address().port) })).out, 'port'), 'FAIL');
        } finally { server.close(); }
        assert.equal((await ps(script, ['-Endpoint'], { PORT: '9443', HTTPS_KEYSTORE: 'x.p12' })).out.trim(), 'https://127.0.0.1:9443');
        assert.equal(row((await ps(script, ['-ProxyConfig', join(KIT, 'proxy', 'Caddyfile')], { PORT, INSPECTO_JAVA_OPTS: LOOPBACK })).out, 'proxy'), 'PASS');
        for (const [name, cfg] of Object.entries(unfenced(t))) {
            assert.equal(row((await ps(script, ['-ProxyConfig', cfg], { PORT, INSPECTO_JAVA_OPTS: LOOPBACK })).out, 'proxy'), 'FAIL', name);
        }
        writeFileSync(join(b, 'spaces.ref'), 'D:\\elsewhere\n');
        assert.equal((await ps(script, ['-SpacesRoot'], { PORT })).out.trim(), 'D:\\elsewhere');
    });

    test('verify-bundle.ps1 and backup-offsite.ps1: tampering FAILs', { skip: !HAS_PWSH && 'pwsh not on PATH' }, async () => {
        const t = tmp('ps-verify');
        const zip = join(t, 'inspecto-deploy-windows_amd64.zip');
        writeFileSync(zip, 'bundle bytes');
        writeSha(zip);
        const vb = join(KIT, 'bin', 'verify-bundle.ps1');
        assert.equal((await ps(vb, [zip])).code, 0);
        appendFileSync(zip, 'x');
        const bad = (await ps(vb, [zip]));
        assert.equal(bad.code, 1, bad.out);
        assert.equal(row(bad.out, 'sha256', 'verify-bundle'), 'FAIL');

        const from = join(t, 'backups');
        const { newest } = makeBackups(from);
        const bo = join(KIT, 'bin', 'backup-offsite.ps1');
        assert.equal((await ps(bo, ['-From', from, '-VerifyOnly'])).code, 0);
        if (WIN) {
            const to = join(t, 'share'); mkdirSync(to);
            const r = (await ps(bo, ['-From', from, '-To', to]));
            assert.equal(r.code, 0, r.out);
            assert.deepEqual(readdirSync(to).sort(), [newest, `${newest}.manifest.json`]);
        }
        appendFileSync(join(from, newest), 'x');
        const damaged = (await ps(bo, ['-From', from, '-VerifyOnly']));
        assert.equal(damaged.code, 1, damaged.out);
        assert.match(damaged.out, /FAIL\s+verify/);
    });

    const WIN_PWSH = { skip: !(WIN && HAS_PWSH) && 'Windows + pwsh only' };
    const alive = (pid) => { try { process.kill(pid, 0); return true; } catch { return false; } };

    test('upgrade.ps1 -NoService: swaps and merges, and leaves a process under a sibling folder alone', WIN_PWSH, async () => {
        const PORT = String(await freePort());
        const f = upgradeFixture('ps-up-ok');
        // C:\...\inspecto-deploy-staging\ shares the bundle folder's name as a prefix, and is not the bundle
        const sibling = join(f.t, 'inspecto-deploy-staging');
        mkdirSync(sibling);
        writeFileSync(join(sibling, 'idle.js'), 'setTimeout(() => {}, 300000);\n');
        // node itself, not the pwsh app-execution alias, so the command line naming the sibling path is this process's
        const idle = spawn(process.execPath, [join(sibling, 'idle.js')], { stdio: 'ignore' });
        try {
            await new Promise((ok) => setTimeout(ok, 1500));
            const r = (await ps(join(f.root, 'bin', 'upgrade.ps1'), [f.zip, '-NoService', '-ProxyConfig', NGINX], { PORT, INSPECTO_JAVA_OPTS: LOOPBACK }));
            assert.equal(r.code, 0, r.out);
            assertUpgraded(f);
            assert.ok(alive(idle.pid), 'the sibling-folder process was not stopped');
        } finally { idle.kill(); }
    });

    test('upgrade.ps1: a rollback stops a process of the new bundle that holds a file and the port, then restores', WIN_PWSH, async () => {
        const PORT = String(await freePort());
        const t0 = tmp('ps-holder-pid');
        const pidFile = join(t0, 'holder.pid');
        // The new bundle's preflight starts a process from the bundle folder - it locks lib\held.lock (no sharing, so
        // Windows refuses to move lib\) and listens on PORT - then fails, as a new java left running would.
        const f = upgradeFixture('ps-up-holder', {
            srcHook: (src) => {
                writeFileSync(join(src, 'lib', 'holder.ps1'), [
                    '$lib = Split-Path -Parent $MyInvocation.MyCommand.Path',
                    "$fs = [System.IO.File]::Open((Join-Path $lib 'held.lock'), 'OpenOrCreate', 'ReadWrite', 'None')",
                    '$l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, [int]$env:PORT); $l.Start()',
                    'Start-Sleep -Seconds 300', ''].join('\r\n'));
                writeFileSync(join(src, 'bin', 'preflight.ps1'), [
                    'param([switch]$Endpoint, [switch]$SpacesRoot, [switch]$Direct, [string]$ProxyConfig = "")',
                    '$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)',
                    'if ($Endpoint) { "http://127.0.0.1:$env:PORT"; exit 0 }',
                    "if ($SpacesRoot) { Join-Path $root 'spaces'; exit 0 }",
                    "$p = Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList @('-NoProfile', '-File', (Join-Path $root 'lib\\holder.ps1')) -WindowStyle Hidden -PassThru",
                    'Set-Content -Path $env:HOLDER_PID_FILE -Value $p.Id',
                    'for ($i = 0; $i -lt 40; $i++) { if (@([System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object { $_.Port -eq [int]$env:PORT }).Count) { break }; Start-Sleep -Milliseconds 250 }',
                    '"[preflight] FAIL  stub: a new-bundle process is left running"; exit 1', ''].join('\r\n'));
            },
        });
        let holder = 0;
        try {
            const r = (await ps(join(f.root, 'bin', 'upgrade.ps1'), [f.zip, '-NoService', '-ProxyConfig', NGINX], { PORT, INSPECTO_JAVA_OPTS: LOOPBACK, HOLDER_PID_FILE: pidFile }));
            holder = Number(read(pidFile).trim());
            assert.ok(holder > 0, r.out);
            assert.equal(r.code, 1, r.out);
            assertRolledBack(f, r.out);
            assert.ok(!alive(holder), 'the new-bundle process was stopped');
        } finally { if (holder && alive(holder)) process.kill(holder); }
    });
});
