package com.gamma.workflow;

import java.util.Map;

/**
 * The read view of anything Workflow &amp; SLA governs (plan 8a, the governed-item contract): an Incident, a Case, and
 * whatever a module later opts in. It carries exactly what the SLA / escalation decisions read - identity, the item
 * kind, the workflow state, priority, owner, the open timestamp and the free attributes the deadlines and the
 * fire-once ledger live in - and nothing the host persists. The host adapts its own record to this view and applies
 * the decisions {@link SlaDecisions} returns through its own store.
 *
 * @param kind       the governed kind (an {@link ObjectType} name for Incidents and Cases)
 * @param closed     the host's own settled flag (an Incident or Case's {@code closedAt} is set)
 * @param attributes the persisted string attributes ({@code dueAt}, {@code slaBreachedAt}, {@code escalations} ...)
 */
public record GovernedItem(String id, String kind, String status, String severity, String priority, String assignee,
                           long createdAt, boolean closed, Map<String, String> attributes) {
}
