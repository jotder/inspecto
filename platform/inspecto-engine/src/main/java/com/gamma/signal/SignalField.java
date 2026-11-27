package com.gamma.signal;

import com.gamma.api.PublicApi;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One payload field of a {@link SignalTypeDecl} (SIGNAL-BOARD-1 §11.2): its key in {@link Signal#payload()}, its
 * {@link Kind}, whether it is present on every posting ({@code always}) and what it means. A {@code when} or
 * {@code bind} on a field that is not {@code always} may find it absent.
 */
@PublicApi(since = "4.0.0")
public record SignalField(String name, Kind kind, boolean always, String description) {

    /** The JSON shape of a field's value. {@code number} is any decimal; {@code integer} a whole number. */
    public enum Kind {
        STRING, INTEGER, NUMBER, BOOLEAN, LIST, MAP;

        /** The lowercase name the API and the docs use. */
        public String wire() { return name().toLowerCase(Locale.ROOT); }
    }

    public SignalField {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("a Signal field needs a name");
        if (kind == null) throw new IllegalArgumentException("Signal field '" + name + "' needs a kind");
        description = description == null ? "" : description;
    }

    /** A field present on every posting of its type. */
    public static SignalField always(String name, Kind kind, String description) {
        return new SignalField(name, kind, true, description);
    }

    /** A field present on some postings only. */
    public static SignalField optional(String name, Kind kind, String description) {
        return new SignalField(name, kind, false, description);
    }

    /** JSON view for the API. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("kind", kind.wire());
        m.put("always", always);
        m.put("description", description);
        return m;
    }
}
