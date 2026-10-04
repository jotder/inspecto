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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in content refusal ({@code processing.refusal: restricted_quarantine}, default off).
 *
 * <p><b>Triggers.</b> (1) An AUTHOR-raised refusal: the FIRST {@link SQLException} of a transform failure whose
 * message is EXACTLY {@code "Invalid Input Error: INGEST_REFUSE:<CODE>"} ({@link #CODE}) — a conversion/cast error or
 * a code anywhere else never triggers it, so data content cannot. (2) {@code processing.refusal_scan: card_number}:
 * the platform scans every raw cell except {@code refusal_scan_exempt} for a card-shaped number ({@link #cardScanSql}).
 * (3) With the mode on, a file NAME or HEADER line carrying a card-shaped number ({@link #CARD_IN_NAME} /
 * {@link #CARD_IN_HEADER}).
 *
 * <p><b>Effect.</b> The file moves to {@code <data root>/.restricted/<pipeline>/} ({@link #dir}) — outside every
 * directory the sealed ingest/enrichment connections allowlist — under a generated name carrying the reason code. It is never polled,
 * {@code BackupTask} skips {@code .restricted}, and the quarantine listing and errors-file routes skip it. Only the
 * reason code is recorded — the member's status row, one WARN line, one AUDIT event {@code ingest.refused}.
 *
 * <p><b>Retention (mandatory, operator 2026-10-04).</b> A restricted file is DELETED once older than
 * {@code refusal_retention_days} (default 7, 1..30) by {@link #sweepExpired}, run with each poll cycle's housekeeping
 * and after each refusal; each deletion is audited ({@code ingest.refused.retention}: stored name, size, sha256,
 * reason code, retention — never content).
 */
final class RefusalQuarantine {

    private static final Logger log = LoggerFactory.getLogger(RefusalQuarantine.class);

    static final Pattern CODE = Pattern.compile("INGEST_REFUSE:[A-Z][A-Z_]{0,63}");
    /** The restricted directory's name inside {@code dirs.quarantine}; restated by {@code BackupTask} and the routes. */
    static final String DIR = ".restricted";
    static final String CARD = "INGEST_REFUSE:CARD_NUMBER";
    static final String CARD_IN_NAME = "INGEST_REFUSE:CARD_NUMBER_IN_FILE_NAME";
    static final String CARD_IN_HEADER = "INGEST_REFUSE:CARD_NUMBER_IN_HEADER";
    /** The scan could not run — FAIL CLOSED: the file is restricted, never landed (a scan that errors proves nothing). */
    static final String SCAN_FAILED = "INGEST_REFUSE:SCAN_FAILED";
    /** Test seam only: rewrites the scan SQL just before it runs (e.g. to inject a failure). Identity in production. */
    static volatile java.util.function.UnaryOperator<String> scanSqlSeam = sql -> sql;
    private static final String DUCKDB_PREFIX = "Invalid Input Error: ";
    private static final AtomicLong SEQ = new AtomicLong();

    private RefusalQuarantine() {}

    // ── triggers ───────────────────────────────────────────────────────────────────────────────────────────────

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
     * Judge one relation (a member's raw view or table) before its rows are used: the platform card scan first, then
     * the author's mapping in isolation. {@code null} = not refused (any other failure is left to the real pass).
     */
    static String judge(Connection conn, Map<String, Object> schema, PipelineConfig cfg, String relation) {
        if (!cfg.refusal().restricted()) return null;
        String scanned = scan(conn, schema, cfg, relation);
        if (scanned != null) return scanned;
        try {
            com.gamma.etl.DataTransformer.materialize(conn, schema, cfg, relation, "__refusal_probe");
            return null;
        } catch (Exception e) {
            return reasonCode(cfg, e);
        } finally {
            ConsignmentIngestStrategy.dropTable(conn, "__refusal_probe");
        }
    }

    /** The platform card scan of one FILE (single-member and chunked lanes): a raw view over it, scanned, dropped. */
    static String scanFile(Connection conn, File f, Map<String, Object> schema, PipelineConfig cfg, int srcId) {
        if (!cfg.refusal().restricted() || !cfg.refusal().cardScan()) return null;
        try {
            com.gamma.etl.DuckDbCsvIngester.createRawInputView(f, conn, schema, cfg, "__refusal_scan", srcId);
            return scan(conn, schema, cfg, "__refusal_scan");
        } catch (Exception cannotScan) {
            // FAIL CLOSED: a file the scan cannot read is not proven clean. Restricting it (rather than letting the
            // lane quarantine it as UNREADABLE) keeps any raw copy out of the ordinary quarantine tree too.
            return SCAN_FAILED;
        } finally {
            ConsignmentIngestStrategy.dropView(conn, "__refusal_scan");
        }
    }

    private static String scan(Connection conn, Map<String, Object> schema, PipelineConfig cfg, String relation) {
        if (!cfg.refusal().cardScan()) return null;
        String sql = cardScanSql(columns(schema, cfg.refusal().scanExempt()), relation);
        if (sql == null) return null;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(scanSqlSeam.apply(sql))) {
            return rs.next() ? CARD : null;
        } catch (SQLException | RuntimeException e) {
            // FAIL CLOSED (round-4 decision): restrict, do not fail the batch — a failed batch leaves the unscanned
            // file in the inbox, re-polled every cycle, and its error text would quote cell values into the ledger.
            log.warn("[INGEST] [{}] card scan failed ({}) — restricting the file", cfg.identity().pipelineName(),
                    e.getClass().getSimpleName());
            return SCAN_FAILED;
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
            return null;
        }
        return null;
    }

    // ── the card scan (SQL) ────────────────────────────────────────────────────────────────────────────────────

    /** A raw column the scan reads: its name and whether it is a DATE/TIMESTAMP (a well-formed value is skipped). */
    record Column(String name, boolean temporal) {}

    /** The schema's raw fields minus the exempt ones (case-insensitive); a name that is not a plain identifier is skipped. */
    static List<Column> columns(Map<String, Object> schema, List<String> exempt) {
        List<Column> out = new ArrayList<>();
        if (!(schema.get("raw") instanceof Map<?, ?> raw) || !(raw.get("fields") instanceof List<?> fields)) return out;
        java.util.Set<String> skip = new java.util.HashSet<>();
        for (String x : exempt) skip.add(x.toUpperCase(Locale.ROOT));
        for (Object f : fields)
            if (f instanceof Map<?, ?> m && m.get("name") != null) {
                String name = String.valueOf(m.get("name"));
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || skip.contains(name.toUpperCase(Locale.ROOT))) continue;
                String type = String.valueOf(m.get("type")).toUpperCase(Locale.ROOT);
                out.add(new Column(name, type.startsWith("DATE") || type.startsWith("TIMESTAMP")));
            }
        return out;
    }

    /** Unicode Nd digit blocks (zero code point of each run of ten) folded to ASCII before the scan. */
    static final int[] DIGIT_ZEROS = {0x0660, 0x06F0, 0x07C0, 0x0966, 0x09E6, 0x0A66, 0x0AE6, 0x0B66, 0x0BE6, 0x0C66,
            0x0CE6, 0x0D66, 0x0DE6, 0x0E50, 0x0ED0, 0x0F20, 0x1040, 0x1090, 0x17E0, 0x1810, 0x1946, 0x19D0, 0x1A80,
            0x1A90, 0x1B50, 0x1BB0, 0x1C40, 0x1C50, 0xA620, 0xA8D0, 0xA900, 0xA9D0, 0xA9F0, 0xAA50, 0xABF0, 0xFF10,
            0x1D7CE, 0x1D7D8, 0x1D7E2, 0x1D7EC, 0x1D7F6};

    /**
     * {@code SELECT 1 FROM relation WHERE <some cell carries a card-shaped number> LIMIT 1}, or {@code null} with no
     * column to scan. Per cell: marks (Mn, Me) and format characters (Cf) stripped, Nd digits folded, a well-formed
     * date/timestamp skipped; then runs of digit groups joined by 1–5 non-alphanumerics,
     * split into groups; a candidate is one group of 13–19 digits or a 4-4-4-4 / 4-4-4-4-3 / 4-6-5 / 4-6-4 window —
     * accepted when {@link CardNumbers#IIN_REGEX} matches it (brand prefix AND length) and it is Luhn-valid. Linear:
     * RE2 regexes, windows of at most 5 groups, a group longer than 19 digits is no candidate.
     */
    static String cardScanSql(List<Column> cols, String relation) {
        if (cols.isEmpty()) return null;
        StringBuilder uni = new StringBuilder(), asc = new StringBuilder();
        for (int z : DIGIT_ZEROS) for (int i = 0; i < 10; i++) { uni.appendCodePoint(z + i); asc.append((char) ('0' + i)); }
        List<String> cells = new ArrayList<>();
        for (Column c : cols) {
            String n = "translate(regexp_replace(CAST(\"" + c.name() + "\" AS VARCHAR), '[\\p{Mn}\\p{Me}\\p{Cf}]', '', 'g'), '"
                    + uni + "', '" + asc + "')";
            // No IBAN skip (round-4 decision): "DE00 4111 1111 1111 1111" is a card number dressed as an IBAN.
            // An operator exempts a known IBAN column with refusal_scan_exempt instead.
            cells.add(c.temporal() ? "CASE WHEN regexp_full_match(" + n
                    + ", '[0-9]{4}-[0-9]{2}-[0-9]{2}( [0-9]{2}:[0-9]{2}:[0-9]{2})?') THEN NULL ELSE " + n + " END" : n);
        }
        String row = "concat_ws('X', " + String.join(", ", cells) + ")";
        String runs = "list_transform(regexp_extract_all(" + row + ", '[0-9]+(?:[^0-9A-Za-z]{1,5}[0-9]+)*'), "
                + "lambda r: regexp_split_to_array(r, '[^0-9]+'))";
        String shapes = String.join(", ",
                "CASE WHEN " + len(0) + " BETWEEN 13 AND 19 THEN g[i] END",
                "CASE WHEN " + lens(4, 4, 4, 4) + " THEN " + cat(4) + " END",
                "CASE WHEN " + lens(4, 4, 4, 4, 3) + " THEN " + cat(5) + " END",
                "CASE WHEN " + lens(4, 6, 5) + " THEN " + cat(3) + " END",
                "CASE WHEN " + lens(4, 6, 4) + " THEN " + cat(3) + " END");
        String cands = "flatten(list_transform(" + runs + ", lambda g: flatten(list_transform(range(1, len(g) + 1), "
                + "lambda i: [" + shapes + "]))))";
        String d = "CAST(substr(reverse(d), k, 1) AS INTEGER)";
        String luhn = "list_sum(list_transform(range(1, length(d) + 1), lambda k: CASE WHEN k % 2 = 0 THEN " + d
                + " * 2 - CASE WHEN " + d + " > 4 THEN 9 ELSE 0 END ELSE " + d + " END)) % 10 = 0";
        String card = "d IS NOT NULL AND regexp_full_match(d, '" + CardNumbers.IIN_REGEX + "') AND " + luhn;
        return "SELECT 1 FROM \"" + relation + "\" WHERE len(list_filter(" + cands + ", lambda d: " + card + ")) > 0 LIMIT 1";
    }

    private static String len(int k) { return "length(g[i + " + k + "])"; }

    private static String lens(int... sizes) {
        List<String> p = new ArrayList<>();
        for (int k = 0; k < sizes.length; k++) p.add(len(k) + " = " + sizes[k]);
        return String.join(" AND ", p);
    }

    private static String cat(int n) {
        List<String> p = new ArrayList<>();
        for (int k = 0; k < n; k++) p.add("g[i + " + k + "]");
        return String.join(" || ", p);
    }

    // ── effect ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Move {@code m}'s file to the restricted quarantine, audit it, and return its member audit row. */
    static MemberAudit restrict(Consignment.Member m, PipelineConfig cfg, String code, String batchId, LocalDateTime start)
            throws IOException {
        Path dir = dir(cfg);
        String exposed = readableBySeal(cfg);
        if (exposed != null)   // fail closed: never move a refused file where a mapping expression could read it
            throw new IOException("refusing to restrict: " + exposed + " — give the Pipeline data dirs below the data root");
        Files.createDirectories(dir);
        com.gamma.config.safety.PathJail.registerRestrictedStore(dir.getParent());
        String name = storedName(m.file().getName(), code);
        Path target = dir.resolve(name).normalize();
        if (!target.getParent().equals(dir)) throw new IOException("restricted quarantine target escapes its directory");
        Files.move(m.file().toPath(), target);
        sweepExpired(cfg);
        log.warn("[INGEST] [{}] refused {} → restricted quarantine as {}", cfg.identity().pipelineName(), code, name);
        audit(cfg, "ingest.refused", "Pipeline '" + cfg.identity().pipelineName() + "' refused a file: " + code,
                code, name, batchId);
        return MemberAudit.refused(m, name, code, start);
    }

    /** {@code <dirs.quarantine>/.restricted} — inside the Pipeline's own (jailed) quarantine directory. */
    static Path dir(PipelineConfig cfg) {
        Path q = Path.of(cfg.dirs().quarantine()).toAbsolutePath().normalize();
        Path root = dataRoot(q);
        String name = cfg.identity().pipelineName();
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,120}")) name = "p" + Integer.toHexString(String.valueOf(name).hashCode());
        Path d = root.resolve(DIR).resolve(name).normalize();
        if (!d.startsWith(root)) throw new IllegalStateException("restricted store escapes the data root");
        return d;
    }

    /**
     * The data root the restricted store lives directly under: the Space's registered data root when the Pipeline's
     * quarantine is inside it, else the nearest ancestor named {@code data} (the Space layout); with neither it FAILS
     * CLOSED. The store is {@code <root>/.restricted/<pipeline>/} — deliberately OUTSIDE every {@code dirs:} entry,
     * because the sealed ingest and enrichment connections allowlist those by PREFIX (DuckDB
     * {@code allowed_directories}), so a store under {@code dirs.quarantine} was readable by a mapping expression.
     */
    static Path dataRoot(Path quarantine) {
        Path reg = com.gamma.pipeline.SpaceConfigRoot.currentDataRoot();
        if (reg != null) {
            Path r = reg.toAbsolutePath().normalize();
            if (quarantine.startsWith(r)) return r;
        }
        for (Path p = quarantine; p != null; p = p.getParent())
            if (p.getFileName() != null && p.getFileName().toString().equals("data")) return p;
        // No data root is known: fail closed rather than guess a directory the jail may not cover.
        throw new IllegalStateException("no data root for the restricted store (the quarantine " + quarantine
                + " is under no registered Space data root and no data/ directory)");
    }

    /** Why the restricted store would be readable by this Pipeline's sealed ingest connection, or {@code null}. */
    static String readableBySeal(PipelineConfig cfg) {
        Path d = dir(cfg);
        for (Path allowed : ConsignmentIngestStrategy.ingestAllowedDirs(cfg, null))
            if (d.startsWith(allowed)) return "the restricted store " + d + " lies under the allowlisted " + allowed;
        return null;
    }

    /** {@code refused-<CODE suffix>-<ms>-<n><ext>} — the code, never the original name (it may be the value). */
    private static String storedName(String original, String code) {
        int dot = original.lastIndexOf('.');
        String ext = dot > 0 && original.length() - dot <= 8 && original.substring(dot).matches("\\.[A-Za-z0-9]+")
                ? original.substring(dot) : "";
        return "refused-" + code.substring("INGEST_REFUSE:".length()) + "-" + System.currentTimeMillis() + "-"
                + SEQ.incrementAndGet() + ext;
    }

    private static final Pattern STORED = Pattern.compile("refused-([A-Z][A-Z_]{0,63})-\\d+-\\d+.*");

    private static final long SWEEP_THROTTLE_MS = 60_000L;
    private static final Map<Path, Long> LAST_SWEEP = new java.util.concurrent.ConcurrentHashMap<>();

    /** The housekeeping entry (every poll cycle): {@link #sweepExpired} at most once a minute per store. Never throws. */
    static void sweep(PipelineConfig cfg) {
        if (!cfg.refusal().restricted()) return;
        try {
            long now = System.currentTimeMillis();
            Long prev = LAST_SWEEP.put(dir(cfg), now);
            if (prev != null && now - prev < SWEEP_THROTTLE_MS) return;
            sweepExpired(cfg);
        } catch (RuntimeException e) {
            log.warn("[INGEST] restricted quarantine retention sweep failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Mandatory retention (operator 2026-10-04, PCI): DELETE every restricted file older than
     * {@code refusal_retention_days} (default {@link PipelineConfig.Refusal#DEFAULT_RETENTION_DAYS}, at most
     * {@link PipelineConfig.Refusal#MAX_RETENTION_DAYS}). Path-jailed: only a regular file (never a link, never a
     * sub-directory) directly in this Pipeline's store whose real parent IS the store and whose name is one
     * {@link #storedName} generated. Crash-safe and idempotent: the audit event (stored name, size, sha256, reason
     * code, retention - never content) is emitted BEFORE the delete, so a crash can duplicate the record, never lose
     * it; a delete that fails leaves the file, retried by the next sweep. Returns the number deleted.
     */
    static int sweepExpired(PipelineConfig cfg) {
        if (!cfg.refusal().restricted()) return 0;
        Path dir = dir(cfg);
        if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return 0;
        int days = cfg.refusal().retentionDays();
        FileTime cutoff = FileTime.from(Instant.now().minus(days, ChronoUnit.DAYS));
        int n = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            Path store = dir.toRealPath();
            for (Path p : files) {
                try {
                    if (!Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!store.equals(p.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS).getParent())) continue;
                    Matcher mm = STORED.matcher(p.getFileName().toString());
                    if (!mm.matches()) continue;
                    if (Files.getLastModifiedTime(p, java.nio.file.LinkOption.NOFOLLOW_LINKS).compareTo(cutoff) >= 0) continue;
                    String code = "INGEST_REFUSE:" + mm.group(1);
                    audit(cfg, "ingest.refused.retention", "Pipeline '" + cfg.identity().pipelineName()
                            + "' deleted a restricted file past its " + days + "-day retention: " + code, code,
                            p.getFileName().toString(), null, Map.of("size", String.valueOf(Files.size(p)),
                                    "sha256", sha256(p), "retention_days", String.valueOf(days)));
                    if (Files.deleteIfExists(p)) n++;
                } catch (IOException e) {
                    log.warn("[INGEST] restricted file not deleted yet (retried next sweep): {}", e.getClass().getSimpleName());
                }
            }
        } catch (IOException e) {
            log.warn("[INGEST] restricted quarantine retention sweep failed: {}", e.getClass().getSimpleName());
        }
        return n;
    }

    private static String sha256(Path p) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65_536];
            for (int r; (r = in.read(buf)) > 0; ) md.update(buf, 0, r);
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void audit(PipelineConfig cfg, String action, String message, String code, String stored, String batchId) {
        audit(cfg, action, message, code, stored, batchId, Map.of());
    }

    private static void audit(PipelineConfig cfg, String action, String message, String code, String stored,
                              String batchId, Map<String, String> extra) {
        try {
            Event.Builder b = Event.builder(EventType.AUDIT).source("audit").message(message)
                    .action(action).actionCategory("security")
                    .attr("pipeline", cfg.identity().pipelineName()).attr("reason", code).attr("stored_as", stored);
            if (batchId != null) b.attr("consignment", batchId);
            extra.forEach(b::attr);
            EventLog.current().emit(b);
        } catch (RuntimeException auditDown) {
            log.warn("[INGEST] refusal audit event not recorded: {}", auditDown.getClass().getSimpleName());
        }
    }

    // ── names and headers (Java) ───────────────────────────────────────────────────────────────────────────────

    /** The card test for NAMES and HEADERS — the same brand/length list as the SQL scan ({@link #IIN_REGEX}). */
    static final class CardNumbers {
        /**
         * Brand prefix AND length, over the digits alone: Amex 34/37 (15); Diners 30/36/38/39 (14–19); JCB 35
         * (16–19); Visa 4 (13, 16, 19); 50–59 incl. Maestro (13–19); Mastercard 2-series 22–27 (16); 6 — Discover,
         * UnionPay, Maestro (13–19). Shared verbatim with the SQL scan.
         */
        static final String IIN_REGEX = "(3[47][0-9]{13}|3[0689][0-9]{12,17}|35[0-9]{14,17}|4[0-9]{12}|4[0-9]{15}"
                + "|4[0-9]{18}|5[0-9]{12,18}|2[2-7][0-9]{14}|6[0-9]{12,18})";
        private static final Pattern IIN = Pattern.compile(IIN_REGEX);
        private static final Pattern RUN = Pattern.compile("[0-9](?:[^0-9A-Za-z]{0,5}[0-9]){12,60}");

        private CardNumbers() {}

        static boolean containsCandidate(String s) {
            Matcher m = RUN.matcher(s);
            while (m.find()) {
                String d = m.group().replaceAll("[^0-9]", "");
                for (int i = 0; i + 13 <= d.length(); i++)
                    for (int len = 13; len <= 19 && i + len <= d.length(); len++) {
                        String c = d.substring(i, i + len);
                        if (IIN.matcher(c).matches() && luhn(c)) return true;
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
