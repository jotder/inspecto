package com.gamma.job;

import com.gamma.notify.Notification;
import com.gamma.notify.NotificationAccess;
import com.gamma.ops.IncidentAccess;
import com.gamma.ops.OperationalObject;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The dry-run view of a Run's granted {@link PlatformServices} (plan §3.4, extending MNT-1):
 * mutating services record instead of act — the stand-in logs the would-be effect to the RunLog and
 * performs nothing — while read-only services pass through unchanged. Grants themselves are
 * unaffected: an undeclared service stays invisible, dry run or not.
 *
 * <p>Every <em>mutating</em> Platform Service added to the v1 menu must be substituted here, or
 * {@code dryRun()} on the Job becomes a lie the moment the Job calls it.
 */
final class DryRunServices implements PlatformServices {

    private final PlatformServices granted;
    private final RunLog log;

    private DryRunServices(PlatformServices granted, RunLog log) {
        this.granted = granted;
        this.log = log;
    }

    /** Wrap a Run's grant for a dry run; {@code log} receives the would-be effects. */
    static PlatformServices wrap(PlatformServices granted, RunLog log) {
        return new DryRunServices(granted, log);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(Class<T> type) {
        Optional<T> real = granted.find(type);
        if (real.isEmpty()) return real;   // not granted — dry run never widens visibility
        if (type == NotificationAccess.class) {
            return Optional.of((T) (NotificationAccess) n -> {
                log.info("dry run: would emit notification", "title", n.title(),
                        "category", n.category(), "dedupeKey", n.dedupeKey());
                return Optional.<Notification>empty();
            });
        }
        if (type == IncidentAccess.class) {
            return Optional.of((T) (IncidentAccess) (title, message, severity, scope, attributes, dedupeAttribute) -> {
                log.info("dry run: would open incident", "title", title, "severity", severity,
                        "scope", scope, "dedupeAttribute", dedupeAttribute);
                return Optional.<OperationalObject>empty();
            });
        }
        return real;   // read-only services are unaffected by a dry run
    }

    @Override
    public Set<Class<?>> granted() {
        return granted.granted();
    }
}
