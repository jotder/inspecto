package com.gamma.ops;

import com.gamma.util.BrowsableStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * The shared skeleton of the four JDBC-backed operational stores (JAVA-5): {@link DbObjectStore},
 * {@code DbLinkStore}, {@code DbNoteStore} and {@code DbTagAssignmentStore}.
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

    /** The single shared JDBC connection; all subclass access is serialised on the store's monitor. */
    protected final Connection conn;

    private final String browseId;
    private final String browseLabel;
    private final String table;
    private final String what;

    /**
     * @param conn        the already-open connection this store owns and closes
     * @param browseId    stable id for the raw-table browser
     * @param browseLabel human-readable name for the raw-table browser
     * @param table       the one table this store owns
     * @param what        the noun used in the close-failure warning (e.g. {@code "link"})
     */
    protected AbstractJdbcStore(Connection conn, String browseId, String browseLabel,
                                String table, String what) {
        this.conn = conn;
        this.browseId = browseId;
        this.browseLabel = browseLabel;
        this.table = table;
        this.what = what;
    }

    @Override public String browseId() { return browseId; }
    @Override public String browseLabel() { return browseLabel; }
    @Override public List<String> browseTables() { return List.of(table); }
    @Override public Connection browseConnection() { return conn; }

    /** Close the shared connection. A close failure is logged, never thrown — shutdown must not fail. */
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Error closing {} DB connection: {}", what, e.getMessage());
        }
    }
}
