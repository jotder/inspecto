package com.gamma.pipeline;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The name-contract check described in {@code docs/okf/frontend/features/pipelines.md} (§3.3 of the
 * archived {@code docs/archived-documents/plans-archive/vocabulary-and-config-contract-plan.md}):
 * per node type, a key the UI declares must be the key the engine reads.
 *
 * <p>⚠ <b>Bound to ONE representation.</b> "Read somewhere in {@code PipelineLift} /
 * {@code PipelineCompiler} / {@code RowShaper}" is too weak a right-hand side — {@code where}
 * satisfies it via {@code RowShaper} while being unreachable from the flat {@code *_pipeline.toon}
 * the editor actually writes, which is exactly how D1 looked fixed while filters still did nothing.
 * So this drives the <b>real reachable path</b> end to end:
 *
 * <pre>{@code   PipelineEditable.toMap → PipelineCodec → [set key] → PipelineEditable.lower
 *                        → ConfigCodec.toToon → PipelineConfig.load → engine field }</pre>
 *
 * A sentinel that does not survive that trip is a key nothing reads, whatever it is spelled.
 * {@code PipelineLift.lift} alone would NOT do: it is the legacy/authored lift and emits a different
 * vocabulary ({@code includes} plural) than the editable lift ({@code include}, verbatim from file).
 *
 * <p>The declared side mirrors {@code inspecto-ui/src/app/.../pipelines/node-attributes.ts} — keep the
 * two in step; {@code node-attributes.spec.ts} pins the same names on the TS side. Keys are written
 * here in their <b>post-{@code nestKeys}</b> form: the dialog turns a declared {@code stability__window}
 * into node cfg {@code stability.window} before saving (D4).
 */
class NodeConfigNameContractTest {

    /** One declared attribute: where it lands in node cfg, and the engine field that must receive it. */
    private record Contract(String nodeType, String uiKey, String cfgPath, Object sentinel,
                            Function<PipelineConfig, Object> engineReads, Object expected) {}

    private static List<Contract> contracts() {
        return List.of(
                // ── transform.filter — the two filtering moments (D1/D7) ────────────────────────
                new Contract("transform.filter", "where", "where", "AMOUNT > 0",
                        c -> c.csv().where(), "AMOUNT > 0"),
                new Contract("transform.filter", "include_regex", "include_regex", List.of("^CALL"),
                        c -> c.csv().includeRegex(), List.of("^CALL")),
                new Contract("transform.filter", "exclude_regex", "exclude_regex", List.of("^TEST"),
                        c -> c.csv().excludeRegex(), List.of("^TEST")),
                new Contract("transform.filter", "include_prefixes", "include_prefixes", List.of("A"),
                        c -> c.csv().includePrefixes(), List.of("A")),
                new Contract("transform.filter", "exclude_prefixes", "exclude_prefixes", List.of("Z"),
                        c -> c.csv().excludePrefixes(), List.of("Z")),
                new Contract("transform.filter", "filter_target_column", "filter_target_column", 4,
                        c -> c.csv().filterTargetColumn(), 4),

                // ── acquisition — the shared COLLECTOR_ATTRIBUTES table ─────────────────────────
                new Contract("acquisition", "include", "include", List.of("glob:**/*.dat"),
                        c -> c.collector().includes(), List.of("glob:**/*.dat")),
                new Contract("acquisition", "exclude", "exclude", List.of("*.tmp"),
                        c -> c.collector().excludes(), List.of("*.tmp")),
                new Contract("acquisition", "discovery", "discovery", "watch",
                        c -> c.collector().discovery(), "watch"),
                new Contract("acquisition", "recursive_depth", "recursive_depth", 7,
                        c -> c.collector().recursiveDepth(), 7),
                new Contract("acquisition", "guarantee", "guarantee", "EXACTLY_ONCE",
                        c -> c.collector().guarantee(), PipelineConfig.Guarantee.EXACTLY_ONCE),
                new Contract("acquisition", "stability__window", "stability.window", "45s",
                        c -> c.collector().stability().windowMillis(), 45_000L),
                new Contract("acquisition", "post_action__on_success", "post_action.on_success", "MOVE",
                        c -> c.collector().postAction().onSuccess(), "MOVE"),
                new Contract("acquisition", "post_action__archive_path", "post_action.archive_path", "/arch",
                        c -> c.collector().postAction().archivePath(), "/arch"),

                // ── the `duplicate:` keys — on ACQUISITION since the 2026-08-04 fold. D9 had split
                // them onto a `transform.dedup.fingerprint` node; that node was removed because the
                // check executes in the CollectorProcessor poll cycle (`ledgerFilter`), so it had no
                // runtime of its own and misrepresented where dedup happens.
                new Contract("acquisition", "duplicate__mode", "duplicate.mode", "etag",
                        c -> c.collector().duplicate().mode(), "etag"),
                new Contract("acquisition", "duplicate__on_change", "duplicate.on_change",
                        "skip", c -> c.collector().duplicate().onChange(), "skip"),

                // ── transform.dedup — the record-grain dedup Step (ELT amendment P2 lowering) ────
                new Contract("transform.dedup", "keys", "keys", List.of("EVENT_DATE"),
                        c -> c.dedup().keys(), List.of("EVENT_DATE")),
                new Contract("transform.dedup", "order_by", "order_by", "EVENT_DATE DESC",
                        c -> c.dedup().orderBy(), "EVENT_DATE DESC"),

                // ── sink.persistent — the destination + the shared OUTPUT_ATTRIBUTES table ──────
                // `database` is the key `lower` hard-requires on the primary sink (NO_PERSISTENT_SINK);
                // it lowers onto `dirs.database`, which is where the engine reads the write root.
                new Contract("sink.persistent", "database", "database", "data/contract/db2",
                        c -> c.dirs().database(), "data/contract/db2"),
                new Contract("sink.persistent", "format", "format", "PARQUET",
                        c -> c.output().format(), "PARQUET"),
                new Contract("sink.persistent", "compression", "compression", "zstd",
                        c -> c.output().compression(), "zstd"));
    }

