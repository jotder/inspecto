package com.gamma.inspector;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.auth.secrets.SecretResolver;
import com.gamma.config.safety.EgressGate;
import com.gamma.config.safety.PathJail;
import com.gamma.etl.DuckDbExtension;
import com.gamma.etl.PipelineConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.regex.Pattern;

import static com.gamma.util.SqlIdent.sqlStr;

/**
 * An object-store {@code dirs.database} on the ingest lane ({@code SCALEOUT-OBJSTORE-DATABASE-DIR-1}, scale-out
 * plan §5.4 bullet 6). {@link #prepare} runs on the ingest connection BEFORE the seal: it re-checks containment,
 * refuses every pipeline shape this lane does not write to a bucket, resolves {@code output.connection} (route
 * (a): a Connection id, never a secret in the pipeline), gates the endpoint through the Safety Policy, loads
 * {@code httpfs} and creates an in-memory DuckDB secret scoped to the database prefix. The seal then admits that
 * prefix and nothing else ({@code SqlSandbox.sealAllowing}).
 *
 * <p>Refused by name (pending operator confirmation, decided 2026-11-01 under delegation — the conservative
 * reading): several {@code sinks:}, {@code produces: reference} (its versioned store and merge read the store
 * through {@code java.nio}), and DuckLake registration (the registrar's own connection holds no credentials yet).
 * The Connection's {@code base_path} is not consulted: the operator allow-list ({@code -Dassist.safety.object_roots})
 * is what bounds where a pipeline may write, and the Connection supplies only the endpoint and credentials.
 */
final class ObjectStoreDatabase {

    /** The DuckDB secret's name on the ingest connection. */
    static final String STORE_SECRET_NAME = "inspecto_output_store";
    private static final Pattern BARE_HOST = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*");

    private ObjectStoreDatabase() {}

    /** @return the contained database prefix ({@code s3://bucket/key}, no trailing {@code /}) to admit on the seal */
    static String prepare(Connection conn, PipelineConfig cfg) throws SQLException {
        String database = cfg.dirs().database();
        String root;
        try {
            root = PathJail.requireObjectStore(PathJail.allowedObjectRoots(), database, "dirs.database");
        } catch (PathJail.Escape refused) {
            throw refuse(refused.getMessage());
        }
        if (cfg.sinks().size() != 1 || !database.equals(cfg.sinks().get(0).database()))
            throw refuse("dirs.database '" + database + "' is an object-store location, which this lane writes "
                    + "only as the single output: destination — not with a sinks: list");
        if (cfg.producesReference())
            throw refuse("dirs.database '" + database + "' is an object-store location, and a produces: reference "
                    + "pipeline keeps its versioned store on a local directory");
        Map<String, Object> duckLake = cfg.sinks().get(0).duckLake();
        if (duckLake != null && Boolean.parseBoolean(String.valueOf(duckLake.get("enabled"))))
            throw refuse("dirs.database '" + database + "' is an object-store location, and registering its "
                    + "output in DuckLake is not built yet — disable output.ducklake for this pipeline");

        String id = cfg.output().connection();
        if (id == null || id.isBlank())
            throw refuse("dirs.database '" + database + "' is an object-store location, so output.connection must "
                    + "name the s3 Connection that reaches it");
        ConnectionProfile p = ConnectionRegistry.find(id).orElseThrow(() ->
                refuse("output.connection '" + id + "' is not a Connection registered in this Space"));
        if (!"s3".equals(p.connector()))
            throw refuse("output.connection '" + id + "' is a '" + p.connector() + "' Connection; an object-store "
                    + "dirs.database needs an s3 one");
        if (p.tunnel() != null || p.proxy() != null)
            throw refuse("output.connection '" + id + "' declares a tunnel or proxy, which DuckDB's object-store "
                    + "client cannot dial through");
        if (p.host() == null || p.host().isBlank())
            throw refuse("output.connection '" + id + "' has no host");
        // The host is glued into DuckDB's ENDPOINT, which dials whatever authority it parses: a ':', '/' or '@'
        // would let 'evil:80/x.allowed.com' pass the egress pattern yet dial 'evil'. Bare DNS names only.
        if (!BARE_HOST.matcher(p.host()).matches())
            throw refuse("output.connection '" + id + "' host must be a bare DNS name, without port, path or "
                    + "userinfo (the port goes in the port field)");
        String secret = SecretResolver.resolve(p.password());
        if (p.username() == null || p.username().isBlank() || secret == null || secret.isBlank())
            throw refuse("output.connection '" + id + "' has no access key and resolvable secret");

        // The S3 client DuckDB uses dials from native code, past every Java-side egress hop, so the Safety
        // Policy is applied here, once, before any request can be made.
        EgressGate.current().require(p.host(), p.port(), "object-store dirs.database (" + root + ")");
        // LOAD is file access: after the seal it is refused like any other.
        DuckDbExtension.ensureLoaded(conn, "httpfs", "writing to an object-store dirs.database (" + root + ")");
        String endpoint = p.host() + (p.port() > 0 ? ":" + p.port() : "");
        boolean ssl = !"http".equalsIgnoreCase(p.options().getOrDefault("protocol", "https"));
        try (Statement st = conn.createStatement()) {
            // A temporary (in-memory) secret: it dies with the connection and is never written to disk.
            // duckdb_secrets() shows it redacted.
            st.execute("CREATE OR REPLACE SECRET " + STORE_SECRET_NAME + " (TYPE s3"
                    + ", KEY_ID " + sqlStr(p.username())
                    + ", SECRET " + sqlStr(secret)
                    + ", ENDPOINT " + sqlStr(endpoint)
                    + ", REGION " + sqlStr(p.options().getOrDefault("region", "us-east-1"))
                    + ", URL_STYLE 'path', USE_SSL " + ssl
                    + ", SCOPE " + sqlStr(root) + ")");
        }
        return root;
    }

    private static SQLException refuse(String why) {
        return new SQLException("refusing to ingest: " + why + " (SCALEOUT-OBJSTORE-DATABASE-DIR-1)");
    }
}
