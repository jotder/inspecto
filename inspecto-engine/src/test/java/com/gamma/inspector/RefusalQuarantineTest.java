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
        }
    }

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
