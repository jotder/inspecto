package com.gamma.control;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Per-space Entity Types of Link Analysis (LA-17 step 2, design §4.1) — which identifiers an entity can be, how
 * each one's key is folded, whether Investigation responses mask it, and which Dataset
 * {@code columns[].classification} values type a projection column as it. Stored as the {@code entity_types} key
 * of {@link LinkAnalysisSettings} ({@code link-analysis.toon}); {@code null} there means "inherit
 * {@link #DEFAULTS}", and a stated list <b>replaces</b> the defaults wholesale, so an empty one is refused as
 * ambiguous rather than read as "no types".
 *
 * <p>⛔ <b>The normalisers are a CLOSED set</b> ({@link #NORMALISERS}) because the browser mints the same keys:
 * {@link #normalise} must agree with {@code entity-key.ts} character for character, and the shared fixture
 * {@code entity-normaliser-parity.fixture.json} feeds both sides. ⚠ "Whitespace" is the <b>JavaScript
 * {@code \s} set</b> spelled out as {@link #WS} — Java's {@code \s}, {@code trim()} and {@code strip()} all
 * disagree with it (NBSP, U+FEFF, U+0085), and a key that folds differently on the two sides is a split identity.
 */
public final class EntityTypes {

    private EntityTypes() {}

    /** One configured Entity Type; {@code classifications} are the Dataset column classifications it claims. */
    public record EntityType(String id, String label, String normaliser, boolean masked, List<String> classifications) {
        public EntityType {
            classifications = List.copyOf(classifications);
        }
    }

    /** The closed normaliser set, in declaration order. */
    public static final List<String> NORMALISERS = List.of("default", "digits", "e164", "upper-trim");

    /** More types than this is refused — a curated preference, not a registry. */
    static final int MAX_TYPES = 64;

    /** The seeded types a space inherits until it states its own (design §2.6). */
    public static final List<EntityType> DEFAULTS = List.of(
            new EntityType("subscriber", "Subscriber", "default", true, List.of("SUBSCRIBER")),
            new EntityType("imsi", "IMSI", "digits", true, List.of("IMSI")),
            new EntityType("imei", "IMEI", "digits", true, List.of("IMEI")),
            new EntityType("msisdn", "MSISDN", "e164", true, List.of("MSISDN")),
            new EntityType("wallet", "Wallet", "upper-trim", true, List.of("WALLET")),
            new EntityType("account", "Account", "upper-trim", true, List.of("ACCOUNT")),
            new EntityType("agent", "Agent / till", "upper-trim", false, List.of("AGENT", "TILL")),
            new EntityType("handset", "Handset", "default", false, List.of("HANDSET")),
            new EntityType("cell", "Cell", "upper-trim", false, List.of("CELL", "CELL_ID")));

    /** The JavaScript {@code \s} set as an explicit class (see the class note — never Java's {@code \s}). */
    static final String WS = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]";
    private static final Pattern WS_RUN = Pattern.compile(WS + "+");
    private static final Pattern WS_EDGES = Pattern.compile("\\A" + WS + "+|" + WS + "+\\z");
    /* ⚠ \z, not $: Java's $ also matches before a final line terminator (U+0085 included), JS's does not. */
    private static final Pattern TRAILING_PUNCT = Pattern.compile("[" + WS.substring(1, WS.length() - 1) + ".,;:]+\\z");
    private static final Pattern NON_DIGIT = Pattern.compile("[^0-9]");
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

    /** The entity key of {@code value} under {@code normaliser}; an undeclared normaliser is refused. */
    public static String normalise(String normaliser, String value) {
        /* ⚠ "default" folds final sigma (U+03C2) to sigma (U+03C3) after lowercasing: Java and JS disagree on the
           Final_Sigma context (e.g. before a modifier letter), so only the fold makes both sides agree. */
        return switch (normaliser) {
            case "default" -> trimWs(TRAILING_PUNCT.matcher(
                    WS_RUN.matcher(value.toLowerCase(Locale.ROOT).replace('\u03C2', '\u03C3')).replaceAll(" ")).replaceAll(""));
            case "digits" -> NON_DIGIT.matcher(value).replaceAll("");
            case "e164" -> {
                String t = trimWs(value);
                String d = NON_DIGIT.matcher(t).replaceAll("");
                if (t.startsWith("+")) yield "+" + d;
                yield d.startsWith("00") ? "+" + d.substring(2) : d;
            }
            case "upper-trim" -> trimWs(WS_RUN.matcher(value).replaceAll(" ")).toUpperCase(Locale.ROOT);
            default -> throw new IllegalArgumentException("normaliser must be one of " + NORMALISERS
                    + ", got '" + normaliser + "'");
        };
    }

    private static String trimWs(String s) {
        return WS_EDGES.matcher(s).replaceAll("");
    }

    /**
     * The types in {@code raw} — a list of {@code {id, label, normaliser, masked, classifications}} objects, the
     * shape both the wire and {@code link-analysis.toon} carry — validated. Label and classifications are
     * trimmed. Throws {@link IllegalArgumentException} naming the first problem; nothing is clamped or dropped.
     */
    static List<EntityType> parse(Object raw) {
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("entityTypes must be a list");
        List<EntityType> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException("each entity type must be an object");
            String id = str(m, "id");
            if (!(m.get("masked") instanceof Boolean masked))
                throw new IllegalArgumentException("entity type '" + id + "': masked must be true or false");
            if (!(m.get("classifications") instanceof List<?> cs))
                throw new IllegalArgumentException("entity type '" + id + "': classifications must be a list");
            List<String> classifications = new ArrayList<>();
            for (Object c : cs) {
                if (!(c instanceof String s))
                    throw new IllegalArgumentException("entity type '" + id + "': each classification must be a string");
                classifications.add(s.trim());
            }
            out.add(new EntityType(id, str(m, "label").trim(), str(m, "normaliser"), masked, classifications));
        }
        validate(out);
        return List.copyOf(out);
    }

    private static String str(Map<?, ?> m, String key) {
        if (!(m.get(key) instanceof String s))
            throw new IllegalArgumentException("each entity type needs a string '" + key + "'");
        return s;
    }

    /** Refuse (with a message) what a stated list may not be: see design §4.1 for each rule. */
    static void validate(List<EntityType> types) {
        if (types.isEmpty())
            throw new IllegalArgumentException("entityTypes may not be empty — a stated list replaces the defaults; "
                    + "send null to inherit them");
        if (types.size() > MAX_TYPES)
            throw new IllegalArgumentException("at most " + MAX_TYPES + " entity types, got " + types.size());
        Set<String> ids = new HashSet<>();
        Map<String, String> owner = new HashMap<>();
        for (EntityType t : types) {
            if (!ID.matcher(t.id()).matches())
                throw new IllegalArgumentException("entity type id must match " + ID.pattern() + ", got '" + t.id() + "'");
            if (!ids.add(t.id())) throw new IllegalArgumentException("duplicate entity type id '" + t.id() + "'");
            if (t.label().isBlank()) throw new IllegalArgumentException("entity type '" + t.id() + "': label is blank");
            if (!NORMALISERS.contains(t.normaliser()))
                throw new IllegalArgumentException("entity type '" + t.id() + "': normaliser must be one of "
                        + NORMALISERS + ", got '" + t.normaliser() + "'");
            for (String c : t.classifications()) {
                String k = c.trim().toUpperCase(Locale.ROOT);
                if (k.isEmpty())
                    throw new IllegalArgumentException("entity type '" + t.id() + "': a classification is blank");
                String prev = owner.putIfAbsent(k, t.id());
                if (prev != null)
                    throw new IllegalArgumentException(prev.equals(t.id())
                            ? "entity type '" + t.id() + "' lists classification '" + c.trim() + "' twice"
                            : "classification '" + c.trim() + "' is claimed by both '" + prev + "' and '" + t.id() + "'");
            }
        }
    }

    /** The wire/TOON shape of {@code types}: one {@code {id, label, normaliser, masked, classifications}} each. */
    static List<Map<String, Object>> shape(List<EntityType> types) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EntityType t : types) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("label", t.label());
            m.put("normaliser", t.normaliser());
            m.put("masked", t.masked());
            m.put("classifications", t.classifications());
            out.add(m);
        }
        return out;
    }
}
