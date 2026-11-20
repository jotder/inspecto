package com.gamma.inspector;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.PatternFilter;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RemoteFile;
import com.gamma.auth.secrets.SecretResolver;
import com.gamma.config.safety.EgressGate;
import com.gamma.config.safety.EgressRefusedException;
import com.gamma.etl.DuckDbExtension;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.gamma.acquire.CollectorConnector.Capability.STREAM;

/**
 * The {@code postgres} {@link CollectorConnector} ({@code BASIC-ELT-PROFILE-1} BE-4): one Postgres table, or one read-only
 * {@code SELECT}, copied into a typed Parquet file. It reads through DuckDB's {@code postgres} extension, already staged in
 * every bundle, so no JDBC driver is needed and Basic has it; the JDBC-based {@code db} scheme ({@code DbExportConnector},
 * optional module) stays the one for incremental loads.
 *
 * <h3>The Connection</h3>
 * {@code host}, {@code port} (default 5432), {@code database}, {@code username} and {@code password}, plus
 * {@code options.schema} + {@code options.table} or {@code options.query} (what to copy). Nothing else: a tunnel, a proxy
 * or any other option is refused, so a profile this connector does not understand never half-works.
 *
 * <p><b>The password is a {@code ${ENV:INSPECTO_PG_…}} reference, and only that</b>: whoever picks the host also picks
 * which variable is resolved and sent to it, so without the prefix anyone who may save such a Connection could hand any
 * server secret (a cloud key, the keystore password) to a Postgres server they run. The prefix makes "this one may be sent
 * to a database" an administrator's decision, taken by naming the variable. {@link ConnectionProfile} itself resolves any
 * reference; the rule lives here, in the scheme, and every Connection save runs it ({@link PostgresConnectorFactory#validate}).
 * The value is resolved only at connect time into a {@code TEMPORARY SECRET} of an in-memory DuckDB - never stored,
 * returned, logged or put in a connection string (DuckDB echoes a libpq string, password included, in its connect error;
 * the secret form does not) - and every error text is scrubbed of it anyway.
 *
 * <p><b>The Safety Policy's network gate</b> ({@link EgressGate}) is passed before every session, before the password is
 * resolved: a host the Space's or the server's policy refuses is {@link Refused}, whoever asked (Add data's test, draft or
 * Run now, or a Pipeline's acquisition, which {@code CollectorConnectors.forConfig} has also gated).
 *
 * <h3>Read-only, three ways</h3>
 * The database is attached {@code READ_ONLY}, so DuckDB refuses a write and the Postgres session runs
 * {@code transaction_read_only = on}, so Postgres refuses one too; a query must be one {@code SELECT} or {@code WITH}; and
 * every session refuses a role that could write (superuser, or INSERT / UPDATE / DELETE / TRUNCATE on any non-system table).
 *
 * <h3>Typed as the import reads</h3>
 * A time-zoned timestamp becomes its UTC wall time; a type with no import column type (UUID, JSON, arrays, intervals…)
 * becomes text. The export is one logical file per cycle ({@code <table>.parquet} or {@code query.parquet}), so the
 * connector is {@link Capability#STREAM}-only: there is no source-side file to delete, move or rename.
 */
public final class PostgresCollectorConnector implements CollectorConnector {

    public static final String SCHEME = "postgres";
    /** The only environment variables a {@code postgres} Connection may name. */
    public static final String ENV_PREFIX = "INSPECTO_PG_";
    public static final int DEFAULT_PORT = 5432;
    public static final int MAX_QUERY_CHARS = 20_000;

