package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Metadata Bundle v2 backend (SPC-4) over real HTTP: export (real content + contentHash), the
 * read-only preview fit-check (new/unchanged/drifted + requires satisfied/missing), import apply
 * (imported/overwritten/skipped/unchanged/failed, dependency order), and every gate — write-root
 * 503, unsupported-kind 422, malformed-envelope 422. One embedded engine per test, ephemeral port.
 */
class ControlApiBundleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    /** Seed a component of {@code type} with a single-field content, via the real CRUD route. */
    private void seed(int port, String type, String id, String field, String value) throws Exception {
        HttpResponse<String> r = send(port, "POST", "/components/" + type,
                "{\"id\":\"" + id + "\",\"" + field + "\":\"" + value + "\"}");
        assertEquals(200, r.statusCode(), r.body());
    }

    @Test
    void exportsRealContentWithHashAndReportsMissing(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            seed(c.port, "dataset", "sales", "title", "Sales");
            seed(c.port, "widget", "sales_bar", "vizType", "bar");

            JsonNode out = json(send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"dataset\",\"id\":\"sales\"},"
                    + "{\"kind\":\"widget\",\"id\":\"sales_bar\"},"
                    + "{\"kind\":\"dataset\",\"id\":\"ghost\"}]}"));

            JsonNode bundle = out.get("bundle");
            assertEquals("inspecto-metadata-bundle", bundle.get("format").asText());
            assertEquals(2, bundle.get("version").asInt());
            assertEquals(2, bundle.get("items").size(), "only the two existing items travel");
            JsonNode item0 = bundle.get("items").get(0);
            assertEquals("Sales", item0.get("content").get("title").asText());
            assertTrue(item0.get("provenance").get("contentHash").asText().matches("sha256:[0-9a-f]{64}"));
            assertEquals(1, out.get("missing").size());
            assertEquals("ghost", out.get("missing").get(0).get("id").asText());
        }
    }

    @Test
    void exportRejectsUnsupportedKindAndEmptySelection(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            // 'connection' became supported (reference-only) 2026-07-25 — 'collector' is still out of scope.
            assertEquals(422, send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"collector\",\"id\":\"pg\"}]}").statusCode());
            assertEquals(422, send(c.port, "POST", "/bundle/export", "{\"items\":[]}").statusCode());
        }
    }

    /**
     * LA-21 / D-E6: a Live Working Set Widget cannot leave its Space. A bundle is how a Widget leaves one, so export
     * CONVERTS it — the copy is Frozen at the Live Widget's own pin — and says so under {@code converted}. A Frozen one
     * travels verbatim, and the source Widget is not touched.
     */
    @Test
    void aLiveWorkingSetWidgetLeavesTheSpaceFrozenAtItsPin(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String binding = "\"vizType\":\"working-set\",\"viewId\":\"case-a\",\"workingSet\":{\"relation\":\"links\","
                    + "\"pin\":{\"step\":4,\"workingSetHash\":\"sha256:ab\",\"pinnedAt\":\"2026-09-23T10:00:00Z\"},\"mode\":";
            assertEquals(200, send(c.port, "POST", "/components/widget",
                    "{\"id\":\"ws_live\"," + binding + "\"live\"}}").statusCode());
            assertEquals(200, send(c.port, "POST", "/components/widget",
                    "{\"id\":\"ws_frozen\"," + binding + "\"frozen\"}}").statusCode());
            assertEquals(422, send(c.port, "POST", "/components/widget",
                    "{\"id\":\"ws_bad\"," + binding + "\"sometimes\"}}").statusCode(),
                    "a mode the tile cannot state is refused at save");

            JsonNode out = json(send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"widget\",\"id\":\"ws_live\"},{\"kind\":\"widget\",\"id\":\"ws_frozen\"}]}"));
            JsonNode items = out.at("/bundle/items");
            assertEquals(2, items.size());
            JsonNode live = items.get(0).get("content").get("workingSet");
            assertEquals("frozen", live.get("mode").asText(), "the Live Widget left as a Frozen one");
            assertEquals(4, live.at("/pin/step").asInt(), "at its own pin");
            assertEquals("sha256:ab", live.at("/pin/workingSetHash").asText());
            assertEquals("links", live.get("relation").asText());
            assertEquals("case-a", items.get(0).at("/content/viewId").asText());
            assertEquals("frozen", items.get(1).at("/content/workingSet/mode").asText());

            JsonNode converted = out.get("converted");
            assertEquals(1, converted.size(), "only the Live one is converted, and the response says which: " + out);
            assertEquals("ws_live", converted.get(0).get("id").asText());
            assertEquals("live", converted.get(0).get("from").asText());
            assertEquals("frozen", converted.get(0).get("to").asText());

            assertTrue(send(c.port, "GET", "/components/widget/ws_live", null).body().contains("\"live\""),
                    "export converts the copy, never the source Widget");
        }
    }

    // ── referential-integrity import gate (System Maintenance MNT-16) ───────────────

    @Test
    void importRejectsABundleThatIntroducesBrokenReferences(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String bad = "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"items\":["
                    + "{\"kind\":\"widget\",\"id\":\"lonely\",\"content\":{\"vizType\":\"bar\",\"datasetId\":\"ghost_ds\"}}]}";
            HttpResponse<String> r = send(c.port, "POST", "/bundle/import", bad);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("ghost_ds"), "the finding names the broken ref: " + r.body());
            assertEquals(404, send(c.port, "GET", "/components/widget/lonely", null).statusCode(),
                    "fail-closed: nothing was written");
        }
    }

    @Test
    void importAcceptsABundleWhoseItemsResolveEachOther(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            // The widget's dataset travels IN the same bundle — the union resolves, so the gate passes.
            String good = "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"items\":["
                    + "{\"kind\":\"widget\",\"id\":\"lonely\",\"content\":{\"vizType\":\"bar\",\"datasetId\":\"ghost_ds\"}},"
                    + "{\"kind\":\"dataset\",\"id\":\"ghost_ds\",\"content\":{\"title\":\"Ghost\"}}]}";
            JsonNode out = json(send(c.port, "POST", "/bundle/import", good));
            assertEquals(2, out.get("imported").asInt(), out.toString());
            assertEquals(0, out.get("failed").asInt(), out.toString());
        }
    }

    @Test
    void preExistingBrokenRefsNeverBlockAnUnrelatedImport(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            seed(c.port, "widget", "old_broken", "datasetId", "long_gone");   // broken ref already on disk
            String unrelated = "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"items\":["
                    + "{\"kind\":\"dataset\",\"id\":\"newcomer\",\"content\":{\"title\":\"New\"}}]}";
            JsonNode out = json(send(c.port, "POST", "/bundle/import", unrelated));
            assertEquals(1, out.get("imported").asInt(),
                    "an old broken ref is the registry's problem, not this bundle's: " + out);
        }
    }

    @Test
    void previewClassifiesNewUnchangedDriftedAndRequires(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            seed(c.port, "dataset", "sales", "title", "Sales");
            // Export gives a bundle whose item hash matches the stored component exactly.
            JsonNode exported = json(send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"dataset\",\"id\":\"sales\"}]}")).get("bundle");

            // 1) identical bundle → unchanged
            JsonNode p1 = json(send(c.port, "POST", "/bundle/preview", JSON.writeValueAsString(exported)));
            assertEquals("unchanged", p1.get("items").get(0).get("status").asText());

            // 2) mutate the item content → drifted (hash no longer matches the target)
            ObjectNode drift = (ObjectNode) exported.deepCopy();
            ((ObjectNode) drift.get("items").get(0).get("content")).put("title", "Renamed");
            JsonNode p2 = json(send(c.port, "POST", "/bundle/preview", JSON.writeValueAsString(drift)));
            assertEquals("drifted", p2.get("items").get(0).get("status").asText());

            // 3) an item that does not exist on the target → new
            ObjectNode fresh = (ObjectNode) exported.deepCopy();
            ((ObjectNode) fresh.get("items").get(0)).put("id", "brand_new");
            JsonNode p3 = json(send(c.port, "POST", "/bundle/preview", JSON.writeValueAsString(fresh)));
            assertEquals("new", p3.get("items").get(0).get("status").asText());

            // requires: existing dataset satisfied, missing one flagged
            ObjectNode withReq = (ObjectNode) exported.deepCopy();
            withReq.putArray("requires")
                    .add(JSON.createObjectNode().put("kind", "dataset").put("id", "sales"))
                    .add(JSON.createObjectNode().put("kind", "dataset").put("id", "absent"));
            JsonNode p4 = json(send(c.port, "POST", "/bundle/preview", JSON.writeValueAsString(withReq)));
            assertEquals("satisfied", p4.get("requires").get(0).get("status").asText());
            assertEquals("missing", p4.get("requires").get(1).get("status").asText());
        }
    }

    @Test
    void previewFlagsRequiresPresentButDifferent(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            seed(c.port, "dataset", "sales", "title", "Sales");

            // export stamps the source's stored content hash as originHash on a resolvable required ref
            JsonNode bundle = json(send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"dataset\",\"id\":\"sales\"}],"
                    + "\"requires\":[{\"kind\":\"dataset\",\"id\":\"sales\"}]}")).get("bundle");
            String originHash = bundle.get("requires").get(0).get("originHash").asText();
            assertTrue(originHash.matches("sha256:[0-9a-f]{64}"), "export stamps a resolvable ref's origin hash");

            // the stamped hash matches the unchanged target → satisfied (and echoes the target hash)
            JsonNode same = json(send(c.port, "POST", "/bundle/preview", JSON.writeValueAsString(bundle)));
            JsonNode ok = same.get("requires").get(0);
            assertEquals("satisfied", ok.get("status").asText());
            assertEquals(originHash, ok.get("targetHash").asText(), "same version => origin and target hashes agree");

            // present on the target but carrying a different origin hash → different (drift)
            ObjectNode stale = (ObjectNode) bundle.deepCopy();
            ((ObjectNode) stale.get("requires").get(0)).put("originHash", "sha256:" + "0".repeat(64));
            JsonNode drifted = json(send(c.port, "POST", "/bundle/preview", JSON.writeValueAsString(stale)));
            assertEquals("different", drifted.get("requires").get(0).get("status").asText());

            // absent on the target stays `missing`, hash or not
            ObjectNode absent = (ObjectNode) bundle.deepCopy();
            ((ObjectNode) absent.get("requires").get(0)).put("id", "gone");
            JsonNode miss = json(send(c.port, "POST", "/bundle/preview", JSON.writeValueAsString(absent)));
            assertEquals("missing", miss.get("requires").get(0).get("status").asText());
        }
    }

    /**
     * Bundle load-as-draft D3: preview reports the integrity findings the previewed items would INTRODUCE,
     * read-only — the same (registry ∪ incoming) minus pre-existing subtraction import enforces, so a draft
     * editor can show them before its own Save. (a) resolvable ⇒ [], (b) absent Dataset ⇒ one finding naming
     * it, (c) an unrelated broken ref already on disk ⇒ still [] for (a) — and nothing is ever written.
     */
    @Test
    void previewReportsOnlyTheIntegrityFindingsTheItemsWouldIntroduce(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            seed(c.port, "dataset", "sales", "title", "Sales");
            String ok = bundleOf("widget", "sales_bar", "{\"vizType\":\"bar\",\"datasetId\":\"sales\"}");
            String broken = bundleOf("widget", "lonely", "{\"vizType\":\"bar\",\"datasetId\":\"ghost_ds\"}");

            JsonNode a = json(send(c.port, "POST", "/bundle/preview", ok));
            assertTrue(a.get("integrity").isArray(), "preview carries an integrity list: " + a);
            assertEquals(0, a.get("integrity").size(), a.toString());

            JsonNode b = json(send(c.port, "POST", "/bundle/preview", broken));
            assertEquals(1, b.get("integrity").size(), b.toString());
            assertTrue(b.get("integrity").get(0).asText().contains("ghost_ds"), b.toString());
            assertEquals(404, send(c.port, "GET", "/components/widget/lonely", null).statusCode(),
                    "preview is read-only — the finding is advisory and nothing was written");

            seed(c.port, "widget", "old_broken", "datasetId", "long_gone");
            JsonNode c2 = json(send(c.port, "POST", "/bundle/preview", ok));
            assertEquals(0, c2.get("integrity").size(),
                    "a pre-existing broken ref is subtracted, exactly as import does: " + c2);
        }
    }

    /** Without a write root there is no registry to judge against: the list is present and empty, never a 503. */
    @Test
    void previewIntegrityIsEmptyWithoutAWriteRoot(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            HttpResponse<String> r = send(c.port, "POST", "/bundle/preview",
                    bundleOf("widget", "lonely", "{\"vizType\":\"bar\",\"datasetId\":\"ghost_ds\"}"));
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(0, json(r).get("integrity").size(), r.body());
        }
    }

    // Forced Authenticator standing in for the Standard edition (same seam as ControlApiNavMenusTest).
    private static final Authenticator SEED_ROLES = ex -> switch (
            String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
        case "Bearer business" -> Optional.of(new Subject("biz", Roles.SEED.get("business").capabilities()));
        case "Bearer developer" -> Optional.of(new Subject("dev", Roles.SEED.get("developer").capabilities()));
        default -> Optional.empty();
    };

    @AfterEach
    void restoreAuthenticator() {
        Authenticators.forTest(null);
    }

    /**
     * The extended preview stays READ-ONLY and ungated under a real Subject: a Business subject (no
     * canAuthorWorkbench) gets its integrity findings, while the same subject is refused the write door —
     * so the probe that would otherwise succeed (developer import) proves the 403 is the gate, not a typo.
     */
    @Test
    void previewIntegrityIsReadableWithoutTheAuthoringCapability(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String broken = bundleOf("widget", "lonely", "{\"vizType\":\"bar\",\"datasetId\":\"ghost_ds\"}");
            Authenticators.forTest(SEED_ROLES);
            assertFalse(Roles.SEED.get("business").capabilities().contains(Roles.CAN_AUTHOR_WORKBENCH));

            HttpResponse<String> p = send(c.port, "POST", "/bundle/preview", broken, "Authorization", "Bearer business");
            assertEquals(200, p.statusCode(), p.body());
            assertEquals(1, json(p).get("integrity").size(), p.body());

            assertEquals(403, send(c.port, "POST", "/bundle/import", broken, "Authorization", "Bearer business").statusCode(),
                    "the write door stays gated");
            HttpResponse<String> dev = send(c.port, "POST", "/bundle/import", broken, "Authorization", "Bearer developer");
            assertEquals(422, dev.statusCode(), "an authoring subject passes the gate and meets the integrity refusal: " + dev.body());
        }
    }

    @Test
    void importAppliesOverwritesSkipsAndIsIdempotent(@TempDir Path source, @TempDir Path target) throws Exception {
        String bundle;
        try (Ctx src = open(source, source.resolve("wr"))) {
            seed(src.port, "dataset", "sales", "title", "Sales");
            seed(src.port, "widget", "sales_bar", "vizType", "bar");
            bundle = JSON.writeValueAsString(json(send(src.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"widget\",\"id\":\"sales_bar\"},"
                    + "{\"kind\":\"dataset\",\"id\":\"sales\"}]}")).get("bundle"));
        }
        try (Ctx tgt = open(target, target.resolve("wr"))) {
            // fresh target → both imported; dependency order means dataset applies before the widget
            JsonNode r1 = json(send(tgt.port, "POST", "/bundle/import", bundle));
            assertEquals(2, r1.get("imported").asInt(), r1.toString());
            assertEquals("dataset", r1.get("results").get(0).get("kind").asText(), "referenced kinds first");
            assertEquals(200, send(tgt.port, "GET", "/components/dataset/sales", null).statusCode());

            // re-import identical bundle → idempotent (both unchanged, nothing written)
            JsonNode r2 = json(send(tgt.port, "POST", "/bundle/import", bundle));
            assertEquals(2, r2.get("unchanged").asInt(), r2.toString());
            assertEquals(0, r2.get("imported").asInt());

            // overwrite semantics on a single, independent dataset "kpi" (hand-written bundles):
            assertEquals(1, json(send(tgt.port, "POST", "/bundle/import", bundleOf("dataset", "kpi", "{\"title\":\"A\"}")))
                    .get("imported").asInt(), "first import");
            // differing content + explicit overwrite → overwritten
            String ow = "{\"bundle\":" + bundleOf("dataset", "kpi", "{\"title\":\"B\"}")
                    + ",\"actions\":{\"dataset/kpi\":\"overwrite\"}}";
            assertEquals(1, json(send(tgt.port, "POST", "/bundle/import", ow)).get("overwritten").asInt(), "explicit overwrite");
            // differing content, no action → defaults to skip (existing is preserved)
            assertEquals(1, json(send(tgt.port, "POST", "/bundle/import", bundleOf("dataset", "kpi", "{\"title\":\"C\"}")))
                    .get("skipped").asInt(), "differing but no overwrite → skip");
            // identical content → unchanged regardless (nothing to write)
            assertEquals(1, json(send(tgt.port, "POST", "/bundle/import", bundleOf("dataset", "kpi", "{\"title\":\"B\"}")))
                    .get("unchanged").asInt(), "identical to current → unchanged");
        }
    }

    /** A minimal single-item v2 bundle for import assertions. */
    private static String bundleOf(String kind, String id, String contentJson) {
        return "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"exportedAt\":\"2026-07-07T00:00:00Z\","
                + "\"sourceSpace\":null,\"items\":[{\"kind\":\"" + kind + "\",\"id\":\"" + id + "\","
                + "\"content\":" + contentJson + "}]}";
    }

    @Test
    void importReportsUnsupportedKindAndBadItemWithoutAborting(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String bundle = "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,"
                    + "\"exportedAt\":\"2026-07-07T00:00:00Z\",\"sourceSpace\":null,\"items\":["
                    + "{\"kind\":\"dataset\",\"id\":\"ok\",\"content\":{\"title\":\"OK\"}},"
                    + "{\"kind\":\"collector\",\"id\":\"pg\",\"content\":{\"host\":\"h\"}},"       // unsupported
                    + "{\"kind\":\"widget\",\"id\":\"nocontent\"}"                                  // missing content
                    + "]}";
            JsonNode r = json(send(c.port, "POST", "/bundle/import", bundle));
            assertEquals(1, r.get("imported").asInt(), r.toString());
            assertEquals(1, r.get("skipped").asInt(), "unsupported kind skipped");
            assertEquals(1, r.get("failed").asInt(), "missing content failed");
            assertEquals(200, send(c.port, "GET", "/components/dataset/ok", null).statusCode(),
                    "the good item still applied despite the two bad ones");
        }
    }

    @Test
    void importGatedOnWriteRoot(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {   // no write root
            String bundle = "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"exportedAt\":\"x\","
                    + "\"sourceSpace\":null,\"items\":[{\"kind\":\"dataset\",\"id\":\"d\",\"content\":{}}]}";
            assertEquals(503, send(c.port, "POST", "/bundle/import", bundle).statusCode());
        }
    }

    @Test
    void importValidatesEnvelope(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(422, send(c.port, "POST", "/bundle/import",
                    "{\"format\":\"nope\",\"version\":2,\"items\":[{\"kind\":\"dataset\",\"id\":\"d\",\"content\":{}}]}").statusCode());
            assertEquals(422, send(c.port, "POST", "/bundle/import",
                    "{\"format\":\"inspecto-metadata-bundle\",\"version\":3,\"items\":[{\"kind\":\"dataset\",\"id\":\"d\",\"content\":{}}]}").statusCode());
            assertEquals(422, send(c.port, "POST", "/bundle/import",
                    "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"items\":[]}").statusCode());
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (headers.length > 0) b.headers(headers);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }
}
