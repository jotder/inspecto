#!/usr/bin/env node
// Where Maven's LOCAL repository is on this machine, and the third-party jars package.ps1 copies out of it
// (PACKAGE-DEMOAUTH-ADVANCED-1). package.ps1 used to read `%USERPROFILE%\.m2\repository` directly, which is
// wrong wherever <localRepository> in ~/.m2/settings.xml moves it (here: C:/.m2/repository) - the driver was
// "not in the local Maven repo" although Maven itself held it. One resolution, shared with tools/sbom.mjs:
//   1. $M2_REPO,  2. <localRepository> in ~/.m2/settings.xml (a value with ${...} is not expanded -> skipped),
//   3. ~/.m2/repository.
// CLI (read by package.ps1):  node tools/maven-repo.mjs            -> the local repository directory
//                             node tools/maven-repo.mjs --pg-driver <version>  -> the driver jar path (exit 1 if absent)
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { fileURLToPath } from 'node:url';

export function m2Repo({ env = process.env, home = homedir() } = {}) {
    if (env.M2_REPO) return env.M2_REPO;
    const settings = join(home, '.m2', 'settings.xml');
    if (existsSync(settings)) {
        const m = readFileSync(settings, 'utf8').match(/<localRepository>\s*([^<]+?)\s*<\/localRepository>/);
        if (m && !m[1].includes('${')) return m[1];
    }
    return join(home, '.m2', 'repository');
}

/** Where the PostgreSQL JDBC driver `version` sits inside a local repository. */
export const pgDriverPath = (repo, version) => join(repo, 'org', 'postgresql', 'postgresql', version, `postgresql-${version}.jar`);

function main(argv) {
    const repo = m2Repo();
    const i = argv.indexOf('--pg-driver');
    if (i < 0) { console.log(repo); return 0; }
    const jar = pgDriverPath(repo, argv[i + 1]);
    console.log(jar);
    return existsSync(jar) ? 0 : 1;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) process.exit(main(process.argv.slice(2)));
