package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Space admission over real HTTP ({@code LA-FULL-CYCLE-TEST-1} finding 32, decision D5; the rule is
 * {@link SpaceAdmission}), armed: a Space a signed-in person creates is closed. 401 without a credential; 200 for its
 * creator, for a holder of {@code canAdminister} and for a holder of a role the Space's own role table defines; 404 - the
 * unknown-Space answer - for everyone else, on its Datasets, arrivals, inbox and settings, and it is not listed to them.
 * The closure is not a capability (403 stays 403), survives a rename, a restart and an import, and reaches an un-prefixed
 * request. A Space with no {@code admission} stays open; with no sign-in nothing is recorded and everything reads. The
 * inbox's place on the server's disk is shown only to a holder of {@code canAdminister} ({@link ServerPaths}).
 * Synthetic fixtures only.
 */
class ControlApiSpaceAdmissionTest {

    private static final String CREATOR = "Bearer creator", ADMIN = "Bearer admin", MEMBER = "Bearer member",
            OUTSIDER = "Bearer outsider", DRIFTER = "Bearer drifter";
    /** What the finding read in the new Space, and the settings beside it; {@code transfers} is its import Dataset. */
    private static final List<String> READS = List.of("/components/dataset", "/components/dataset/transfers",
            "/datasets/transfers/rows", "/datasets/transfers/arrivals", "/datasets/transfers/feed", "/datasets/transfers/storage",
            "/pipelines/authored/transfers/inbox", "/settings/data", "/access/roles");

