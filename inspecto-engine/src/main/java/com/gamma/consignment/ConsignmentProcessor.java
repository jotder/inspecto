package com.gamma.consignment;

import com.gamma.api.PublicApi;

/**
 * <b>§14.2 — what a third party implements.</b> One unit of Consignment-scoped work: given a
 * {@link ProcessorContext} already narrowed to one Consignment, do something and report.
 *
 * <p><b>Authors never touch {@code Job} or {@code JobContext}.</b> Discovery is the sanctioned
 * {@link java.util.ServiceLoader} SPI (declare this interface in {@code META-INF/services}), exactly as
 * {@code CollectorConnector} is discovered; a {@code consignment.process} Job config then selects one by
 * {@link #id()}. The framework's built-in {@code ConsignmentProcessJobType} adapter is the only thing that
 * knows about Jobs, Signals or parameter resolution — which is what keeps this interface two methods wide.
 *
 * <p>Implementations must be safe to invoke repeatedly: a Consignment can be reprocessed (§5.3), and a run can
 * be replayed after a crash.
 */
@PublicApi(since = "5.0.0")
public interface ConsignmentProcessor {

    /** The id a Job config selects this processor by. Stable — it is configuration, not a display name. */
    String id();

    /**
     * Process one Consignment. Throw to fail the run — the Job framework already converts a thrown exception
     * into a {@code FAILED} run with the message recorded, so there is no error-return to get wrong.
     */
    ProcessorResult process(ProcessorContext ctx) throws Exception;
}
