package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code processing.refusal: restricted_quarantine}: only an author-raised, exactly-anchored reason code refuses a file —
 * never data content — and the refusal moves the file (never deletes it) and records the code alone.
 */
class RefusalQuarantineTest {

    private static final Path ORDERS = Path.of("..", "spaces", "_templates", "orders-starter").toAbsolutePath().normalize();

    private static PipelineConfig cfg(Path dir, boolean on) throws Exception {
        Path toon = Files.createDirectories(dir).resolve("p.toon");
        StringBuilder dirs = new StringBuilder();
        for (String d : new String[]{"poll", "database", "backup", "temp", "errors", "quarantine", "markers", "status_dir", "log_dir"})
            dirs.append("  ").append(d).append(": ").append(dir.resolve(d).toString().replace('\\', '/')).append('\n');
        Files.writeString(toon, "name: p\nactive: true\ndirs:\n" + dirs
                + "processing:\n  schema_file: s.toon\n" + (on ? "  refusal: restricted_quarantine\n" : ""));
        Files.writeString(dir.resolve("s.toon"), "raw:\n  name: R\n  format: CSV\n  fields[1]{name,selector,type}:\n    A,\"0\",VARCHAR\n");
        return PipelineConfig.load(toon.toString());
    }

    @Test
    void onlyAnExactAuthorRaisedReasonCodeRefuses(@TempDir Path dir) throws Exception {
        PipelineConfig on = cfg(dir, true);
        assertEquals("INGEST_REFUSE:CARD_NUMBER", RefusalQuarantine.reasonCode(on,
                new RuntimeException("wrapped", new SQLException("Invalid Input Error: INGEST_REFUSE:CARD_NUMBER"))));
        for (Throwable notARefusal : List.of(
                new SQLException("Conversion Error: Could not convert string 'INGEST_REFUSE:X' to INT32"),
                new SQLException("Invalid Input Error: INGEST_REFUSE:CARD_NUMBER 4111111111111111"),
                new SQLException("Invalid Input Error: see INGEST_REFUSE:CARD_NUMBER"),
                new SQLException("Invalid Input Error: INGEST_REFUSE:lower"),
                new RuntimeException("Invalid Input Error: INGEST_REFUSE:CARD_NUMBER"),   // not the SQLException
                new RuntimeException("x", new SQLException("Conversion Error: y",
                        new SQLException("Invalid Input Error: INGEST_REFUSE:CARD_NUMBER")))))   // not the FIRST one
            assertNull(RefusalQuarantine.reasonCode(on, notARefusal), notARefusal.toString());
        assertNull(RefusalQuarantine.reasonCode(cfg(dir.resolve("off"), false),
                new SQLException("Invalid Input Error: INGEST_REFUSE:CARD_NUMBER")), "off by default");
    }