    private final HttpClient client = HttpClient.newHttpClient();
    /** Switched off once the Space exists, so its creator is admitted by being its creator and by nothing else. */
    private final AtomicBoolean creatorAdministers = new AtomicBoolean(true);

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    /**
     * creator: administers until the test says otherwise, then only uploads. admin: another administrator. member:
     * holds the role {@code claims-team}, which no built-in table defines. outsider: holds the built-in
     * {@code operations} role and its upload and run grants - what a Demo User of another Space's roster resolves to.
     * drifter: an Authenticator that publishes the role {@code claims-team} everywhere except in the Space {@code bank},
     * where it publishes nothing at all.
     */
    @BeforeEach
    void arm() {
        creatorAdministers.set(true);
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case CREATOR -> Optional.of(new Subject("creator-1", creatorAdministers.get()
                    ? Set.of(Roles.CAN_ADMINISTER, Roles.CAN_UPLOAD_DATA) : Set.of(Roles.CAN_UPLOAD_DATA)));
            case ADMIN -> Optional.of(new Subject("admin-2", Set.of(Roles.CAN_ADMINISTER, Roles.CAN_CONFIGURE_ACCESS, Roles.CAN_UPLOAD_DATA)));
            case MEMBER -> {
                ComponentAccess.heldRoles(ex, Set.of("claims-team"));
                yield Optional.of(new Subject("member-1", Set.of(Roles.CAN_UPLOAD_DATA)));
            }
            case OUTSIDER -> {
                ComponentAccess.heldRoles(ex, Set.of("operations"));
                yield Optional.of(new Subject("outsider-1", Set.of(Roles.CAN_UPLOAD_DATA, Roles.CAN_OPERATE_RUNS)));
            }
            case DRIFTER -> {
                if (!String.valueOf(Roles.configRoot(ex)).replace('\\', '/').endsWith("bank/config"))
                    ComponentAccess.heldRoles(ex, Set.of("claims-team"));
                yield Optional.of(new Subject("drifter-1", Set.of()));
            }
            default -> Optional.empty();
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        return client.send(b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofByteArray(body)).build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> json(Ctx c, String method, String path, String body, String auth) throws Exception {
        return send(c, method, path, body == null ? null : body.getBytes(StandardCharsets.UTF_8), auth);
    }

    private HttpResponse<String> get(Ctx c, String path, String auth) throws Exception {
        return json(c, "GET", path, null, auth);
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    /** An error answer without its per-request correlation id. */
    private static String error(HttpResponse<String> r) {
        return r.body().replaceAll("\"correlationId\":\"[^\"]*\"", "");
    }

    private static List<String> ids(JsonNode spaces) {
        List<String> out = new java.util.ArrayList<>();
        spaces.forEach(s -> out.add(s.get("id").asText()));
        return out;
    }

    /** Turn Add data on in the Space {@code id} and import forty synthetic transfers as the Dataset {@code transfers}. */
    private void addData(Ctx c, String id, String auth) throws Exception {
        data(json(c, "PUT", "/spaces/" + id + "/settings/data", "{\"selfImport\":true}", auth), 200);
        JsonNode draft = data(send(c, "POST", "/spaces/" + id + "/onboard/files?name=transfers.csv&kind=stream",
                ControlApiOnboardFilesTest.transfers(40).getBytes(StandardCharsets.UTF_8), auth), 200);
        data(json(c, "POST", "/spaces/" + id + "/onboard/files/" + draft.get("draftId").asText() + "/commit",
                "{\"name\":\"transfers\"}", auth), 200);
    }

    // ── the closed default: 401 / 404 / 403 / 200 ───────────────────────────────────────────────────────────

    @Test
    void aSpaceSomeoneSignedInCreatesAdmitsItsCreatorAndAdministratorsOnly(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("older").resolve("config"));   // a Space that was there before: no admission
        try (Ctx c = open(root)) {
            JsonNode made = data(json(c, "POST", "/spaces", "{\"id\":\"bank\"}", CREATOR), 200);
            assertEquals("closed", made.path("admission").asText(), made.toString());
            assertEquals("creator-1", made.path("createdBy").asText(), made.toString());
            String manifest = Files.readString(root.resolve("bank").resolve("space.toon"));
            assertTrue(manifest.contains("created_by: creator-1") && manifest.contains("admission: closed"), manifest);
            addData(c, "bank", CREATOR);
            creatorAdministers.set(false);   // from here the creator holds canUploadData only

            String unknown = error(get(c, "/spaces/bank2/components/dataset", OUTSIDER)).replace("bank2", "bank");
            assertTrue(unknown.contains("NOT_FOUND") && unknown.contains("no such space 'bank'"), unknown);
            for (String read : READS) {
                assertEquals(401, get(c, "/spaces/bank" + read, null).statusCode(), "no credential: " + read);
                assertEquals(200, get(c, "/spaces/bank" + read, CREATOR).statusCode(), "its creator: " + read);
                assertEquals(200, get(c, "/spaces/bank" + read, ADMIN).statusCode(), "an administrator: " + read);
                for (String refused : List.of(OUTSIDER, MEMBER)) {
                    HttpResponse<String> r = get(c, "/spaces/bank" + read, refused);
                    assertEquals(404, r.statusCode(), refused + " " + read + ": " + r.body());
                    assertEquals(unknown, error(r), "the answer an unknown Space id gets, word for word: " + read);
                }
            }
            assertTrue(get(c, "/spaces/bank/datasets/transfers/rows", CREATOR).body().contains("EUR"), "the creator reads the rows");
            // a refused caller reaches no handler: nothing runs, nothing is written
            assertEquals(404, json(c, "POST", "/spaces/bank/datasets/transfers/run", null, OUTSIDER).statusCode());
            assertEquals(404, get(c, "/spaces/bank/bootstrap", OUTSIDER).statusCode(), "not even the Space's bootstrap");
            // each refusal is recorded in the closed Space's own audit log, under the person refused
            boolean recorded = c.spaces.space(com.gamma.service.SpaceId.of("bank")).orElseThrow().service().events()
                    .page(200, null, null).stream().map(com.gamma.audit.Event::toMap).map(Object::toString)
                    .anyMatch(e -> e.contains("ACCESS_DENIED") && e.contains("outsider-1")
                            && e.contains("/datasets/transfers/arrivals") && e.contains("404"));
            assertTrue(recorded, "the refused read of the arrivals is an ACCESS_DENIED (404) event of the Space");
            // admission is not a capability: the admitted creator still needs canAdminister for the Space's switch
            assertEquals(403, json(c, "PUT", "/spaces/bank/settings/data", "{\"selfImport\":false}", CREATOR).statusCode());

            // listed only to the people it admits; the Space that was there before is listed to everyone
            assertEquals(List.of("bank", "older"), ids(data(get(c, "/spaces", CREATOR), 200)));
            assertEquals(List.of("bank", "older"), ids(data(get(c, "/spaces", ADMIN), 200)));
            for (String refused : List.of(OUTSIDER, MEMBER)) {
                assertEquals(List.of("older"), ids(data(get(c, "/spaces", refused), 200)));
                assertEquals(List.of("older"), ids(data(get(c, "/bootstrap", refused), 200).get("spaces")));
                assertEquals(200, get(c, "/spaces/older/components/dataset", refused).statusCode(), "a Space with no admission is open");
            }
            assertEquals(List.of("bank", "older"), ids(data(get(c, "/bootstrap", ADMIN), 200).get("spaces")));

            // the inbox: where it is on the server's disk is shown to an administrator only
            String absolute = root.resolve("bank").resolve("data").resolve("inbox").resolve("transfers").toAbsolutePath().normalize().toString();
            for (String route : List.of("/spaces/bank/datasets/transfers/feed", "/spaces/bank/pipelines/authored/transfers/inbox")) {
                assertEquals(absolute, data(get(c, route, ADMIN), 200).get("inbox").asText(), route);
                HttpResponse<String> shown = get(c, route, CREATOR);
                assertEquals("inbox/transfers", data(shown, 200).get("inbox").asText(), route);
                assertFalse(shown.body().replace("\\\\", "/").replace('\\', '/').contains(root.toString().replace('\\', '/')),
                        "no server path reaches someone who does not administer: " + shown.body());
            }
        }
    }

    // ── admitting others: a role the Space itself defines ───────────────────────────────────────────────────

    @Test
    void aRoleTheSpacesOwnRoleTableDefinesAdmitsItsHolders(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            data(json(c, "POST", "/spaces", "{\"id\":\"bank\"}", CREATOR), 200);
            assertEquals(404, get(c, "/spaces/bank/settings/data", MEMBER).statusCode(), "held, but the Space does not define it yet");

            data(json(c, "PUT", "/spaces/bank/access/roles",
                    "{\"roles\":[{\"name\":\"claims-team\",\"capabilities\":[\"canUploadData\"]}]}", ADMIN), 200);
            assertEquals(200, get(c, "/spaces/bank/settings/data", MEMBER).statusCode(), "the Space now defines the role");
            assertEquals(List.of("bank"), ids(data(get(c, "/spaces", MEMBER), 200)));
            // a role only the built-in table defines admits nobody, whatever it grants
            assertEquals(404, get(c, "/spaces/bank/settings/data", OUTSIDER).statusCode());
            assertEquals(List.of(), ids(data(get(c, "/spaces", OUTSIDER), 200)));

            data(json(c, "PUT", "/spaces/bank/access/roles", "{\"roles\":[]}", ADMIN), 200);
            assertEquals(404, get(c, "/spaces/bank/settings/data", MEMBER).statusCode(), "the role is no longer defined here");
        }
    }

