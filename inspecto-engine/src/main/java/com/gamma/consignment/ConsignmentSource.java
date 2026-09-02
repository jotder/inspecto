package com.gamma.consignment;

/**
 * One source Consignment an at-rest run READ (execution residuals X2 — cross-lane provenance): the
 * consignment id, the pipeline that produced it ({@code consignment_outputs.producer}, {@code null} on
 * write paths with no pipeline identity in scope), and the store it was read from.
 *
 * <p>Carries the pipeline deliberately: a bare consignment id does not name its pipeline, and every UI
 * surface that opens a Consignment is keyed by the {@code (pipeline, batchId)} pair — a linkage the reader
 * cannot follow is only half a linkage.
 */
public record ConsignmentSource(String consignmentId, String pipeline, String tableName) {}
