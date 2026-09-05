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
 * <p>A block whose rows are not all {@code DIRECT} cannot stay tabular: {@code EXPR} needs a nested
 * {@code args.expression}, and the two date rules carry parameters. Such a block is rewritten as the TOON
 * <b>block-list</b> form instead — one {@code - name:/from:/fn:/args:} entry per row, replacing exactly the
 * header and its N rows; every line outside the block still survives byte-for-byte:
 * <pre>
 *   fields[8]:
 *     - name: REGION
 *       from: ""
 *       fn: custom
 *       args:
 *         expression: "UPPER(TRIM(REGION))"
 * </pre>
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

        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            int at = headerIdx + i;
            if (at >= lines.size()) return new Result(file, false, "block claims " + count
                    + " rows but the file ends after " + (i - 1), count);
            String row = lines.get(at);
            if (!row.startsWith(rowIndent)) return new Result(file, false,
                    "row " + i + " is not indented like the others — refusing a partial rewrite", count);
            rows.add(row);
        }

        List<String> rewritten = new ArrayList<>(lines.subList(0, headerIdx));
        boolean allDirect = rows.stream().allMatch(r -> r.endsWith(",DIRECT"));
        if (allDirect) {
            // The tabular forms line up exactly — only the header and each trailing type token change.
            rewritten.add(indent + "fields[" + count + "]{name,from,fn}:");
            for (String row : rows)
                rewritten.add(row.substring(0, row.length() - ",DIRECT".length()) + ",keep");
        } else {
            // Something needs args: the whole block becomes the block-list form, via the same converter
            // the engine's read-time conversion uses, so the two spellings cannot drift.
            List<Map<String, Object>> rules = new ArrayList<>();
            for (String row : rows) {
                List<String> cells = splitRow(row.trim());
                if (cells.size() != 3) return new Result(file, false,
                        "row (" + row.trim() + ") does not have 3 cells — refusing a partial rewrite", count);
                Map<String, Object> rule = new java.util.LinkedHashMap<>();
                rule.put("targetColumn", cells.get(0));
                rule.put("sourceExpression", cells.get(1));
                rule.put("transformType", cells.get(2));
                rules.add(rule);
            }
            List<Map<String, Object>> fields;
            try {
                fields = RecordTransform.fromMappingRules(rules);
            } catch (IllegalArgumentException e) {
                return new Result(file, false, e.getMessage(), count);
            }
            rewritten.add(indent + "fields[" + count + "]:");
            for (Map<String, Object> f : fields) {
                rewritten.add(rowIndent + "- name: " + toonScalar(f.get("name")));
                rewritten.add(rowIndent + "  from: " + toonScalar(f.get("from")));
                rewritten.add(rowIndent + "  fn: " + toonScalar(f.get("fn")));
                @SuppressWarnings("unchecked")
                Map<String, Object> args = (Map<String, Object>) f.get("args");
                if (args != null && !args.isEmpty()) {
                    rewritten.add(rowIndent + "  args:");
                    for (Map.Entry<String, Object> a : args.entrySet())
                        rewritten.add(rowIndent + "    " + a.getKey() + ": " + toonScalar(a.getValue()));
                }
            }
        }
        rewritten.addAll(lines.subList(headerIdx + 1 + count, lines.size()));

        String updated = String.join("\n", rewritten) + (original.endsWith("\n") ? "\n" : "");

        String problem = verify(original, updated);
        if (problem != null) return new Result(file, false, problem, count);

        if (!dryRun) Files.writeString(file, updated);
        return new Result(file, true, null, count);
    }

    /** Split one tabular TOON row into its cells: commas separate, double quotes group (doubled inside). */
    static List<String> splitRow(String row) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < row.length() && row.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else quoted = false;
                } else if (c == '\\' && i + 1 < row.length()) {
                    cur.append(row.charAt(++i));
                } else cur.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cur.toString()); cur.setLength(0);
            } else cur.append(c);
        }
        cells.add(cur.toString());
        return cells;
    }

    /** A scalar as a TOON value: bare when it is a plain identifier-like token, double-quoted otherwise. */
    static String toonScalar(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (!s.isEmpty() && s.matches("[A-Za-z_][A-Za-z0-9_.]*") && !s.matches("true|false|null"))
            return s;
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
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
