package com.gamma.control;

import com.gamma.consignment.ConsignmentProcessor;
import com.gamma.consignment.ProcessorContext;
import com.gamma.consignment.ProcessorResult;

/**
 * A {@link ConsignmentProcessor} deployed the way a third party deploys one — declared in
 * {@code src/test/resources/META-INF/services/com.gamma.consignment.ConsignmentProcessor} — so
 * {@link ControlApiJobProcessorsTest} can prove {@code GET /jobs/processors} serves a real classpath
 * provider over HTTP rather than only an empty list.
 *
 * <p>⚠ <b>This is the module's only registered processor, and registering it is visible to every test in
 * {@code inspecto}.</b> That is safe today because nothing else here runs a {@code consignment.process} Job;
 * a test that needs an empty catalog cannot live in this module. The per-branch semantics of the catalog
 * (shadowing, unusable providers, truncation) are proven in {@code ProcessorCatalogTest} instead, which
 * publishes its fixtures through a classloader it builds — the context classloader trick cannot work here,
 * because the handler runs on the {@code HttpServer}'s own threads, not the test's.
 *
 * <p>{@code process} is never called: a catalog read only ever asks for {@link #id()}.
 */
public final class CatalogProbeProcessor implements ConsignmentProcessor {

    /** The id a {@code consignment.process} Job config would select this by. */
    public static final String ID = "catalog.probe";

    public CatalogProbeProcessor() {}

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ProcessorResult process(ProcessorContext ctx) {
        throw new UnsupportedOperationException("a catalog read never processes a Consignment");
    }
}
