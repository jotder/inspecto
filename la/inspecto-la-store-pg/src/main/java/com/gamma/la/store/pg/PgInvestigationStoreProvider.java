package com.gamma.la.store.pg;

import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.InvestigationStoreProvider;
import com.gamma.util.ConnectionSource;
import com.gamma.util.JdbcDrivers;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supplies {@code -Dinvestigations.backend=db} (registered in {@code META-INF/services}). One HikariCP pool per database (URL + user,
 * sized by {@code -Ddb.pool.size} like every other PostgreSQL store) is shared by every Space, and one {@link PgInvestigationStore}
 * per Space schema; the schema is bootstrapped once, under an advisory lock, the first time the Space is used.
 *
 * <p>Nothing is cached on failure: a Space whose database was down answers 503 and is retried on the next request.
 */
public final class PgInvestigationStoreProvider implements InvestigationStoreProvider {

    private static final Map<String, ConnectionSource> POOLS = new ConcurrentHashMap<>();
    private static final Map<String, InvestigationStore> STORES = new ConcurrentHashMap<>();

    @Override
    public String backend() {
        return "db";
    }

    @Override
    public InvestigationStore open(String spaceId, Connection connection) throws IOException {
        String schema = schemaFor(spaceId);
        String key = connection.url() + "\n" + connection.user() + "\n" + schema;
        InvestigationStore known = STORES.get(key);
        if (known != null) return known;
        ConnectionSource pool = pool(connection);
        try {
            InvestigationStore opened = new PgInvestigationStore(pool, schema);
            InvestigationStore raced = STORES.putIfAbsent(key, opened);
            return raced != null ? raced : opened;
        } catch (ApiException unreachable) {
            throw new IOException(unreachable.getMessage(), unreachable);
        }
    }

    private static ConnectionSource pool(Connection connection) throws IOException {
        String key = connection.url() + "\n" + connection.user();
        ConnectionSource pool = POOLS.get(key);
        if (pool != null) return pool;
        try {
            // Opens the first connection: a database that is down fails HERE, and nothing is cached, so the next request retries.
            ConnectionSource made = JdbcDrivers.source(connection.url(), connection.user(), connection.password(), "la-investigations");
            ConnectionSource raced = POOLS.putIfAbsent(key, made);
            if (raced != null) {
                made.close();
                return raced;
            }
            return made;
        } catch (SQLException | RuntimeException e) {   // Hikari's PoolInitializationException is a RuntimeException
            throw new IOException("cannot connect to " + connection + ": " + e.getMessage(), e);
        }
    }

    /**
     * The schema of a Space: {@code space_<id with '-' as '_'>}, exactly as the platform's other stores name it
     * ({@code OperationalDb.schemaFor}, which this module cannot call). A Space id that cannot be a schema name is refused.
     */
    static String schemaFor(String spaceId) throws IOException {
        String schema = "space_" + spaceId.replace('-', '_');
        if (!schema.matches("[a-z_][a-z0-9_]{0,62}"))
            throw new IOException("Space id '" + spaceId + "' cannot be made a PostgreSQL schema name");
        return schema;
    }
}
