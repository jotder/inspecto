package com.gamma.util;

import com.gamma.api.PublicApi;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * The <b>operations zone</b> — the wall-clock zone the operator's schedule is expressed in. It governs
 * when a cron trigger fires ({@code PipelineScheduler}, {@code JobService}) and what date the
 * {@code $today}/{@code $yesterday}/{@code $day(-1)} macros resolve to ({@code ExpressionContext}).
 *
 * <h3>⚠ Not the data zone</h3>
 * This is deliberately <b>not</b> {@code meta.domain.timezone}. That key is a catalog annotation
 * describing what zone the <em>data's</em> timestamps are in — a domain note, whose real consumer is the
 * consignment event-time cut (§5.6/§10.1). The two routinely differ: data landed in UTC while the
 * operator sits in {@code Asia/Kolkata} and wants a 06:00 local run. One value cannot answer both
 * questions, and making the catalog note govern execution would also make firing depend on
 * <b>directory scan order</b> — a space may hold any number of {@code *_meta.toon} files and
 * {@code MetadataGraphService} merges them last-non-blank-wins.
 *
 * <h3>Default and posture</h3>
 * Unset ⇒ {@link ZoneId#systemDefault()}, i.e. byte-identical to the behaviour before this existed, which
 * is why introducing it shifts no existing schedule and needs no migration. Set-but-unresolvable
 * <b>throws</b> rather than falling back: a silent fallback would run every schedule in the wrong zone
 * while the operator believed otherwise, and a typo is indistinguishable from intent. That matches the
 * fail-loud posture {@code ConfigSpecs.meta()}'s {@code domain-timezone-resolvable} rule already takes
 * for the descriptive key.
 *
 * <p>⛔ Deliberately <b>no picker and no cached field</b>: resolution is a pure function of the property,
 * so a test can set it, and callers resolve once at construction (the zone is fixed for a service's life).
 */
@PublicApi(since = "5.0.0")
public final class OperationsZone {

    private OperationsZone() {}

    /** The JVM property carrying the operations zone, e.g. {@code -Dops.timezone=Asia/Kolkata}. */
    public static final String PROPERTY = "ops.timezone";

    /**
     * The configured operations zone, or the JVM default when unset/blank.
     *
     * @throws IllegalArgumentException when the property is set to something this JVM cannot resolve —
     *                                  the message names both the property and the offending value, which
     *                                  the descriptive key's {@code CrossFieldRule} cannot do.
     */
    public static ZoneId resolve() {
        String raw = System.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) return ZoneId.systemDefault();
        String id = raw.trim();
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    "-D" + PROPERTY + "=" + id + " is not a zone this JVM can resolve — use an IANA id "
                            + "such as 'Asia/Kolkata' or 'UTC'", e);
        }
    }
}