    /**
     * Forward direction (D2/D5's failure mode: declared but read by nothing). Every declared
     * attribute, set through the editor's own save path, must land on the engine field that reads it.
     */
    @Test
    void everyDeclaredAttributeReachesTheEngine(@TempDir Path dir) throws Exception {
        List<String> unreachable = new ArrayList<>();
        for (Contract k : contracts()) {
            PipelineConfig reparsed = saveThrough(dir, k.nodeType(), k.cfgPath(), k.sentinel());
            Object actual = k.engineReads().apply(reparsed);
            if (!k.expected().equals(actual))
                unreachable.add("%s.%s (cfg %s): engine read %s, expected %s"
                        .formatted(k.nodeType(), k.uiKey(), k.cfgPath(), actual, k.expected()));
        }
        assertTrue(unreachable.isEmpty(),
                "declared attributes that do not survive a save to the flat config:\n  "
                        + String.join("\n  ", unreachable));
    }

    /**
     * Reverse direction, and the one that would have caught D7: {@code PipelineLift.filterConfig}
     * had always emitted the pre-parse vocabulary, but nothing declared it — so a working feature was
     * invisible in the dialog for months. The filter node's cfg IS the filter vocabulary (lower copies
     * it wholesale into {@code processing.csv_settings}), so equality is the right assertion here.
     *
     * <p>Not asserted for {@code acquisition}/{@code sink.persistent}: those nodes carry the whole raw
     * {@code collector:}/{@code output:} block and their tables are deliberately a curated subset, with
     * the dialog's free-form editor as the escape hatch.
     *
     * <p>⚠ The fixture must exercise <b>every</b> filter capability: {@code filterConfig} omits a key
     * whose list is empty, so an under-populated fixture fails this as a phantom "missing spec".
     */
    @Test
    void everyKeyTheFilterNodeCarriesIsDeclared(@TempDir Path dir) throws Exception {
        Set<String> declared = Set.of("where", "include_regex", "exclude_regex",
                "include_prefixes", "exclude_prefixes", "filter_target_column");
        Path toon = writeFixture(dir);
        PipelineGraph g = liftEditable(toon);

        Set<String> carried = nodeOfType(g, "transform.filter").config().keySet();
        assertEquals(declared, carried,
                "the filter node's config keys and node-attributes.ts must match exactly — an extra key "
                        + "here is a capability the dialog cannot reach (D7), a missing one is a dead spec");
    }

