package com.gamma.job;

import com.gamma.alert.Alert;
import com.gamma.alert.AlertAccess;
import com.gamma.notify.MailAccess;
import com.gamma.notify.Notification;
import com.gamma.notify.NotificationAccess;
import com.gamma.objects.IncidentAccess;
import com.gamma.objects.ObjectAccess;
import com.gamma.util.RunLog;
import com.gamma.workflow.ObjectType;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.util.List;
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
                return Optional.<String>empty();
            });
        }
        if (type == AlertAccess.class) {
            // Evaluation is not a read: a breach fires an Alert, advances its cooldown and may open an
            // Incident. So a dry run must not evaluate at all — and a consumer that reports the empty
            // result as "nothing breached" would be lying, which is why AlertAccess says so in its javadoc.
            return Optional.of((T) (AlertAccess) () -> {
                log.info("dry run: would evaluate this space's Alert Rules — nothing was checked");
                return List.<Alert>of();
            });
        }
        if (type == com.gamma.linkindex.LinkIndexAccess.class) {
            // A build writes a new index version: no preview form. Reporting DRY_RUN (not UP_TO_DATE) keeps a
            // consumer from claiming an index that was never checked is current.
            return Optional.of((T) (com.gamma.linkindex.LinkIndexAccess) req -> {
                log.info("dry run: would build the Link Analysis Index - nothing was built", "job", req.job());
                return new com.gamma.linkindex.LinkIndexAccess.Outcome(
                        com.gamma.linkindex.LinkIndexAccess.Outcome.DRY_RUN, null, null,
                        "dry run: nothing was built", 0L, 0L, 0);
            });
        }
        if (type == MailAccess.class) {
            // Recipients by COUNT, never by address, and never the body: a Run Log is widely readable (the same
            // convention mail.send follows for a real send). false = nothing was sent, so no consumer can
            // report a delivery that never happened.
            return Optional.of((T) (MailAccess) (to, cc, subject, body, attachments) -> {
                log.info("dry run: would send mail - nothing was sent", "recipients", recipientCount(to, cc),
                        "subject", subject, "attachments", attachments == null ? 0 : attachments.size());
                return false;
            });
        }
        if (type == ObjectAccess.class) {
            return Optional.of((T) new DryRunObjects((ObjectAccess) real.get(), log));
        }
        // S3-1: a pack-contributed mutating service substitutes through its own mandatory stand-in.
        if (granted instanceof PlatformServiceRegistry.StandIns s) {
            Optional<Object> standIn = s.standIn(type, log);
            if (standIn.isPresent()) return Optional.of((T) standIn.get());
        }
        return real;   // read-only services are unaffected by a dry run
    }

    @Override
    public Set<Class<?>> granted() {
        return granted.granted();
    }

    /** Distinct non-blank To + Cc addresses — the count {@code MailAccess.overChannels()} would address. */
    private static int recipientCount(List<String> to, List<String> cc) {
        return (int) java.util.stream.Stream.of(to, cc)
                .filter(java.util.Objects::nonNull).flatMap(List::stream)
                .filter(a -> a != null && !a.isBlank()).map(String::trim).distinct().count();
    }

    /**
     * The {@code objects} stand-in: every read answers from the real service, every write logs the would-be
     * effect and changes nothing. It is deliberately NOT the real implementation's class, so a consumer that
     * downcasts the seam to reach the engine behind it ({@code OpsJobTypes.engineFor}) finds nothing under a
     * dry run instead of escaping it.
     */
    private record DryRunObjects(ObjectAccess real, RunLog log) implements ObjectAccess {

        // ── reads: pass through ──
        @Override public boolean hasActive(ObjectType kind, String scope) { return real.hasActive(kind, scope); }
        @Override public boolean hasActiveMatching(ObjectType kind, String scope, Map<String, String> match) {
            return real.hasActiveMatching(kind, scope, match);
        }
        @Override public Map<String, String> activeAttributeIndex(ObjectType kind, String scope, String attribute) {
            return real.activeAttributeIndex(kind, scope, attribute);
        }
        @Override public List<String> tagsOf(String targetKind, String targetId) { return real.tagsOf(targetKind, targetId); }
        @Override public List<String> targetIdsForTag(String tag, String targetKind) {
            return real.targetIdsForTag(tag, targetKind);
        }
        @Override public Optional<Map<String, Object>> summary(String objectId) { return real.summary(objectId); }
        @Override public List<Map<String, Object>> findByStatus(ObjectType kind, String status) {
            return real.findByStatus(kind, status);
        }
        @Override public long countByStatus(ObjectType kind, String status) { return real.countByStatus(kind, status); }
        @Override public List<Map<String, Object>> activeDetail(String rawType) { return real.activeDetail(rawType); }
        @Override public Map<String, Object> byEntity(Path writeRoot, HttpExchange ex, String type, String key,
                                                      String status, int limit) {
            return real.byEntity(writeRoot, ex, type, key, status, limit);
        }

        // ── writes: record, never act ──
        /** A synthetic id marked {@code dry-run:} — it names no stored object, so every read of it is empty. */
        @Override public String open(ObjectType kind, String title, String description, String severity,
                                     String scope, Map<String, String> attributes) {
            log.info("dry run: would open object - nothing was opened", "kind", kind, "title", title,
                    "severity", severity, "scope", scope);
            return "dry-run:" + java.util.UUID.randomUUID();
        }
        /** {@code false} — the seam's "nothing done" answer, so no consumer reports a transition that never ran. */
        @Override public boolean transition(String objectId, String action, String actor) {
            log.info("dry run: would transition object - nothing changed", "object", objectId, "action", action);
            return false;
        }
        @Override public boolean saveAttributes(String objectId, Map<String, String> attributes, String actor, String what) {
            log.info("dry run: would save object attributes - nothing changed", "object", objectId, "what", what,
                    "attributes", attributes == null ? 0 : attributes.size());
            return false;
        }
        @Override public void link(String fromId, String toId, String relationship, String actor) {
            log.info("dry run: would link objects - nothing linked", "from", fromId, "to", toId,
                    "relationship", relationship);
        }
        @Override public void linkSubject(String fromId, String subjectKind, String subjectId, String relationship,
                                          String actor) {
            log.info("dry run: would link object to subject - nothing linked", "from", fromId,
                    "subjectKind", subjectKind, "subject", subjectId, "relationship", relationship);
        }
        @Override public void addTag(String tag, String targetKind, String targetId, String actor) {
            log.info("dry run: would tag - nothing tagged", "tag", tag, "targetKind", targetKind, "target", targetId);
        }
        @Override public void ensureTag(String name) {
            log.info("dry run: would register tag - nothing registered", "tag", name);
        }
    }
}
