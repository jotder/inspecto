package com.gamma.etl;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Never store values</b> (operator decision 2026-10-03, {@code INGEST-REJECT-SIDECAR-RAW-PAN-1} +
 * {@code INGEST-FAILURE-TEXT-QUOTES-VALUE-1}): the one place an ingest failure is rendered for anything that reaches
 * disk — the reject sidecar, the status ledger, the retry record, the event, the log line.
 *
 * <p>A value is replaced by its {@linkplain #fingerprint FINGERPRINT}: an HMAC-SHA256 under a random per-Space salt,
 * truncated to 16 hex. The same value fingerprints the same within a Space (so two failures can be correlated), it
 * cannot be reversed, and a dictionary attack needs the salt, which never leaves the Space.
 *
 * <p>{@link #scrub} does not look for the value — it cannot know it. It strips every QUOTED literal ({@code '…'} and
 * {@code "…"}, SQL-doubled quotes included), any line a caret ({@code ^}) line points at (an unquoted echo, as
 * {@code strptime} gives) and the tail of DuckDB's CSV {@code Original Line:} context generically,
 * so any engine error shape that quotes the cell or echoes the line is covered. ⚠ The cost is that a quoted
 * identifier in an error (a column name in {@code "…"}) is fingerprinted too; unquoted context survives.
 */
public final class FailureText {

    private FailureText() {}

    /** Where the per-Space salt lives, relative to the Space root. */
    static final String SALT_FILE = ".inspecto/value-fingerprint.salt";

    /** GREEDY to the last same quote on the line: engines print a value containing a quote UNESCAPED, so the
     *  nearest closing quote is not the literal's end. Over-redacting a quoted type name is the price. */
    private static final Pattern QUOTED = Pattern.compile("'[^\n]*'|\"[^\n]*\"");
    /** An unterminated quote (a message truncated mid-literal): everything after it is the literal. */
    private static final Pattern UNTERMINATED = Pattern.compile("['\"].*", Pattern.DOTALL);
    private static final Pattern CONFIG_ERROR = Pattern.compile("(Binder|Catalog|Parser) Error:");
    private static final Pattern DATA_ERROR = Pattern.compile(
            "(?i)Conversion Error|Invalid Input Error|Out of Range Error|Constraint Error|original line|could not "
                    + "(convert|parse|cast)");
    private static final Pattern CARET_ECHO =Pattern.compile("(?m)^([^\\n]*)(\\r?\\n[ \\t]*\\^[ \\t]*)$");
    private static final Pattern ORIGINAL_LINE =Pattern.compile("(?im)(original line\\s*:)[^\\n]*");

    private static final Map<Path, byte[]> SALTS = new ConcurrentHashMap<>();

    /** A safe, non-null rendering of a failure: its scrubbed message, else its simple class name. */
    public static String render(Throwable t, PipelineConfig cfg) {
        if (t == null) return "";
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : scrub(m, cfg);
    }

    /** {@code text} with every quoted literal and every echoed CSV line replaced by its fingerprint. */
    public static String scrub(String text, PipelineConfig cfg) {
        if (text == null || text.isEmpty()) return text;
        // A Binder / Catalog / Parser error is about the AUTHORED SQL (a missing column, a bad expression): data
        // never reaches binding, and its quoted identifiers are the whole diagnosis. Kept verbatim unless the text
        // also carries a data-shaped error.
        // (DuckDB's "Invalid Input Error: ... closed pending query result" preamble is a wrapper, not a data error.)
        String judged = com.gamma.util.DuckDbUtil.withoutPendingQueryPreamble(text);
        if (CONFIG_ERROR.matcher(judged).find() && !DATA_ERROR.matcher(judged).find()) return text;
        byte[] salt = cfg == null ? null : salt(cfg);
        Matcher ol = ORIGINAL_LINE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (ol.find()) {
            String line = text.substring(ol.end(1), ol.end()).trim();
            ol.appendReplacement(sb, Matcher.quoteReplacement(ol.group(1) + " <" + fp(salt, line) + ">"));
        }
        ol.appendTail(sb);
        // An UNQUOTED echo of the value, pointed at by a caret line under it (strptime: "SECRETVAL\n^").
        Matcher ca = CARET_ECHO.matcher(sb.toString());
        sb = new StringBuilder();
        while (ca.find())
            ca.appendReplacement(sb, Matcher.quoteReplacement("<" + fp(salt, ca.group(1)) + ">" + ca.group(2)));
        ca.appendTail(sb);
        String s = sb.toString();
        Matcher q = QUOTED.matcher(s);
        sb = new StringBuilder();
        while (q.find()) {
            String lit = q.group();
            char c = lit.charAt(0);
            String inner = lit.substring(1, lit.length() - 1).replace("" + c + c, "" + c);
            q.appendReplacement(sb, Matcher.quoteReplacement(c + "<" + fp(salt, inner) + ">" + c));
        }
        q.appendTail(sb);
        s = sb.toString();
        // Any quote still standing opens a literal that was cut off: fingerprint the rest.
        String outside = QUOTED.matcher(s).replaceAll("");
        Matcher u = UNTERMINATED.matcher(outside);
        if (u.find()) {
            int at = s.lastIndexOf(u.group());
            if (at >= 0) s = s.substring(0, at) + "<" + fp(salt, s.substring(at + 1)) + ">";
        }
        return s;
    }

    /** The salted fingerprint of one value under {@code cfg}'s Space: {@code fp:<16 hex>}. */
    public static String fingerprint(PipelineConfig cfg, String value) {
        return fp(salt(cfg), value == null ? "" : value);
    }

    private static String fp(byte[] salt, String value) {
        if (salt == null) return "redacted";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            return "fp:" + HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * The Space's salt — 32 random bytes created once (atomic create) at {@link #SALT_FILE} under the Space root:
     * the nearest ancestor of the pipeline's status/errors/poll dir that holds a {@code config} directory, else
     * that dir's parent.
     */
    static byte[] salt(PipelineConfig cfg) {
        Path root = spaceRoot(cfg);
        return SALTS.computeIfAbsent(root.resolve(SALT_FILE).toAbsolutePath().normalize(), FailureText::loadOrCreate);
    }

    static Path spaceRoot(PipelineConfig cfg) {
        PipelineConfig.Dirs d = cfg.dirs();
        Path start = d.statusFilePath() != null ? Paths.get(d.statusFilePath()).toAbsolutePath().getParent()
                : d.errors() != null ? Paths.get(d.errors()).toAbsolutePath()
                : Paths.get(d.poll()).toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent())
            if (Files.isDirectory(p.resolve("config"))) return p;
        return start.getParent() != null ? start.getParent() : start;
    }

    private static byte[] loadOrCreate(Path file) {
        try {
            if (Files.isRegularFile(file) && Files.size(file) == 32) return Files.readAllBytes(file);
            Files.createDirectories(file.getParent());
            byte[] fresh = new byte[32];
            new SecureRandom().nextBytes(fresh);
            Path tmp = Files.createTempFile(file.getParent(), "salt", ".tmp");
            Files.write(tmp, fresh);
            try {
                Files.move(tmp, file);   // no REPLACE_EXISTING: a concurrent creator wins, we read theirs
            } catch (FileAlreadyExistsException raced) {
                Files.deleteIfExists(tmp);
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read or create the value-fingerprint salt " + file, e);
        }
    }
}