    /**
     * What the acquisition <em>node</em> owns of the {@code collector:} block. These assertions exist so
     * the ownership line cannot silently move.
     *
     * <p>Since the 2026-08-04 fold the node owns the block almost whole — {@code duplicate:} included,
     * because file duplicate detection executes inside the {@code CollectorProcessor} poll cycle
     * ({@code ledgerFilter}), i.e. during acquisition. D9 had routed those keys through a
     * {@code transform.dedup.fingerprint} node; that node was removed (it had no runtime of its own and
     * placed the check after collection, which is not where it happens). {@code gap_detection} keeps its
     * own node, and {@code connection} is not a cfg key at all.
     */
    @Test
    void theAcquisitionNodeOwnsTheCollectorBlockExceptGapAndConnection(@TempDir Path dir) throws Exception {
        // `connection` is not a cfg key at all: it rides on `use: connection/<name>`, and lower strips a
        // cfg-level one (`PipelineEditable:248`). D3-remainder closed this in the UI by making the
        // `connection` attribute write `use:` instead of cfg — the engine contract below is unchanged.
        assertNull(saveThrough(dir, "acquisition", "connection", "prod_sftp").collector().connection(),
                "`connection` became a cfg key — the UI writes it on `use:`, so this is a contract change");

        // The fold's core guarantee: a `duplicate.mode` typed on the acquisition node now REACHES the
        // engine instead of losing to a node overlay. (Under D9 the fixture's own `checksum` won.)
        assertEquals("etag", saveThrough(dir, "acquisition", "duplicate.mode", "etag")
                        .collector().duplicate().mode(),
                "the acquisition node lost `duplicate.mode` to an overlay — the 2026-08-04 fold "
                        + "regressed; a value typed on the node is being silently discarded again");
    }

    /**
     * The declared-but-unreachable case, pinned rather than fixed. {@code transform.route},
     * {@code sink.materialized} and {@code sink.view} have attribute tables, but the flat config has no
     * home for them — {@code PipelineEditable.lower} refuses them with {@code UNSUPPORTED_NODE} and the
     * palette greys them out up front. Their specs are therefore unreachable from the pipeline editor
     * by design, not by defect. This pins the set so adding a spec for a non-lowerable type is a
     * conscious act; if one of these becomes lowerable, add its keys to {@link #contracts()}.
     */
    @Test
    void declaredTypesTheFlatEditorCannotSaveAreKnown() {
        assertTrue(PipelineEditable.isLowerable("acquisition"));
        assertTrue(PipelineEditable.isLowerable("transform.filter"));
        assertTrue(PipelineEditable.isLowerable("sink.persistent"));
        // The fingerprint-dedup node was removed 2026-08-04 (no runtime of its own — dedup executes
        // in the poll cycle), so a stale graph still carrying one is refused rather than half-honoured.
        assertFalse(PipelineEditable.isLowerable("transform.dedup.fingerprint"));

        // transform.route and transform.dedup became lowerable in the ELT amendment P2 route/dedup
        // slice; dedup's keys joined contracts(), route's contract is the draft-path test below
        // (arming a route: pipeline is refused at prepare(), so it cannot ride saveThrough's load).
        assertTrue(PipelineEditable.isLowerable("transform.route"));
        assertTrue(PipelineEditable.isLowerable("transform.dedup"));

        // transform.summarize became lowerable in the ELT amendment P3 S1 slice; like route, an active
        // pipeline carrying it is refused at prepare() (compile-only until a recipe-driven executor
        // lands), so its contract is the draft-path test below too, not contracts().
        assertTrue(PipelineEditable.isLowerable("transform.summarize"));

        for (String authoredOnly : List.of("sink.materialized", "sink.view"))
            assertFalse(PipelineEditable.isLowerable(authoredOnly),
                    authoredOnly + " became lowerable — its declared attributes now need a contract entry");
    }

