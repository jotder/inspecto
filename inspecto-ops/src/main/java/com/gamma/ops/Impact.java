package com.gamma.ops;

import com.gamma.objects.ObjectType;
import com.gamma.util.JsonAttributes;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The typed financial impact of an Incident or a Case (WS-10, {@code ASSURE-IMPACT-LEDGER-1}): four
 * non-negative decimal amounts — {@code suspected}, {@code confirmed}, {@code recovered}, {@code prevented} —
 * in one ISO 4217 {@code currency}, plus a free-text {@code period} and {@code basis}.
 *
 * <p><b>{@link #outstanding()} = confirmed − recovered is derived on read and never stored</b>: a body that
 * carries it is refused, and {@link #toJson()} never writes it. On a Case this IS the impact its Findings
 * carry — the Findings blob no longer holds an {@code impactAmount} of its own (no parallel field).
 *
 * <p>Stored as one JSON object in {@code attributes.impact} (the {@code postmortem} / {@code findings} idiom),
 * amounts as plain decimal strings so a round trip through the attribute bag cannot lose precision to a
 * {@code double}. Written only through {@code PUT /objects/{id}/impact}, which validates with {@link #fromBody}.
 */
public record Impact(BigDecimal suspected, BigDecimal confirmed, BigDecimal recovered, BigDecimal prevented,
                     String currency, String period, String basis) {

    /** The attribute that holds the stored impact. */
    public static final String ATTR = "impact";

    /** The object types that carry an impact. */
    public static final Set<ObjectType> TYPES = Set.of(ObjectType.INCIDENT, ObjectType.CASE);

    /** The four amounts, in ledger order. */
    public static final List<String> AMOUNTS = List.of("suspected", "confirmed", "recovered", "prevented");

    private static final Set<String> KEYS = Set.of("suspected", "confirmed", "recovered", "prevented",
            "currency", "period", "basis");
    static final int MAX_PERIOD = 64;
    static final int MAX_BASIS = 2000;
    /** Bounds on an amount: below 10^15 with at most 6 decimal places — a ledger, not a float. */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000000");
    private static final int MAX_SCALE = 6;

    /** An impact with nothing set — what an absent or cleared {@code attributes.impact} reads as. */
    public boolean isEmpty() {
        return suspected == null && confirmed == null && recovered == null && prevented == null
                && currency == null && period == null && basis == null;
    }

    /** {@code confirmed − recovered} (recovered absent counts as 0); {@code null} while nothing is confirmed. */
    public BigDecimal outstanding() {
        if (confirmed == null) return null;
        return confirmed.subtract(recovered == null ? BigDecimal.ZERO : recovered);
    }

    /** The amount named by one of {@link #AMOUNTS}, or {@code "outstanding"}. */
    public BigDecimal amount(String name) {
        return switch (name) {
            case "suspected" -> suspected;
            case "confirmed" -> confirmed;
            case "recovered" -> recovered;
            case "prevented" -> prevented;
            case "outstanding" -> outstanding();
            default -> throw new IllegalArgumentException("unknown impact amount '" + name + "'");
        };
    }

    /**
     * Parse and validate a request's {@code impact} object — fail closed ({@link IllegalArgumentException} →
     * 422): an unknown key, a supplied {@code outstanding} (derived, never stored), an amount that is not a
     * non-negative decimal within bounds, an amount with no {@code currency}, a currency that is not an
     * ISO 4217 code, or an over-long {@code period}/{@code basis}. {@code null} or {@code ""} leaves a field
     * unset, so {@code {}} clears the impact.
     */
    public static Impact fromBody(Map<?, ?> body) {
        for (Object k : body.keySet()) {
            String key = String.valueOf(k);
            if ("outstanding".equals(key))
                throw new IllegalArgumentException("'outstanding' is derived (confirmed - recovered) and is never stored");
            if (!KEYS.contains(key))
                throw new IllegalArgumentException("unknown impact field '" + key + "' — allowed: "
                        + KEYS.stream().sorted().collect(Collectors.joining(", ")));
        }
        BigDecimal suspected = amount(body, "suspected");
        BigDecimal confirmed = amount(body, "confirmed");
        BigDecimal recovered = amount(body, "recovered");
        BigDecimal prevented = amount(body, "prevented");
        String currency = text(body, "currency", 16);
        if (currency != null) {
            currency = currency.toUpperCase(Locale.ROOT);
            if (!isIsoCurrency(currency))
                throw new IllegalArgumentException("currency '" + currency + "' is not an ISO 4217 code");
        }
        boolean anyAmount = suspected != null || confirmed != null || recovered != null || prevented != null;
        if (anyAmount && currency == null)
            throw new IllegalArgumentException("currency is required when any amount is set");
        return new Impact(suspected, confirmed, recovered, prevented, currency,
                text(body, "period", MAX_PERIOD), text(body, "basis", MAX_BASIS));
    }

    /**
     * Read a stored {@code attributes.impact}: absent, blank or unreadable → empty. Lenient on purpose — what
     * is stored went through {@link #fromBody}; a hand-edited value that no longer parses reads as no impact
     * rather than failing every read of the object.
     */
    public static Impact fromAttribute(String json) {
        if (json == null || json.isBlank()) return empty();
        try {
            return fromBody(JsonAttributes.fromPayloadJson(json));
        } catch (IllegalArgumentException unreadable) {
            return empty();
        }
    }

    /** The stored impact of {@code o}, or empty when it carries none. */
    public static Optional<Impact> of(OperationalObject o) {
        Impact i = fromAttribute(o.attributes().get(ATTR));
        return i.isEmpty() ? Optional.empty() : Optional.of(i);
    }

    public static Impact empty() {
        return new Impact(null, null, null, null, null, null, null);
    }

    /** The stored form: the set fields only, amounts as plain decimal strings; {@code ""} when empty. */
    public String toJson() {
        if (isEmpty()) return "";
        Map<String, Object> m = new LinkedHashMap<>();
        for (String a : AMOUNTS) if (amount(a) != null) m.put(a, amount(a).toPlainString());
        if (currency != null) m.put("currency", currency);
        if (period != null) m.put("period", period);
        if (basis != null) m.put("basis", basis);
        return JsonAttributes.toPayloadJson(m);
    }

    /** The read view — every field (absent = {@code null}) plus the derived {@code outstanding}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String a : AMOUNTS) m.put(a, amount(a));
        m.put("outstanding", outstanding());
        m.put("currency", currency);
        m.put("period", period);
        m.put("basis", basis);
        return m;
    }

    private static BigDecimal amount(Map<?, ?> body, String key) {
        Object v = body.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) return null;
        if (!(v instanceof Number) && !(v instanceof String))
            throw new IllegalArgumentException("impact '" + key + "' must be a decimal number");
        BigDecimal d;
        try {
            d = new BigDecimal(v.toString().trim());
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("impact '" + key + "' must be a decimal number, not '" + v + "'");
        }
        if (d.signum() < 0) throw new IllegalArgumentException("impact '" + key + "' must not be negative");
        if (d.compareTo(MAX_AMOUNT) >= 0)
            throw new IllegalArgumentException("impact '" + key + "' must be below " + MAX_AMOUNT.toPlainString());
        d = d.stripTrailingZeros();
        if (d.scale() > MAX_SCALE)
            throw new IllegalArgumentException("impact '" + key + "' allows at most " + MAX_SCALE + " decimal places");
        return d.scale() < 0 ? d.setScale(0) : d;
    }

    private static String text(Map<?, ?> body, String key, int max) {
        Object v = body.get(key);
        if (v == null) return null;
        if (!(v instanceof String s)) throw new IllegalArgumentException("impact '" + key + "' must be a string");
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) throw new IllegalArgumentException("impact '" + key + "' is longer than " + max + " characters");
        return t;
    }

    private static boolean isIsoCurrency(String code) {
        if (!code.matches("[A-Z]{3}")) return false;
        return Currency.getAvailableCurrencies().stream().anyMatch(c -> c.getCurrencyCode().equals(code));
    }
}
