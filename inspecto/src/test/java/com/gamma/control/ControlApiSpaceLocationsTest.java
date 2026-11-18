package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.Roles;
import com.gamma.config.safety.DiscoveredRoots;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.AppRoot;
import com.gamma.service.CollectorService;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceLocations;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Space locations over real HTTP ({@code SPACE-LOCATIONS-1}, plan D1-D9), armed: {@code POST /spaces/_locations/check},
 * {@code POST /spaces/attach}, {@code POST /spaces/{id}/detach}, {@code PUT /spaces/{id}/data-root}, {@code POST /spaces}
 * with {@code path} / {@code dataRoot}, and the {@code location} of a {@code GET /spaces} row. Every gate in order: 401 /
 * 403 without {@code canAdminister}, 409 on a single-tenant server, 409 {@code SPACE_LOCATIONS_DISABLED} without the
 * launch setting, 422 for a malformed body, 403 outside the allowed folders ({@code ..} and links included), 409 for a
 * taken id or an overlap, then the act. Synthetic fixtures only.
 */
class ControlApiSpaceLocationsTest {

    private static final String ADMIN = "Bearer admin", ANALYST = "Bearer analyst";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] ROUTES = {"POST /spaces/_locations/check", "POST /spaces/attach",
            "POST /spaces/home/detach", "PUT /spaces/home/data-root"};

    @TempDir Path tmp;
    private Path install, spacesRoot, disks, inside;
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of(Roles.CAN_ADMINISTER)));
            case ANALYST -> Optional.of(new Subject("analyst-1", Set.of(Roles.CAN_UPLOAD_DATA)));
            default -> Optional.empty();
        });
        install = Files.createDirectories(tmp.resolve("install"));
        System.setProperty(AppRoot.PROPERTY, install.toString());
        spacesRoot = Files.createDirectories(install.resolve("spaces"));
        Files.createDirectories(spacesRoot.resolve("home").resolve("config"));
        disks = Files.createDirectories(tmp.resolve("disks"));          // an allowed folder outside the install
        inside = Files.createDirectories(install.resolve("disk"));      // an allowed folder under the install
        allow(disks, inside);
    }

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        System.clearProperty(SpaceLocations.PROPERTY);
        System.clearProperty(AppRoot.PROPERTY);
        DiscoveredRoots.clear();
    }

    private static void allow(Path... roots) {
        System.setProperty(SpaceLocations.PROPERTY,
                String.join(File.pathSeparator, java.util.Arrays.stream(roots).map(Path::toString).toList()));
    }

    private Ctx open() throws Exception {
        SpaceManager spaces = SpaceManager.discover(spacesRoot);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    private HttpResponse<String> send(int port, String method, String path, Object body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        return client.send(b.method(method, body == null ? BodyPublishers.noBody()
                : BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> send(Ctx c, String method, String path, Object body) throws Exception {
        return send(c.port, method, path, body, ADMIN);
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static String code(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("error").get("errorCode").asText();
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** A Space folder kept outside the Spaces root. */
    private Path spaceFolder(Path parent, String id) throws Exception {
        Path f = Files.createDirectories(parent.resolve(id).resolve("config"));
        return f.getParent();
    }

    private static String[] split(String route) {
        return route.split(" ", 2);
    }

    // ── gates, in order ──────────────────────────────────────────────────────────────────────────────

    @Test
    void everyRouteRefusesACallerWithoutCanAdminister() throws Exception {
        try (Ctx c = open()) {
            for (String route : ROUTES) {
                String[] r = split(route);
                assertEquals(401, send(c.port, r[0], r[1], body("path", disks.toString()), null).statusCode(), route);
                assertEquals("PERMISSION_DENIED", code(send(c.port, r[0], r[1], body("path", disks.toString()), ANALYST), 403),
                        route);
            }
            assertEquals("PERMISSION_DENIED", code(send(c.port, "POST", "/spaces",
                    body("id", "acme", "path", disks.resolve("acme").toString()), ANALYST), 403));
        }
    }

    @Test
    void aSingleTenantServerAnswers409() throws Exception {
        System.setProperty("assist.write.root", tmp.resolve("cfg").toString());
        CollectorService svc = new CollectorService(List.of(), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        try {
            api.start();
            for (String route : ROUTES) {
                String[] r = split(route);
                HttpResponse<String> res = send(api.port(), r[0], r[1].replace("home", "default"),
                        body("path", disks.toString(), "kind", "space"), ADMIN);
                assertEquals("CONFLICT", code(res, 409), route);
                assertTrue(res.body().contains("single space"), res.body());
            }
        } finally {
            System.clearProperty("assist.write.root");
            api.close();
            svc.close();
        }
    }

    @Test
    void withoutTheSettingEveryFolderRouteAnswersDisabled() throws Exception {
        System.clearProperty(SpaceLocations.PROPERTY);
        assumeTrue(System.getenv(SpaceLocations.ENV) == null, "the environment sets " + SpaceLocations.ENV);
        try (Ctx c = open()) {
            for (String route : ROUTES) {
                String[] r = split(route);
                HttpResponse<String> res = send(c, r[0], r[1], body("path", disks.toString(), "kind", "space"));
                assertEquals("SPACE_LOCATIONS_DISABLED", code(res, 409), route);
                assertTrue(res.body().contains(SpaceLocations.PROPERTY), res.body());
            }
            assertEquals("SPACE_LOCATIONS_DISABLED", code(send(c, "POST", "/spaces",
                    body("id", "acme", "dataRoot", disks.resolve("acme").toString())), 409));
            // the setting is checked before the body (D9): an empty body still answers disabled
            assertEquals("SPACE_LOCATIONS_DISABLED", code(send(c, "POST", "/spaces/_locations/check", body()), 409));
            assertFalse(data(send(c, "GET", "/spaces/_meta", null), 200).get("locationsEnabled").asBoolean());
            // a create without folders is untouched by the setting
            data(send(c, "POST", "/spaces", body("id", "plain")), 200);
        }
    }

    @Test
    void aMalformedBodyIs422() throws Exception {
        try (Ctx c = open()) {
            assertEquals("CONFIG_VALIDATION_FAILED", code(send(c, "POST", "/spaces/_locations/check",
                    body("path", disks.toString(), "kind", "elsewhere")), 422));
            assertEquals("CONFIG_VALIDATION_FAILED", code(send(c, "POST", "/spaces/_locations/check", body("kind", "data")), 422));
            assertEquals("CONFIG_VALIDATION_FAILED", code(send(c, "POST", "/spaces/attach", body("path", "s3://bucket/acme")), 422));
            assertEquals("CONFIG_VALIDATION_FAILED", code(send(c, "POST", "/spaces/attach", body("path", "  ")), 422));
            assertEquals("CONFIG_VALIDATION_FAILED", code(send(c, "POST", "/spaces/attach",
                    body("path", spaceFolder(disks, "acme").toString(), "id", "other")), 422));
            assertEquals("CONFIG_VALIDATION_FAILED", code(send(c, "PUT", "/spaces/home/data-root", body()), 422));
            assertEquals("CONFIG_VALIDATION_FAILED", code(send(c, "POST", "/spaces",
                    body("id", "acme", "path", disks.resolve("not-acme").toString())), 422));
        }
    }

    @Test
    void aPathOutsideTheAllowedFoldersIs403EvenThroughDotDot() throws Exception {
        Path outside = spaceFolder(tmp.resolve("elsewhere"), "acme");
        String escaping = disks + File.separator + ".." + File.separator + "elsewhere" + File.separator + "acme";
        try (Ctx c = open()) {
            assertEquals("PATH_JAIL_VIOLATION", code(send(c, "POST", "/spaces/attach", body("path", outside.toString())), 403));
            assertEquals("PATH_JAIL_VIOLATION", code(send(c, "POST", "/spaces/attach", body("path", escaping)), 403));
            assertEquals("PATH_JAIL_VIOLATION", code(send(c, "PUT", "/spaces/home/data-root",
                    body("path", tmp.resolve("elsewhere").resolve("data").toString())), 403));
            assertEquals("PATH_JAIL_VIOLATION", code(send(c, "POST", "/spaces",
                    body("id", "acme", "path", escaping)), 403));
            JsonNode check = data(send(c, "POST", "/spaces/_locations/check", body("path", escaping, "kind", "space")), 200);
            assertFalse(check.get("ok").asBoolean());
            assertFalse(check.get("checks").get(0).get("ok").asBoolean(), "the allowed-folder check fails first");
            assertFalse(Files.exists(spacesRoot.resolve("acme.ref")));
        }
    }

    /** A link (a symlink, or a junction where symlinks need a privilege) inside an allowed folder that leads out of it. */
    @Test
    void aLinkLeadingOutOfAnAllowedFolderIs403() throws Exception {
        Path target = Files.createDirectories(tmp.resolve("elsewhere"));
        spaceFolder(target, "acme");
        Path link = disks.resolve("door");
        assumeTrue(link(link, target), "this host can make neither a symlink nor a junction");
        try (Ctx c = open()) {
            assertEquals("PATH_JAIL_VIOLATION", code(send(c, "POST", "/spaces/attach",
                    body("path", link.resolve("acme").toString())), 403));
            assertFalse(Files.exists(spacesRoot.resolve("acme.ref")));
        }
    }

    private static boolean link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception noPrivilege) {
            if (!File.separator.equals("\\")) return false;
            try {
                Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                return p.waitFor() == 0 && Files.isDirectory(link);
            } catch (Exception e) {
                return false;
            }
        }
    }

    @Test
    void aTakenIdOrAnOverlapIs409() throws Exception {
        Path home2 = spaceFolder(disks, "home");   // a Space folder whose id is already hosted
        try (Ctx c = open()) {
            HttpResponse<String> taken = send(c, "POST", "/spaces/attach", body("path", home2.toString()));
            assertEquals("CONFLICT", code(taken, 409));
            assertTrue(taken.body().contains("already exists"), taken.body());
            // a data folder inside another Space's folder
            data(send(c, "POST", "/spaces", body("id", "acme")), 200);
            data(send(c, "PUT", "/spaces/acme/data-root", body("path", disks.resolve("acme-data").toString())), 200);
            HttpResponse<String> nested = send(c, "POST", "/spaces", body("id", "beta",
                    "dataRoot", disks.resolve("acme-data").resolve("beta").toString()));   // nothing may sit inside it now
            assertEquals("CONFLICT", code(nested, 409));
            assertTrue(nested.body().contains("overlaps Space 'acme'"), nested.body());
            // a Space folder inside the Spaces root needs no path
            Path inRoot = spacesRoot.resolve("gamma");
            HttpResponse<String> inSpaces = send(c, "POST", "/spaces", body("id", "gamma", "path", inRoot.toString()));
            assertEquals(403, inSpaces.statusCode(), "the Spaces root is not an allowed folder: " + inSpaces.body());
        }
    }

    // ── attach and detach (D3, D4) ───────────────────────────────────────────────────────────────────

    @Test
    void attachBootsTheSpaceLiveAndAgainAfterARestart() throws Exception {
        Path acme = spaceFolder(disks, "acme");
        Files.writeString(acme.resolve("space.toon"), "display_name: Acme\nadmission: closed\ncreated_by: someone\n");
        try (Ctx c = open()) {
            JsonNode row = data(send(c, "POST", "/spaces/attach", body("path", acme.toString(), "id", "acme")), 200);
            assertEquals("acme", row.get("id").asText());
            assertEquals("closed", row.get("admission").asText(), "the Space keeps the admission its space.toon says");
            assertTrue(row.get("location").get("referenced").asBoolean());
            assertEquals(acme.toAbsolutePath().normalize().toString(), row.get("location").get("spaceFolder").asText());
            assertTrue(c.spaces.space(SpaceId.of("acme")).isPresent(), "booted live, no restart");
            assertEquals(200, send(c, "GET", "/spaces/acme/pipelines", null).statusCode());
            assertEquals(acme.toAbsolutePath().normalize().toString(),
                    Files.readString(spacesRoot.resolve("acme.ref")).trim().replace('/', File.separatorChar),
                    "outside the install the reference is absolute");
        }
        try (SpaceManager again = SpaceManager.discover(spacesRoot)) {   // a restart over the same Spaces root
            assertTrue(again.space(SpaceId.of("acme")).isPresent(), "the reference boots it again");
        }
    }

    @Test
    void attachUnderTheInstallWritesARelativeReference() throws Exception {
        Path acme = spaceFolder(inside, "acme");
        try (Ctx c = open()) {
            data(send(c, "POST", "/spaces/attach", body("path", acme.toString())), 200);
            assertEquals("disk/acme", Files.readString(spacesRoot.resolve("acme.ref")).trim());
        }
    }

    @Test
    void attachRefusesAFolderThatIsNotASpace() throws Exception {
        Path notASpace = Files.createDirectories(disks.resolve("acme"));
        try (Ctx c = open()) {
            HttpResponse<String> r = send(c, "POST", "/spaces/attach", body("path", notASpace.toString()));
            assertEquals("CONFLICT", code(r, 409));
            assertTrue(r.body().contains("not a Space"), r.body());
            assertFalse(Files.exists(spacesRoot.resolve("acme.ref")));
        }
    }

    @Test
    void detachLeavesBothFoldersAndRefusesASpaceInsideTheSpacesRoot() throws Exception {
        Path acme = spaceFolder(disks, "acme");
        Path data = inside.resolve("acme-data");   // not beside the Space folder: the boot refuses its holder
        Files.writeString(acme.resolve("space.toon"), "data_root: \"" + data.toString().replace('\\', '/') + "\"\n");
        try (Ctx c = open()) {
            data(send(c, "POST", "/spaces/attach", body("path", acme.toString())), 200);
            assertTrue(Files.isDirectory(data.resolve("duckdb")), "booted onto its data folder");
            JsonNode out = data(send(c, "POST", "/spaces/acme/detach", null), 200);
            assertTrue(out.get("detached").asBoolean());
            assertTrue(c.spaces.space(SpaceId.of("acme")).isEmpty());
            assertFalse(Files.exists(spacesRoot.resolve("acme.ref")));
            assertTrue(Files.isDirectory(acme.resolve("config")), "the Space folder stays");
            assertTrue(Files.isDirectory(data.resolve("duckdb")), "the data folder stays");
            assertEquals(404, send(c, "POST", "/spaces/acme/detach", null).statusCode());

            HttpResponse<String> home = send(c, "POST", "/spaces/home/detach", null);
            assertEquals("CONFLICT", code(home, 409));
            assertTrue(home.body().contains("DELETE /spaces/home"), home.body());
            assertTrue(c.spaces.space(SpaceId.of("home")).isPresent());

            data(send(c, "POST", "/spaces/attach", body("path", acme.toString())), 200);   // and it can be attached again
        }
    }

    // ── data folder (D5) ─────────────────────────────────────────────────────────────────────────────

    @Test
    void anEmptySpaceIsPointedAtADataFolderAndBack() throws Exception {
        try (Ctx c = open()) {
            data(send(c, "POST", "/spaces", body("id", "acme")), 200);
            Path target = inside.resolve("acme-data");
            JsonNode row = data(send(c, "PUT", "/spaces/acme/data-root", body("path", target.toString())), 200);
            assertEquals(target.toAbsolutePath().normalize().toString(), row.get("location").get("dataFolder").asText());
            String toon = Files.readString(spacesRoot.resolve("acme").resolve("space.toon"));
            assertTrue(toon.contains("disk/acme-data"), "relative to the app root when under it: " + toon);
            assertTrue(Files.isRegularFile(target.resolve(".inspecto-space")), "the boot stamps the folder");
            assertTrue(Files.isDirectory(target.resolve("duckdb")));
            assertTrue(c.spaces.space(SpaceId.of("acme")).isPresent());

            JsonNode back = data(send(c, "PUT", "/spaces/acme/data-root", body("path", null)), 200);
            assertEquals(spacesRoot.resolve("acme").toAbsolutePath().normalize().toString(),
                    back.get("location").get("dataFolder").asText());
            assertFalse(Files.readString(spacesRoot.resolve("acme").resolve("space.toon")).contains("acme-data"));
            assertFalse(Files.exists(target.resolve(".inspecto-space")), "the old folder is released");
        }
    }

    /** A file-backed event store writes data/events/ at every boot (the demo launcher's -Devents.backend=parquet): that
     *  log is not data a person put there, so an attached Space can still be pointed at its data folder, and the log moves. */
    @Test
    void theEventLogEveryBootWritesIsNotData() throws Exception {
        Path events = spacesRoot.resolve("home").resolve("data").resolve("events");
        Files.createDirectories(events);
        Files.writeString(events.resolve("pending.jsonl"), "{}\n");
        try (Ctx c = open()) {
            assertFalse(data(send(c, "GET", "/spaces", null), 200).get(0).get("location").get("holdsData").asBoolean());
            Path target = disks.resolve("home-data");
            data(send(c, "PUT", "/spaces/home/data-root", body("path", target.toString())), 200);
            assertTrue(Files.isRegularFile(target.resolve("data").resolve("events").resolve("pending.jsonl")), "the log moves");
        }
    }

    @Test
    void aSpaceThatHoldsDataIsRefused() throws Exception {
        Files.createDirectories(spacesRoot.resolve("home").resolve("data").resolve("inbox"));
        Files.writeString(spacesRoot.resolve("home").resolve("data").resolve("inbox").resolve("a.csv"), "x\n");
        try (Ctx c = open()) {
            HttpResponse<String> r = send(c, "PUT", "/spaces/home/data-root", body("path", disks.resolve("home").toString()));
            assertEquals("SPACE_HOLDS_DATA", code(r, 409));
            assertTrue(r.body().contains("move it by hand"), r.body());
            assertFalse(Files.exists(disks.resolve("home")));
            assertTrue(data(send(c, "GET", "/spaces", null), 200).get(0).get("location").get("holdsData").asBoolean());
        }
    }

    // ── create into chosen folders (D6) ──────────────────────────────────────────────────────────────

    @Test
    void aBlankSpaceIsCreatedIntoAChosenFolderAndDataFolder() throws Exception {
        Path folder = disks.resolve("acme");
        Path dataFolder = Files.createDirectories(inside.resolve("data-acme"));   // pre-created, empty
        try (Ctx c = open()) {
            data(send(c, "POST", "/spaces", body("id", "acme", "path", folder.toString(),
                    "dataRoot", dataFolder.toString())), 200);
            assertTrue(Files.isRegularFile(folder.resolve("space.toon")));
            assertTrue(Files.isRegularFile(spacesRoot.resolve("acme.ref")));
            assertTrue(Files.isRegularFile(dataFolder.resolve(".inspecto-space")));
            assertTrue(Files.isDirectory(dataFolder.resolve("duckdb")));
            assertFalse(Files.exists(folder.resolve("duckdb")), "the stores live on the data folder");
        }
        try (SpaceManager again = SpaceManager.discover(spacesRoot)) {
            assertEquals(dataFolder.toAbsolutePath().normalize(),
                    again.space(SpaceId.of("acme")).orElseThrow().root().dataRoot());
        }
    }

    @Test
    void aTemplateSpaceIsCreatedIntoChosenFoldersWithItsSamplesOnTheDataFolder() throws Exception {
        Path tpl = spacesRoot.resolve("_templates").resolve("starter");
        Files.createDirectories(tpl.resolve("config"));
        Files.createDirectories(tpl.resolve("data").resolve("samples"));
        Files.writeString(tpl.resolve("template.toon"), "name: Starter\n");
        Files.writeString(tpl.resolve("data").resolve("samples").resolve("s.csv"), "a\n1\n");
        Path folder = Files.createDirectories(disks.resolve("acme"));   // pre-created, empty
        Path dataFolder = inside.resolve("data-acme");
        try (Ctx c = open()) {
            data(send(c, "POST", "/spaces", body("id", "acme", "template", "starter", "path", folder.toString(),
                    "dataRoot", dataFolder.toString())), 200);
            assertTrue(Files.isRegularFile(dataFolder.resolve("data").resolve("samples").resolve("s.csv")));
            assertFalse(Files.exists(folder.resolve("data").resolve("samples")));
            assertTrue(Files.isDirectory(folder.resolve("config")));
            assertTrue(c.spaces.space(SpaceId.of("acme")).isPresent());
            HttpResponse<String> notEmpty = send(c, "POST", "/spaces", body("id", "beta", "path",
                    Files.writeString(Files.createDirectories(disks.resolve("beta")).resolve("x.txt"), "x").getParent().toString()));
            assertEquals("CONFLICT", code(notEmpty, 409));
        }
    }

    // ── checks and rows (D7, D8) ─────────────────────────────────────────────────────────────────────

    @Test
    void theCheckReportsEveryCheckAndWritesNothing() throws Exception {
        Path acme = spaceFolder(disks, "acme");
        try (Ctx c = open()) {
            JsonNode meta = data(send(c.port, "GET", "/spaces/_meta", null, ANALYST), 200);
            assertTrue(meta.get("locationsEnabled").asBoolean(), "the setting is set: " + meta);
            assertTrue(meta.get("multiSpace").asBoolean());
            JsonNode r = data(send(c, "POST", "/spaces/_locations/check", body("path", acme.toString(), "kind", "space")), 200);
            assertTrue(r.get("ok").asBoolean(), r.toString());
            List<String> names = new java.util.ArrayList<>();
            r.get("checks").forEach(ch -> names.add(ch.get("check").asText()));
            assertEquals(List.of("allowed", "install", "overlap", "name", "reachable", "writable", "free-space", "content"), names);
            JsonNode d = data(send(c, "POST", "/spaces/_locations/check",
                    body("path", disks.resolve("d").toString(), "kind", "data", "id", "home")), 200);
            assertTrue(d.get("ok").asBoolean(), d.toString());
            assertFalse(Files.exists(spacesRoot.resolve("acme.ref")));
            assertFalse(Files.exists(disks.resolve("d")));
        }
    }

    @Test
    void onlyAnAdministratorSeesWhereASpaceLives() throws Exception {
        try (Ctx c = open()) {
            JsonNode admin = data(send(c.port, "GET", "/spaces", null, ADMIN), 200).get(0);
            JsonNode loc = admin.get("location");
            assertEquals(spacesRoot.resolve("home").toAbsolutePath().normalize().toString(), loc.get("spaceFolder").asText());
            assertEquals(loc.get("spaceFolder").asText(), loc.get("dataFolder").asText());
            assertFalse(loc.get("referenced").asBoolean());
            assertFalse(loc.get("holdsData").asBoolean());
            JsonNode analyst = data(send(c.port, "GET", "/spaces", null, ANALYST), 200).get(0);
            assertFalse(analyst.has("location"), analyst.toString());
            assertFalse(analyst.toString().contains(tmp.getFileName().toString()), "no path for a non-administrator");
        }
    }
}
