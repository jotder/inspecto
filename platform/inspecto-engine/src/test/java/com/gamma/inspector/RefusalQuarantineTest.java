package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.audit.Event;
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

    private static final Path ORDERS = Path.of("..", "..", "spaces", "_templates", "orders-starter").toAbsolutePath().normalize();

    private static PipelineConfig cfg(Path dir, boolean on) throws Exception {
        Path toon = Files.createDirectories(dir).resolve("p.toon");
        StringBuilder dirs = new StringBuilder();
        for (String d : new String[]{"poll", "database", "backup", "temp", "errors", "quarantine", "markers", "status_dir", "log_dir"})
            dirs.append("  ").append(d).append(": ").append(dir.resolve("data").resolve(d).toString().replace('\\', '/')).append('\n');
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
            if (!on) { knownGap = pc; knownGapSpace = space; knownGapSource = Files.readAllBytes(f); }
        }
        // INGEST-FAILURE-TEXT-QUOTES-VALUE-1 (fixed 2026-10-03, never store values): the transform-failure text — DuckDB
        // quotes the offending cell — reaches the batch ledger and the retry record with the value FINGERPRINTED.
        StringBuilder kept = new StringBuilder(Files.readString(Path.of(knownGap.dirs().batchesFilePath())));
        Path retries = Path.of(knownGap.dirs().statusFilePath()).getParent().resolve("retries");
        assertTrue(Files.isDirectory(retries), "the failed batch left a retry record");
        try (Stream<Path> r = Files.list(retries)) { for (Path p : r.toList()) kept.append(Files.readString(p)); }
        assertFalse(kept.toString().contains("SECRETVAL"), "no value at rest: " + kept);
        assertFalse(kept.toString().contains("4111111111111111"), kept.toString());
        assertTrue(kept.toString().contains("fp:"), "the value's fingerprint is kept instead: " + kept);
        assertTrue(kept.toString().contains("Could not convert"), "the reason survives: " + kept);
        // And nothing anywhere under the Space but the source file itself (inbox) holds it.
        SpaceScan.assertNoValueOutsideSources(knownGapSpace, knownGapSource, "SECRETVAL", "4111111111111111");
    }

    private static Path knownGapSpace;
    private static byte[] knownGapSource;

    private static PipelineConfig knownGap;

    @Test
    void aRefusalIsAuditedWithTheCodeAloneAndTheFileMovedUnderAGeneratedName(@TempDir Path dir) throws Exception {
        PipelineConfig on = cfg(dir, true);
        Path in = Files.createDirectories(dir.resolve("data").resolve("poll"));
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
        // The full number and the original name, not a "4111" prefix: the event carries random SHA-256 hex
        // (audit_hash / audit_prev_hash) that contains any given 4 digits in ~0.2% of runs.
        assertFalse(audit.toString().contains("4111111111111111"), "the audit event names the code, not the value: " + audit);
        assertFalse(audit.toString().contains("secret_"), "nor the original file name: " + audit);
    }

    @Test
    void theNameCheckWantsACardShapedLuhnValidRun() {
        assertTrue(RefusalQuarantine.CardNumbers.containsCandidate("x_4111-1111-1111-1111.csv"));
        assertFalse(RefusalQuarantine.CardNumbers.containsCandidate("PAYMENT_ATTEMPTS_20260704.csv"));
        assertFalse(RefusalQuarantine.CardNumbers.containsCandidate("x_4111111111111112.csv"), "Luhn-invalid");
        assertFalse(RefusalQuarantine.CardNumbers.containsCandidate("x_1751328000123.csv"), "no card IIN");
    }

    /**
     * Round 5: the restricted store is {@code <data root>/.restricted/<pipeline>/}, OUTSIDE every allowlisted ingest dir.
     * The round-3 repro ({@code dirs.quarantine} = the data root) cannot place it outside the allowlist, so the refusal
     * fails closed instead of moving the file where a mapping could read it; the store stays jailed either way.
     */
    @Test
    void theRestrictedStoreSitsOutsideTheSealAllowlistOrRefuses(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("space");
        PipelineConfig on = cfg(root, true);
        Path data = root.resolve("data").toAbsolutePath().normalize();
        assertEquals(data.resolve(".restricted").resolve("p"), RefusalQuarantine.dir(on));
        assertNull(RefusalQuarantine.readableBySeal(on), "outside every allowlisted dir");
        for (Path allowed : ConsignmentIngestStrategy.ingestAllowedDirs(on, null))
            assertFalse(RefusalQuarantine.dir(on).startsWith(allowed), allowed.toString());
        Path toon = root.resolve("p.toon");
        Files.writeString(toon, Files.readString(toon).replaceAll("  quarantine: .*\n",
                "  quarantine: " + data.toString().replace('\\', '/') + "\n"));
        PipelineConfig atRoot = PipelineConfig.load(toon.toString());
        assertTrue(RefusalQuarantine.dir(atRoot).startsWith(data), "jailed");
        assertNotNull(RefusalQuarantine.readableBySeal(atRoot), "an allowlisted data root would expose it");
        Path f = Files.writeString(Files.createDirectories(data.resolve("poll")).resolve("x.csv"), "A\nx\n");
        var sel = new com.gamma.etl.SchemaSelector.Selection(atRoot.schemas().single(), null);
        assertThrows(java.io.IOException.class, () -> RefusalQuarantine.restrict(
                new com.gamma.etl.Consignment.Member(f.toFile(), 0, 4, sel), atRoot, RefusalQuarantine.CARD, "b", java.time.LocalDateTime.now()));
        assertTrue(Files.exists(f), "fail closed: the file was not moved where the seal could read it");
        assertNotNull(com.gamma.config.safety.PathJail.readAllowlistRefusal(data.resolve(".restricted").resolve("p")));
        Files.createDirectories(data.resolve(".restricted"));
        assertNotNull(com.gamma.config.safety.PathJail.readAllowlistRefusal(data), "a dir holding a restricted store");
    }

    /**
     * Round 5 repro: a Pipeline whose mapping expression READS the restricted store. The sealed ingest connection
     * refuses it — the batch fails, and no landed row carries the restricted file's content.
     */
    @Test
    void aMappingExpressionCannotReadTheRestrictedStore(@TempDir Path tmp) throws Exception {
        Path space = tmp.resolve("spaces").resolve("pay");
        copy(PAY, space);
        Path toonP = space.resolve("config/payment_attempts/payment_attempts_pipeline.toon");
        PipelineConfig pc = PipelineConfig.load(toonP.toString());
        Path inbox = Files.createDirectories(Path.of(pc.dirs().poll()));
        String header = "ATTEMPT_ID,ATTEMPT_TS,ATTEMPT_DATE,ACCOUNT_ID,INSTRUMENT_TOKEN,BIN,DEVICE_ID,MERCHANT_ID,AMOUNT,CURRENCY,OUTCOME\n";
        Files.writeString(inbox.resolve("PAYMENT_ATTEMPTS_20260704.csv"), header
                + "pa_x1,2026-07-04 10:00:00,2026-07-04,acc_x,4111111111111111,402400,dev_x,m_01,10.00,EUR,APPROVED\n");
        CollectorProcessor.run(pc);
        Path store = RefusalQuarantine.dir(pc);
        try (Stream<Path> r = Files.list(store)) { assertEquals(1, r.count(), "the premise: one file is restricted"); }

        Path schema = space.resolve("config/payment_attempts/payment_attempts_schema.toon");
        String glob = store.toString().replace('\\', '/') + "/*";
        Files.writeString(schema, Files.readString(schema).replace("    - name: MERCHANT_ID\n      from: MERCHANT_ID\n      fn: keep",
                "    - name: MERCHANT_ID\n      from: \"\"\n      fn: custom\n      args:\n        expression: \"(SELECT string_agg(content, '') FROM read_text('"
                        + glob + "'))\""));
        PipelineConfig reader = PipelineConfig.load(toonP.toString());
        Files.writeString(inbox.resolve("PAYMENT_ATTEMPTS_20260705.csv"), header
                + "pa_y1,2026-07-05 10:00:00,2026-07-05,acc_y,tok_y,402400,dev_y,m_01,10.00,EUR,APPROVED\n");
        CollectorProcessor.run(reader);
        assertTrue(Files.exists(inbox.resolve("PAYMENT_ATTEMPTS_20260705.csv")), "the batch failed: the file stays");
        String batches = Files.readString(Path.of(reader.dirs().batchesFilePath()));
        assertTrue(batches.contains("FAILED"), batches);
        assertTrue(batches.toLowerCase(java.util.Locale.ROOT).contains("permission")
                || batches.toLowerCase(java.util.Locale.ROOT).contains("disabled"), "a permission error: " + batches);
        Path db = Path.of(reader.dirs().database());
        if (Files.exists(db)) try (Stream<Path> w = Files.walk(db)) {
            for (Path f : w.filter(Files::isRegularFile).toList())
                assertFalse(new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1).contains("4111111111111111"), f.toString());
        }
    }

    /** Round 5: the fail-closed branch of {@code scanFile} — the raw view cannot even be built. */
    @Test
    void aScanWhoseRawViewCannotBeBuiltRestrictsTheFile(@TempDir Path tmp) throws Exception {
        Path space = tmp.resolve("spaces").resolve("pay");
        copy(PAY, space);
        PipelineConfig pc = PipelineConfig.load(space.resolve("config/payment_attempts/payment_attempts_pipeline.toon").toString());
        Path inbox = Files.createDirectories(Path.of(pc.dirs().poll()));
        Path f = Files.writeString(inbox.resolve("PAYMENT_ATTEMPTS_20260704.csv"),
                "ATTEMPT_ID,ATTEMPT_TS,ATTEMPT_DATE,ACCOUNT_ID,INSTRUMENT_TOKEN,BIN,DEVICE_ID,MERCHANT_ID,AMOUNT,CURRENCY,OUTCOME\n"
                        + "pa_x1,2026-07-04 10:00:00,2026-07-04,acc_x,tok_x,402400,dev_x,m_01,10.00,EUR,APPROVED\n");
        var sel = new com.gamma.etl.SchemaSelector.Selection(pc.schemas().single(), null);
        com.gamma.util.DuckDbUtil.loadDriver();
        try (var conn = java.sql.DriverManager.getConnection("jdbc:duckdb:")) {
            // The view cannot be built over a file that does not exist: the scan cannot prove it clean.
            assertEquals(RefusalQuarantine.SCAN_FAILED, RefusalQuarantine.scanFile(conn,
                    inbox.resolve("gone.csv").toFile(), pc.schemas().single(), pc, 0));
            assertNull(RefusalQuarantine.scanFile(conn, f.toFile(), pc.schemas().single(), pc, 0), "a clean file scans clean");
        }
        MemberAudit a = RefusalQuarantine.restrict(new com.gamma.etl.Consignment.Member(f.toFile(), 0, 4, sel), pc,
                RefusalQuarantine.SCAN_FAILED, "b", java.time.LocalDateTime.now());
        assertEquals(com.gamma.etl.MemberStatus.QUARANTINED_RESTRICTED, a.status());
        assertEquals(RefusalQuarantine.SCAN_FAILED, a.error());
        try (Stream<Path> r = Files.list(RefusalQuarantine.dir(pc))) { assertEquals(1, r.count()); }
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
        // No IBAN skip since round 4 (a PAN dressed as an IBAN evaded it): IBAN columns are exempted by the operator.
        assertTrue(rates.get("DE IBANs in 4-digit groups") < 0.06, "IBANs need refusal_scan_exempt: " + rates);
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
        Path f = Files.writeString(Files.createDirectories(dir.resolve("data").resolve("poll")).resolve("b.csv"), "A\nx\n");
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

    private static Path aged(Path dir, String name, int daysOld) throws Exception {
        Path f = Files.writeString(Files.createDirectories(dir).resolve(name), "4111111111111111,secret-row\n");
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - daysOld * 86_400_000L));
        return f;
    }

    /** Operator 2026-10-04 (PCI): retention is MANDATORY — unset = 7 days; past it the file is deleted and audited. */
    @Test
    void anExpiredRestrictedFileIsDeletedAndAuditedWithoutItsContent(@TempDir Path dir) throws Exception {
        PipelineConfig on = cfg(dir, true);
        assertEquals(7, on.refusal().retentionDays(), "unset = the mandatory 7-day default");
        Path store = RefusalQuarantine.dir(on);
        Path expired = aged(store, "refused-CARD_NUMBER-1-1.csv", 8);
        Path fresh = aged(store, "refused-CARD_NUMBER-2-2.csv", 6);
        String sha = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(expired)));
        long size = Files.size(expired);
        List<Event> seen = new ArrayList<>();
        BiConsumer<EventLog, Event> tap = (log, e) -> seen.add(e);
        EventLog.addTap(tap);
        try {
            // the poll cycle's housekeeping runs the sweep — no new refusal, nothing in the inbox
            CollectorProcessor.ingest(on, null);
        } finally {
            EventLog.removeTap(tap);
        }
        assertFalse(Files.exists(expired), "past the 7-day retention: deleted");
        assertTrue(Files.exists(fresh), "inside the retention window: kept");
        List<Event> gone = seen.stream().filter(e -> "ingest.refused.retention".equals(e.attributes().get("action"))).toList();
        assertEquals(1, gone.size(), "exactly the expired file is audited: " + gone);
        Map<String, String> a = gone.get(0).attributes();
        assertEquals("refused-CARD_NUMBER-1-1.csv", a.get("stored_as"));
        assertEquals("INGEST_REFUSE:CARD_NUMBER", a.get("reason"));
        assertEquals(String.valueOf(size), a.get("size"));
        assertEquals(sha, a.get("sha256"));
        assertEquals("7", a.get("retention_days"));
        assertFalse(gone.get(0).toString().contains("4111111111111111"), "never the content: " + gone.get(0));
        assertEquals(0, RefusalQuarantine.sweepExpired(on), "idempotent: a second sweep deletes nothing more");
    }

    /** The sweep is jailed to the Pipeline's own store: nothing outside it (or not generated by it) is ever deleted. */
    @Test
    void theSweepNeverDeletesAFileOutsideTheRestrictedStore(@TempDir Path dir) throws Exception {
        PipelineConfig on = cfg(dir, true);
        Path store = RefusalQuarantine.dir(on);
        Path data = dir.resolve("data");
        Path outside = aged(data.resolve("errors"), "refused-CARD_NUMBER-1-5.csv", 60);
        List<Path> keep = List.of(outside,
                aged(store.resolveSibling("other"), "refused-CARD_NUMBER-1-1.csv", 60),   // another Pipeline's store
                aged(store.resolve("nested"), "refused-CARD_NUMBER-1-2.csv", 60),        // a sub-directory
                aged(data.resolve("quarantine"), "refused-CARD_NUMBER-1-3.csv", 60),     // the ordinary quarantine
                aged(data.resolve("backup"), "refused-CARD_NUMBER-1-4.csv", 60),
                aged(store, "operator-notes.txt", 60));                                   // a name it never generated
        try {
            Files.createSymbolicLink(store.resolve("refused-CARD_NUMBER-1-6.csv"), outside);
        } catch (UnsupportedOperationException | java.io.IOException noSymlinkPrivilege) {
            // Windows without the symlink privilege: the link case cannot be planted; the rest still holds
        }
        Path expired = aged(store, "refused-CARD_NUMBER-9-9.csv", 60);
        assertEquals(1, RefusalQuarantine.sweepExpired(on));
        assertFalse(Files.exists(expired));
        for (Path p : keep) assertTrue(Files.exists(p), "never deleted: " + p);
    }

    /** The window is mandatory and capped: 0 and 31 are refused at load (the control plane refuses them 422). */
    @Test
    void aRetentionOutsideOneToThirtyIsRefusedAtLoad(@TempDir Path dir) throws Exception {
        cfg(dir, true);
        Path toon = dir.resolve("p.toon");
        String base = Files.readString(toon);
        for (int bad : new int[]{0, 31}) {
            Files.writeString(toon, base.replace("  refusal: restricted_quarantine\n",
                    "  refusal: restricted_quarantine\n  refusal_retention_days: " + bad + "\n"));
            Exception e = assertThrows(Exception.class, () -> PipelineConfig.load(toon.toString()));
            assertTrue(String.valueOf(e).contains("refusal_retention_days"), e.toString());
        }
        Files.writeString(toon, base.replace("  refusal: restricted_quarantine\n",
                "  refusal: restricted_quarantine\n  refusal_retention_days: 30\n"));
        assertEquals(30, PipelineConfig.load(toon.toString()).refusal().retentionDays());
    }

    private static final Path PAY =Path.of("..", "..", "spaces", "_templates", "payment-fraud").toAbsolutePath().normalize();

    /** Round 4: an exempt list that covers EVERY raw field would switch the scan off silently — refused at load. */
    @Test
    void exemptingEveryRawFieldIsRefusedAtLoad(@TempDir Path tmp) throws Exception {
        Path space = tmp.resolve("spaces").resolve("pay");
        copy(PAY, space);
        Path toon = space.resolve("config/payment_attempts/payment_attempts_pipeline.toon");
        String all = "ATTEMPT_ID,ATTEMPT_TS,ATTEMPT_DATE,ACCOUNT_ID,INSTRUMENT_TOKEN,BIN,DEVICE_ID,MERCHANT_ID,AMOUNT,CURRENCY,OUTCOME";
        Files.writeString(toon, Files.readString(toon).replace("  refusal_scan: card_number\n",
                "  refusal_scan: card_number\n  refusal_scan_exempt[11]: " + all + "\n"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PipelineConfig.load(toon.toString()));
        assertTrue(e.getMessage().contains("exempts every raw field"), e.getMessage());
        // One field short of all is allowed (the operator's call), and still scans that one field.
        Files.writeString(toon, Files.readString(toon).replace("refusal_scan_exempt[11]: " + all,
                "refusal_scan_exempt[10]: " + all.replace(",OUTCOME", "")));
        assertEquals(10, PipelineConfig.load(toon.toString()).refusal().scanExempt().size());
    }

    /** Round 4: the scan FAILS CLOSED — an error while scanning restricts the file; nothing lands. */
    @Test
    void aScanFailureRestrictsTheFileAndLandsNothing(@TempDir Path tmp) throws Exception {
        Path space = tmp.resolve("spaces").resolve("pay");
        copy(PAY, space);
        PipelineConfig pc = PipelineConfig.load(space.resolve("config/payment_attempts/payment_attempts_pipeline.toon").toString());
        Path inbox = Files.createDirectories(Path.of(pc.dirs().poll()));
        Files.writeString(inbox.resolve("PAYMENT_ATTEMPTS_20260704.csv"),
                "ATTEMPT_ID,ATTEMPT_TS,ATTEMPT_DATE,ACCOUNT_ID,INSTRUMENT_TOKEN,BIN,DEVICE_ID,MERCHANT_ID,AMOUNT,CURRENCY,OUTCOME\n"
                        + "pa_x1,2026-07-04 10:00:00,2026-07-04,acc_x,tok_x,402400,dev_x,m_01,10.00,EUR,APPROVED\n");
        RefusalQuarantine.scanSqlSeam = sql -> "SELECT * FROM no_such_relation_for_the_scan";
        try {
            CollectorProcessor.run(pc);
        } finally {
            RefusalQuarantine.scanSqlSeam = sql -> sql;
        }
        try (Stream<Path> db = Files.exists(Path.of(pc.dirs().database())) ? Files.walk(Path.of(pc.dirs().database())) : Stream.empty()) {
            assertEquals(0, db.filter(Files::isRegularFile).count(), "no row of an unscanned file lands");
        }
        try (Stream<Path> r = Files.list(RefusalQuarantine.dir(pc))) { assertEquals(1, r.count()); }
        assertTrue(Files.readString(Path.of(pc.dirs().statusFilePath())).contains(RefusalQuarantine.SCAN_FAILED));
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

    /**
     * Round 6: a nested data/ fallback. Pipeline A's quarantine is under {@code data/p/data/}, so its store is
     * {@code data/p/data/.restricted/a}; a second Pipeline B allowlisting {@code data/p} (the grandparent) is refused.
     */
    @Test
    void aSecondPipelineAllowlistingTheGrandparentOfANestedStoreIsRefused(@TempDir Path tmp) throws Exception {
        Path outer = tmp.resolve("space/data/p");
        PipelineConfig a = cfg(outer, true);   // dirs under <outer>/data/...
        Path poll = Files.createDirectories(Path.of(a.dirs().poll()));
        Path f = Files.writeString(poll.resolve("x.csv"), "A\nx\n");
        var sel = new com.gamma.etl.SchemaSelector.Selection(a.schemas().single(), null);
        RefusalQuarantine.restrict(new com.gamma.etl.Consignment.Member(f.toFile(), 0, 4, sel), a, RefusalQuarantine.CARD, "b",
                java.time.LocalDateTime.now());
        assertTrue(RefusalQuarantine.dir(a).startsWith(outer.toAbsolutePath().normalize().resolve("data").resolve(".restricted")));
        assertNotNull(com.gamma.config.safety.PathJail.readAllowlistRefusal(outer), "B's allowlisted grandparent holds it");
    }
}
