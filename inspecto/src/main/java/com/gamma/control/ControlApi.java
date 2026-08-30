package com.gamma.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.api.PublicApi;
import com.gamma.event.EventLog;
import com.gamma.service.CollectorService;
import com.gamma.service.SpaceContext;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded REST control plane for a running {@link CollectorService} (M3). Built on the
 * JDK's {@link HttpServer} (no extra dependencies — keeps the lean fat-JAR) with
 * Jackson for JSON. Every CLI operation has an HTTP equivalent: list/trigger/pause/
 * resume pipelines, query runs/batches/files/lineage/quarantine via the
 * {@link com.gamma.etl.StatusStore}, reprocess a batch, and validate a config.
 *
 * <h3>Authentication (W6)</h3>
 * The core (Personal edition) is <b>auth-free</b> — every route is open, exactly as before. Authentication
 * and authorization are an <em>edition</em> concern: {@link #dispatch} looks up an {@link Authenticator}
 * via {@link Authenticators} (a {@code ServiceLoader} seam); when the Standard edition's
 * {@code inspecto-security} module is absent, the lookup is empty and nothing is enforced. When it is
 * present, every route outside the health/bootstrap probe surface requires a valid credential
 * ({@code 401 UNAUTHENTICATED} on failure); write routes additionally declare a required capability via
 * {@link ApiContext#withCapability} ({@code 403 PERMISSION_DENIED} on a missing grant). See
 * {@code docs/EDITIONS.md}.
 *
 * <h3>Per-space routing</h3>
 * One process hosts many isolated spaces (see {@link SpaceManager}). Every route below may be addressed under a
 * {@code /spaces/{id}} prefix ({@code GET /spaces/acme/runs}); {@link #dispatch} strips the prefix, binds the
 * request to that space, and matches the <em>unchanged</em> patterns against the remainder, so each space's
 * service/stores/events/metric-label resolve in isolation. An unknown id is a {@code 404}. {@code /health},
 * {@code /ready}, {@code /metrics} (and the future {@code /spaces} CRUD group) stay un-prefixed and server-global;
 * an un-prefixed API path resolves the {@code default} (or sole) space, so single-space callers are unaffected.
 *
 * <h3>Versioned API (v1) — v4.8.0</h3>
 * The route table is served under {@code /api/v1/…} with the v1 transport contract
 * (docs/superpower/api-contract-design.md): responses wrapped in the
 * {@code {data, metadata, links, diagnostics}} envelope ({@link Envelope}), errors as structured
 * objects with machine-readable codes ({@link ErrorCodes}), a per-request {@code Correlation-ID}
 * (issued when absent; echoed on every response), and gzip content negotiation.
 *
 * <p><b>API-5 (2026-07-25): {@code /api/v1} is the only API surface.</b> The unversioned aliases that
 * predated it are retired — {@link #routeDispatch} matches the route table only for a v1 request or an
 * {@link #isInfraRoute} probe ({@code /health}, {@code /ready}, {@code /metrics},
 * {@code /metrics/acquisition}), which stay unversioned because health checks and metric scrapers have
 * no v1 semantics. A bare business path is no longer an API call: a GET falls through to the SPA (those
 * paths double as Angular deep links), anything else is a {@code 404}. An {@code /api/…} path that is
 * not {@code /api/v1} is an unmigrated client and gets a JSON {@code 404} from {@link #normalizePath}.
 *
 * <h3>Routes</h3>
 * <pre>
 *   GET  /health                              liveness (open)
 *   GET  /ready                               readiness (open)
 *   GET  /bootstrap                           platform bootstrap: edition/features/config-specs/enums/spaces/session (ETag'd) [v4.8.0]
 *   POST /auth/exchange                       redeem an OIDC code (PKCE) → access token + httpOnly refresh cookie [v4.8.0, 503 on Personal]
 *   POST /auth/refresh                        mint a fresh access token from the refresh cookie      [v4.8.0, 503 on Personal]
 *   POST /auth/logout                         revoke (best-effort) + clear the session cookie        [v4.8.0, 503 on Personal]
 *   GET  /spaces                              list hosted spaces (manifests)               [v4.7.0]
 *   POST /spaces                              body {id,display_name?,description?} — create + boot a space [v4.7.0]
 *   PUT  /spaces/{id}                         body {display_name?,description?} — rename/re-describe (not 'default') [v4.10.0]
 *   DELETE /spaces/{id}[?purge=true]          deregister + drain a space; purge also deletes its files [v4.7.0]
 *   GET  /runs                           list pipelines + state
 *   POST /runs                           body {"configPath":"…"} — register a new pipeline   [v4.1.0]
 *   POST /runs/{name}/trigger            run one pipeline once
 *   POST /runs/{name}/pause              pause (poll cycle skips it)
 *   POST /runs/{name}/resume             resume
 *   GET  /runs/{name}/commits            committed batch ids
 *   GET  /runs/{name}/batches            batch audit rows
 *   GET  /runs/{name}/files              per-file audit rows
 *   GET  /runs/{name}/files/stage?path=  a file's recorded Stage-C progression [v5.1.0, Phase 4 §2.4]
 *   GET  /runs/{name}/lineage[?batchId=] input→output lineage rows
 *   GET  /runs/{name}/quarantine         quarantined inputs + reason
 *   POST /runs/{name}/reprocess          body {"batchId":"…"} — replay a batch
 *   POST /trigger                             run all pipelines once
 *   POST /validate                            body {"configPath":"…"} or {"type":…,"config":{…}} — findings
 *   GET  /status                              live status snapshot (all pipelines)        [v2.8.0]
 *   GET  /report[?from=&to=]                  service-wide batch-audit report             [v2.8.0]
 *   GET  /runs/{name}/report[?from=&to=] batch-audit report for one pipeline         [v2.8.0]
 *   GET  /jobs                                list config-driven jobs + last/next run      [v2.8.0]
 *   GET  /jobs/{name}/runs                    recent run history for a job                 [v2.8.0]
 *   POST /jobs/{name}/trigger                 run a job once now (v1: 202 + runId + Location)  [v2.8.0]
 *   GET  /jobs/runs/{runId}                   poll one job run's status (RUNNING → terminal)   [v4.8.0]
 *   GET  /enrichment                          list Stage-2 enrichment jobs + last run      [v2.9.0]
 *   GET  /enrichment/{job}/runs               enrichment run-audit rows                    [v2.9.0]
 *   GET  /enrichment/{job}/lineage[?runId=]   enrichment output lineage rows               [v2.9.0]
 *   GET  /enrichment/{job}/report[?from=&to=] run-audit rollup for one enrichment job      [v2.9.0]
 *   GET  /catalog                             metadata-graph table list                    [v3.2.0]
 *   GET  /catalog/tables/{id}                 one node + overlay + neighbours              [v3.2.0]
 *   GET  /catalog/kpis                        KPI catalog + domain notes                   [v3.2.0]
 *   GET  /catalog/graph[?from=&depth=&direction=&kinds=&edgeKinds=&overlay=]  subgraph      [v3.2.0]
 *   GET  /config/spec/{type}                  declarative spec for a config type           [v3.2.0]
 *   POST /assist/{intent}                     run an assist skill (e.g. explain-entity)    [v3.3.0]
 *   POST /config/write                        body {type,config,subdir?,overwrite?} — persist a config [v4.1.0]
 *   GET  /settings/branding                   per-space UI branding {logoDataUrl,caption,footerText}  [v4.10.0]
 *   PUT  /settings/branding                   replace per-space UI branding (write-root gated)         [v4.10.0]
 *   POST /queries/{id}/run                     run a persisted query ($-params resolved, Result Set contract) [v4.8.0]
 *   GET  /events[?limit=]                     recent events, newest-first (live tail)       [v4.2.0]
 *   GET  /events/search[?level=&type=&pipeline=&correlationId=&q=&from=&to=&limit=&offset=] filtered events [v4.2.0]
 *   GET  /events/{id}                         one event by id                               [v4.2.0]
 *   GET  /events/export[?format=csv&…filters] export matching events (csv | json)           [v4.2.0]
 *   GET  /events/views                        list operator-saved views                     [v4.2.0]
 *   POST /events/views                        body {name,level?,type?,pipeline?,q?,…} — upsert a view [v4.2.0]
 *   POST /events/views/{name}/delete          delete a saved view                           [v4.2.0]
 *   GET  /objects[?type=&status=&severity=&assignee=&owner=&correlationId=&q=&limit=&offset=] filtered objects [v4.3.0]
 *   POST /objects                             body {type?,title,severity?,priority?,assignee?,dueAt?|dueInMinutes?,…} — create (INCIDENT) [v4.4.0]
 *   GET  /objects/{id}                        one object by id                              [v4.3.0]
 *   POST /objects/{id}/ack | /resolve         fixed-action lifecycle transition (ALERT)     [v4.3.0]
 *   POST /objects/{id}/transition             body {action} or {status|to} (+ actor?) — any workflow move [v4.3.0]
 *   POST /objects/{id}/links                  body {to,relationship?,actor?} — correlate two objects (CASE) [v4.5.0]
 *   GET  /objects/{id}/links                  links incident to this object                 [v4.5.0]
 *   GET  /objects/{id}/graph[?depth=]         correlation subgraph (nodes + edges)          [v4.5.0]
 *   POST /objects/{id}/comments               body {body,author?} — add a comment           [v4.6.0]
 *   GET  /objects/{id}/comments               list comments (newest-first)                  [v4.6.0]
 *   POST /objects/{id}/attachments            body {name,uri,contentType?,author?} — evidence ref [v4.6.0]
 *   GET  /objects/{id}/attachments            list attachment references                    [v4.6.0]
 *   POST /objects/{id}/rca                     body {sections[]} | {template:{…}} | {template:"name"} — seed RCA skeleton [v4.6.0]
 *   GET  /rca/templates                       RCA templates loaded from *_rca.toon          [v4.6.0]
 *   GET  /connections                         reusable connection profiles (secret-masked)  [v4.2.0]
 *   GET  /connections/{id}                    one connection profile (secret-masked)        [v4.2.0]
 *   POST /connections/{id}/test               TCP-reachability + secret-resolution test     [v4.2.0]
 *   POST /connections                         body {id,connector,…} — create (write-root gated); 409 if id exists [v4.2.0]
 *   PUT  /connections/{id}                    replace a profile (masked secrets preserved); 404 if unknown [v4.2.0]
 *   DELETE /connections/{id}                  remove a profile; 404 if unknown, 409 if in use by a pipeline [v4.2.0]
 * </pre>
 *
 * <p>The {@code /catalog*}, {@code /config/spec/*} and {@code /assist/*} routes require the
 * {@code assist.read} scope (satisfied by {@code control}); they expose the M1 metadata graph, the
 * M2 declarative config specs, and the M3 in-process assist agent for the UI and the agent. The
 * {@code /assist/*} route delegates to the optional {@code inspecto-agent} module when it is
 * on the classpath; with no agent present it returns {@code 503}, leaving the core unchanged.</p>
 *
 * <p>Report routes accept an optional inclusive date range {@code ?from=&to=} (v2.10.0) —
 * a date ({@code 2026-05-01}) or datetime ({@code 2026-05-01 09:00:00}); a date-only
 * {@code to} covers the whole day. Reports also carry duration percentiles (p50/p95/p99).
 *
 * <h3>UI hosting (v4.1.0)</h3>
 * Two optional, pure-JDK additions let a single process serve both the JSON API and the operator
 * SPA, with no new dependencies:
 * <ul>
 *   <li><b>CORS</b> — set {@code -Dcontrol.cors=<origin>} (e.g. {@code http://localhost:4200}, or
 *       {@code *}) to emit {@code Access-Control-Allow-*} headers and answer {@code OPTIONS}
 *       preflights with {@code 204}. Unset (the default) ⇒ behaviour is byte-for-byte unchanged.</li>
 *   <li><b>Static SPA</b> — set {@code -Dui.dir=<dir>} to serve the built Angular app from disk as a
 *       {@code PUBLIC} fallback for any {@code GET} that matches no API route. A request for a file
 *       that exists is served with its MIME type; an extensionless path (an SPA deep link) falls back
 *       to {@code index.html}. API paths that match a route keep returning JSON (incl. JSON 404s).</li>
 *   <li><b>HTTPS (W6)</b> — set {@code -Dhttps.keystore=<PKCS12 path>} (+
 *       {@code -Dhttps.keystore.password=<pw>}) to serve over TLS 1.3 instead of plain HTTP. Unset
 *       (the default) ⇒ plain HTTP, byte-for-byte unchanged (Personal edition).</li>
 * </ul>
 */
@PublicApi(since = "2.4.0")
public final class ControlApi implements AutoCloseable, ApiContext {

    private static final Logger log = LoggerFactory.getLogger(ControlApi.class);
    private static final Object HANDLED = ApiContext.HANDLED;

    /**
     * Captures a per-space request prefix {@code /spaces/<id>/<rest>}: group 1 = the space id (same charset as
     * {@link SpaceId}), group 2 = the remaining path (with leading {@code /}) matched against the unchanged route
     * table. {@code /spaces} and {@code /spaces/<id>} with no trailing path deliberately do <em>not</em> match —
     * they stay server-global for the {@code SpaceRoutes} CRUD group.
     */
    private static final Pattern SPACE_PREFIX = Pattern.compile("^/spaces/([a-z0-9][a-z0-9-]{0,62})(/.*)$");

    /** Routes that stay open even when the Standard edition's security module is active (W6): liveness/
     *  readiness/metrics probes carry no credentials; {@code /bootstrap} is how the SPA discovers it
     *  needs to start the OIDC redirect in the first place (its own {@code session.authenticated} reports
     *  {@code false} rather than 401); and the {@code /auth/*} session routes (W6d) run <em>before</em> a
     *  Bearer token exists — their credential is the code being redeemed or the {@code httpOnly} refresh
     *  cookie. Everything else requires a valid {@link Authenticator} result. */
    private static final java.util.Set<String> PUBLIC_PATHS = java.util.Set.of(
            "/health", "/ready", "/metrics", "/bootstrap",
            "/auth/exchange", "/auth/refresh", "/auth/logout");

    private final HttpServer http;
    private final SpaceManager spaces;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Route> routes = new ArrayList<>();
    /** S6 — the composed request pipeline: an ordered chain of cross-cutting {@link Middleware} wrapping the
     *  terminal route dispatch. Built once in the constructor, after the route table is registered. */
    private final Chain pipeline;
    /** Allowed CORS origin ({@code -Dcontrol.cors}); {@code null} ⇒ CORS disabled (default). */
    private final String corsOrigin;
    /** Static SPA root ({@code -Dui.dir}); {@code null} ⇒ no static serving (default). */
    private final Path uiDir;
    /**
     * Filesystem root under which {@code POST /config/write} may persist authored configs
     * ({@code -Dassist.write.root}); {@code null} ⇒ config writes are disabled (the route returns
     * 503). Made absolute + normalised so the write path-jail ({@code startsWith}) is meaningful.
     */
    private final Path writeRoot;

    /** Per-instance {@code Idempotency-Key} replay cache for retryable writes (W5). */
    private final Idempotency.Store idempotency = new Idempotency.Store();

    /**
     * Control plane over a single running service — wrapped as the {@code default} space. The long-standing
     * single-tenant entry point (and every test); behaviour is unchanged.
     *
     * @param service the running service to control
     * @param port    TCP port (0 = ephemeral; read back via {@link #port()})
     */
    public ControlApi(CollectorService service, int port) throws IOException {
        this(SpaceManager.single(service), port);
    }

    /**
     * Control plane over a {@link SpaceManager} hosting one or more spaces. Until the {@code /spaces/{id}} request
     * seam lands, every request resolves the manager's {@linkplain SpaceManager#current() current} (default) space.
     *
     * @param spaces the hosted spaces to control
     * @param port   TCP port (0 = ephemeral; read back via {@link #port()})
     */
    public ControlApi(SpaceManager spaces, int port) throws IOException {
        this.spaces = spaces;
        String cors  = System.getProperty("control.cors");
        this.corsOrigin = blank(cors) ? null : cors.trim();
        String ui    = System.getProperty("ui.dir");
        this.uiDir   = blank(ui) ? null : Path.of(ui.trim()).toAbsolutePath().normalize();
        String wr    = System.getProperty("assist.write.root");
        this.writeRoot = blank(wr) ? null : Path.of(wr.trim()).toAbsolutePath().normalize();
        this.http    = createServer(port);
        this.http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        // Teach DatasetRelation to resolve shared/<owner>/<item> refs to the owner's Exchange snapshot,
        // grant-checked for the calling space (a no-op resolver until installed — fail-closed).
        com.gamma.query.SharedRefResolver.install(new ExchangeRefResolver(spaces));
        registerRoutes();
        // S6 — compose the request pipeline once (outermost first): correlation and CORS wrap the error
        // boundary so error/preflight responses still carry the Correlation-ID + CORS headers; path
        // normalization → idempotency replay → space binding then feed the terminal route dispatch.
        this.pipeline = compose(this::routeDispatch,
                this::correlation, this::cors, this::errorBoundary,
                this::normalizePath, this::idempotency, this::bindSpace);
        this.http.createContext("/", this::dispatch);
        // Fail-closed at the edge (W6): resolve the edition's Authenticator now, not on the first
        // request, so a misconfigured Standard deployment (e.g. missing -Dauth.oidc.jwksUri, which the
        // security module's no-arg constructor rejects) fails to boot instead of silently accepting
        // traffic. A no-op on Personal — the lookup just resolves empty.
        Authenticators.active();
    }

    /**
     * The address the control plane listens on: {@code -Dcontrol.bind=<host-or-IP>}, or **every
     * interface** when unset.
     *
     * <p>⚠ <b>The default is the wildcard address, and that is a deliberate call (2026-08-29), not an
     * oversight.</b> Narrowing it to loopback would silently make every already-deployed Standard and
     * Enterprise install unreachable on upgrade, so the flag exists to let a deployment restrict itself
     * rather than to change what an existing one does.
     *
     * <p>🔴 <b>Consequently, exposure is an operator responsibility, and it matters most exactly where
     * there is no authentication.</b> Personal ships no {@code Authenticator} at all
     * ({@code docs/EDITIONS.md}), so a Personal install left on the default binds an unauthenticated
     * control plane to every interface. Set {@code -Dcontrol.bind=127.0.0.1} (or firewall the port) for
     * any single-user install. {@code docs/EDITIONS.md} previously asserted loopback as if the code
     * enforced it; it never did.
     *
     * <p>An unresolvable host fails the boot rather than falling back, mirroring the authenticator check
     * above: a control plane that cannot honour its stated bind must not come up serving a wider one.
     */
    // Package-private, not private: ControlApiBindTest pins the resolution rules directly. Booting a
    // server per case would prove only that SOME address bound, which is the half that never breaks.
    static InetSocketAddress bindAddress(int port) throws IOException {
        String host = System.getProperty("control.bind");
        if (blank(host)) return new InetSocketAddress(port);
        InetSocketAddress address = new InetSocketAddress(host.trim(), port);
        if (address.isUnresolved())
            throw new IOException("-Dcontrol.bind=" + host.trim() + " does not resolve to an address");
        return address;
    }

    /**
     * Plain HTTP by default (Personal edition, unchanged). Set {@code -Dhttps.keystore=<PKCS12 path>}
     * (+ {@code -Dhttps.keystore.password=<pw>}) to serve over TLS 1.3 instead (Standard edition,
     * docs/EDITIONS.md); pure JDK ({@link HttpsServer} + {@code javax.net.ssl}), no new dependency.
     *
     * <p>Both transports bind through {@link #bindAddress(int)} — a TLS listener that ignored
     * {@code -Dcontrol.bind} would be the surprising half of the pair.
     */
    private static HttpServer createServer(int port) throws IOException {
        String keystore = System.getProperty("https.keystore");
        if (blank(keystore)) return HttpServer.create(bindAddress(port), 0);
        char[] password = System.getProperty("https.keystore.password", "").toCharArray();
        try (var in = Files.newInputStream(Path.of(keystore.trim()))) {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            javax.net.ssl.KeyManagerFactory kmf =
                    javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);
            javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLSv1.3");
            ssl.init(kmf.getKeyManagers(), null, null);
            HttpsServer https = HttpsServer.create(bindAddress(port), 0);
            https.setHttpsConfigurator(new HttpsConfigurator(ssl));
            return https;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("failed to configure HTTPS from -Dhttps.keystore=" + keystore, e);
        }
    }

    public int port() { return http.getAddress().getPort(); }

    /**
     * System property carrying this JVM's loopback control-plane base URL (root, no {@code /api/v1}
     * prefix — routes register at root, e.g. {@code /components/...}). Published on {@link #start()}
     * and cleared on {@link #close()}. AGT-5 P3: the in-process embedded-intelligence agent's <em>act</em>
     * tools read it to call the same fail-closed, audited control-plane contracts as any UI/API caller
     * (carrying {@code X-Agent-Session} so the write is attributed {@code actor=agent:<session>}) —
     * no private backdoor. Absent when no control plane is running (the act tools then degrade honestly).
     */
    public static final String LOCAL_BASE_URL_PROP = "inspecto.control.localBaseUrl";

    public void start() {
        http.start();
        System.setProperty(LOCAL_BASE_URL_PROP, "http://127.0.0.1:" + port());
        if (Authenticators.active().isPresent())
            log.info("ControlApi started on port {} (Standard edition — authentication enforced via {})",
                    port(), Authenticators.active().get().getClass().getName());
        else
            log.info("ControlApi started on port {} (no authentication — Personal/core edition). "
                    + "Authorization is added by the Standard/Enterprise security module.", port());
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    /** S7: seconds to let in-flight exchanges finish before force-closing on shutdown (was {@code stop(0)},
     *  which dropped in-flight requests immediately). */
    private static final int SHUTDOWN_DRAIN_SECONDS = 2;

    @Override
    public void close() {
        http.stop(SHUTDOWN_DRAIN_SECONDS);
        // Only clear the loopback URL if it still points at us (a later ControlApi in the same JVM may
        // have re-published its own — don't strip a live one out from under it).
        String mine = "http://127.0.0.1:" + port();
        if (mine.equals(System.getProperty(LOCAL_BASE_URL_PROP))) {
            System.clearProperty(LOCAL_BASE_URL_PROP);
        }
        log.info("ControlApi stopped");
    }

    // ── CLI ────────────────────────────────────────────────────────────────────

    /**
     * Run the service <em>with</em> the control plane attached:
     * <pre>
     *   java -cp inspecto.jar com.gamma.control.ControlApi \
     *        [-Dcontrol.port=8080] \
     *        [-Dservice.poll.seconds=N] [-Dservice.max.runs=M] &lt;config.toon | dir&gt; ...
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        // Multi-space mode: -Dspaces.root points at a container dir of spaces/<id>/; each is booted in isolation.
        // Legacy single-tenant mode (no -Dspaces.root): build the one default space from the CLI config args.
        String spacesRoot = System.getProperty("spaces.root");
        SpaceManager spaces;
        if (spacesRoot != null && !spacesRoot.isBlank()) {
            spaces = SpaceManager.discover(Path.of(spacesRoot.trim()));
            if (spaces.size() == 0) {
                System.err.println("No spaces (a dir with a config/ subtree) found under -Dspaces.root=" + spacesRoot);
                System.exit(1);
            }
        } else {
            if (args.length < 1) {
                System.err.println("Usage: ControlApi [-Dcontrol.port=8080] [-Dspaces.root=DIR] "
                        + "[-Dservice.poll.seconds=N] [-Dservice.max.runs=M] <pipeline.toon | dir> [more ...]");
                System.exit(1);
            }
            spaces = SpaceManager.single(CollectorService.fromArgs(args));
        }
        int port = Integer.getInteger("control.port", 8080);
        ControlApi api = new ControlApi(spaces, port);

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.close();
            spaces.close();
            latch.countDown();
        }, "inspecto-shutdown"));
        spaces.startAll();
        api.start();
        latch.await();   // block until SIGTERM/SIGINT
    }

    // ── routes ───────────────────────────────────────────────────────────────────

    private void registerRoutes() {
        get ("/health", (e, m) -> Map.of("status", "UP"));
        // MNT-15: per-subsystem health — deeper than the liveness probe, auth-gated (not a public path).
        get ("/health/details", (e, m) -> HealthDetails.of(this));
        get ("/ready",  (e, m) -> Map.of("status", "READY", "pipelines", service().pipelines().size()));
        // Prometheus scrape endpoint — text exposition, open (scrapers don't carry tokens)
        get("/metrics", (e, m) ->
                respondText(e, com.gamma.metrics.MetricRegistry.global().scrape()));

        // The API contract itself (HARD-4): byte-equal to docs/api/openapi-v1.json at build time.
        // Auth-gated like the rest of /api/v1; loaded lazily on first request so tests and CLI runs
        // without a docs/ checkout still boot. Patterns are registered version-free — normalizePath
        // has already stripped the /api/v1 prefix by dispatch time.
        get("/openapi.json", (e, m) -> {
            byte[] doc = openApiContract();
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, doc.length);
            e.getResponseBody().write(doc);
            return HANDLED;
        });

        // Feature route modules extracted from this class (see RouteModule); each owns its own routes + docs.
        for (RouteModule module : List.of(
                new BootstrapRoutes(), new AuthRoutes(),
                new SpaceRoutes(), new ExchangeRoutes(), new DataSourceRoutes(),
                new RunRoutes(),
                new ConnectionRoutes(), new ViewRoutes(), new PipelineListRoutes(), new PipelineGraphRoutes(), new PipelineSettingsRoutes(), new PipelineRenameRoutes(), new PipelineRelatedRoutes(), new ComponentRoutes(), new BundleRoutes(),
                new EventRoutes(), new ObjectRoutes(), new NoteRoutes(), new QueueRoutes(), new TagRoutes(), new CatalogRoutes(), new ConfigPreviewRoutes(), new ConfigWriteRoutes(), new ConfigReadRoutes(), new ParserRoutes(),
                new QueryRoutes(), new BiRoutes(), new DbBrowserRoutes(), new ReconRoutes(), new ShareRoutes(), new InvRoutes(), new GeoRoutes(),
                new ExpectationRoutes(), new RequirementRoutes(),
                new JobRoutes(), new SignalRoutes(), new LineageRoutes(), new EnrichmentRoutes(), new AlertRoutes(), new DecisionRoutes(), new RuleRoutes(), new AcquisitionRoutes(),
                new NotificationRoutes(), new DeliveryStatusRoutes(), new SettingsRoutes(), new NavRoutes(), new AccessRoutes(),
                new AssistRoutes(), new AgentRoutes(), new SystemRoutes(), new SchedulerRoutes()))
            module.register(this);
    }

    // ── dispatch: a composable middleware chain (S6) ─────────────────────────────
    //
    // Each cross-cutting concern is one Middleware, composed once in the constructor (see `pipeline`).
    // The route-matching path is threaded across stages as an exchange attribute (successively rewritten
    // by normalizePath + bindSpace); read it with `path(ex)`. The terminal is `routeDispatch`.

    /** A stage of the request pipeline: do its work, then (unless it writes the response and returns) call
     *  {@code next}. Ordering + the shared per-exchange state (effective path, MDC) are the contract.
     *  Throws {@code Exception} so a route handler's checked failure propagates up to {@link #errorBoundary}. */
    @FunctionalInterface
    private interface Middleware { void handle(HttpExchange ex, Chain next) throws Exception; }

    /** A link in the composed chain — either a {@link Middleware} bound to its successor, or the terminal. */
    @FunctionalInterface
    private interface Chain { void proceed(HttpExchange ex) throws Exception; }

    /** Fold {@code middlewares} (outermost first) around {@code terminal} into a single {@link Chain}. */
    private static Chain compose(Chain terminal, Middleware... middlewares) {
        Chain chain = terminal;
        for (int i = middlewares.length - 1; i >= 0; i--) {
            Middleware m = middlewares[i];
            Chain next = chain;
            chain = ex -> m.handle(ex, next);
        }
        return chain;
    }

    /** The route-matching path — the raw URI path as rewritten by {@link #normalizePath}/{@link #bindSpace}
     *  (its {@code /api[/v1]} and {@code /spaces/{id}} prefixes stripped). Defaults to the raw path. */
    private static final String ATTR_EFFECTIVE_PATH = "inspecto.effectivePath";
    private static String path(HttpExchange ex) {
        return ApiContext.attr(ex, ATTR_EFFECTIVE_PATH) instanceof String s ? s : ex.getRequestURI().getPath();
    }
    private static void setPath(HttpExchange ex, String path) { ApiContext.attr(ex, ATTR_EFFECTIVE_PATH, path); }

    /** SEC-EXCHANGE-ATTRS (BACKLOG §1 0-b): every request-scoped attribute any stage or route stamps on
     *  the exchange. The attribute map is private to the exchange only by DEFAULT — see the note on
     *  ApiContext's attribute block. Wherever it falls back to the shared {@code HttpContext} map (any
     *  pre-JDK-26 runtime, or a current one started with {@code -Djdk.httpserver.attributes=context} —
     *  and the -NoRuntime bundle explicitly supports "Java 24+"), one map is shared by every request this
     *  server handles: request A authenticates, request B hits a public path (nothing stamped, no 401),
     *  and B's {@code ATTR_SUBJECT} reads A's Subject — flowing into requireCapability, actor() and
     *  authorize(). Clearing the roster at dispatch start makes a shared-map runtime behave like a
     *  private-map one; on a private-map runtime the map is empty here and this is a no-op.
     *  Completeness is enforced by {@code ExchangeAttributeScopeTest} against ApiContext's ATTR_* roster. */
    static final String[] REQUEST_SCOPED_ATTRS = {
            ApiContext.ATTR_CORRELATION_ID, ApiContext.ATTR_START_NANOS, ApiContext.ATTR_SELF_PATH,
            ApiContext.ATTR_ERROR_CODE, ApiContext.ATTR_IDEMPOTENCY_STORE, ApiContext.ATTR_IDEMPOTENCY_KEY,
            ApiContext.ATTR_RAW_BODY, ApiContext.ATTR_SUBJECT, ApiContext.ATTR_RESOURCE_PERMISSIONS,
            ApiContext.ATTR_PAGINATION, ATTR_EFFECTIVE_PATH, Roles.ATTR_CONFIG_ROOT,
            AccessDecider.ATTR_MATCHED_POLICY };

    /** Drop the request's whole attribute scope — dispatch's first act (see {@link #correlation}).
     *  Since 2026-08-19 the scope lives in {@link ApiContext#REQUEST_SCOPES}, keyed by exchange identity and
     *  never in the JDK's attribute map — which is SHARED across in-flight requests on pre-JDK-26
     *  runtimes (the bundle ships GraalVM 25), where clearing keys at dispatch start fixed only the
     *  sequential leak and left concurrent requests racing (crossed static-asset bodies, crossed
     *  RAW_BODY/SUBJECT). A fresh exchange has no scope, so this is a belt for exchange-object reuse. */
    static void clearRequestScope(HttpExchange ex) {
        ApiContext.dropAttrScope(ex);
    }

    private void dispatch(HttpExchange ex) throws IOException {
        try {
            pipeline.proceed(ex);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // The errorBoundary stage maps business failures to responses; reaching here means a stage
            // itself failed (e.g. while writing the response). Surface it to the HTTP server as IOException.
            throw new IOException(e);
        }
    }

    /** Correlation-ID (v1 contract, EVERY request — v4.8.0): honour a caller-supplied id, else mint one;
     *  echo it as a response header, carry it on the exchange for the v1 envelope/error bodies, and put it
     *  on the SLF4J MDC so events bridged during this request (EventStoreAppender reads mdc "correlationId")
     *  tie back. Engine-typed events keep their own explicit correlation (batch/run ids). Outermost stage:
     *  its finally closes the exchange once the whole chain has unwound. */
    private void correlation(HttpExchange ex, Chain next) throws Exception {
        clearRequestScope(ex);   // SEC-EXCHANGE-ATTRS: nothing from a previous request may be readable
        String cid = ex.getRequestHeaders().getFirst("Correlation-ID");
        cid = (cid == null || cid.isBlank()) ? java.util.UUID.randomUUID().toString() : cid.trim();
        ApiContext.attr(ex, ApiContext.ATTR_CORRELATION_ID, cid);
        ex.getResponseHeaders().set("Correlation-ID", cid);
        MDC.put("correlationId", cid);
        try {
            next.proceed(ex);
        } finally {
            MDC.remove("correlationId");
            ex.close();
            ApiContext.dropAttrScope(ex);   // the scope must not outlive the request (see clearRequestScope)
        }
    }

    /** CORS: emit the permissive headers for the configured origin (they ride every response below) and
     *  answer a preflight OPTIONS before any routing (no token, no body). Inert unless {@code -Dcontrol.cors}. */
    private void cors(HttpExchange ex, Chain next) throws Exception {
        if (corsOrigin != null) {
            applyCors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
        }
        next.proceed(ex);
    }

    /** Error boundary: map a thrown {@link ApiException} to its status (carrying its error code for the v1
     *  envelope) and anything else to a 500. Inside correlation + CORS, so error bodies still carry the
     *  Correlation-ID and CORS headers. */
    private void errorBoundary(HttpExchange ex, Chain next) throws IOException {
        try {
            next.proceed(ex);
        } catch (ApiException ae) {
            if (ae.errorCode != null) ApiContext.attr(ex, ApiContext.ATTR_ERROR_CODE, ae.errorCode);
            respond(ex, ae.status, Map.of("error", ae.getMessage()));
        } catch (Exception e) {
            // A client that walks away mid-write is not a server fault. The browser cancels in-flight
            // asset fetches on every reload or navigation, and the write then fails with a platform
            // socket message ("An established connection was aborted...", "Broken pipe", "Connection
            // reset"). The response is already committed, so respond() cannot send a 500 either — it
            // would throw again on the same dead socket. Logged at DEBUG so a routine reload stops
            // filling the operator log with 30-frame stacks that look like a real failure.
            if (isClientDisconnect(e)) {
                log.debug("{} {} aborted by the client: {}", ex.getRequestMethod(), path(ex), e.getMessage());
                return;
            }
            log.error("{} {} failed", ex.getRequestMethod(), path(ex), e);
            respond(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** True when an exception is the socket giving way because the peer went first, not a server fault. */
    private static boolean isClientDisconnect(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (!(c instanceof IOException)) continue;
            String m = c.getMessage();
            if (m == null) continue;
            String lower = m.toLowerCase();
            if (lower.contains("aborted") || lower.contains("broken pipe")
                    || lower.contains("connection reset") || lower.contains("connection was closed")) {
                return true;
            }
        }
        return false;
    }

    /** Resolve the route-matching path: mark a "/api/v1/…" request for the v1 transport contract (Envelope +
     *  structured errors, docs/superpower/api-contract-design.md) and strip the version prefix, so the route
     *  table itself stays version-free. The ng-serve dev proxy forwards "/api" <em>unchanged</em> (proxy.conf.json
     *  declares no pathRewrite), so a single SPA build sends "/api/v1/…" both in dev and same-origin.
     *
     *  <p>API-5 (2026-07-25): {@code /api/v1} is the only versioned prefix there has ever been, so any other
     *  "/api/…" path is a caller that never migrated — answered here as a JSON 404 rather than falling through
     *  to {@link #serveStatic}, which would hand an API client a 200 {@code text/html} SPA shell. Static assets
     *  never carry "/api", so they're untouched. */
    private void normalizePath(HttpExchange ex, Chain next) throws Exception {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/api/v1") || path.startsWith("/api/v1/")) {
            // No "this is v1" marker is stamped: ApiContext.v1 derives it from the URI, because a stamped
            // flag survives the request wherever exchange attributes fall back to the shared HttpContext map.
            ApiContext.attr(ex, ApiContext.ATTR_START_NANOS, System.nanoTime());
            ApiContext.attr(ex, ApiContext.ATTR_SELF_PATH, path);
            path = path.length() == 7 ? "/" : path.substring(7);
        } else if (path.equals("/api") || path.startsWith("/api/")) {
            respond(ex, 404, Map.of("error", "unknown API version — every route is served under /api/v1"));
            return;
        }
        setPath(ex, path);
        next.proceed(ex);
    }

    /** Idempotency-Key (W5): a keyed write whose response is already cached replays it verbatim, skipping the
     *  handler — so a retried trigger/create does not run twice. Keyed on the raw request path so /api/v1 and
     *  legacy surfaces don't share entries. A miss marks the exchange so ApiContext.respondJson captures the
     *  first response. */
    private void idempotency(HttpExchange ex, Chain next) throws Exception {
        String method = ex.getRequestMethod();
        String idemKey = Idempotency.keyFor(ex, method, ex.getRequestURI().getPath());
        if (idemKey != null) {
            Idempotency.Entry hit = idempotency.get(idemKey);
            if (hit != null) {
                Idempotency.replay(ex, hit);
                AuditTrail.record(ex, method, path(ex), hit.status());
                return;
            }
            ApiContext.attr(ex, ApiContext.ATTR_IDEMPOTENCY_STORE, idempotency);
            ApiContext.attr(ex, ApiContext.ATTR_IDEMPOTENCY_KEY, idemKey);
        }
        next.proceed(ex);
    }

    /** Per-space request seam: a "/spaces/{id}/<rest>" path binds this request to that space and is then
     *  matched as "/<rest>" against the unchanged route table — so RouteModules never see the prefix. An
     *  unknown id is a 404. The bound space is carried on the SLF4J MDC (the same per-space routing key the
     *  engine singletons read — Stage 3a), so service()/writeRoot() and every space-scoped singleton resolve
     *  to it for the life of this request only. "default" sets no MDC (the fallback namespace everywhere),
     *  keeping single-space output byte-identical. /health, /ready, /metrics and /spaces CRUD stay un-prefixed. */
    private void bindSpace(HttpExchange ex, Chain next) throws Exception {
        String path = path(ex);
        Matcher sp = SPACE_PREFIX.matcher(path);
        boolean spaceBound = false;
        try {
            if (sp.matches()) {
                String id = sp.group(1);
                if (spaces.space(SpaceId.of(id)).isEmpty()) {
                    respond(ex, 404, Map.of("error", "no such space '" + id + "'"));
                    return;
                }
                setPath(ex, sp.group(2));
                if (!EventLog.DEFAULT_SPACE_ID.equals(id)) {
                    MDC.put(EventLog.SPACE_MDC_KEY, id);
                    spaceBound = true;
                }
            }
            next.proceed(ex);
        } finally {
            if (spaceBound) MDC.remove(EventLog.SPACE_MDC_KEY);
        }
    }

    /** Terminal stage: match the resolved path against the route table (applying the auth gate), fall back to
     *  an SPA asset for an unmatched GET, else answer 404/405.
     *
     *  <p>API-5 (2026-07-25): the route table is reachable <em>only</em> under {@code /api/v1}, plus the
     *  always-unversioned infra probes ({@link #isInfraRoute}). Route patterns are registered version-free, so
     *  a bare "/pipelines" would otherwise still match and serve the retired unversioned surface — the guard
     *  below is what actually retires it. Such a request is treated as "not an API call at all": a GET falls
     *  through to the SPA, because bare paths are genuinely ambiguous (Angular routes like "/objects" collide
     *  with route patterns of the same name, and deep links must keep working); anything else is a 404. */
    private void routeDispatch(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        String path = path(ex);
        if (!ApiContext.v1(ex) && !isInfraRoute(path)) {
            if ("GET".equals(method) && serveStatic(ex, path)) return;
            if (!"GET".equals(method)) AuditTrail.accessDenied(ex, method, path, 404);
            respond(ex, 404, Map.of("error", "not found — API routes are served under /api/v1"));
            return;
        }
        boolean pathMatched = false;
        for (Route r : routes) {
            Matcher m = r.pattern.matcher(path);
            if (!m.matches()) continue;
            pathMatched = true;
            if (!r.method.equals(method)) continue;
            authenticate(ex, path);
            authorize(ex, method, path);
            Object result = r.handler.handle(ex, m);
            if (result != HANDLED) respond(ex, 200, result);
            // The REAL status, not a literal 200. A handler that responds itself and returns HANDLED
            // routinely sends 422 (a rejected config write, a failed compatibility gate) — recording
            // those as 200 made a refused write indistinguishable from a successful one in the audit
            // log, which is the one record an incident investigator is entitled to trust.
            int sent = ex.getResponseCode();
            AuditTrail.record(ex, method, path, sent > 0 ? sent : 200);
            return;
        }
        // No API route matched the path: a GET may be an SPA asset / deep link (PUBLIC).
        if (!pathMatched && "GET".equals(method) && serveStatic(ex, path)) return;
        int status = pathMatched ? 405 : 404;
        // A non-GET attempt at a forbidden/unknown route (or a disallowed method on a read-only route —
        // the append-only immutability guard) is the auth-free analogue of a 401/403.
        if (!"GET".equals(method)) AuditTrail.accessDenied(ex, method, path, status);
        respond(ex, status, Map.of("error", pathMatched ? "method not allowed" : "not found"));
    }

    /** AuthN gate (W6): a no-op when no {@link Authenticator} is on the classpath (Personal edition —
     *  {@link Authenticators#active()} is empty), so Personal behaviour is byte-for-byte unchanged. When
     *  the Standard edition's security module is present, a {@link #PUBLIC_PATHS} route (bootstrap/health)
     *  authenticates <em>optionally</em> — a Subject is attached when credentials resolve one (so
     *  {@code /bootstrap} reports the real session for an already-logged-in caller), but missing/invalid
     *  credentials there is not an error. Every other route requires a valid credential; a miss is
     *  {@code 401 UNAUTHENTICATED}. On success the resolved {@link Subject} is attached to the exchange
     *  for {@link ApiContext#actor}, {@code requireCapability} and the v1 envelope's {@code permissions}. */
    /**
     * Paths that carry their own credential <em>in the request</em> and so are exempt from platform auth:
     * the BI-6 share token in a public-dashboard path, and the provider signature on a D8 delivery-status
     * callback. Both verify themselves before doing anything, and both are inert until configured
     * ({@code -Dbi.share.secret} / an adapter's key), so neither exemption widens the default surface.
     *
     * <p>Deliberately <b>not</b> {@code PUBLIC_PATHS}: that is an exact-match set of fixed
     * infrastructure/auth paths, and these are prefixes carrying a variable segment. Also deliberately not
     * {@code isInfraRoute} — these are business callbacks, not infra probes, and they stay under
     * {@code /api/v1} like every other business route.
     */
    private static boolean isSelfVerifyingPublic(String path) {
        return path.startsWith("/public/dashboards/") || path.startsWith("/public/delivery-status/");
    }

    private void authenticate(HttpExchange ex, String path) {
        // BI-6: /public/dashboards/* carries its own credential — the HMAC share token in the path,
        // verified (signature + expiry) by ShareRoutes. Sharing as a whole is disabled unless
        // -Dbi.share.secret is configured, so this exemption is inert by default.
        boolean required = !PUBLIC_PATHS.contains(path) && !isSelfVerifyingPublic(path);
        Authenticators.active().ifPresent(a -> {
            // RBAC R1: hand the authenticator the bound space's config root so it can resolve the
            // authored roles.toon (Roles.effective) for THIS request — per-space, restart-free.
            // A multi-space server can legitimately host ZERO spaces (its last one was just deleted), and
            // writeRoot() -> SpaceManager.current() throws there. Authentication itself needs no space, so
            // resolve the roles root only when one is hosted: Roles.effective(null) degrades to the seeded
            // roles, and the recovery route (POST /spaces) stays reachable instead of 500ing in the gate.
            java.nio.file.Path rolesRoot = spaces.size() == 0 ? null : writeRoot();
            if (rolesRoot != null) Roles.configRoot(ex, rolesRoot);
            // SEC-7(a): on Standard the acting identity is authoritative from the authenticated Subject; a
            // client-supplied X-Actor header is an attempted actor spoof and is rejected outright. (Personal
            // has no Authenticator, so this branch never runs there and X-Actor stays the historic actor.)
            String spoof = ex.getRequestHeaders().getFirst("X-Actor");
            if (spoof != null && !spoof.isBlank())
                throw new ApiException(403, ErrorCodes.PERMISSION_DENIED,
                        "X-Actor is not accepted on this edition; the actor is taken from the authenticated session");
            java.util.Optional<Subject> subject = a.authenticate(ex);
            if (subject.isPresent()) ApiContext.attr(ex, ApiContext.ATTR_SUBJECT, subject.get());
            else if (required) throw new ApiException(401, ErrorCodes.UNAUTHENTICATED, "authentication required");
        });
    }

    /** Route-level PEP (ABAC A3): consult the edition's {@link AccessDecider} — a no-op when none is
     *  on the classpath (Personal/Standard byte-identical) or no {@link Subject} is attached (the
     *  public probe surface). Only an explicit {@code DENY} acts (403); {@code ALLOW}/{@code ABSTAIN}
     *  fall through to the existing capability gates — a policy allow never bypasses them. The action
     *  vocabulary is {@code read} (GET/HEAD) / {@code operate} (a state change gated
     *  {@code canOperateRuns} per the R4 manifest) / {@code write} (every other state change).
     *  Each policy-matched verdict (DENY → 403, or a policy-matched ALLOW) is recorded to the audit
     *  trail with the matched policy name (ABAC A5); an ABSTAIN is not a policy decision and is not
     *  audited (the existing capability gates decide and audit their own mutations). */
    private void authorize(HttpExchange ex, String method, String path) {
        AccessDecider decider = AccessDeciders.active().orElse(null);
        if (decider == null || PUBLIC_PATHS.contains(path) || isSelfVerifyingPublic(path)) return;
        if (!(ApiContext.attr(ex, ApiContext.ATTR_SUBJECT) instanceof Subject subject)) return;
        String action = actionFor(method, path);
        ApiContext.attr(ex, AccessDecider.ATTR_MATCHED_POLICY, null);   // clear stale; the decider re-stamps
        AccessDecider.Decision decision = decider.decide(ex, subject, action, path, null, Map.of());
        if (decision == AccessDecider.Decision.ABSTAIN) return;
        String policy = ApiContext.attr(ex, AccessDecider.ATTR_MATCHED_POLICY) instanceof String p ? p : null;
        boolean granted = decision == AccessDecider.Decision.ALLOW;
        AuditTrail.policyDecision(ex, granted, action, path, null, null, policy);
        if (!granted) throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "denied by access policy");
    }

    /** The ABAC action verb for a request — the single source of truth shared by the {@link #authorize}
     *  PEP and the {@code POST /access/explain} dry-run ({@code AccessRoutes}): {@code read} for
     *  GET/HEAD, {@code operate} when the route's manifest capability is {@code canOperateRuns}, else
     *  {@code write}. */
    static String actionFor(String method, String path) {
        return "GET".equals(method) || "HEAD".equals(method) ? "read"
                : Roles.CAN_OPERATE_RUNS.equals(CapabilityManifest.capabilityFor(method, path)) ? "operate"
                : "write";
    }

    private void respond(HttpExchange ex, int status, Object body) throws IOException {
        ApiContext.respondJson(ex, status, body);
    }

    /** Write a {@code text/plain} body (Prometheus exposition) and signal it's handled. */
    private Object respondText(HttpExchange ex, String text) throws IOException {
        return respondText(ex, text, "text/plain; version=0.0.4; charset=utf-8");
    }

    /** Write {@code text} with an explicit {@code Content-Type} (e.g. {@code text/csv}); returns {@link #HANDLED}. */
    private Object respondText(HttpExchange ex, String text, String contentType) throws IOException {
        return ApiContext.respondText(ex, text, contentType);
    }

    /** The served API contract (HARD-4), loaded once from the first resolvable source. */
    private volatile byte[] openApiDoc;

    private byte[] openApiContract() throws IOException {
        byte[] doc = openApiDoc;
        if (doc == null) {
            synchronized (this) {
                if (openApiDoc == null) {
                    openApiDoc = Files.readAllBytes(resolveOpenApiDoc());
                }
                doc = openApiDoc;
            }
        }
        return doc;
    }

    /** Locate {@code docs/api/openapi-v1.json}: working dir → repo root → module dir; null when absent. */
    static Path locateOpenApiDoc() {
        for (Path base : List.of(Path.of(""), Path.of(".."), Path.of(".").toAbsolutePath().getParent(),
                                  Path.of("inspecto").toAbsolutePath())) {
            Path p = base.resolve("docs").resolve("api").resolve("openapi-v1.json");
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private Path resolveOpenApiDoc() throws IOException {
        Path p = locateOpenApiDoc();
        if (p != null) return p.toAbsolutePath();
        throw new IOException("docs/api/openapi-v1.json not found — run from the repo or deploy it next to the jar");
    }

    // ── CORS + static SPA (v4.1.0) ────────────────────────────────────────────────

    /** Emit permissive CORS headers for the configured origin (set once per exchange in dispatch). */
    private void applyCors(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", corsOrigin);
        h.set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Api-Token, Correlation-ID");
        // X-Config-Fingerprint rides GET /pipelines/{name}/document (§5.1): the body is a Markdown
        // blob, so the sign-off fingerprint has nowhere else to go and the browser cannot read a
        // response header cross-origin unless it is exposed here.
        h.set("Access-Control-Expose-Headers", "Correlation-ID, X-Config-Fingerprint");
        h.set("Access-Control-Max-Age", "600");
        if (!"*".equals(corsOrigin)) h.set("Vary", "Origin");
    }

    /**
     * Serve a file from the {@code -Dui.dir} SPA root (PUBLIC). Returns {@code false} (caller emits a
     * JSON 404) when no static root is configured or the request resolves to no servable file. An
     * extensionless path with no matching file falls back to {@code index.html} so client-side
     * routing (deep links) works. Path traversal is blocked by confining the resolved path under the
     * root.
     */
    private boolean serveStatic(HttpExchange ex, String path) throws IOException {
        if (uiDir == null) return false;
        String rel = path.equals("/") ? "index.html" : path.substring(1);   // strip leading '/'
        Path target = uiDir.resolve(rel).normalize();
        if (!target.startsWith(uiDir)) { logStaticMiss(ex, "outside the ui root"); return false; }
        if (Files.isRegularFile(target)) { writeFile(ex, target); return true; }
        if (!hasExtension(rel)) {                                            // SPA deep link
            Path index = uiDir.resolve("index.html");
            if (Files.isRegularFile(index)) { writeFile(ex, index); return true; }
        }
        logStaticMiss(ex, "no such file under the ui root");
        return false;
    }

    /**
     * Write a UI file, with a cache directive and a validator.
     *
     * ⚠ {@code no-cache} is deliberate for EVERY file, content-hashed chunk names included. This
     * handler previously sent no cache headers and no validator at all, which does NOT mean "do not
     * cache" — with neither a directive nor a validator a browser falls back to HEURISTIC freshness
     * and may reuse a response without revalidating. Serving an upgraded UI from the same host:port
     * then combines the new {@code index.html} with stale chunks, and because Angular reuses its short
     * chunk names across builds a stale {@code chunk-<hash>.js} can hold a DIFFERENT module than the
     * one the new graph imports from it — the import resolves to undefined and bootstrap dies on a
     * missing helper ("w$1 is not a function"). The failure looks like a corrupt bundle, so it costs
     * far more to diagnose than the revalidation costs to avoid.
     *
     * {@code no-cache} means "revalidate before reuse", not "do not store": the ETag makes that
     * revalidation cheap, answering an unchanged file with a bodiless 304 instead of the bytes.
     * Size+mtime is a sufficient validator here — these files are build output, replaced wholesale by
     * a deploy, never edited in place within a millisecond of the same length.
     */
    private void writeFile(HttpExchange ex, Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        String etag = '"' + Long.toHexString(attrs.lastModifiedTime().toMillis())
                    + '-' + Long.toHexString(attrs.size())
                    + '-' + Integer.toHexString(file.getFileName().toString().hashCode()) + '"';
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("ETag", etag);
        if (etag.equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
            ex.sendResponseHeaders(304, -1);   // -1 ⇒ no body, as 304 requires
            logStatic(ex, file, 304, 0);
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType(file.getFileName().toString()));
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream body = ex.getResponseBody()) {
            body.write(bytes);
        } catch (IOException e) {
            // Content-Length is already committed, so a body that stops short leaves this keep-alive
            // connection DESYNCED: the next response on it is framed against THIS request's outstanding
            // length, and a later chunk then arrives under an earlier chunk's URL. The module graph links
            // against the wrong file and bootstrap dies on an export the named chunk does not have.
            // Close the exchange so the connection cannot be handed back to the keep-alive pool.
            log.debug("[UI-STATIC] body write aborted for {} - closing the connection", file.getFileName(), e);
            ex.close();
            throw e;
        }
        logStatic(ex, file, 200, bytes.length);
    }

    /**
     * DEBUG-level access log for the static UI surface — the capture BACKLOG §5 BUNDLE-1 asks for and
     * could not previously get: <em>"log every static request with its status and byte count on a cold
     * server and diff that against a clean load."</em> There was no per-request logging anywhere in this
     * class, so a failing first load left nothing behind to diff.
     *
     * <p>Logged AFTER the body is written, so a line's absence is itself evidence — a request that threw
     * mid-write never logs, which is precisely the "module came back short" shape being hunted. The
     * byte count is what was actually handed to the response body, not the file size.
     *
     * <p>The SERVED file is logged, not just the request path: {@link #serveStatic} answers every
     * extensionless path with {@code index.html}, so the URI alone cannot tell an SPA shell response
     * apart from a real asset — and "a chunk came back as HTML" is one of the shapes being hunted.
     *
     * <p>⚠ DEBUG deliberately: one line per chunk is ~130 lines per cold load, which is diagnostic gold
     * for an hour and noise for ever after. Enable with
     * {@code -Dorg.slf4j.simpleLogger.log.com.gamma.control.ControlApi=debug}.
     */
    private void logStatic(HttpExchange ex, Path file, int status, int bytes) {
        if (!log.isDebugEnabled()) return;
        log.debug("[UI-STATIC] {} {} -> {} ({} bytes, served={})",
                ex.getRequestMethod(), ex.getRequestURI().getPath(), status, bytes, file.getFileName());
    }

    /**
     * The other half of the capture: a static request that resolves to NO file never reaches
     * {@link #writeFile} — {@link #serveStatic} returns false and the caller answers a JSON 404. That is
     * exactly the BUNDLE-1 shape (the new {@code index.html} importing a chunk the bundle does not ship),
     * so leaving it unlogged would make the one decisive line missing from every capture.
     */
    private void logStaticMiss(HttpExchange ex, String why) {
        if (!log.isDebugEnabled()) return;
        log.debug("[UI-STATIC] {} {} -> 404 ({})", ex.getRequestMethod(), ex.getRequestURI().getPath(), why);
    }

    /** True when the last path segment carries a file extension (e.g. {@code main.js}, not {@code dashboard}). */
    private static boolean hasExtension(String rel) {
        int slash = rel.lastIndexOf('/');
        return rel.lastIndexOf('.') > slash;
    }

    /** Minimal extension→MIME map for the static SPA assets we actually ship. */
    private static String contentType(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "html"          -> "text/html; charset=utf-8";
            case "js", "mjs"     -> "text/javascript; charset=utf-8";
            case "css"           -> "text/css; charset=utf-8";
            case "json", "map"   -> "application/json";
            case "svg"           -> "image/svg+xml";
            case "ico"           -> "image/x-icon";
            case "png"           -> "image/png";
            case "jpg", "jpeg"   -> "image/jpeg";
            case "gif"           -> "image/gif";
            case "webp"          -> "image/webp";
            case "woff2"         -> "font/woff2";
            case "woff"          -> "font/woff";
            case "ttf"           -> "font/ttf";
            case "txt"           -> "text/plain; charset=utf-8";
            default              -> "application/octet-stream";
        };
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> body(HttpExchange ex) throws IOException {
        byte[] raw = rawBody(ex);
        if (raw.length == 0) return Map.of();
        return json.readValue(raw, new TypeReference<Map<String, Object>>() {});
    }

    @Override
    public byte[] rawBody(HttpExchange ex) throws IOException {
        // Cached, because getRequestBody() is single-read: a route that verifies a signature over the raw
        // payload and then parses it would otherwise see an empty body on the second call (D8, §4.1).
        if (ApiContext.attr(ex, ApiContext.ATTR_RAW_BODY) instanceof byte[] cached) return cached;
        byte[] raw;
        try (InputStream in = ex.getRequestBody()) {
            raw = in.readAllBytes();
        }
        ApiContext.attr(ex, ApiContext.ATTR_RAW_BODY, raw);
        return raw;
    }

    /**
     * The space this request is bound to: the one named by the request's space MDC (set by {@link #dispatch} for a
     * {@code /spaces/{id}} path), or — for an un-prefixed request — the manager's
     * {@linkplain SpaceManager#current() current} (default/first) space.
     */
    private SpaceContext currentContext() {
        return spaces.space(SpaceId.of(EventLog.currentSpaceId())).orElseGet(spaces::current);
    }

    @Override
    public CollectorService service() { return currentContext().service(); }

    @Override
    public SpaceManager spaces() { return spaces; }

    /**
     * The write-jail root for {@code POST /config/write} and disk-registration, scoped to the bound space: a
     * discovered space writes into its own {@code config/} tree (hot-reloaded by {@code ConfigRegistry.rebuild});
     * the legacy/default space keeps the server-global {@code -Dassist.write.root} (unchanged single-tenant behaviour).
     */
    @Override
    public Path writeRoot() {
        Path spaceConfig = currentContext().root().config();
        return spaceConfig != null ? spaceConfig : writeRoot;
    }

    @Override
    public Path dataRoot() {
        String dir = currentContext().root().dataDir();
        return (dir == null || dir.isBlank()) ? null : Path.of(dir);
    }

    // Route registration. The core (Personal edition) is auth-free — every route is open.
    // Standard/Enterprise editions re-introduce authorization out-of-band via the security module.
    @Override public void get (String pattern, Handler h) { routes.add(new Route("GET",    Pattern.compile("^" + pattern + "$"), h)); }
    @Override public void post(String pattern, Handler h) { routes.add(new Route("POST",   Pattern.compile("^" + pattern + "$"), h)); }
    @Override public void put   (String pattern, Handler h) { routes.add(new Route("PUT",    Pattern.compile("^" + pattern + "$"), h)); }
    @Override public void patch (String pattern, Handler h) { routes.add(new Route("PATCH",  Pattern.compile("^" + pattern + "$"), h)); }
    @Override public void delete(String pattern, Handler h) { routes.add(new Route("DELETE", Pattern.compile("^" + pattern + "$"), h)); }

    /**
     * The always-unversioned infra probes: the <em>only</em> paths {@link #routeDispatch} will match outside
     * {@code /api/v1}. They have no v1 semantics (no envelope, no error object) and are consumed by load
     * balancers, container health checks and Prometheus scrapers that cannot be expected to version a probe
     * URL — so API-5's move to a v1-only surface deliberately exempts them. Anything not listed here is
     * reachable only under {@code /api/v1}.
     */
    private static boolean isInfraRoute(String path) {
        return path.equals("/health") || path.equals("/ready")
                || path.equals("/metrics") || path.equals("/metrics/acquisition");
    }

    private record Route(String method, Pattern pattern, Handler handler) {}
}
