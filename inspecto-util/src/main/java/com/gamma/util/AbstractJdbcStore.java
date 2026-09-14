package com.gamma.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * The shared skeleton of the JDBC-backed stores (JAVA-5): {@code DbObjectStore}, {@code DbLinkStore},
 * {@code DbNoteStore}, {@code DbTagAssignmentStore} and {@code DbDeliveryReceiptStore}.
 *
 * <p>⚠ <b>Moved here from {@code com.gamma.ops} in EDG-01 cell 7 (2026-09-08), and the move is the point.</b>
 * This is generic JDBC scaffolding — a connection field, the {@link BrowsableStore} seam, and a {@code close()}
 * that swallows a close failure into one warning — that merely happened to live in the operational-objects
 * package because four of its five subclasses are operational stores. The fifth,
 * {@code com.gamma.notify.DbDeliveryReceiptStore}, is not: so when CP-11 moved {@code com.gamma.ops} out of
 * the mandatory build into an optional edition module, this class could not travel with it without taking an
 * unrelated core store hostage. It is infrastructure, not domain, and now lives where that is legible.
 *
 * <p>Each carried the same connection field, the same four-line {@link BrowsableStore} seam differing
 * only in two labels and a table name, and the same {@code close()} that swallows a close failure into
 * one warning. The queries themselves are genuinely per-store and stay where they are — only the
 * bookkeeping around them moves here.
 *
 * <p>⚠ Schema creation deliberately does NOT happen in this constructor. An {@code initSchema()} called
 * from a base constructor runs before the subclass's own fields are assigned; it works today only
 * because every subclass keeps its DDL in static constants, and would break silently for the first one
 * that does not. Each subclass calls its own {@code initSchema()} as its last constructor act instead.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public abstract class AbstractJdbcStore implements BrowsableStore {

    private static final Logger log = LoggerFactory.getLogger(AbstractJdbcStore.class);

    /**
     * Where this store borrows a connection for each operation.
     *
     * <p>⚠ <b>This was a {@code protected final Connection conn} held for the store's whole life</b>
     * until scale-out phase A ({@code OPS-03}). Fifteen stores each pinning a connection is fine on one
     * node and a connection storm on twenty pods. Subclasses now wrap each operation in
     * {@link #withConn}. ⛔ Never stash the borrowed {@link Connection} in a field — it is valid only
     * for the duration of the callback.
     */
    protected final ConnectionSource src;

    private final String browseId;
    private final String browseLabel;
    private final String table;
    private final String what;

    /**
     * @param src         the connection source this store owns and closes
     * @param browseId    stable id for the raw-table browser
     * @param browseLabel human-readable name for the raw-table browser
     * @param table       the one table this store owns
     * @param what        the noun used in the close-failure warning (e.g. {@code "link"})
     */
    protected AbstractJdbcStore(ConnectionSource src, String browseId, String browseLabel,
                                String table, String what) {
        this.src = src;
        this.browseId = browseId;
        this.browseLabel = browseLabel;
        this.table = table;
        this.what = what;
    }

    /**
     * Run {@code body} against a borrowed connection — the replacement for the old {@code conn} field.
     * Reentrant, so one store method may call another (see {@link ConnectionSource}).
     */
    protected final <T> T withConn(ConnectionSource.SqlFunction<T> body) throws SQLException {
        return src.with(body);
    }

    /** {@link #withConn} for a body with no result. */
    protected final void runConn(ConnectionSource.SqlAction body) throws SQLException {
        src.run(body);
    }

    @Override public String browseId() { return browseId; }
    @Override public String browseLabel() { return browseLabel; }
    @Override public List<String> browseTables() { return List.of(table); }
    @Override public ConnectionSource browseSource() { return src; }

    /** Close the source. A close failure is logged, never thrown — shutdown must not fail. */
    public void close() {
        try {
            src.close();
        } catch (RuntimeException e) {
            log.warn("Error closing {} DB connection source: {}", what, e.getMessage());
        }
    }
}
