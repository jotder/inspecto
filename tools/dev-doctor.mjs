#!/usr/bin/env node
// Read-only developer environment check for a new or existing laptop.
//
//   node tools/dev-doctor.mjs [--json]
//
// Every check only PROBES (child_process / fs reads) and prints ✓ / ⚠ / ✗ with the exact fix command.
// Nothing is modified. Exit 1 if any ✗, else 0. The check functions are pure over an injected `probe`
// (see realProbe) so tools/dev-doctor.test.mjs drives them with fixtures.
import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync, statSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const OK = 'ok', WARN = 'warn', FAIL = 'fail';
const r = (status, name, detail, fix) => ({ status, name, detail, ...(fix ? { fix } : {}) });

/** Major version from `java -version` / `mvn -v` text ("27.0.1", "1.8.0_x" → 8). */
export function javaMajor(text) {
    const m = /version "?(\d+)(?:\.(\d+))?/.exec(text ?? '') || /Java version: (\d+)/.exec(text ?? '');
    if (!m) return null;
    const a = Number(m[1]);
    return a === 1 && m[2] ? Number(m[2]) : a;
}
const verOf = (text, re) => { const m = re.exec(text ?? ''); return m ? m.slice(1).map(Number) : null; };
const gte = (v, min) => { for (let i = 0; i < min.length; i++) { if ((v[i] ?? 0) !== min[i]) return (v[i] ?? 0) > min[i]; } return true; };

export function checkJava(p) {
    const out = [];
    const path = p.run('java', ['-version']);
    const pm = path.ok ? javaMajor(path.out) : null;
    // a java that RAN but printed nothing is a launcher stub whose JDK moved (Oracle's javapath\java.exe exits 0xC0000409)
    const crashed = !path.ok && path.status != null && !path.out.trim();
    out.push(pm === 27 ? r(OK, 'java on PATH', 'major 27')
        : r(FAIL, 'java on PATH', pm ? `major ${pm}, need 27` : crashed ? `the first java on PATH crashed (exit ${path.status}) — a launcher stub pointing at a moved or removed JDK, e.g. Oracle's javapath` : 'not found',
            'put %JAVA_HOME%\\bin FIRST on PATH; remove stale JDK entries (old openjdk bins, Oracle\\Java\\javapath); open a new shell'));
    const jh = p.env.JAVA_HOME;
    if (!jh) out.push(r(FAIL, 'JAVA_HOME', 'not set', 'setx JAVA_HOME "<jdk-27 dir>"  (then open a new shell)'));
    else {
        const hv = p.run(join(jh, 'bin', 'java'), ['-version']);
        const hm = hv.ok ? javaMajor(hv.out) : null;
        out.push(hm === 27 ? r(OK, 'JAVA_HOME java', `${jh} → major 27`)
            : r(FAIL, 'JAVA_HOME java', `${jh} → ${hm ? `major ${hm}` : 'no java'}; Maven fails "release version 27 not supported"`,
                'setx JAVA_HOME "<jdk-27 dir>"  (then open a new shell)'));
    }
    const mvn = p.run('mvn', ['-v']);
    const mm = mvn.ok ? javaMajor(mvn.out) : null;
    if (mvn.ok) out.push(mm === 27 ? r(OK, 'mvn -v Java', '27') : r(FAIL, 'mvn -v Java', `${mm ?? 'unknown'}, need 27`, 'point JAVA_HOME at JDK 27'));
    return out;
}

export function checkMaven(p) {
    const out = [];
    const mvn = p.run('mvn', ['-v']);
    const v = mvn.ok ? verOf(mvn.out, /Apache Maven (\d+)\.(\d+)/) : null;
    out.push(v && gte(v, [3, 9]) ? r(OK, 'maven', `${v.join('.')}`)
        : r(FAIL, 'maven', v ? `${v.join('.')}, need 3.9+` : 'not found', 'install Apache Maven 3.9+ and add its bin to PATH'));
    const repo = join(p.home, '.m2', 'repository');
    out.push(p.exists(join(repo, 'com', 'eoiagent')) ? r(OK, '~/.m2 com/eoiagent', 'present')
        : r(FAIL, '~/.m2 com/eoiagent', 'missing (built from source jotder/inspect-agent)',
            'gh repo clone jotder/inspect-agent && cd inspect-agent && mvn -DskipTests install'));
    out.push(p.exists(join(repo, 'com', 'gamma', 'inspector')) ? r(OK, '~/.m2 com/gamma/inspector', 'present')
        : r(FAIL, '~/.m2 com/gamma/inspector', 'no previous inspecto install', 'mvn -DskipTests install  (repo root, online once)'));
    return out;
}

