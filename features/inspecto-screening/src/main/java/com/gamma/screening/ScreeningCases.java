package com.gamma.screening;

import com.gamma.ops.ObjectService;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.link.LinkRelationship;
import com.gamma.workflow.ObjectType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A confirmed Screening Hit opens an Incident for the screened subject and a Case that CONTAINS it (SCREENING-1-D2,
 * operator 2026-10-14): the investigation continues in the Case, and the hit carries {@code caseId} / {@code incidentId}.
 * The Incident's entity attributes are the subject key and the source Dataset, the same identity Link Analysis uses, so
 * the Case reads as one more thing known about that subject. Never the screened name: it may be masked, and a Case is
 * visible to more people than a hit.
 */
final class ScreeningCases {

    private ScreeningCases() {}

    /** The Incident and Case a confirm opened. */
    record Opened(String caseId, String incidentId) {}

    /** Open both and link them; a failure part-way discards what was opened and rethrows. */
    static Opened open(ObjectService svc, Map<String, Object> hit, String actor) {
        String id = String.valueOf(hit.get("id"));
        String list = String.valueOf(hit.get("listId"));
        String subject = String.valueOf(hit.get("subjectKey"));
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put(ObjectService.ATTR_ENTITY_KEY, subject);
        if (hit.get("source") instanceof Map<?, ?> s && s.get("dataset") != null)
            attrs.put(ObjectService.ATTR_ENTITY_DATASET, String.valueOf(s.get("dataset")));
        attrs.put("screeningHit", id);
        attrs.put("screeningList", list);
        List<String> created = new java.util.ArrayList<>();
        try {
            String what = "Screening Hit " + id + ": subject " + subject + " matched Entity List " + list
                    + " (" + hit.get("method") + ", Match Score " + hit.get("score") + "), confirmed by " + actor + ".";
            OperationalObject incident = svc.open(ObjectType.INCIDENT, "Screening match: " + list + " / " + subject, what,
                    null, null, null, null, null, attrs);
            created.add(incident.id());
            OperationalObject kase = svc.open(ObjectType.CASE, "Screening: confirmed match on " + list + " for " + subject,
                    what, null, null, actor, null, null, Map.of("screeningHit", id));
            created.add(kase.id());
            svc.link(kase.id(), incident.id(), LinkRelationship.CONTAINS, actor);
            return new Opened(kase.id(), incident.id());
        } catch (RuntimeException failure) {
            discard(svc, created, actor, failure);
            throw failure;
        }
    }

    /** Undo {@link #open}: the hit could not be saved as confirmed. */
    static void discard(ObjectService svc, Opened o, String actor, RuntimeException cause) {
        discard(svc, List.of(o.incidentId(), o.caseId()), actor, cause);
    }

    private static void discard(ObjectService svc, List<String> ids, String actor, RuntimeException cause) {
        for (String id : ids.reversed()) {
            try {
                svc.substrate().discard(id, actor, cause);
            } catch (RuntimeException ignored) {
                // best effort: the original failure is what the caller sees
            }
        }
    }
}
