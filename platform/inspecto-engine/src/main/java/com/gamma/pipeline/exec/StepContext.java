package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.etl.TypeFlow;
import com.gamma.job.PlatformServices;
import com.gamma.signal.SignalEmitter;
import com.gamma.util.RunLog;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Everything an {@link StepExecutor} may touch while it runs (S2-3). It is modelled on
 * {@code ProcessorContext}: a narrow façade, not a connection.
 *
 * <ul>
 *   <li>{@link #in()} reads the node's input row by row with typed getters. The engine issues the one
 *       statement behind it; there is no author SQL at all.</li>
 *   <li>{@link #emit} writes typed values straight into an engine-owned appender. It refuses a relation the
 *       node type does not declare in {@code emits()}.</li>
 *   <li>{@link #services()} is the {@code requires:} grant. An undeclared service is invisible. In a dry run,
 *       mutating services record instead of act, exactly as a Job's do.</li>
 *   <li>{@link #signals()} emits Signals when armed. In a dry run it only logs what it would emit.</li>
 * </ul>
 *
 * <p>Once the Step finishes, fails or times out, every method that reads or writes data throws.
 */
@PublicApi(since = "4.0.0")
public interface StepContext {

    /** The node's id in its pipeline. */
    String nodeId();

    /** The node's authored attributes (its {@code config} block). */
    Map<String, Object> attributes();

    /** The input relation's columns, in order. */
    List<TypeFlow.Column> schema();

    /** The node's input, opened on first call. The same reader is returned on every call. */
    StepInput in() throws SQLException;

    /** A writer for {@code rel} with the input's columns. Opened on first call; reused after that. */
    StepOutput emit(String rel) throws SQLException;

    /**
     * A writer for {@code rel} with its own columns. Each column type must be one a {@link StepOutput}
     * can append: {@code BOOLEAN}, {@code INTEGER}, {@code BIGINT}, {@code DOUBLE}, {@code VARCHAR},
     * {@code DATE} or {@code TIMESTAMP}. The first call for a relation fixes its columns.
     */
    StepOutput emit(String rel, List<TypeFlow.Column> columns) throws SQLException;

    /** Structured logging for this Step. */
    RunLog log();

    /** Domain Signals. In a dry run they are logged, not emitted. */
    SignalEmitter signals();

    /** Whether this is a dry run (a preview) that must mutate nothing. */
    boolean dryRun();

    /** The Platform Services this Step declared in {@link StepExecutor#requires()}. Nothing else. */
    PlatformServices services();
}
