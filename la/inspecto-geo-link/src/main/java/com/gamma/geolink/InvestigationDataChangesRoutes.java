package com.gamma.geolink;

import com.gamma.control.HostContext;
import com.gamma.la.api.InvestigationRoutes;
import com.gamma.la.core.CollectorCoveragePort;
import com.gamma.la.core.CollectorCoveragePorts;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.InvestigationStore;
import com.gamma.signal.DeliveryAnomalySignal;
import com.gamma.signal.Signal;
import com.gamma.signal.Signals;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * <b>Corrected-day warning</b> (LA-DATA-TRUST-1 S5): {@code GET /inv/investigations/{id}/data-changes} lists the
 * {@code collector.delivery.anomaly} Signals ({@code REDELIVERED}, {@code RENAMED_CORRECTION}, {@code PART_COUNT_CHANGED},
 * {@code DUPLICATE_SKIPPED}) that moved a day inside the Investigation's window AFTER the analyst last looked — so the
 * Investigation can say "day D was corrected after you last looked" and prompt a refresh. Selection: {@link DataChanges}.
 *
 * <p><b>How a Signal is tied to the Dataset.</b> The Signal names its {@code pipeline}; the Dataset is tied to Pipelines by the
 * SAME attribution the coverage route uses ({@link CollectorCoveragePort}: the Collectors whose Consignment lineage wrote into
 * the Dataset's stores), mapped back to Pipelines through each Pipeline's Collector id. A Collector with several Pipelines
 * therefore attributes all of them (a possible over-report, never a miss). Without that evidence the answer is
 * {@code assessed:false}, never "no changes".
 *
 * <p><b>Access:</b> exactly {@code GET …/coverage} — {@link InvestigationRoutes#openForRead} (owner or linked-Case member,
 * R3 Dataset gate, Enterprise PDP), a read with no capability gate; the feature gate is {@code geoLink}. Persists nothing.
 */
public final class InvestigationDataChangesRoutes implements RouteModule {

    /** A page of Signals is bounded by the store; a window with more anomalies than this reports {@code capped:true}. */
    static final int PAGE = 500;

    @Override
    public Set<String> featureIds() {
        return Set.of("geoLink");
    }

    @Override
    public void register(ApiContext api) {
        api.get("/inv/investigations/([^/]+)/data-changes", (e, m) -> dataChanges(api, e, m.group(1)));
    }

    @SuppressWarnings("unchecked")
    private Object dataChanges(ApiContext api, HttpExchange ex, String id) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, id);
        List<Map<String, Object>> log = new ArrayList<>();
        for (String line : inv.store().log(InvestigationStore.Scope.main(id))) log.add(ApiContext.JSON.readValue(line, Map.class));
        Map<String, Object> window = InvestigationEvaluator.evaluate(log, -1, null).window;
        Instant lastLook = DataChanges.lastLook(inv.header(), log);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("dataset", inv.dataset());
        out.put("window", window);
        out.put("lastLookAt", lastLook.toString());
        Optional<Set<String>> pipelines = pipelinesOf(api, inv);
        if (pipelines.isEmpty()) {
            out.put("assessed", false);
            out.put("note", "data changes are not assessed: no Consignment lineage attributes this Dataset's stores to a Pipeline");
            out.put("changes", List.of());
            out.put("changedDays", List.of());
            out.put("capped", false);
            out.put("readAt", Instant.now().toString());
            return out;
        }
        List<Signal> found = Signals.query(HostContext.of(api).service().events(), DeliveryAnomalySignal.TYPE,
                lastLook.toEpochMilli(), null, null, null, PAGE);
        List<Map<String, Object>> rows = new ArrayList<>();
        TreeSet<String> days = new TreeSet<>();
        for (DataChanges.Change c : DataChanges.select(found, pipelines.get(), window, lastLook)) {
            rows.add(c.toMap());
            days.add(c.day());
        }
        out.put("assessed", true);
        out.put("pipelines", new ArrayList<>(new TreeSet<>(pipelines.get())));
        out.put("changes", rows);
        out.put("changedDays", new ArrayList<>(days));
        out.put("capped", found.size() >= PAGE);
        out.put("readAt", Instant.now().toString());
        return out;
    }

    /** The Pipelines whose Collector delivered into the Dataset's stores, or empty when nothing can be attributed. */
    private static Optional<Set<String>> pipelinesOf(ApiContext api, InvestigationRoutes.Inv inv) {
        Optional<Map<String, Object>> ds = DatasetProviders.require().dataset(inv.writeRoot(), inv.dataset());
        Optional<List<CollectorCoveragePort.Delivery>> found = ds.isEmpty() ? Optional.empty()
                : CollectorCoveragePorts.active().flatMap(p -> p.deliveries(api, ds.get()));
        if (found.isEmpty()) return Optional.empty();
        Set<String> collectors = new HashSet<>();
        for (CollectorCoveragePort.Delivery d : found.get()) collectors.add(d.collector());
        var service = HostContext.of(api).service();
        Set<String> out = new HashSet<>();
        for (var pv : service.pipelines())
            service.configFor(pv.name()).ifPresent(cfg -> {
                if (collectors.contains(cfg.collector().id())) out.add(pv.name());
            });
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }
}
