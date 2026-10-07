package com.gamma.job;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import com.gamma.consignment.EventTimeBounds;

/**
 * The fire-time context an {@link ExpressionProvider} evaluates an Expression against
 * (job-parameter-contract §4.1): this Run's identity/timing/attribution and zone, the Job's success
 * watermark, an {@code (job, artifactName)} → latest {@link RunArtifact} lookup for
 * {@code $upstream(…)}, and the firing Signal's payload for {@code $signal.<field>} (empty for
 * cron/manual fires).
 *
 * <p>Public because {@link ExpressionProvider} is an SPI: a classpath provider or a Job Pack
 * contributes tokens and needs the context they resolve against. Was {@code ParameterResolver.Context}
 * until the registry seam replaced the hardcoded {@code deduce()} switch.
 *
 * @param bounds a store name → live event-time range lookup, backing
 *               {@code $upstream(…).event_time_min|event_time_max} (§5-B). A function rather than a store
 *               for the same reason {@code upstream} is one: the provider stays free of engine dependencies
 *               and a test supplies its own. Defaults to "always unknown" via the seven-arg constructor.
 */
public record ExpressionContext(String runId, Instant fireTime, String actor, ZoneId zone,
                                Supplier<Optional<LocalDateTime>> lastSuccess,
                                BiFunction<String, String, Optional<RunArtifact>> upstream,
                                Map<String, Object> signalPayload,
                                Function<String, Optional<EventTimeBounds>> bounds) {

    /**
     * As the canonical constructor with no bounds lookup — every range resolves as unknown. Keeps the call
     * sites that predate §5-B compiling unchanged, mirroring the delegating constructor {@code ParameterDecl}
     * gained for the same reason (§10 step 7).
     */
    public ExpressionContext(String runId, Instant fireTime, String actor, ZoneId zone,
                             Supplier<Optional<LocalDateTime>> lastSuccess,
                             BiFunction<String, String, Optional<RunArtifact>> upstream,
                             Map<String, Object> signalPayload) {
        this(runId, fireTime, actor, zone, lastSuccess, upstream, signalPayload, s -> Optional.empty());
    }
}
