package com.gamma.anomaly.baseline;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Seasonality of a self baseline (design §4.2, {@code seasonality: none | weekday | hour | weekday_hour}).
 * {@link #fallbackChain()} is the order tried when a slot lacks history (§15: cell, then weekday, then none).
 */
public enum Seasonality {
    NONE, WEEKDAY, HOUR, WEEKDAY_HOUR;

    /** True when {@code bucket} falls in the same season slot as {@code scored}. */
    public boolean sameSlot(LocalDateTime bucket, LocalDateTime scored) {
        return switch (this) {
            case NONE -> true;
            case WEEKDAY -> bucket.getDayOfWeek() == scored.getDayOfWeek();
            case HOUR -> bucket.getHour() == scored.getHour();
            case WEEKDAY_HOUR -> bucket.getDayOfWeek() == scored.getDayOfWeek() && bucket.getHour() == scored.getHour();
        };
    }

    /** This seasonality, then the coarser ones it falls back to, ending in NONE. */
    public List<Seasonality> fallbackChain() {
        return switch (this) {
            case NONE -> List.of(NONE);
            case WEEKDAY -> List.of(WEEKDAY, NONE);
            case HOUR -> List.of(HOUR, NONE);
            case WEEKDAY_HOUR -> List.of(WEEKDAY_HOUR, WEEKDAY, NONE);
        };
    }

    /** Label of the slot for the explanation's {@code season}, e.g. "TUESDAY", "03h", "TUESDAY 03h", "none". */
    public String label(LocalDateTime scored) {
        return switch (this) {
            case NONE -> "none";
            case WEEKDAY -> scored.getDayOfWeek().toString();
            case HOUR -> "%02dh".formatted(scored.getHour());
            case WEEKDAY_HOUR -> scored.getDayOfWeek() + " %02dh".formatted(scored.getHour());
        };
    }
}