    /**
     * The route node's draft-path contract: {@code route:} survives lift → edit → lenient lower →
     * re-decode, with each branch stamped with its destination database. Uses {@code fromMap} (no
     * {@code prepare()}) because arming a {@code route:} pipeline is deliberately refused.
     */
    @Test
    void routeAttributesSurviveTheDraftSavePath(@TempDir Path dir) throws Exception {
        Path toon = writeFixture(dir);
        Map<String, Object> raw = decode(toon);
        raw.put("active", Boolean.FALSE);
        raw.put("route", new LinkedHashMap<>(Map.of(
                "mode", "case",
                "branches", List.of(new LinkedHashMap<>(Map.of("key", "emea", "where", "ID LIKE 'E%'"))))));

        PipelineConfig cfg = PipelineConfig.fromMap(raw);
        PipelineGraph g = PipelineCodec.fromMap(PipelineEditable.toMap(cfg, raw));

        List<PipelineNode> nodes = new ArrayList<>();
        for (PipelineNode n : g.nodes()) {
            if (!"transform.route".equals(n.type())) { nodes.add(n); continue; }
            Map<String, Object> c = new LinkedHashMap<>(n.config());
            c.put("mode", "clone");
            nodes.add(new PipelineNode(n.id(), n.type(), n.name(), n.description(), c, n.use()));
        }
        Map<String, Object> lowered = PipelineEditable.lower(
                new PipelineGraph(g.name(), g.active(), nodes, g.edges()), raw, false);

        PipelineConfig reparsed = PipelineConfig.fromMap(lowered);
        assertNotNull(reparsed.routeConfig());
        assertEquals("clone", reparsed.routeConfig().get("mode"),
                "a route edit typed in the editor must survive the draft save");
    }

    /**
     * The summarize node's draft-path contract: {@code processing.summarize} survives lift → edit →
     * lenient lower → re-decode. Uses {@code fromMap} (no {@code prepare()}) because arming an active
     * pipeline carrying {@code summarize} is deliberately refused, same posture as {@code route}.
     */
    @Test
    void summarizeAttributesSurviveTheDraftSavePath(@TempDir Path dir) throws Exception {
        Path toon = writeFixture(dir);
        Map<String, Object> raw = decode(toon);
        raw.put("active", Boolean.FALSE);
        @SuppressWarnings("unchecked")
        Map<String, Object> processing = (Map<String, Object>) raw.get("processing");
        processing.put("summarize", new LinkedHashMap<>(Map.of(
                "group_by", List.of("EVENT_DATE"), "measures", List.of("count"))));

        PipelineConfig cfg = PipelineConfig.fromMap(raw);
        PipelineGraph g = PipelineCodec.fromMap(PipelineEditable.toMap(cfg, raw));

        List<PipelineNode> nodes = new ArrayList<>();
        for (PipelineNode n : g.nodes()) {
            if (!"transform.summarize".equals(n.type())) { nodes.add(n); continue; }
            Map<String, Object> c = new LinkedHashMap<>(n.config());
            c.put("measures", List.of("count", "sum(AMOUNT)"));
            nodes.add(new PipelineNode(n.id(), n.type(), n.name(), n.description(), c, n.use()));
        }
        Map<String, Object> lowered = PipelineEditable.lower(
                new PipelineGraph(g.name(), g.active(), nodes, g.edges()), raw, false);

        PipelineConfig reparsed = PipelineConfig.fromMap(lowered);
        assertNotNull(reparsed.summarize());
        assertEquals(List.of("EVENT_DATE"), reparsed.summarize().groupBy());
        assertEquals(List.of("count", "sum(AMOUNT)"), reparsed.summarize().measures(),
                "a summarize edit typed in the editor must survive the draft save");
    }

    // ── the editor's real save path ────────────────────────────────────────────────

