package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-HTTP tests for the parser-catalog routes: {@code GET /parsers} (the self-describing
 * registry — id/label/hierarchical/ingestable + grammar schema) and
 * {@code POST /parsers/&#123;id&#125;/preview} (stateless grammar preview, table or tree).
 * Covers each fail-closed step: bad body 400, unknown id 404, caps 400, caller errors 422,
 * plus both result kinds. The parse mechanics themselves live in {@code ParsersTest} /
 * {@code XmlParserPluginTest} — what can only be checked here is the HTTP contract.
 */
class ControlApiParsersTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg) throws Exception {
        Path toon = TestConfigs.csv(cfg, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void catalogServesTheBuiltinsPlusXmlAndAsn1WithSchemasAndHonestIngestability(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            JsonNode list = json(send(c.port, "GET", "/parsers", null));
            assertEquals(8, list.size(), list.toString());
            assertEquals("delimited", list.get(0).get("id").asText());
            assertEquals("parquet", list.get(3).get("id").asText()); // ELT P3 S3c-1: the sixth builtin
            assertEquals("xlsx", list.get(4).get("id").asText()); // multiformat X2: the fifth builtin
            assertEquals("xml", list.get(6).get("id").asText());
            assertEquals("asn1", list.get(7).get("id").asText());
            for (JsonNode p : list) {
                assertTrue(p.get("grammarSchema").size() > 0, p.get("id").asText() + " has no schema");
                assertTrue(p.get("grammarSchema").get(0).hasNonNull("path"));
            }
            assertTrue(list.get(3).get("ingestable").asBoolean(), "a builtin is ingestable by identity");
            // Both plugins are hierarchical, and since the tree→segments bridge shipped both name an
            // ingester — the catalog serves the class, which is what the segments editor gates on.
            JsonNode xml = list.get(6);
            assertTrue(xml.get("hierarchical").asBoolean());
            assertTrue(xml.get("ingestable").asBoolean(), "XmlRecordIngester flattens onto segments");
            assertEquals("com.gamma.ingester.XmlRecordIngester", xml.get("ingesterClass").asText());
            JsonNode asn1 = list.get(7);
            assertTrue(asn1.get("hierarchical").asBoolean());
            assertTrue(asn1.get("ingestable").asBoolean(), "Asn1RecordIngester flattens onto segments");
            assertEquals("com.gamma.ingester.Asn1RecordIngester", asn1.get("ingesterClass").asText());
            assertTrue(list.get(0).get("ingestable").asBoolean());
        }
    }

