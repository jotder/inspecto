package com.gamma.intelligence.spi;

import com.gamma.intelligence.AgentAnswerSink;
import com.gamma.intelligence.AgentAskRequest;
import com.gamma.intelligence.AgentAskResult;
import com.gamma.intelligence.AgentSessionRequest;
import com.gamma.intelligence.AgentSessionResult;
import com.gamma.api.PublicApi;
import com.gamma.service.CollectorService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service-provider interface for the optional embedded-intelligence agent (AGT-5, P0). Successor
 * track to the reflex-layer {@link com.gamma.assist.spi.AssistAgent}: where that SPI answers one
 * single-shot skill call, this one hosts multi-turn sessions on the eoiagent deliberative loop.
 * Both stay live side by side — the reflex layer is the fallback when this module is absent.
 *
 * <p>The implementation lives in the separate {@code file-processor-intelligence} module so the
 * core fat-JAR stays dependency-lean. When that module — and a provider declared via
 * {@code META-INF/services/com.gamma.intelligence.spi.IntelligenceAgent} — is on the classpath,
 * {@link CollectorService} discovers it with {@link java.util.ServiceLoader} at startup. A provider
 * can also be supplied explicitly via {@link CollectorService#registerIntelligenceAgent} (used by
 * tests).
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #init(CollectorService)} — called once, before {@link CollectorService#start()}.</li>
 *   <li>{@link #start()} — called after the service has started.</li>
 *   <li>{@link #close()} — called on service shutdown.</li>
 * </ol>
 */
@PublicApi(since = "4.0.0")
public interface IntelligenceAgent extends AutoCloseable {

    /** Stable, human-readable name for logs (e.g. {@code "inspecto-intelligence"}). */
    String name();

    /**
     * Wire the agent to the running service. Called exactly once, before
     * {@link CollectorService#start()}. Implementations should capture only what they need and
     * return quickly; defer model/platform assembly to {@link #start()}.
     */
    void init(CollectorService service);

    /** Called after {@link CollectorService#start()}. Default no-op. */
    default void start() {}

    /** Open a new session for the caller described by {@code request}. */
    AgentSessionResult openSession(AgentSessionRequest request);

    /**
     * Ask a question on an open session. Implementations throw {@link IllegalArgumentException}
     * for an unknown/closed {@code sessionId} — the control plane maps that to HTTP 404.
     */
    AgentAskResult ask(String sessionId, AgentAskRequest request);

    /**
     * Ask a question, streaming tokens to {@code sink} as they're produced (AGT-5, hardening pass).
     * Additive default: delivers the whole {@link #ask} answer as a single {@code onComplete} call
     * (no real per-token streaming) and reports an unknown session via {@link AgentAnswerSink#onError}
     * instead of throwing — so an implementation that doesn't support streaming keeps compiling and
     * degrades to a post-hoc "stream" rather than breaking the route.
     */
    default void askStream(String sessionId, AgentAskRequest request, AgentAnswerSink sink) {
        try {
            sink.onComplete(ask(sessionId, request));
        } catch (IllegalArgumentException e) {
            sink.onError(e.getMessage());
        }
    }

    /**
     * Invoke <b>one named tool</b> from the belt directly, bypassing the deliberative loop (AGT-6a A1,
     * the inline-authoring surface). Where {@link #ask} lets the model decide which tools to call and
     * then paraphrases what they returned, this hands back the tool's own result verbatim — so a UI can
     * render a structural draft, anchored findings and a diff rather than prose. No model is involved.
     *
     * <p><b>Draft-only, enforced here.</b> Implementations MUST refuse a tool whose
     * {@code ToolSpec.mutating()} is true by throwing {@link IllegalStateException} (the control plane
     * maps that to HTTP 403). Mutating tools are reachable only through their own gated path — the
     * approval spine — and an inline box must never become a second, ungated way to act. The
     * non-mutating belt is safe by construction: those tools persist nothing.
     *
     * <p>Returns the tool's result as a plain JSON-friendly map — {@code ok}, plus {@code value} on
     * success or {@code error} on an expected failure (the tools never throw; a missing argument or an
     * unvalidatable kind is an {@code ok=false} result). Empty when no tool of that name exists, which
     * the control route maps to 404. Default empty: an implementation with no tool belt exposes nothing
     * rather than 503, mirroring {@link #recentCases}.
     *
     * @param args    the tool's arguments, as the caller supplied them
     * @param session the agent-session token for audit attribution ({@code actor=agent:<run>})
     * @throws IllegalStateException when the named tool is mutating (→ 403)
     */
    default Optional<Map<String, Object>> runTool(String name, Map<String, Object> args, String session) {
        return Optional.empty();
    }

    /**
     * {@link #runTool}, but the arguments come from <b>one operator sentence</b> (AGT-6a A5.1). The model
     * produces the tool's arguments and nothing else; the tool then runs through the same deterministic
     * path, so every property of {@code runTool} — draft-only, result verbatim, no paraphrase — survives.
     *
     * <p>Implementations MUST keep this gate order, because the surface's safety rests on it:
     * <ol>
     *   <li>unknown tool → empty ({@code 404});</li>
     *   <li><b>mutating tool → {@link IllegalStateException} BEFORE any model call</b> ({@code 403}). A
     *       refused tool must never have arguments composed for it;</li>
     *   <li>no model configured → {@link UnsupportedOperationException} ({@code 503}). This is
     *       deliberately <b>not</b> an {@code ok=false} result: "this deployment has no model" is not the
     *       operator's sentence being wrong, and telling them to rephrase would be a lie;</li>
     *   <li>malformed or absent model output → an {@code ok=false} result ({@code 422}), retryable.</li>
     * </ol>
     *
     * <p>The result is {@link #runTool}'s map plus <b>{@code derivedArgs}</b> — what the sentence became.
     * The surface shows it before the operator applies anything; with a model in the loop that echo is
     * what makes the draft reviewable rather than magic.
     *
     * @param prompt the operator's sentence
     * @param args   arguments the pane already knows (dataset, table, open component id). Applied <b>after</b>
     *               the model's, so they win — the screen knows these and a model can hallucinate them.
     * @throws IllegalStateException         when the named tool is mutating (→ 403)
     * @throws UnsupportedOperationException when no model is configured (→ 503)
     */
    default Optional<Map<String, Object>> deriveTool(String name, String prompt, Map<String, Object> args,
                                                     String session) {
        return Optional.empty();
    }

    /**
     * The most recent investigation Cases (AGT-5 P1 slice D), newest first, as plain JSON-friendly
     * maps — the core stays free of the {@code Case} record type, which lives in the optional
     * {@code file-processor-intelligence} module. Default empty: an implementation without an
     * investigation tier (or the module absent) yields no cases rather than a 503 — {@code GET
     * /agent/cases} is a read that degrades, mirroring {@code GET /assist/diagnoses}.
     */
    default List<Map<String, Object>> recentCases(int limit) {
        return List.of();
    }

    /** One Case by id, or empty when unknown (the control route maps that to 404). */
    default Optional<Map<String, Object>> caseById(String id) {
        return Optional.empty();
    }

    /**
     * The prior Cases most similar to the Case {@code id} (AGT-5 P5, case-similarity recall), newest
     * -and-most-relevant first, each carrying a {@code similarity} score — so an operator (or the
     * investigator) can reuse earlier root-cause work on a like incident. Empty when the id is unknown
     * <em>or</em> the tier is absent; the control route reads the base Case first to distinguish
     * unknown (404) from simply-no-neighbours (empty list). Default empty.
     */
    default List<Map<String, Object>> similarCases(String id, int k) {
        return List.of();
    }

    /**
     * Recent agent-action approvals (AGT-5 P3, autonomy L2), newest first, as plain JSON-friendly
     * maps — the core stays free of the {@code Approval} type, which lives in the optional
     * {@code file-processor-intelligence} module. Includes both pending and already-decided entries so
     * {@code GET /agent/approvals} can show recent history. Default empty: an implementation without an
     * act tier (or the module absent) yields no approvals rather than a 503, mirroring {@link #recentCases}.
     */
    default List<Map<String, Object>> recentApprovals(int limit) {
        return List.of();
    }

    /** One approval by id, or empty when unknown (the control route maps that to 404). */
    default Optional<Map<String, Object>> approvalById(String id) {
        return Optional.empty();
    }

    /**
     * Record an operator's decision on a pending approval (AGT-5 P3): {@code approve} resumes the
     * gated mutating tool, otherwise it is denied. Returns the updated approval view, or empty when
     * the id is unknown or already decided (the control route maps that to 404, so a lapsed or
     * double decision can never re-open an approval). Default empty for implementations without an act tier.
     */
    default Optional<Map<String, Object>> decideApproval(String id, boolean approve, String decidedBy) {
        return Optional.empty();
    }

    /**
     * The bounded-autonomy policy (AGT-5 P4, autonomy L3) as a plain JSON-friendly map — the core stays
     * free of the {@code AutonomyPolicy} type, which lives in the optional
     * {@code file-processor-intelligence} module. Empty when the implementation has no autonomy tier (or
     * the module is absent), which the control route maps to 503 — unlike the read-degrading
     * {@link #recentApprovals}, an absent policy engine is a genuine "feature not present" rather than
     * "nothing yet".
     */
    default Optional<Map<String, Object>> autonomyPolicy() {
        return Optional.empty();
    }

    /**
     * Replace the autonomy policy from an operator's {@code PUT /agent/policy} body (kill switch +
     * per-action-class mode/budget). Returns the stored view, or empty when there is no autonomy tier.
     * The write is audited by {@code ControlApi.dispatch} as any other governed mutation.
     */
    default Optional<Map<String, Object>> updateAutonomyPolicy(Map<String, Object> body, String updatedBy) {
        return Optional.empty();
    }

    /**
     * Engage or disengage the global kill switch (AGT-5 P4) — the one-call hard-off that denies every
     * autonomous action class regardless of its mode. Returns the updated policy view, or empty when
     * there is no autonomy tier.
     */
    default Optional<Map<String, Object>> setAutonomyKillSwitch(boolean engaged, String updatedBy) {
        return Optional.empty();
    }

    /**
     * The autonomy ledger (AGT-5 P4, L3) — recent decisions the {@code ops_monitor} loop reached
     * (executed, shadowed, or skipped), newest first, as plain JSON-friendly maps. The dashboard's
     * "what the agent did, why, and spend" feed. Default empty (read-degrading, like
     * {@link #recentApprovals}): an implementation without an autonomy driver yields no actions rather
     * than a 503.
     */
    default List<Map<String, Object>> recentAutonomousActions(int limit) {
        return List.of();
    }

    /** One autonomy-ledger entry by id, or empty when unknown (the control route maps that to 404). */
    default Optional<Map<String, Object>> autonomousActionById(String id) {
        return Optional.empty();
    }

    /**
     * Record an operator's feedback on an investigation Case (AGT-5 P5, "Learning") — the raw signal
     * the learning tier turns into eval growth + per-skill tuning. {@code body} carries the
     * {@code rating} (helpful / not-helpful) and an optional {@code note}. Returns the stored feedback
     * view, or empty when the {@code caseId} is unknown (the control route maps that to 404). Default
     * empty for implementations without an investigation tier.
     */
    default Optional<Map<String, Object>> recordCaseFeedback(String caseId, Map<String, Object> body, String submittedBy) {
        return Optional.empty();
    }

    /**
     * Recent Case feedback, newest first, as plain JSON-friendly maps (AGT-5 P5) — the aggregate the
     * tuning dashboard reads. Default empty (read-degrading, like {@link #recentCases}).
     */
    default List<Map<String, Object>> recentCaseFeedback(int limit) {
        return List.of();
    }

    /** Released on service shutdown. Default no-op. */
    @Override default void close() {}
}
