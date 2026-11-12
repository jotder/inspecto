package com.gamma.inspector;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.auth.secrets.SecretResolver;
import com.gamma.config.safety.EgressGate;
import com.gamma.config.safety.PathJail;
import com.gamma.etl.DuckDbExtension;
import com.gamma.etl.DuckLakeRegistrar;
import com.gamma.etl.PipelineConfig;
import com.gamma.util.egress.EgressPolicy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

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
 * reading): several {@code sinks:} and {@code produces: reference} (its versioned store and merge read the store
 * through {@code java.nio}). DuckLake registration of the output is built: {@link #registrarSetup} gives the
 * registrar's own connection the same prefix-scoped secret.
 * The Connection's {@code base_path} is not consulted: the operator allow-list ({@code -Dassist.safety.object_roots})
 * is what bounds where a pipeline may write, and the Connection supplies only the endpoint and credentials.
 */
final class ObjectStoreDatabase {

    /** The DuckDB secret's name on the ingest connection. */
    static final String STORE_SECRET_NAME = "inspecto_output_store";

    private ObjectStoreDatabase() {}

    /** @return the contained database prefix ({@code s3://bucket/key}, no trailing {@code /}) to admit on the seal */
    static String prepare(Connection conn, PipelineConfig cfg) throws SQLException {
        Store store = resolve(cfg);
        // LOAD is file access: after the seal it is refused like any other.
        store.createSecret(conn, "writing to");
        return store.root();
    }

    /**
     * The registrar's connection setup for this batch's output: the same checks as {@link #prepare}, then the
     * same prefix-scoped secret on the registrar's own (unsealed) connection, so the {@code INSERT … SELECT}
     * from the written Parquet can read the bucket. ⛔ Every file must lie under the contained prefix: that
     * connection has no seal, so a path outside the prefix would be read with whatever it can reach.
     */
    static DuckLakeRegistrar.ConnectionSetup registrarSetup(PipelineConfig cfg, List<String> files) throws SQLException {
        Store store = resolve(cfg);
        // A prefix test, not the containment rule's key character set (operator 2026-11-02): a partition value
        // DuckDB percent-encodes ('ts=2026-11-01%2010%3A00%3A00') must register. ⛔ The secret's scope is matched
        // on the string BEFORE DuckDB normalises '..', so this check is the boundary: '.'/'..' segments are
        // refused, and so are '\' (SqlViews.pathList rewrites it to '/', turning 'a\..\' into a climb) and glob
        // bytes (read_parquet expands them). DuckDB's own writer escapes all of these, so real output has none.
        for (String f : files) {
            boolean inside = f.startsWith(store.root() + "/") && f.chars().noneMatch(ch -> "\\*?[]{}".indexOf(ch) >= 0);
            if (inside)
                for (String seg : f.substring(store.root().length() + 1).split("/", -1))
                    if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) inside = false;
            if (!inside)
                throw refuse("not registering in DuckLake: written file '" + f + "' is not under the object-store "
                        + "dirs.database " + store.root() + "/");
        }
        return conn -> store.createSecret(conn, "registering in DuckLake the output of");
    }

    /** A contained, gated object-store database and the credentials that reach it. */
    private record Store(String root, ConnectionProfile p, String secret) {
        void createSecret(Connection conn, String purpose) throws SQLException {
            DuckDbExtension.ensureLoaded(conn, "httpfs", purpose + " an object-store dirs.database (" + root + ")");
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
                        // Trailing '/': DuckDB matches a scope as a plain string prefix, so without it the
                        // secret would also serve the sibling prefix '<root>X/' (verification finding).
                        + ", SCOPE " + sqlStr(root + "/") + ")");
            }
        }
    }

    private static Store resolve(PipelineConfig cfg) throws SQLException {
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
        // would let 'evil:80/x.allowed.com' pass the egress pattern yet dial 'evil'. A DNS name or a canonical IPv4
        // dotted quad only (item (e)): libcurl reads '2130706433', '0x7f.1', '0177.0.0.1' or '127.1' as 127.0.0.1,
        // which the Safety Policy's patterns never compared; an IPv6 literal cannot be glued into ENDPOINT unbracketed.
        String hostProblem = null;
        if (p.host().indexOf(':') >= 0 || p.host().indexOf('[') >= 0) hostProblem = "an IPv6 literal or a port";
        else {
            try {
                EgressPolicy.checkHost(p.host());
            } catch (IllegalArgumentException bad) {
                hostProblem = bad.getMessage();
            }
        }
        if (hostProblem != null)
            throw refuse("output.connection '" + id + "' host must be a bare DNS name or a canonical IPv4 address, "
                    + "without port, path or userinfo (the port goes in the port field): " + hostProblem);
        String secret = SecretResolver.resolve(p.password());
        if (p.username() == null || p.username().isBlank() || secret == null || secret.isBlank())
            throw refuse("output.connection '" + id + "' has no access key and resolvable secret");

        // The S3 client DuckDB uses dials from native code, past every Java-side egress hop, so the Safety
        // Policy is applied here, once, before any request can be made.
        EgressGate.current().require(p.host(), p.port(), "object-store dirs.database (" + root + ")");
        return new Store(root, p, secret);
    }

    private static SQLException refuse(String why) {
        return new SQLException("refusing to ingest: " + why + " (SCALEOUT-OBJSTORE-DATABASE-DIR-1)");
    }
}
