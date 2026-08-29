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

    private final List<DerivedTable> emitted = new ArrayList<>();
    private final LinkedHashSet<String> names = new LinkedHashSet<>();

    @Override
    public void emit(DerivedTable table) {
        List<String> violations = check(table, names);
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
            // 🔴 The SAME lexical allow-list ConsignmentReader.query enforces. Without it a derived table
            // would be a hole straight through the read-only invariant: the author's SQL runs on the same
            // unsealed sandbox (the relations are lazy views over files), so a `COPY … TO` or a `read_csv`
            // here would reach the filesystem exactly where query() refuses to let it. Shape-checking for
            // a leading SELECT is not a substitute — SqlGuard also rejects multiple statements, comment
            // tricks and the whole blocked-function surface.
            for (Finding f : SqlGuard.check(table.sql())) out.add("sql: " + f.message());
        }
        if (table.partitionBy() != null && !SAFE_NAME.matcher(table.partitionBy()).matches())
            out.add("partitionBy '" + table.partitionBy() + "' must match " + SAFE_NAME.pattern());
        return out;
    }
}
