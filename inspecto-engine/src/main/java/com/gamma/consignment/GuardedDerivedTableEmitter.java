package com.gamma.consignment;

import com.gamma.config.spec.Finding;
import com.gamma.sql.SqlGuard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The {@link DerivedTableEmitter} the framework hands a processor: validates each request and holds it.
 * <b>Storage lives in {@link DerivedTableWriter}</b>, so this class validates and collects, and the Job
 * owns the "register only after the data is revealed" ordering — the same split
 * {@link GuardedSummaryEmitter} uses.
 *
 * <p><b>What is refused, and why each one is a refusal rather than a repair:</b>
 * <ul>
 *   <li>an unsafe {@code name} — it becomes a <b>directory name</b>, so this is the path jail at the seam
 *       where third-party text enters. Escaping it would invent a name the author did not ask for;</li>
 *   <li>blank {@code sql} — there is no sensible default relation to guess;</li>
 *   <li>an unsafe {@code partitionBy} — it becomes a SQL identifier and a directory level;</li>
 *   <li>a duplicate {@code name} within one run — two emissions for one table would race for the same
 *       path, and picking a winner silently is how one of them disappears.</li>
 * </ul>
 *
 * <p>⚠ A statement that is not a {@code SELECT} is refused by <b>shape</b>, not by parsing: the SQL runs on
 * a sealed, read-only sandbox that would reject a write anyway, but refusing here names the mistake at the
 * seam the author touched instead of deep inside the writer.
 */
public final class GuardedDerivedTableEmitter implements DerivedTableEmitter {

    /**
     * Identifiers that may become a SQL identifier or a <b>directory name</b>. Deliberately strict and
     * deliberately identical to {@code SummaryWriter}'s rule: a name arrives from third-party processor
     * code and is used to build a path, so anything outside this set is refused rather than escaped.
     */
    static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    /**
     * Whether a path may be named directly in author SQL. The framework passes the registry's own
     * readability test; {@link #NONE} refuses every path, which is the correct default for any caller that
     * cannot answer the question.
     */
    @FunctionalInterface
    public interface ReadablePaths {
        boolean isReadable(String path);

        /** Refuses every path — a caller with no registry cannot vouch for one. */
        ReadablePaths NONE = path -> false;
    }

    private final List<DerivedTable> emitted = new ArrayList<>();
    private final LinkedHashSet<String> names = new LinkedHashSet<>();
    private final ReadablePaths readable;

    /** An emitter that admits no direct file reads. */
    public GuardedDerivedTableEmitter() {
        this(ReadablePaths.NONE);
    }

    public GuardedDerivedTableEmitter(ReadablePaths readable) {
        this.readable = readable == null ? ReadablePaths.NONE : readable;
    }

    @Override
    public void emit(DerivedTable table) {
        List<String> violations = check(table, names, readable);
        if (!violations.isEmpty())
            throw new IllegalArgumentException("derived table refused: " + String.join("; ", violations));
        names.add(table.name());
        emitted.add(table);
    }

    /** The requests that passed, in emit order. */
    public List<DerivedTable> emitted() {
        return List.copyOf(emitted);
    }

    /** Every violation in {@code table}, empty when it is acceptable. Package-private so the test can pin it. */
    static List<String> check(DerivedTable table, LinkedHashSet<String> taken) {
        return check(table, taken, ReadablePaths.NONE);
    }

    /**
     * A {@code read_parquet('<literal>')} call, matched narrowly on purpose: the function name, optional
     * whitespace, one single-quoted literal, optional whitespace, close. Anything richer — extra options,
     * a non-literal argument, a different reader — does not match, so it is left in the text and
     * {@code SqlGuard} refuses it. <b>Fail-closed by construction</b>: a call this does not recognise is a
     * call that gets rejected, never one that slips through unchecked.
     */
    private static final Pattern READ_PARQUET =
            Pattern.compile("(?i)read_parquet\\s*\\(\\s*'((?:[^']|'')*)'\\s*\\)");

    /** Every violation in {@code table}, with {@code readable} deciding which paths may be named directly. */
    static List<String> check(DerivedTable table, LinkedHashSet<String> taken, ReadablePaths readable) {
        List<String> out = new ArrayList<>();
        if (table == null) return List.of("the request is null");
        if (table.name() == null || !SAFE_NAME.matcher(table.name()).matches())
            out.add("name '" + table.name() + "' must match " + SAFE_NAME.pattern()
                    + " (it becomes a directory name)");
        else if (taken != null && taken.contains(table.name()))
            out.add("name '" + table.name() + "' was already emitted in this run");
        if (table.sql() == null || table.sql().isBlank())
            out.add("sql is blank — name the SELECT that produces the table");
        else {
            // The SAME lexical allow-list ConsignmentReader.query enforces, for the reason that seam
            // states about itself: "invariant protection, not a defence against hostile in-process code".
            // ⛔ NOT a security boundary — a processor is arbitrary Java on the engine classpath and can
            // open a file directly. What it protects is the ADDRESSING invariant: a `COPY … TO` in author
            // SQL writes a file the registry never learns about, and everything downstream keys off
            // registered outputs (the Selector's pruning, retire_superseded, compact, and the next step's
            // own outputs()). An unregistered file is invisible to all of them.
            //
            // ⇒ which is exactly why a REGISTERED path is different, and admitted: reading one keeps the
            // invariant intact. Each read_parquet('<literal>') is checked against the registry and then
            // MASKED to an identifier, so the rest of the statement still meets every other SqlGuard rule
            // — single statement, must start with SELECT/WITH, no other blocked function.
            String masked = table.sql();
            for (java.util.regex.MatchResult m : READ_PARQUET.matcher(table.sql()).results().toList()) {
                String path = m.group(1).replace("''", "'");
                if (!readable.isReadable(path))
                    out.add("sql: read_parquet('" + path + "') names a path this registry does not list as "
                            + "readable — a derived table may read registered outputs, not arbitrary files");
                else
                    masked = masked.replace(m.group(), "__registered_read");
            }
            for (Finding f : SqlGuard.check(masked)) out.add("sql: " + f.message());
        }
        if (table.partitionBy() != null && !SAFE_NAME.matcher(table.partitionBy()).matches())
            out.add("partitionBy '" + table.partitionBy() + "' must match " + SAFE_NAME.pattern());
        return out;
    }
}
