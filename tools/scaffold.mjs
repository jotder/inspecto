#!/usr/bin/env node
// Pack scaffolder (platform-services plan S1-8). Generates a standalone, offline-buildable pack
// project from the templates in tools/templates/ — the piece that makes the plugin envelope usable.
//
//   node tools/scaffold.mjs new job       --id acme.reconcile --name "Acme Reconcile"
//   node tools/scaffold.mjs new processor --id acme.masker    --name "Acme Masker"
//   node tools/scaffold.mjs new nodetype  --id acme.redact    --name "Acme Redact"
//   node tools/scaffold.mjs new step      ...   # refuses until the Step-kind registry lands (S2-3)
//   node tools/scaffold.mjs new service   ...   # refuses until contributed services land (S3-1)
//
// Design notes, each one load-bearing:
//
//   * NO ARCHETYPE. Maven archetypes resolve from a repository; this repo builds air-gapped. Plain
//     file templates + token stamping need nothing but Node, which the vocabulary guard already
//     assumes. Zero dependencies, same as its neighbours check-vocabulary.mjs / check-secrets.mjs.
//   * COORDINATES ARE READ FROM THE REPO, never hardcoded. The generated pom pins the engine
//     groupId/artifactId/version the pack was scaffolded from, read out of inspecto-engine/pom.xml
//     at generation time — so an artifactId or version change cannot leave this script emitting a
//     dependency that does not resolve.
//   * TOKENS ARE {{name}}, NOT ${name}. A generated pom.xml legitimately contains Maven's own
//     ${...} properties (${project.version}); sharing the delimiter would mean stamping over them.
//   * REFUSALS ARE HONEST. `new step` and `new service` do not emit a half-working skeleton for a
//     mount the engine cannot host yet; they name the slice that unlocks them and exit non-zero.

import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const templateRoot = join(repoRoot, 'tools', 'templates');

const KINDS = {
    job: { template: 'job', gate: null },
    processor: { template: 'processor', gate: null },
    // A node type is a pipeline STEP deployed on the engine CLASSPATH — the same delivery as a
    // processor, and deliberately NOT the gated `step` kind below, which is pack-hosted (isolated
    // classloader, StepContext, watchdog) and still owned by platform-services S2-3.
    nodetype: { template: 'nodetype', gate: null },
    step: {
        gate: 'PACK-hosted Steps are not hosted yet. The Step-kind registry opens at S2-3 of '
            + 'docs/superpower/platform-services-plan.md (StepContext, the services ceiling, failure '
            + 'mapping and the watchdog), which is itself gated on the branch-aware executor becoming '
            + 'the armed path — until then a pack-loaded Step could not run. '
            + 'For a Step you can ship TODAY, scaffold a `nodetype`: it is a node type deployed on the '
            + 'engine CLASSPATH (PipelineNodeType + PipelineNodeExecutor), which runs now. What it does '
            + 'NOT get is what this gate is about — hot deploy, an isolated classloader, and a watchdog.',
    },
    service: {
        gate: 'Packs cannot contribute Platform Services yet. That is S3-1 of '
            + 'docs/superpower/platform-services-plan.md (the ServiceProvider SPI + collision '
            + 'handling). A pack can already *consume* every service in the v1 menu — declare it in '
            + "the Job Type's requires: list.",
    },
};

// ── args ───────────────────────────────────────────────────────────────────────

function parseArgs(argv) {
    const [verb, kind, ...rest] = argv;
    const flags = {};
    for (let i = 0; i < rest.length; i++) {
        if (!rest[i].startsWith('--')) fail(`unexpected argument '${rest[i]}'`);
        const key = rest[i].slice(2);
        const value = rest[i + 1];
        if (value === undefined || value.startsWith('--')) fail(`--${key} needs a value`);
        flags[key] = value;
        i++;
    }
    return { verb, kind, flags };
}

function usage() {
    return [
        'usage: node tools/scaffold.mjs new <job|processor|nodetype> --id <id> --name "<Name>" [--package <pkg>] [--out <dir>]',
        '',
        '  --id       the type id authors reference, e.g. acme.reconcile (lowercase, dot-separated)',
        '  --name     the human title shown in the UI',
        '  --package  Java package for the generated sources (default: com.example.pack)',
        '  --out      where to generate (default: packs-dev/<id>/, which is gitignored)',
    ].join('\n');
}

function fail(message) {
    console.error(`scaffold: ${message}`);
    process.exit(1);
}

// ── the repo's own coordinates ─────────────────────────────────────────────────

/** First match of <tag>value</tag>, or null. Deliberately not an XML parser: these poms are
 *  hand-maintained and the four values wanted are unambiguous single occurrences. */
function tag(xml, name) {
    const m = xml.match(new RegExp(`<${name}>([^<]+)</${name}>`));
    return m ? m[1].trim() : null;
}

