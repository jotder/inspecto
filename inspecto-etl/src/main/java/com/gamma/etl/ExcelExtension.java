package com.gamma.etl;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Loads DuckDB's {@code excel} extension for a connection — the one piece of {@code read_xlsx}
 * infrastructure the driver does not carry: unlike {@code json}/{@code icu}, the extension is
 * <b>not statically linked</b> into duckdb_jdbc (probed on 1.5.2.1), so it must be provisioned.
 *
 * <p>The layering (cached {@code LOAD} → the staged file under {@code -Dduckdb.extension.dir} → a
 * networked {@code INSTALL}) lives in {@link DuckDbExtension}, which {@link DuckLakeRegistrar} shares.
 * This class stays as the named front door for {@code excel}: callers read better for it, and the
 * air-gap flag's constant has always been quoted from here.
 *
 * <p>If all three layers fail, the batch fails with a message naming every remedy — never a silent or
 * partial parse. {@code LOAD} is idempotent and cheap, so callers invoke this per ingest connection.
 */
public final class ExcelExtension {

    /** The extension this class provisions. */
    private static final String NAME = "excel";

    /** The air-gap escape hatch: a directory holding {@code excel.duckdb_extension} for this platform. */
    public static final String DIR_PROPERTY = DuckDbExtension.DIR_PROPERTY;

    private ExcelExtension() {}

    /** Load the extension on {@code conn} or throw with every remedy named. */
    public static void ensureLoaded(Connection conn) throws SQLException {
        DuckDbExtension.ensureLoaded(conn, NAME, "frontend 'xlsx'");
    }

    /** Best-effort load; {@code false} when unavailable (tests use this to skip, never to pass). */
    public static boolean tryLoad(Connection conn) {
        return DuckDbExtension.tryLoad(conn, NAME);
    }
}
