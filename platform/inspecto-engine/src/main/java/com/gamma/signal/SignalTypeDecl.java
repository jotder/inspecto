package com.gamma.signal;

import com.gamma.api.PublicApi;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The template of a {@link Signal} (SIGNAL-BOARD-1 §11.2): its dotted {@code type}, a human {@code title}, what
 * posts it ({@code job-type:<id>}, {@code engine}, {@code step}, {@code agent} or {@code exchange}), the payload
 * {@code fields} it carries and the version it appeared in. {@code type} may name a family, {@code prefix.*}, when
 * the last part is chosen at run time; a family matches every type that starts with {@code prefix.}.
 */
@PublicApi(since = "4.0.0")
public record SignalTypeDecl(String type, String title, String poster, List<SignalField> fields, String since) {

    /** Lowercase dotted words: at least two parts, each a letter or digit then letters, digits, {@code -} or {@code _}. */
    private static final Pattern DOTTED = Pattern.compile("[a-z0-9][a-z0-9_-]*(\\.[a-z0-9][a-z0-9_-]*)+");

    public SignalTypeDecl {
        if (type == null || !(isDotted(type) || isFamily(type)))
            throw new IllegalArgumentException("Signal type '" + type + "' is not a dotted name or a prefix.* family");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("Signal type '" + type + "' needs a title");
        if (poster == null || poster.isBlank()) throw new IllegalArgumentException("Signal type '" + type + "' needs a poster");
        fields = fields == null ? List.of() : List.copyOf(fields);
        Set<String> seen = new HashSet<>();
        for (SignalField f : fields)
            if (!seen.add(f.name()))
                throw new IllegalArgumentException("Signal type '" + type + "' declares field '" + f.name() + "' twice");
    }

    /** Is {@code type} a dotted Signal type name (not a family)? */
    public static boolean isDotted(String type) {
        return type != null && DOTTED.matcher(type).matches();
    }

    /** Is {@code type} a {@code prefix.*} family whose prefix is itself one or more dotted words? */
    private static boolean isFamily(String type) {
        if (!type.endsWith(".*")) return false;
        String prefix = type.substring(0, type.length() - 2);
        return DOTTED.matcher(prefix).matches() || prefix.matches("[a-z0-9][a-z0-9_-]*");
    }

    /** Does this declaration name a family ({@code prefix.*})? */
    public boolean family() { return type.endsWith(".*"); }

    /** Does this declaration cover {@code signalType}: the same name, or a member of this family? */
    public boolean covers(String signalType) {
        if (signalType == null) return false;
        if (!family()) return type.equals(signalType);
        String prefix = type.substring(0, type.length() - 1);   // keeps the trailing dot
        return signalType.startsWith(prefix) && signalType.length() > prefix.length();
    }

    /** The declared field called {@code name}, or {@code null}. */
    public SignalField field(String name) {
        for (SignalField f : fields) if (f.name().equals(name)) return f;
        return null;
    }

    /** JSON view for the API (without {@code source}, which the catalog adds). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("poster", poster);
        m.put("fields", fields.stream().map(SignalField::toMap).toList());
        m.put("since", since);
        return m;
    }
}