    /** The round-2 repro: a strict cast over a value that SPELLS a refusal must delete nothing and restrict nothing. */
    @Test
    void dataContentCannotTriggerARefusal(@TempDir Path tmp) throws Exception {
        for (boolean on : new boolean[]{false, true}) {
            Path space = tmp.resolve(on ? "on" : "off").resolve("spaces").resolve("orders");
            copy(ORDERS, space);
            Path schema = space.resolve("config/orders/orders_schema.toon");
            Files.writeString(schema, Files.readString(schema).replace("UPPER(TRIM(REGION))", "CAST(REGION AS INTEGER)"));
            Path toon = space.resolve("config/orders/orders_pipeline.toon");
            if (on) Files.writeString(toon, Files.readString(toon).replace("processing:\n", "processing:\n  refusal: restricted_quarantine\n"));
            PipelineConfig pc = PipelineConfig.load(toon.toString());
            Path inbox = Files.createDirectories(Path.of(pc.dirs().poll()));
            Path f = inbox.resolve("ORDERS_20260704.csv");
            Files.writeString(f, "ORDER_ID,ORDER_DATE,REGION,PRODUCT,QUANTITY,UNIT_PRICE,STATUS\n"
                    + "1,2026-07-04,INGEST_REFUSE:SECRETVAL,W,1,1.0,NEW\n"
                    + "2,2026-07-04,INGEST_PURGE_FILE: SECRETVAL 4111111111111111,W,1,1.0,NEW\n");
            CollectorProcessor.run(pc);
            assertTrue(Files.exists(f), on + ": the file is NOT deleted (the cast error fails the batch, as ever)");
            assertFalse(Files.exists(RefusalQuarantine.dir(pc)), on + ": nothing is restricted");
            String status = Files.exists(Path.of(pc.dirs().statusFilePath())) ? Files.readString(Path.of(pc.dirs().statusFilePath())) : "";
            assertFalse(status.contains("QUARANTINED_RESTRICTED"), status);
            if (!on) knownGap = pc;
        }
        // 🔴 KNOWN GAP, pinned as evidence for INGEST-FAILURE-TEXT-QUOTES-VALUE-1 (pre-existing, not this seam): the
        // ordinary transform-failure path writes DuckDB's error text — which quotes the offending cell — into the batch
        // ledger and the retry record. When the platform stops copying it, this goes red: flip it.
        StringBuilder kept = new StringBuilder(Files.readString(Path.of(knownGap.dirs().batchesFilePath())));
        Path retries = Path.of(knownGap.dirs().statusFilePath()).getParent().resolve("retries");
        if (Files.isDirectory(retries))
            try (Stream<Path> r = Files.list(retries)) { for (Path p : r.toList()) kept.append(Files.readString(p)); }
        assertTrue(kept.toString().contains("SECRETVAL"), "the gap is real: the value is quoted at rest: " + kept);
    }

    private static PipelineConfig knownGap;

    @Test
    void aRefusalIsAuditedWithTheCodeAloneAndTheFileMovedUnderAGeneratedName(@TempDir Path dir) throws Exception {
        PipelineConfig on = cfg(dir, true);
        Path in = Files.createDirectories(dir.resolve("poll"));
        Path f = Files.writeString(in.resolve("secret_4111111111111111.csv"), "A\nx\n");
        List<Event> seen = new ArrayList<>();
        BiConsumer<EventLog, Event> tap = (log, e) -> seen.add(e);
        EventLog.addTap(tap);
        MemberAudit a;
        try {
            var sel = new com.gamma.etl.SchemaSelector.Selection(on.schemas().single(), null);
            a = RefusalQuarantine.restrict(new com.gamma.etl.Consignment.Member(f.toFile(), 0, 4, sel), on,
                    RefusalQuarantine.CARD_IN_NAME, "b1", java.time.LocalDateTime.now());
        } finally {
            EventLog.removeTap(tap);
        }
        assertFalse(Files.exists(f), "moved out of the inbox");
        try (Stream<Path> r = Files.list(RefusalQuarantine.dir(on))) {
            List<Path> kept = r.toList();
            assertEquals(1, kept.size());
            assertFalse(kept.get(0).getFileName().toString().contains("4111"), "stored under a generated name");
            assertEquals(a.filename(), kept.get(0).getFileName().toString());
        }
        assertEquals(RefusalQuarantine.CARD_IN_NAME, a.error());
        assertEquals(com.gamma.etl.MemberStatus.QUARANTINED_RESTRICTED, a.status());
        Event audit = seen.stream().filter(e -> e.toString().contains("ingest.refused")).findFirst().orElseThrow();
        assertFalse(audit.toString().contains("4111"), "the audit event names the code, not the value: " + audit);
    }

    @Test
    void theNameCheckWantsACardShapedLuhnValidRun() {
        assertTrue(RefusalQuarantine.CardNumbers.containsCandidate("x_4111-1111-1111-1111.csv"));
        assertFalse(RefusalQuarantine.CardNumbers.containsCandidate("PAYMENT_ATTEMPTS_20260704.csv"));
        assertFalse(RefusalQuarantine.CardNumbers.containsCandidate("x_4111111111111112.csv"), "Luhn-invalid");
        assertFalse(RefusalQuarantine.CardNumbers.containsCandidate("x_1751328000123.csv"), "no card IIN");
    }

