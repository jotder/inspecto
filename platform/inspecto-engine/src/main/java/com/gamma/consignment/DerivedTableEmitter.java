package com.gamma.consignment;

import com.gamma.api.PublicApi;

/**
 * How a {@link ConsignmentProcessor} asks for a <b>derived table</b> — the sanctioned way to create a new
 * table from the base table, per Consignment.
 *
 * <p><b>Why this and not a {@code Connection}.</b> {@link ProcessorContext} deliberately hands out no JDBC
 * handle: a raw connection makes the read-modify-write the append-only data path forbids trivially
 * expressible. This seam keeps the write declarative — the processor says what it wants materialised, the
 * framework materialises and registers it — so the same rule holds for a third-party plugin as for the
 * engine.
 *
 * <p><b>Its sibling is {@link SummaryEmitter}, and they are not interchangeable.</b> A summary is a
 * measure-bearing rollup whose composability is enforced (every row carries {@code count}, every measure
 * declares how it combines) precisely so incremental summaries stay correct. A derived table is an
 * arbitrary relation with none of those guarantees — the freedom is the point, and so is the fact that
 * nothing will check your arithmetic. Reach for {@link SummaryEmitter} when the output is a measure.
 *
 * <p>⚠ <b>A derived table is not readable within the run that emits it.</b> Emissions are materialised
 * after {@code process()} returns, so a processor cannot read back what it just asked for. The next step
 * in the chain sees it, through the Consignment output registry like any other output.
 */
@PublicApi(since = "4.0.0")
@FunctionalInterface
public interface DerivedTableEmitter {

    /**
     * Ask for one derived table.
     *
     * @throws IllegalArgumentException when the request is malformed — an unsafe name, blank SQL, or an
     *                                  unsafe {@code partitionBy}. The message names <em>every</em>
     *                                  violation, so a refusal takes one repair round rather than several.
     */
    void emit(DerivedTable table);
}