export function checkNode(p) {
    const out = [];
    const v = verOf(p.nodeVersion, /v?(\d+)\.(\d+)/);
    out.push(v && v[0] >= 22 ? r(OK, 'node', p.nodeVersion) : r(FAIL, 'node', `${p.nodeVersion}, need >= 22`, 'install Node.js 22 LTS or newer'));
    const npm = p.run('npm', ['-v']);
    out.push(npm.ok ? r(OK, 'npm', npm.out.trim()) : r(FAIL, 'npm', 'not found', 'reinstall Node.js (ships npm)'));
    out.push(p.exists(join(p.root, 'inspecto-ui', 'node_modules')) ? r(OK, 'inspecto-ui/node_modules', 'present')
        : r(FAIL, 'inspecto-ui/node_modules', 'missing', 'cd inspecto-ui && npm ci'));
    return out;
}

export function checkCodegraph(p) {
    const cg = p.run('codegraph', ['--version']);
    return [
        cg.ok ? r(OK, 'codegraph CLI', cg.out.trim().split('\n')[0]) : r(FAIL, 'codegraph CLI', 'not on PATH', 'install the codegraph CLI and add it to PATH'),
        p.exists(join(p.root, '.codegraph')) ? r(OK, '.codegraph index', 'present') : r(FAIL, '.codegraph index', 'missing', 'codegraph index'),
    ];
}

export function checkGit(p) {
    const cfg = (k) => { const x = p.run('git', ['-C', p.root, 'config', '--get', k]); return x.ok ? x.out.trim() : ''; };
    const hooks = cfg('core.hooksPath');
    const name = cfg('user.name'), email = cfg('user.email');
    const gh = p.run('gh', ['auth', 'status']);
    return [
        hooks === '.githooks' ? r(OK, 'git core.hooksPath', '.githooks')
            : r(FAIL, 'git core.hooksPath', hooks ? `'${hooks}'` : 'unset', 'git config core.hooksPath .githooks'),
        name && email ? r(OK, 'git identity', 'user.name + user.email set')
            : r(FAIL, 'git identity', `missing ${[!name && 'user.name', !email && 'user.email'].filter(Boolean).join(' + ')}`,
                'git config user.name "<name>" && git config user.email "<email>"'),
        gh.ok ? r(OK, 'gh auth', 'authenticated') : r(WARN, 'gh auth', 'not authenticated or gh missing', 'gh auth login'),
    ];
}

// Ports from the repo docs: WSO2 IS 9443 (admin-guide-local-testing.md), postgres 55432 (admin-guide-ha-dr-drill.md),
// MinIO 127.0.0.1:9000 (module/acquire spec). Hints only — the doctor never starts a container.
export const CONTAINERS = [
    { name: 'postgres', match: /postgres/i, hint: 'docker run -d --name postgres -p 127.0.0.1:55432:5432 -e POSTGRES_PASSWORD=postgres postgres:latest' },
    { name: 'WSO2 IS', match: /wso2is/i, hint: 'docker run -d --name wso2is -p 9443:9443 wso2/wso2is' },
    { name: 'MinIO', match: /minio/i, hint: 'docker run -d --name minio -p 127.0.0.1:9000:9000 minio/minio server /data' },
];
export function checkDocker(p) {
    const info = p.run('docker', ['info', '--format', '{{.ServerVersion}}']);
    if (!info.ok) return [r(WARN, 'docker', 'daemon not reachable', 'start Docker Desktop')];
    const ps = p.run('docker', ['ps', '-a', '--format', '{{.Image}}\t{{.Names}}\t{{.State}}']);
    const rows = ps.ok ? ps.out.split('\n').filter(Boolean).map((l) => l.split('\t')) : [];
    const out = [r(OK, 'docker', `server ${info.out.trim()}`)];
    for (const c of CONTAINERS) {
        const hit = rows.filter(([img, nm]) => c.match.test(img) || c.match.test(nm ?? ''));
        const running = hit.find((h) => h[2] === 'running');
        if (running) out.push(r(OK, `container ${c.name}`, `${running[1]} running`));
        else if (hit.length) out.push(r(WARN, `container ${c.name}`, `${hit[0][1]} present, not running`, `docker start ${hit[0][1]}`));
        else out.push(r(WARN, `container ${c.name}`, 'absent', c.hint));
    }
    return out;
}

export function checkDuckdbCache(p) {
    const env = p.env.DUCKDB_EXTENSION_CACHE;
    const dirs = [env, join(p.root, '.duckdb-extension-cache')].filter(Boolean);
    const hit = dirs.find((d) => p.exists(d));
    return [hit ? r(OK, 'DuckDB extension cache', hit)
        : r(FAIL, 'DuckDB extension cache', 'no .duckdb-extension-cache/ and DUCKDB_EXTENSION_CACHE unset', 'node tools/fetch-duckdb-extensions.mjs')];
}

