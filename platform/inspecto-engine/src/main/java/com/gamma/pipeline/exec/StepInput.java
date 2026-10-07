package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.etl.TypeFlow;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A forward-only cursor over a Step's input (S2-3). Column indexes are 0-based, in {@link #columns()} order.
 * The getters are typed so a Step never boxes a value it does not need boxed — S2-2 measured the bridge cost
 * per cell. After a primitive getter, {@link #wasNull()} says whether the value was SQL NULL, as in JDBC.
 */
@PublicApi(since = "4.0.0")
public interface StepInput {

    /** The input columns, in order. */
    List<TypeFlow.Column> columns();

    /** The 0-based index of column {@code name}; throws when the input has no such column. */
    int column(String name);

    /** Advance to the next row; {@code false} when there are no more. */
    boolean next() throws SQLException;

    /** Whether the last value read was SQL NULL. */
    boolean wasNull() throws SQLException;

    long getLong(int column) throws SQLException;

    int getInt(int column) throws SQLException;

    double getDouble(int column) throws SQLException;

    boolean getBoolean(int column) throws SQLException;

    /** The value as text, or {@code null}. Works for every column type. */
    String getString(int column) throws SQLException;

    /** A {@code DATE} value, or {@code null}. */
    LocalDate getDate(int column) throws SQLException;

    /** A {@code TIMESTAMP} value, or {@code null}. */
    LocalDateTime getTimestamp(int column) throws SQLException;
}