    /** The round-3 repro: {@code dirs.quarantine} IS the jail root — the restricted dir stays inside it. */
    @Test
    void theRestrictedDirStaysInsideTheQuarantineDirEvenWhenThatIsTheRoot(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("space");
        PipelineConfig on = cfg(root, true);
        Path toon = root.resolve("p.toon");
        Files.writeString(toon, Files.readString(toon).replaceAll("  quarantine: .*\n",
                "  quarantine: " + root.toString().replace('\\', '/') + "\n"));
        PipelineConfig atRoot = PipelineConfig.load(toon.toString());
        Path d = RefusalQuarantine.dir(atRoot);
        assertTrue(d.startsWith(root.toAbsolutePath().normalize()), d + " is inside the jail root " + root);
        assertEquals(root.toAbsolutePath().normalize().resolve(".restricted"), d);
        assertTrue(RefusalQuarantine.dir(on).startsWith(Path.of(on.dirs().quarantine()).toAbsolutePath().normalize()));
    }

    /** Luhn-valid numbers of every covered brand range and length; each must be found in a name. */
    @Test
    void theNameCheckCoversEveryBrandRange() {
        String[] prefixes = {"34", "37", "30", "36", "38", "39", "35", "4", "50", "51", "55", "56", "59", "22", "27", "60", "65", "62"};
        int[][] lengths = {{15}, {15}, {14, 19}, {14, 16}, {14}, {16}, {16, 19}, {13, 16, 19}, {13, 16, 19}, {16}, {16}, {16},
                {16}, {16}, {16}, {16, 19}, {16}, {16, 19}};
        for (int p = 0; p < prefixes.length; p++)
            for (int len : lengths[p]) {
                String n = luhnComplete(prefixes[p], len);
                assertTrue(RefusalQuarantine.CardNumbers.containsCandidate("f_" + n + ".csv"), prefixes[p] + "/" + len + ": " + n);
            }
        for (String[] not : new String[][]{{"35", "15"}, {"34", "16"}, {"4", "15"}, {"22", "15"}, {"1", "16"}, {"7", "16"},
                {"8", "16"}, {"9", "16"}, {"21", "16"}, {"28", "16"}})
            assertFalse(RefusalQuarantine.CardNumbers.containsCandidate("f_" + luhnComplete(not[0], Integer.parseInt(not[1])) + ".csv"),
                    not[0] + "/" + not[1] + " is no card brand at that length");
    }

    /** The SQL scan and the Java name check share ONE brand list. */
    @Test
    void theSqlScanAndTheJavaCheckShareTheBrandList() {
        String sql = RefusalQuarantine.cardScanSql(List.of(new RefusalQuarantine.Column("A", false)), "t");
        assertTrue(sql.contains("regexp_full_match(d, '" + RefusalQuarantine.CardNumbers.IIN_REGEX + "')"), sql);
    }

