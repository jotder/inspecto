package com.gamma.entitystore;

import java.util.EnumMap;
import java.util.Map;

/**
 * The Link Analysis limits in force for one Space ({@link LaLimits#forSpace}): every {@link LaLimit} resolves to the
 * value stated for it (its Space's {@code link-analysis.toon} for a {@link LaLimit.Layer#SPACE} item, the server-wide
 * {@code la-limits.toon} for an {@link LaLimit.Layer#INSTALL} one), else its shipped default. Only values the item
 * {@link LaLimit#accepts} are ever held as stated: a stored value outside the item's range reads as the default.
 */
public final class LaLimitValues {

    /** No stated value anywhere: every item at its shipped default. */
    public static final LaLimitValues DEFAULTS = new LaLimitValues(new EnumMap<>(LaLimit.class));

    private final EnumMap<LaLimit, Number> stated;

    LaLimitValues(Map<LaLimit, Number> stated) {
        this.stated = stated.isEmpty() ? new EnumMap<>(LaLimit.class) : new EnumMap<>(stated);
    }

    /** The value stated for {@code l} (the wire number), or {@code null} = inherit the default. */
    public Number stated(LaLimit l) {
        return stated.get(l);
    }

    /** The value in force (the wire number): the stated one, else the default; {@code null} only for a {@link LaLimit#nullable} item. */
    public Number inForce(LaLimit l) {
        Number v = stated.get(l);
        return v != null ? v : l.defaultValue();
    }

    /** The whole-number value in force. ⚠ A {@link LaLimit#nullable} item without a value has none: read it with {@link #getNullable}. */
    public long get(LaLimit l) {
        if (l.decimal()) throw new IllegalArgumentException("'" + l.path() + "' is a decimal limit; read it with getDecimal");
        Number v = inForce(l);
        if (v == null) throw new IllegalStateException("'" + l.path() + "' has no value in force; read it with getNullable");
        return v.longValue();
    }

    /** The value in force of a {@link LaLimit#nullable} item, or {@code null} = no threshold. */
    public Long getNullable(LaLimit l) {
        Number v = inForce(l);
        return v == null ? null : v.longValue();
    }

    /** The value in force of a {@link LaLimit#decimal} item (a rate per second). */
    public double getDecimal(LaLimit l) {
        return inForce(l).doubleValue();
    }
}