    @Test
    void aRoleHeldInTheSpaceARequestRunsInIsNotCarriedIntoAnotherSpacesListRow(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("aaa").resolve("config"));   // open, and what an un-prefixed request runs in
        try (Ctx c = open(root)) {
            data(json(c, "POST", "/spaces", "{\"id\":\"bank\"}", CREATOR), 200);
            data(json(c, "PUT", "/spaces/bank/access/roles",
                    "{\"roles\":[{\"name\":\"claims-team\",\"capabilities\":[]}]}", ADMIN), 200);
            assertEquals(List.of("aaa", "bank"), ids(data(get(c, "/spaces", MEMBER), 200)), "the role is held in bank: listed");
            // held in aaa, where GET /spaces runs, but not in bank: the list row is judged by bank alone
            assertEquals(404, get(c, "/spaces/bank/settings/data", DRIFTER).statusCode());
            assertEquals(List.of("aaa"), ids(data(get(c, "/spaces", DRIFTER), 200)));
            assertEquals(List.of("aaa"), ids(data(get(c, "/bootstrap", DRIFTER), 200).get("spaces")));
        }
    }

    @Test
    void aSpaceMadeFromATemplateIsClosedAndAdmitsTheRolesTheTemplateDefines(@TempDir Path root) throws Exception {
        Path tpl = root.resolve("_templates").resolve("claims");
        Files.createDirectories(tpl.resolve("config"));
        Files.writeString(tpl.resolve("template.toon"), """
                name: Claims
                tagline: "A claims desk"
                description: "A role table and nothing else."
                icon: heroicons_outline:cube
                contents[1]: "Roles"
                """);
        Files.writeString(tpl.resolve("config").resolve("roles.toon"), """
                roles[1]:
                  - name: claims-team
                    capabilities[1]: canUploadData
                """);
        try (Ctx c = open(root)) {
            JsonNode made = data(json(c, "POST", "/spaces", "{\"id\":\"desk\",\"template\":\"claims\"}", CREATOR), 200);
            assertEquals("closed", made.path("admission").asText(), made.toString());
            assertEquals("creator-1", made.path("createdBy").asText(), made.toString());
            assertEquals(200, get(c, "/spaces/desk/settings/data", MEMBER).statusCode(),
                    "the role table the template brought is the Space's own: the roles it defines are admitted");
            assertEquals(404, get(c, "/spaces/desk/settings/data", OUTSIDER).statusCode());
        }
    }

    // ── the closure is durable ──────────────────────────────────────────────────────────────────────────────

    @Test
    void aClosedSpaceStaysClosedThroughARenameAndARestart(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            data(json(c, "POST", "/spaces", "{\"id\":\"bank\"}", CREATOR), 200);
            JsonNode renamed = data(json(c, "PUT", "/spaces/bank", "{\"display_name\":\"Bank transfers\"}", ADMIN), 200);
            assertEquals("Bank transfers", renamed.get("displayName").asText());
            assertEquals("closed", renamed.path("admission").asText(), "a rename is not an opening: " + renamed);
            assertEquals(404, get(c, "/spaces/bank/settings/data", OUTSIDER).statusCode());
        }
        creatorAdministers.set(false);
        try (Ctx c = open(root)) {   // the same folders, a new server
            assertEquals(404, get(c, "/spaces/bank/settings/data", OUTSIDER).statusCode(), "closed after a restart");
            assertEquals(200, get(c, "/spaces/bank/settings/data", CREATOR).statusCode(), "and its creator is still its creator");
            assertEquals(List.of(), ids(data(get(c, "/spaces", OUTSIDER), 200)));
        }
    }

    @Test
    void anImportedSpaceIsClosedForWhoeverImportsItNotForWhoTheBundleNames(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            data(json(c, "POST", "/spaces", "{\"id\":\"bank\"}", CREATOR), 200);
            HttpResponse<byte[]> bundle = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                    + "/api/v1/spaces/bank/export")).header("Authorization", CREATOR).GET().build(), BodyHandlers.ofByteArray());
            assertEquals(200, bundle.statusCode());

            JsonNode copy = data(send(c, "POST", "/spaces/import?id=copy", bundle.body(), ADMIN), 200);
            assertEquals("closed", copy.path("admission").asText(), copy.toString());
            assertEquals("admin-2", copy.path("createdBy").asText(), "the importer, not the bundle's creator-1: " + copy);
            creatorAdministers.set(false);
            assertEquals(404, get(c, "/spaces/copy/settings/data", CREATOR).statusCode(),
                    "the exporting Space's creator is nobody in the imported one");
            assertEquals(404, get(c, "/spaces/copy/settings/data", OUTSIDER).statusCode());
            assertEquals(200, get(c, "/spaces/copy/settings/data", ADMIN).statusCode());
        }
    }

    // ── an un-prefixed request ──────────────────────────────────────────────────────────────────────────────

    @Test
    void anUnprefixedRequestDoesNotReadAClosedSpaceItFallsBackTo(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("zzz").resolve("config"));
        try (Ctx c = open(root)) {
            data(json(c, "POST", "/spaces", "{\"id\":\"a-first\"}", CREATOR), 200);   // the smallest id: what an un-prefixed request runs in
            addData(c, "a-first", CREATOR);
            creatorAdministers.set(false);
            assertEquals(200, get(c, "/datasets/transfers/arrivals", CREATOR).statusCode(), "un-prefixed is the closed Space a-first");

            HttpResponse<String> refused = get(c, "/datasets/transfers/arrivals", OUTSIDER);
            assertEquals(404, refused.statusCode(), refused.body());
            assertFalse(error(refused).contains("a-first"), "and the answer does not name it: " + refused.body());
            assertEquals(404, get(c, "/components/dataset", OUTSIDER).statusCode());
            // the sign-in path and the installation-scope routes go on, each deciding per Space itself
            assertEquals(List.of("zzz"), ids(data(get(c, "/spaces", OUTSIDER), 200)));
            assertEquals(List.of("zzz"), ids(data(get(c, "/bootstrap", OUTSIDER), 200).get("spaces")));
            assertEquals(200, get(c, "/spaces/_meta", OUTSIDER).statusCode());
            assertEquals(200, get(c, "/spaces/zzz/components/dataset", OUTSIDER).statusCode());
        }
    }

    // ── what is NOT closed ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void aSpaceOnDiskIsOpenUnlessItsManifestSaysClosedOrDoesNotRead(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("plain").resolve("config"));   // no space.toon at all
        Files.createDirectories(root.resolve("named").resolve("config"));
        Files.writeString(root.resolve("named").resolve("space.toon"), "display_name: Named\ncreated_at: 2026-01-01T00:00:00Z\n");
        Files.createDirectories(root.resolve("sealed").resolve("config"));
        Files.writeString(root.resolve("sealed").resolve("space.toon"), "display_name: Sealed\nadmission: closed\ncreated_by: someone-else\n");
        Files.createDirectories(root.resolve("torn").resolve("config"));
        Files.writeString(root.resolve("torn").resolve("space.toon"), "display_name: [\n\tadmission\n");
        try (Ctx c = open(root)) {
            assertEquals(List.of("named", "plain"), ids(data(get(c, "/spaces", OUTSIDER), 200)));
            for (String open : List.of("plain", "named"))
                assertEquals(200, get(c, "/spaces/" + open + "/settings/data", OUTSIDER).statusCode(), open);
            for (String closed : List.of("sealed", "torn")) {
                assertEquals(404, get(c, "/spaces/" + closed + "/settings/data", OUTSIDER).statusCode(), closed);
                assertEquals(200, get(c, "/spaces/" + closed + "/settings/data", ADMIN).statusCode(), closed);
            }
        }
    }

    @Test
    void withNoSignInNothingIsRecordedAndEverythingReads(@TempDir Path root) throws Exception {
        Authenticators.forTest(null);   // no Authenticator on this class path: nobody signs in
        try (Ctx c = open(root)) {
            JsonNode made = data(json(c, "POST", "/spaces", "{\"id\":\"bank\"}", null), 200);
            assertFalse(made.has("admission") || made.has("createdBy"), made.toString());
            String manifest = Files.readString(root.resolve("bank").resolve("space.toon"));
            assertFalse(manifest.contains("created_by") || manifest.contains("admission"), manifest);
            addData(c, "bank", null);
            for (String read : READS) assertEquals(200, get(c, "/spaces/bank" + read, null).statusCode(), read);
            assertEquals(List.of("bank"), ids(data(get(c, "/spaces", null), 200)));
            String absolute = root.resolve("bank").resolve("data").resolve("inbox").resolve("transfers").toAbsolutePath().normalize().toString();
            assertEquals(absolute, data(get(c, "/spaces/bank/datasets/transfers/feed", null), 200).get("inbox").asText(),
                    "with no sign-in the inbox path is shown as before");
            assertEquals(absolute, data(get(c, "/spaces/bank/pipelines/authored/transfers/inbox", null), 200).get("inbox").asText());
        }
    }
}
