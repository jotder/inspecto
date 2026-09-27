package com.gamma.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `SEC-IMPORT-ROLES-ESCALATION-1`, over real HTTP with an ARMED Authenticator (without a Subject
 * {@code withCapability} is a no-op and every one of these would pass vacuously). {@code POST /import},
 * {@code POST /pipelines/import} and {@code POST /bundle/import} are all gated {@code canAuthorWorkbench}, and
 * each could land a file a narrower gate owns — above all {@code roles.toon}, the table {@link Roles#effective}
 * resolves every Subject's capabilities from, and {@code demo-users.toon}, Demo sign-in's user table.
 *
 * <p>Every refusal is asserted as 403 AND a config tree byte-for-byte unchanged, on a Space with and without a
 * role table (a Windows alias like {@code roles.toon.} is only dangerous where the real file does not yet exist
 * to collide with, and only a real-path check helps where it does).
 */
class ControlApiImportReservedPathsTest {

    private static final String BUILDER = "Bearer builder", ADMIN = "Bearer admin";
    /** A builder who may also onboard Connections — a data-source bundle carries one (IMPORT-CONNECTION-JOB-GATE-1). */
    private static final String ONBOARDER = "Bearer onboarder";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** Every spelling the verifiers used to reach a reserved file or directory, and the doors they tried. */
    static final List<String> ALIASES = List.of(
            "roles.toon", "roles.toon.", "roles.toon ", "ROLES~1.TOO", "Roles.Toon", "demo-users.toon",
            "agent/policy.json", "agent/approvals.jsonl", "agent/policy.toon", "offers.toon", "grants.toon",
            "pending-changes./x.json", "pending-changes./x.toon", "PENDIN~1/x.json", "audit./x.json", "audit./x.toon",
            "registry/access-profiles./x.toon", "registry/access-profiles/x.toon", "registry/ACCESS~1/x.toon",
            "CON.toon", "nul/x_pipeline.toon", "orders/COM1.toon", "LPT9", "a:b_pipeline.toon");

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path config) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case BUILDER -> Optional.of(new Subject("builder-1", Set.of("canAuthorWorkbench")));
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canAdminister")));
            case ONBOARDER -> Optional.of(new Subject("builder-1", Set.of("canAuthorWorkbench", "canOnboardConnections")));
            default -> Optional.empty();
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    /** Space {@code alpha}: {@code test_etl} registered from {@code config/etl_pipeline.toon} (the config ROOT —
     *  an overwrite lands its satellites there), its data dirs and schema OUTSIDE config/. */
    private Ctx open(Path root, boolean withRoleTable) throws Exception {
        Path base = root.resolve("alpha");
        Path config = base.resolve("config");
        Files.createDirectories(config);
        Path tmp = TestConfigs.csv(base.resolve("data").resolve("etl"), PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        if (withRoleTable)
            Roles.write(config, Map.of("developer", new Roles.Def(Set.of("canAuthorWorkbench"), null)), List.of());
        Files.writeString(base.resolve("space.toon"), "display_name: \"Alpha\"\ndescription: \"x\"\ncreated_at: \"2026-06-23\"\n");
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), config);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** Every file (with its sha256) and directory under the config root — the "nothing written" oracle. */
    private static Map<String, String> tree(Path config) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> all = Files.walk(config)) {
            for (Path p : all.toList()) {
                String rel = config.relativize(p).toString().replace('\\', '/');
                out.put(rel, Files.isDirectory(p) ? "<dir>"
                        : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
            }
        }
        return out;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] dataSourceZip(Map<String, String> entries) throws Exception {
        Map<String, byte[]> all = new LinkedHashMap<>();
        all.put("bundle.toon", "kind: datasource\n".getBytes(StandardCharsets.UTF_8));
        entries.forEach((k, v) -> all.put(k, v.getBytes(StandardCharsets.UTF_8)));
        return zip(all);
    }

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/alpha" + path))
                .header("Authorization", auth).method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A roles.toon granting the builder's seeded role — `developer` — installation administration. */
    private static String escalatingRoles(Path scratch) throws Exception {
        Files.createDirectories(scratch);
        Roles.write(scratch, Map.of("developer", new Roles.Def(Set.of("canAuthorWorkbench", "canAdminister"), null)), List.of());
        return Files.readString(scratch.resolve(Roles.FILE));
    }

    private static final String SUPER_DEMO_USER = "users[1]{id,displayName,title,roles,landing}:\n"
            + "  builder-1,Builder,x,super,\n";

    /** test_etl's own Pipeline bundle, exported by the server: entry name → bytes, the manifest decoded. */
    private LinkedHashMap<String, byte[]> exported(Ctx c) throws Exception {
        HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                        + "/api/v1/spaces/alpha/pipelines/test_etl/bundle")).header("Authorization", BUILDER).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, r.statusCode());
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(r.body()))) {
            for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry())
                if (!e.isDirectory()) entries.put(e.getName(), zis.readAllBytes());
        }
        return entries;
    }

    /** test_etl's bundle carrying one more satellite {@code sat}, the pipeline body changed by {@code mutate}. */
    private LinkedHashMap<String, byte[]> withSatellite(Ctx c, String sat, byte[] bytes,
                                                         Consumer<Map<String, Object>> mutate) throws Exception {
        LinkedHashMap<String, byte[]> entries = exported(c);
        Map<String, Object> manifest = ConfigCodec.toMap(new String(entries.get("manifest.toon"), StandardCharsets.UTF_8));
        List<Object> sats = new ArrayList<>(manifest.get("satellites") instanceof List<?> l ? l : List.of());
        sats.add(new LinkedHashMap<>(Map.of("path", sat)));
        manifest.put("satellites", sats);
        entries.put("manifest.toon", ConfigCodec.toToon(manifest).getBytes(StandardCharsets.UTF_8));
        String pipelineEntry = String.valueOf(manifest.get("pipeline_file"));
        Map<String, Object> pipeline = ConfigCodec.toMap(new String(entries.get(pipelineEntry), StandardCharsets.UTF_8));
        mutate.accept(pipeline);
        entries.put(pipelineEntry, ConfigCodec.toToon(pipeline).getBytes(StandardCharsets.UTF_8));
        entries.put(sat, bytes);
        return entries;
    }

    /** The same closure as a {@code /bundle/import} {@code pipeline} item, overwriting test_etl. */
    private static byte[] metadataBundle(LinkedHashMap<String, byte[]> closureEntries) throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(closureEntries);
        Map<String, Object> manifest = ConfigCodec.toMap(new String(entries.remove("manifest.toon"), StandardCharsets.UTF_8));
        Map<String, Object> files = new LinkedHashMap<>();
        entries.forEach((k, v) -> files.put(k, new String(v, StandardCharsets.UTF_8)));
        Map<String, Object> item = Map.of("kind", "pipeline", "id", "test_etl",
                "content", Map.of("closure", Map.of("manifest", manifest, "files", files)));
        return JSON.writeValueAsBytes(Map.of(
                "bundle", Map.of("format", "inspecto-metadata-bundle", "version", 1, "items", List.of(item)),
                "actions", Map.of("pipeline/test_etl", "overwrite")));
    }

    /** One refusal: 403, and the config tree exactly as it was. */
    private void refused(Ctx c, String door, String path, byte[] body, String what) throws Exception {
        Map<String, String> before = tree(c.config);
        HttpResponse<String> r = send(c, "POST", path, body, BUILDER);
        assertEquals(403, r.statusCode(), door + " " + what + " -> " + r.body());
        assertEquals(before, tree(c.config), door + " " + what + ": the config tree changed");
    }

    private void assertNoEscalation(Ctx c) {
        assertFalse(Roles.effective(c.config).get("developer").capabilities().contains("canAdminister"),
                "the builder's role gained nothing");
    }

    // ── the three doors × every alias × with and without a role table ───────────────────────────

    @Test
    void everyDoorRefusesEveryAliasWithAndWithoutARoleTable(@TempDir Path root) throws Exception {
        String roles = escalatingRoles(root.resolve("scratch"));
        for (boolean withRoleTable : List.of(false, true)) {
            try (Ctx c = open(root.resolve(withRoleTable ? "with" : "without"), withRoleTable)) {
                for (String alias : ALIASES) {
                    byte[] bytes = (alias.contains("demo-users") ? SUPER_DEMO_USER : roles).getBytes(StandardCharsets.UTF_8);
                    String what = "'" + alias + "' (role table: " + withRoleTable + ")";
                    refused(c, "/import", "/import", dataSourceZip(Map.of(alias, new String(bytes, StandardCharsets.UTF_8))), what);
                    refused(c, "/import", "/import?on_conflict=overwrite",
                            dataSourceZip(Map.of(alias, new String(bytes, StandardCharsets.UTF_8))), what + " overwrite");
                    LinkedHashMap<String, byte[]> closure = withSatellite(c, alias, bytes, p -> {});
                    refused(c, "/pipelines/import", "/pipelines/import?name=test_etl&conflict=overwrite", zip(closure), what);
                    refused(c, "/bundle/import", "/bundle/import", metadataBundle(closure), what);
                }
                assertNoEscalation(c);
            }
        }
    }

    /**
     * Hole 1: the shape rule was skipped for any entry ANY string of a carried Pipeline named, so
     * {@code notes.r0: "demo-users.toon"} wrote Demo sign-in's user table. Now only a REAL reference key counts —
     * and even a real one ({@code schema_file}) cannot name a reserved file.
     */
    @Test
    void demoUsersCannotBeWrittenViaANotesFieldOrARealReferenceKey(@TempDir Path root) throws Exception {
        for (boolean withRoleTable : List.of(false, true)) {
            try (Ctx c = open(root.resolve(withRoleTable ? "with" : "without"), withRoleTable)) {
                Map<String, Object> base = ConfigCodec.toMap(Files.readString(c.config.resolve("etl_pipeline.toon")));
                for (Map.Entry<String, Consumer<Map<String, Object>>> how : Map.<String, Consumer<Map<String, Object>>>of(
                        "a notes field", p -> p.put("notes", new LinkedHashMap<>(Map.of("r0", "demo-users.toon"))),
                        "a description", p -> p.put("description", "demo-users.toon"),
                        "processing.schema_file", p -> processing(p).put("schema_file", "demo-users.toon"),
                        "processing.mapping_file", p -> processing(p).put("mapping_file", "demo-users.toon")).entrySet()) {
                    String what = "demo-users.toon via " + how.getKey() + " (role table: " + withRoleTable + ")";
                    Map<String, Object> evil = new LinkedHashMap<>(base);
                    evil.put("name", "evil_etl");
                    how.getValue().accept(evil);
                    refused(c, "/import", "/import", dataSourceZip(Map.of("evil_pipeline.toon", ConfigCodec.toToon(evil),
                            "demo-users.toon", SUPER_DEMO_USER)), what);
                    LinkedHashMap<String, byte[]> closure = withSatellite(c, "demo-users.toon",
                            SUPER_DEMO_USER.getBytes(StandardCharsets.UTF_8), how.getValue());
                    refused(c, "/pipelines/import", "/pipelines/import?name=test_etl&conflict=overwrite", zip(closure), what);
                    refused(c, "/bundle/import", "/bundle/import", metadataBundle(closure), what);
                }
                assertFalse(Files.exists(c.config.resolve("demo-users.toon")));
            }
        }
    }

    /** Hole 1, the allowlist half: a notes field makes NOTHING referenced, so a root file outside the shape stays refused. */
    @Test
    void aNonReferenceStringBuysNoExemptionFromTheShapeRule(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, false)) {
            Map<String, Object> evil = new LinkedHashMap<>(ConfigCodec.toMap(Files.readString(c.config.resolve("etl_pipeline.toon"))));
            evil.put("name", "evil_etl");
            evil.put("notes", new LinkedHashMap<>(Map.of("r0", "anything.toon", "r1", "orders/g.asn")));
            refused(c, "/import", "/import", dataSourceZip(Map.of("evil_pipeline.toon", ConfigCodec.toToon(evil),
                    "anything.toon", "x: 1\n")), "anything.toon via notes");
            refused(c, "/import", "/import", dataSourceZip(Map.of("evil_pipeline.toon", ConfigCodec.toToon(evil),
                    "orders/g.asn", "G DEFINITIONS ::= BEGIN END\n")), "orders/g.asn via notes");
            // and on the Pipeline door a satellite gets no exemption either (hole 2: every satellite was "referenced")
            refused(c, "/pipelines/import", "/pipelines/import?name=test_etl&conflict=overwrite",
                    zip(withSatellite(c, "anything.toon", "x: 1\n".getBytes(StandardCharsets.UTF_8), p -> {})),
                    "an unreferenced root satellite");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> processing(Map<String, Object> p) {
        Map<String, Object> proc = new LinkedHashMap<>((Map<String, Object>) p.get("processing"));
        p.put("processing", proc);
        return proc;
    }

    // ── SEC-IMPORT-OPS-CONFIGS-1: the suffix-scanned ops and semantic configs ──────────────────────

    /** Every suffix the boot scans load from ANYWHERE under config/ — written only through their own routes. */
    static final List<String> OPS_SUFFIXES = List.of("_workflow.toon", "_caserule.toon", "_tagrule.toon", "_tag.toon",
            "_meta.toon", "_rca.toon", "_job_template.toon", "_escalation.toon", "_queue.toon");

    /** The verified exploit: an INCIDENT lifecycle whose terminal state is CLOSED — one move, no Disposition. */
    private static final String CLOSING_WORKFLOW = """
            workflow:
              object_type: INCIDENT
              initial: IDENTIFIED
              terminal[1]: CLOSED
              transitions[1]{from,to,action}:
                IDENTIFIED,CLOSED,close
            """;

    /**
     * An {@code ops/incident_workflow.toon} returned 200 at {@code /import}, and the next Space start let the last
     * workflow per object type win. Each suffix, in a subdirectory, under registry/, at the root, and NAMED by a
     * real reference key, at every door: 403 and the tree unchanged.
     */
    @Test
    void everyDoorRefusesEverySuffixScannedOpsConfigAnywhere(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, true)) {
            Map<String, Object> base = ConfigCodec.toMap(Files.readString(c.config.resolve("etl_pipeline.toon")));
            for (String suffix : OPS_SUFFIXES) {
                String body = "_workflow.toon".equals(suffix) ? CLOSING_WORKFLOW : "x: 1\n";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                for (String at : List.of("ops/incident" + suffix, "a/b/incident" + suffix, "jobs/incident" + suffix,
                        "registry/datasets/incident" + suffix, "incident" + suffix)) {
                    refused(c, "/import", "/import", dataSourceZip(Map.of(at, body)), at);
                    refused(c, "/import", "/import?on_conflict=overwrite", dataSourceZip(Map.of(at, body)), at + " overwrite");
                }
                // named through a REAL reference key: a reference widens nothing for these
                Map<String, Object> evil = new LinkedHashMap<>(base);
                evil.put("name", "evil_etl");
                processing(evil).put("schema_file", "incident" + suffix);
                refused(c, "/import", "/import", dataSourceZip(Map.of("evil/evil_pipeline.toon", ConfigCodec.toToon(evil),
                        "evil/incident" + suffix, body)), "a referenced evil/incident" + suffix);

                // the Pipeline door, as a satellite: lands beside the pipeline (the root on this overwrite) — referenced
                LinkedHashMap<String, byte[]> referenced = withSatellite(c, "incident" + suffix, bytes,
                        p -> processing(p).put("schema_file", "incident" + suffix));
                refused(c, "/pipelines/import", "/pipelines/import?name=test_etl&conflict=overwrite", zip(referenced),
                        "a referenced satellite incident" + suffix);
                // ...and unreferenced into a fresh Pipeline's own directory (<root>/copy_etl/), where any name passed before
                LinkedHashMap<String, byte[]> fresh = withSatellite(c, "incident" + suffix, bytes, p -> {});
                refused(c, "/pipelines/import", "/pipelines/import?name=copy_etl", zip(fresh),
                        "a satellite incident" + suffix + " in a fresh Pipeline directory");
                refused(c, "/bundle/import", "/bundle/import", metadataBundle(referenced), "a referenced closure file incident" + suffix);
                refused(c, "/bundle/import", "/bundle/import", metadataBundle(fresh), "a closure file incident" + suffix);
            }
            try (Stream<Path> all = Files.walk(c.config)) {
                assertTrue(all.noneMatch(f -> f.getFileName().toString().endsWith("_workflow.toon")), "no workflow landed");
            }
        }
    }

    /** A reserved root-level name one directory down is nobody's legitimate file (defence against a later suffix loader). */
    @Test
    void aReservedRootNameIsRefusedInAnySubdirectory(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, true)) {
            String roles = escalatingRoles(root.resolve("scratch"));
            Map<String, Object> base = ConfigCodec.toMap(Files.readString(c.config.resolve("etl_pipeline.toon")));
            for (String name : List.of("roles.toon", "demo-users.toon", "branding.toon")) {
                refused(c, "/import", "/import", dataSourceZip(Map.of("ops/" + name, roles)), "ops/" + name);
                // NAMED by a real reference key — the shape rule would admit it, so only the reserved-name rule refuses
                Map<String, Object> evil = new LinkedHashMap<>(base);
                evil.put("name", "evil_etl");
                processing(evil).put("schema_file", name);
                refused(c, "/import", "/import", dataSourceZip(Map.of("evil/evil_pipeline.toon", ConfigCodec.toToon(evil),
                        "evil/" + name, roles)), "a referenced evil/" + name);
            }
            assertNoEscalation(c);
        }
    }

    // ── what still imports ───────────────────────────────────────────────────────────────────────

    private static final String SCHEMA = """
            partitionKey: EVENT_DATE
            raw:
              name: ev
              format: CSV
              fields[2]{name,selector,type}:
                ACCOUNT_NUMBER,"account",VARCHAR
                EVENT_DATE,"event_date",DATE
            mapping:
              canonicalName: ev
              rawName: ev
              rules[2]{targetColumn,sourceExpression,transformType}:
                ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """;

    /** A Pipeline in its own directory referencing a schema, a grammar and a mapping, with its Connection and Job. */
    private static Map<String, String> legitimateDataSource(Path dataDir, String id, String schemaFile) throws Exception {
        Path scratch = Files.createDirectories(dataDir.resolve("scratch-" + id));
        Map<String, Object> p = ConfigCodec.toMap(Files.readString(
                TestConfigs.csv(dataDir.resolve(id), PipelineConfigBatchTest.miniSchema()).name(id).write()));
        Map<String, Object> proc = processing(p);
        proc.put("schema_file", schemaFile);
        proc.put("mapping_file", id + "_mapping.csv");
        proc.put("grammar", id + ".grammar.toon");
        Map<String, String> out = new LinkedHashMap<>();
        out.put(id + "/" + id + "_pipeline.toon", ConfigCodec.toToon(p));
        out.put(id + "/" + schemaFile, SCHEMA);
        out.put(id + "/" + id + "_mapping.csv", "targetColumn,sourceExpression,transformType\n"
                + "ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT\nEVENT_DATE,EVENT_DATE,DIRECT\n");
        out.put(id + "/" + id + ".grammar.toon", "name: " + id + "_grammar\nversion: 1\nengine: auto\ndelimiter: \",\"\n"
                + "has_header: false\n");
        out.put("connections/" + id + "_local_connection.toon", "connection:\n  id: " + id.toUpperCase()
                + "_LOCAL\n  connector: local\n  base_path: " + scratch.toString().replace('\\', '/') + "\n");
        out.put("jobs/" + id + "_heartbeat_job.toon", "job:\n  name: " + id + "_heartbeat\n  type: maintenance\n"
                + "  task: heartbeat\n  on_pipeline: " + id + "\n");
        return out;
    }

    @Test
    void aLegitimateDataSourceBundleStillImports(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, true)) {
            Map<String, String> ds = legitimateDataSource(root.resolve("alpha").resolve("data"), "orders", "orders_v2.toon");
            HttpResponse<String> r = send(c, "POST", "/import", dataSourceZip(ds), ONBOARDER);
            assertEquals(200, r.statusCode(), r.body());
            for (String path : ds.keySet()) assertTrue(Files.exists(c.config.resolve(path)), path);
            HttpResponse<String> ids = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                    + "/api/v1/spaces/alpha/datasources")).header("Authorization", BUILDER).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(ids.body().contains("orders"), ids.body());
            // and the Pipeline door takes back test_etl's own export — its root-level schema is REFERENCED
            HttpResponse<String> own = send(c, "POST", "/pipelines/import?name=test_etl&conflict=overwrite",
                    zip(exported(c)), BUILDER);
            assertEquals(200, own.statusCode(), own.body());
            assertNoEscalation(c);
        }
    }

    // ── all-or-nothing ───────────────────────────────────────────────────────────────────────────

    /**
     * Hole 3: a refusal AFTER the unpack (a 422 on a reference, a registration refusal) used to leave every
     * written file on disk. Each case below overwrites an existing file too, so "unchanged" means restored, not
     * merely "nothing new".
     */
    @Test
    void aRefusedImportLeavesTheTreeByteForByteUnchanged(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, true)) {
            Path data = root.resolve("alpha").resolve("data");
            String etl = Files.readString(c.config.resolve("etl_pipeline.toon"));
            String etlChanged = etl.replace("threads: 1", "threads: 2");

            // (1) the integrity gate, which runs on the WRITTEN files: a pipeline naming a schema nobody has
            Map<String, String> broken = new LinkedHashMap<>();
            broken.put("etl_pipeline.toon", etlChanged);
            broken.putAll(legitimateDataSource(data, "fresh", "fresh_v1.toon"));
            broken.remove("fresh/fresh_v1.toon");
            Map<String, String> before = tree(c.config);
            HttpResponse<String> r = send(c, "POST", "/import?on_conflict=overwrite", dataSourceZip(broken), ONBOARDER);
            assertEquals(422, r.statusCode(), r.body());
            assertEquals(before, tree(c.config), "the integrity refusal restored etl_pipeline.toon and removed the rest");

            // (2) a registration refusal after the unpack — a schema that exists but does not parse as one; the
            // pipeline registered before it is forgotten again
            Map<String, String> unregistrable = new LinkedHashMap<>();
            unregistrable.put("etl_pipeline.toon", etlChanged);
            unregistrable.putAll(legitimateDataSource(data, "first", "first_v1.toon"));
            Map<String, String> bad = legitimateDataSource(data, "second", "second_v1.toon");
            bad.put("second/second_mapping.csv", "targetColumn,sourceExpression,transformType\nbad-name!,ACCOUNT_NUMBER,DIRECT\n");
            unregistrable.putAll(bad);
            HttpResponse<String> r2 = send(c, "POST", "/import?on_conflict=overwrite", dataSourceZip(unregistrable), ONBOARDER);
            assertEquals(422, r2.statusCode(), r2.body());
            assertTrue(r2.body().contains("invalid pipeline second/second_pipeline.toon"), "a REGISTRATION refusal: " + r2.body());
            assertEquals(before, tree(c.config), "the registration refusal restored the tree");
            HttpResponse<String> ids = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                    + "/api/v1/spaces/alpha/datasources")).header("Authorization", BUILDER).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertFalse(ids.body().contains("first"), "the pipeline registered before the refusal is unregistered: " + ids.body());

            // (3) a pipeline id registered from ANOTHER file: refused before any write (the up-front pre-check)
            Map<String, String> clash = new LinkedHashMap<>(legitimateDataSource(data, "third", "third_v1.toon"));
            clash.put("elsewhere/elsewhere_pipeline.toon", etl);
            assertEquals(409, send(c, "POST", "/import?on_conflict=overwrite", dataSourceZip(clash), ONBOARDER).statusCode());
            assertEquals(before, tree(c.config));

            // (4) the Pipeline door: a SaveGate ERROR after the satellites landed — the root schema they overwrote
            // is restored, not deleted (the old cleanup deleted every satellite, pre-existing ones included)
            LinkedHashMap<String, byte[]> entries = exported(c);
            Map<String, Object> manifest = ConfigCodec.toMap(new String(entries.get("manifest.toon"), StandardCharsets.UTF_8));
            String pipelineEntry = String.valueOf(manifest.get("pipeline_file"));
            Map<String, Object> pipeline = ConfigCodec.toMap(new String(entries.get(pipelineEntry), StandardCharsets.UTF_8));
            processing(pipeline).put("threads", -7);
            entries.put(pipelineEntry, ConfigCodec.toToon(pipeline).getBytes(StandardCharsets.UTF_8));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sats = (List<Map<String, Object>>) manifest.get("satellites");
            String sat = String.valueOf(sats.get(0).get("path"));
            sats.get(0).remove("sha256");
            entries.put("manifest.toon", ConfigCodec.toToon(manifest).getBytes(StandardCharsets.UTF_8));
            entries.put(sat, "changed: true\n".getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> r4 = send(c, "POST", "/pipelines/import?name=test_etl&conflict=overwrite", zip(entries), BUILDER);
            assertEquals(422, r4.statusCode(), r4.body());
            assertEquals(before, tree(c.config), "the SaveGate refusal restored the overwritten satellite");
        }
    }

    // ── the other doors ──────────────────────────────────────────────────────────────────────────

    @Test
    void aComponentBundleCannotCarryTheAccessConfig(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, true)) {
            for (String kind : List.of("access-profile", "access-catalog")) {
                String bundle = "{\"bundle\":{\"format\":\"inspecto-metadata-bundle\",\"version\":1,\"items\":[{\"kind\":\""
                        + kind + "\",\"id\":\"user-builder-1\",\"content\":{\"subjectType\":\"user\",\"subjectId\":\"builder-1\",\"grants\":{}}}]}}";
                refused(c, "/bundle/import", "/bundle/import", bundle.getBytes(StandardCharsets.UTF_8), kind);
            }
        }
    }

    /** The allowlist, not a denylist: an unknown root-level file is refused even though no rule names it. */
    @Test
    void theConfigRootTakesOnlyConventionalConfigShapes(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, false)) {
            for (String shape : List.of("anything.toon", "future-settings.toon", "registry/requirements/x/y.toon",
                    "registry/unknown-kind/x.toon", "orders/data.parquet", "notes.txt", "approval.toon",
                    "branding.toon", "nav-menus.toon", "rename.journal", "recon-state/r.toon",
                    "expectation-baselines/e.toon", ".history/v1.toon", "registry/../roles.toon"))
                refused(c, "/import", "/import", dataSourceZip(Map.of(shape, "x: 1\n")), shape);
        }
    }
}
