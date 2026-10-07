package com.gamma.alert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The heap {@link AlertStore}: tests and the explicit {@code -Dalerts.backend=memory} opt-out. Lost on restart —
 * which is exactly why the durable {@link DbAlertStore} is the default on every edition.
 */
public final class InMemoryAlertStore implements AlertStore {

    private final Map<String, Row> rows = new LinkedHashMap<>();

    @Override public synchronized void insert(Row row) { rows.put(row.id(), row); }

    @Override public synchronized boolean transition(String alertId, String action, String actor) {
        Row cur = rows.get(alertId);
        if (cur == null) return false;
        Optional<String> next = WORKFLOW.apply(cur.state(), action);
        if (next.isEmpty()) return false;
        long closed = WORKFLOW.isTerminal(next.get()) ? System.currentTimeMillis() : cur.closedAt();
        rows.put(alertId, new Row(cur.id(), cur.title(), cur.message(), cur.severity(), cur.scope(),
                cur.attributes(), next.get(), cur.openedAt(), closed,
                WORKFLOW.isTerminal(next.get()) ? actor : cur.closedBy(), cur.incidentId(), cur.fired()));
        return true;
    }

    @Override public synchronized List<Row> active(String scope) {
        String s = scope == null ? "" : scope;
        return rows.values().stream().filter(r -> s.equals(r.scope()) && !WORKFLOW.isTerminal(r.state())).toList().reversed();
    }

    @Override public synchronized List<Row> allActive() {
        return rows.values().stream().filter(r -> !WORKFLOW.isTerminal(r.state())).toList().reversed();
    }

    @Override public synchronized void linkIncident(String alertId, String incidentId) {
        Row cur = rows.get(alertId);
        if (cur == null) return;
        rows.put(alertId, new Row(cur.id(), cur.title(), cur.message(), cur.severity(), cur.scope(),
                cur.attributes(), cur.state(), cur.openedAt(), cur.closedAt(), cur.closedBy(), incidentId, cur.fired()));
    }

    @Override public synchronized Optional<Row> get(String alertId) { return Optional.ofNullable(rows.get(alertId)); }

    @Override public synchronized List<Row> recentRows(int limit) {
        List<Row> out = new ArrayList<>();
        List<Row> all = new ArrayList<>(rows.values());
        for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--)
            if (all.get(i).fired() != null) out.add(all.get(i));
        return out;
    }

    @Override public synchronized long size() { return rows.size(); }
}
