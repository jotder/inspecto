package com.gamma.alert;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@link InMemoryAlertStore} that remembers every call — the Alert-store counterpart of
 * {@code FakeObjectAccess.opened / transitioned}, for tests that used to assert ALERT objects through the object seam
 * (MODULE-REORG-P7-INCIDENTS slice 2: an Alert is a store record now, never an object).
 */
final class RecordingAlertStore implements AlertStore {

    /** One {@link #transition} call. */
    record Moved(String alertId, String action, String actor) {}

    private final InMemoryAlertStore inner = new InMemoryAlertStore();
    final List<Row> opened = new ArrayList<>();
    final List<Moved> moved = new ArrayList<>();

    /** The ids of the Alerts that were resolved, in order. */
    List<String> resolvedIds() {
        return moved.stream().filter(m -> "resolve".equals(m.action())).map(Moved::alertId).toList();
    }

    @Override public synchronized void insert(Row row) { opened.add(row); inner.insert(row); }
    @Override public synchronized boolean transition(String alertId, String action, String actor) {
        boolean ok = inner.transition(alertId, action, actor);
        if (ok) moved.add(new Moved(alertId, action, actor));
        return ok;
    }
    @Override public List<Row> active(String scope) { return inner.active(scope); }
    @Override public List<Row> allActive() { return inner.allActive(); }
    @Override public void linkIncident(String alertId, String incidentId) { inner.linkIncident(alertId, incidentId); }
    @Override public List<Alert> recentFired(int limit) { return inner.recentFired(limit); }
    @Override public java.util.Optional<Row> get(String alertId) { return inner.get(alertId); }
    @Override public List<Row> recentRows(int limit) { return inner.recentRows(limit); }
    @Override public long size() { return inner.size(); }
    @Override public Purge purgeResolvedBefore(java.time.Instant cutoff, boolean dryRun) { return inner.purgeResolvedBefore(cutoff, dryRun); }
}