/** Directories at depth 1 and 2 of the checkout that cannot be listed. */
export function checkUnreadable(p) {
    const bad = [];
    for (const a of p.listDirs(p.root) ?? []) {
        if (a === '.git' || a === 'node_modules') continue;
        const sub = p.listDirs(join(p.root, a));
        if (sub === null) { bad.push(a); continue; }
        for (const b of sub) if (p.listDirs(join(p.root, a, b)) === null) bad.push(`${a}/${b}`);
    }
    return [bad.length ? r(WARN, 'unreadable dirs', bad.join(', '), 'close the process holding them, or fix their ACL') : r(OK, 'unreadable dirs', 'none')];
}

/** Claude Code's per-project dir name: every ':' '\' '/' becomes '-'. */
export const claudeProjectDir = (root) => root.replace(/[:\\/]/g, '-');
export function localOnlyFiles(p) {
    return [
        ['product-map/private/', join(p.root, 'product-map', 'private')],
        ['SESSION_STATUS.local.md', join(p.root, 'SESSION_STATUS.local.md')],
        ['.claude/sessions/snapshot.md', join(p.root, '.claude', 'sessions', 'snapshot.md')],
        ['docs/superpower/rfp-mvno-assurance/', join(p.root, 'docs', 'superpower', 'rfp-mvno-assurance')],
        ['docs/superpower/group-regulator-assurance/', join(p.root, 'docs', 'superpower', 'group-regulator-assurance')],
        ['Claude memory dir', join(p.home, '.claude', 'projects', claudeProjectDir(p.root), 'memory')],
    ];
}
export function checkLocalOnly(p) {
    return localOnlyFiles(p).map(([label, path]) => p.exists(path) ? r(OK, `local-only ${label}`, 'present')
        : r(WARN, `local-only ${label}`, 'missing (never in git)', `copy by hand from the previous machine to ${path}`));
}

export const CHECKS = [
    ['Java', checkJava], ['Maven', checkMaven], ['Node', checkNode], ['CodeGraph', checkCodegraph], ['Git', checkGit],
    ['Docker', checkDocker], ['DuckDB', checkDuckdbCache], ['Checkout', checkUnreadable], ['Local-only files', checkLocalOnly],
];
export function runAll(p) {
    return CHECKS.map(([group, fn]) => {
        try { return { group, results: fn(p) }; } catch (e) { return { group, results: [r(FAIL, group, `check crashed: ${e.message}`)] }; }
    });
}
export const exitCode = (groups) => (groups.some((g) => g.results.some((x) => x.status === FAIL)) ? 1 : 0);

export function realProbe(root) {
    return {
        root, home: homedir(), env: process.env, nodeVersion: process.version,
        run(cmd, args) {
            // No shell first: a shell splits arguments on whitespace (a docker --format with tabs broke into pieces).
            // Only a Windows .cmd shim (mvn, npm, codegraph) needs the shell — retry with it, arguments quoted.
            const opts = { encoding: 'utf8', timeout: 20000, windowsHide: true };
            let x = spawnSync(cmd, args, opts);
            // (also when the program died with no output at all: Oracle's javapath\java.exe launcher exits 0xC0000409
            // when started without a console shell, then works through one)
            if (process.platform === 'win32' && (x.error?.code === 'ENOENT' || (x.status !== 0 && !`${x.stdout ?? ''}${x.stderr ?? ''}`.trim())))
                x = spawnSync([cmd, ...args].map((a) => (/[\s"]/.test(a) ? `"${a.replace(/"/g, '\\"')}"` : a)).join(' '), [], { ...opts, shell: true });
            return { ok: !x.error && x.status === 0, status: x.error ? null : x.status, out: `${x.stdout ?? ''}${x.stderr ?? ''}` };
        },
        exists: (path) => existsSync(path),
        listDirs(path) {
            try { return readdirSync(path, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name); }
            catch { try { statSync(path); } catch { /* vanished */ } return null; }
        },
    };
}

if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
    const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
    const groups = runAll(realProbe(root));
    if (process.argv.includes('--json')) console.log(JSON.stringify({ groups, exit: exitCode(groups) }, null, 2));
    else {
        const mark = { ok: '✓', warn: '⚠', fail: '✗' };
        for (const g of groups) {
            console.log(`\n${g.group}`);
            for (const x of g.results) console.log(`  ${mark[x.status]} ${x.name}: ${x.detail}${x.fix ? `\n      fix: ${x.fix}` : ''}`);
        }
        console.log(exitCode(groups) ? '\n✗ environment has blocking problems' : '\n✓ environment OK (see ⚠ for optional items)');
    }
    process.exit(exitCode(groups));
}
