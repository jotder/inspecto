package com.gamma.entitystore;

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

    /**
     * One configured Entity Type; {@code classifications} are the Dataset column classifications it claims.
     * {@code family} is one of {@link #FAMILIES} (ENTITY-TYPE-CATALOG-1), {@code definition} one line on what it is and
     * {@code example} a made-up value of its shape: the three are reader guidance for the "what does this column hold"
     * picker, never part of identity. {@code shape} is one of the closed {@link ValueShapes} vocabulary: what a value looks like,
     * so Add data can propose the type from a sample. Each is {@code null} when nothing states it (a Space's own type of a new id).
     */
    public record EntityType(String id, String label, String normaliser, boolean masked, List<String> classifications,
                             String family, String definition, String example, String shape) {
        public EntityType {
            classifications = List.copyOf(classifications);
        }

        /** A type with no reader guidance (family, definition, example and shape unstated). */
        public EntityType(String id, String label, String normaliser, boolean masked, List<String> classifications) {
            this(id, label, normaliser, masked, classifications, null, null, null, null);
        }
    }

    /**
     * A family: the small, closed, NEUTRAL layer every Entity Type sits under (ENTITY-TYPE-CATALOG-1 Q-1), so a reader who
     * does not know a domain word picks by family and example.
     */
    public record Family(String id, String label, String definition) {}

    /** The closed family set, in the order a picker shows them. ⛔ Neutral words only ({@code IndustryPackNeutralityTest}). */
    public static final List<Family> FAMILIES = List.of(
            new Family("party", "Party", "A person or customer the records are about."),
            new Family("organisation", "Organisation", "A business or body that serves customers: a shop, a branch, a supplier."),
            new Family("account", "Account", "Something that holds a balance or a service for a party."),
            new Family("identifier", "Identifier",
                    "A number or code that names a party or an account: a phone number, a card number, an email address."),
            new Family("device", "Device", "A phone, terminal or other piece of equipment, or the serial number that names it."),
            new Family("place", "Place", "Where something happened: a site, a branch, an area."),
            new Family("document", "Document", "A paper or record that identifies a party or evidences a deal: an ID document, a contract."));

    /**
     * The most characters a stated definition, and a stated example, may hold. ⚠ The definition bound IS the Industry pack's
     * term bound: an overlay lends a type its {@code entity_type.*} term's definition and the pack's load validates the
     * overlay, so a tighter bound here would refuse a pack that loads today.
     */
    static final int MAX_DEFINITION = IndustryPack.MAX_DEFINITION, MAX_EXAMPLE = 64;

    /** The family ids of {@link #FAMILIES}, in order. */
    public static List<String> familyIds() {
        return FAMILIES.stream().map(Family::id).toList();
    }

    /** The wire shape of {@link #FAMILIES}: one {@code {id, label, definition}} each. */
    public static List<Map<String, Object>> familiesShape() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Family f : FAMILIES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id());
            m.put("label", f.label());
            m.put("definition", f.definition());
            out.add(m);
        }
        return out;
    }

    /** The closed normaliser set, in declaration order. */
    public static final List<String> NORMALISERS = List.of("default", "digits", "e164", "upper-trim");

    /**
     * The <b>neutral starter catalogue</b> (ENTITY-TYPE-CATALOG-1 Q-6): what a Space with no Industry pack and no stated
     * list reads, and the core definitions a pack restating one of these ids starts from. With a pack in force a Space reads
     * the types its packs LIST instead ({@link IndustryPack#overlay(List, List)}). ⛔ The labels are NEUTRAL (AC-09): an
     * industry word ("MSISDN", "Wallet") is a pack relabelling the type, never the core's. The first nine ids and their
     * classifications are data contracts and stay as they are; each type sits under one {@link #FAMILIES} family.
     */
    public static final List<EntityType> DEFAULTS = List.of(
            new EntityType("subscriber", "Subscriber", "default", true, List.of("SUBSCRIBER"),
                    "party", "A customer of the service.", "C-104233", "free"),
            new EntityType("imsi", "Subscriber identity", "digits", true, List.of("IMSI"), // vocab-allow: the classification is a data contract; the label is neutral (IndustryPackNeutralityTest)
                    "identifier", "The identity the service stores on a customer's card: up to 15 digits.", "001010123456789", "digits(14-15)"),
            new EntityType("imei", "Device identity", "digits", true, List.of("IMEI"), // vocab-allow: the classification is a data contract; the label is neutral (IndustryPackNeutralityTest)
                    "device", "The serial number of a phone or terminal: 15 digits.", "490154203237518", "luhn(15)"),
            new EntityType("msisdn", "Phone number", "e164", true, List.of("MSISDN"), // vocab-allow: the classification is a data contract; the label is neutral (IndustryPackNeutralityTest)
                    "identifier", "A telephone number, best in international form.", "+44 7700 900123", "e164"),
            new EntityType("wallet", "Stored-value account", "upper-trim", true, List.of("WALLET"),
                    "account", "An account that holds a stored balance, spent from a phone or a card.", "SV-00012345", "free"),
            new EntityType("account", "Account", "upper-trim", true, List.of("ACCOUNT"),
                    "account", "An account a party holds with a provider.", "ACC-0042-7781", "free"),
            new EntityType("agent", "Intermediary / till", "upper-trim", false, List.of("AGENT", "TILL"),
                    "organisation", "A business that serves customers on a provider's behalf: a shop, a till, an intermediary.", "TILL-0381", "free"),
            new EntityType("handset", "Device", "default", false, List.of("HANDSET"),
                    "device", "A phone or terminal, by its make and model.", "Model X-200", "free"),
            new EntityType("cell", "Location", "upper-trim", false, List.of("CELL", "CELL_ID"),
                    "place", "The network site or area that served an event.", "SITE-0451-B", "free"),
            // the neutral types every domain shares (ENTITY-TYPE-CATALOG-1 P3)
            new EntityType("email", "Email address", "default", true, List.of("EMAIL"),
                    "identifier", "An address mail is sent to.", "name@example.org", "email"),
            new EntityType("id_document", "Identity document", "upper-trim", true, List.of("ID_DOCUMENT", "NATIONAL_ID", "PASSPORT"),
                    "document", "The number of a paper that identifies a person: an identity card, a passport.", "X1234567", "free"),
            new EntityType("organisation", "Organisation", "default", false, List.of("ORGANISATION", "COMPANY"),
                    "organisation", "A business or body, by its name or registration number.", "Example Trading Ltd", "free"),
            new EntityType("bank_account", "Bank account", "upper-trim", true, List.of("IBAN", "BANK_ACCOUNT"),
                    "account", "An account held at a bank, best written as an IBAN.", "GB82WEST12345698765432", "iban"),
            new EntityType("card", "Payment card", "digits", true, List.of("CARD", "PAN"),
                    "identifier", "The long number on a payment card: 13 to 19 digits with a check digit.", "4000000000000002", "luhn(13-19)"),
            new EntityType("ip_address", "Network address", "default", false, List.of("IP_ADDRESS"),
                    "identifier", "The address a device used on a network.", "192.0.2.10", "free"),
            new EntityType("address", "Postal address", "default", false, List.of("ADDRESS"),
                    "place", "Where a party lives or a business trades.", "1 Example Street", "free"));

    /** The core default of {@code id}, else {@code null} (a Space's own type). */
    public static EntityType coreDefault(String id) {
        for (EntityType t : DEFAULTS) if (t.id().equals(id)) return t;
        return null;
    }

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
                // A bare "+" (from "+N/A", "00", …) carries no number: empty, so it mints no junk hub.
                String r = t.startsWith("+") ? "+" + d : d.startsWith("00") ? "+" + d.substring(2) : d;
                yield r.equals("+") ? "" : r;
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
     * The types in {@code raw} — a list of {@code {id, label, normaliser, masked, classifications}} objects (optional
     * {@code family}, {@code definition}, {@code example}, {@code shape}; left out, they are the core default's of the same id), the
     * shape both the wire and {@code link-analysis.toon} carry — validated. Label and classifications are
     * trimmed. Throws {@link IllegalArgumentException} naming the first problem; nothing is clamped or dropped. At most
     * the catalogue default of {@link LaLimit#INPUT_ENTITY_TYPES} types.
     */
    public static List<EntityType> parse(Object raw) {
        return parse(raw, (int) LaLimit.INPUT_ENTITY_TYPES.defaultValue().longValue());
    }

    /** {@link #parse(Object)} under the Space's {@code input.entity_types} in force: more types than {@code maxTypes} is refused. */
    public static List<EntityType> parse(Object raw, int maxTypes) {
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
            // Guidance a stated type leaves out is the core default's of the same id (a stated list copies the defaults).
            EntityType core = coreDefault(id);
            String family = opt(m, id, "family", core == null ? null : core.family());
            String definition = opt(m, id, "definition", core == null ? null : core.definition());
            String example = opt(m, id, "example", core == null ? null : core.example());
            String shape = opt(m, id, "shape", core == null ? null : core.shape());
            out.add(new EntityType(id, str(m, "label").trim(), str(m, "normaliser"), masked, classifications,
                    family, definition, example, shape));
        }
        validate(out, maxTypes);
        return List.copyOf(out);
    }

    /** An optional trimmed string; absent or blank reads as {@code fallback}. */
    private static String opt(Map<?, ?> m, String id, String key, String fallback) {
        Object v = m.get(key);
        if (v == null) return fallback;
        if (!(v instanceof String s))
            throw new IllegalArgumentException("entity type '" + id + "': " + key + " must be a string");
        return s.isBlank() ? fallback : s.trim();
    }

    private static String str(Map<?, ?> m, String key) {
        if (!(m.get(key) instanceof String s))
            throw new IllegalArgumentException("each entity type needs a string '" + key + "'");
        return s;
    }

    /** {@link #validate(List, int)} under the catalogue default of {@link LaLimit#INPUT_ENTITY_TYPES}. */
    static void validate(List<EntityType> types) {
        validate(types, (int) LaLimit.INPUT_ENTITY_TYPES.defaultValue().longValue());
    }

    /** Refuse (with a message) what a stated list may not be: see design §4.1 for each rule; more than {@code maxTypes} types (a curated preference, not a registry). */
    public static void validate(List<EntityType> types, int maxTypes) {
        if (types.isEmpty())
            throw new IllegalArgumentException("entityTypes may not be empty — a stated list replaces the defaults; "
                    + "send null to inherit them");
        if (types.size() > maxTypes)
            throw new IllegalArgumentException("at most " + maxTypes + " entity types, got " + types.size());
        Set<String> ids = new HashSet<>();
        Map<String, String> owner = new HashMap<>();
        for (EntityType t : types) {
            if (!ID.matcher(t.id()).matches())
                throw new IllegalArgumentException("entity type id must match " + ID.pattern() + ", got '" + t.id() + "'");
            if (t.id().equals("entity")) // untyped projection ids are entity:<value>; a type named entity would collide
                throw new IllegalArgumentException("entity type id 'entity' is reserved (untyped ids are entity:<value>)");
            if (!ids.add(t.id())) throw new IllegalArgumentException("duplicate entity type id '" + t.id() + "'");
            if (t.label().isBlank()) throw new IllegalArgumentException("entity type '" + t.id() + "': label is blank");
            if (t.family() != null && !familyIds().contains(t.family()))
                throw new IllegalArgumentException("entity type '" + t.id() + "': family must be one of " + familyIds()
                        + ", got '" + t.family() + "'");
            if (t.definition() != null && t.definition().length() > MAX_DEFINITION)
                throw new IllegalArgumentException("entity type '" + t.id() + "': definition is longer than " + MAX_DEFINITION + " characters");
            if (t.example() != null && t.example().length() > MAX_EXAMPLE)
                throw new IllegalArgumentException("entity type '" + t.id() + "': example is longer than " + MAX_EXAMPLE + " characters");
            if (t.shape() != null) {
                try {
                    ValueShapes.validate(t.shape());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("entity type '" + t.id() + "': " + e.getMessage());
                }
            }
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

    /**
     * The wire/TOON shape of {@code types}: one {@code {id, label, normaliser, masked, classifications}} each, plus
     * {@code family}, {@code definition}, {@code example} and {@code shape} where stated.
     */
    public static List<Map<String, Object>> shape(List<EntityType> types) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EntityType t : types) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("label", t.label());
            m.put("normaliser", t.normaliser());
            m.put("masked", t.masked());
            m.put("classifications", t.classifications());
            putGuidance(m, t);
            out.add(m);
        }
        return out;
    }

    /** Adds {@code family}, {@code definition}, {@code example} and {@code shape} to {@code m}, each only when stated. */
    public static void putGuidance(Map<String, Object> m, EntityType t) {
        if (t.family() != null) m.put("family", t.family());
        if (t.definition() != null) m.put("definition", t.definition());
        if (t.example() != null) m.put("example", t.example());
        if (t.shape() != null) m.put("shape", t.shape());
    }
}
