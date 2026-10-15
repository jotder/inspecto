package com.gamma.intelligence.pack;

import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.tool.Tool;
import com.gamma.intelligence.context.ContextBroker;
import com.gamma.mask.EvidenceMasker;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.util.BrowsableStore;
import com.gamma.util.ConnectionSource;
import com.gamma.util.JdbcDrivers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AGENT-EGRESS-MASK-1: the row-reading scan tools and every other tool result are masked on the way out - on the
 * direct route unless the caller may reveal, and on the MODEL-BOUND path whenever the provider is not local.
 * (Real-HTTP over the core is not reachable from this module: the agent is a ServiceLoader add-on, absent from the
 * core's test classpath. The tools are driven through the same decorator chain {@code InspectoToolProvider} builds.)
 */
class EgressMaskingTest {

    private static final Set<String> AUTHOR = Set.of("canAuthorWorkbench");
    private static final Set<String> REVEALER = Set.of("canAuthorWorkbench", "canRevealLinkEntities");
    private static final String RAW = "905551234567";

    private Connection conn;
    private CollectorService svc;
    private final List<CollectorService> opened = new java.util.ArrayList<>();

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        opened.forEach(CollectorService::close);
    }

    private static Path spaceWithClassifiedMsisdn(Path root) {
        try {
            new ComponentStore(root.resolve("registry")).write("dataset", "calls", Map.of("physicalRef", "calls",
                    "columns", List.of(Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "dur"))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return root;
    }

    private CollectorService open() {
        CollectorService s = new CollectorService(List.of(), 3600, 1);
        opened.add(s);
        return s;
    }

    private BrowsableStore callsStore(String browseId) throws Exception {
        conn = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE calls(msisdn BIGINT, dur DOUBLE)");
            st.execute("INSERT INTO calls VALUES (905551234567,10),(1,10),(2,10),(3,10),(4,10),(5,10),(6,10),(7,10),(8,10),(9,1000)");
        }
        return new BrowsableStore() {
            @Override public String browseId() { return browseId; }
            @Override public String browseLabel() { return "Test"; }
            @Override public List<String> browseTables() { return List.of("calls"); }
            @Override public ConnectionSource browseSource() { return JdbcDrivers.source(conn); }
        };
    }

    private Tool belt(String name, BrowsableStore store, EgressMasking egress) {
        svc = open();
        return InspectoTools.tools(svc, null, () -> List.of(store), egress).stream()
                .filter(t -> t.spec().name().equals(name)).map(egress::decorate).findFirst().orElseThrow();
    }

    private static ToolResult call(Tool t, Set<String> caps, boolean modelBound, Map<String, Object> args) {
        Supplier<ToolResult> run = () -> t.invoke(new ToolCall(t.spec().name(), args, new RunId("t")));
        return ToolCaller.with(ToolCaller.of(caps), modelBound ? () -> ToolCaller.modelBound(run) : run);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstRow(ToolResult r) {
        assertTrue(r.ok(), () -> String.valueOf(r.error()));
        return ((List<Map<String, Object>>) ((Map<String, Object>) r.value()).get("rows")).get(0);
    }

    private static final Map<String, Object> SCAN_DUR = Map.of("table", "calls", "column", "dur", "threshold", 2.5);

    @Test
    void aCallerWhoCannotRevealGetsTheSameTokenAsTheDbQueryForTheRowKey(@TempDir Path root) throws Exception {
        EgressMasking egress = new EgressMasking(() -> true, () -> spaceWithClassifiedMsisdn(root));
        Tool scan = belt("anomaly_scan", callsStore("jobs"), egress);
        // flag the outlier row whose FIRST column is the classified key: put the key on the outlier
        try (Statement st = conn.createStatement()) { st.execute("UPDATE calls SET msisdn = " + RAW + " WHERE dur = 1000"); }
        Map<String, Object> row = firstRow(call(scan, AUTHOR, false, SCAN_DUR));
        String expected = EvidenceMasker.forSpace(root).tokenFor(RAW);
        assertEquals(expected, row.get("rowIdentifier"));
        assertTrue(expected.matches("masked:[0-9a-f]{16}"));
        assertEquals(1000.0, row.get("value"), "an unclassified column is untouched");
    }

    @Test
    void aClassifiedScannedColumnIsMaskedToo(@TempDir Path root) throws Exception {
        EgressMasking egress = new EgressMasking(() -> true, () -> spaceWithClassifiedMsisdn(root));
        Tool scan = belt("anomaly_scan", callsStore("jobs"), egress);
        Map<String, Object> row = firstRow(call(scan, AUTHOR, false,
                Map.of("table", "calls", "column", "msisdn", "method", "threshold", "threshold", 100)));
        assertEquals(EvidenceMasker.forSpace(root).tokenFor(RAW), row.get("value"));
    }

    @Test
    void aRevealerGetsRawOnTheDirectPath(@TempDir Path root) throws Exception {
        EgressMasking egress = new EgressMasking(() -> true, () -> spaceWithClassifiedMsisdn(root));
        Tool scan = belt("anomaly_scan", callsStore("jobs"), egress);
        Map<String, Object> row = firstRow(call(scan, REVEALER, false,
                Map.of("table", "calls", "column", "msisdn", "method", "threshold", "threshold", 100)));
        assertEquals(905551234567L, row.get("value"));
    }

    @Test
    void theModelBoundPathIsMaskedEvenForARevealerWhenTheProviderIsNotLocal(@TempDir Path root) throws Exception {
        Map<String, Object> args = Map.of("table", "calls", "column", "msisdn", "method", "threshold", "threshold", 100);
        EgressMasking hosted = new EgressMasking(() -> false, () -> spaceWithClassifiedMsisdn(root));
        Object seen = firstRow(call(belt("anomaly_scan", callsStore("jobs"), hosted), REVEALER, true, args)).get("value");
        assertEquals(EvidenceMasker.forSpace(root).tokenFor(RAW), seen);
        conn.close();

        EgressMasking local = new EgressMasking(() -> true, () -> root);
        Tool scan = belt("anomaly_scan", callsStore("jobs"), local);
        assertEquals(905551234567L, firstRow(call(scan, REVEALER, true, args)).get("value"),
                "a local provider keeps a revealer's raw value");
        assertTrue(String.valueOf(firstRow(call(scan, AUTHOR, true, args)).get("value")).startsWith("masked:"),
                "a local provider still masks for a caller who cannot reveal");
    }

    @Test
    void anUnboundCallerFailsClosed(@TempDir Path root) throws Exception {
        EgressMasking egress = new EgressMasking(() -> true, () -> spaceWithClassifiedMsisdn(root));
        Tool scan = belt("anomaly_scan", callsStore("jobs"), egress);
        ToolResult r = scan.invoke(new ToolCall("anomaly_scan",
                Map.of("table", "calls", "column", "msisdn", "method", "threshold", "threshold", 100), new RunId("t")));
        assertTrue(String.valueOf(firstRow(r).get("value")).startsWith("masked:"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void suggestExpectationsMasksTheBoundsOfAClassifiedColumnAndDropsTheRangeDraft(@TempDir Path root) throws Exception {
        EgressMasking egress = new EgressMasking(() -> true, () -> spaceWithClassifiedMsisdn(root));
        Tool suggest = belt("suggest_expectations", callsStore("jobs"), egress);
        ToolResult r = call(suggest, AUTHOR, false, Map.of("table", "calls", "column", "msisdn"));
        Map<String, Object> v = (Map<String, Object>) r.value();
        Map<String, Object> profile = (Map<String, Object>) v.get("profile");
        assertTrue(String.valueOf(profile.get("max")).startsWith("masked:"));
        assertFalse(r.value().toString().contains(RAW));
        assertTrue(((List<Map<String, Object>>) v.get("suggestions")).stream().noneMatch(s -> s.toString().contains("range")));

        Map<String, Object> clean = (Map<String, Object>) call(suggest, AUTHOR, false,
                Map.of("table", "calls", "column", "dur")).value();
        assertEquals(1000.0, ((Map<String, Object>) clean.get("profile")).get("max"), "unclassified column untouched");
    }

    @Test
    void entityBearingStoresAreRefusedOutright(@TempDir Path root) throws Exception {
        EgressMasking egress = new EgressMasking(() -> true, () -> root);
        for (String id : List.of("objects", "links", "notes", "tag-assignments", "events", "delivery-receipts")) {
            if (conn != null) conn.close();
            Tool scan = belt("anomaly_scan", callsStore(id), egress);
            ToolResult r = call(scan, REVEALER, false, SCAN_DUR);
            assertFalse(r.ok(), id + " must be refused");
            assertTrue(r.error().contains("entity keys"), r.error());
        }
    }

    // ── signals: defence in depth ──────────────────────────────────────────────────────────────

    private static Signal sig(String id, String message, Map<String, Object> payload) {
        return new Signal(id, "call.failed", Instant.now(), Severity.ERROR, Ref.of("pipeline", "p"), Ref.of("pipeline", "p"),
                "corr-1", null, null, null, message, payload, 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSignalMessageAndPayloadCarryingAMsisdnAreMaskedInTheTools(@TempDir Path root) throws Exception {
        EgressMasking egress = new EgressMasking(() -> false, () -> root);
        svc = open();
        svc.events().append(sig("s1", "call failed msisdn=" + RAW + " cause=timeout", Map.of("msisdn", RAW)).toEvent());
        String token = EvidenceMasker.forSpace(root).tokenFor(RAW);
        for (String name : List.of("signal_timeline", "timeline_build", "signals_query")) {
            Tool t = InspectoTools.tools(svc, null, List::of, egress).stream()
                    .filter(x -> x.spec().name().equals(name)).map(egress::decorate).findFirst().orElseThrow();
            Map<String, Object> args = name.equals("signal_timeline") ? Map.of("correlationId", "corr-1")
                    : Map.of("sinceMinutes", 60);
            ToolResult r = call(t, REVEALER, true, args);
            assertTrue(r.ok(), name + ": " + r.error());
            assertFalse(r.value().toString().contains(RAW), name + " leaked the raw msisdn: " + r.value());
            assertTrue(r.value().toString().contains(token), name + " must carry the token");
        }
        ContextBroker broker = new ContextBroker(svc, egress);
        String overlay = ToolCaller.with(ToolCaller.of(REVEALER), () -> ToolCaller.modelBound(broker::liveOverlay));
        assertFalse(overlay.contains(RAW), overlay);
        assertTrue(overlay.contains(token), overlay);
    }
}
