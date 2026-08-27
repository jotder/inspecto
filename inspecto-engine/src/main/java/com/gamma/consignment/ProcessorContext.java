package com.gamma.consignment;

import com.gamma.api.PublicApi;
import com.gamma.signal.SignalEmitter;
import com.gamma.util.RunLog;

import java.util.List;

/**
 * <b>§14.3 — everything a {@link ConsignmentProcessor} may read.</b> A Consignment-scoped façade derived from a
 * Job run: the framework resolves which Consignment this is and hands the author a view already narrowed to it,
 * so nothing here requires knowing how the Consignment was found.
 *
 * <p><b>Why the Job surface is delegated, not exposed.</b> A {@code job()} accessor would be one method instead
 * of three, and it is the wrong trade: it leaks the entire Job surface into a contract every third-party
 * processor binds to, after which nothing in {@code JobContext} could ever change. {@link #log()} is 3 methods
 * and {@link #signals()} is 1 — both small and stable enough to re-expose. {@code ArtifactRecorder} is
 * deliberately <em>not</em> delegated: exposing it beside {@link #summaries()} would give authors two plausible
 * ways to emit the same thing.
 *
 * <p><b>Two members of §14.3's eight are absent, each for a stated reason.</b>
 * <ul>
 *   <li>{@code Connection read()} became {@link #read()} returning a {@link ConsignmentReader}. A raw JDBC
 *       handle makes the read-modify-write §5.1 forbids trivially expressible, so §14.4 step 2's own acceptance
 *       test ("a write attempt fails") is unsatisfiable with one — and it leaks a far larger surface than the
 *       {@code job()} accessor §14.3 rejected on exactly that ground.</li>
 *   <li>{@code Manifest manifest()} is <b>deferred</b>: no {@code Manifest} type exists. §2/§4 specify it as a
 *       relation and neither is built, so it is blocked the same way {@code outputs()} was blocked on §11.3.
 *       The existing per-Consignment {@code BatchManifest} was rejected as a stand-in — it is an ingest-side
 *       Gson DTO, and §14.3 excludes in-motion types precisely to stop the at-rest tier re-coupling to them.
 *       Until §2/§4 land, {@link #outputs()} is the addressing authority.</li>
 * </ul>
 */
@PublicApi(since = "4.0.0")
public interface ProcessorContext {

    /** The Consignment this run is about — resolved by the framework, never by the author. */
    String consignmentId();

    /**
     * Every file this Consignment wrote, from the §11.3 registry: path, row count, bytes, partition, record day
     * and lifecycle state. Empty when the registry is default-off — in which case remember the manifest, not
     * this table, is authoritative for a file's existence.
     */
    List<ConsignmentOutput> outputs();

    /** Read-only SQL over this Consignment's own data. */
    ConsignmentReader read();

    /** Emit summaries under §7.2's guardrails. The only sanctioned way to emit summary output. */
    SummaryEmitter summaries();

    /** Structured per-run logging, delegated from the Job run. */
    RunLog log();

    /**
     * Emit domain Signals, delegated from the Job run. The Consignment id is stamped into every payload by the
     * framework, so an author never re-states which Consignment a Signal is about.
     */
    SignalEmitter signals();

    /**
     * Whether this is a dry run (preview) that must mutate nothing. A processor that cannot preview must do
     * nothing and say so — never fall through to the real action.
     */
    boolean dryRun();
}
