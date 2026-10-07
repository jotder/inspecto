package com.gamma.acquire;

import com.gamma.api.PublicApi;

import java.util.ServiceLoader;

/**
 * The plugin seam for outbound {@link ExportConnector}s (EXPORT-1) — the mirror of
 * {@link CollectorConnectorFactory}. Register an implementation in
 * {@code META-INF/services/com.gamma.acquire.ExportConnectorFactory}; the factory whose {@link #scheme()}
 * equals a {@link ConnectionProfile#connector()} builds the exporter for that Connection.
 *
 * <p>Credentials never enter here: the profile carries {@code ${…}} references that the transport resolves
 * through {@link SecretResolver} at request-signing time.
 */
@PublicApi(since = "4.0.0")
public interface ExportConnectorFactory {

    /** The {@code connection.connector} value this factory handles (e.g. {@code "s3"}). */
    String scheme();

    /** Build an exporter bound to {@code profile}; throws {@link IllegalArgumentException} for an unusable one. */
    ExportConnector exporter(ConnectionProfile profile);

    /**
     * The exporter for {@code profile}, from the first discovered factory serving its scheme; throws
     * {@link IllegalStateException} naming the scheme when this bundle ships none.
     */
    static ExportConnector forProfile(ConnectionProfile profile) {
        for (ExportConnectorFactory f : ServiceLoader.load(ExportConnectorFactory.class))
            if (f.scheme().equalsIgnoreCase(profile.connector())) return f.exporter(profile);
        throw new IllegalStateException("Connection '" + profile.id() + "' uses connector '" + profile.connector()
                + "', and no outbound export transport for it is installed (only s3, from inspecto-connectors;"
                + " an HDFS cluster is reached through its S3-compatible gateway)");
    }
}
