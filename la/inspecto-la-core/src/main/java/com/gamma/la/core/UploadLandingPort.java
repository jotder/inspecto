package com.gamma.la.core;

import com.gamma.spi.http.ApiContext;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The upload-landing PORT of Link Analysis (LA-ANALYST-UPLOAD-1, D-UP1): where an analyst's file may land. Only a Collector
 * that says so ({@code collector.accepts_upload: true}, local connector, a poll directory, not Dataset-fed) is a target, so
 * the analyst never names a Pipeline, a schema, a store or a path - the Collector's own Pipeline parses, types, masks and
 * quarantines what lands. The bridge ({@code inspecto-geo-link}'s {@code HostUploadLandingPort}) implements it, registered
 * through {@code META-INF/services/com.gamma.la.core.UploadLandingPort}; find the active one through {@link UploadLandingPorts}.
 */
public interface UploadLandingPort {

    /**
     * A Collector an analyst may land a file in.
     *
     * @param collector the Collector id
     * @param pipeline  the Pipeline that owns it
     * @param inbox     the absolute, normalised poll directory
     * @param includes  the Collector's include patterns ({@code glob:} / {@code regex:} / bare), as configured
     * @param excludes  its exclude patterns
     */
    record Target(String collector, String pipeline, Path inbox, List<String> includes, List<String> excludes) {
        public Target {
            includes = includes == null ? List.of() : List.copyOf(includes);
            excludes = excludes == null ? List.of() : List.copyOf(excludes);
        }

        /** Whether the Collector would pick up a file of this bare name: matches an include and no exclude. A file it would not
         *  pick up must be refused, or it would sit in the inbox unprocessed. */
        public boolean accepts(String name) {
            return matchesAny(includes, name) && !matchesAny(excludes, name);
        }

        private static boolean matchesAny(List<String> patterns, String name) {
            for (String p : patterns) if (matches(p, name)) return true;
            return false;
        }

        private static boolean matches(String pattern, String name) {
            try {
                String p = pattern.trim();
                if (p.startsWith("regex:")) return Pattern.compile(p.substring(6)).matcher(name).matches();
                if (p.startsWith("glob:")) p = p.substring(5);
                Path file = Path.of(name);
                if (matcher(p).matches(file)) return true;
                // a leading any-depth segment also covers a file directly in the inbox ("**/X_*.csv" matches "X_1.csv")
                return p.startsWith("**/") && matcher(p.substring(3)).matches(file);
            } catch (RuntimeException badPattern) {
                return false;   // an unreadable pattern accepts nothing: fail closed
            }
        }

        private static PathMatcher matcher(String glob) {
            return FileSystems.getDefault().getPathMatcher("glob:" + glob);
        }
    }

    /** The Collector {@code collector} (by Collector id, else Pipeline name) as an upload target, or empty when it is unknown,
     *  does not accept uploads, is not a local poll Collector, or is Dataset-fed - the caller cannot tell which. */
    Optional<Target> target(ApiContext api, String collector);

    /** Every Collector that accepts uploads (what {@link #target} would answer for), by Collector id. */
    List<Target> targets(ApiContext api);

    /**
     * Where a landed file is in the Collector's own ingest, as far as the filesystem says (LA-I2-GAPS-RESIDUALS-1).
     *
     * @param state        {@code pending} (still in the inbox, not yet processed) | {@code ingested} (the Collector's
     *                     duplicate-check marker exists) | {@code quarantined} (the whole file was moved to quarantine) |
     *                     {@code consumed} (left the inbox, no marker or quarantine record - duplicate check is off)
     * @param rejectedRows rows the Pipeline refused into the per-file reject ledger, or {@code null} when there is none
     * @param reason       the quarantine reason folder for {@code quarantined}, else {@code null}
     */
    record Ingest(String state, Long rejectedRows, String reason) {}

    /** The ingest state of {@code file}, an upload to {@code target}. Metadata only - never a row or a header value. */
    Ingest ingest(ApiContext api, Target target, String file);
}
