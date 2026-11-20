package com.gamma.mcp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The AK-1 MCP Tools and resources: a FIXED table mapping each one to the existing Control API route it replays
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

    enum Kind { ID, TEXT, SQL, INT }

    record Arg(String name, Kind kind, boolean required, String description) {}

    /** The request a tool replays: method, route-table path (with an optional {@code ?query}) and body. */
    record Call(String method, String path, byte[] body) {}

    /** Thrown for an argument the tool refuses — a JSON-RPC {@code -32602 Invalid params}. */
    static final class BadArgs extends RuntimeException {
        BadArgs(String message) { super(message); }
    }

    @FunctionalInterface
    interface Router {
        Call route(Map<String, Object> args);
    }

    record Tool(String name, String title, String description, List<Arg> args, Router router) {

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
            d.put("annotations", Map.of("readOnlyHint", true, "destructiveHint", false,
                    "idempotentHint", true, "openWorldHint", false));
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

    static final List<Resource> RESOURCES = List.of(
            new Resource("inspecto://glossary", "glossary", "Glossary",
                    "The canonical vocabulary: what every Inspecto concept is called, and the words never to use.",
                    "text/markdown", "META-INF/inspecto/mcp/GLOSSARY.md", null),
            new Resource("inspecto://catalog", "catalog", "Catalog",
                    "This Space's Catalog: its Streams, References and Datasets.", "application/json", null, "/catalog"),
            new Resource("inspecto://modules", "modules", "Edition and modules",
                    "The modules this install carries and their state in this Space.", "application/json", null, "/modules"));

    static Tool tool(String name) {
        for (Tool t : TOOLS) if (t.name().equals(name)) return t;
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
