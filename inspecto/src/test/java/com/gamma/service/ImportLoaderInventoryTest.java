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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
                "pending.jsonl", "pending.held.jsonl"))
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
        ALLOWED.put("audit-anchors.jsonl", "the signed audit anchors (AuditAnchors, ASSURE-AUDIT-CHAIN-1) in "
                + "<config root>.secrets/ beside the Pending Change key — a SIBLING of the config tree, so no import "
                + "reaches it; each line is MAC'd, so a planted one reads as integrity: invalid");
        ALLOWED.put("audit-anchoring.json", "the durable \"anchoring started\" record (AuditAnchors) in "
                + "<config root>.secrets/ beside the key — a SIBLING of the config tree, so no import reaches it");
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

    // ── SUFFIX scans (SEC-IMPORT-OPS-CONFIGS-1) ────────────────────────────────────────────────────

    /** A suffix literal ({@code "_workflow.toon"}, {@code ".grammar.toon"}) anywhere in main code. */
    private static final Pattern SUFFIX = Pattern.compile("\"([._][A-Za-z0-9_]+\\.(?:toon|csv))\"");

    private static final String DATA_SHAPE = "a data-source shape an import is MEANT to carry (ImportPaths.ROOT_SUFFIXES "
            + "/ SUBDIR_SUFFIXES) — Pipelines, Enrichments, Schemas, Jobs, Connections, Mappings, Structures, "
            + "Grammars, Decode Profiles, Views, all written at the same canAuthorWorkbench gate";
    private static final String OUTPUT = "an output sidecar the engine writes under the DATA dir (errors, status, "
            + "measures, enrichment runs/lineage) — no loader reads it from a config root";

    /**
     * Suffixes an import may land, each with why. Every other suffix literal in main code must be refused by
     * {@link ImportPaths} EVERYWHERE — at the root even when referenced, in a subdirectory even when referenced,
     * and under {@code registry/} — because the boot-time scans ({@code ServiceBootstrap.resolveBySuffix},
     * {@code OpsEngineProvider.loadConfigs}) walk the WHOLE config tree: a directory is no containment.
     */
    static final Map<String, String> SUFFIX_ALLOWED = new TreeMap<>();

    static {
        for (String s : List.of("_pipeline.toon", "_enrich.toon", "_schema.toon", "_job.toon", "_connection.toon",
                "_mapping.csv", "_structure.csv", ".grammar.toon", "_grammar.toon", "_profile.toon", "_view.toon"))
            SUFFIX_ALLOWED.put(s, DATA_SHAPE);
        for (String s : List.of("_errors.csv", "_status.csv", "_measures.csv", "_enrich_runs.csv", "_enrich_lineage.csv"))
            SUFFIX_ALLOWED.put(s, OUTPUT);
    }

    private static Map<String, List<String>> suffixScan() throws IOException {
        Map<String, List<String>> where = new TreeMap<>();
        Path reactor = Path.of("..").toAbsolutePath().normalize();
        try (Stream<Path> siblings = Files.list(reactor)) {
            for (Path sibling : siblings.filter(Files::isDirectory).sorted().toList()) {
                Path src = sibling.resolve(Path.of("src", "main", "java"));
                if (!Files.isDirectory(src)) continue;
                try (Stream<Path> files = Files.walk(src)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                        Matcher m = SUFFIX.matcher(Files.readString(f));
                        String at = reactor.relativize(f).toString().replace('\\', '/');
                        while (m.find()) where.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(at);
                    }
                }
            }
        }
        return where;
    }

    /** Whether no import can land a {@code *<suffix>} file anywhere, referenced or not. */
    private static boolean refusedEverywhere(String suffix) {
        String name = "x" + suffix;
        return ImportPaths.shapeRefusal(name, true) != null
                && ImportPaths.shapeRefusal("orders/" + name, true) != null
                && ImportPaths.shapeRefusal("registry/datasets/" + name, false) != null;
    }

    @Test
    void everySuffixALoaderScansForIsRefusedEverywhereOrAllowedWithAReason() throws IOException {
        Map<String, List<String>> found = suffixScan();
        for (String must : List.of("_workflow.toon", "_caserule.toon", "_tagrule.toon", "_tag.toon", "_meta.toon",
                "_rca.toon", "_job_template.toon", "_pipeline.toon"))
            assertTrue(found.containsKey(must), "the suffix scan went blind — it must at least see " + must + ": " + found.keySet());
        Map<String, List<String>> open = new TreeMap<>();
        for (var e : found.entrySet())
            if (!SUFFIX_ALLOWED.containsKey(e.getKey()) && !refusedEverywhere(e.getKey())) open.put(e.getKey(), e.getValue());
        assertTrue(open.isEmpty(), () -> "suffixes a loader scans for that an import could plant — refuse them in "
                + "ImportPaths.REFUSED_SUFFIXES, or add a reason to SUFFIX_ALLOWED: " + open);
    }

    @Test
    void theSuffixAllowListHasNoStaleOrDoubleRows() throws IOException {
        Map<String, List<String>> found = suffixScan();
        Set<String> stale = new LinkedHashSet<>(SUFFIX_ALLOWED.keySet());
        stale.removeAll(found.keySet());
        assertTrue(stale.isEmpty(), () -> "SUFFIX_ALLOWED rows no code names any more: " + stale);
        Set<String> both = new LinkedHashSet<>();
        for (String s : SUFFIX_ALLOWED.keySet()) if (refusedEverywhere(s)) both.add(s);
        assertTrue(both.isEmpty(), () -> "refused AND allowed — drop the allow row: " + both);
        // an allowed DATA shape must really be importable where a bundle puts it (else the row is a lie)
        for (String s : SUFFIX_ALLOWED.keySet())
            if (DATA_SHAPE.equals(SUFFIX_ALLOWED.get(s)))
                assertTrue(ImportPaths.shapeRefusal("orders/x" + s, false) == null, s + " is allowed but refused in a subdirectory");
    }

    /**
     * ASSURE-WORKFLOW-SLA-1: the governance registry dirs are read by {@code GovernanceRegistry} (inspecto-ops) on
     * every change, exactly as the {@code *_workflow.toon} suffix scan reads a workflow — so, like that suffix, no
     * import may land a file in them, referenced or not, whatever the case of the path.
     */
    @Test
    void theGovernanceRegistryDirsAreRefusedToEveryImport() {
        for (String kind : List.of("workflow", "sla-policy", "escalation-rule")) {
            String dir = com.gamma.pipeline.ComponentRegistry.dirForType(kind).orElseThrow();
            assertFalse(ImportPaths.REGISTRY_DIRS.contains(dir), dir);
            for (boolean referenced : List.of(false, true))
                assertTrue(ImportPaths.shapeRefusal("registry/" + dir + "/incident.toon", referenced) != null, dir);
        }
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
