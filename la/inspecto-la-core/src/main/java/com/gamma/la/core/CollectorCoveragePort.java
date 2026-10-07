package com.gamma.la.core;

import com.gamma.spi.http.ApiContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Collector-attribution PORT of Link Analysis (LA-COLLECTOR-COVERAGE-1): which Collector delivered rows into a
 * Dataset's backing stores, per event day. A Dataset row carries no Collector, so the answer comes from the Consignment
 * audit trail the engine always writes - {@code batches} (Consignment to Pipeline to output store) joined to
 * {@code lineage} (Consignment to event-day partition and row count) - and the Pipeline's Collector id. The bridge
 * ({@code inspecto-geo-link}'s {@code HostCollectorCoveragePort}) implements it, registered through
 * {@code META-INF/services/com.gamma.la.core.CollectorCoveragePort}; find the active one through {@link CollectorCoveragePorts}.
 *
 * <p><b>Unbound or unattributable means "not assessed"</b>, never "no gaps".
 */
public interface CollectorCoveragePort {

    /** {@code rows} that {@code collector} delivered into the Dataset on the event day {@code day} ({@code yyyy-MM-dd}, as the Pipeline cut it). */
    record Delivery(String collector, String day, long rows) {}

    /** Every recorded delivery into the stores behind {@code dataset}, or empty when none can be attributed. */
    Optional<List<Delivery>> deliveries(ApiContext api, Map<String, Object> dataset);
}
