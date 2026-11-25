package com.gamma.mcp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The MCP Tools and resources (AK-1 reads, AK-2b writes and Ship reads): a FIXED table mapping each one to the existing Control API route it replays
 * (spec-mcp.md "MCP Tools and resources"). Nothing here touches data — a tool only names a route and builds its path
 * from validated arguments; {@link McpRoutes} replays it as the caller, so the route's own gates decide.
 *
 * <p>⛔ No argument can choose a route: an argument is only ever URL-encoded into a path segment or a query value of
 * the tool's fixed route, and a path-segment argument must look like an id ({@link #ID}), so {@code ..} or a slash
 * cannot walk the route table even before the route's own validation runs.
 */
final class McpTools {

    private McpTools() {}

    /** An id-shaped argument: what Dataset, Pipeline, Job, Run and Incident ids look like. Never {@code .} / {@code ..}. */
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._:@-]{0,199}");

    /** A free-text filter value. */
    static final int MAX_TEXT = 512;
    /** A {@code query_dataset} statement. */
    static final int MAX_SQL = 65_536;

    /** {@code ENUM}: one of {@link Arg#values}; {@code OBJECT}: a JSON object, passed on as the replayed route's body. */
    enum Kind { ID, TEXT, SQL, INT, ENUM, OBJECT }

    record Arg(String name, Kind kind, boolean required, String description, List<String> values) {
        Arg(String name, Kind kind, boolean required, String description) {
            this(name, kind, required, description, List.of());
        }
    }

    /** The request a tool replays: method, route-table path (with an optional {@code ?query}), body and headers. */
    record Call(String method, String path, byte[] body, Map<String, String> headers) {
        Call(String method, String path, byte[] body) {
            this(method, path, body, Map.of());
        }
    }

    /** Thrown for an argument the tool refuses — a JSON-RPC {@code -32602 Invalid params}. */
    static final class BadArgs extends RuntimeException {
        BadArgs(String message) { super(message); }
    }

    @FunctionalInterface
    interface Router {
        Call route(Map<String, Object> args);
    }

    /**
     * {@code write}: the tool changes something once a person approves it (a proposal, a withdrawal, an Action Request, a
     * Pipeline run) — D-AK3: only an agent Subject (a verified agent client) may call it; MCP annotations say so.
     */
    record Tool(String name, String title, String description, List<Arg> args, Router router, boolean write) {

        Tool(String name, String title, String description, List<Arg> args, Router router) {
            this(name, title, description, args, router, false);
        }

        /** The validated arguments, by name; refuses an unknown, missing, mistyped or oversized one. */
        Map<String, Object> validate(Map<String, Object> raw) {
            Map<String, Object> in = raw == null ? Map.of() : raw;
            for (String k : in.keySet())
                if (args.stream().noneMatch(a -> a.name().equals(k)))
                    throw new BadArgs("unknown argument '" + k + "' for " + name);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Arg a : args) {
                Object v = in.get(a.name());
                if (v == null) {
                    if (a.required()) throw new BadArgs("missing argument '" + a.name() + "' for " + name);
                    continue;
                }
                out.put(a.name(), switch (a.kind()) {
                    case INT -> {
                        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()))
                            throw new BadArgs("'" + a.name() + "' must be an integer");
                        long l = n.longValue();
                        if (l < 1 || l > 10_000) throw new BadArgs("'" + a.name() + "' must be between 1 and 10000");
                        yield l;
                    }
                    case ID -> {
                        if (!(v instanceof String s) || !ID.matcher(s).matches())
                            throw new BadArgs("'" + a.name() + "' must be an id (letters, digits, . _ : @ -; at most 200)");
                        yield s;
                    }
                    case ENUM -> {
                        if (!(v instanceof String s) || !a.values().contains(s))
                            throw new BadArgs("'" + a.name() + "' must be one of " + a.values());
                        yield s;
                    }
                    case OBJECT -> {
                        if (!(v instanceof Map<?, ?> m)) throw new BadArgs("'" + a.name() + "' must be a JSON object");
                        yield m;
                    }
                    case TEXT, SQL -> {
                        if (!(v instanceof String s) || s.isBlank())
                            throw new BadArgs("'" + a.name() + "' must be a non-empty string");
                        int max = a.kind() == Kind.SQL ? MAX_SQL : MAX_TEXT;
                        if (s.length() > max) throw new BadArgs("'" + a.name() + "' is longer than " + max + " characters");
                        yield s;
                    }
                });
            }
            return out;
        }

        /** The MCP {@code Tool} definition {@code tools/list} returns. */
        Map<String, Object> definition() {
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (Arg a : args) {
                Map<String, Object> p = new LinkedHashMap<>();
                switch (a.kind()) {
                    case INT -> { p.put("type", "integer"); p.put("minimum", 1); p.put("maximum", 10_000); }
                    case ID -> { p.put("type", "string"); p.put("pattern", "^" + ID.pattern() + "$"); }
                    case TEXT -> { p.put("type", "string"); p.put("maxLength", MAX_TEXT); }
                    case SQL -> { p.put("type", "string"); p.put("maxLength", MAX_SQL); }
                    case ENUM -> { p.put("type", "string"); p.put("enum", a.values()); }
                    case OBJECT -> p.put("type", "object");
                }
                p.put("description", a.description());
                props.put(a.name(), p);
                if (a.required()) required.add(a.name());
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            if (!required.isEmpty()) schema.put("required", required);
            schema.put("additionalProperties", false);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", name);
            d.put("title", title);
            d.put("description", description);
            d.put("inputSchema", schema);
            // AK-2b: a write tool is not read-only, and not destructive either — a proposal changes nothing until a person
            // approves it (plan §4.4).
            d.put("annotations", Map.of("readOnlyHint", !write, "destructiveHint", false,
                    "idempotentHint", !write, "openWorldHint", false));
            return d;
        }
    }

    /** A resource: a packaged classpath file, or a replayed route. */
    record Resource(String uri, String name, String title, String description, String mimeType,
                    String classpath, String route) {
        Map<String, Object> definition() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("uri", uri);
            d.put("name", name);
            d.put("title", title);
            d.put("description", description);
            d.put("mimeType", mimeType);
            return d;
        }
    }

    // ── the table ───────────────────────────────────────────────────────────────────────────────

    private static Arg id(String name, String what) { return new Arg(name, Kind.ID, true, what); }

    private static Arg text(String name, String what) { return new Arg(name, Kind.TEXT, false, what); }

    private static Arg limit() { return new Arg("limit", Kind.INT, false, "At most this many rows (1-10000)."); }

    private static Router get(String path) { return a -> new Call("GET", path, null); }

    static final List<Tool> TOOLS = List.of(
            new Tool("edition_and_modules", "Edition and modules",
                    "The modules this install carries, their state and whether this Space switched them off. Check it "
                            + "before suggesting a feature: a module that is not ACTIVE here cannot be used.",
                    List.of(), get("/modules")),
            new Tool("list_spaces", "List Spaces",
                    "The Spaces this server hosts. Tools act in the Space whose URL the agent was given.",
                    List.of(), get("/spaces")),
            new Tool("list_datasets", "List Datasets",
                    "The Datasets registered in this Space (their ids, schemas and producing Pipelines).",
                    List.of(), get("/components/dataset")),
            new Tool("describe_dataset", "Describe a Dataset",
                    "One Dataset's registry entry: schema, keys, classification and the Pipeline that produces it.",
                    List.of(id("dataset", "The Dataset id.")),
                    a -> new Call("GET", "/components/dataset/" + seg(a.get("dataset")), null)),
            new Tool("dataset_freshness", "Dataset freshness",
                    "When a Dataset's data was last brought up to date (data as of).",
                    List.of(id("dataset", "The Dataset id.")),
                    a -> new Call("GET", "/datasets/" + seg(a.get("dataset")) + "/freshness", null)),
            new Tool("query_dataset", "Query a Dataset (read-only SQL)",
                    "Run one read-only SQL SELECT over a Dataset's stored rows. The statement must pass the server's "
                            + "read-only SQL check; classified values come back masked unless your person may reveal them.",
                    List.of(id("dataset", "The Dataset (store) the SQL reads from."),
                            new Arg("sql", Kind.SQL, true, "One read-only SELECT statement."), limit()),
                    a -> {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("table", a.get("dataset"));   // no "group": only the business stores, never ops:*
                        body.put("sql", a.get("sql"));
                        if (a.get("limit") != null) body.put("limit", a.get("limit"));
                        return new Call("POST", "/db/query", McpRoutes.jsonBytes(body));
                    }),
            new Tool("list_pipelines", "List Pipelines",
                    "The Pipelines registered in this Space, broken ones included with their reason.",
                    List.of(), get("/pipelines")),
            new Tool("get_pipeline", "Get a Pipeline",
                    "One Pipeline's read-only graph: its Collector, steps and the Datasets it produces.",
                    List.of(id("pipeline", "The Pipeline id.")),
                    a -> new Call("GET", "/pipelines/" + seg(a.get("pipeline")) + "/graph", null)),
            new Tool("list_jobs", "List Jobs", "The Jobs defined in this Space and their schedules.",
                    List.of(), get("/jobs")),
            new Tool("list_job_runs", "List Job Runs", "Recent Job Runs, newest first; optionally of one Job.",
                    List.of(new Arg("job", Kind.ID, false, "Only this Job's Runs."), limit()),
                    a -> new Call("GET", "/jobs/runs" + query(a, "job", "job", "limit", "limit"), null)),
            new Tool("get_run", "Get a Run", "One Job Run by id: its state, timings, result and failure reason.",
                    List.of(id("run", "The Run id.")),
                    a -> new Call("GET", "/jobs/runs/" + seg(a.get("run")), null)),
            new Tool("list_incidents", "List Incidents",
                    "Incidents in this Space that your person can see, newest first.",
                    List.of(text("status", "Only Incidents in this status."),
                            text("severity", "Only Incidents of this severity."),
                            text("q", "Free-text filter."), limit()),
                    a -> new Call("GET", "/objects?type=incident"
                            + query(a, "status", "status", "severity", "severity", "q", "q", "limit", "limit")
                                    .replaceFirst("^\\?", "&"), null)),
            new Tool("get_incident", "Get an Incident", "One Incident with its fields and history.",
                    List.of(id("incident", "The Incident id.")),
                    a -> new Call("GET", "/objects/" + seg(a.get("incident")), null)),
            new Tool("get_incident_briefs", "Get an Incident's Briefs",
                    "The Briefs written for one Incident: why it opened, the evidence, the value at risk and the "
                            + "proposed next actions. Needs canWorkIncidents.",
                    List.of(id("incident", "The Incident id.")),
                    a -> new Call("GET", "/incidents/" + seg(a.get("incident")) + "/briefs", null)),
            new Tool("list_alert_rules", "List Alert Rules", "The armed Alert Rules of this Space.",
                    List.of(), get("/alerts/rules")),
            new Tool("list_alerts", "List Alerts", "Recent Alerts raised in this Space.", List.of(), get("/alerts")),
            new Tool("list_expectations", "List Expectations", "The Expectations defined in this Space.",
                    List.of(), get("/expectations")),
            new Tool("get_lineage", "Get lineage", "The lineage around one output Dataset: what feeds it and what it feeds.",
                    List.of(id("dataset", "The output Dataset (store) id.")),
                    a -> new Call("GET", "/lineage" + query(a, "dataset", "store"), null)),
            new Tool("health", "Health", "Per-subsystem health of this server.", List.of(), get("/health/details")));

    // ── AK-2b: write tools and Ship reads (plan §4.4) ────────────────────────────────────────────

    /**
     * The kinds {@code propose_change} may write — the ones the Handbook's builder tutorials use (plan §7) — each to its OWN
     * write route, every one on {@code PendingChanges.REPLAYABLE}, so an agent's write is always held as a Pending Change.
     * {@code kind → [create, update, delete]} route templates; {@code {id}} is the URL-encoded target id. A Pipeline's
     * create and update are both its graph save (a new id creates it).
     */
    static final Map<String, List<String>> CHANGE_ROUTES = Map.of(
            "pipeline", List.of("PUT /pipelines/{id}/graph", "PUT /pipelines/{id}/graph", "DELETE /config/pipeline/{id}"),
            "expectation", List.of("POST /expectations", "PUT /expectations/{id}", "DELETE /expectations/{id}"),
            "alert-rule", List.of("POST /alerts/rules", "PUT /alerts/rules/{id}", "DELETE /alerts/rules/{id}"),
            "job", List.of("POST /jobs", "PUT /jobs/{id}", "DELETE /jobs/{id}"),
            "widget", List.of("POST /components/widget", "PUT /components/widget/{id}", "DELETE /components/widget/{id}"),
            "dashboard", List.of("POST /components/dashboard", "PUT /components/dashboard/{id}",
                    "DELETE /components/dashboard/{id}"));

    static final List<String> OPERATIONS = List.of("create", "update", "delete");

    /**
     * The endpoint's own refusal list (plan §4.4 "Never offered"): a call whose method and path look like the never-list is
     * refused here, before any replay — beside the host's agent gate, which refuses the same to an agent on every door.
     * No tool router can produce such a path ({@code McpWriteToolsTest} asserts it); this is the belt to that brace.
     */
    static final List<Pattern> REFUSED = List.of(
            // AK-2f: a Draft promote and an expand deny too — a person promotes a Draft; an agent never decides an expand
            Pattern.compile("[A-Z]+ .*/(approve|decline|decision|reveal|retry|mark-failed|promote|deny)(\\?.*)?"),
            Pattern.compile("[A-Z]+ /promotion/apply(\\?.*)?"),
            Pattern.compile("(POST|PUT|PATCH|DELETE) /(apps|access)(/.*)?"),
            Pattern.compile("[A-Z]+ /pending-changes/[^/]+/(approve|decline)(\\?.*)?"));

    static boolean refused(Call c) {
        String line = c.method() + " " + c.path();
        for (Pattern p : REFUSED) if (p.matcher(line).matches()) return true;
        return false;
    }

    private static Arg body(String what, boolean required) { return new Arg("body", Kind.OBJECT, required, what); }

    @SuppressWarnings("unchecked")
    private static Call proposeChange(Map<String, Object> a) {
        String kind = (String) a.get("kind"), op = (String) a.get("operation");
        Object id = a.get("id");
        Map<String, Object> content = (Map<String, Object>) a.get("content");
        if (!"create".equals(op) && id == null) throw new BadArgs("'id' is required to " + op + " a " + kind);
        if ("pipeline".equals(kind) && id == null) throw new BadArgs("'id' names the Pipeline to create");
        if (!"delete".equals(op) && content == null) throw new BadArgs("'content' is required to " + op + " a " + kind);
        if ("delete".equals(op) && content != null) throw new BadArgs("a delete takes no 'content'");
        String template = CHANGE_ROUTES.get(kind).get(OPERATIONS.indexOf(op));
        int sp = template.indexOf(' ');
        String path = template.substring(sp + 1).replace("{id}", id == null ? "" : seg(id));
        Map<String, String> headers = new LinkedHashMap<>();
        if (a.get("reason") instanceof String reason) {
            // the route's own limit (PendingChanges.MAX_REASON), and one line, as the approver reads it. Carried under the
            // header's name here; McpRoutes.replay moves it to RequestAttrs.ATTR_CHANGE_REASON (AGENT-PROPOSE-500-1)
            if (reason.length() > 500) throw new BadArgs("'reason' is at most 500 characters");
            if (reason.indexOf('\r') >= 0 || reason.indexOf('\n') >= 0) throw new BadArgs("'reason' is one line");
            headers.put("X-Change-Reason", reason);
        }
        return new Call(template.substring(0, sp), path, content == null ? null : McpRoutes.jsonBytes(content), headers);
    }

    @SuppressWarnings("unchecked")
    private static byte[] bodyOf(Map<String, Object> a) {
        Object b = a.get("body");
        return McpRoutes.jsonBytes(b == null ? Map.of() : (Map<String, Object>) b);
    }

    static final List<Tool> WRITE_TOOLS = List.of(
            new Tool("validate_config", "Validate a config",
                    "Check a config draft against the server's own save gate WITHOUT writing it: the findings a save would "
                            + "refuse it on come back as data. Run it before propose_change.",
                    List.of(new Arg("type", Kind.ID, true, "The config type, e.g. pipeline, schema, job, enrichment."),
                            new Arg("config", Kind.OBJECT, true, "The draft config map.")),
                    a -> new Call("POST", "/validate", McpRoutes.jsonBytes(Map.of("type", a.get("type"), "config", a.get("config"))))),
            new Tool("dry_run_pipeline", "Dry-run a Pipeline",
                    "Run a Pipeline over sample rows in memory and return what each step produces; writes nothing. Pass a "
                            + "draft graph in the body to dry-run a change before proposing it.",
                    List.of(id("pipeline", "The Pipeline id."),
                            body("The dry-run request: optional sample rows and an optional draft graph, as POST "
                                    + "/pipelines/authored/{id}/dry-run takes them.", false)),
                    a -> new Call("POST", "/pipelines/authored/" + seg(a.get("pipeline")) + "/dry-run", bodyOf(a))),
            new Tool("propose_change", "Propose a config change",
                    "Propose creating, updating or deleting a Pipeline, Expectation, Alert Rule, Job, Widget or Dashboard. "
                            + "The change is NOT applied: it becomes a Pending Change that another person must approve. "
                            + "'content' is the kind's own write body (for a Pipeline, its graph). Validate and dry-run first.",
                    List.of(new Arg("kind", Kind.ENUM, true, "What to change.", List.copyOf(new java.util.TreeSet<>(CHANGE_ROUTES.keySet()))),
                            new Arg("operation", Kind.ENUM, true, "create, update or delete.", OPERATIONS),
                            new Arg("id", Kind.ID, false, "The target id (required to update or delete, and to create a Pipeline)."),
                            new Arg("content", Kind.OBJECT, false, "The proposed content (not for a delete)."),
                            text("reason", "Why — shown to the approver.")),
                    McpTools::proposeChange, true),
            new Tool("list_pending_changes", "List Pending Changes",
                    "The Pending Changes of this Space, newest first; optionally only those in one status (pending, approved, "
                            + "declined, withdrawn, expired).",
                    List.of(text("status", "Only Pending Changes in this status.")),
                    a -> new Call("GET", "/pending-changes" + query(a, "status", "status"), null)),
            new Tool("get_pending_change", "Get a Pending Change",
                    "One Pending Change: what it changes, who proposed it (and through which agent client), its status.",
                    List.of(id("change", "The Pending Change id (pc-…).")),
                    a -> new Call("GET", "/pending-changes/" + seg(a.get("change")), null)),
            new Tool("diff_pending_change", "Diff a Pending Change",
                    "The line diff of what a Pending Change would change — what the approver reads.",
                    List.of(id("change", "The Pending Change id (pc-…).")),
                    a -> new Call("GET", "/pending-changes/" + seg(a.get("change")) + "/diff", null)),
            new Tool("withdraw_change", "Withdraw a Pending Change",
                    "Take back a Pending Change your person proposed, while it is still waiting. Only its author may.",
                    List.of(id("change", "The Pending Change id (pc-…).")),
                    a -> new Call("POST", "/pending-changes/" + seg(a.get("change")) + "/withdraw", McpRoutes.jsonBytes(Map.of())),
                    true),
            new Tool("propose_action", "Propose an Action Request",
                    "Propose an outbound action (an Action Request) for a person to approve; nothing is sent until they do. "
                            + "Needs canWorkIncidents.",
                    List.of(body("The Action Request, as POST /action-requests takes it.", true)),
                    a -> new Call("POST", "/action-requests", bodyOf(a)), true),
            new Tool("export_bundle", "Export a bundle",
                    "Export components of this Space as a bundle (a read): their content and provenance. Body "
                            + "{items:[{kind,id}]}.",
                    List.of(body("The export request, as POST /bundle/export takes it.", true)),
                    a -> new Call("POST", "/bundle/export", bodyOf(a))),
            new Tool("preview_promotion", "Preview a promotion",
                    "What promoting this Space's config into another Space would change, without changing it. Needs "
                            + "canAdminister.",
                    List.of(body("The promotion request, as POST /promotion/preview takes it.", true)),
                    a -> new Call("POST", "/promotion/preview", bodyOf(a))),
            new Tool("run_pipeline_now", "Run a Pipeline now",
                    "Run a Pipeline once, now (D-AK2: a delegated person within their own rights, never a machine). Dry-run "
                            + "it first. Needs canAuthorWorkbench.",
                    List.of(id("pipeline", "The Pipeline id."),
                            body("The run request, as POST /pipelines/authored/{id}/run takes it.", false)),
                    a -> new Call("POST", "/pipelines/authored/" + seg(a.get("pipeline")) + "/run", bodyOf(a)), true));

    // ── AK-2d: operations tools (plan §4.6, §6.2 lane C) ─────────────────────────────────────────

    /** {@code operate_run}'s actions → the run route each replays (every one in {@code AgentGate.ACTIONS}). */
    static final Map<String, String> RUN_ACTIONS = Map.of(
            "trigger", "trigger", "pause", "pause", "resume", "resume", "reprocess", "reprocess",
            "replay-rejects", "replay-rejects", "retry-now", "retries/retry-now", "cancel-retries", "retries/cancel",
            "drain", "drain", "release-quarantined", "quarantine/release");

    private static final String HELD_FOR_A_MACHINE = " Signed in as a person, it runs at once within that person's own rights "
            + "(needs canOperateRuns); as an unattended machine agent it is held as a Pending Action for a person to approve.";

    static final List<Tool> OPERATIONS_TOOLS = List.of(
            new Tool("list_runs", "List runs",
                    "Every ingest run (Pipeline) of this Space with its state: last result, paused, schedule.",
                    List.of(), get("/runs")),
            new Tool("get_run_status", "Get a manual run's status",
                    "One manual run by the run id a trigger returned: its state, timings and result.",
                    List.of(id("runId", "The run id a trigger returned.")),
                    a -> new Call("GET", "/runs/runs/" + seg(a.get("runId")), null)),
            new Tool("get_run_batches", "Get a run's batches",
                    "The batches of one ingest run: what each processed, failed or parked — where to look before a reprocess.",
                    List.of(id("run", "The run (Pipeline) name.")),
                    a -> new Call("GET", "/runs/" + seg(a.get("run")) + "/batches", null)),
            new Tool("list_quarantined_files", "List a run's quarantined files",
                    "The files one ingest run set aside instead of loading: each file's name, the reason, when, and the "
                            + "path to pass to operate_run release-quarantined.",
                    List.of(id("run", "The run (Pipeline) name.")),
                    a -> new Call("GET", "/runs/" + seg(a.get("run")) + "/quarantine", null)),
            new Tool("operate_run", "Operate a run",
                    "Trigger, pause, resume, reprocess a batch, replay rejected rows, retry now, cancel retries, drain an "
                            + "ingest run, or release one quarantined file back into its inbox." + HELD_FOR_A_MACHINE
                            + " Diagnose first (get_run_batches, list_quarantined_files, list_job_runs).",
                    List.of(id("run", "The run (Pipeline) name."),
                            new Arg("action", Kind.ENUM, true, "What to do.", List.copyOf(new java.util.TreeSet<>(RUN_ACTIONS.keySet()))),
                            body("The action's own request body, when it takes one (reprocess: {batchId}; "
                                    + "release-quarantined: {path, reprocess?}, the path as list_quarantined_files gives it).", false),
                            text("reason", "Why — on the audit row, and shown to the approver when it is held.")),
                    McpTools::operateRun, true),
            new Tool("trigger_job", "Trigger a Job",
                    "Run a Job once, now." + HELD_FOR_A_MACHINE,
                    List.of(id("job", "The Job id."), text("reason", "Why — shown to the approver when it is held.")),
                    a -> withReason(new Call("POST", "/jobs/" + seg(a.get("job")) + "/trigger", McpRoutes.jsonBytes(Map.of())), a),
                    true),
            new Tool("replay_job_run", "Replay a Job Run",
                    "Run a past Job Run again with the same inputs." + HELD_FOR_A_MACHINE,
                    List.of(id("runId", "The Job Run id."), text("reason", "Why — shown to the approver when it is held.")),
                    a -> withReason(new Call("POST", "/jobs/runs/" + seg(a.get("runId")) + "/replay", McpRoutes.jsonBytes(Map.of())), a),
                    true),
            new Tool("list_pending_actions", "List Pending Actions",
                    "The Pending Actions of this Space (a machine agent's held run and Case actions), newest first; optionally only "
                            + "those in one status (pending, approved, declined, withdrawn, expired, stale, failed).",
                    List.of(text("status", "Only Pending Actions in this status.")),
                    a -> new Call("GET", "/pending-actions" + query(a, "status", "status"), null)),
            new Tool("get_pending_action", "Get a Pending Action",
                    "One Pending Action: the action, the machine that asked, its status and outcome.",
                    List.of(id("action", "The Pending Action id (pa-…).")),
                    a -> new Call("GET", "/pending-actions/" + seg(a.get("action")), null)),
            new Tool("withdraw_action", "Withdraw a Pending Action",
                    "Take back a Pending Action this machine asked for, while it is still waiting. Only the one who asked may.",
                    List.of(id("action", "The Pending Action id (pa-…).")),
                    a -> new Call("POST", "/pending-actions/" + seg(a.get("action")) + "/withdraw", McpRoutes.jsonBytes(Map.of())),
                    true));

    private static Call operateRun(Map<String, Object> a) {
        String route = RUN_ACTIONS.get((String) a.get("action"));
        return withReason(new Call("POST", "/runs/" + seg(a.get("run")) + "/" + route, bodyOf(a)), a);
    }

    /** {@code work_incident_or_case}'s actions → the route tail each replays (every one in {@code AgentGate.CASE_ACTIONS}). */
    static final List<String> CASE_MOVES = List.of("ack", "assign", "resolve", "transition");

    /** {@code work_alert}'s actions → the route tail each replays. */
    static final List<String> ALERT_MOVES = List.of("ack", "resolve");

    private static final String CASE_HELD_FOR_A_MACHINE = " Signed in as a person, it runs at once within that person's own "
            + "rights; as an unattended machine agent it is held as a Pending Action for a person to approve.";

    /** AK-2e: the Incident and Case analyst's actions (plan §4.6 analyst rows, operator 2026-11-25). */
    static final List<Tool> CASE_TOOLS = List.of(
            new Tool("add_comment", "Comment on an Incident or Case",
                    "Add a comment to an Incident's or Case's thread, as the one signed in." + CASE_HELD_FOR_A_MACHINE,
                    List.of(id("id", "The Incident or Case id."), new Arg("comment", Kind.TEXT, true, "The comment."),
                            text("reason", "Why — shown to the approver when it is held.")),
                    a -> withReason(new Call("POST", "/objects/" + seg(a.get("id")) + "/comments",
                            McpRoutes.jsonBytes(Map.of("body", a.get("comment")))), a), true),
            new Tool("build_incident_brief", "Build an Incident Brief",
                    "Investigate an Incident: build and save a Brief (its evidence, similar Incidents, value at risk), and "
                            + "optionally file its proposed next step as a pending Action Request — which a person other "
                            + "than the one who asked must approve. Needs canWorkIncidents." + CASE_HELD_FOR_A_MACHINE,
                    List.of(id("incident", "The Incident id."),
                            body("Optional: {\"propose\": {connection, method, payloadTemplate, reason}} to file the next step.", false),
                            text("reason", "Why — shown to the approver when it is held.")),
                    a -> withReason(new Call("POST", "/incidents/" + seg(a.get("incident")) + "/briefs", bodyOf(a)), a), true),
            new Tool("work_incident_or_case", "Work an Incident or Case",
                    "Acknowledge, assign, resolve or move an Incident or Case through its Workflow. Needs canWorkIncidents."
                            + CASE_HELD_FOR_A_MACHINE + " Read it first (get_incident).",
                    List.of(id("id", "The Incident or Case id."),
                            new Arg("action", Kind.ENUM, true, "What to do.", CASE_MOVES),
                            body("The action's body: assign {assignee}; resolve {disposition} (an Incident needs one); "
                                    + "transition {action} or {status}, with {disposition} for an Incident's resolve.", false),
                            text("reason", "Why — on the audit row, and shown to the approver when it is held.")),
                    a -> withReason(new Call("POST", "/objects/" + seg(a.get("id")) + "/" + a.get("action"), bodyOf(a)), a),
                    true),
            new Tool("work_alert", "Acknowledge or resolve an Alert",
                    "Acknowledge or resolve one fired Alert. Needs canWorkIncidents." + CASE_HELD_FOR_A_MACHINE,
                    List.of(id("alert", "The Alert id."), new Arg("action", Kind.ENUM, true, "What to do.", ALERT_MOVES),
                            text("reason", "Why — on the audit row, and shown to the approver when it is held.")),
                    a -> withReason(new Call("POST", "/alerts/" + seg(a.get("alert")) + "/" + a.get("action"),
                            McpRoutes.jsonBytes(Map.of())), a), true),
            new Tool("open_case_from_entities", "Open a Case from entities",
                    "Open a Case over the entities (and existing Incidents) an investigation found. Needs "
                            + "canManageIncidents, so only a person's agent can — an unattended machine agent never may.",
                    List.of(body("{title, description?, entities: [{id, dataset, label} or {objectId}]} — at most the "
                            + "server's entity cap.", true),
                            text("reason", "Why — on the audit row.")),
                    a -> withReason(new Call("POST", "/cases/from-entities", bodyOf(a)), a), true));

    /** The call with the {@code reason} argument carried as {@code X-Change-Reason}, checked as {@link #proposeChange} checks it. */
    private static Call withReason(Call c, Map<String, Object> a) {
        if (!(a.get("reason") instanceof String reason)) return c;
        if (reason.length() > 500) throw new BadArgs("'reason' is at most 500 characters");
        if (reason.indexOf('\r') >= 0 || reason.indexOf('\n') >= 0) throw new BadArgs("'reason' is one line");
        return new Call(c.method(), c.path(), c.body(), Map.of("X-Change-Reason", reason));
    }

    /** {@code run_link_query}'s kinds → the read-shaped Link Analysis route each replays ({@code AgentGate.LA_READS}). */
    static final Map<String, String> LINK_QUERIES = Map.ofEntries(
            Map.entry("projection", "/inv/projection"), Map.entry("neighbors", "/inv/projection/neighbors"),
            Map.entry("neighbors-preview", "/inv/projection/neighbors/preview"), Map.entry("multi", "/inv/projection/multi"),
            Map.entry("expand-preview", "/inv/expand/preview"), Map.entry("paths", "/inv/traversal/recursive-paths"),
            Map.entry("branching", "/inv/pattern/branching"), Map.entry("temporal", "/inv/pattern/temporal"),
            Map.entry("case-history", "/inv/case-history"), Map.entry("reference-rows", "/inv/reference-rows"));

    /** {@code get_investigation}'s views → the GET route tail each reads. */
    static final List<String> INVESTIGATION_VIEWS = List.of("working-set", "log", "coverage", "case", "members",
            "references", "measures", "data-changes", "drafts");

    /** {@code work_draft}'s actions → the Draft write each replays ({@code AgentGate.LA_ACTIONS}). */
    static final List<String> DRAFT_ACTIONS = List.of("ops", "undo", "discard", "rebase");

    private static final String LA_HELD_FOR_A_MACHINE = " Signed in as a person, it runs at once within that person's own "
            + "rights; as an unattended machine agent that a lead added to the Investigation it is held as a Pending Action.";

    /** AK-2f: the analyst in Link Analysis (plan §6.6, operator 2026-11-25). Never promote, reveal or approve an expand. */
    static final List<Tool> LA_TOOLS = List.of(
            new Tool("list_investigations", "List Investigations",
                    "The Link Analysis Investigations your person (or this machine) is a member of.", List.of(),
                    get("/inv/investigations")),
            new Tool("get_investigation", "Read an Investigation",
                    "One view of an Investigation: its working set (the graph), log, coverage, linked Case, members, "
                            + "references, measures, data changes since sealing, or its Drafts. Keys are masked: an agent never reveals.",
                    List.of(id("investigation", "The Investigation id."),
                            new Arg("view", Kind.ENUM, true, "What to read.", INVESTIGATION_VIEWS)),
                    a -> new Call("GET", "/inv/investigations/" + seg(a.get("investigation")) + "/" + a.get("view"), null)),
            new Tool("get_draft", "Read a Draft",
                    "One view of a Draft: its working set, its log, or the conflicts a rebase would meet.",
                    List.of(id("investigation", "The Investigation id."), id("draft", "The Draft id."),
                            new Arg("view", Kind.ENUM, true, "What to read.", List.of("working-set", "log", "conflicts"))),
                    a -> new Call("GET", "/inv/investigations/" + seg(a.get("investigation")) + "/drafts/" + seg(a.get("draft"))
                            + "/" + a.get("view"), null)),
            new Tool("explore_entity", "Explore an entity",
                    "An Entity's profile or summary: its links, activity and the Incidents and Cases that name it (masked).",
                    List.of(id("type", "The Entity Type."), id("key", "The entity key, as a Link Analysis read returned it."),
                            new Arg("view", Kind.ENUM, true, "What to read.", List.of("profile", "summary")),
                            text("dataset", "The Dataset to summarise the entity in — required for the summary.")),
                    a -> {
                        if ("summary".equals(a.get("view")) && a.get("dataset") == null)
                            throw new BadArgs("'dataset' is required for the summary view");
                        return new Call("GET", "/inv/entities/" + seg(a.get("type")) + "/" + seg(a.get("key")) + "/"
                                + a.get("view") + query(a, "dataset", "dataset"), null);
                    }),
            new Tool("run_link_query", "Run a Link Analysis query",
                    "Run one Link Analysis query over a Dataset: a projection, the neighbours of a set of entities (or a "
                            + "count of what one step would add), a multi-Dataset projection, an unsaved expand, paths between "
                            + "entities, a branching or temporal pattern, an entity's case history, or reference rows. Nothing "
                            + "is saved; keys are masked.",
                    List.of(new Arg("kind", Kind.ENUM, true, "Which query.", List.copyOf(new java.util.TreeSet<>(LINK_QUERIES.keySet()))),
                            body("The query's request, as its route takes it (dataset, sourceCol, targetCol, seeds, depth, …).", true)),
                    a -> new Call("POST", LINK_QUERIES.get((String) a.get("kind")), bodyOf(a))),
            new Tool("run_preset", "Run a preset query",
                    "Run one of the Space's preset Link Analysis queries over a Dataset. Nothing is saved; keys are masked.",
                    List.of(id("preset", "The preset id."), body("The preset's parameters.", false)),
                    a -> new Call("POST", "/inv/presets/" + seg(a.get("preset")) + "/run", bodyOf(a))),
            new Tool("list_graph_algorithms", "List graph algorithms",
                    "The graph-analysis algorithms this server runs, their limits and the budget a run takes.", List.of(),
                    get("/inv/graph/algorithms")),
            new Tool("list_graph_runs", "List graph runs", "The graph-analysis runs of this Space and their state.", List.of(),
                    get("/inv/graph/runs")),
            new Tool("get_graph_run", "Get a graph run", "One graph-analysis run: its state and, once done, its results (masked).",
                    List.of(id("run", "The graph run id.")), a -> new Call("GET", "/inv/graph/runs/" + seg(a.get("run")), null)),
            new Tool("open_draft", "Open a Draft",
                    "Open your Draft on an Investigation you are a member of, to work on a copy of its graph. A person "
                            + "promotes a Draft into the Investigation; you never do." + LA_HELD_FOR_A_MACHINE,
                    List.of(id("investigation", "The Investigation id."), body("Optional: {\"label\": \"…\"}.", false),
                            text("reason", "Why — shown to the approver when it is held.")),
                    a -> withReason(new Call("POST", "/inv/investigations/" + seg(a.get("investigation")) + "/drafts", bodyOf(a)), a),
                    true),
            new Tool("work_draft", "Work in a Draft",
                    "Add an op to your Draft (seed, expand, connect, exclude, hide, keep, annotate, threshold, window, resolve), undo one, rebase it onto the "
                            + "Investigation's current log, or discard it." + LA_HELD_FOR_A_MACHINE,
                    List.of(id("investigation", "The Investigation id."), id("draft", "The Draft id."),
                            new Arg("action", Kind.ENUM, true, "What to do.", DRAFT_ACTIONS),
                            body("The action's body: ops {op, …}; undo {undoes?}; rebase {confirm?, expectHead?}.", false),
                            text("reason", "Why — shown to the approver when it is held.")),
                    a -> withReason(new Call("POST", "/inv/investigations/" + seg(a.get("investigation")) + "/drafts/"
                            + seg(a.get("draft")) + "/" + a.get("action"), bodyOf(a)), a), true),
            new Tool("attach_investigation_to_case", "Attach an Investigation to a Case",
                    "Link an Investigation to the Case it serves. Needs canManageIncidents, so only a person's agent can.",
                    List.of(id("investigation", "The Investigation id."), id("case", "The Case id."),
                            text("reason", "Why — on the audit row.")),
                    a -> withReason(new Call("PUT", "/inv/investigations/" + seg(a.get("investigation")) + "/case",
                            McpRoutes.jsonBytes(Map.of("caseRef", a.get("case")))), a), true),
            new Tool("start_graph_run", "Start a graph run",
                    "Start a graph-analysis run over an Investigation's graph (list_graph_algorithms names them). Needs "
                            + "canRunLinkGraphAnalysis, so only a person's agent can; read it with get_graph_run.",
                    List.of(body("The run request: {investigationId, algorithm, params}.", true), text("reason", "Why — on the audit row.")),
                    a -> withReason(new Call("POST", "/inv/graph/runs", bodyOf(a)), a), true),
            new Tool("cancel_graph_run", "Cancel a graph run", "Stop a graph run you started (an administrator's agent: any run).",
                    List.of(id("run", "The graph run id.")),
                    a -> new Call("POST", "/inv/graph/runs/" + seg(a.get("run")) + "/cancel", McpRoutes.jsonBytes(Map.of())), true));

    /** Every tool: AK-1's reads, then AK-2b's, then AK-2d's, then AK-2e's, then AK-2f's. */
    static final List<Tool> ALL_TOOLS;
    static {
        List<Tool> all = new ArrayList<>(TOOLS);
        all.addAll(WRITE_TOOLS);
        all.addAll(OPERATIONS_TOOLS);
        all.addAll(CASE_TOOLS);
        all.addAll(LA_TOOLS);
        ALL_TOOLS = List.copyOf(all);
    }

    static final List<Resource> RESOURCES = List.of(
            new Resource("inspecto://glossary", "glossary", "Glossary",
                    "The canonical vocabulary: what every Inspecto concept is called, and the words never to use.",
                    "text/markdown", "META-INF/inspecto/mcp/GLOSSARY.md", null),
            new Resource("inspecto://catalog", "catalog", "Catalog",
                    "This Space's Catalog: its Streams, References and Datasets.", "application/json", null, "/catalog"),
            new Resource("inspecto://modules", "modules", "Edition and modules",
                    "The modules this install carries and their state in this Space.", "application/json", null, "/modules"));

    static Tool tool(String name) {
        for (Tool t : ALL_TOOLS) if (t.name().equals(name)) return t;
        return null;
    }

    static Resource resource(String uri) {
        for (Resource r : RESOURCES) if (r.uri().equals(uri)) return r;
        return null;
    }

    /** One URL-encoded path segment. */
    static String seg(Object v) {
        return URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** {@code ?k=v&…} from argument → parameter name pairs, present arguments only; empty when none. */
    static String query(Map<String, Object> a, String... argToParam) {
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < argToParam.length; i += 2) {
            Object v = a.get(argToParam[i]);
            if (v == null) continue;
            q.append(q.isEmpty() ? '?' : '&').append(argToParam[i + 1]).append('=').append(seg(v));
        }
        return q.toString();
    }
}
