package com.gamma.etl;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gamma.util.SqlBuilder;

/**
 * The <b>one</b> vocabulary of {@code raw.fields[].type} values the engine honours, and the SQL each
 * one compiles to.
 *
 * <p><b>Why this class exists.</b> Before 2026-08-22 the honoured set was a four-branch
 * {@code switch} inlined in {@link TransformCompiler#direct} — {@code TIMESTAMP} / {@code DATE} /
 * {@code DOUBLE}, with a {@code default} that emitted the column <em>uncast</em>. Nothing validated
 * the declared type, so a schema saying {@code type: BIGINT} silently produced a {@code VARCHAR}
 * column: no error, no warning, and the loss only surfaced when something downstream tried to sum it.
 * Two more sites carried their own copy of that three-type list ({@link TransformCompiler#dateExpr}'s
 * date-vs-else choice and {@link DataTransformer}'s cast-failure measurability test), so widening the
 * set meant widening three lists that were free to drift.
 *
 * <p><b>Operator decision (2026-08-22): fail closed.</b> Every DuckDB scalar type reachable by
 * {@code TRY_CAST} from text is honoured, and a type outside this vocabulary is <b>refused at config
 * load</b> ({@link Identifiers#validateSchema}) rather than silently degraded to text. A typo is now
 * a load error instead of a column that quietly holds strings.
 *
 * <p><b>Deliberately excluded</b>, because offering them would be the same lie in a new place:
 * <ul>
 *   <li><b>Nested</b> {@code LIST} / {@code STRUCT} / {@code MAP} / {@code UNION} — a delimited or
 *       fixed-width token cannot be meaningfully cast into one, and the ingest relation is flat.</li>
 *   <li><b>{@code JSON}</b> — extension-dependent, and this deployment does not statically link
 *       DuckDB extensions (the {@code excel} lane learned that the hard way).</li>
 *   <li><b>{@code INTERVAL}</b>, {@code BIT}, {@code ENUM} — no sane text form arriving from a feed.</li>
 * </ul>
 */
public final class SchemaFieldTypes {

    private SchemaFieldTypes() {}

    /** The pass-through type: stored as parsed, no cast attempted. */
    public static final String VARCHAR = "VARCHAR";

    /**
     * Fixed-name honoured types. {@code DECIMAL(p,s)} is parameterised and handled by
     * {@link #DECIMAL} instead of being listed here.
     */
    private static final Set<String> EXACT = Set.of(
            VARCHAR, "BOOLEAN", "BLOB", "UUID",
            "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "HUGEINT",
            "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT",
            "FLOAT", "DOUBLE",
            "DATE", "TIME", "TIMESTAMP", "TIMESTAMPTZ");

    /** {@code DECIMAL(p,s)} — DuckDB allows precision 1‥38 and scale 0‥precision. */
    private static final Pattern DECIMAL = Pattern.compile("DECIMAL\\(\\s*(\\d{1,2})\\s*,\\s*(\\d{1,2})\\s*\\)");

    /** Types parsed from TEXT through the pipeline's strptime format lists, not a bare cast. */
    private static final Set<String> DATE_LIKE = Set.of("DATE", "TIMESTAMP", "TIMESTAMPTZ");

