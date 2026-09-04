package com.gamma.etl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Migrates a stored schema's legacy {@code mapping.rules[]} to the Record Transformer's
 * {@code mapping.fields[]} — the production caller for {@link RecordTransform#fromMappingRules}.
 *
 * <p><b>Surgical by design.</b> Parsing a schema and re-serialising it rewrites the WHOLE file: the TOON
 * writer normalises quoting on untouched lines and drops the trailing newline, which on a 537-column
 * schema turns a 1-line change into an unreadable 500-line diff. So the common case is rewritten as
 * TEXT — only the {@code rules[…]} block's header and each row's trailing type token change, and every
 * other byte survives.
 *
 * <p>The tabular forms line up exactly, which is what makes this safe:
 * <pre>
 *   rules[N]{targetColumn,sourceExpression,transformType}:      fields[N]{name,from,fn}:
 *     ORDER_ID,ORDER_ID,DIRECT                          →         ORDER_ID,ORDER_ID,keep
 * </pre>
 *
 * <p>⛔ A rule that is not {@code DIRECT} is NOT rewritten this way. {@code EXPR} needs a nested
 * {@code args.expression}, which a flat tabular row cannot hold, and {@code CONCAT_DT} /
 * {@code FILENAME_DATE} have no faithful catalog equivalent at all
 * ({@link RecordTransform#fromMappingRules} refuses them). Such a file is REPORTED and left untouched
 * rather than half-migrated — its {@code rules[]} stay readable permanently.
 *
 * <p>Every rewrite is verified before it is written: the compiled projection
 * ({@link DataTransformer#dataColumns}) must be byte-identical before and after, or the file is left
 * alone and the mismatch reported. Equality of the emitted SQL is the only check a plausible-looking but
 * wrong field list cannot pass.
 *
 * <p>Usage: {@code java -cp <jar> com.gamma.etl.MappingMigrator [--dry-run] <file-or-dir>…}
 */
public final class MappingMigrator {

    private MappingMigrator() {}

    /** {@code   rules[537]{targetColumn,sourceExpression,transformType}:} — indent captured, count kept. */
    private static final Pattern HEADER = Pattern.compile(
            "^(\\s*)rules\\[(\\d+)]\\{targetColumn,sourceExpression,transformType}:\\s*$");

    /** The outcome for one file. {@code changed} false with a null problem means "nothing to do". */
    public record Result(Path file, boolean changed, String problem, int rules) {}

    public static void main(String[] args) throws IOException {
        boolean dryRun = List.of(args).contains("--dry-run");
        List<Path> targets = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("--")) continue;
            Path p = Path.of(a);
            if (Files.isDirectory(p)) {
                try (var walk = Files.walk(p)) {
                    walk.filter(f -> f.toString().endsWith(".toon")).sorted().forEach(targets::add);
                }
            } else {
                targets.add(p);
            }
        }
        if (targets.isEmpty()) {
            System.err.println("usage: MappingMigrator [--dry-run] <file-or-dir>…");
            System.exit(2);
        }

        int migrated = 0, skipped = 0, refused = 0;
        for (Path p : targets) {
            Result r = migrate(p, dryRun);
            if (r.problem() != null) {
                System.out.println("REFUSED  " + p + " — " + r.problem());
                refused++;
            } else if (r.changed()) {
                System.out.println((dryRun ? "WOULD DO " : "MIGRATED ") + p + " — " + r.rules() + " rules");
                migrated++;
            } else {
                skipped++;
            }
        }
        System.out.println("migrated=" + migrated + " refused=" + refused + " untouched=" + skipped
                + (dryRun ? "  (dry run — nothing written)" : ""));
    }

    /**
     * Migrate one schema file in place. Returns what happened; never throws for a file it simply cannot
     * migrate — that is a {@code problem}, so a bulk run reports it and moves on.
     */
    public static Result migrate(Path file, boolean dryRun) throws IOException {
        String original = Files.readString(file);
        List<String> lines = original.lines().toList();

        int headerIdx = -1;
        Matcher header = null;
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = HEADER.matcher(lines.get(i));
            if (m.matches()) { headerIdx = i; header = m; break; }
        }
        if (headerIdx < 0) return new Result(file, false, null, 0);   // no legacy block — nothing to do

        String indent = header.group(1);
        int count = Integer.parseInt(header.group(2));
        String rowIndent = indent + "  ";

        List<String> rewritten = new ArrayList<>(lines);
        rewritten.set(headerIdx, indent + "fields[" + count + "]{name,from,fn}:");

        for (int i = 1; i <= count; i++) {
            int at = headerIdx + i;
            if (at >= lines.size()) return new Result(file, false, "block claims " + count
                    + " rows but the file ends after " + (i - 1), count);
            String row = lines.get(at);
            if (!row.startsWith(rowIndent)) return new Result(file, false,
                    "row " + i + " is not indented like the others — refusing a partial rewrite", count);
            // ⛔ Only DIRECT maps onto a flat tabular row. Anything else needs a shape this cannot write.
            if (!row.endsWith(",DIRECT")) return new Result(file, false,
                    "row " + i + " is not DIRECT (" + row.trim() + ") — needs a nested field shape, "
                            + "so this file keeps its rules[]", count);
            rewritten.set(at, row.substring(0, row.length() - ",DIRECT".length()) + ",keep");
        }

        String updated = String.join("\n", rewritten) + (original.endsWith("\n") ? "\n" : "");

        String problem = verify(original, updated);
        if (problem != null) return new Result(file, false, problem, count);

        if (!dryRun) Files.writeString(file, updated);
        return new Result(file, true, null, count);
    }

    /**
     * The safety gate: both spellings must compile to the SAME projection. Returns a problem, or null.
     *
     * <p>⚠ A schema whose projection cannot be compiled at all (an unusual shape, a type this build does
     * not honour) is refused rather than migrated blind — an uncompilable before/after pair proves
     * nothing.
     */
    private static String verify(String before, String after) {
        PipelineConfig.CsvSettings csv =
                PipelineConfig.CsvSettings.ofFormats(List.of("%Y-%m-%d"), List.of("%Y-%m-%d %H:%M:%S"));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) com.gamma.config.io.ConfigCodec.toMap(before);
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) com.gamma.config.io.ConfigCodec.toMap(after);
            List<Map<String, Object>> was = DataTransformer.dataColumns(a, csv, "raw_input");
            List<Map<String, Object>> now = DataTransformer.dataColumns(b, csv, "raw_input");
            return was.equals(now) ? null
                    : "the compiled projection would CHANGE — refusing (was " + was.size()
                      + " columns, now " + now.size() + ")";
        } catch (RuntimeException e) {
            return "could not compile the projection to compare: " + e.getMessage();
        }
    }
}
