// reactor-modules — path-agnostic Maven module identity for the guard tools (docs/superpower/module-architecture-reorg-plan.md §8 D-MR2).
//
// A tool that attributes a repo path to "its module" must not assume the module directory is the FIRST path segment: after a
// directory regroup the modules sit one level down (features/inspecto-ops/...). The module directories are therefore read from
// the poms themselves — every <module> entry of the root pom (default list AND every profile list), recursively through nested
// aggregators — the same resolution inspecto's ReactorModules.java (test tree) does. Files outside any reactor module fall back
// to their first path segment (tools/templates/..., the pom-less asn-parser/src, root files -> null).

/** Historic report label of a module directory whose directory name is not the label the tools always printed. */
const LABEL_ALIAS = { 'asn-parser/asn-decoders': 'asn-parser' };

const MODULE = /<module>\s*([^<\s]+)\s*<\/module>/g;

/** read(repoRelativePosixPath) -> text | null. Returns the Set of reactor module dirs (posix, repo-relative, root excluded). */
export function reactorModuleDirs(read) {
    const dirs = new Set();
    const walk = (dir) => {
        const pom = read(dir ? `${dir}/pom.xml` : 'pom.xml');
        if (pom == null) return;
        for (const m of pom.replace(/<!--[\s\S]*?-->/g, '').matchAll(MODULE)) {
            const child = (dir ? `${dir}/${m[1]}` : m[1]).split('/').filter((s) => s && s !== '.').join('/');
            if (!dirs.has(child)) { dirs.add(child); walk(child); }
        }
    };
    walk('');
    return dirs;
}

/** The OUTERMOST reactor module directory containing `path` (posix, repo-relative), or null. */
export function moduleDirOf(path, dirs) {
    const parts = path.split('/');
    for (let i = 1; i < parts.length; i++) { const d = parts.slice(0, i).join('/'); if (dirs.has(d)) return d; }
    return null;
}

/** The module label of a repo path: its reactor module's directory name; else the first path segment; null for root files. */
export function moduleLabel(path, dirs) {
    const d = moduleDirOf(path, dirs);
    if (d) return Object.entries(LABEL_ALIAS).find(([k]) => d === k || d.endsWith(`/${k}`))?.[1] ?? d.slice(d.lastIndexOf('/') + 1);
    return path.includes('/') ? path.slice(0, path.indexOf('/')) : null;
}
