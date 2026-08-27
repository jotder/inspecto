package com.gamma.ops.findings;

import com.gamma.api.PublicApi;
import com.gamma.ops.ObjectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The deployment-authored description of an operational object's <b>Findings</b> sections (C3 / BACKLOG
 * D6) — what fields the Findings panel shows, in what disclosure tier, with what choices.
 *
 * <p>Findings were a fixed {@code disposition}/{@code impactAmount}/{@code recordsAffected}/{@code summary}
 * shape hardcoded in the Angular panel, with {@code mail-model.ts} recording "a deployment-configurable
 * list is the documented follow-up". This is that follow-up: a spec is persisted as a
 * {@code findings-spec} component per space (so it inherits the generic {@code /components} CRUD,
 * ETags and version history — see {@code ComponentStore.WRITABLE_TYPES}) and served as the
 * <em>effective</em> spec by {@code GET /findings/{type}}.
 *
 * <p><b>Absent a component the built-in {@link #defaultFor(ObjectType)} applies</b>, so an existing
 * deployment sees no change until it authors one — the {@code NotificationRules} overlay idiom, not the
 * {@code *_workflow.toon} whole-replace-at-boot idiom. A <em>present</em> spec fully replaces the default
 * for its type; field-level merge is deliberately unsupported because it makes "remove a section"
 * inexpressible.
 *
 * <p><b>Vocabulary.</b> A section is authored in the frontend {@code AttributeSpec} vocabulary
 * ({@code key}/{@code label}/{@code type}/{@code tier}/…) and served verbatim, so
 * {@code <inspecto-schema-form>} consumes it without translation. The backend {@code ConfigSpec}/
 * {@code FieldSpec} family is deliberately <em>not</em> involved — it is compiled-in metadata for
 * pipeline/Studio config types, and mapping between the two shapes would be lossy for no reuse
 * (see {@code docs/superpower/findings-spec-plan.md} §2.1).
 *
 * <p>Validation is <b>fail-closed at authoring time</b>: {@link #fromMap} throws
 * {@link IllegalArgumentException} (→ 422) for anything the renderer could not draw, including unknown
 * keys — a typo'd {@code tier} silently defaulting is how a required field becomes invisible.
 *
 * @since 4.6.0
 */
@PublicApi(since = "4.0.0")
public record FindingsSpec(String objectType, List<Section> sections) {

    /** Renderer-supported control types — the {@code AttributeType} union in {@code attribute-spec.ts}. */
    public static final Set<String> TYPES =
            Set.of("string", "identifier", "number", "boolean", "select", "autocomplete", "multiline",
                    "list");

    /** Disclosure tiers — {@code AttributeTier}: always visible / collapsed / behind the gear. */
    public static final Set<String> TIERS = Set.of("required", "optional", "advanced");

    /** Keys accepted on a section. Anything else is rejected rather than ignored. */
    private static final Set<String> SECTION_KEYS =
            Set.of("key", "label", "type", "tier", "required", "default", "options", "pattern",
                    "min", "max", "dependsOn", "help", "placeholder");

    public FindingsSpec {
        objectType = objectType == null ? "" : objectType.trim().toLowerCase(Locale.ROOT);
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /** One Findings field. Mirrors {@code AttributeSpec}; {@code required} is boxed so "unset" stays
     *  distinguishable from {@code false} (the renderer derives it from {@code tier} when unset). */
    public record Section(String key, String label, String type, String tier, Boolean required,
                          Object defaultValue, List<Option> options, String pattern,
                          Double min, Double max, DependsOn dependsOn, String help, String placeholder) {

        public Section {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /** A {@code select} choice. */
    public record Option(String value, String label) {}

    /** A conditional-visibility clause: show this section while {@code key} does ({@code equals}) or does
     *  not ({@code negated}) hold {@code value}. Exactly one sense, matching {@code AttributeSpec.dependsOn}. */
    public record DependsOn(String key, Object value, boolean negated) {}

    // ── the built-in default (today's hardcoded panel, as data) ──────────────────

    /** The disposition ladder shipped in {@code mail-model.ts} ({@code CASE_DISPOSITIONS}). */
    private static final List<String> DISPOSITIONS =
            List.of("CONFIRMED", "FALSE_POSITIVE", "RECOVERED", "WRITTEN_OFF", "INCONCLUSIVE");

    /**
     * The built-in Findings shape — exactly what the panel renders today, so an unconfigured deployment
     * is unchanged. Every section is {@code tier=required} (always visible) but
     * {@code required=false} (nothing is mandatory), which is how the panel behaves now: the
     * no-disposition prompt on resolve is a <em>soft</em> warning, not a validation error.
     */
    public static FindingsSpec defaultFor(ObjectType type) {
        List<Option> dispositions = DISPOSITIONS.stream()
                .map(d -> new Option(d, humanize(d))).toList();
        return new FindingsSpec(type == null ? "case" : type.name().toLowerCase(Locale.ROOT), List.of(
                section("disposition", "Disposition", "select", dispositions),
                section("impactAmount", "Impact amount", "string", List.of()),
                section("recordsAffected", "Records affected", "string", List.of()),
                section("summary", "Summary", "multiline", List.of())));
    }

    private static Section section(String key, String label, String type, List<Option> options) {
        return new Section(key, label, type, "required", false, null, options,
                null, null, null, null, null, null);
    }

    /** {@code FALSE_POSITIVE} → {@code "False positive"} — the label the UI shows for a ladder value. */
    private static String humanize(String enumName) {
        String s = enumName.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ── parse + validate ────────────────────────────────────────────────────────

    /**
     * Parse + validate a spec from a stored/request map ({@code {name|objectType, sections:[…]}}).
     * Rejects (→ 422): no sections; a blank or duplicate {@code key}; an unknown {@code type} or
     * {@code tier}; a {@code select} with no {@code options}; an unparseable {@code pattern};
     * {@code min > max}; a {@code dependsOn} naming no sibling section; and any unknown section key.
     */
    public static FindingsSpec fromMap(Map<String, Object> m) {
        if (m == null) throw new IllegalArgumentException("a findings spec body is required");
        String objectType = str(m, "objectType") != null ? str(m, "objectType") : str(m, "name");
        if (objectType == null)
            throw new IllegalArgumentException("objectType (or name) is required, e.g. 'incident' or 'case'");
        ObjectType.of(objectType);   // throws IllegalArgumentException on an unknown type

        if (!(m.get("sections") instanceof List<?> raw) || raw.isEmpty())
            throw new IllegalArgumentException("sections must be a non-empty list");

        List<Section> sections = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> sm))
                throw new IllegalArgumentException("each section must be an object");
            Section s = section(cast(sm));
            if (!seen.add(s.key()))
                throw new IllegalArgumentException("duplicate section key '" + s.key() + "'");
            sections.add(s);
        }
        // dependsOn is resolved only once every key is known, so forward references are legal.
        for (Section s : sections) {
            if (s.dependsOn() != null && !seen.contains(s.dependsOn().key()))
                throw new IllegalArgumentException("section '" + s.key() + "' dependsOn unknown key '"
                        + s.dependsOn().key() + "'");
        }
        return new FindingsSpec(objectType, sections);
    }

    private static Section section(Map<String, Object> m) {
        for (String k : m.keySet()) {
            if (!SECTION_KEYS.contains(k))
                throw new IllegalArgumentException("unknown section key '" + k + "' (expected one of "
                        + new java.util.TreeSet<>(SECTION_KEYS) + ")");
        }
        String key = str(m, "key");
        if (key == null) throw new IllegalArgumentException("every section needs a key");

        String type = str(m, "type") == null ? "string" : str(m, "type").toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type))
            throw new IllegalArgumentException("section '" + key + "' has unknown type '" + type
                    + "' (expected one of " + new java.util.TreeSet<>(TYPES) + ")");

        String tier = str(m, "tier") == null ? "optional" : str(m, "tier").toLowerCase(Locale.ROOT);
        if (!TIERS.contains(tier))
            throw new IllegalArgumentException("section '" + key + "' has unknown tier '" + tier
                    + "' (expected one of " + new java.util.TreeSet<>(TIERS) + ")");

        List<Option> options = options(key, m.get("options"));
        if ("select".equals(type) && options.isEmpty())
            throw new IllegalArgumentException("section '" + key + "' is a select and needs options");

        String pattern = str(m, "pattern");
        if (pattern != null) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException bad) {
                throw new IllegalArgumentException("section '" + key + "' has an invalid pattern: "
                        + bad.getDescription());
            }
        }

        Double min = num(m, "min"), max = num(m, "max");
        if (min != null && max != null && min > max)
            throw new IllegalArgumentException("section '" + key + "' has min > max");

        Boolean required = m.get("required") instanceof Boolean b ? b : null;
        return new Section(key, str(m, "label") == null ? key : str(m, "label"), type, tier, required,
                m.get("default"), options, pattern, min, max, dependsOn(key, m.get("dependsOn")),
                str(m, "help"), str(m, "placeholder"));
    }

    private static List<Option> options(String sectionKey, Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list))
            throw new IllegalArgumentException("section '" + sectionKey + "' options must be a list");
        List<Option> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> om) {
                Map<String, Object> map = cast(om);
                String value = str(map, "value");
                if (value == null)
                    throw new IllegalArgumentException("section '" + sectionKey + "' has an option with no value");
                String label = str(map, "label");
                out.add(new Option(value, label == null ? value : label));
            } else if (o != null && !o.toString().isBlank()) {
                out.add(new Option(o.toString(), o.toString()));   // a bare string list is a legal shorthand
            } else {
                throw new IllegalArgumentException("section '" + sectionKey + "' has a blank option");
            }
        }
        return out;
    }

    private static DependsOn dependsOn(String sectionKey, Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> dm))
            throw new IllegalArgumentException("section '" + sectionKey + "' dependsOn must be an object");
        Map<String, Object> m = cast(dm);
        String key = str(m, "key");
        if (key == null)
            throw new IllegalArgumentException("section '" + sectionKey + "' dependsOn needs a key");
        boolean hasEquals = m.containsKey("equals"), hasNot = m.containsKey("notEquals");
        if (hasEquals == hasNot)
            throw new IllegalArgumentException("section '" + sectionKey
                    + "' dependsOn needs exactly one of equals / notEquals");
        return new DependsOn(key, hasEquals ? m.get("equals") : m.get("notEquals"), hasNot);
    }

    // ── validate submitted values (BACKLOG D6 residual) ─────────────────────────

    /**
     * Fail-closed check of a submitted Findings blob against this spec, throwing
     * {@link IllegalArgumentException} (→ 422) on the first violation. Findings land as plain
     * {@code attributes} strings on {@code PATCH /objects/{id}}, so without this a non-UI writer can store
     * a value the form could never have produced.
     *
     * <p>Two deliberate scoping rules, because {@code attributes} is a <b>shared</b> bag (it also carries
     * {@code tags}, {@code caseType}, {@code dueAt}, …) and the PATCH is a <b>merge</b>:
     * <ul>
     *   <li><b>Undeclared keys are not rejected.</b> A key no section declares cannot be told apart from a
     *       non-Findings attribute, so rejecting it would break tagging and every other attribute writer.
     *       What is enforced is that a <em>declared</em> key holds a value the renderer could produce.</li>
     *   <li><b>Nothing is checked unless the patch touches at least one declared key</b>, and
     *       {@code required} is then judged against the <em>merged</em> result — a partial save cannot be
     *       tested against a form it never claimed to submit, and an unrelated attribute write must not
     *       start failing because a triage form was left incomplete.</li>
     * </ul>
     * A section hidden by its {@link DependsOn} against the merged bag is skipped entirely — the form never
     * showed it, so it cannot be required.
     *
     * @param submitted the attributes bag as sent
     * @param merged    the bag as it will be stored (stored ∪ submitted)
     */
    public void validateValues(Map<String, String> submitted, Map<String, String> merged) {
        if (submitted == null || submitted.isEmpty()) return;
        boolean touches = sections.stream().anyMatch(s -> submitted.containsKey(s.key()));
        if (!touches) return;
        for (Section s : sections) {
            if (hidden(s, merged)) continue;
            String value = merged == null ? null : merged.get(s.key());
            if (value == null || value.isBlank()) {
                if (Boolean.TRUE.equals(s.required()))
                    throw new IllegalArgumentException("findings field '" + s.key() + "' ("
                            + s.label() + ") is required");
                continue;
            }
            if (!submitted.containsKey(s.key())) continue;   // an untouched stored value is not re-judged
            checkValue(s, value.trim());
        }
    }

    /** Whether {@code s} is conditionally hidden by its {@code dependsOn} against the merged bag. */
    private static boolean hidden(Section s, Map<String, String> merged) {
        DependsOn d = s.dependsOn();
        if (d == null) return false;
        String other = merged == null ? null : merged.get(d.key());
        boolean equal = String.valueOf(d.value()).equals(other == null ? "" : other.trim());
        return d.negated() == equal;
    }

    private static void checkValue(Section s, String value) {
        switch (s.type()) {
            case "number" -> {
                double n;
                try {
                    n = Double.parseDouble(value);
                } catch (NumberFormatException bad) {
                    throw new IllegalArgumentException("findings field '" + s.key() + "' must be a number, got '"
                            + value + "'");
                }
                if (s.min() != null && n < s.min())
                    throw new IllegalArgumentException("findings field '" + s.key() + "' must be >= " + s.min());
                if (s.max() != null && n > s.max())
                    throw new IllegalArgumentException("findings field '" + s.key() + "' must be <= " + s.max());
            }
            case "boolean" -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
                    throw new IllegalArgumentException("findings field '" + s.key()
                            + "' must be true or false, got '" + value + "'");
            }
            // Only `select` is a closed set; `autocomplete` options are suggestions, so a free value is legal.
            case "select" -> {
                if (s.options().stream().noneMatch(o -> o.value().equals(value)))
                    throw new IllegalArgumentException("findings field '" + s.key() + "' must be one of "
                            + s.options().stream().map(Option::value).toList() + ", got '" + value + "'");
            }
            default -> { }
        }
        if (s.pattern() != null && !Pattern.compile(s.pattern()).matcher(value).matches())
            throw new IllegalArgumentException("findings field '" + s.key() + "' does not match "
                    + s.pattern());
    }

    // ── serialize ───────────────────────────────────────────────────────────────

    /** The wire shape {@code <inspecto-schema-form>} consumes (also the persisted TOON content). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", objectType);          // the component id, so file stem / URL id / content agree
        m.put("objectType", objectType);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Section s : sections) out.add(sectionMap(s));
        m.put("sections", out);
        return m;
    }

    private static Map<String, Object> sectionMap(Section s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", s.key());
        m.put("label", s.label());
        m.put("type", s.type());
        m.put("tier", s.tier());
        if (s.required() != null) m.put("required", s.required());
        if (s.defaultValue() != null) m.put("default", s.defaultValue());
        if (!s.options().isEmpty()) {
            List<Map<String, Object>> opts = new ArrayList<>();
            for (Option o : s.options()) opts.add(Map.of("value", o.value(), "label", o.label()));
            m.put("options", opts);
        }
        if (s.pattern() != null) m.put("pattern", s.pattern());
        if (s.min() != null) m.put("min", s.min());
        if (s.max() != null) m.put("max", s.max());
        if (s.dependsOn() != null) {
            m.put("dependsOn", Map.of("key", s.dependsOn().key(),
                    s.dependsOn().negated() ? "notEquals" : "equals", s.dependsOn().value()));
        }
        if (s.help() != null) m.put("help", s.help());
        if (s.placeholder() != null) m.put("placeholder", s.placeholder());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) { return (Map<String, Object>) m; }

    private static Double num(Map<String, Object> m, String key) {
        return m.get(key) instanceof Number n ? n.doubleValue() : null;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v == null || v.toString().isBlank()) ? null : v.toString().trim();
    }
}