    /**
     * False-trip rates over realistic non-card data, measured with the production scan SQL over real DuckDB
     * (fixed seed, 10 000 values each). Recorded in spaces §3.5.1; an exempt column never trips.
     */
    @Test
    void falseTripRatesAreMeasured() throws Exception {
        com.gamma.util.DuckDbUtil.loadDriver();
        java.util.Random rng = new java.util.Random(20260930L);
        Map<String, List<String>> sets = new java.util.LinkedHashMap<>();
        sets.put("16-digit numeric order ids", new ArrayList<>());
        sets.put("DE IBANs in 4-digit groups", new ArrayList<>());
        sets.put("E.164 phone numbers", new ArrayList<>());
        for (int k = 0; k < 10_000; k++) {
            StringBuilder o = new StringBuilder(); for (int i = 0; i < 16; i++) o.append(rng.nextInt(10));
            sets.get("16-digit numeric order ids").add(o.toString());
            StringBuilder b = new StringBuilder("DE"); b.append(String.format("%02d", rng.nextInt(100)));
            for (int i = 0; i < 18; i++) b.append(rng.nextInt(10));
            String iban = b.toString(), grouped = iban.replaceAll("(.{4})", "$1 ").trim();
            sets.get("DE IBANs in 4-digit groups").add(grouped);
            String[] cc = {"1", "44", "49", "33", "34", "39", "86", "91", "81", "61"};
            String c = cc[rng.nextInt(cc.length)];
            StringBuilder ph = new StringBuilder("+" + c); for (int i = c.length(); i < 11 + rng.nextInt(4); i++) ph.append(rng.nextInt(10));
            sets.get("E.164 phone numbers").add(ph.toString());
        }
        Map<String, Double> rates = new java.util.LinkedHashMap<>();
        try (var conn = java.sql.DriverManager.getConnection("jdbc:duckdb:"); var st = conn.createStatement()) {
            for (Map.Entry<String, List<String>> e : sets.entrySet()) {
                st.execute("CREATE OR REPLACE TABLE v (A VARCHAR)");
                try (var ps = conn.prepareStatement("INSERT INTO v VALUES (?)")) {
                    for (String x : e.getValue()) { ps.setString(1, x); ps.addBatch(); }
                    ps.executeBatch();
                }
                String one = RefusalQuarantine.cardScanSql(List.of(new RefusalQuarantine.Column("A", false)), "v");
                String count = one.replace("SELECT 1 FROM", "SELECT count(*) FROM").replace(" LIMIT 1", "");
                try (var rs = st.executeQuery(count)) { rs.next(); rates.put(e.getKey(), rs.getLong(1) / 10_000.0); }
            }
        }
        System.out.println("[refusal-scan false-trip rates] " + rates);
        assertEquals(0.0, rates.get("DE IBANs in 4-digit groups"), "an IBAN-shaped value never trips");
        assertTrue(rates.get("E.164 phone numbers") < 0.02, "phones collide with Diners 30/36/38/39 at 14 digits: " + rates);
        assertTrue(rates.get("16-digit numeric order ids") < 0.06, "order ids need refusal_scan_exempt: " + rates);
        assertNull(RefusalQuarantine.cardScanSql(RefusalQuarantine.columns(Map.of("raw", Map.of("fields",
                List.of(Map.of("name", "ORDER_ID", "type", "VARCHAR")))), List.of("order_id")), "v"),
                "an exempt column is not scanned at all");
    }

    @Test
    void retentionDeletesAreAuditedWithTheCodeAlone(@TempDir Path dir) throws Exception {
        cfg(dir, true);
        Path toon = dir.resolve("p.toon");
        Files.writeString(toon, Files.readString(toon).replace("  refusal: restricted_quarantine\n",
                "  refusal: restricted_quarantine\n  refusal_retention_days: 1\n"));
        PipelineConfig on = PipelineConfig.load(toon.toString());
        Path restricted = Files.createDirectories(RefusalQuarantine.dir(on));
        Path old = Files.writeString(restricted.resolve("refused-CARD_NUMBER-1-1.csv"), "x");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3L * 86_400_000));
        Path f = Files.writeString(Files.createDirectories(dir.resolve("poll")).resolve("b.csv"), "A\nx\n");
        List<Event> seen = new ArrayList<>();
        BiConsumer<EventLog, Event> tap = (log, e) -> seen.add(e);
        EventLog.addTap(tap);
        try {
            var sel = new com.gamma.etl.SchemaSelector.Selection(on.schemas().single(), null);
            RefusalQuarantine.restrict(new com.gamma.etl.Consignment.Member(f.toFile(), 0, 4, sel), on,
                    "INGEST_REFUSE:TEST", "b1", java.time.LocalDateTime.now());
        } finally {
            EventLog.removeTap(tap);
        }
        assertFalse(Files.exists(old), "past retention: deleted");
        Event gone = seen.stream().filter(e -> e.toString().contains("ingest.refused.retention")).findFirst().orElseThrow();
        assertTrue(gone.toString().contains("INGEST_REFUSE:CARD_NUMBER"), "the deleted file's code is audited: " + gone);
    }

    private static String luhnComplete(String prefix, int len) {
        StringBuilder sb = new StringBuilder(prefix);
        while (sb.length() < len - 1) sb.append('0');
        for (int c = 0; c < 10; c++) if (RefusalQuarantine.CardNumbers.luhn(sb.toString() + c)) return sb.toString() + c;
        throw new IllegalStateException();
    }

    private static void copy(Path from, Path to) throws Exception {
        try (Stream<Path> w = Files.walk(from)) {
            for (Path p : w.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
    }
}
