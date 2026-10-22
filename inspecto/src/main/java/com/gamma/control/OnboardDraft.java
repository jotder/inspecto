package com.gamma.control;

import com.gamma.entitystore.EntityTypes;
import com.gamma.etl.ExcelExtension;
import com.gamma.pipeline.exec.SchemaSuggest;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads an uploaded file and proposes how to import it ({@code LA-ONBOARD-1} OB-2, {@code ONBOARDING-FIRST-RUN-1} B2):
 * the {@code format} block (file type; for delimited text the delimiter, header and quote), one {@code type} per column
 * (with the strptime {@code format} a date or time column was proven with), a row sample, and through
 * {@link EntityShapes} the {@code entityTypes} block. Every proposed choice is {@code {value, evidence, confidence}}.
 *
 * <p><b>Confidence (OF-2, one rule for both apps):</b> {@code high} when every sampled value agrees with the choice and
 * the sample has at least {@link #MIN_SAMPLE} non-blank values; {@code low} otherwise. A low choice is highlighted and
 * needs a click in the UI; the server never applies one silently - nothing is written before the commit.
 *
 * <p>Formats (DATA-TIERS-1 DT-8): CSV / TSV (any delimiter DuckDB's {@code sniff_csv} finds), Parquet, JSON (an array of
 * objects) and NDJSON, and Excel ({@code .xlsx}) only when this bundle carries the DuckDB {@code excel} extension -
 * otherwise a 422 that says to save the sheet as CSV. Reads run on a sealed in-memory DuckDB allowed only the draft's
 * own directory.
 *
 * <p>The {@code format} block is the extension point of {@code ONBOARDING-FIRST-RUN-1} slice C1 (file-name dates,
 * decimal mark, nested-JSON record path): add a key, keep the {@code {value, evidence, confidence}} shape.
 */
final class OnboardDraft {

    private OnboardDraft() {}

    /** OF-2: below this many non-blank sampled values a choice is never {@code high}. */
    static final int MIN_SAMPLE = 50;
    /** Rows read to propose types and Entity Types. */
    static final int SAMPLE_ROWS = 1000;
    /** Rows returned in the draft for the screen's preview. */
    static final int PREVIEW_ROWS = 20;

    /** The file types Add data reads (the {@code format.type} value). */
    static final List<String> TYPES = List.of("csv", "tsv", "parquet", "json", "ndjson", "excel");

    private static final List<String> TIMESTAMP_FORMATS = List.of("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S",
            "%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%SZ", "%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M",
            "%d/%m/%Y %H:%M:%S", "%m/%d/%Y %H:%M:%S", "%d/%m/%Y %H:%M", "%d.%m.%Y %H:%M:%S");
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    /** One proposed choice. */
    static Map<String, Object> choice(Object value, String evidence, boolean high) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("evidence", evidence);
        m.put("confidence", high ? "high" : "low");
        return m;
    }

    /** One column as the commit will write it: output name, the header it came from, its selector and proposed type. */
    record Col(String name, String header, String selector, String type, String format) {}

    /** What was read: the draft's format and columns, the row count, and the sample. */
    record Analysis(Map<String, Object> format, List<Map<String, Object>> columns, List<Col> cols, long rows,
                    List<Map<String, Object>> preview, List<EntityShapes.Column> sampled) {}

    /** The file type from its name, confirmed (or overruled) by its first bytes. */
    static String fileType(Path file, String name) throws IOException {
        byte[] head = new byte[4];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(head, 0, 4);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (n == 4 && head[0] == 'P' && head[1] == 'A' && head[2] == 'R' && head[3] == '1') return "parquet";
        if (n >= 2 && head[0] == 'P' && head[1] == 'K') {
            if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) return "excel";
            throw unsupported(name, "it is a zip archive");
        }
        if (lower.endsWith(".xls"))
            throw new ApiException(422, ErrorCodes.NOT_SUPPORTED, "'" + name + "' is an old-style Excel workbook; save it as CSV (or .xlsx) and add that");
        if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) throw unsupported(name, "it is not a valid .xlsx workbook");
        if (lower.endsWith(".parquet")) throw unsupported(name, "it does not start with the Parquet magic bytes");
        if (lower.endsWith(".ndjson") || lower.endsWith(".jsonl")) return "ndjson";
        if (lower.endsWith(".json")) return firstNonBlank(file) == '[' ? "json" : "ndjson";
        if (lower.endsWith(".tsv") || lower.endsWith(".tab")) return "tsv";
        if (lower.endsWith(".csv") || lower.endsWith(".txt")) return "csv";
        throw unsupported(name, "its type is not one Add data reads");
    }

    private static ApiException unsupported(String name, String why) {
        return new ApiException(422, ErrorCodes.NOT_SUPPORTED, "'" + name + "' cannot be added: " + why
                + ". Add data reads CSV, TSV, Parquet, JSON and NDJSON files" + (excelAvailable() ? ", and Excel (.xlsx)" : ""));
    }

    private static int firstNonBlank(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            for (int i = 0, c; i < 4096 && (c = in.read()) != -1; i++) if (!Character.isWhitespace(c) && c != 0xEF && c != 0xBB && c != 0xBF) return c;
        }
        return -1;
    }

    /** Whether this bundle can read Excel: the DuckDB {@code excel} extension loads (DT-8). Never installs from the network here. */
    static boolean excelAvailable() {
        boolean[] ok = {false};
        try {
            DuckDbUtil.loadDriver();
            try (Connection c = DuckDbUtil.openInMemory(null, List.of(), conn -> ok[0] = probeExcel(conn))) {
                return ok[0];
            }
        } catch (SQLException | ClassNotFoundException | RuntimeException e) {
            return false;
        }
    }

    /** Load {@code excel} from the bundle only: the staged {@code -Dduckdb.extension.dir}, else DuckDB's local cache. */
    private static boolean probeExcel(Connection conn) {
        if (!com.gamma.config.safety.SafetyPolicy.effectiveTier().permitsExtension("excel")) return false;
        String dir = System.getProperty(ExcelExtension.DIR_PROPERTY);
        if (dir != null && !dir.isBlank()) return ExcelExtension.tryLoad(conn);
        try (Statement st = conn.createStatement()) {
            st.execute("LOAD excel");
            return true;
        } catch (SQLException notCached) {
            return false;
        }
    }

    /** Read {@code file} (in {@code dir}) and propose. */
    static Analysis analyse(Path dir, Path file, String name, String type, Map<String, String> overrides) throws IOException {
        if ("excel".equals(type) && !excelAvailable())
            throw new ApiException(422, ErrorCodes.NOT_SUPPORTED, "this installation cannot read Excel workbooks (the DuckDB excel "
                    + "extension is not in the bundle); save '" + name + "' as CSV and add that");
        try {
            DuckDbUtil.loadDriver();
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        try (Connection conn = DuckDbUtil.openInMemory(null, List.of(dir), "excel".equals(type) ? ExcelExtension::ensureLoaded : null)) {
            return switch (type) {
                case "csv", "tsv" -> delimited(conn, file, name, type, overrides);
                case "parquet" -> typed(conn, file, com.gamma.sql.SqlViews.parquetFile(file),
                        choice("parquet", "the file starts with the Parquet magic bytes", true), true);
                case "json" -> typed(conn, file, "read_json(" + SqlIdent.pathStr(file) + ", format = 'array')",
                        choice("json", "the file is a JSON array", true), false);
                case "ndjson" -> typed(conn, file, "read_json(" + SqlIdent.pathStr(file) + ", format = 'newline_delimited')",
                        choice("ndjson", "the file holds one JSON object per line", true), false);
                case "excel" -> typed(conn, file, "read_xlsx(" + SqlIdent.pathStr(file) + ", header = true, all_varchar = true)",
                        choice("excel", "the file is an .xlsx workbook; its first sheet is read", true), false);
                default -> throw new IllegalStateException(type);
            };
        } catch (SQLException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + name + "' could not be read as "
                    + type.toUpperCase(Locale.ROOT) + ": " + firstLine(e.getMessage()));
        }
    }

    private static String firstLine(String s) {
        if (s == null) return "unreadable";
        String line = s.replaceAll("\\s+", " ").trim();
        if (line.length() > 400) line = line.substring(0, 400) + "…";
        // never leak a server path
        return line.replaceAll("[\"'][^\"']*[/\\\\][^\"']*[\"']", "'<file>'");
    }

    // ── delimited text ───────────────────────────────────────────────────────────────────────────────────────

    private static Analysis delimited(Connection conn, Path file, String name, String type, Map<String, String> overrides)
            throws SQLException, IOException {
        String delim = ",", quote = "\"";
        boolean header = true, sniffed = true;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT Delimiter, Quote, HasHeader FROM sniff_csv(" + SqlIdent.pathStr(file) + ")")) {
            if (rs.next()) {
                // sniff_csv spells "none found" as "(empty)" (or NUL): keep the safe default then - '"' is harmless when unused
                delim = oneChar(rs.getString(1), ",");
                quote = oneChar(rs.getString(2), "\"");
                header = rs.getBoolean(3);
            }
        } catch (SQLException unsettled) {
            // DuckDB could not settle the dialect (mixed line endings, ragged lines): guess the delimiter that splits the
            // first lines most evenly, keep a header and '"', and say so - every format choice is then low confidence
            sniffed = false;
            delim = guessDelimiter(file);
        }
        if ("tsv".equals(type) && !"\t".equals(delim)) delim = "\t";
        // the user's own choice wins over the sniff, and is high confidence by definition
        if (overrides.containsKey("delimiter")) delim = overrides.get("delimiter");
        if (overrides.containsKey("quote")) quote = overrides.get("quote");
        if (overrides.containsKey("header")) header = Boolean.parseBoolean(overrides.get("header"));
        String finalType = "\t".equals(delim) ? "tsv" : "csv";

        // field-count evidence over the first lines (quote-aware; an embedded newline makes it approximate)
        Map<Integer, Integer> widths = new TreeMap<>();
        int lines = 0, quotedFields = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            while (lines < SAMPLE_ROWS && (line = r.readLine()) != null) {
                if (first) {
                    first = false;
                    if (header) continue;
                }
                if (line.isBlank()) continue;
                int[] q = new int[1];
                widths.merge(fields(line, delim.charAt(0), quote.isEmpty() ? 0 : quote.charAt(0), q), 1, Integer::sum);
                quotedFields += q[0];
                lines++;
            }
        } catch (java.nio.charset.MalformedInputException notUtf8) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + name + "' is not UTF-8 text; save it as CSV UTF-8 and add it again");
        }
        int mode = 0, modeCount = 0;
        for (var e : widths.entrySet()) if (e.getValue() > modeCount) {
            mode = e.getKey();
            modeCount = e.getValue();
        }
        boolean allLinesAgree = widths.size() == 1;
        String delimName = switch (delim) {
            case "," -> "','";
            case ";" -> "';'";
            case "\t" -> "a tab";
            case "|" -> "'|'";
            default -> "'" + delim + "'";
        };

        String relation = "read_csv(" + SqlIdent.pathStr(file) + ", delim = " + SqlIdent.sqlStr(delim) + ", quote = " + SqlIdent.sqlStr(quote)
                + ", header = " + header + ", all_varchar = true, null_padding = true, ignore_errors = true)";
        Analysis base = typed(conn, file, relation, null, false);

        Map<String, Object> format = new LinkedHashMap<>();
        boolean extMatches = name.toLowerCase(Locale.ROOT).endsWith(finalType.equals("tsv") ? ".tsv" : ".csv")
                || (finalType.equals("tsv") && name.toLowerCase(Locale.ROOT).endsWith(".tab"));
        format.put("type", choice(finalType, "the file name is '" + name + "' and its lines split on " + delimName,
                extMatches && allLinesAgree && sniffed && lines >= MIN_SAMPLE));
        format.put("delimiter", overrides.containsKey("delimiter") ? choice(delim, "you chose " + delimName, true) : choice(delim, (allLinesAgree ? "every one of " + lines : modeCount + " of " + lines)
                + " sampled lines splits into " + mode + " fields on " + delimName
                + (allLinesAgree ? "" : "; " + (lines - modeCount) + " do not (they may be rejected)"), allLinesAgree && sniffed && lines >= MIN_SAMPLE));
        format.put("header", overrides.containsKey("header") ? choice(header, "you chose " + (header ? "a header line" : "no header line"), true)
                : headerChoice(header, base, sniffed ? lines : 0));
        format.put("quote", overrides.containsKey("quote") ? choice(quote, "you chose " + (quote.isEmpty() ? "no quote" : "'" + quote + "'"), true) : choice(quote, quote.isEmpty() ? "no field is quoted" : quotedFields == 0
                ? "no sampled field is quoted; '" + quote + "' is honoured if one is" : quotedFields + " sampled fields are quoted with '" + quote + "'",
                allLinesAgree && sniffed && lines >= MIN_SAMPLE));
        // header=false ⇒ DuckDB named the columns column0..; give them positional names the user can rename
        List<Col> cols = new ArrayList<>();
        for (int i = 0; i < base.cols().size(); i++) {
            Col c = base.cols().get(i);
            String n = header ? c.name() : "col_" + (i + 1);
            cols.add(new Col(n, header ? c.header() : null, String.valueOf(i), c.type(), c.format()));
        }
        List<Map<String, Object>> columns = header ? base.columns() : renameColumns(base.columns(), cols);
        List<EntityShapes.Column> sampled = header ? base.sampled() : renameSampled(base.sampled(), cols);
        List<Map<String, Object>> preview = header ? base.preview() : renamePreview(base.preview(), base.cols(), cols);
        return new Analysis(format, columns, cols, base.rows(), preview, sampled);
    }

    private static Map<String, Object> headerChoice(boolean header, Analysis base, int lines) {
        if (!header) return choice(false, "the first line looks like data, not names; the columns are numbered", false);
        List<String> typedHeaders = new ArrayList<>();
        for (Col c : base.cols()) if (!"VARCHAR".equals(c.type())) typedHeaders.add(c.header());
        if (typedHeaders.isEmpty())
            return choice(true, "the first line holds names, but every column is text, so it could also be data", false);
        return choice(true, "the first line holds names (" + String.join(", ", typedHeaders.subList(0, Math.min(3, typedHeaders.size())))
                + ") where the lines below hold typed values", lines >= MIN_SAMPLE);
    }

    private static List<Map<String, Object>> renameColumns(List<Map<String, Object>> columns, List<Col> cols) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>(columns.get(i));
            m.put("name", cols.get(i).name());
            m.put("header", null);
            out.add(m);
        }
        return out;
    }

    private static List<EntityShapes.Column> renameSampled(List<EntityShapes.Column> s, List<Col> cols) {
        List<EntityShapes.Column> out = new ArrayList<>();
        for (int i = 0; i < s.size(); i++) out.add(new EntityShapes.Column(cols.get(i).name(), s.get(i).type(), s.get(i).values()));
        return out;
    }

    private static List<Map<String, Object>> renamePreview(List<Map<String, Object>> rows, List<Col> from, List<Col> to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < from.size(); i++) m.put(to.get(i).name(), r.get(from.get(i).name()));
            out.add(m);
        }
        return out;
    }

    /** The candidate that splits the first lines into the same field count (more than one) most often; {@code ,} by default. */
    private static String guessDelimiter(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String l; lines.size() < 200 && (l = r.readLine()) != null; ) if (!l.isBlank()) lines.add(l);
        }
        String best = ",";
        int bestScore = -1;
        for (char d : new char[] {',', ';', '\t', '|'}) {
            Map<Integer, Integer> widths = new TreeMap<>();
            for (String l : lines) widths.merge(fields(l, d, '"', new int[1]), 1, Integer::sum);
            int score = 0;
            for (var e : widths.entrySet()) if (e.getKey() > 1) score = Math.max(score, e.getValue());
            if (score > bestScore) {
                bestScore = score;
                best = String.valueOf(d);
            }
        }
        return best;
    }

    /** Fields in {@code line} split on {@code d}, honouring {@code q} quotes; {@code quoted[0]} counts quoted fields. */
    static int fields(String line, char d, char q, int[] quoted) {
        int n = 1;
        boolean in = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q != 0 && ch == q) {
                if (!in && (i == 0 || line.charAt(i - 1) == d)) quoted[0]++;
                in = !in;
            } else if (ch == d && !in) n++;
        }
        return n;
    }

    // ── any relation ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Columns, types, rows and sample of {@code relation}. {@code declaredTypes}: the file carries its own types
     * (Parquet), so they are taken, not voted.
     */
    private static Analysis typed(Connection conn, Path file, String relation, Map<String, Object> typeChoice, boolean declaredTypes)
            throws SQLException, IOException {
        List<String> headers = new ArrayList<>();
        List<String> declared = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("DESCRIBE SELECT * FROM " + relation)) {
            while (rs.next()) {
                headers.add(rs.getString(1));
                declared.add(rs.getString(2));
            }
        }
        if (headers.isEmpty()) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the file has no columns");
        List<String> names = safeNames(headers);

        long rows;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM " + relation)) {
            rs.next();
            rows = rs.getLong(1);
        }
        StringBuilder proj = new StringBuilder();
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) proj.append(", ");
            proj.append("CAST(").append(SqlIdent.q(headers.get(i))).append(" AS VARCHAR) AS ").append(SqlIdent.q(names.get(i)));
        }
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TEMP TABLE onboard_sample AS SELECT " + proj + " FROM " + relation + " LIMIT " + SAMPLE_ROWS);
        }
        List<Map<String, Object>> sample = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM onboard_sample")) {
            while (rs.next()) {
                Map<String, Object> r = new LinkedHashMap<>();
                for (int i = 0; i < names.size(); i++) r.put(names.get(i), rs.getString(i + 1));
                sample.add(r);
            }
        }
        if (sample.isEmpty()) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the file holds no rows");

        List<SchemaSuggest.Field> voted;
        if (declaredTypes) {
            voted = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) voted.add(new SchemaSuggest.Field(names.get(i), engineType(declared.get(i))));
        } else {
            try {
                voted = SchemaSuggest.infer(sample);
            } catch (SQLException e) {
                throw new IOException(e);
            }
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        List<Col> cols = new ArrayList<>();
        List<EntityShapes.Column> sampled = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            SchemaSuggest.Field f = voted.get(i);
            List<String> values = new ArrayList<>();
            for (Map<String, Object> r : sample) {
                Object v = r.get(n);
                if (v != null && !String.valueOf(v).isBlank()) values.add(String.valueOf(v));
            }
            String fmt = f.format();
            if ("TIMESTAMP".equals(f.type()) && !declaredTypes) fmt = timestampFormat(conn, n);
            Map<String, Object> typeCh = typeChoice(f.type(), fmt, values, declaredTypes ? declared.get(i) : null);
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", n);
            col.put("header", headers.get(i));
            col.put("type", typeCh);
            col.put("sample", values.stream().distinct().limit(3).toList());
            columns.add(col);
            cols.add(new Col(n, headers.get(i), headers.get(i), f.type(), fmt));
            sampled.add(new EntityShapes.Column(n, f.type(), values));
        }
        List<Map<String, Object>> preview = sample.subList(0, Math.min(PREVIEW_ROWS, sample.size()));
        Map<String, Object> format = new LinkedHashMap<>();
        if (typeChoice != null) format.put("type", typeChoice);
        return new Analysis(format, columns, cols, rows, new ArrayList<>(preview), sampled);
    }

    /** The one {@link #TIMESTAMP_FORMATS} entry every non-blank sampled value parses with, else {@code null} (a bare cast). */
    private static String timestampFormat(Connection conn, String column) throws SQLException {
        String c = SqlIdent.q(column);
        for (String f : TIMESTAMP_FORMATS) {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM onboard_sample WHERE "
                    + c + " IS NOT NULL AND trim(" + c + ") <> '' AND TRY_STRPTIME(" + c + ", " + SqlIdent.sqlStr(f) + ") IS NULL")) {
                rs.next();
                if (rs.getLong(1) == 0) return f;
            }
        }
        return null;
    }

    private static Map<String, Object> typeChoice(String type, String fmt, List<String> values, String declared) {
        int n = values.size();
        Map<String, Object> ch;
        if (declared != null) {
            ch = choice(type, "the file declares " + declared, true);
        } else if (n == 0) {
            ch = choice(type, "no value in the sample; kept as text", false);
        } else if (!"VARCHAR".equals(type)) {
            ch = choice(type, (n >= MIN_SAMPLE ? "all " : "only ") + n + " sampled values parse as " + type.toLowerCase(Locale.ROOT)
                    + (fmt == null ? "" : " written " + fmt), n >= MIN_SAMPLE);
        } else {
            int numbers = 0;
            String odd = null;
            for (String v : values) {
                if (NUMBER.matcher(v.trim()).matches()) numbers++;
                else if (odd == null) odd = v;
            }
            if (numbers > 0 && numbers * 5 >= n * 4)
                ch = choice(type, numbers + " of " + n + " sampled values are numbers but " + (n - numbers) + " are not (e.g. '"
                        + clip(odd) + "'), so text", false);
            else
                ch = choice(type, "the values are text (e.g. '" + clip(values.get(0)) + "')", n >= MIN_SAMPLE);
        }
        ch.put("format", fmt);
        return ch;
    }

    private static String clip(String s) {
        return s == null ? "" : s.length() > 24 ? s.substring(0, 24) + "…" : s;
    }

    /** A file-declared DuckDB type as a schema type. */
    static String engineType(String duck) {
        String t = duck == null ? "" : duck.toUpperCase(Locale.ROOT);
        if (t.matches("(BIG|SMALL|TINY|U)?INT(EGER)?|HUGEINT|UBIGINT|USMALLINT|UTINYINT|UINTEGER")) return "BIGINT";
        if (t.startsWith("DOUBLE") || t.startsWith("FLOAT") || t.startsWith("REAL") || t.startsWith("DECIMAL")) return "DOUBLE";
        if (t.startsWith("TIMESTAMP")) return "TIMESTAMP";
        if (t.equals("DATE")) return "DATE";
        if (t.equals("BOOLEAN")) return "BOOLEAN";
        return "VARCHAR";
    }

    /** Headers as safe, unique column names: letters, digits and {@code _}, not starting with a digit. */
    static List<String> safeNames(List<String> headers) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i) == null ? "" : headers.get(i).trim();
            String n = h.replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("^_+|_+$", "");
            if (n.isEmpty()) n = "col_" + (i + 1);
            if (Character.isDigit(n.charAt(0))) n = "c_" + n;
            if (n.length() > 64) n = n.substring(0, 64);
            String base = n;
            for (int k = 2; !seen.add(n.toLowerCase(Locale.ROOT)); k++) n = base + "_" + k;
            out.add(n);
        }
        return out;
    }

    private static String oneChar(String s, String dflt) {
        String v = s == null ? "" : s.replace("\0", "");
        return v.length() == 1 ? v : dflt;
    }

    /** The Entity Type block for an analysis. */
    static Map<String, Object> entityTypes(Analysis a, List<EntityTypes.EntityType> types, String kind) {
        return EntityShapes.propose(a.sampled(), types, kind);
    }
}
