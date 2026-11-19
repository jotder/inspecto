package com.gamma.inspector;

import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.etl.PipelineConfig;

/**
 * {@link CollectorConnectorFactory} for the {@code postgres} scheme ({@link PostgresCollectorConnector}). Registered via
 * {@code META-INF/services} in this module, like {@code dataset}: the engine is on every runtime classpath, Basic's
 * included, and the scheme needs no driver. Add data's Postgres snapshot builds its connector here too
 * ({@code PostgresSnapshot}), so a snapshot and a Collector Pipeline read Postgres the same way.
 */
public final class PostgresConnectorFactory implements CollectorConnectorFactory {

    @Override
    public String scheme() {
        return PostgresCollectorConnector.SCHEME;
    }

    @Override
    public CollectorConnector create(PipelineConfig cfg) {
        return create(cfg, null);
    }

    /** A Pipeline's export: no row cap (a Pipeline's own limits apply downstream). */
    @Override
    public CollectorConnector create(PipelineConfig cfg, ConnectionProfile profile) {
        if (profile == null)
            throw new IllegalArgumentException("postgres source '" + cfg.collector().id()
                    + "' requires collector.connection to reference a postgres Connection (host, database, username, password, "
                    + "options.schema + options.table or options.query)");
        return new PostgresCollectorConnector(profile, 0, true);
    }

    /**
     * Every Connection save runs this ({@code CollectorConnectors.validate}): the host, names, what to copy, no tunnel or
     * proxy, and the password a {@code ${ENV:INSPECTO_PG_…}} reference - checked by name, nothing is resolved.
     */
    @Override
    public void validate(ConnectionProfile profile) {
        new PostgresCollectorConnector(profile, 0, true);
    }

    /** The connector for {@code profile}, refusing an export of more than {@code maxRows} rows (0 or less: no cap). */
    public static PostgresCollectorConnector connector(ConnectionProfile profile, long maxRows) {
        return new PostgresCollectorConnector(profile, maxRows, true);
    }

    /** A connector for a connection test only ({@link PostgresCollectorConnector#listTables}): no table chosen yet. */
    public static PostgresCollectorConnector forTest(ConnectionProfile profile) {
        return new PostgresCollectorConnector(profile, 0, false);
    }
}
