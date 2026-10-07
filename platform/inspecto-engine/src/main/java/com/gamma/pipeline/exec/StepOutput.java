package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.etl.TypeFlow;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A row writer for one emitted relation (S2-3), backed by an engine-owned DuckDB appender. Write a row as
 * {@link #beginRow()}, one {@code append} per column in {@link #columns()} order, then {@link #endRow()} — or
 * {@link #copyRow} to pass the input's current row through unchanged. Values go straight to the appender;
 * nothing is boxed per row.
 */
@PublicApi(since = "4.0.0")
public interface StepOutput {

    /** The relationship this writer emits, e.g. {@code data} or {@code reject:bad_score}. */
    String rel();

    /** This relation's columns, in order. */
    java.util.List<TypeFlow.Column> columns();

    StepOutput beginRow() throws SQLException;

    StepOutput append(long v) throws SQLException;

    StepOutput append(int v) throws SQLException;

    StepOutput append(double v) throws SQLException;

    StepOutput append(boolean v) throws SQLException;

    /** Appends {@code v}, or NULL when it is {@code null}. */
    StepOutput append(String v) throws SQLException;

    /** Appends {@code v}, or NULL when it is {@code null}. */
    StepOutput append(LocalDate v) throws SQLException;

    /** Appends {@code v}, or NULL when it is {@code null}. */
    StepOutput append(LocalDateTime v) throws SQLException;

    StepOutput appendNull() throws SQLException;

    StepOutput endRow() throws SQLException;

    /**
     * Write {@code in}'s current row as one whole row of this relation. The relation must have the input's
     * columns (the one-argument {@link StepContext#emit(String)}).
     */
    StepOutput copyRow(StepInput in) throws SQLException;
}
