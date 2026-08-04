package com.gamma.consignment;

import com.gamma.api.PublicApi;

import java.util.List;
import java.util.Map;

/**
 * <b>§14.4 step 2 — the read-only view a processor gets over its own Consignment.</b> One relation per target
 * the Consignment wrote, resolved from the §11.3 output registry, queryable with read-only SQL.
 *
 * <p><b>Why this is not the {@code Connection} §14.3 specified.</b> §14.3 rejected a {@code job()} accessor on
 * the grounds that it "leaks the entire Job surface into a contract every third-party processor binds to" — and
 * a raw {@link java.sql.Connection} leaks a far larger one: {@code createStatement().execute(…)} makes the
 * read-modify-write that §5.1's append-only invariant forbids trivially expressible, so §14.4 step 2's own
 * acceptance test ("a write attempt through {@code read()} fails") is unsatisfiable with a raw handle. A narrow
 * seam is read-only <em>by construction</em>: there is no method here that could write.
 *
 * <p><b>What enforces it.</b> Every {@link #query} goes through {@code SqlGuard} — the engine's existing
 * lexical allow-list, which admits a single {@code SELECT}/{@code WITH} and rejects all DDL/DML plus the
 * file/extension surface ({@code read_*}, {@code copy}, {@code attach}, …). The underlying connection is a
 * hardened {@code SqlSandbox} (extensions disabled, memory/thread caps, query timeout).
 *
 * <p><b>Why the sandbox is not sealed.</b> {@code SqlSandbox.seal()} sets {@code enable_external_access=false},
 * which stops <em>all</em> file reads — which is why {@code SqlOracle} materialises its inputs as tables first.
 * It can afford to: it only needs column types, so it copies with {@code LIMIT 0}. A processor needs the actual
 * rows, and materialising a whole Consignment into scratch is exactly the cost §11.3 exists to avoid. So the
 * relations stay <b>lazy views</b> over the registered output files and {@code SqlGuard} is what keeps the
 * blocked surface out. This is invariant protection, not a defence against hostile in-process code.
 */
@PublicApi(since = "5.0.0")
public interface ConsignmentReader extends AutoCloseable {

    /**
     * Run a read-only query over this Consignment's {@link #relations() relations}.
     *
     * @throws IllegalArgumentException when {@code sql} is not a single read-only query, carrying the
     *                                 {@code SqlGuard} findings in its message. Refused before DuckDB is
     *                                 touched at all — planning alone can evaluate smuggled functions.
     */
    List<Map<String, Object>> query(String sql) throws Exception;

    /**
     * The relation names available to {@link #query} — one per distinct {@code table_name} the Consignment
     * wrote. Empty when the registry holds no outputs for it (including when the registry is default-off, in
     * which case nothing about the Consignment's data is readable here — the manifest, not this, is
     * authoritative for a file's existence).
     */
    List<String> relations();

    @Override
    void close();
}
