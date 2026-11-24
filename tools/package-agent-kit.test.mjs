// Tests for tools/package-agent-kit.mjs — the per-vendor packages built from the Kit, no network, no disk.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, cpSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { buildPackages, productVersion, readSkills } from './package-agent-kit.mjs';

const OPTS = {
    mcpUrl: 'https://inspecto.example.com/api/v1/spaces/sales/mcp', clientId: 'claude-code', geminiClientId: 'gemini-cli',
    authorizationUrl: 'https://id.example.com/realms/i/protocol/openid-connect/auth',
    tokenUrl: 'https://id.example.com/realms/i/protocol/openid-connect/token', scopes: 'openid', callbackPort: 8765, version: '4.0.0',
};
const skills = readSkills();
const files = buildPackages(OPTS, skills);
const j = (p) => JSON.parse(files[p]);

test('the Claude Code marketplace and plugin are well-formed', () => {
    const m = j('claude/.claude-plugin/marketplace.json');
    assert.equal(m.plugins[0].name, 'inspecto');
    assert.equal(m.plugins[0].source, './plugins/inspecto');
    assert.ok(m.owner.name);
    const p = j('claude/plugins/inspecto/.claude-plugin/plugin.json');
    assert.equal(p.name, 'inspecto');
    assert.equal(p.version, '4.0.0');
    const s = j('claude/plugins/inspecto/.mcp.json').mcpServers.inspecto;
    assert.equal(s.type, 'http');          // a url with no type would be read as stdio
    assert.equal(s.url, OPTS.mcpUrl);
    assert.deepEqual(s.oauth, { clientId: 'claude-code', callbackPort: 8765, scopes: 'openid' });
});

test('the Gemini CLI extension is well-formed', () => {
    const e = j('gemini/inspecto/gemini-extension.json');
    assert.match(e.name, /^[a-z0-9-]+$/);   // lowercase, digits, dashes; equals the folder name
    assert.equal(e.name, 'inspecto');
    assert.equal(e.contextFileName, 'GEMINI.md');
    const s = e.mcpServers.inspecto;
    assert.equal(s.httpUrl, OPTS.mcpUrl);  // httpUrl = Streamable HTTP; url would be SSE
    assert.equal(s.url, undefined);
    assert.equal(s.oauth.clientId, 'gemini-cli');
    assert.equal(s.oauth.redirectUri, 'http://localhost:8765/oauth/callback');
    assert.deepEqual(s.oauth.scopes, ['openid']);
    assert.equal(s.oauth.clientSecret, undefined);
    assert.ok(files['gemini/inspecto/GEMINI.md'].includes('propose_change'));
});

test('the Codex CLI project is well-formed', () => {
    const t = files['codex/.codex/config.toml'];
    assert.match(t, /^\[mcp_servers\.inspecto\]$/m);
    assert.match(t, /^url = "https:\/\/inspecto\.example\.com\/api\/v1\/spaces\/sales\/mcp"$/m);
    assert.match(t, /^scopes = \["openid"\]$/m);
    assert.match(t, /^\[mcp_servers\.inspecto\.oauth\]$/m);
    assert.match(t, /^client_id = "claude-code"$/m);
    assert.match(t, /^callback_port = 8765$/m);
    assert.doesNotMatch(t, /client_secret|bearer_token/);
    assert.ok(files['codex/AGENTS.md'].includes('propose_change'));
});

test('the OpenCode (local model) project is well-formed', () => {
    const s = j('opencode/opencode.json').mcp.servers.inspecto;
    assert.equal(s.type, 'remote');
    assert.equal(s.url, OPTS.mcpUrl);
    assert.deepEqual(s.oauth, { client_id: 'claude-code', scope: 'openid', callback_port: 8765, redirect_uri: 'http://127.0.0.1:8765/callback' });
    assert.ok(files['opencode/AGENTS.md'].includes('propose_change'));
});

test('per-client ids are used where given', () => {
    const f = buildPackages({ ...OPTS, codexClientId: 'codex', localClientId: 'opencode' }, skills);
    assert.match(f['codex/.codex/config.toml'], /^client_id = "codex"$/m);
    assert.equal(JSON.parse(f['opencode/opencode.json']).mcp.servers.inspecto.oauth.client_id, 'opencode');
    assert.throws(() => buildPackages({ ...OPTS, codexClientId: 'a"b' }, skills), /codex-client-id/);
});