    /**
     * The honoured type names, for a UI/AI list. {@code DECIMAL(p,s)} is appended as the bare token
     * {@code DECIMAL} — a caller offering it must ask for precision and scale.
     */
    public static List<String> names() {
        Set<String> out = new LinkedHashSet<>();
        // VARCHAR first (the default and the pass-through), then a stable, readable order.
        out.add(VARCHAR);
        out.add("BOOLEAN");
        for (String t : List.of("TINYINT", "SMALLINT", "INTEGER", "BIGINT", "HUGEINT",
                "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "FLOAT", "DOUBLE")) out.add(t);
        out.add("DECIMAL");
        for (String t : List.of("DATE", "TIME", "TIMESTAMP", "TIMESTAMPTZ", "UUID", "BLOB")) out.add(t);
        return List.copyOf(out);
    }

    /**
     * Canonical spelling: trimmed, upper-cased, inner whitespace collapsed, and the SQL-standard
     * long forms folded onto the short ones DuckDB reports. An empty/absent type normalises to
     * {@link #VARCHAR} — absence has always meant "leave it as text", and that stays true.
     */
    public static String normalize(String raw) {
        if (raw == null) return VARCHAR;
        String t = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (t.isEmpty()) return VARCHAR;
        t = switch (t) {
            case "TIMESTAMP WITH TIME ZONE" -> "TIMESTAMPTZ";
            case "TIMESTAMP WITHOUT TIME ZONE" -> "TIMESTAMP";
            case "STRING", "TEXT" -> VARCHAR;
            case "INT", "INT4" -> "INTEGER";
            case "INT8", "LONG" -> "BIGINT";
            case "INT2" -> "SMALLINT";
            case "INT1" -> "TINYINT";
            case "REAL", "FLOAT4" -> "FLOAT";
            case "FLOAT8" -> "DOUBLE";
            case "BOOL", "LOGICAL" -> "BOOLEAN";
            case "NUMERIC" -> "DECIMAL(18,3)";
            default -> t;
        };
        // DECIMAL spacing is normalised so `DECIMAL( 18 , 2 )` and `DECIMAL(18,2)` are one value.
        Matcher m = DECIMAL.matcher(t);
        return m.matches() ? "DECIMAL(" + Integer.parseInt(m.group(1)) + "," + Integer.parseInt(m.group(2)) + ")" : t;
    }

    /** Whether the engine can actually produce this type — the fail-closed gate's predicate. */
    public static boolean isHonored(String type) {
        String t = normalize(type);
        if (EXACT.contains(t)) return true;
        Matcher m = DECIMAL.matcher(t);
        if (!m.matches()) return false;
        int precision = Integer.parseInt(m.group(1));
        int scale = Integer.parseInt(m.group(2));
        return precision >= 1 && precision <= 38 && scale >= 0 && scale <= precision;
    }

    /**
     * A date/time type parsed through the pipeline's {@code date_formats}/{@code timestamp_formats}
     * rather than a bare cast — also the set a {@code DATE_*} partition may be cut from.
     */
    public static boolean isDateLike(String type) {
        return DATE_LIKE.contains(normalize(type));
    }

    /**
     * Whether a declared type causes a coercion at all. {@code VARCHAR} does not, so a value can
     * never be "silently nulled" by it — which is exactly the question the cast-failure audit asks
     * before it bothers counting a column.
     */
    public static boolean coerces(String type) {
        return !VARCHAR.equals(normalize(type));
    }

    /**
     * The SQL producing {@code col} at its declared type.
     *
     * <p>Date-like types keep the pre-existing shape: cast to {@code VARCHAR} first — a no-op for a
     * raw CSV column, and what turns an <em>already typed</em> DATE/TIMESTAMP (the plugin lane) into
     * an ISO string so {@code TRY_STRPTIME} always receives text — then the format-list
     * {@code COALESCE} chain. Everything else is a plain {@code TRY_CAST}, which nulls a bad value
     * rather than failing the batch (the same forgiveness {@code DOUBLE} always had).
     *
     * @param type must be {@link #isHonored honoured}; an unhonoured type is a config-load error, so
     *             reaching here with one is a bug and throws rather than degrading to text
     */
    public static String castSql(String col, String type, List<String> dateFormats, List<String> tsFormats) {
        return castSql(col, type, dateFormats, tsFormats, null);
    }

    /**
     * As {@link #castSql(String, String, List, List)}, with the column's <b>source time zone</b>
     * applied — {@code zoneArg} is a SQL zone argument from
     * {@link SourceZones#zoneArg(String, String)}, or {@code null} for the wall-clock default.
     *
     * <p>A {@code null} {@code zoneArg} reproduces the four-argument form character for character,
     * which is what makes "no zone declared" a literal no-op rather than an equivalent rewrite.
     *
     * <p>⚠ Only {@code TIMESTAMP} and {@code TIMESTAMPTZ} are shifted. A {@code DATE} has no instant
     * to move, and shifting one would push a calendar day across midnight under any negative offset.
     *
     * @throws IllegalArgumentException for a {@code TIMESTAMPTZ} with no zone source — that
     *         combination is refused at config load, because the zone DuckDB would otherwise supply
     *         is the <em>host's</em>
     */
    public static String castSql(String col, String type, List<String> dateFormats,
                                 List<String> tsFormats, String zoneArg) {
        String t = normalize(type);
        if (VARCHAR.equals(t)) return col;
        if (!isHonored(t)) {
            throw new IllegalArgumentException("unhonoured schema field type '" + type
                    + "'; this must be refused at config load (see Identifiers.validateSchema)");
        }
        if (DATE_LIKE.contains(t)) {
            StringBuilder sb = new StringBuilder();
            String asText = "CAST(" + col + " AS VARCHAR)";
            if ("DATE".equals(t)) {
                SqlBuilder.appendCoalesce(sb, asText, dateFormats, t);
                return sb.toString();
            }
            // Parse naive first in both cases: TRY_STRPTIME yields a zone-less TIMESTAMP, and the
            // zone is what turns it into an instant. Casting straight to TIMESTAMPTZ (what this did
            // before) resolves it against the SESSION zone — the host — which is never the data's.
            SqlBuilder.appendCoalesce(sb, asText, tsFormats, "TIMESTAMP");
            String naive = sb.toString();
            return "TIMESTAMPTZ".equals(t)
                    ? SourceZones.toInstant(naive, zoneArg)
                    : SourceZones.toNaiveUtc(naive, zoneArg);
        }
        return "TRY_CAST(" + col + " AS " + t + ")";
    }
}