    /** Lift the fixture, set {@code cfgPath} on the {@code nodeType} node, lower, re-read as the engine does. */
    private static PipelineConfig saveThrough(Path dir, String nodeType, String cfgPath, Object value)
            throws Exception {
        Path toon = writeFixture(dir);
        Map<String, Object> raw = decode(toon);
        PipelineGraph g = liftEditable(toon);

        List<PipelineNode> nodes = new ArrayList<>();
        for (PipelineNode n : g.nodes()) {
            if (!n.type().equals(nodeType) || isQuarantine(n)) { nodes.add(n); continue; }
            Map<String, Object> cfg = new LinkedHashMap<>(n.config());
            put(cfg, cfgPath, value);
            nodes.add(new PipelineNode(n.id(), n.type(), n.name(), n.description(), cfg, n.use()));
        }
        Map<String, Object> lowered = PipelineEditable.lower(
                new PipelineGraph(g.name(), g.active(), nodes, g.edges()), raw, true);

        Path saved = dir.resolve("saved_pipeline.toon");
        Files.writeString(saved, ConfigCodec.toToon(lowered));
        return PipelineConfig.load(saved.toString());
    }

    private static PipelineGraph liftEditable(Path toon) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        return PipelineCodec.fromMap(PipelineEditable.toMap(cfg, decode(toon)));
    }

    /** The quarantine node is a {@code sink.persistent} too, but carries only {@code dir}. */
    private static boolean isQuarantine(PipelineNode n) {
        return "quarantine".equals(n.id());
    }

    private static PipelineNode nodeOfType(PipelineGraph g, String type) {
        return g.nodes().stream().filter(n -> n.type().equals(type)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " node in the lifted graph"));
    }

    /** Set a dotted path, creating intermediate maps ({@code stability.window} → nested). */
    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> cfg, String path, Object value) {
        int dot = path.indexOf('.');
        if (dot < 0) { cfg.put(path, value); return; }
        Object child = cfg.get(path.substring(0, dot));
        Map<String, Object> nested = (child instanceof Map<?, ?> m)
                ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        put(nested, path.substring(dot + 1), value);
        cfg.put(path.substring(0, dot), nested);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decode(Path toon) throws Exception {
        return (Map<String, Object>) (Map<?, ?>) ConfigLoader.filesystem().decode(toon.toString());
    }

    /** A single-schema CSV pipeline carrying every block the contracts touch, filters included. */
    private static Path writeFixture(Path dir) throws Exception {
        Path sf = dir.resolve("schema.toon");
        Files.writeString(sf, """
                partitionKey: EVENT_DATE
                raw:
                  name: ed_data
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID, "0", VARCHAR
                    EVENT_DATE, "1", DATE
                mapping:
                  canonicalName: ed_data
                  rawName: ed_data
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID, ID, DIRECT
                    EVENT_DATE, EVENT_DATE, DIRECT
                """);
        String base = dir.toString().replace('\\', '/');
        Path p = dir.resolve("contract_pipeline.toon");
        Files.writeString(p, """
                name: NAME_CONTRACT
                active: true
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                  backup: %1$s/backup
                  temp: %1$s/temp
                  quarantine: %1$s/quarantine
                output:
                  format: CSV
                  compression: none
                collector:
                  connector: local
                  duplicate:
                    mode: checksum
                    on_change: reprocess
                  include[1]: "glob:**/*.csv"
                  exclude[1]: "*.bak"
                  recursive_depth: 1
                  discovery: poll
                  guarantee: BEST_EFFORT
                  stability:
                    window: 5s
                  post_action:
                    on_success: RETAIN
                processing:
                  threads: 2
                  schema_file: %2$s
                  dedup:
                    keys[1]: ID
                    order_by: ID
                  csv_settings:
                    delimiter: ","
                    include_regex[1]: "^A"
                    exclude_regex[1]: "^B"
                    include_prefixes[1]: "C"
                    exclude_prefixes[1]: "D"
                    filter_target_column: 0
                    where: "ID IS NOT NULL"
                """.formatted(base, sf.toString().replace('\\', '/')));
        return p;
    }
}