    public static final Pattern HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.:-]{0,252}");
    public static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$.-]{0,62}");
    public static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,62}");
    public static final Pattern PASSWORD_REF = Pattern.compile("\\$\\{ENV:(" + ENV_PREFIX + "[A-Z0-9_]{1,100})}");
    public static final Pattern READ_ONLY_QUERY = Pattern.compile("(?is)\\s*(select|with)\\b.*");
    private static final Set<String> OPTIONS = Set.of("schema", "table", "query");

    private final ConnectionProfile profile;
    private final int port;
    private final String schema, table, query;
    private final long maxRows;

    /**
     * @param maxRows the most rows one export may hold (a larger one is {@link Refused#tooLarge() refused}); 0 or less = no cap
     * @param needsTarget whether {@code options} must name what to copy - false only for {@link #listTables}
     * @throws IllegalArgumentException when the profile is not one this scheme can read
     */
    PostgresCollectorConnector(ConnectionProfile profile, long maxRows, boolean needsTarget) {
        String id = profile.id();
        if (!SCHEME.equals(profile.connector()))
            throw new IllegalArgumentException("connection '" + id + "' is not a " + SCHEME + " connection");
        if (profile.host() == null || !HOST.matcher(profile.host()).matches())
            throw new IllegalArgumentException("postgres connection '" + id + "' needs a host name or address");
        port = profile.port() == ConnectionProfile.NO_PORT ? DEFAULT_PORT : profile.port();
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("postgres connection '" + id + "': port must be 1-65535");
        if (profile.database() == null || !NAME.matcher(profile.database()).matches())
            throw new IllegalArgumentException("postgres connection '" + id + "' needs a Postgres database name");
        if (profile.username() == null || !NAME.matcher(profile.username()).matches())
            throw new IllegalArgumentException("postgres connection '" + id + "' needs a Postgres role name as its username");
        // the value is never echoed: only whether it looked like a reference
        if (profile.password() == null || !PASSWORD_REF.matcher(profile.password().trim()).matches())
            throw new IllegalArgumentException("postgres connection '" + id + "': the password must be a secret reference such as ${ENV:"
                    + ENV_PREFIX + "SALES} - the name of an environment variable on the server, starting " + ENV_PREFIX
                    + ", that holds it; never the password itself");
        if (profile.tunnel() != null || profile.proxy() != null)
            throw new IllegalArgumentException("postgres connection '" + id + "' cannot use a tunnel or a proxy");
        for (String key : profile.options().keySet())
            if (!OPTIONS.contains(key))
                throw new IllegalArgumentException("postgres connection '" + id + "' has an unknown option '" + key
                        + "' (schema, table, query)");
        Map<String, String> o = profile.options();
        String q = o.get("query") == null || o.get("query").isBlank() ? null : o.get("query").trim();
        String s = o.get("schema"), t = o.get("table");
        if (q != null) {
            if (s != null || t != null) throw new IllegalArgumentException("postgres connection '" + id + "': give a table or a query, not both");
            if (q.length() > MAX_QUERY_CHARS)
                throw new IllegalArgumentException("postgres connection '" + id + "': the query is longer than " + MAX_QUERY_CHARS + " characters");
            if (q.indexOf(';') >= 0) throw new IllegalArgumentException("postgres connection '" + id + "': the query must be one statement");
            if (!READ_ONLY_QUERY.matcher(q).matches())
                throw new IllegalArgumentException("postgres connection '" + id + "': the query must be a read-only SELECT (or WITH … SELECT)");
        } else if (s != null || t != null || needsTarget) {
            if (s == null || !IDENT.matcher(s).matches() || t == null || !IDENT.matcher(t).matches())
                throw new IllegalArgumentException("postgres connection '" + id + "' needs options.schema and options.table "
                        + "(plain names), or options.query");
        }
        this.profile = profile;
        this.schema = q == null ? s : null;
        this.table = q == null ? t : null;
        this.query = q;
        this.maxRows = maxRows;
    }

    // ── CollectorConnector ─────────────────────────────────────────────────────────────────────────────────────

    @Override
    public String scheme() {
        return SCHEME;
    }

    @Override
    public EnumSet<Capability> capabilities() {
        return EnumSet.of(STREAM);   // a copied table has no source-side file to delete/move/rename
    }

    /** One logical export per cycle; no database access here (discovery stays cheap). */
    @Override
    public List<RemoteFile> discover(DiscoveryContext ctx) {
        String name = fileName();
        if (!new PatternFilter(ctx.includes(), ctx.excludes()).accepts(name)) return List.of();
        return List.of(new RemoteFile(name, name, RemoteFile.SIZE_UNKNOWN, java.time.Instant.now(), null, null, null));
    }

    @Override
    public Readiness readiness(RemoteFile file) {
        return Readiness.READY;   // a table is always ready to copy
    }

    @Override
    public InputStream open(RemoteFile file) throws AcquisitionException {
        try {
            Path dir = Files.createTempDirectory("pgexport-");
            Path tmp = fetchTo(file, dir.resolve(fileName()));
            InputStream in = Files.newInputStream(tmp);
            return new java.io.FilterInputStream(in) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { Files.deleteIfExists(tmp); Files.deleteIfExists(dir); }
                }
            };
        } catch (IOException e) {
            throw new AcquisitionException("Postgres export stream failed for " + file.relativePath(), e);
        }
    }

    @Override
    public Path fetchTo(RemoteFile file, Path dest) throws AcquisitionException {
        try {
            export(dest);
            return dest;
        } catch (Refused | IllegalArgumentException refused) {
            throw new AcquisitionException("Postgres export of '" + profile.id() + "' refused: " + refused.getMessage(), refused);
        }
    }

    @Override
    public void post(RemoteFile file, PostAction action) {
        // nothing to finalize: the source is a table, never a file
    }

    /** The export's file name: the table's, or {@code query}, as Parquet. */
    public String fileName() {
        return (table != null ? table : "query") + ".parquet";
    }

    /** Whether this connector copies a table (false: a query). */
    public boolean copiesTable() {
        return table != null;
    }

    // ── the work ───────────────────────────────────────────────────────────────────────────────────────────────

    /** What a connection test answers: the login's name and up to {@code limit} tables and views. */
    public record Listing(String user, List<Map<String, Object>> tables, boolean truncated) {}

    /** Connect, refuse a write-capable login, list the tables. {@code scratch} is the only folder the session may touch. */
    public Listing listTables(Path scratch, int limit) {
        try (Session s = Session.open(this, scratch)) {
            String who = s.requireReadOnlyRole();
            List<Map<String, Object>> tables = new ArrayList<>();
            boolean truncated = false;
            try (Statement st = s.conn.createStatement();
                 ResultSet r = st.executeQuery("SELECT table_schema, table_name, table_type FROM information_schema.tables "
                         + "WHERE table_catalog = 'pg' AND table_schema NOT IN ('pg_catalog', 'information_schema') "
                         + "ORDER BY 1, 2 LIMIT " + (limit + 1))) {
                while (r.next()) {
                    if (tables.size() == limit) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("schema", r.getString(1));
                    t.put("table", r.getString(2));
                    t.put("view", !"BASE TABLE".equalsIgnoreCase(r.getString(3)));
                    tables.add(t);
                }
            } catch (SQLException e) {
                throw s.failed("listing its tables", e);
            }
            return new Listing(who, tables, truncated);
        }
    }

    /**
     * Copy the table or query into {@code target} as typed Parquet; the session may touch only {@code target}'s folder.
     * Returns the row count. Refused (never partly answered) when the login could write, the source has no columns or no
     * rows, or it holds more than the cap.
     */
    public long export(Path target) {
        Path dir = target.toAbsolutePath().normalize().getParent();
        String what = table != null ? "table" : "query";
        try (Session s = Session.open(this, dir)) {
            s.requireReadOnlyRole();   // on every copy: a login granted a write since the last one is refused too
            List<String> cols = new ArrayList<>();
            try (Statement st = s.conn.createStatement();
                 ResultSet r = st.executeQuery("DESCRIBE SELECT * FROM " + relation())) {
                while (r.next()) cols.add(projection(r.getString(1), r.getString(2)));
            } catch (SQLException e) {
                throw s.failed("reading " + (table != null ? "the table" : "the query"), e);
            }
            if (cols.isEmpty()) throw new Refused("the " + what + " has no columns", 0);
            String select = "SELECT " + String.join(", ", cols) + " FROM " + relation() + (maxRows > 0 ? " LIMIT " + (maxRows + 1) : "");
            String path = target.toAbsolutePath().normalize().toString().replace('\\', '/');
            try (Statement st = s.conn.createStatement()) {
                st.execute("COPY (" + select + ") TO " + SqlIdent.sqlStr(path) + " (FORMAT parquet)");
            } catch (SQLException e) {
                throw s.failed("copying the rows", e);
            }
            long rows;
            try (Statement st = s.conn.createStatement();
                 ResultSet r = st.executeQuery("SELECT count(*) FROM " + com.gamma.sql.SqlViews.parquetFile(target))) {
                r.next();
                rows = r.getLong(1);
            } catch (SQLException e) {
                throw s.failed("counting the rows", e);
            }
            if (maxRows > 0 && rows > maxRows) throw new Refused("the " + what + " holds more than " + maxRows + " rows", maxRows);
            if (rows == 0) throw new Refused("the " + what + " holds no rows", 0);
            return rows;
        }
    }

    private String relation() {
        return query != null ? "postgres_query('pg', " + SqlIdent.sqlStr(query) + ")"
                : "pg." + SqlIdent.q(schema) + "." + SqlIdent.q(table);
    }

    /** One column of the copy, cast to a type the import reads. */
    static String projection(String name, String duckType) {
        String col = SqlIdent.q(name);
        String t = duckType == null ? "" : duckType.toUpperCase(Locale.ROOT);
        if (t.equals("TIMESTAMP WITH TIME ZONE")) return "timezone('UTC', " + col + ") AS " + col;
        if (t.matches("(U?(TINY|SMALL|BIG|HUGE)?INT(EGER)?|UHUGEINT|DOUBLE|FLOAT|REAL|DECIMAL\\(\\d+,\\d+\\)|DATE|TIMESTAMP|BOOLEAN|VARCHAR)"))
            return col;
        return "CAST(" + col + " AS VARCHAR) AS " + col;
    }

    /**
     * Why an export or a test was refused, the database's own reason scrubbed of the password. {@link #tooLarge()} when
     * the source held more rows than the cap ({@link #limit()}); every other refusal is the caller's to fix.
     */
    public static final class Refused extends RuntimeException {
        private final long limit;

        Refused(String message, long limit) {
            super(message, null, false, false);
            this.limit = limit;
        }

        public boolean tooLarge() {
            return limit > 0;
        }

        public long limit() {
            return limit;
        }
    }

    // ── one session ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * An in-memory DuckDB with the database attached read-only through a temporary secret, then sealed to {@code dir}
     * (the attach happens before the seal; afterwards no other attach and no file outside {@code dir}).
     */
    static final class Session implements AutoCloseable {
        final Connection conn;
        private final String password;

        private Session(Connection conn, String password) {
            this.conn = conn;
            this.password = password;
        }

        static Session open(PostgresCollectorConnector c, Path dir) {
            ConnectionProfile p = c.profile;
            // BE-4 part 2: the Safety Policy's network gate, before any password is resolved - on every session, so Add
            // data's test, draft and Run now pass it as a Pipeline's acquisition does (CollectorConnectors.forConfig). The
            // calling thread's tier: Add data's request thread, or the acquisition's pinned run thread (one export file,
            // so the fetch is never handed to a pool worker)
            try {
                EgressGate.current().require(p.host(), c.port, "postgres connection '" + p.id() + "'");
            } catch (EgressRefusedException refused) {
                throw new Refused(refused.getMessage(), 0);
            }
            String ref = p.password().trim();
            String pw = SecretResolver.resolve(ref);
            if (pw == null) {
                Matcher m = PASSWORD_REF.matcher(ref);
                throw new Refused("the password reference " + ref + " is not set on the server: set the environment variable "
                        + (m.matches() ? m.group(1) : ref) + " where Inspecto runs, then restart it", 0);
            }
            try {
                Files.createDirectories(dir);
                Connection conn = DuckDbUtil.openInMemory(null, List.of(dir), x -> {
                    DuckDbExtension.ensureLoaded(x, "postgres_scanner", "reading a Postgres table");
                    try (Statement st = x.createStatement()) {
                        st.execute("CREATE TEMPORARY SECRET onboard_pg (TYPE postgres, HOST " + SqlIdent.sqlStr(p.host())
                                + ", PORT " + c.port + ", DATABASE " + SqlIdent.sqlStr(p.database())
                                + ", USER " + SqlIdent.sqlStr(p.username()) + ", PASSWORD " + SqlIdent.sqlStr(pw) + ")");
                    }
                    try (Statement st = x.createStatement()) {
                        st.execute("ATTACH '' AS pg (TYPE postgres, SECRET onboard_pg, READ_ONLY)");
                    }
                });
                return new Session(conn, pw);
            } catch (SQLException e) {
                throw failed(pw, "connecting to " + p.host() + ":" + c.port + "/" + p.database() + " as " + p.username(), e);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        /** The role's name; refused when it could write (superuser, or a write grant on any non-system table). */
        String requireReadOnlyRole() {
            String sql = "SELECT current_user::text AS who, "
                    + "(SELECT rolsuper FROM pg_roles WHERE rolname = current_user) AS su, "
                    + "(SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE c.relkind IN ('r', 'p', 'f', 'v', 'm') AND n.nspname NOT IN ('pg_catalog', 'information_schema') "
                    + "AND n.nspname NOT LIKE 'pg\\_%' AND (has_table_privilege(c.oid, 'INSERT') OR has_table_privilege(c.oid, 'UPDATE') "
                    + "OR has_table_privilege(c.oid, 'DELETE') OR has_table_privilege(c.oid, 'TRUNCATE'))) AS writable";
            try (Statement st = conn.createStatement();
                 ResultSet r = st.executeQuery("SELECT * FROM postgres_query('pg', " + SqlIdent.sqlStr(sql) + ")")) {
                r.next();
                String who = r.getString(1);
                boolean su = r.getBoolean(2);
                long writable = r.getLong(3);
                if (su)
                    throw new Refused("'" + who + "' is a superuser, which could change the database. Inspecto reads Postgres "
                            + "with a read-only login: create a role with only CONNECT, USAGE on the schema and SELECT on its tables", 0);
                if (writable > 0)
                    throw new Refused("'" + who + "' may change " + writable + " table" + (writable == 1 ? "" : "s")
                            + " (INSERT, UPDATE, DELETE or TRUNCATE). Inspecto reads Postgres with a read-only login: grant it "
                            + "only CONNECT, USAGE on the schema and SELECT on the tables", 0);
                return who;
            } catch (SQLException e) {
                throw failed("checking the role's rights", e);
            }
        }

        Refused failed(String doing, SQLException e) {
            return Session.failed(password, doing, e);
        }

        /** Naming what failed, the database's own reason scrubbed of the password. */
        private static Refused failed(String password, String doing, SQLException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (password != null && !password.isEmpty()) msg = msg.replace(password, "***");
            msg = msg.replace('\n', ' ').trim();
            if (msg.length() > 400) msg = msg.substring(0, 400) + "…";
            return new Refused("Postgres: " + doing + " failed: " + msg, 0);
        }

        @Override
        public void close() {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // an in-memory connection: nothing to keep
            }
        }
    }
}