    @Test
    void builtinPreviewReturnsATable(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            JsonNode r = json(send(c.port, "POST", "/parsers/delimited/preview",
                    "{\"grammar\":{\"delimited\":{\"has_header\":true}},\"sample_text\":\"id,qty\\n1001,3\\n\"}"));
            assertEquals("table", r.get("kind").asText());
            assertEquals("id", r.get("columns").get(0).asText());
            assertEquals(1, r.get("rowCount").asInt());
        }
    }

    /** B2: the delimited preview additionally serves per-column INFERRED types (auto_detect sniff). */
    @Test
    void builtinPreviewCarriesInferredColumnTypes(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            JsonNode r = json(send(c.port, "POST", "/parsers/delimited/preview",
                    "{\"grammar\":{\"delimited\":{\"has_header\":true}},"
                            + "\"sample_text\":\"id,when,city\\n1,2026-07-15,london\\n2,2026-07-16,paris\\n\"}"));
            JsonNode types = r.get("columnTypes");
            assertEquals(3, types.size(), r.toString());
            assertEquals("id", types.get(0).get("name").asText());
            assertEquals("BIGINT", types.get(0).get("type").asText());
            assertEquals("DATE", types.get(1).get("type").asText());
            assertEquals("VARCHAR", types.get(2).get("type").asText());
        }
    }

    /** AUTHORING-REDESIGN-1 (i): the delimited preview serves what the SAMPLE resolves to, per option. */
    @Test
    void builtinPreviewCarriesTheDialectTheSampleResolvesTo(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            JsonNode r = json(send(c.port, "POST", "/parsers/delimited/preview",
                    "{\"grammar\":{\"delimited\":{\"delimiter\":\",\"}},"
                            + "\"sample_text\":\"id|city\\n1|london\\n2|paris\\n3|rome\\n\"}"));
            JsonNode resolved = r.get("resolved");
            assertNotNull(resolved, r.toString());
            assertEquals("|", resolved.get("delimiter").asText(), "the file's answer, not the grammar's: " + resolved);
            assertEquals("true", resolved.get("has_header").asText(), resolved.toString());
        }
    }

    @Test
    void xmlPreviewReturnsATreeAndAcceptsBase64Samples(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            String doc = "<orders><order id=\"1\"><amount>42.5</amount></order>"
                    + "<order id=\"2\"><amount>17</amount></order></orders>";
            String b64 = Base64.getEncoder().encodeToString(doc.getBytes());
            JsonNode r = json(send(c.port, "POST", "/parsers/xml/preview", "{\"sample_b64\":\"" + b64 + "\"}"));
            assertEquals("tree", r.get("kind").asText());
            assertEquals(2, r.get("recordCount").asInt());
            JsonNode rec = r.get("nodes").get(0);
            assertEquals("order", rec.get("label").asText());
            assertEquals("@id", rec.get("children").get(0).get("label").asText());
            assertEquals("42.5", rec.get("children").get(1).get("value").asText());
        }
    }

    @Test
    void asn1PreviewReturnsATreeViaBase64Sample(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            String grammar = "TEST DEFINITIONS IMPLICIT TAGS ::= BEGIN\n"
                    + "Record ::= [APPLICATION 1] SEQUENCE { id [0] INTEGER }\n"
                    + "END\n";
            // 61 03 { 80 01 0A } == id=10
            byte[] sample = java.util.HexFormat.of().parseHex("610380010A");
            String body = new ObjectMapper().writeValueAsString(Map.of(
                    "grammar", Map.of("asn1", Map.of("grammar", grammar, "root_type", "Record")),
                    "sample_b64", Base64.getEncoder().encodeToString(sample)));
            JsonNode r = json(send(c.port, "POST", "/parsers/asn1/preview", body));
            assertEquals("tree", r.get("kind").asText());
            assertEquals(1, r.get("recordCount").asInt());
            JsonNode id = r.get("nodes").get(0).get("children").get(0);
            assertEquals("id", id.get("label").asText());
            assertEquals("10", id.get("value").asText());
        }
    }

    /**
     * Operator decision 2026-09-23: the preview takes a stored {@code .asn} FILE ({@code asn1.grammar_file})
     * and answers exactly what the same module pasted inline answers; a ref outside the allowed roots is
     * the jail's verdict, 403 — never "not readable", which would leak whether the path exists.
     */
    @Test
    void asn1PreviewTakesAGrammarFileAndAnEscapingOneIs403(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            String grammar = "TEST DEFINITIONS IMPLICIT TAGS ::= BEGIN\n"
                    + "Record ::= [APPLICATION 1] SEQUENCE { id [0] INTEGER }\n"
                    + "END\n";
            Path asn = java.nio.file.Files.writeString(cfg.resolve("record.asn"), grammar);
            String sample = Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex("610380010A"));
            ObjectMapper m = new ObjectMapper();
            JsonNode inline = json(send(c.port, "POST", "/parsers/asn1/preview", m.writeValueAsString(Map.of(
                    "grammar", Map.of("asn1", Map.of("grammar", grammar, "root_type", "Record")),
                    "sample_b64", sample))));
            JsonNode file = json(send(c.port, "POST", "/parsers/asn1/preview", m.writeValueAsString(Map.of(
                    "grammar", Map.of("asn1", Map.of("grammar_file", asn.toString(), "root_type", "Record")),
                    "sample_b64", sample))));
            assertEquals(inline, file);

            String outside = Path.of(System.getProperty("user.home")).getRoot()
                    .resolve("nowhere-inspecto/x.asn").toString();
            HttpResponse<String> esc = send(c.port, "POST", "/parsers/asn1/preview", m.writeValueAsString(Map.of(
                    "grammar", Map.of("asn1", Map.of("grammar_file", outside, "root_type", "Record")),
                    "sample_b64", sample)));
            assertEquals(403, esc.statusCode(), esc.body());
        }
    }

    /**
     * {@code BUNDLE-ASN1-GRAMMAR-FILE-1}: a Pipeline in a subdirectory spells its module as a sibling
     * ({@code record.asn}), and the drawer sends the Pipeline's {@code subdir} so the preview resolves the
     * SAME spelling beside it. Without a Pipeline context the ref falls back to the Space root, where the
     * sibling spelling does not resolve; an absolute {@code subdir} is a caller error.
     */
    @Test
    void asn1PreviewResolvesAGrammarFileBesideThePipelineSubdir(@TempDir Path cfg) throws Exception {
        String grammar = "TEST DEFINITIONS IMPLICIT TAGS ::= BEGIN\n"
                + "Record ::= [APPLICATION 1] SEQUENCE { id [0] INTEGER }\n"
                + "END\n";
        Path wr = java.nio.file.Files.createDirectories(cfg.resolve("wr"));
        java.nio.file.Files.writeString(java.nio.file.Files.createDirectories(wr.resolve("msc")).resolve("record.asn"),
                grammar);
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", wr.toString());
        Ctx ctx;
        try {
            ctx = open(cfg);
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
        try (Ctx c = ctx) {
            String sample = Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex("610380010A"));
            ObjectMapper m = new ObjectMapper();
            Map<String, Object> sibling = Map.of("asn1", Map.of("grammar_file", "record.asn", "root_type", "Record"));
            JsonNode inline = json(send(c.port, "POST", "/parsers/asn1/preview", m.writeValueAsString(Map.of(
                    "grammar", Map.of("asn1", Map.of("grammar", grammar, "root_type", "Record")),
                    "sample_b64", sample))));
            JsonNode beside = json(send(c.port, "POST", "/parsers/asn1/preview", m.writeValueAsString(Map.of(
                    "grammar", sibling, "subdir", "msc", "sample_b64", sample))));
            assertEquals(inline, beside, "the Pipeline's sibling spelling previews beside it");

            HttpResponse<String> noContext = send(c.port, "POST", "/parsers/asn1/preview", m.writeValueAsString(Map.of(
                    "grammar", sibling, "sample_b64", sample)));
            assertEquals(422, noContext.statusCode(), noContext.body());

            HttpResponse<String> absolute = send(c.port, "POST", "/parsers/asn1/preview", m.writeValueAsString(Map.of(
                    "grammar", sibling, "subdir", wr.resolve("msc").toString(), "sample_b64", sample)));
            assertEquals(400, absolute.statusCode(), absolute.body());
        }
    }

    /**
     * Decode Profile (trust design slice C2): the drawer's {@code subdir} context carries a
     * {@code asn1.profile_file} too. A profile-backed preview answers exactly what the same settings
     * inline answer — the profile's grammar_file resolving beside the PROFILE, a Pipeline key winning —
     * and an escaping profile is the jail's 403.
     */
    @Test
    void asn1PreviewHonoursADecodeProfileBesideThePipelineSubdir(@TempDir Path cfg) throws Exception {
        String grammar = "TEST DEFINITIONS IMPLICIT TAGS ::= BEGIN\n"
                + "Record ::= [APPLICATION 1] SEQUENCE { id [0] INTEGER }\n"
                + "END\n";
        Path wr = java.nio.file.Files.createDirectories(cfg.resolve("wr"));
        Path vendor = java.nio.file.Files.createDirectories(wr.resolve("vendors/acme"));
        java.nio.file.Files.writeString(vendor.resolve("acme.asn"), grammar);
        // root_type deliberately WRONG in the profile: the Pipeline's own key must win for the tree to match.
        java.nio.file.Files.writeString(vendor.resolve("acme.decode.toon"),
                "asn1:\n  grammar_file: acme.asn\n  root_type: NoSuchType\n  strictness: BER\n");
        java.nio.file.Files.createDirectories(wr.resolve("msc"));
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", wr.toString());
        Ctx ctx;
        try {
            ctx = open(cfg);
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
        try (Ctx c = ctx) {
            String sample = Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex("610380010A"));
            ObjectMapper m = new ObjectMapper();
            JsonNode inline = json(send(c.port, "POST", "/parsers/asn1/preview", m.writeValueAsString(Map.of(
                    "grammar", Map.of("asn1", Map.of("grammar", grammar, "root_type", "Record")),
                    "sample_b64", sample))));
            HttpResponse<String> viaProfile = send(c.port, "POST", "/parsers/asn1/preview", m.writeValueAsString(Map.of(
                    "grammar", Map.of("asn1", Map.of("profile_file", "../vendors/acme/acme.decode.toon",
                            "root_type", "Record")),
                    "subdir", "msc", "sample_b64", sample)));
            assertEquals(200, viaProfile.statusCode(), viaProfile.body());
            assertEquals(inline, json(viaProfile), "the profile-backed preview tree equals the inline one");

            HttpResponse<String> profileRootType = send(c.port, "POST", "/parsers/asn1/preview", m.writeValueAsString(Map.of(
                    "grammar", Map.of("asn1", Map.of("profile_file", "../vendors/acme/acme.decode.toon")),
                    "subdir", "msc", "sample_b64", sample)));
            assertEquals(422, profileRootType.statusCode(), "without the override the profile's root_type applies: "
                    + profileRootType.body());

            HttpResponse<String> esc = send(c.port, "POST", "/parsers/asn1/preview", m.writeValueAsString(Map.of(
                    "grammar", Map.of("asn1", Map.of("profile_file", Path.of(System.getProperty("user.home"))
                                    .getRoot().resolve("nowhere-inspecto/x.decode.toon").toString(),
                            "root_type", "Record")),
                    "subdir", "msc", "sample_b64", sample)));
            assertEquals(403, esc.statusCode(), esc.body());
        }
    }

    @Test
    void unknownParserIs404(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            HttpResponse<String> res = send(c.port, "POST", "/parsers/made_up_format/preview",
                    "{\"sample_text\":\"x\"}");
            assertEquals(404, res.statusCode(), res.body());
        }
    }

    @Test
    void aMissingSampleIs400AndAnOversizedOneIsRefused(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            assertEquals(400, send(c.port, "POST", "/parsers/delimited/preview", "{}").statusCode());
            String big = "x".repeat(1_000_001);
            HttpResponse<String> res = send(c.port, "POST", "/parsers/delimited/preview",
                    "{\"sample_text\":\"" + big + "\"}");
            assertEquals(400, res.statusCode(), res.body());
            assertTrue(res.body().contains("too large"), res.body());
        }
    }

    @Test
    void aCallerParseProblemIs422WithTheReasonNeverA500(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            // Malformed XML sample.
            HttpResponse<String> bad = send(c.port, "POST", "/parsers/xml/preview",
                    "{\"sample_text\":\"<a><oops\"}");
            assertEquals(422, bad.statusCode(), bad.body());
            assertTrue(bad.body().contains("not well-formed"), bad.body());
            // A grammar naming an unknown encoding.
            HttpResponse<String> enc = send(c.port, "POST", "/parsers/delimited/preview",
                    "{\"grammar\":{\"encoding\":\"NOPE-8\"},\"sample_text\":\"a,b\\n\"}");
            assertEquals(422, enc.statusCode(), enc.body());
            assertTrue(enc.body().contains("unknown encoding"), enc.body());
        }
    }

    /** Slice P2 (operator D1 2026-09-25): a parser a Job Pack contributed is listed with its pack as
     *  {@code source}; built-ins and classpath plugins say so too — the Job Type provenance vocabulary. */
    @Test
    void catalogNamesEachParsersProvenanceIncludingAPacksParser(@TempDir Path cfg) throws Exception {
        com.gamma.parse.Parsers.register(new com.gamma.parse.ParserPlugin() {
            @Override public String id() { return "acme_cdr"; }
            @Override public String label() { return "Acme CDR"; }
            @Override public boolean hierarchical() { return false; }
            @Override public List<com.gamma.config.spec.FieldSpec> grammarSchema() { return List.of(); }
            @Override public com.gamma.parse.ParseResult preview(byte[] s, Map<String, Object> g) {
                return new com.gamma.parse.ParseResult.Tree(0, List.of());
            }
        }, "acme-1.jar");
        try (Ctx c = open(cfg)) {
            JsonNode list = json(send(c.port, "GET", "/parsers", null));
            assertEquals(9, list.size(), list.toString());
            assertEquals("builtin", list.get(0).get("source").asText());
            assertEquals("classpath", list.get(6).get("source").asText(), "xml is a ServiceLoader plugin");
            JsonNode pack = list.get(8);
            assertEquals("acme_cdr", pack.get("id").asText(), "a pack parser lists after every other");
            assertEquals("pack:acme-1.jar", pack.get("source").asText());
        } finally {
            com.gamma.parse.Parsers.deregister("acme-1.jar");
        }
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────

    // ⚠ Every route is served under `/api/v1` — a bare `/api` returns "unknown API version", which
    // presents as the ROUTE being unregistered rather than the request being misaddressed.
    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", "application/json");
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /** ⚠ Unwrap the v1 envelope — reading the body directly yields `{data,metadata,…}`, not the payload. */
    private JsonNode json(HttpResponse<String> res) throws Exception {
        assertTrue(res.statusCode() < 300, "expected 2xx, got " + res.statusCode() + ": " + res.body());
        return V1Body.of(res.body());
    }
}
