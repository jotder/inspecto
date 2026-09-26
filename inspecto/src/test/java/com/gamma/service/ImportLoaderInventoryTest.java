package com.gamma.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LOADER inventory behind the import judge (`SEC-IMPORT-ROLES-ESCALATION-1`): every file or directory a
 * loader opens by a FIXED name is one an import could plant, so each must be reserved from import
 * ({@link ReservedConfigPaths#reserved}) or sit on {@link #ALLOWED} with the written reason an import landing it
 * is harmless or impossible. The miss this exists for: {@code demo-users.toon} — {@code DemoUsers} reads it from
 * every Space's config root and a Demo User may carry {@code roles: super}, yet an import could write it; and
 * {@code agent/policy.json}, the assist agent's autonomy policy.
 *
 * <p>The scan is lexical, over every reactor module's {@code src/main/java}, and collects three shapes:
 * <ol>
 *   <li>a chain of literal {@code .resolve("a").resolve("b")…} calls — the path {@code a/b}, directories
 *       included (a reserved directory covers everything under it);</li>
 *   <li>{@code AgentWriteRoot.resolve("x")} — the path {@code agent/x} (the assist write root's subtree);</li>
 *   <li>any string literal naming a {@code .toon / .json / .jsonl / .tsv / .journal / .key} file (a constant
 *       passed to {@code resolve(FILE)}, {@code ToonHelper.load}, …) — covered when it is reserved itself or is
 *       the last segment of a reserved shape-1/2 path.</li>
 * </ol>
 * ⚠ A name built by concatenation ({@code "role-" + name + ".toon"}) is invisible to it — keep fixed names literal.
 */
class ImportLoaderInventoryTest {

    private static final Pattern CHAIN = Pattern.compile("((?:\\.resolve\\(\\s*\"[^\"\\\\]+\"\\s*\\))+)");
    private static final Pattern SEG = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern AGENT = Pattern.compile("AgentWriteRoot\\.resolve\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern NAME = Pattern.compile(
            "\"([A-Za-z0-9._][A-Za-z0-9._/-]*\\.(?:toon|json|jsonl|tsv|journal|key))\"");

    private static final String NOT_CONFIG = "not under a Space config root: a Space-root sibling, the data dir, "
            + "a temp/audit dir or the spaces root — an import writes under config/ only (BundleImporter jail)";
    private static final String IN_ZIP = "a bundle / backup zip's own manifest, split out by the reader, never written";
    private static final String SHARED = "the Data Exchange store under <spaces-root>/_shared/ — outside every Space";
    private static final String REPO = "a repository / classpath resource (docs, UI sources, parity fixtures) — "
            + "not a Space file";
    private static final String IMPORTABLE = "authored content an import is MEANT to carry (Pipelines, Jobs, "
            + "Views, registry components, schemas, mappings) at the same canAuthorWorkbench gate";
    private static final String NOT_A_FILE = "a key, attribute or table name, not a file";

    /** Fixed names an import may land (or cannot reach), each with why. Keys are the scanned path/name. */
    static final Map<String, String> ALLOWED = new TreeMap<>();

    static {
        for (String p : List.of("exchange", "current.toon", "snapshot.parquet", "snapshot.parquet.tmp"))
            ALLOWED.put(p, SHARED);
        for (String p : List.of("template.toon"))
            ALLOWED.put(p, "the shipped Space template catalog under <spaces-root>/_templates/ — outside every Space");
        for (String p : List.of("bundle.toon", "manifest.toon", "backup-manifest.json"))
            ALLOWED.put(p, IN_ZIP);
        for (String p : List.of("config", "data", "data/events", "database", "duckdb", "errors", "logs", "poll",
                "quarantine", "temp", "status", "manifests", "acquire", "artifacts",
                "job-packs-staging", "jobs_runs.csv", "runlog", "flows", "pipelines", "investigations",
                "alert-rules", "sets", "pending", "header.json", "log.jsonl", "attachments.jsonl", "mask.key",
                "pending.jsonl"))
            ALLOWED.put(p, NOT_CONFIG);
        for (String p : List.of("docs", "docs/GLOSSARY.md", "docs/api/openapi-v1.json", "openapi-v1.json",
                "inspecto-ui", "inspecto-ui/src/app/app.routes.ts", "index.html", "parser.json"))
            ALLOWED.put(p, REPO);
        for (String p : List.of("registry", "registry/datasets", "jobs", "views", "schemas", "mappings"))
            ALLOWED.put(p, IMPORTABLE);
        ALLOWED.put("archived-config", "ConfigMigrator's backup of a migrated config — written, never read back as "
                + "live config, so a planted file there changes nothing");
        // AccessGrants.java:79-80 resolves these under a variable already holding <config>/registry — the literal
        // chain is split there, so the scan sees them without the prefix. Pinned reserved in the test below.
        for (String p : List.of("access-catalog/catalog.toon", "catalog.toon"))
            ALLOWED.put(p, "registry/access-catalog/catalog.toon (AccessGrants) — inside the RESERVED "
                    + "registry/access-catalog/");
        for (String p : List.of("reference.key", "inspecto.idempotency.key"))
            ALLOWED.put(p, NOT_A_FILE);
    }

    private record Found(Set<String> paths, Map<String, List<String>> where) {}

    private static Found scan() throws IOException {
        Set<String> paths = new TreeSet<>();
        Map<String, List<String>> where = new LinkedHashMap<>();
        Path reactor = Path.of("..").toAbsolutePath().normalize();
        try (Stream<Path> siblings = Files.list(reactor)) {
            for (Path sibling : siblings.filter(Files::isDirectory).sorted().toList()) {
                Path src = sibling.resolve(Path.of("src", "main", "java"));
                if (!Files.isDirectory(src)) continue;
                try (Stream<Path> files = Files.walk(src)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                        String text = Files.readString(f);
                        String at = reactor.relativize(f).toString().replace('\\', '/');
                        List<String> found = new ArrayList<>();
                        Matcher c = CHAIN.matcher(text);
                        while (c.find()) {
                            List<String> segs = new ArrayList<>();
                            Matcher s = SEG.matcher(c.group(1));
                            while (s.find()) segs.add(s.group(1));
                            found.add(String.join("/", segs));
                        }
                        Matcher a = AGENT.matcher(text);
                        while (a.find()) found.add("agent/" + a.group(1));
                        Matcher n = NAME.matcher(text);
                        while (n.find()) found.add(n.group(1));
                        for (String p : found) {
                            paths.add(p);
                            where.computeIfAbsent(p, k -> new ArrayList<>()).add(at);
                        }
                    }
                }
            }
        }
        return new Found(paths, where);
    }

    /**
     * Whether {@code p} is reserved, is a bare name that some reserved scanned path ends with, or is a name no
     * import can ever write because the segment rules refuse it (a suffix literal like {@code _job.toon} or
     * {@code .grammar.toon}, a {@code __pending.json} sidecar) — proven per name by calling the rule, not assumed.
     */
    private static boolean covered(String p, Set<String> all) {
        if (ReservedConfigPaths.reserved(p)) return true;
        if (ImportPaths.segmentRefusal(p) != null) return true;
        if (p.contains("/") || !p.contains(".")) return false;   // a bare FILE name (a constant), never a bare dir
        for (String q : all) if (q.endsWith("/" + p) && ReservedConfigPaths.reserved(q)) return true;
        return false;
    }

    @Test
    void everyFixedNameALoaderReadsIsReservedOrAllowedWithAReason() throws IOException {
        Found f = scan();
        for (String must : List.of("roles.toon", "demo-users.toon", "agent/policy.json", "agent/approvals.jsonl",
                "recon-state", "rename.journal", "offers.toon", "grants.toon"))
            assertTrue(f.paths().contains(must), "the scan went blind — it must at least see " + must + ": " + f.paths());
        Map<String, List<String>> open = new TreeMap<>();
        for (String p : f.paths())
            if (!covered(p, f.paths()) && !ALLOWED.containsKey(p)) open.put(p, f.where().get(p));
        assertTrue(open.isEmpty(), () -> "fixed names a loader reads that an import could plant — reserve them in "
                + "ReservedConfigPaths, or add a reason to ALLOWED: " + open);
    }

    @Test
    void theAllowReasonsThatPointAtAReservedDirectoryAreTrue() {
        assertTrue(ReservedConfigPaths.reserved("registry/access-catalog/catalog.toon"));
    }

    @Test
    void theAllowListHasNoStaleOrDoubleRows() throws IOException {
        Set<String> paths = scan().paths();
        Set<String> stale = new LinkedHashSet<>(ALLOWED.keySet());
        stale.removeAll(paths);
        assertTrue(stale.isEmpty(), () -> "ALLOWED rows no code names any more: " + stale);
        Set<String> both = new LinkedHashSet<>();
        for (String p : ALLOWED.keySet()) if (covered(p, paths)) both.add(p);
        assertTrue(both.isEmpty(), () -> "reserved AND allowed — drop the allow row: " + both);
    }
}