test('every skill ships unchanged in every package', () => {
    assert.ok(Object.keys(skills).length >= 7);
    for (const [n, text] of Object.entries(skills)) {
        assert.equal(files[`claude/plugins/inspecto/skills/${n}/SKILL.md`], text);
        assert.equal(files[`gemini/inspecto/skills/${n}/SKILL.md`], text);
        assert.equal(files[`codex/.agents/skills/${n}/SKILL.md`], text);
        assert.equal(files[`opencode/.opencode/skills/${n}/SKILL.md`], text);
    }
});

test('no secret and no unexpanded variable is written', () => {
    for (const [p, text] of Object.entries(files)) {
        if (p.endsWith('SKILL.md')) continue;
        assert.doesNotMatch(text, /clientSecret|client_secret|\$\{/, p);
    }
});

test('the Claude client id is used for Gemini when no Gemini client is given', () => {
    const f = buildPackages({ ...OPTS, geminiClientId: undefined }, skills);
    assert.equal(JSON.parse(f['gemini/inspecto/gemini-extension.json']).mcpServers.inspecto.oauth.clientId, 'claude-code');
});

test('bad inputs are refused', () => {
    const bad = (o, re) => assert.throws(() => buildPackages({ ...OPTS, ...o }, skills), re);
    bad({ mcpUrl: 'http://inspecto.example.com/api/v1/mcp' }, /must be https/);
    bad({ mcpUrl: 'https://inspecto.example.com/api/v1/health' }, /must end in \/mcp/);
    bad({ clientId: 'a b' }, /client id/);
    bad({ clientId: undefined }, /client id/);
    bad({ tokenUrl: 'ftp://x' }, /must be https/);
    bad({ callbackPort: 80 }, /callback-port/);
    bad({ scopes: 'openid a\x7fb' }, /--scopes/);
    bad({ scopes: 'openid "x"' }, /--scopes/);
    bad({ mcpUrl: 'https://inspecto.example.com/api/\x7f/mcp' }, /control character/);
    bad({ mcpUrl: 'https://inspecto.example.com/a\uD800/mcp' }, /non-ASCII/);
    bad({ version: 'latest' }, /SemVer/);
    assert.throws(() => buildPackages(OPTS, {}), /no skills/);
    // blank scopes fall back to openid rather than an empty scope
    const blank = buildPackages({ ...OPTS, scopes: '  ' }, skills);
    assert.deepEqual(JSON.parse(blank['gemini/inspecto/gemini-extension.json']).mcpServers.inspecto.oauth.scopes, ['openid']);
    // localhost over http is allowed for a developer install
    assert.ok(buildPackages({ ...OPTS, mcpUrl: 'http://localhost:8080/api/v1/mcp' }, skills));
});

test('the version defaults to the product version without -SNAPSHOT', () => {
    assert.match(productVersion(), /^\d+\.\d+\.\d+$/);
});

test('in a bundle (agent-kit/ with skills, VERSION, no pom) the packager builds from beside itself', () => {
    // the layout package.ps1 stages: <bundle>/agent-kit/{README.md, skills/, package-agent-kit.mjs, VERSION}
    const bundle = mkdtempSync(join(tmpdir(), 'ak-bundle-'));
    try {
        const kit = join(bundle, 'agent-kit');
        cpSync(fileURLToPath(new URL('../agent-kit/skills', import.meta.url)), join(kit, 'skills'), { recursive: true });
        copyFileSync(fileURLToPath(new URL('./package-agent-kit.mjs', import.meta.url)), join(kit, 'package-agent-kit.mjs'));
        writeFileSync(join(kit, 'VERSION'), '4.1.0-SNAPSHOT\n');
        const r = spawnSync(process.execPath, [join(kit, 'package-agent-kit.mjs'), '--mcp-url', 'https://x.example/api/v1/mcp',
            '--client-id', 'c', '--authorization-url', 'https://id.example/a', '--token-url', 'https://id.example/t'], { encoding: 'utf8' });
        assert.equal(r.status, 0, r.stderr);
        const plugin = JSON.parse(readFileSync(join(kit, 'packages/claude/plugins/inspecto/.claude-plugin/plugin.json'), 'utf8'));
        assert.equal(plugin.version, '4.1.0');
        for (const n of Object.keys(skills)) assert.ok(existsSync(join(kit, 'packages/gemini/inspecto/skills', n, 'SKILL.md')), n);
    } finally {
        rmSync(bundle, { recursive: true, force: true });
    }
});
