package com.gamma.control;

import com.gamma.util.OperationsZone;

/**
 * Parsing an operator-typed time bound, anchored in the {@linkplain OperationsZone operations zone}.
 *
 * <p>Extracted from {@code EventRoutes} in EDG-01 cell 6 (2026-09-08) because it stopped being one route's
 * private helper: the {@code /events*} feed moved to the optional {@code inspecto-events} module, while
 * {@link AuditLogRoutes} kept an audit read in core, and both parse bounds the same way. A second copy
 * would be a defect rather than a duplication — a bound read in a different zone hands back a window
 * silently offset by the difference, which is the whole point of the class.
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public final class TimeBounds {
    private TimeBounds() {}

    /**
     * Parse a time bound as epoch millis (all-digits) or a {@code yyyy-MM-dd[ HH:mm:ss]} string; null when
     * blank.
     *
     * <p>A bare local timestamp is an <b>operator's</b> wall clock — someone typed "from 2026-08-15" into a
     * console — so it is anchored in the {@linkplain OperationsZone operations zone}, the same zone their
     * schedule and {@code $today} already resolve in. ⚠ Resolved OUTSIDE the try: a misconfigured
     * {@code -Dops.timezone} throws, and inside it that would be caught as {@code RuntimeException} and
     * reported as a 400 blaming the query the operator just typed.
     */
    public static Long epochMillis(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (t.chars().allMatch(Character::isDigit)) {
            try { return Long.parseLong(t); } catch (NumberFormatException ignore) { return null; }
        }
        java.time.ZoneId zone = OperationsZone.resolve();
        try {
            String norm = (t.length() <= 10 ? t + " 00:00:00" : t.replace('T', ' ')).substring(0, 19);
            return java.time.LocalDateTime.parse(norm,
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(zone).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            throw new ApiException(400, "invalid time '" + s + "' (use epoch millis or yyyy-MM-dd[ HH:mm:ss])");
        }
    }
}
