package com.gamma.decision;

import com.gamma.objects.ObjectAccess;

import java.util.Map;
import java.util.Optional;

/**
 * What a {@link ConsequenceProvider} may touch: the applied rule and the matched record, plus the narrow host
 * services a consequence needs. The host ({@code DecisionRoutes}) implements it; {@code HostContext} /
 * {@code ApiContext} never cross the SPI.
 */
public interface ConsequenceContext {

    String ruleName();

    /** The person who applied the rule; null when the engine did. */
    String actor();

    /** True when the engine fired the rule, false when a person applied it. Always passed explicitly. */
    boolean automatic();

    /** The whole stored rule. */
    Map<String, Object> rule();

    /** The matched row an {@code emit-signal} payload mapping reads from (empty when none was supplied). */
    Map<String, Object> record();

    /** Operational objects (Incidents); empty on a bundle without the ops module. */
    Optional<ObjectAccess> objects();

    /** Whether the host service {@code id} ({@code objects}, {@code jobs}) is available — what {@link ConsequenceProvider#requires} names. */
    boolean has(String serviceId);

    /** Emit a Signal onto this space's ledger; {@code offerTo} (nullable) names the one Space it is offered to. */
    void emitSignal(String type, String source, Map<String, Object> payload, String offerTo);

    /** Whether the named job exists and is disabled (false when unknown). */
    boolean jobDisabled(String jobId);

    /** Trigger a job run; the run id, or empty when there is no such job. */
    Optional<String> triggerJob(String jobId, String requestedBy);

    /** Trigger a pipeline run; false when there is no such pipeline. */
    boolean triggerPipeline(String pipelineId);

    /** Author an Alert Rule through the same path as {@code POST /alerts/rules}; throws on refusal. */
    void authorAlertRule(Map<String, Object> body) throws Exception;
}
