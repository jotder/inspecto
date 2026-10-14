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

    /**
     * One File a Pipeline ingested towards a Dataset (LA-DATA-TRUST-1 S1), from the Consignment audit trail.
     *
     * @param name          the File's name as the status ledger recorded it
     * @param day           the event day ({@code yyyy-MM-dd}) of its output partition, or null when it wrote none (a quarantined or empty File)
     * @param rows          rows the File contributed (0 for a File that was read and held no rows - distinct from no File at all)
     * @param errorRows     rows it lost; {@code -1} when the ledger did not say
     * @param ingestedAt    when its Consignment finished (the commit time, NOT the arrival time), or null
     * @param outcome       {@code SUCCESS}, {@code PARTIAL} (committed with error rows), {@code QUARANTINED}, {@code FAILED}, or the ledger's own word
     * @param collector     the Pipeline's Collector id
     * @param pipeline      the Pipeline that ingested it
     * @param consignmentId its Consignment, or null
     * @param outputFiles   the partition files it wrote, as the lineage ledger names them (path separators and any data-root prefix NOT normalised)
     */
    record FileEntry(String name, String day, long rows, long errorRows, String ingestedAt, String outcome,
                     String collector, String pipeline, String consignmentId, List<String> outputFiles) {
        public FileEntry {
            outputFiles = outputFiles == null ? List.of() : List.copyOf(outputFiles);
        }
    }

    /** Every File recorded towards {@code dataset}, or empty when none can be attributed. Default: not supported. */
    default Optional<List<FileEntry>> files(ApiContext api, Map<String, Object> dataset) {
        return Optional.empty();
    }
}
