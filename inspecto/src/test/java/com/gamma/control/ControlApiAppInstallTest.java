package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRODUCT-ASSEMBLY-1 PA-7d over real HTTP: install, upgrade and remove a signed App on a running server, with every Space's
 * config byte-identical through all three (the slice's proof, D-PA25), and every gate: 401 / 403 / 200 on the capability,
 * 409 off a Platform product build, 422 for a package that does not verify, a Platform range or a path id
 * that disagrees, 409 for a reinstall or a template a build ships, 404 for an App not installed.
 */
class ControlApiAppInstallTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN = "Bearer admin", OPS = "Bearer ops";
    private final HttpClient client = HttpClient.newHttpClient();
    private KeyPair signer;

    @TempDir Path tmp;

    @BeforeEach
    void arm() throws Exception {
        Authenticators.forTest(ex -> {
            String who = ex.getRequestHeaders().getFirst("Authorization");
            String role = ADMIN.equals(who) ? "admin" : OPS.equals(who) ? "operations" : null;
            if (role == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(role);
            ComponentAccess.heldRoles(ex, java.util.Set.of(role));
            return Optional.of(new Subject(role + "-1", def.capabilities(), def.dataScopes()));
        });
        signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path signers = tmp.resolve("app-signers.properties");
        Files.writeString(signers, "acme-signing-1=" + Base64.getEncoder().encodeToString(signer.getPublic().getEncoded()) + "\n");
        System.setProperty(AppPackage.SIGNERS_PROPERTY, signers.toString());
        System.setProperty(InstalledApps.ROOT_PROPERTY, tmp.resolve("apps").toString());
        descriptor("platform");
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        System.clearProperty(AppPackage.SIGNERS_PROPERTY);
        System.clearProperty(InstalledApps.ROOT_PROPERTY);
        System.clearProperty(ProductDescriptor.PROPERTY);
    }

    private void descriptor(String shape) throws Exception {
        Path f = tmp.resolve("product-" + shape + ".json");
        Files.writeString(f, "{\"schema\":\"inspecto.product/1\",\"id\":\"inspecto\",\"title\":\"Inspecto\",\"shape\":\"" + shape
                + "\",\"host\":\"gamma\",\"brand\":{\"name\":\"Inspecto\"},\"edition\":\"Standard\",\"distribution\":\"production\",\"apps\":[]}");
        System.setProperty(ProductDescriptor.PROPERTY, f.toString());
    }

    private record Ctx(SpaceManager spaces, ControlApi api, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private Ctx open() throws Exception {
        Path root = tmp.resolve("spaces");
        Path shipped = Files.createDirectories(root.resolve("_templates").resolve("shipped-tpl"));
        Files.writeString(shipped.resolve("template.toon"), "name: \"Shipped\"\ntagline: \"a template the build ships\"\n");
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, root);
    }

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.api().port() + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        b.header("Content-Type", body == null ? "application/json" : "application/zip");
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofByteArray(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> sendJson(Ctx c, String method, String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.api().port() + "/api/v1" + path))
                .header("Authorization", ADMIN).header("Content-Type", "application/json")
                .method(method, BodyPublishers.ofString(json)).build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        JsonNode n = JSON.readTree(r.body());
        return n.has("data") ? n.get("data") : n;
    }

    /** The App's files, as tools/inspecto-app.mjs would package them (paths under the App folder). */
    private static Map<String, String> appFiles(String version, boolean v2) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("app.toon", "id: acme-spend\ntitle: Acme Spend Watch\nversion: " + version + "\nplatform: >=0.1\nindustries[0]:\n"
                + "requires:\n  addons[0]:\ntemplates[1]: acme-spend\nnav:\n  findings:\n    path: data-browser\n    label: Acme Spend Watch findings\n"
                + "docs: docs/index.md\n");
        f.put("docs/index.md", "# Acme Spend Watch\n");
        f.put("templates/acme-spend/template.toon", "name: \"Acme Spend Watch\"\ntagline: \"one feed\"\nminEdition: basic\n");
        f.put("templates/acme-spend/config/registry/datasets/acme_findings.toon", "name: acme_findings\nphysicalRef: acme_findings\n");
        f.put("templates/acme-spend/config/jobs/acme_detect_job.toon",
                "job:\n  name: acme_detect\n  type: sql.template\n  description: \"" + (v2 ? "v2 threshold" : "v1") + "\"\n  enabled: false\n");
        if (v2) f.put("templates/acme-spend/config/registry/datasets/acme_extra.toon", "name: acme_extra\nphysicalRef: acme_extra\n");
        return f;
    }

    /** A package: one App folder, MANIFEST.sha256 and, when {@code sign}, MANIFEST.sig by {@code signer} as {@code keyId}. */
    private byte[] pkg(String id, Map<String, String> files, boolean sign, String keyId, String tamper) throws Exception {
        StringBuilder manifest = new StringBuilder();
        Map<String, byte[]> entries = new TreeMap<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            byte[] b = e.getValue().getBytes(StandardCharsets.UTF_8);
            entries.put(id + "/" + e.getKey(), b);
            manifest.append(AppPackage.sha256(b)).append("  ").append(id).append('/').append(e.getKey()).append('\n');
        }
        byte[] m = manifest.toString().getBytes(StandardCharsets.UTF_8);
        entries.put(id + "/MANIFEST.sha256", m);
        if (sign) {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(signer.getPrivate());
            s.update(m);
            entries.put(id + "/MANIFEST.sig", ("keyId=" + keyId + "\nsignature=" + Base64.getEncoder().encodeToString(s.sign()) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (tamper != null) entries.put(id + "/" + tamper, (new String(entries.get(id + "/" + tamper), StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private byte[] good(String version, boolean v2) throws Exception {
        return pkg("acme-spend", appFiles(version, v2), true, "acme-signing-1", null);
    }

    /** Every file under every Space's config/, with its bytes - the "no lost config" witness. */
    private static Map<String, byte[]> spaceConfigs(Path root) throws Exception {
        Map<String, byte[]> out = new TreeMap<>();
        try (Stream<Path> s = Files.list(root)) {
            for (Path space : s.filter(Files::isDirectory).toList()) {
                String n = space.getFileName().toString();
                if (n.startsWith("_") || n.startsWith(".") || !Files.isDirectory(space.resolve("config"))) continue;
                try (Stream<Path> w = Files.walk(space.resolve("config"))) {
                    for (Path f : w.filter(Files::isRegularFile).toList()) out.put(root.relativize(f).toString(), Files.readAllBytes(f));
                }
            }
        }
        return out;
    }

    private static void assertSame(Map<String, byte[]> before, Map<String, byte[]> after) {
        assertEquals(before.keySet(), after.keySet(), "the same config files");
        for (String k : before.keySet()) assertArrayEquals(before.get(k), after.get(k), k + " is byte-identical");
    }

    private static List<String> changes(JsonNode files) {
        List<String> out = new ArrayList<>();
        for (JsonNode f : files) out.add(f.path("path").asText() + ":" + f.path("change").asText());
        return out;
    }

    @Test
    void installUpgradeAndRemoveOnARunningServerLoseNoConfig() throws Exception {
        try (Ctx c = open()) {
            assertEquals(200, sendJson(c, "POST", "/spaces", "{\"id\":\"plain\"}").statusCode());
            // dry run: the plan, nothing written
            JsonNode plan = data(send(c, "POST", "/apps?dryRun=true", good("0.1.0", false), ADMIN));
            assertTrue(plan.path("dryRun").asBoolean(), plan.toString());
            assertTrue(changes(plan.path("templates").get(0).path("files")).contains("config/jobs/acme_detect_job.toon:added"), plan.toString());
            assertEquals("findings", plan.path("nav").get(0).path("id").asText());
            assertFalse(Files.exists(tmp.resolve("apps").resolve("acme-spend")), "a dry run writes nothing");

            Map<String, byte[]> before = spaceConfigs(c.root());
            HttpResponse<String> install = send(c, "POST", "/apps", good("0.1.0", false), ADMIN);
            assertEquals(200, install.statusCode(), install.body());
            assertEquals("acme-signing-1", data(install).path("installed").path("signedBy").asText());
            assertSame(before, spaceConfigs(c.root()));
            assertTrue(send(c, "GET", "/apps", null, ADMIN).body().contains("\"acme-spend\""));
            assertTrue(send(c, "GET", "/spaces/templates", null, ADMIN).body().contains("acme-spend"), "the template is offered at once");
            JsonNode nav = data(send(c, "GET", "/bootstrap", null, ADMIN)).path("product").path("nav");
            assertTrue(nav.toString().contains("Acme Spend Watch findings"), "the App's nav is served at once: " + nav);

            // a Space made from the App's template records where it came from
            assertEquals(200, sendJson(c, "POST", "/spaces", "{\"id\":\"made\",\"template\":\"acme-spend\"}").statusCode());
            assertTrue(Files.readString(c.root().resolve("made").resolve(InstalledApps.PROVENANCE)).contains("app: acme-spend"));

            // upgrade: the template changes; each Space made from it is offered the difference, and none is changed
            before = spaceConfigs(c.root());
            JsonNode up = data(send(c, "PUT", "/apps/acme-spend", good("0.2.0", true), ADMIN));
            assertEquals("0.1.0", up.path("installedVersion").asText());
            JsonNode offer = up.path("spaces").get(0);
            assertEquals("made", offer.path("space").asText(), up.toString());
            List<String> offered = changes(offer.path("files"));
            assertTrue(offered.contains("config/jobs/acme_detect_job.toon:update"), offered.toString());
            assertTrue(offered.contains("config/registry/datasets/acme_extra.toon:new"), offered.toString());
            assertSame(before, spaceConfigs(c.root()));
            assertTrue(Files.readString(c.root().resolve("_templates/acme-spend/config/jobs/acme_detect_job.toon")).contains("v2 threshold"),
                    "new Spaces get the new version");

            // one upgrade behind (made from 0.1.0, 0.2.0 installed, 0.3.0 offered): an untouched file is still "update" - the baseline is
            // the file as the Space was created, not the template installed now
            Map<String, String> v3 = appFiles("0.3.0", true);
            v3.put("templates/acme-spend/config/jobs/acme_detect_job.toon", v3.get("templates/acme-spend/config/jobs/acme_detect_job.toon").replace("v2 threshold", "v3 threshold"));
            byte[] v3pkg = pkg("acme-spend", v3, true, "acme-signing-1", null);
            JsonNode behind = data(send(c, "PUT", "/apps/acme-spend?dryRun=true", v3pkg, ADMIN));
            assertTrue(changes(behind.path("spaces").get(0).path("files")).contains("config/jobs/acme_detect_job.toon:update"), behind.toString());
            // and only a Space that changed the file is told it is customised (the Space is still not changed)
            Path edited = c.root().resolve("made/config/jobs/acme_detect_job.toon");
            Files.writeString(edited, Files.readString(edited).replace("v1", "edited here"));
            JsonNode again = data(send(c, "PUT", "/apps/acme-spend?dryRun=true", v3pkg, ADMIN));
            assertTrue(changes(again.path("spaces").get(0).path("files")).contains("config/jobs/acme_detect_job.toon:customised"), again.toString());

            // remove: templates and nav go; the Spaces keep every file and are named
            before = spaceConfigs(c.root());
            JsonNode removed = data(send(c, "DELETE", "/apps/acme-spend", null, ADMIN));
            assertEquals("[\"made\"]", removed.path("spacesKept").toString());
            assertSame(before, spaceConfigs(c.root()));
            assertFalse(Files.exists(c.root().resolve("_templates/acme-spend")));
            assertTrue(Files.exists(c.root().resolve("_templates/shipped-tpl")), "a shipped template is never touched");
            assertFalse(data(send(c, "GET", "/bootstrap", null, ADMIN)).path("product").path("nav").toString().contains("Acme Spend Watch"));
        }
    }

    @Test
    void theCapabilityGate401And403And200() throws Exception {
        try (Ctx c = open()) {
            assertEquals(401, send(c, "POST", "/apps?dryRun=true", good("0.1.0", false), null).statusCode());
            assertEquals(403, send(c, "POST", "/apps?dryRun=true", good("0.1.0", false), OPS).statusCode());
            assertEquals(200, send(c, "POST", "/apps?dryRun=true", good("0.1.0", false), ADMIN).statusCode());
            assertEquals(403, send(c, "DELETE", "/apps/acme-spend", null, OPS).statusCode());
        }
    }

    @Test
    void aPackageThatDoesNotVerifyIs422() throws Exception {
        try (Ctx c = open()) {
            HttpResponse<String> unsigned = send(c, "POST", "/apps", pkg("acme-spend", appFiles("0.1.0", false), false, null, null), ADMIN);
            assertEquals(422, unsigned.statusCode());
            assertTrue(unsigned.body().contains("not signed"), unsigned.body());
            HttpResponse<String> untrusted = send(c, "POST", "/apps", pkg("acme-spend", appFiles("0.1.0", false), true, "someone-else", null), ADMIN);
            assertTrue(untrusted.statusCode() == 422 && untrusted.body().contains("does not trust"), untrusted.body());
            HttpResponse<String> tampered = send(c, "POST", "/apps", pkg("acme-spend", appFiles("0.1.0", false), true, "acme-signing-1", "app.toon"), ADMIN);
            assertTrue(tampered.statusCode() == 422 && tampered.body().contains("does not match its manifest hash"), tampered.body());
            HttpResponse<String> notZip = send(c, "POST", "/apps", "not a zip".getBytes(StandardCharsets.UTF_8), ADMIN);
            assertEquals(422, notZip.statusCode(), notZip.body());
            // the Platform range (a test server's version reads "dev", which admits any range, so the rule is checked directly)
            assertTrue(InstalledApps.satisfies("4.1.0-SNAPSHOT", ">=4.0 <5"));
            assertFalse(InstalledApps.satisfies("5.0.0", ">=4.0 <5"));
            assertFalse(InstalledApps.satisfies("3.9.9", ">=4.0"));
            assertTrue(InstalledApps.satisfies("dev", ">=99"));
            HttpResponse<String> wrongId = send(c, "PUT", "/apps/other-app", good("0.1.0", false), ADMIN);
            assertTrue(wrongId.statusCode() == 422 && wrongId.body().contains("not 'other-app'"), wrongId.body());
            // a name Windows would rewrite onto the server's own marker, and two names one file apart in case
            Map<String, String> forged = appFiles("0.1.0", false);
            forged.put("templates/acme-spend/.installed-by.", "app=evil\n");
            HttpResponse<String> marker = send(c, "POST", "/apps", pkg("acme-spend", forged, true, "acme-signing-1", null), ADMIN);
            assertTrue(marker.statusCode() == 422 && marker.body().contains("ends with a dot"), marker.body());
            Map<String, String> cased = appFiles("0.1.0", false);
            cased.put("DOCS/index.md", "x");
            cased.put("docs/INDEX.md", "y");
            HttpResponse<String> casing = send(c, "POST", "/apps", pkg("acme-spend", cased, true, "acme-signing-1", null), ADMIN);
            assertTrue(casing.statusCode() == 422 && casing.body().contains("differ only in case"), casing.body());
            assertFalse(Files.exists(tmp.resolve("apps").resolve("acme-spend")), "nothing refused was written");
        }
    }

    @Test
    void conflictsAre409AndAnUnknownAppIs404() throws Exception {
        try (Ctx c = open()) {
            assertEquals(200, send(c, "POST", "/apps", good("0.1.0", false), ADMIN).statusCode());
            HttpResponse<String> again = send(c, "POST", "/apps", good("0.1.0", false), ADMIN);
            assertTrue(again.statusCode() == 409 && again.body().contains("installed already"), again.body());
            Map<String, String> clash = appFiles("0.1.0", false);
            clash.put("app.toon", clash.get("app.toon").replace("id: acme-spend", "id: acme-clash").replace("templates[1]: acme-spend", "templates[1]: shipped-tpl"));
            clash.put("templates/shipped-tpl/template.toon", "name: \"Clash\"\n");
            HttpResponse<String> collides = send(c, "POST", "/apps", pkg("acme-clash", clash, true, "acme-signing-1", null), ADMIN);
            assertTrue(collides.statusCode() == 409 && collides.body().contains("a template this build ships"), collides.body());
            HttpResponse<String> notInstalled = send(c, "PUT", "/apps/acme-new", pkg("acme-new", Map.of("app.toon", "id: acme-new\nversion: 0.1.0\n"), true, "acme-signing-1", null), ADMIN);
            assertTrue(notInstalled.statusCode() == 409 && notInstalled.body().contains("is not installed"), notInstalled.body());
            assertEquals(404, send(c, "DELETE", "/apps/no-such-app", null, ADMIN).statusCode());
        }
    }

    @Test
    void aDomainProductOrAServerWithoutADescriptorIs409() throws Exception {
        descriptor("domain");
        try (Ctx c = open()) {
            HttpResponse<String> domain = send(c, "POST", "/apps", good("0.1.0", false), ADMIN);
            assertTrue(domain.statusCode() == 409 && domain.body().contains("Domain product"), domain.body());
        }
        System.clearProperty(ProductDescriptor.PROPERTY);
        try (Ctx c = open()) {
            HttpResponse<String> none = send(c, "POST", "/apps", good("0.1.0", false), ADMIN);
            assertTrue(none.statusCode() == 409 && none.body().contains("not built as a Product"), none.body());
        }
    }
}
