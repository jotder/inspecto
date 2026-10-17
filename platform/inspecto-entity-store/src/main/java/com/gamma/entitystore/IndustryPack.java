package com.gamma.entitystore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One <b>Industry pack</b> of Inspecto LA (LA-EDITIONS-1 §3, settles {@code LDP-D7}): a vertical's vocabulary and
 * defaults mapped onto the neutral Link Analysis concepts. A Space names the pack it reads in as its <b>Domain
 * Profile</b> ({@code domain_profile} in {@code link-analysis.toon}); none named = the neutral vocabulary.
 *
 * <p>⛔ <b>A pack relabels; it never adds a concept.</b> Every term is keyed by a canonical concept key, and every key
 * must name something the core already has:
 * <ul>
 *   <li>{@code concept.<name>} — one of {@link #CONCEPTS} (the working-set tiles and the two ends of a link);</li>
 *   <li>{@code entity_type.<id>} — a core Entity Type ({@link EntityTypes#DEFAULTS});</li>
 *   <li>{@code link_kind.<id>} — a link kind the pack's own {@code link_kinds} list declares (link kinds are data values);</li>
 *   <li>{@code measure.<id>} — a Value Measure ({@link #MEASURES});</li>
 *   <li>{@code pattern.<id>} — a pattern pack the pack's own {@code pattern_packs} list references;</li>
 *   <li>{@code term.<id>} — a glossary entry only (a typology such as a fraud scheme): shown in the pack's term list,
 *       relabels nothing.</li>
 * </ul>
 * The pack's Entity Types restate core types (normaliser from the closed {@link EntityTypes#NORMALISERS} set, masking,
 * classifications); an id the core does not have is refused. Everything is validated at load and a pack that fails
 * any rule is refused whole ({@link IndustryPacks}), never partly applied.
 */
public record IndustryPack(String id, String name, String description, List<String> spaceTemplates,
                           List<String> patternPacks, Profile profile, List<Term> terms,
                           List<EntityTypes.EntityType> entityTypes, List<String> linkKinds) {

    public IndustryPack {
        spaceTemplates = List.copyOf(spaceTemplates);
        patternPacks = List.copyOf(patternPacks);
        terms = List.copyOf(terms);
        entityTypes = List.copyOf(entityTypes);
        linkKinds = List.copyOf(linkKinds);
    }

    /** One relabel: the display name, an optional abbreviation and the hover definition of a canonical concept key. */
    public record Term(String key, String label, String abbreviation, String definition) {}

    /**
     * How Link Analysis reads a projected graph under this pack: column-name hints for the measure and the time axis
     * (case-insensitive patterns, in preference order), the toolbox groups to badge, and the optional "Investigate a
     * number" binding ({@code null} = not offered).
     */
    public record Profile(List<String> measureHints, List<String> timeHints, List<String> suggestedTools,
                          Map<String, Object> investigate) {
        public Profile {
            measureHints = List.copyOf(measureHints);
            timeHints = List.copyOf(timeHints);
            suggestedTools = List.copyOf(suggestedTools);
            investigate = investigate == null ? null : Map.copyOf(investigate);
        }
    }

    /** The {@code concept.*} keys and their neutral labels (what a Space without a pack reads). */
    public static final Map<String, String> CONCEPTS = orderedMap(
            "node", "Nodes", "link", "Links", "row", "Folded rows", "link_from", "From", "link_to", "To");

    /** The Value Measure ids a {@code measure.*} key may name — pinned equal to la-api's {@code ValueMeasures.ALERTABLE}. */
    public static final Set<String> MEASURES = Set.of("passThrough", "velocity", "timeToCashOut", "cashOutConcentration",
            "structuring", "benefitTransfer");

    /** The id a Space states for "no pack": the neutral vocabulary. Never a pack id. */
    public static final String GENERIC = "generic";

    static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");
    /** A link-kind id: the Entity Type id shape. */
    private static final Pattern KIND = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final Pattern HINT = Pattern.compile("^[A-Za-z0-9_^$]{1,32}$");
    private static final Pattern TOOL = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    private static final int MAX_LABEL = 60;
    private static final int MAX_ABBREVIATION = 16;
    private static final int MAX_DEFINITION = 400;
    private static final int MAX_TERMS = 200;
    private static final Set<String> INVESTIGATE_STRINGS = Set.of("entity_type", "dataset", "source_col", "target_col",
            "link_kind_col", "time_col", "events_col");
    private static final Set<String> INVESTIGATE_NUMBERS = Set.of("window_days", "min_events", "max_fan_out", "budget",
            "max_degree");

    /**
     * Parse and validate a pack from its four decoded files. {@code dirId} is the directory the files were read from;
     * the manifest's {@code id} must equal it. Throws {@link IllegalArgumentException} naming the first problem.
     */
    public static IndustryPack parse(String dirId, Map<String, Object> manifest, Map<String, Object> termsDoc,
                                     Map<String, Object> entityTypesDoc, Map<String, Object> linkKindsDoc) {
        String id = str(manifest, "id");
        if (!id.equals(dirId)) throw new IllegalArgumentException("manifest id '" + id + "' differs from its directory '" + dirId + "'");
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("pack id must match " + ID.pattern() + ", got '" + id + "'");
        if (id.equals(GENERIC)) throw new IllegalArgumentException("pack id 'generic' is reserved for the neutral vocabulary");
        String name = bounded(str(manifest, "name"), "name", MAX_LABEL);
        String description = bounded(str(manifest, "description"), "description", MAX_DEFINITION);
        List<String> templates = refs(manifest.get("space_templates"), "space_templates");
        List<String> patterns = refs(manifest.get("pattern_packs"), "pattern_packs");
        Profile profile = profile(manifest.get("profile"));

        List<String> kinds = new ArrayList<>();
        for (String k : strings(linkKindsDoc.get("link_kinds"), "link_kinds")) {
            if (!KIND.matcher(k).matches()) throw new IllegalArgumentException("link kind id must match ^[a-z][a-z0-9_]{0,31}$, got '" + k + "'");
            if (kinds.contains(k)) throw new IllegalArgumentException("link kind '" + k + "' is listed twice");
            kinds.add(k);
        }

        List<EntityTypes.EntityType> types = new ArrayList<>();
        Set<String> coreIds = new HashSet<>();
        for (EntityTypes.EntityType t : EntityTypes.DEFAULTS) coreIds.add(t.id());
        Set<String> seenTypes = new HashSet<>();
        for (Object o : list(entityTypesDoc.get("entity_types"), "entity_types")) {
            if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException("each entity type must be an object");
            String tid = str(m, "id");
            if (!coreIds.contains(tid))
                throw new IllegalArgumentException("entity type '" + tid + "' is not a core Entity Type - a pack relabels, it never adds one");
            if (!seenTypes.add(tid)) throw new IllegalArgumentException("entity type '" + tid + "' is listed twice");
            String normaliser = str(m, "normaliser");
            if (!EntityTypes.NORMALISERS.contains(normaliser))
                throw new IllegalArgumentException("entity type '" + tid + "': normaliser must be one of " + EntityTypes.NORMALISERS
                        + ", got '" + normaliser + "'");
            if (!(m.get("masked") instanceof Boolean masked))
                throw new IllegalArgumentException("entity type '" + tid + "': masked must be true or false");
            List<String> cls = strings(m.get("classifications"), "entity type '" + tid + "' classifications");
            String coreLabel = EntityTypes.DEFAULTS.stream().filter(t -> t.id().equals(tid)).findFirst().orElseThrow().label();
            types.add(new EntityTypes.EntityType(tid, coreLabel, normaliser, masked, cls));
        }

        List<Term> terms = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        List<?> rawTerms = list(termsDoc.get("terms"), "terms");
        if (rawTerms.size() > MAX_TERMS) throw new IllegalArgumentException("at most " + MAX_TERMS + " terms, got " + rawTerms.size());
        for (Object o : rawTerms) {
            if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException("each term must be an object");
            String key = str(m, "key");
            if (!keys.add(key)) throw new IllegalArgumentException("term '" + key + "' is listed twice");
            checkKey(key, coreIds, kinds, patterns);
            String label = bounded(str(m, "label"), "term '" + key + "' label", MAX_LABEL);
            String abbreviation = m.get("abbreviation") == null ? "" : bounded(optStr(m, "abbreviation"), "term '" + key + "' abbreviation", MAX_ABBREVIATION);
            String definition = bounded(str(m, "definition"), "term '" + key + "' definition", MAX_DEFINITION);
            String neutral = neutralLabel(key);
            if (neutral != null && neutral.equalsIgnoreCase(label))
                throw new IllegalArgumentException("term '" + key + "' relabels to its neutral label '" + neutral + "' - drop it");
            terms.add(new Term(key, label, abbreviation, definition));
        }
        IndustryPack pack = new IndustryPack(id, name, description, templates, patterns, profile, terms, types, kinds);
        EntityTypes.validate(pack.overlay(EntityTypes.DEFAULTS), Integer.MAX_VALUE);
        return pack;
    }

    /** The core's neutral label for a {@code concept.*} or {@code entity_type.*} key; {@code null} for every other key. */
    public static String neutralLabel(String key) {
        if (key.startsWith("concept.")) return CONCEPTS.get(key.substring("concept.".length()));
        if (key.startsWith("entity_type.")) {
            String id = key.substring("entity_type.".length());
            return EntityTypes.DEFAULTS.stream().filter(t -> t.id().equals(id)).map(EntityTypes.EntityType::label).findFirst().orElse(null);
        }
        return null;
    }

    private static void checkKey(String key, Set<String> coreIds, List<String> kinds, List<String> patterns) {
        int dot = key.indexOf('.');
        String ns = dot < 0 ? "" : key.substring(0, dot);
        String rest = dot < 0 ? "" : key.substring(dot + 1);
        boolean ok = switch (ns) {
            case "concept" -> CONCEPTS.containsKey(rest);
            case "entity_type" -> coreIds.contains(rest);
            case "link_kind" -> kinds.contains(rest);
            case "measure" -> MEASURES.contains(rest);
            case "pattern" -> patterns.contains(rest);
            case "term" -> ID.matcher(rest).matches();
            default -> false;
        };
        if (!ok)
            throw new IllegalArgumentException("term '" + key + "' names no core concept (concept.* " + CONCEPTS.keySet()
                    + ", entity_type.<core type>, link_kind.<listed kind>, measure.<Value Measure>, pattern.<listed pattern pack>"
                    + " or term.<id>) - a pack relabels, it never adds a concept");
    }

    /** The term for {@code key}, or {@code null}. */
    public Term term(String key) {
        for (Term t : terms) if (t.key().equals(key)) return t;
        return null;
    }

    /**
     * {@code base} as this pack reads it: each type the pack restates takes its normaliser, masking and classifications,
     * and each type with an {@code entity_type.*} term takes that label. Order and membership are {@code base}'s.
     */
    public List<EntityTypes.EntityType> overlay(List<EntityTypes.EntityType> base) {
        List<EntityTypes.EntityType> out = new ArrayList<>();
        for (EntityTypes.EntityType b : base) {
            EntityTypes.EntityType t = entityTypes.stream().filter(p -> p.id().equals(b.id())).findFirst().orElse(b);
            Term term = term("entity_type." + b.id());
            out.add(new EntityTypes.EntityType(b.id(), term != null ? term.label() : b.label(), t.normaliser(), t.masked(),
                    t.classifications()));
        }
        return List.copyOf(out);
    }

    /** The wire shape {@code GET /settings/link-analysis/domain-profile} answers for this pack. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        m.put("spaceTemplates", spaceTemplates);
        m.put("patternPacks", patternPacks);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("measureHints", profile.measureHints());
        p.put("timeHints", profile.timeHints());
        p.put("suggestedTools", profile.suggestedTools());
        if (profile.investigate() != null) {
            Map<String, Object> inv = new LinkedHashMap<>();
            for (String k : List.of("entity_type", "dataset", "source_col", "target_col", "link_kind_col", "time_col",
                    "events_col", "window_days", "min_events", "max_fan_out", "budget", "max_degree"))
                if (profile.investigate().get(k) != null) inv.put(camel(k), profile.investigate().get(k));
            p.put("investigate", inv);
        }
        m.put("profile", p);
        List<Map<String, Object>> ts = new ArrayList<>();
        for (Term t : terms) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("key", t.key());
            x.put("label", t.label());
            x.put("abbreviation", t.abbreviation());
            x.put("definition", t.definition());
            ts.add(x);
        }
        m.put("terms", ts);
        m.put("entityTypes", EntityTypes.shape(overlay(EntityTypes.DEFAULTS)));
        m.put("linkKinds", linkKinds);
        return m;
    }

    private static String camel(String snake) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') up = true;
            else {
                b.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return b.toString();
    }

    private static Profile profile(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) throw new IllegalArgumentException("the manifest needs a 'profile' block");
        List<String> measure = hints(m.get("measure_hints"), "profile.measure_hints");
        List<String> time = hints(m.get("time_hints"), "profile.time_hints");
        List<String> tools = new ArrayList<>();
        for (String t : strings(m.get("suggested_tools"), "profile.suggested_tools")) {
            if (!TOOL.matcher(t).matches()) throw new IllegalArgumentException("profile.suggested_tools: '" + t + "' is not a tool id");
            tools.add(t);
        }
        Map<String, Object> inv = null;
        Object rawInv = m.get("investigate");
        if (rawInv != null) {
            if (!(rawInv instanceof Map<?, ?> im)) throw new IllegalArgumentException("profile.investigate must be a block");
            inv = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : im.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (INVESTIGATE_STRINGS.contains(k)) {
                    if (!(e.getValue() instanceof String s) || !REF.matcher(s).matches())
                        throw new IllegalArgumentException("profile.investigate." + k + " must be an identifier");
                    inv.put(k, s);
                } else if (INVESTIGATE_NUMBERS.contains(k)) {
                    if (!(e.getValue() instanceof Number n) || n.doubleValue() < 0 || n.doubleValue() != Math.floor(n.doubleValue())
                            || n.longValue() > 1_000_000)
                        throw new IllegalArgumentException("profile.investigate." + k + " must be a whole number from 0 to 1000000");
                    inv.put(k, n.longValue());
                } else throw new IllegalArgumentException("profile.investigate: unknown key '" + k + "'");
            }
            for (String req : List.of("entity_type", "dataset", "source_col", "target_col"))
                if (!inv.containsKey(req)) throw new IllegalArgumentException("profile.investigate needs '" + req + "'");
            Object type = inv.get("entity_type");
            if (EntityTypes.DEFAULTS.stream().noneMatch(t -> t.id().equals(type)))
                throw new IllegalArgumentException("profile.investigate.entity_type '" + type + "' is not a core Entity Type");
        }
        return new Profile(measure, time, tools, inv);
    }

    private static List<String> hints(Object raw, String what) {
        List<String> out = strings(raw, what);
        for (String h : out)
            if (!HINT.matcher(h).matches()) throw new IllegalArgumentException(what + ": '" + h + "' must match " + HINT.pattern());
        return out;
    }

    private static List<String> refs(Object raw, String what) {
        List<String> out = strings(raw, what);
        for (String r : out) if (!REF.matcher(r).matches()) throw new IllegalArgumentException(what + ": '" + r + "' is not a name");
        return out;
    }

    private static List<?> list(Object raw, String what) {
        if (!(raw instanceof List<?> l)) throw new IllegalArgumentException("'" + what + "' must be a list");
        return l;
    }

    private static List<String> strings(Object raw, String what) {
        List<String> out = new ArrayList<>();
        for (Object o : list(raw, what)) {
            if (!(o instanceof String s) || s.isBlank()) throw new IllegalArgumentException("'" + what + "' must hold non-blank strings");
            out.add(s.trim());
        }
        return out;
    }

    private static String str(Map<?, ?> m, String key) {
        if (!(m.get(key) instanceof String s) || s.isBlank()) throw new IllegalArgumentException("'" + key + "' must be a non-blank string");
        return s.trim();
    }

    private static String optStr(Map<?, ?> m, String key) {
        if (!(m.get(key) instanceof String s)) throw new IllegalArgumentException("'" + key + "' must be a string");
        return s.trim();
    }

    private static String bounded(String s, String what, int max) {
        if (s.length() > max) throw new IllegalArgumentException(what + " is longer than " + max + " characters");
        return s;
    }

    private static Map<String, String> orderedMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }
}
