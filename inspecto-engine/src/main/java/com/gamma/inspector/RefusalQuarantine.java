package com.gamma.inspector;

import com.gamma.etl.Consignment;
import com.gamma.etl.MemberStatus;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in content refusal ({@code processing.refusal: restricted_quarantine}, default off).
 *
 * <p><b>Trigger.</b> Only an AUTHOR-raised refusal: a DuckDB {@code error()} whose whole message is a reason code
 * matching {@link #CODE} — the exception is a {@link SQLException} whose message is EXACTLY
 * {@code "Invalid Input Error: INGEST_REFUSE:<CODE>"}. A conversion/cast error, or a code appearing anywhere else in
 * a message or cause chain, never triggers it, so data content cannot (a cast error quotes the value but is a
 * {@code Conversion Error}). Opt-in pipelines may also refuse a file whose NAME or HEADER line carries a card-number
 * candidate ({@link #CARD_IN_NAME} / {@link #CARD_IN_HEADER}).
 *
 * <p><b>Effect.</b> The file moves to {@code <quarantine>/../restricted-quarantine/} under a GENERATED name (the
 * original name may itself be the sensitive value). That directory is never polled (it is not the inbox), is skipped
 * by {@code BackupTask}, and is not the quarantine tree the run routes list. Nothing of the file is written; only the
 * reason code is recorded — in the member's status row, the WARN log and one AUDIT event — never exception text, a
 * value or the original name. An optional {@code refusal_retention_days} ages restricted files out.
 */
final class RefusalQuarantine {

    private static final Logger log = LoggerFactory.getLogger(RefusalQuarantine.class);

    static final Pattern CODE = Pattern.compile("INGEST_REFUSE:[A-Z][A-Z_]{0,63}");
    static final String DIR = "restricted-quarantine";
    static final String CARD_IN_NAME = "INGEST_REFUSE:CARD_NUMBER_IN_FILE_NAME";
    static final String CARD_IN_HEADER = "INGEST_REFUSE:CARD_NUMBER_IN_HEADER";
    private static final String DUCKDB_PREFIX = "Invalid Input Error: ";
    private static final AtomicLong SEQ = new AtomicLong();

    private RefusalQuarantine() {}

    /** The reason code iff {@code cfg} opts in and the FIRST {@link SQLException} in {@code e}'s chain is exactly one. */
    static String reasonCode(PipelineConfig cfg, Throwable e) {
        if (!cfg.refusal().restricted()) return null;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException) {
                String m = t.getMessage();
                if (m == null || !m.startsWith(DUCKDB_PREFIX)) return null;
                String code = m.substring(DUCKDB_PREFIX.length());
                return CODE.matcher(code).matches() ? code : null;
            }
        }
        return null;
    }

    /**
     * Probe ONE member's transform in isolation (multi-member and Java lanes): materialise {@code source} into a
     * throwaway table and report the reason code if the author's mapping refused it; any other failure returns
     * {@code null} and is left to the real pass, which fails exactly as before.
     */
    static String probe(Connection conn, Map<String, Object> schema, PipelineConfig cfg, String source) {
        if (!cfg.refusal().restricted()) return null;
        try {
            com.gamma.etl.DataTransformer.materialize(conn, schema, cfg, source, "__refusal_probe");
            return null;
        } catch (Exception e) {
            return reasonCode(cfg, e);
        } finally {
            ConsignmentIngestStrategy.dropTable(conn, "__refusal_probe");
        }
    }

    /** {@link #CARD_IN_NAME} / {@link #CARD_IN_HEADER} when the opted-in file's name or first line carries a card number. */
    static String nameOrHeaderRefusal(PipelineConfig cfg, File f) {
        if (!cfg.refusal().restricted()) return null;
        if (CardNumbers.containsCandidate(f.getName())) return CARD_IN_NAME;
        try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            char[] buf = new char[65_536];
            int n = r.read(buf);
            if (n <= 0) return null;
            String head = new String(buf, 0, n);
            int nl = head.indexOf('\n');
            if (CardNumbers.containsCandidate(nl < 0 ? head : head.substring(0, nl))) return CARD_IN_HEADER;
        } catch (IOException | RuntimeException unreadable) {
            return null;   // an unreadable file is the ingest's to classify, not this check's
        }
        return null;
    }

    /** Move {@code m}'s file to the restricted quarantine, audit it, and return its member audit row. */
    static MemberAudit restrict(Consignment.Member m, PipelineConfig cfg, String code, String batchId, LocalDateTime start)
            throws IOException {
        Path dir = dir(cfg);
        Files.createDirectories(dir);
        String name = f(m.file().getName());
        Path target = dir.resolve(name).normalize();
        if (!target.startsWith(dir)) throw new IOException("restricted quarantine target escapes its directory");
        Files.move(m.file().toPath(), target);
        age(dir, cfg.refusal().retentionDays());
        log.warn("[INGEST] [{}] refused {} → restricted quarantine as {}", cfg.identity().pipelineName(), code, name);
        try {
            EventLog.current().emit(Event.builder(EventType.AUDIT).source("audit")
                    .message("Pipeline '" + cfg.identity().pipelineName() + "' refused a file: " + code)
                    .action("ingest.refused").actionCategory("security")
                    .attr("pipeline", cfg.identity().pipelineName()).attr("reason", code)
                    .attr("consignment", batchId).attr("stored_as", name));
        } catch (RuntimeException auditDown) {
            log.warn("[INGEST] refusal audit event not recorded: {}", auditDown.getClass().getSimpleName());
        }
        return MemberAudit.refused(m, name, code, start);
    }

    /** {@code <quarantine dir>/../restricted-quarantine}. */
    static Path dir(PipelineConfig cfg) {
        return Path.of(cfg.dirs().quarantine()).toAbsolutePath().normalize().resolveSibling(DIR);
    }

    /** A generated name keeping only the extension — the original name may be the sensitive value. */
    private static String f(String original) {
        int dot = original.lastIndexOf('.');
        String ext = dot > 0 && original.length() - dot <= 8 && original.substring(dot).matches("\\.[A-Za-z0-9]+")
                ? original.substring(dot) : "";
        return "refused-" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet() + ext;
    }

    private static void age(Path dir, Integer days) {
        if (days == null) return;
        FileTime cutoff = FileTime.from(Instant.now().minus(days, ChronoUnit.DAYS));
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path p : files)
                if (Files.isRegularFile(p) && Files.getLastModifiedTime(p).compareTo(cutoff) < 0) Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("[INGEST] restricted quarantine retention sweep failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * The platform's card-number candidate test for NAMES and HEADERS: a Luhn-valid 13–19 digit run (separators
     * between digits allowed) that starts with a card IIN prefix. Content is judged by the Pipeline's own mapping.
     */
    static final class CardNumbers {
        private static final Pattern RUN = Pattern.compile("[0-9](?:[^0-9A-Za-z]{0,3}[0-9]){12,40}");
        private static final Pattern IIN = Pattern.compile("^(?:4|5[1-5]|2[2-7]|3[47]|6)");

        private CardNumbers() {}

        static boolean containsCandidate(String s) {
            Matcher m = RUN.matcher(s);
            while (m.find()) {
                String d = m.group().replaceAll("[^0-9]", "");
                for (int i = 0; i + 13 <= d.length(); i++)
                    for (int len = 13; len <= 19 && i + len <= d.length(); len++) {
                        String c = d.substring(i, i + len);
                        if (IIN.matcher(c).find() && luhn(c)) return true;
                    }
            }
            return false;
        }

        static boolean luhn(String d) {
            int sum = 0;
            for (int k = 0; k < d.length(); k++) {
                int v = d.charAt(d.length() - 1 - k) - '0';
                if (k % 2 == 1) { v *= 2; if (v > 9) v -= 9; }
                sum += v;
            }
            return sum % 10 == 0;
        }
    }

    /** {@link MemberStatus#QUARANTINED_RESTRICTED} is this class's status. */
    static final MemberStatus STATUS = MemberStatus.QUARANTINED_RESTRICTED;
}
