package com.gamma.alert;

import com.gamma.api.PublicApi;

import java.util.List;
import java.util.function.Supplier;

/**
 * Platform Service {@code alerts}: run this space's authored Alert Rules and get back what breached.
 * Granted to a Run via a Job Type's {@code requires: [alerts]} declaration and looked up as
 * {@code ctx.services().get(AlertAccess.class)}; the engine itself is the first consumer — the
 * {@code alert.evaluate} built-in reaches the evaluator only through this grant.
 *
 * <h3>Why the menu carries an evaluator and not a "raise an alert" call</h3>
 * Detection is declarative: an Alert Rule states {@code metric}/{@code window}/{@code threshold} and
 * {@link AlertService} owns the window arithmetic, the severity mapping, the cooldown and the
 * Alert→Incident promotion. A plugin that wanted to raise something directly wants
 * {@link com.gamma.ops.IncidentAccess} or a Signal, not this. This service exists because
 * {@code alert.evaluate} needs a **clock over the existing rules** — the demand the v1 menu
 * deliberately waited for (platform-services **D7**: the Job could not honestly declare
 * {@code incidents}, because the Incidents it causes are opened inside {@code AlertService}, not by
 * the Job).
 *
 * <h3>Dry-run contract (plan §3.4)</h3>
 * ⚠ **Evaluation mutates and cannot be previewed**: a breach fires an Alert, advances that rule's
 * cooldown and may open an Incident — the detection *is* the action. Under a dry run the framework
 * therefore substitutes a recording stand-in that logs the would-be evaluation and returns empty,
 * and a consumer must report that nothing was checked rather than "nothing breached".
 *
 * @since 5.1.0
 */
@PublicApi(since = "4.0.0")
public interface AlertAccess {

    /**
     * Evaluate every authored Alert Rule now and return the Alerts this pass fired — empty when
     * nothing breached, when every breach is still inside its cooldown, or under a dry run.
     */
    List<Alert> evaluateRules();

    /** The production implementation over an {@link AlertService}, resolved lazily so boot wiring can
     *  register the service before the Alert engine is constructed. An absent engine fails loudly:
     *  evaluation is the whole work, so reporting success would report health that was never checked. */
    static AlertAccess over(Supplier<AlertService> alerts) {
        return () -> {
            AlertService svc = alerts.get();
            if (svc == null)
                throw new IllegalStateException("the alerts Platform Service has no Alert engine in this space");
            return svc.evaluateRules();
        };
    }
}
