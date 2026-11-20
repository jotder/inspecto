package com.gamma.control;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.config.io.ConfigCodec;
import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimitValues;
import com.gamma.inspector.PostgresCollectorConnector;
import com.gamma.inspector.PostgresConnectorFactory;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
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
 *       {@code POST /onboard/files/{draftId}/commit}, which saves the snapshot's Connection and
 *       {@code imports/<id>_snapshot.toon}.</li>
 *   <li>{@code POST /datasets/{id}/run} (Run now) on a snapshot Dataset re-copies it ({@link #refresh}).</li>
 * </ul>
 *
 * <p><b>How it reads</b> ({@code BASIC-ELT-PROFILE-1} BE-4): through the acquisition SPI's {@code postgres} scheme
 * ({@link PostgresCollectorConnector}, DuckDB's {@code postgres} extension - no JDBC driver on Basic), the same connector a
 * Collector Pipeline with {@code collector.connector: postgres} uses. It owns the session, the read-only checks, the typed
 * copy and the {@code ${ENV:INSPECTO_PG_…}} rule; this class is the HTTP edge and Add data's wording.
 *
 * <p><b>A saved Connection</b> (operator ruling O4, 2026-11-19): the commit saves the snapshot's connection as an ordinary
 * {@code postgres} Connection, {@code <id>_postgres_connection.toon} under the write root, through the same save as
 * {@code POST /connections} - so the scheme's check refuses any secret that is not an {@code INSPECTO_PG_} reference - and
 * {@code _snapshot.toon} only names it. A snapshot committed before this keeps its connection inline in
 * {@code _snapshot.toon} (validated exactly as a request body) until its next Run now migrates it ({@link #switchPipeline}).
 *
 * <p><b>The Pipeline reads Postgres itself</b> (operator ruling O7, 2026-11-20): after a commit's first run, or at an old
 * snapshot's next Run now, its Pipeline is switched to {@code collector.connector: postgres} through that Connection, with
 * {@code output.load: replace} - so a run outside Add data copies the table again and replaces the rows ({@link #switchPipeline}).
 *
 * <p><b>Export, then move</b> (operator 2026-11-05): Run now EXPORTS the table into a temporary folder under the data
 * root's drafts folder - never inside the inbox - and only once the file is complete MOVES it atomically into the
 * Dataset's inbox, where the ordinary import path takes it like an uploaded file: name-checked columns, the row filter,
 * the arrival, compaction. A snapshot always replaces everything. <b>A snapshot is manual only:</b> nothing polls it.
 */
final class PostgresSnapshot {

    private PostgresSnapshot() {}

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PostgresSnapshot.class);

    /** The Dataset entry's {@code importSource} for a snapshot (display only; {@link #configFile} is the authority). */
    static final String SOURCE = "postgres";
    static final String SUFFIX = "_snapshot.toon";
    /** A snapshot's saved Connection is named {@code <dataset id>}{@value}. */
    static final String CONNECTION_SUFFIX = "_postgres";
    /** At most this many tables are listed by the connection test. */
    static final int LISTED = 500;
    static final int MAX_QUERY_CHARS = PostgresCollectorConnector.MAX_QUERY_CHARS;
    /** Only a variable named {@value}…: see {@link PostgresCollectorConnector}. */
    static final String ENV_PREFIX = PostgresCollectorConnector.ENV_PREFIX;
    /** The id an unsaved profile carries while a request is tested or drafted. */
    private static final String UNSAVED = "add-data-postgres";

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
            String host = text(m, "host", PostgresCollectorConnector.HOST, "host must be a host name or address");
            int port = PostgresCollectorConnector.DEFAULT_PORT;
            if (m.get("port") != null) {
                try {
                    port = Integer.parseInt(String.valueOf(m.get("port")).trim());
                } catch (NumberFormatException e) {
                    port = -1;
                }
                if (port < 1 || port > 65_535) throw bad("port must be 1-65535");
            }
            String database = text(m, "database", PostgresCollectorConnector.NAME, "database must be a Postgres database name");
            String user = text(m, "user", PostgresCollectorConnector.NAME, "user must be a Postgres role name");
            Object pw = m.get("password");
            String ref = pw instanceof String s ? s.trim() : "";
            // the value is never echoed: only whether it looked like a reference
            if (!PostgresCollectorConnector.PASSWORD_REF.matcher(ref).matches())
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

        /** This connection as a {@code postgres} Connection; {@code src} (what to copy) is its options, or none for a test. */
        ConnectionProfile profile(String id, Source src) {
            return new ConnectionProfile(id, PostgresCollectorConnector.SCHEME, host, port, database, null, user, passwordRef,
                    src == null ? Map.of() : src.options(), null, null);
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
                if (!PostgresCollectorConnector.READ_ONLY_QUERY.matcher(query).matches())
                    throw bad("the query must be a read-only SELECT (or WITH … SELECT); Add data never writes to your database");
                return new Source(null, null, query);
            }
            String schema = text(body, "schema", PostgresCollectorConnector.IDENT, "schema must be a schema name from the connection test");
            String table = text(body, "table", PostgresCollectorConnector.IDENT, "table must be a table name from the connection test");
            return new Source(schema, table, null);
        }

        /** From a stored draft or a pre-Connection {@code _snapshot.toon}: data, not trusted - checked exactly as a request body is. */
        static Source fromMap(Map<String, Object> m) {
            return of(m);
        }

        Map<String, Object> toMap() {
            return new LinkedHashMap<>(options());
        }

        /** The Connection's {@code options}: {@code schema} + {@code table}, or {@code query}. */
        Map<String, String> options() {
            Map<String, String> out = new LinkedHashMap<>();
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
    }

    // ── POST /onboard/postgres/test ───────────────────────────────────────────────────────────────────────────

    static Object test(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        OnboardRoutes.requireSelfImport(api, "Add data");
        Conn c = Conn.of(body.get("connection"));
        Path scratch = scratch(OnboardRoutes.requireDataRoot(api));
        try {
            PostgresCollectorConnector.Listing l = answered(() -> PostgresConnectorFactory.forTest(c.profile(UNSAVED, null))
                    .listTables(scratch, LISTED), 0);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("connected", true);
            out.put("user", l.user());
            out.put("readOnly", true);
            out.put("tables", l.tables());
            out.put("truncated", l.truncated());
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
            pull(c.profile(UNSAVED, src), file, lim);
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

    /** The saved Connection a snapshot of Dataset {@code id} reads through. */
    static String connectionId(String id) {
        return id + CONNECTION_SUFFIX;
    }

    /**
     * What a commit saves for a snapshot: the Connection, and the {@code _snapshot.toon} that names it. Decided before the
     * commit writes anything: 409 when a Connection of that id exists, 422 when the Connection save would refuse it.
     */
    record Saved(ConnectionProfile connection, Path connectionFile, CollectorService service, Map<String, Object> config) {

        /** Resolved on the request thread: a commit that runs on in the background undoes in the same Space. */
        static Saved plan(ApiContext api, String id, Map<String, Object> draftSnapshot) {
            Conn c = Conn.of(draftSnapshot.get("connection"));   // re-validated: the draft file is data, not trusted config
            Source s = Source.fromMap(draftSnapshot);
            String connId = connectionId(id);
            ConnectionProfile p = c.profile(connId, s);
            CollectorService service = HostContext.of(api).service();
            Path connectionFile = ConnectionRoutes.connectionFile(api, connId);
            if (service.connection(connId).isPresent() || Files.exists(connectionFile))
                throw new ApiException(409, ErrorCodes.CONFLICT, "a Connection '" + connId + "' already exists; choose another name");
            try {
                ConnectionRoutes.validateProfile(p);   // the check every Connection save runs, the scheme's INSPECTO_PG_ rule among them
            } catch (IllegalArgumentException refused) {
                throw bad(refused.getMessage());
            }
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("source", SOURCE);
            config.put("connection", connId);
            return new Saved(p, connectionFile, service, config);
        }

        /** Save the Connection (as {@code POST /connections} does), then the snapshot settings. On the request thread. */
        void write(ApiContext api, Path snapshotFile) throws IOException {
            ConnectionRoutes.persistConnection(api, connection);
            PostgresSnapshot.write(snapshotFile, config);
        }

        /** Undo {@link #write}: a failed commit leaves no snapshot settings and no Connection behind. */
        void undo(Path snapshotFile) throws IOException {
            Files.deleteIfExists(snapshotFile);
            Files.deleteIfExists(connectionFile);
            service.unregisterConnection(connection.id());
        }
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
        throw unreadable(id);
    }

    /**
     * The Connection a snapshot reads through: the saved one {@code _snapshot.toon} names, or - for a snapshot committed
     * before Connections - its inline {@code connection} block with the table or query beside it, validated exactly as a
     * request body is. 409 when the named Connection is gone or unreadable.
     */
    @SuppressWarnings("unchecked")
    static ConnectionProfile connectionOf(Path writeRoot, String id, Map<String, Object> config) {
        Object c = config.get("connection");
        if (c instanceof Map<?, ?>)   // the pre-Connection shape, until Run now migrates it (switchPipeline)
            return Conn.of(c).profile(connectionId(id), Source.fromMap(config));
        if (!(c instanceof String connId) || connId.isBlank()) throw unreadable(id);
        Path file = ConnectionRoutes.connectionFile(writeRoot, connId.trim());
        if (!Files.isRegularFile(file))
            throw new ApiException(409, ErrorCodes.CONFLICT, "the Connection '" + connId.trim() + "' that '" + id
                    + "' reads through is gone; add the table again");
        try {
            return ConnectionProfile.load(file);
        } catch (IOException | RuntimeException unreadableConnection) {
            throw new ApiException(409, ErrorCodes.CONFLICT, "the Connection '" + connId.trim() + "' that '" + id + "' reads through cannot be read");
        }
    }

    /**
     * Retire a snapshot's settings with its Dataset: {@code _snapshot.toon}, and the Connection the commit saved for it
     * ({@link #connectionId}) unless a Pipeline has since been bound to it. A Connection of any other name is left alone.
     */
    static void retire(Path writeRoot, String id, CollectorService service) throws IOException {
        Path f = configFile(writeRoot, id);
        Object named = null;
        if (Files.isRegularFile(f)) {
            try {
                named = com.gamma.util.ToonHelper.load(f.toString()).get("connection");
            } catch (IOException | RuntimeException unreadable) {
                // nothing known to retire beside the file itself
            }
        }
        String connId = connectionId(id);
        if (connId.equals(named) && !service.connectionInUse(connId)) {
            Files.deleteIfExists(ConnectionRoutes.connectionFile(writeRoot, connId));
            service.unregisterConnection(connId);
        }
        Files.deleteIfExists(f);
    }

    /**
     * The Dataset whose snapshot reads through the saved Connection {@code connId}, or null (BE-4 part 2: what
     * {@code DELETE /connections/{id}} refuses). A snapshot reads through the Connection its {@code _snapshot.toon} names; one
     * whose file cannot be read is taken to read through the Connection its commit saves ({@link #connectionId}), so an
     * unreadable file never lets that Connection go.
     */
    static String readBy(Path writeRoot, String connId) throws IOException {
        Path dir = writeRoot.resolve(OnboardImport.CONFIG_DIR);
        if (connId == null || !Files.isDirectory(dir)) return null;
        String wanted = connId.trim();
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files.sorted()::iterator) {
                String name = f.getFileName().toString();
                if (!name.endsWith(SUFFIX) || !Files.isRegularFile(f)) continue;
                String id = name.substring(0, name.length() - SUFFIX.length());
                Object named;
                try {
                    named = com.gamma.util.ToonHelper.load(f.toString()).get("connection");
                } catch (IOException | RuntimeException unreadableFile) {
                    named = connectionId(id);
                }
                if (named instanceof String s && wanted.equals(s.trim())) return id;
            }
        }
        return null;
    }

    private static ApiException unreadable(String id) {
        return new ApiException(409, ErrorCodes.CONFLICT, "the snapshot settings of '" + id + "' (" + OnboardImport.CONFIG_DIR + "/"
                + id + SUFFIX + ") cannot be read");
    }

    // ── the Pipeline reads Postgres itself (O7) ──────────────────────────────────────────────────────────────

    /**
     * <b>The switch</b> ({@code BASIC-ELT-PROFILE-1} O7, operator 2026-11-20): the snapshot's Pipeline reads the table
     * itself - {@code collector.connector: postgres} through the saved Connection - so any run of it (Run now,
     * {@code POST /pipelines/{id}/run}, a trigger) copies the table again. Two more keys make that run right:
     * {@code output.load: replace} (a run outside Add data replaces the snapshot's rows at its commit instead of adding a
     * second full copy; Add data's own runs choose per run, {@link OnboardImport.Job#runLoad}, which wins) and
     * {@code processing.duplicate_check.enabled: false} (the Pipeline never refuses its own re-export; each export is named
     * apart, {@code PostgresCollectorConnector#cycleName}).
     *
     * <p>ONE function, idempotent, for both ways in: a snapshot commit, after its first run; and an old snapshot, at its
     * next Run now - which first saves the Connection {@code <id>_postgres} from the inline block and rewrites
     * {@code _snapshot.toon} to name it ({@link #migrate}). Each step looks before it writes, so a switch a crash cut short
     * is finished by the next call and a switched Pipeline is left as it is. The Pipeline is rewritten through
     * {@link DatasetFeed#rewriteHeld}: the approval policy, the save gate, the audit and the Pipeline's history. The caller
     * holds the import's claim ({@link OnboardImport#IN_FLIGHT}).
     */
    static void switchPipeline(ApiContext api, OnboardImport.Space space, String id) throws IOException {
        Map<String, Object> config = read(space.writeRoot(), id);
        if (config == null) return;
        Object named = config.get("connection");
        boolean migrated = named instanceof String s && !s.isBlank();
        String connId = migrated ? ((String) named).trim() : connectionId(id);
        Path pipelineFile = space.writeRoot().resolve(OnboardImport.CONFIG_DIR).resolve(id + "_pipeline.toon").toAbsolutePath().normalize();
        boolean switched = switched(com.gamma.util.ToonHelper.load(pipelineFile.toString()), connId);
        if (migrated && switched) return;
        // the approval policy first: a switch it holds writes nothing - no Connection saved, no settings rewritten
        PendingChanges.holdRefusing(api, java.util.List.of("pipeline"), "the snapshot's Pipeline is switched to read Postgres itself");
        String through = migrate(api, space, id, config);
        if (switched) return;
        DatasetFeed.rewriteHeld(api, new DatasetFeed.Import(space.writeRoot(), space.dataRoot(), space.service(), id, Map.of(), pipelineFile),
                p -> switchTo(p, through), null);
    }

    /** {@link #switchPipeline} where it must not fail its caller (a commit that already succeeded): a WARN, and the next Run now switches. */
    static void switchAfterCommit(ApiContext api, OnboardImport.Space space, String id) {
        try {
            switchPipeline(api, space, id);
        } catch (IOException | RuntimeException notSwitched) {
            log.warn("Postgres snapshot '{}': its Pipeline was not switched to read Postgres itself ({}); its next Run now does it",
                    id, notSwitched.getMessage());
        }
    }

    /**
     * The saved Connection the snapshot reads through. A snapshot committed before Connections keeps its connection inline:
     * it is saved as {@code <id>_postgres} (through the save {@code POST /connections} runs) and {@code _snapshot.toon} is
     * rewritten to name it. A Connection of that name already saved for this same table (a migration a crash cut short) is
     * taken as it is; one that reads anything else is 409, never overwritten.
     */
    private static String migrate(ApiContext api, OnboardImport.Space space, String id, Map<String, Object> config) throws IOException {
        Object c = config.get("connection");
        if (c instanceof String named && !named.isBlank()) return named.trim();
        if (!(c instanceof Map<?, ?>)) throw unreadable(id);
        String connId = connectionId(id);
        ConnectionProfile p = Conn.of(c).profile(connId, Source.fromMap(config));   // checked exactly as a request body
        Path file = ConnectionRoutes.connectionFile(space.writeRoot(), connId);
        if (Files.isRegularFile(file)) {
            ConnectionProfile saved;
            try {
                saved = ConnectionProfile.load(file);
            } catch (IOException | RuntimeException unreadableConnection) {
                saved = null;
            }
            if (saved == null || !sameSource(saved, p))
                throw new ApiException(409, ErrorCodes.CONFLICT, "a Connection '" + connId + "' already exists and does not read the table '"
                        + id + "' copies; rename or remove it, then Run now again");
            if (space.service().connection(connId).isEmpty()) space.service().registerConnection(saved);
        } else {
            ConnectionRoutes.persistConnection(api, p);
        }
        Map<String, Object> named = new LinkedHashMap<>();
        named.put("source", SOURCE);
        named.put("connection", connId);
        write(configFile(space.writeRoot(), id), named);
        log.info("Postgres snapshot '{}': its inline connection is now the saved Connection '{}'", id, connId);
        return connId;
    }

    /** Whether two {@code postgres} profiles read the same thing the same way (the id and the folder aside). */
    static boolean sameSource(ConnectionProfile a, ConnectionProfile b) {
        int pa = a.port() == ConnectionProfile.NO_PORT ? PostgresCollectorConnector.DEFAULT_PORT : a.port();
        int pb = b.port() == ConnectionProfile.NO_PORT ? PostgresCollectorConnector.DEFAULT_PORT : b.port();
        return PostgresCollectorConnector.SCHEME.equals(a.connector()) && PostgresCollectorConnector.SCHEME.equals(b.connector())
                && pa == pb && java.util.Objects.equals(a.host(), b.host()) && java.util.Objects.equals(a.database(), b.database())
                && java.util.Objects.equals(a.username(), b.username())
                && java.util.Objects.equals(a.password() == null ? null : a.password().trim(), b.password() == null ? null : b.password().trim())
                && a.options().equals(b.options()) && a.tunnel() == null && b.tunnel() == null && a.proxy() == null && b.proxy() == null;
    }

    /** Whether the Pipeline config {@code p} is already switched to read through {@code connId}. */
    @SuppressWarnings("unchecked")
    static boolean switched(Map<String, Object> p, String connId) {
        Map<String, Object> collector = p.get("collector") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> output = p.get("output") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> processing = p.get("processing") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> dup = processing.get("duplicate_check") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return PostgresCollectorConnector.SCHEME.equals(String.valueOf(collector.get("connector")))
                && connId.equals(String.valueOf(collector.get("connection")))
                && "replace".equals(String.valueOf(output.get("load")))
                && "false".equals(String.valueOf(dup.get("enabled")));
    }

    /** The switch's edit of the Pipeline config, every other key kept. */
    @SuppressWarnings("unchecked")
    static void switchTo(Map<String, Object> p, String connId) {
        Map<String, Object> collector = p.get("collector") instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        collector.put("connector", PostgresCollectorConnector.SCHEME);
        collector.put("connection", connId);
        p.put("collector", collector);
        Map<String, Object> output = p.get("output") instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        output.put("load", "replace");
        p.put("output", output);
        Map<String, Object> processing = p.get("processing") instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        Map<String, Object> dup = processing.get("duplicate_check") instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        dup.put("enabled", false);
        processing.put("duplicate_check", dup);
        p.put("processing", processing);
    }

    // ── refresh: Run now ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Run now on a snapshot: switch its Pipeline first ({@link #switchPipeline}: an old snapshot migrates here), then
     * EXPORT the table into a staged Parquet file under the data root's drafts folder (outside the inbox, so neither a
     * concurrent Run now nor anything reading the inbox sees it half-written) - capped at {@code import.max_rows}, refused
     * in Add data's words - and hand the complete file to the replace path ({@link OnboardImport#addStaged}), which checks
     * its columns, MOVES it into the inbox and imports it, replacing the snapshot's rows at its commit (a run-scoped
     * {@code replace}). That run fetches nothing itself ({@code RunFiles.noFetch}): the export it checked is its one copy.
     * A switch that cannot be made never stops Run now: it is logged, and the next Run now tries again.
     */
    static Object refresh(ApiContext api, OnboardImport.Space space, Map<String, Object> content, String id,
                          HttpExchange ex) throws IOException {
        String pipeline = String.valueOf(content.get("importPipeline"));
        String key = space.writeRoot() + "|" + pipeline;
        if (!OnboardImport.IN_FLIGHT.add(key)) throw new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is being imported right now");
        Path stage = space.dataRoot().resolve(OnboardRoutes.DRAFTS).resolve("a" + UUID.randomUUID().toString().replace("-", ""));
        Path file;
        String name;
        try {
            try {
                switchPipeline(api, space, id);
            } catch (IOException | RuntimeException notSwitched) {
                log.warn("Postgres snapshot '{}': its Pipeline was not switched to read Postgres itself ({}); Run now copies "
                        + "the table as before and the next Run now tries again", id, notSwitched.getMessage());
            }
            Map<String, Object> config = read(space.writeRoot(), id);
            if (config == null) throw unreadable(id);
            ConnectionProfile profile = connectionOf(space.writeRoot(), id, config);
            name = answered(() -> PostgresConnectorFactory.connector(profile, 0).fileName(), 0);   // the profile, checked
            LaLimitValues lim = OnboardRoutes.limits(space.writeRoot());
            file = stage.resolve(OnboardRoutes.storedName(name));
            Files.createDirectories(stage);
            pull(profile, file, lim);
        } catch (IOException | RuntimeException fault) {
            OnboardImport.IN_FLIGHT.remove(key);
            OnboardRoutes.deleteTree(stage);   // a failed or half-written export never reaches the inbox
            throw fault;
        }
        return OnboardImport.addStaged(space, content, id, key, stage, file, name, "replace", ex, true);
    }

    // ── the copy ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Copy what {@code profile} names into {@code target} as typed Parquet through the {@code postgres} scheme. 413 above
     * the Space's {@code import.max_rows}.
     */
    static long pull(ConnectionProfile profile, Path target, LaLimitValues lim) {
        long maxRows = lim.get(LaLimit.IMPORT_MAX_ROWS);
        return answered(() -> PostgresConnectorFactory.connector(profile, maxRows).export(target), maxRows);
    }

    /** The connector's refusals as Add data answers them: 413 over the row cap, 422 for everything else. */
    private static <T> T answered(Supplier<T> work, long maxRows) {
        try {
            return work.get();
        } catch (PostgresCollectorConnector.Refused r) {
            if (r.tooLarge())
                throw new ApiException(413, ErrorCodes.PAYLOAD_TOO_LARGE, r.getMessage() + "; this Space's import.max_rows is " + maxRows);
            throw bad(r.getMessage());
        } catch (IllegalArgumentException refused) {
            throw bad(refused.getMessage());
        }
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
