package com.gamma.control;

import com.gamma.auth.secrets.SecretResolver;
import com.gamma.config.io.ConfigCodec;
import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimitValues;
import com.gamma.etl.DuckDbExtension;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.SqlIdent;
import com.gamma.util.DuckDbUtil;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Choose a table</b> - the third way into Add data ({@code BASIC-ONBOARD-1} P3, plan §4.3, §6.1): a Postgres table or a
 * read-only {@code SELECT}, copied into Parquet as a <i>replace everything</i> snapshot. Every edition, Basic included
 * (this reverses DT-4 for Postgres only).
 *
 * <ul>
 *   <li>{@code POST /onboard/postgres/test} {@code {connection}} - connect, refuse a write-capable role, list the tables.</li>
 *   <li>{@code POST /onboard/postgres} {@code {connection, schema+table | query, kind}} - copy the rows into a draft and
 *       answer the same DRAFT as {@code POST /onboard/files}; the commit is the ordinary
 *       {@code POST /onboard/files/{draftId}/commit}, which also writes {@code imports/<id>_snapshot.toon}.</li>
 *   <li>{@code POST /datasets/{id}/run} (Run now) on a snapshot Dataset re-copies it ({@link #refresh}).</li>
 * </ul>
 *
 * <p><b>Export, then move</b> (operator 2026-11-05): Run now EXPORTS the table with DuckDB {@code COPY (…) TO
 * '<file>.parquet'} into a temporary folder under the data root's drafts folder - never inside the inbox - and only once
 * the file is complete MOVES it atomically into the Dataset's inbox, where the ordinary import path takes it like an
 * uploaded file: name-checked columns, the row filter, the arrival, compaction. A snapshot always replaces everything.
 * <b>A snapshot is manual only:</b> nothing polls it (the 30 s refresh was removed the same day).
 *
 * <p><b>How it reads</b> (plan §6.1 V2): DuckDB's {@code postgres} extension, already staged in every bundle - no JDBC
 * driver on Basic. The password is a {@code ${ENV:NAME}} reference, resolved only here, at the moment of connecting,
 * into a {@code TEMPORARY SECRET} of an in-memory DuckDB; it is never stored, answered, logged or put in a URL or a
 * connection string (DuckDB echoes a libpq string - password included - in its connect error; the secret form does
 * not). Every error text is scrubbed of the resolved value anyway.
 *
 * <p><b>Read-only, three ways.</b> The database is attached {@code READ_ONLY}, so DuckDB refuses a write and the Postgres
 * session runs {@code transaction_read_only = on}, so Postgres refuses one too (measured); a pasted query must be one
 * {@code SELECT} or {@code WITH}; and the connection test refuses a role that could write (superuser, or INSERT /
 * UPDATE / DELETE / TRUNCATE on any non-system table), so Basic is given a read-only login.
 */
final class PostgresSnapshot {

    private PostgresSnapshot() {}

    /** The Dataset entry's {@code importSource} for a snapshot (display only; {@link #configFile} is the authority). */
    static final String SOURCE = "postgres";
    static final String SUFFIX = "_snapshot.toon";
    /** At most this many tables are listed by the connection test. */
    static final int LISTED = 500;
    static final int MAX_QUERY_CHARS = 20_000;

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.:-]{0,252}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$.-]{0,62}");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,62}");
    /**
     * Only a variable named {@value #ENV_PREFIX}…: the person choosing the host also chooses which variable is resolved and
     * sent to it, so without the prefix anyone with {@code canUploadData} could hand any server secret (a cloud key, the
     * keystore password) to a Postgres server they run. The prefix makes "this one may be sent to a database" an
     * administrator's decision, taken by naming the variable.
     */
    static final String ENV_PREFIX = "INSPECTO_PG_";
    private static final Pattern PASSWORD_REF = Pattern.compile("\\$\\{ENV:(" + ENV_PREFIX + "[A-Z0-9_]{1,100})}");
    private static final Pattern READ_ONLY_QUERY = Pattern.compile("(?is)\\s*(select|with)\\b.*");

    static void register(ApiContext api) {
        // ⚠ String LITERALS on purpose (CapabilityManifestTest's scanner).
        api.post("/onboard/postgres/test", ApiContext.withCapability("canUploadData", (e, m) -> test(api, e, api.body(e))));
        api.post("/onboard/postgres", ApiContext.withCapability("canUploadData", (e, m) -> draft(api, e, api.body(e))));
    }

    // ── what the user gives ────────────────────────────────────────────────────────────────────────────────────

    /** Where to connect. {@code passwordRef} is the reference, never the value. */
    record Conn(String host, int port, String database, String user, String passwordRef) {

        static Conn of(Object raw) {
            if (!(raw instanceof Map<?, ?> m))
                throw bad("connection must be {host, port, database, user, password}");
            String host = text(m, "host", HOST, "host must be a host name or address");
            int port = 5432;
            if (m.get("port") != null) {
                try {
                    port = Integer.parseInt(String.valueOf(m.get("port")).trim());
                } catch (NumberFormatException e) {
                    port = -1;
                }
                if (port < 1 || port > 65_535) throw bad("port must be 1-65535");
            }
            String database = text(m, "database", NAME, "database must be a Postgres database name");
            String user = text(m, "user", NAME, "user must be a Postgres role name");
            Object pw = m.get("password");
            String ref = pw instanceof String s ? s.trim() : "";
            // the value is never echoed: only whether it looked like a reference
            if (!PASSWORD_REF.matcher(ref).matches())
                throw bad("password must be a secret reference such as ${ENV:" + ENV_PREFIX + "SALES} - the name of an environment "
                        + "variable on the server, starting " + ENV_PREFIX + ", that holds it; never the password itself");
            return new Conn(host, port, database, user, ref);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("host", host);
            out.put("port", port);
            out.put("database", database);
            out.put("user", user);
            out.put("password", passwordRef);
            return out;
        }

        String envName() {
            Matcher m = PASSWORD_REF.matcher(passwordRef);
            return m.matches() ? m.group(1) : passwordRef;
        }
    }

    /** What to copy: a schema and table, or one read-only query. */
    record Source(String schema, String table, String query) {

        static Source of(Map<?, ?> body) {
            Object q = body.get("query");
            if (q instanceof String s && !s.isBlank()) {
                if (body.get("table") != null) throw bad("give a table or a query, not both");
                String query = s.trim();
                if (query.endsWith(";")) query = query.substring(0, query.length() - 1).trim();
                if (query.length() > MAX_QUERY_CHARS) throw bad("the query is longer than " + MAX_QUERY_CHARS + " characters");
                if (query.indexOf(';') >= 0) throw bad("the query must be one statement (no ';' inside it)");
                if (!READ_ONLY_QUERY.matcher(query).matches())
                    throw bad("the query must be a read-only SELECT (or WITH … SELECT); Add data never writes to your database");
                return new Source(null, null, query);
            }
            String schema = text(body, "schema", IDENT, "schema must be a schema name from the connection test");
            String table = text(body, "table", IDENT, "table must be a table name from the connection test");
            return new Source(schema, table, null);
        }

        /** From a stored draft or {@code _snapshot.toon}: data, not trusted - checked exactly as a request body is. */
        static Source fromMap(Map<String, Object> m) {
            return of(m);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            if (query != null) out.put("query", query);
            else {
                out.put("schema", schema);
                out.put("table", table);
            }
            return out;
        }

        /** A file-name stem: the table, or {@code query}. */
        String stem() {
            return table != null ? table : "query";
        }

        String relation() {
            return query != null ? "postgres_query('pg', " + SqlIdent.sqlStr(query) + ")"
                    : "pg." + SqlIdent.q(schema) + "." + SqlIdent.q(table);
        }
    }

    // ── POST /onboard/postgres/test ───────────────────────────────────────────────────────────────────────────

    static Object test(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        OnboardRoutes.requireSelfImport(api, "Add data");
        Conn c = Conn.of(body.get("connection"));
        Path scratch = scratch(OnboardRoutes.requireDataRoot(api));
        try (Session s = Session.open(c, scratch)) {
            String who = s.requireReadOnlyRole();
            List<Map<String, Object>> tables = new ArrayList<>();
            boolean truncated = false;
            try (Statement st = s.conn.createStatement();
                 ResultSet r = st.executeQuery("SELECT table_schema, table_name, table_type FROM information_schema.tables "
                         + "WHERE table_catalog = 'pg' AND table_schema NOT IN ('pg_catalog', 'information_schema') "
                         + "ORDER BY 1, 2 LIMIT " + (LISTED + 1))) {
                while (r.next()) {
                    if (tables.size() == LISTED) {
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
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("connected", true);
            out.put("user", who);
            out.put("readOnly", true);
            out.put("tables", tables);
            out.put("truncated", truncated);
            return out;
        } finally {
            OnboardRoutes.deleteTree(scratch);
        }
    }

    // ── POST /onboard/postgres ────────────────────────────────────────────────────────────────────────────────

    static Object draft(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = OnboardRoutes.requireSelfImport(api, "Add data");
        Path dataRoot = OnboardRoutes.requireDataRoot(api);
        Conn c = Conn.of(body.get("connection"));
        Source src = Source.of(body);
        String kind = OnboardRoutes.kind(body.get("kind") instanceof String k ? k : null);
        LaLimitValues lim = OnboardRoutes.limits(writeRoot);

        OnboardRoutes.prune(dataRoot.resolve(OnboardRoutes.DRAFTS));
        String draftId = "d" + UUID.randomUUID().toString().replace("-", "");
        Path dir = dataRoot.resolve(OnboardRoutes.DRAFTS).resolve(draftId);
        String name = src.stem() + ".parquet";
        Path file = dir.resolve(OnboardRoutes.storedName(name));
        boolean kept = false;
        try {
            Files.createDirectories(dir);
            pull(c, src, dir, file, lim);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("source", SOURCE);
            snapshot.put("connection", c.toMap());
            snapshot.putAll(src.toMap());
            Object out = OnboardRoutes.answerDraft(writeRoot, ex, draftId, dir, file, name, kind, lim, Map.of(), snapshot);
            kept = true;
            return out;
        } finally {
            if (!kept) OnboardRoutes.deleteTree(dir);
        }
    }

    // ── the commit's part ─────────────────────────────────────────────────────────────────────────────────────

    static Path configFile(Path writeRoot, String id) {
        return writeRoot.resolve(OnboardImport.CONFIG_DIR).resolve(id + SUFFIX);
    }

    /** The snapshot's config as {@code imports/<id>_snapshot.toon} holds it: the connection (the password as its reference) and what to copy. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> config(Map<String, Object> draftSnapshot) {
        Conn c = Conn.of(draftSnapshot.get("connection"));   // re-validated: the draft file is data, not trusted config
        Source s = Source.fromMap(draftSnapshot);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", SOURCE);
        out.put("connection", c.toMap());
        out.putAll(s.toMap());
        return out;
    }

    static void write(Path file, Map<String, Object> config) throws IOException {
        Files.createDirectories(file.getParent());
        com.gamma.util.AtomicFiles.write(file, ConfigCodec.toToon(config).getBytes(StandardCharsets.UTF_8), ".cfg-");
    }

    static Map<String, Object> read(Path writeRoot, String id) {
        Path f = configFile(writeRoot, id);
        if (!Files.isRegularFile(f)) return null;
        try {
            return com.gamma.util.ToonHelper.load(f.toString());
        } catch (IOException | RuntimeException unreadable) {
            // fall through: refused below with the file named
        }
        throw new ApiException(409, ErrorCodes.CONFLICT, "the snapshot settings of '" + id + "' (" + OnboardImport.CONFIG_DIR + "/"
                + id + SUFFIX + ") cannot be read");
    }

    // ── refresh: Run now, and the poll ────────────────────────────────────────────────────────────────────────

    /**
     * Run now on a snapshot: EXPORT the table into a staged Parquet file under the data root's drafts folder (outside the
     * inbox, so neither a concurrent Run now nor anything reading the inbox sees it half-written), then hand the complete
     * file to the replace path ({@link OnboardImport#addStaged}), which MOVES it into the inbox and imports it.
     */
    static Object refresh(OnboardImport.Space space, Map<String, Object> content, String id, Map<String, Object> config,
                          HttpExchange ex) throws IOException {
        Conn c = Conn.of(config.get("connection"));
        Source src = Source.fromMap(config);
        String pipeline = String.valueOf(content.get("importPipeline"));
        LaLimitValues lim = OnboardRoutes.limits(space.writeRoot());
        String key = space.writeRoot() + "|" + pipeline;
        if (!OnboardImport.IN_FLIGHT.add(key)) throw new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is being imported right now");
        Path stage = space.dataRoot().resolve(OnboardRoutes.DRAFTS).resolve("a" + UUID.randomUUID().toString().replace("-", ""));
        String name = src.stem() + ".parquet";
        Path file = stage.resolve(OnboardRoutes.storedName(name));
        try {
            Files.createDirectories(stage);
            pull(c, src, stage, file, lim);
        } catch (IOException | RuntimeException fault) {
            OnboardImport.IN_FLIGHT.remove(key);
            OnboardRoutes.deleteTree(stage);   // a failed or half-written export never reaches the inbox
            throw fault;
        }
        return OnboardImport.addStaged(space, content, id, key, stage, file, name, "replace", ex, true);
    }

    // ── the copy ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Copy {@code src} into {@code target} as Parquet, typed as the import reads: a time-zoned timestamp becomes its UTC
     * wall time, a type the import has no column type for (UUID, JSON, arrays, intervals…) becomes text. 413 above the
     * Space's {@code import.max_rows}.
     */
    static long pull(Conn c, Source src, Path dir, Path target, LaLimitValues lim) {
        long maxRows = lim.get(LaLimit.IMPORT_MAX_ROWS);
        try (Session s = Session.open(c, dir)) {
            s.requireReadOnlyRole();   // on every copy: a login granted a write since the test is refused too
            List<String> cols = new ArrayList<>();
            try (Statement st = s.conn.createStatement();
                 ResultSet r = st.executeQuery("DESCRIBE SELECT * FROM " + src.relation())) {
                while (r.next()) cols.add(projection(r.getString(1), r.getString(2)));
            } catch (SQLException e) {
                throw s.failed("reading " + (src.table() != null ? "the table" : "the query"), e);
            }
            if (cols.isEmpty()) throw bad("the " + (src.table() != null ? "table" : "query") + " has no columns");
            String select = "SELECT " + String.join(", ", cols) + " FROM " + src.relation() + " LIMIT " + (maxRows + 1);
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
            if (rows > maxRows)
                throw new ApiException(413, ErrorCodes.PAYLOAD_TOO_LARGE, "the " + (src.table() != null ? "table" : "query")
                        + " holds more than " + maxRows + " rows; this Space's import.max_rows is " + maxRows);
            if (rows == 0) throw bad("the " + (src.table() != null ? "table" : "query") + " holds no rows");
            return rows;
        }
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

    // ── one connection ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * An in-memory DuckDB with the database attached read-only through a temporary secret, then sealed to {@code dir}
     * (the attach happens before the seal; afterwards no other attach and no file outside {@code dir}).
     */
    static final class Session implements AutoCloseable {
        final Connection conn;
        final String password;

        private Session(Connection conn, String password) {
            this.conn = conn;
            this.password = password;
        }

        static Session open(Conn c, Path dir) {
            String pw = SecretResolver.resolve(c.passwordRef());
            if (pw == null)
                throw bad("the password reference " + c.passwordRef() + " is not set on the server: set the environment variable "
                        + c.envName() + " where Inspecto runs, then restart it");
            try {
                Files.createDirectories(dir);
                Connection conn = DuckDbUtil.openInMemory(null, List.of(dir), x -> {
                    DuckDbExtension.ensureLoaded(x, "postgres_scanner", "reading a Postgres table (Add data)");
                    try (Statement st = x.createStatement()) {
                        st.execute("CREATE TEMPORARY SECRET onboard_pg (TYPE postgres, HOST " + SqlIdent.sqlStr(c.host())
                                + ", PORT " + c.port() + ", DATABASE " + SqlIdent.sqlStr(c.database())
                                + ", USER " + SqlIdent.sqlStr(c.user()) + ", PASSWORD " + SqlIdent.sqlStr(pw) + ")");
                    }
                    try (Statement st = x.createStatement()) {
                        st.execute("ATTACH '' AS pg (TYPE postgres, SECRET onboard_pg, READ_ONLY)");
                    }
                });
                return new Session(conn, pw);
            } catch (SQLException e) {
                throw PostgresSnapshot.failed(pw, "connecting to " + c.host() + ":" + c.port() + "/" + c.database() + " as " + c.user(), e);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        /** The role's name; 422 when it could write (superuser, or a write grant on any non-system table). */
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
                    throw bad("'" + who + "' is a superuser, which could change the database. Add data reads with a read-only "
                            + "login: create a role with only CONNECT, USAGE on the schema and SELECT on its tables");
                if (writable > 0)
                    throw bad("'" + who + "' may change " + writable + " table" + (writable == 1 ? "" : "s")
                            + " (INSERT, UPDATE, DELETE or TRUNCATE). Add data reads with a read-only login: grant it only "
                            + "CONNECT, USAGE on the schema and SELECT on the tables");
                return who;
            } catch (SQLException e) {
                throw failed("checking the role's rights", e);
            }
        }

        ApiException failed(String doing, SQLException e) {
            return PostgresSnapshot.failed(password, doing, e);
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

    /** 422 naming what failed, the database's own reason scrubbed of the password. */
    private static ApiException failed(String password, String doing, SQLException e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (password != null && !password.isEmpty()) msg = msg.replace(password, "***");
        msg = msg.replace('\n', ' ').trim();
        if (msg.length() > 400) msg = msg.substring(0, 400) + "…";
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "Postgres: " + doing + " failed: " + msg);
    }

    private static Path scratch(Path dataRoot) {
        return dataRoot.resolve(OnboardRoutes.DRAFTS).resolve("t" + UUID.randomUUID().toString().replace("-", ""));
    }

    private static String text(Map<?, ?> m, String key, Pattern p, String why) {
        Object v = m.get(key);
        String s = v instanceof String str ? str.trim() : null;
        if (s == null || s.isEmpty() || !p.matcher(s).matches()) throw bad(why);
        return s;
    }

    private static ApiException bad(String message) {
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, message);
    }

    /** Whether {@code id}'s import is a snapshot (its config file exists). */
    static boolean isSnapshot(Path writeRoot, String id) {
        return Files.isRegularFile(configFile(writeRoot, id));
    }
}
