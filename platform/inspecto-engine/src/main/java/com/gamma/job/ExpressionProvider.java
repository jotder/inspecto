package com.gamma.job;

import java.util.List;
import java.util.Optional;

/**
 * One contributor to the Expression vocabulary (job-parameter-contract §4.1) — the SPI that replaced the
 * hardcoded {@code deduce()} switch in {@link ParameterResolver}. Mirrors {@link JobTypeProvider}: the
 * built-ins register through it, classpath modules and Job Packs contribute through it, so a new token is
 * added by <em>registration</em>, never by editing the engine.
 *
 * <p>{@link #declarations()} is the catalog: every token this provider answers for, exactly once.
 * {@link #evaluate} is asked only for expressions the {@code ExpressionRegistry} has already routed to
 * this provider by one of those declarations; an empty return means "declared but unresolvable in this
 * context" (e.g. {@code $job.last_success_time} before the first success).
 */
public interface ExpressionProvider {

    List<ExpressionDecl> declarations();

    Optional<String> evaluate(String expr, ExpressionContext ctx);
}
