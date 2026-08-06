package com.gamma.job;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;

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
 */
public record ExpressionContext(String runId, Instant fireTime, String actor, ZoneId zone,
                                Supplier<Optional<LocalDateTime>> lastSuccess,
                                BiFunction<String, String, Optional<RunArtifact>> upstream,
                                Map<String, Object> signalPayload) {}
