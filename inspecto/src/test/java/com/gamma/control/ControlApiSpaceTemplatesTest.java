package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shipped space-template gallery over real HTTP: {@code GET /spaces/templates} lists
 * {@code _templates/<id>/template.toon} entries (empty when none ship), and {@code POST /spaces} with a
 * {@code template} seeds the new space from the template's config tree with every {@code ${SPACE}} token
 * rewritten to the new id — the booted space discovers the copied pipeline like any authored one.
 */
class ControlApiSpaceTemplatesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    /** A minimal on-disk template: metadata + one ${SPACE}-addressed pipeline + a registry dataset. */
    private void seedTemplate(Path root) throws Exception {
        Path tpl = root.resolve("_templates").resolve("starter");
        Files.createDirectories(tpl.resolve("config").resolve("orders"));
        Files.createDirectories(tpl.resolve("config").resolve("registry").resolve("datasets"));
        Files.writeString(tpl.resolve("template.toon"), """
                name: Starter
                tagline: "A tiny starter feed"
                description: "One pipeline + one dataset."
                icon: heroicons_outline:cube
                contents[2]: "Pipeline", "Dataset"
                """);
        Files.writeString(tpl.resolve("config").resolve("orders").resolve("orders_pipeline.toon"), """
                name: orders
                active: false
                dirs:
                  poll:       data/inbox/${SPACE}/orders
                  database:   data/orders/database
                  backup:     data/orders/backup
                  temp:       data/orders/temp
                  errors:     data/orders/errors
                  quarantine: data/orders/quarantine
                  markers:    data/orders/markers
                  status_dir: data/orders/status
                  log_dir:    data/orders/logs
                output:
                  format: PARQUET
                  compression: snappy
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  csv_settings:
                    delimiter: ","
                """);
        Files.writeString(tpl.resolve("config").resolve("registry").resolve("datasets").resolve("orders_dataset.toon"),
                "physicalRef: orders/database\n");
    }

    @Test
    void galleryListsShippedTemplatesAndIsEmptyWithoutAny(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            JsonNode empty = json(send(c.port, "GET", "/spaces/templates", null));
            assertTrue(empty.isArray() && empty.isEmpty(), "no _templates dir -> empty gallery");
        }
        seedTemplate(root);
        try (Ctx c = open(root)) {
            JsonNode list = json(send(c.port, "GET", "/spaces/templates", null));
            assertEquals(1, list.size());
            JsonNode t = list.get(0);
            assertEquals("starter", t.get("id").asText());
            assertEquals("Starter", t.get("name").asText());
            assertEquals("A tiny starter feed", t.get("tagline").asText());
            assertEquals(2, t.get("contents").size());
        }
    }

    /**
     * A Basic build (no {@code alert.dispatch}) lists a template that ships Alert Rules as NOT creatable, naming
     * the missing feature — the same verdict {@code POST /spaces} answers with its 422 — so the gallery never
     * offers a create that is certain to fail. With the feature present the same template is creatable.
     */
    @Test
    void galleryMarksATemplateThisEditionCannotCreate(@TempDir Path root) throws Exception {
        seedTemplate(root);
        Path rules = root.resolve("_templates/starter/config/registry/alert-rules");
        Files.createDirectories(rules);
        Files.writeString(rules.resolve("low_volume.toon"), """
                name: low_volume
                dataset: orders_dataset
                measure: count
                comparator: lt
                threshold: 10
                """);
        try {
            com.gamma.etl.EditionFeatures.overrideForTest(java.util.Set.of());   // Basic
            try (Ctx c = open(root)) {
                JsonNode t = json(send(c.port, "GET", "/spaces/templates", null)).get(0);
                assertFalse(t.get("creatable").asBoolean(), t.toString());
                assertEquals("alert.dispatch", t.get("missingFeatures").get(0).get("feature").asText(), t.toString());
                assertTrue(t.get("missingFeatures").get(0).get("message").asText().contains("Standard+"));
                HttpResponse<String> refused = send(c.port, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"starter\"}");
                assertEquals(422, refused.statusCode(), "the create the gallery marked uncreatable is refused: " + refused.body());
            }
            com.gamma.etl.EditionFeatures.overrideForTest(java.util.Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
            try (Ctx c = open(root)) {
                JsonNode t = json(send(c.port, "GET", "/spaces/templates", null)).get(0);
                assertTrue(t.get("creatable").asBoolean(), t.toString());
                assertEquals(0, t.get("missingFeatures").size());
            }
        } finally {
            com.gamma.etl.EditionFeatures.overrideForTest(null);
        }
    }

    /**
     * Plan wave B step 5 (operator 2026-11-08, "A: allow, unarmed"): on a Basic build (no {@code alert.dispatch}) a template
     * whose {@code minEdition} is {@code basic} ships its Alert Rules unarmed - the gallery lists it creatable and
     * {@code POST /spaces} creates it with the rule file in place (the boot leaves it unarmed with a WARN) - while the SAME
     * template without {@code minEdition: basic} is still marked uncreatable and refused with a 422. A Basic-tier rule is
     * still judged by its content: a malformed one refuses the create. Falsified: without the minEdition check in
     * {@code SpaceManager.missingFeatures} the basic one is {@code creatable: false}; without it in {@code TemplateSeedGate}
     * its create answers 422.
     */
    @Test
    void aBasicTierTemplateShipsItsAlertRulesUnarmedOnBasic(@TempDir Path root) throws Exception {
        seedTemplate(root);
        Path starter = root.resolve("_templates").resolve("starter");
        String rule = """
                name: low_volume
                dataset: orders_dataset
                measure: count
                comparator: lt
                threshold: 10
                """;
        for (String edition : java.util.List.of("basic", "advanced", "badrule")) {
            Path tpl = root.resolve("_templates").resolve("tier-" + edition);
            try (java.util.stream.Stream<Path> walk = Files.walk(starter)) {
                for (Path p : walk.sorted().toList()) {
                    Path t = tpl.resolve(starter.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(t);
                    else Files.copy(p, t);
                }
            }
            Files.writeString(tpl.resolve("template.toon"), Files.readString(tpl.resolve("template.toon"))
                    + "minEdition: " + ("advanced".equals(edition) ? "advanced" : "basic") + "\n");
            Path rules = Files.createDirectories(tpl.resolve("config/registry/alert-rules"));
            Files.writeString(rules.resolve("low_volume.toon"), "badrule".equals(edition) ? rule + "colour: red\n" : rule);   // a key no Alert Rule models
        }
        try {
            com.gamma.etl.EditionFeatures.overrideForTest(java.util.Set.of());   // Basic
            try (Ctx c = open(root)) {
                JsonNode all = json(send(c.port, "GET", "/spaces/templates", null));
                JsonNode basic = find(all, "tier-basic"), advanced = find(all, "tier-advanced");
                assertTrue(basic.get("creatable").asBoolean(), basic.toString());
                assertEquals(0, basic.get("missingFeatures").size(), basic.toString());
                assertFalse(advanced.get("creatable").asBoolean(), advanced.toString());
                assertEquals("alert.dispatch", advanced.get("missingFeatures").get(0).get("feature").asText(), advanced.toString());

                HttpResponse<String> made = send(c.port, "POST", "/spaces", "{\"id\":\"tiered\",\"template\":\"tier-basic\"}");
                assertTrue(made.statusCode() / 100 == 2, "a Basic-tier template's rules ship unarmed: " + made.body());
                assertTrue(Files.isRegularFile(root.resolve("tiered/config/registry/alert-rules/low_volume.toon")),
                        "the rule ships in the Space, unarmed");
                HttpResponse<String> refused = send(c.port, "POST", "/spaces", "{\"id\":\"plain\",\"template\":\"tier-advanced\"}");
                assertEquals(422, refused.statusCode(), "without a Basic tier the rule still refuses the create: " + refused.body());
                HttpResponse<String> bad = send(c.port, "POST", "/spaces", "{\"id\":\"badly\",\"template\":\"tier-badrule\"}");
                assertEquals(422, bad.statusCode(), "a Basic-tier rule is still judged by its content: " + bad.body());
                assertFalse(Files.exists(root.resolve("plain")) || Files.exists(root.resolve("badly")), "a refused create leaves nothing");
            }
        } finally {
            com.gamma.etl.EditionFeatures.overrideForTest(null);
        }
    }

    /**
     * The same rule ("A: allow, unarmed") for a DEFERRED Alert Rule ({@code pending/alert-rules/}) and its limit for a Decision
     * Rule. On a Basic build the shipped {@code payment-fraud} template, its registry rules removed so only the pending one
     * is left, is creatable from the gallery and through {@code POST /spaces} (the pending rule ships) when it says
     * {@code minEdition: basic}, and refused by both while it says {@code standard}. A Basic-tier template whose Decision Rule
     * has a {@code create-alert} consequence is still marked uncreatable for {@code alert.dispatch}: that is not an Alert Rule
     * the boot can leave unarmed. Falsified: without the minEdition check in {@code TemplateSeedGate.requirePendingAlertRules}
     * the basic create answers 422; without it in {@code SpaceManager.missingFeatures} the basic entry is uncreatable.
     */
    @Test
    void aBasicTierTemplateShipsItsPendingAlertRulesUnarmedButNotACreateAlertDecisionRule(@TempDir Path root) throws Exception {
        seedTemplate(root);
        Path src = Path.of("..", "spaces", "_templates", "payment-fraud");
        for (String edition : java.util.List.of("basic", "standard")) {
            Path tpl = root.resolve("_templates").resolve("pf-" + edition);
            try (java.util.stream.Stream<Path> walk = Files.walk(src)) {
                for (Path p : walk.sorted().toList()) {
                    Path t = tpl.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(t);
                    else Files.copy(p, t);
                }
            }
            try (java.util.stream.Stream<Path> s = Files.list(tpl.resolve("config/registry/alert-rules"))) {
                for (Path f : s.toList()) Files.delete(f);   // only the pending rule is left
            }
            Path meta = tpl.resolve("template.toon");
            Files.writeString(meta, Files.readString(meta).replace("minEdition: standard", "minEdition: " + edition));
            assertTrue(Files.isRegularFile(tpl.resolve("config").resolve(PendingAlertRules.DIR).resolve("pf_high_risk_account.toon")));
        }
        Path dr = root.resolve("_templates").resolve("dr-basic");
        Files.createDirectories(dr.resolve("config/registry/decision-rules"));
        Files.writeString(dr.resolve("template.toon"), "name: Decides\nminEdition: basic\n");
        Files.writeString(dr.resolve("config/registry/decision-rules/breach.toon"), com.gamma.config.io.ConfigCodec.toToon(
                java.util.Map.of("name", "breach", "enabled", true, "consequences", java.util.List.of(java.util.Map.of(
                        "action", "create-alert", "params", java.util.Map.of("rule", "r3", "metric", "error_rate",
                                "comparator", "gt", "threshold", 0.1, "window", "1h"))))));
        try {
            com.gamma.etl.EditionFeatures.overrideForTest(java.util.Set.of());   // Basic
            try (Ctx c = open(root)) {
                JsonNode all = json(send(c.port, "GET", "/spaces/templates", null));
                JsonNode basic = find(all, "pf-basic"), standard = find(all, "pf-standard"), decides = find(all, "dr-basic");
                for (JsonNode m : basic.get("missingFeatures"))
                    assertFalse("alert.dispatch".equals(m.get("feature").asText()), basic.toString());
                boolean refused = false;
                for (JsonNode m : standard.get("missingFeatures")) refused |= "alert.dispatch".equals(m.get("feature").asText());
                assertTrue(refused && !standard.get("creatable").asBoolean(), standard.toString());
                assertFalse(decides.get("creatable").asBoolean(), decides.toString());
                assertEquals("alert.dispatch", decides.get("missingFeatures").get(0).get("feature").asText(), decides.toString());

                HttpResponse<String> made = send(c.port, "POST", "/spaces", "{\"id\":\"pfb\",\"template\":\"pf-basic\"}");
                assertTrue(made.statusCode() / 100 == 2, "a Basic-tier template's pending rule ships unarmed: " + made.body());
                assertTrue(Files.isRegularFile(root.resolve("pfb/config").resolve(PendingAlertRules.DIR).resolve("pf_high_risk_account.toon")));
                HttpResponse<String> refusedCreate = send(c.port, "POST", "/spaces", "{\"id\":\"pfs\",\"template\":\"pf-standard\"}");
                assertEquals(422, refusedCreate.statusCode(), refusedCreate.body());
                assertTrue(refusedCreate.body().contains("pending alert-rule 'pf_high_risk_account' is refused"), refusedCreate.body());
                assertFalse(Files.exists(root.resolve("pfs")), "a refused create leaves nothing");
            }
        } finally {
            com.gamma.etl.EditionFeatures.overrideForTest(null);
        }
    }

    /**
     * Plan wave B step 5 (operator 2026-11-08, "allow on Basic"): with no Demo User sign-in installed, the SAME roster refuses a
     * template whose {@code minEdition} is {@code advanced} (or unstated) and does not refuse one whose {@code minEdition} is
     * {@code basic}, whose roster is its inert Advanced tier. Falsified: without the minEdition check the basic one is
     * {@code creatable: false} naming {@code demo-auth}.
     */
    @Test
    void aBasicTierTemplateKeepsItsDemoUserRosterAsAnInertAdvancedTier(@TempDir Path root) throws Exception {
        seedTemplate(root);
        Path starter = root.resolve("_templates").resolve("starter");
        for (String edition : java.util.List.of("basic", "advanced")) {
            Path tpl = root.resolve("_templates").resolve("tier-" + edition);
            try (java.util.stream.Stream<Path> walk = Files.walk(starter)) {
                for (Path p : walk.sorted().toList()) {
                    Path t = tpl.resolve(starter.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(t);
                    else Files.copy(p, t);
                }
            }
            Files.writeString(tpl.resolve("template.toon"), Files.readString(tpl.resolve("template.toon")) + "minEdition: " + edition + "\n");
            Files.writeString(tpl.resolve("config/demo-users.toon"), "users[0]:\n");
        }
        try (Ctx c = open(root)) {
            JsonNode all = json(send(c.port, "GET", "/spaces/templates", null));
            JsonNode basic = find(all, "tier-basic"), advanced = find(all, "tier-advanced");
            assertTrue(basic.get("creatable").asBoolean(), basic.toString());
            assertEquals(0, basic.get("missingFeatures").size(), basic.toString());
            assertFalse(advanced.get("creatable").asBoolean(), advanced.toString());
            assertEquals("demo-auth", advanced.get("missingFeatures").get(0).get("feature").asText(), advanced.toString());
        }
    }

    /**
     * LA-SAMPLE-GALLERY-DEMOAUTH-1: a template whose people sign in as Demo Users ({@code config/demo-users.toon}) is NOT
     * creatable on a server without that sign-in (Basic, or OIDC, which cannot name its principals), naming
     * {@code demo-auth}; the test's own {@code starter} is such a template and {@code la-sample} has no roster.
     * A template with a Basic tier ({@code minEdition: basic}, the showcase plan's Edition rule, operator 2026-11-08 "allow on
     * Basic") keeps its roster as an inert Advanced tier and stays creatable: the shipped {@code la-claims},
     * {@code la-mobile-money}, {@code la-audit}, {@code la-showcase}, {@code la-utility}, {@code la-card} and {@code la-procurement} (whose Alert Rules ship unarmed, "A: allow, unarmed"),
     * none of them needing Demo Auth. With an
     * Authenticator that names its principals from a Space's config (Demo Auth) the same template is creatable. Falsified:
     * without the roster check every one of them lists {@code creatable: true}.
     */
    @Test
    void galleryCountsTheDemoUserSignInATemplateNeeds(@TempDir Path root) throws Exception {
        seedTemplate(root);
        Files.writeString(root.resolve("_templates/starter/config/demo-users.toon"), "users[0]:\n");
        for (String shipped : java.util.List.of("la-audit", "la-card", "la-claims", "la-mobile-money", "la-police", "la-procurement", "la-sample", "la-showcase", "la-utility")) {
            Path src = Path.of("..", "spaces", "_templates", shipped);
            Path dst = root.resolve("_templates").resolve(shipped);
            Files.createDirectories(dst);
            Files.copy(src.resolve("template.toon"), dst.resolve("template.toon"));
            try (java.util.stream.Stream<Path> walk = Files.walk(src.resolve("config"))) {
                for (Path p : walk.sorted().toList()) {
                    Path t = dst.resolve("config").resolve(src.resolve("config").relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(t);
                    else Files.copy(p, t);
                }
            }
        }
        try {
            try (Ctx c = open(root)) {
                for (JsonNode t : json(send(c.port, "GET", "/spaces/templates", null))) {
                    // la-sample has no roster; the shipped showcases with a Basic tier keep theirs inert, so it does not refuse them
                    java.util.Set<String> basicTier = java.util.Set.of("la-claims", "la-mobile-money", "la-audit", "la-showcase", "la-police", "la-utility", "la-card", "la-procurement");
                    String id = t.get("id").asText();
                    boolean needsDemo = !"la-sample".equals(id) && !basicTier.contains(id);
                    boolean named = false;
                    for (JsonNode m : t.get("missingFeatures")) named |= "demo-auth".equals(m.get("feature").asText());
                    assertEquals(needsDemo, named, t.toString());
                    if (needsDemo) assertFalse(t.get("creatable").asBoolean(), t.toString());
                    if (basicTier.contains(id)) {
                        assertTrue(t.get("creatable").asBoolean(), t.toString());
                        assertEquals(0, t.get("missingFeatures").size(), t.toString());
                    }
                }
            }
            // OIDC: an Authenticator that cannot say who exists is not a Demo User sign-in
            com.gamma.spi.auth.Authenticators.forTest(ex -> java.util.Optional.of(
                    new com.gamma.spi.auth.Subject("oidc-user", java.util.Set.of("canAdminister"))));
            try (Ctx c = open(root)) {
                JsonNode starter = find(json(send(c.port, "GET", "/spaces/templates", null)), "starter");
                assertFalse(starter.get("creatable").asBoolean(), starter.toString());
            }
            com.gamma.spi.auth.Authenticators.forTest(new com.gamma.spi.auth.Authenticator() {
                @Override
                public java.util.Optional<com.gamma.spi.auth.Subject> authenticate(com.sun.net.httpserver.HttpExchange ex) {
                    return java.util.Optional.of(new com.gamma.spi.auth.Subject("demo", java.util.Set.of("canAdminister")));
                }

                @Override
                public java.util.Optional<java.util.Map<String, java.util.List<String>>> principals(Path configRoot) {
                    return java.util.Optional.of(java.util.Map.of());
                }
            });
            try (Ctx c = open(root)) {
                JsonNode starter = find(json(send(c.port, "GET", "/spaces/templates", null)), "starter");
                assertTrue(starter.get("creatable").asBoolean(), "a Demo User sign-in is installed: " + starter);
                assertEquals(0, starter.get("missingFeatures").size(), starter.toString());
            }
        } finally {
            com.gamma.spi.auth.Authenticators.forTest(null);
        }
    }

    private static JsonNode find(JsonNode list, String id) {
        for (JsonNode t : list) if (id.equals(t.get("id").asText())) return t;
        throw new AssertionError("no template " + id + " in " + list);
    }

    @Test
    void createFromTemplateRewritesSpaceTokensAndBoots(@TempDir Path root) throws Exception {
        seedTemplate(root);
        try (Ctx c = open(root)) {
            HttpResponse<String> created = send(c.port, "POST", "/spaces",
                    "{\"id\":\"acme\",\"template\":\"starter\"}");
            assertEquals(200, created.statusCode(), created.body());
            assertEquals("Starter", json(created).get("displayName").asText(), "display name defaults from the template");

            // config copied with ${SPACE} rewritten to the new id
            String pipeline = Files.readString(
                    root.resolve("acme").resolve("config").resolve("orders").resolve("orders_pipeline.toon"));
            assertTrue(pipeline.contains("data/inbox/acme/orders"), pipeline);
            assertFalse(pipeline.contains("${SPACE}"), "every token rewritten");
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve("registry")
                    .resolve("datasets").resolve("orders_dataset.toon")));

            // the seeded space boots and registers like any other (config-internal spaces/<id>/… paths
            // resolve against the server's CWD, so the copied pipeline itself only loads in a real
            // deployment layout — the live smoke covers that; here the space must list + scope cleanly)
            HttpResponse<String> spaces = send(c.port, "GET", "/spaces", null);
            assertTrue(spaces.body().contains("\"acme\""), spaces.body());
            assertEquals(200, send(c.port, "GET", "/spaces/acme/pipelines", null).statusCode());

            // unknown template -> 400; duplicate id -> 409
            assertEquals(400, send(c.port, "POST", "/spaces", "{\"id\":\"x1\",\"template\":\"nope\"}").statusCode());
            assertEquals(409, send(c.port, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"starter\"}").statusCode());
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return V1Body.of(r.body()); }
}
