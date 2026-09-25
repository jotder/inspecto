package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP proof of {@code GET /parsers/asn1/profile} — the read the Parse drawer uses to show a Decode
 * Profile's own values (and their provenance) instead of the served defaults. It reads one {@code .toon} named
 * by a Pipeline's {@code asn1.profile_file}, resolved beside the Pipeline's {@code subdir} and jailed exactly
 * as the preview and the load resolve it ({@code DecodeProfile.read}), and returns the profile's {@code asn1:}
 * block AS AUTHORED. Read-only, so ungated: an authenticated viewer (a real Subject, via a forced
 * authenticator) reads it, as it can already preview with it.
 *
 * <p>⚠ Same error-text rule as the preview: nothing of a {@code .toon} that is NOT a profile is echoed back.
 */
class ControlApiDecodeProfileReadTest {

    /** {@code Bearer viewer} → authenticated, no capabilities. */
    private static final Authenticator FAKE = ex -> "Bearer viewer".equals(ex.getRequestHeaders().getFirst("Authorization"))
            ? Optional.of(new Subject("viewer", Set.of())) : Optional.empty();

    private static final String MARKER = "sk-DO-NOT-ECHO-7731";

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** A write root holding {@code vendors/acme/acme.decode.toon} and a Pipeline directory {@code msc/}. */
    private Ctx open(Path cfg) throws Exception {
        Path wr = Files.createDirectories(cfg.resolve("wr"));
        Path vendor = Files.createDirectories(wr.resolve("vendors/acme"));
        Files.writeString(vendor.resolve("acme.decode.toon"), "asn1:\n  grammar_file: acme.asn\n"
                + "  root_type: CallEventRecord\n  strictness: BER\n  record_header_length: 4\n"
                + "  segments:\n    moCallRecord: mo_call_schema.toon\n");
        Path msc = Files.createDirectories(wr.resolve("msc"));
        // Two .toon files that are NOT profiles, each carrying a marker that must never come back.
        Files.writeString(msc.resolve("other_pipeline.toon"), "api_key: " + MARKER + "\nparsing:\n  frontend: asn1\n");
        Files.writeString(msc.resolve("broken.toon"), "asn1:\n  \"" + MARKER + "\n   : [\n");
        Path toon = TestConfigs.csv(cfg, PipelineConfigBatchTest.miniSchema()).write();
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", wr.toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> get(int port, String profileFile, String subdir) throws Exception {
        StringBuilder q = new StringBuilder();
        if (profileFile != null) q.append("profile_file=").append(URLEncoder.encode(profileFile, StandardCharsets.UTF_8));
        if (subdir != null) q.append(q.isEmpty() ? "" : "&").append("subdir=").append(URLEncoder.encode(subdir, StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/parsers/asn1/profile?" + q))
                .header("Authorization", "Bearer viewer").GET().build();
        return client.send(req, BodyHandlers.ofString());
    }

    @Test
    void aViewerReadsTheProfilesOwnValuesAsAuthoredBesideThePipelineSubdir(@TempDir Path cfg) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg)) {
            HttpResponse<String> ok = get(c.port, "../vendors/acme/acme.decode.toon", "msc");
            assertEquals(200, ok.statusCode(), ok.body());
            JsonNode asn1 = V1Body.of(ok.body()).get("asn1");
            // As authored — relative to the PROFILE, never the resolved absolute path (which is server layout).
            assertEquals("acme.asn", asn1.get("grammar_file").asText());
            assertEquals("CallEventRecord", asn1.get("root_type").asText());
            assertEquals(4, asn1.get("record_header_length").asInt());
            assertEquals("mo_call_schema.toon", asn1.get("segments").get("moCallRecord").asText());
            assertFalse(asn1.has("profile_file"), asn1.toString());
        }
    }

    @Test
    void anEscapingRefIs403AndAWrongExtensionIs422(@TempDir Path cfg) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg)) {
            String outside = Path.of(System.getProperty("user.home")).getRoot()
                    .resolve("nowhere-inspecto/x.decode.toon").toString();
            HttpResponse<String> esc = get(c.port, outside, "msc");
            assertEquals(403, esc.statusCode(), esc.body());
            HttpResponse<String> txt = get(c.port, "../vendors/acme/acme.asn", "msc");
            assertEquals(422, txt.statusCode(), txt.body());
        }
    }

    @Test
    void aToonThatIsNotAProfileIsRefusedWithoutEchoingAnyOfIt(@TempDir Path cfg) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg)) {
            for (String ref : List.of("other_pipeline.toon", "broken.toon")) {
                HttpResponse<String> res = get(c.port, ref, "msc");
                assertEquals(422, res.statusCode(), ref + ": " + res.body());
                assertFalse(res.body().contains(MARKER), ref + " leaked its content: " + res.body());
                assertFalse(res.body().contains("api_key"), ref + " leaked its keys: " + res.body());
            }
        }
    }

    @Test
    void aMissingRefOrAnAbsoluteSubdirIs400(@TempDir Path cfg) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg)) {
            assertEquals(400, get(c.port, null, "msc").statusCode());
            assertEquals(400, get(c.port, "../vendors/acme/acme.decode.toon",
                    cfg.toAbsolutePath().toString()).statusCode());
        }
    }
}