function engineCoordinates() {
    const enginePom = join(repoRoot, 'inspecto-engine', 'pom.xml');
    if (!existsSync(enginePom)) fail(`cannot find ${relative(repoRoot, enginePom)} — run this from the repo`);
    const xml = readFileSync(enginePom, 'utf8');
    const parent = xml.slice(xml.indexOf('<parent>'), xml.indexOf('</parent>'));
    // The module declares its own artifactId; groupId and version come from the parent it inherits.
    const artifactId = tag(xml.slice(xml.indexOf('</parent>')), 'artifactId');
    const groupId = tag(parent, 'groupId');
    const version = tag(parent, 'version');
    if (!artifactId || !groupId || !version) fail('could not read the engine coordinates from its pom');

    const rootPom = readFileSync(join(repoRoot, 'pom.xml'), 'utf8');
    return {
        engineGroupId: groupId,
        engineArtifactId: artifactId,
        engineVersion: version,
        junitVersion: tag(rootPom, 'junit.version') ?? '5.10.2',
        javaRelease: tag(rootPom, 'maven.compiler.release') ?? '24',
        compilerPluginVersion: pluginVersion(rootPom, 'maven-compiler-plugin') ?? '3.13.0',
        surefirePluginVersion: pluginVersion(rootPom, 'maven-surefire-plugin') ?? '3.2.5',
    };
}

/** The <version> that follows a plugin's <artifactId> in the root pom's pluginManagement. */
function pluginVersion(xml, artifactId) {
    const at = xml.indexOf(`<artifactId>${artifactId}</artifactId>`);
    return at < 0 ? null : tag(xml.slice(at), 'version');
}

// ── naming ─────────────────────────────────────────────────────────────────────

const ID_PATTERN = /^[a-z][a-z0-9]*(\.[a-z0-9]+)*$/;

/** acme.reconcile → AcmeReconcile. The class name is derived, never asked for: two names for one
 *  thing is how a scaffolded project drifts from its own id. */
function classNameOf(id) {
    return id.split(/[.\-_]/).map(part => part.charAt(0).toUpperCase() + part.slice(1)).join('');
}

/** acme.reconcile → acme-reconcile, a legal Maven artifactId. */
function artifactIdOf(id) {
    return id.replace(/[._]/g, '-');
}

// ── generation ─────────────────────────────────────────────────────────────────

function stamp(text, tokens) {
    return text.replace(/\{\{(\w+)\}\}/g, (whole, key) => {
        if (!(key in tokens)) fail(`template referenced unknown token {{${key}}}`);
        return tokens[key];
    });
}

/** Copy a template tree, stamping both path segments (__className__, __packageDir__) and content. */
function generate(from, to, tokens, written) {
    for (const entry of readdirSync(from)) {
        const source = join(from, entry);
        const name = entry
            .replace('__packageDir__', tokens.packageName.replace(/\./g, '/'))
            .replace('__className__', tokens.className);
        const target = join(to, name);
        if (statSync(source).isDirectory()) {
            mkdirSync(target, { recursive: true });
            generate(source, target, tokens, written);
        } else {
            mkdirSync(dirname(target), { recursive: true });
            writeFileSync(target, stamp(readFileSync(source, 'utf8'), tokens));
            written.push(target);
        }
    }
}

// ── main ───────────────────────────────────────────────────────────────────────

const { verb, kind, flags } = parseArgs(process.argv.slice(2));

if (verb === '--help' || verb === '-h' || verb === undefined) {
    console.log(usage());
    process.exit(verb === undefined ? 1 : 0);
}
if (verb !== 'new') fail(`unknown command '${verb}'\n\n${usage()}`);
if (!kind || !(kind in KINDS)) fail(`unknown kind '${kind ?? ''}' — expected one of ${Object.keys(KINDS).join(', ')}`);
if (KINDS[kind].gate) fail(`cannot scaffold a ${kind} yet.\n\n${KINDS[kind].gate}`);

const id = flags.id;
if (!id) fail(`--id is required\n\n${usage()}`);
if (!ID_PATTERN.test(id)) fail(`--id '${id}' must be lowercase, dot-separated, e.g. acme.reconcile`);
if (!flags.name) fail(`--name is required\n\n${usage()}`);

const packageName = flags.package ?? 'com.example.pack';
if (!/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$/.test(packageName))
    fail(`--package '${packageName}' is not a legal Java package`);

const outDir = flags.out ? join(repoRoot, flags.out) : join(repoRoot, 'packs-dev', id);
if (existsSync(outDir)) fail(`${relative(repoRoot, outDir)} already exists — delete it or pass --out`);

const tokens = {
    id,
    name: flags.name,
    // A node type's discriminator is `transform.<suffix>`: dots are not legal inside it, because
    // RowShaper matches the type STRING exactly and `transform.dedup*` is already prefix-matched.
    typeSuffix: id.replace(/\./g, '_'),
    className: classNameOf(id),
    artifactId: artifactIdOf(id),
    packageName,
    ...engineCoordinates(),
};

const written = [];
mkdirSync(outDir, { recursive: true });
generate(join(templateRoot, KINDS[kind].template), outDir, tokens, written);

const where = relative(repoRoot, outDir).replace(/\\/g, '/');
console.log(`scaffolded ${kind} '${id}' into ${where}/`);
for (const file of written.map(f => relative(outDir, f).replace(/\\/g, '/')).sort())
    console.log(`  ${file}`);
console.log(`\nagainst ${tokens.engineGroupId}:${tokens.engineArtifactId}:${tokens.engineVersion}\n`);
console.log(`next:  cd ${where} && mvn -o test`);
console.log(`then:  read ${where}/README.md — it has the deploy and signing commands`);
