package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.gamma.control.EntityProfileFixture.A;
import static com.gamma.control.EntityProfileFixture.I1;
import static com.gamma.control.EntityProfileFixture.OWNER;
import static com.gamma.control.EntityProfileFixture.RAW;
import static com.gamma.control.EntityProfileFixture.STRANGER;
import static com.gamma.control.EntityProfileFixture.data;
import static com.gamma.control.EntityProfileFixture.get;
import static com.gamma.control.EntityProfileFixture.noRaw;
import static com.gamma.control.EntityProfileFixture.open;
import static com.gamma.control.EntityProfileFixture.send;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Third-party integration, inbound (LA-EDITIONS-1 §4): {@code POST /inv/inbound/investigations} and
 * {@code POST /inv/deep-links/resolve} over REAL HTTP with an ARMED Authenticator (with no Subject {@code withCapability} is a
 * no-op, so no gate would be proven). The fixture's {@code calls_ds} (owned by analyst-1, shared with nobody) classifies
 * {@code a_msisdn}/{@code b_msisdn} MSISDN (masked under the default {@code typed} mode) and {@code imei} IMEI, with a saved
 * Link Analysis view linking the two MSISDN columns - the mapping an inbound call without a binding uses.
 */
class ControlApiInvestigationInboundTest {

    private static final String ANALYST = "Bearer analyst", READER = "Bearer reader";
    private static final String INBOUND = "/inv/inbound/investigations", RESOLVE = "/inv/deep-links/resolve";

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
    }

    /** OWNER (analyst-1) may manage Incidents and reveal; ANALYST may manage Incidents only; READER holds nothing. */
    private static void subjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents", "canRevealLinkEntities")));
            case ANALYST -> Optional.of(new Subject("analyst-2", Set.of("canManageIncidents")));
            case READER, STRANGER -> Optional.of(new Subject("analyst-3", Set.of()));
            default -> Optional.empty();
        });
    }

    private static String alert(String system, String id, String entities, String extra) {
        return "{\"reference\":{\"system\":\"" + system + "\",\"id\":\"" + id + "\",\"url\":\"https://fraud.example/alerts/" + id + "\"},"
                + "\"entities\":[" + entities + "],\"purpose\":\"Fraud alert triage\",\"title\":\"Alert " + id + "\"" + extra + "}";
    }

    private static List<String> ops(JsonNode log) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : log.get("entries")) out.add(e.get("op").asText());
        return out;
    }

    @Test
    void anExternalRecordOpensASeededInvestigationAndARepeatAnswersTheSameOne(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            String body = alert("fraudpro", "AL-1001", "\"msisdn:" + A + "\",\"imei:" + I1 + "\"",
                    ",\"window\":{\"from\":\"2026-10-01T00:00:00Z\",\"to\":\"2026-10-04T00:00:00Z\"}");
            JsonNode first = noRaw(send(c, "POST", INBOUND, body, OWNER));
            String id = first.get("investigationId").asText();
            assertTrue(id.matches("ext-[0-9a-f]{32}"), id);
            assertTrue(first.get("created").asBoolean(), first.toString());
            assertEquals(2, first.get("seeded").asInt());
            assertEquals("/open?investigation=" + id, first.get("link").asText());
            assertEquals("fraudpro", first.at("/reference/system").asText());
            assertEquals("alert", first.at("/reference/type").asText(), "type defaults to alert");

            JsonNode log = data(get(c, "/inv/investigations/" + id + "/log", OWNER));
            assertEquals(List.of("seed", "seed", "window"), ops(log), "one seed per Entity Type, then the window: " + log);
            assertEquals("calls_ds", log.at("/header/dataset").asText(), "bound through the saved view's mapping");
            assertEquals("a_msisdn", log.at("/header/sourceCol").asText());
            assertEquals("b_msisdn", log.at("/header/targetCol").asText());
            assertEquals("ts", log.at("/header/timeCol").asText(), "the Dataset's eventDate");
            assertEquals("Fraud alert triage", log.at("/header/purpose").asText());
            for (String raw : RAW) assertFalse(log.toString().contains(raw), "the log is masked: " + log);

            JsonNode refs = data(get(c, "/inv/investigations/" + id + "/references", OWNER));
            assertEquals(1, refs.get("count").asInt());
            assertEquals("AL-1001", refs.at("/references/0/id").asText());
            assertFalse(refs.at("/references/0/trusted").asBoolean());

            JsonNode again = noRaw(send(c, "POST", INBOUND, alert("fraudpro", "AL-1001", "\"msisdn:" + A + "\"", ""), OWNER));
            assertEquals(id, again.get("investigationId").asText(), "idempotent on (system, id)");
            assertFalse(again.get("created").asBoolean());
            assertEquals(0, again.get("seeded").asInt());
            assertEquals(3, data(get(c, "/inv/investigations/" + id + "/log", OWNER)).get("entries").size(), "a repeat adds no step");
            assertEquals(1, data(get(c, "/inv/investigations/" + id + "/references", OWNER)).get("count").asInt(), "nor a reference");

            String other = data(send(c, "POST", INBOUND, alert("fraudpro", "AL-1002", "\"msisdn:" + A + "\"", ""), OWNER))
                    .get("investigationId").asText();
            assertNotEquals(id, other, "another external id is another Investigation");
            String otherSystem = data(send(c, "POST", INBOUND, alert("casemgr", "AL-1001", "\"msisdn:" + A + "\"", ""), OWNER))
                    .get("investigationId").asText();
            assertNotEquals(id, otherSystem, "the same id in another system is another Investigation");
            assertEquals(3, data(get(c, "/inv/investigations", OWNER)).get("total").asInt());
        }
    }

    @Test
    void anInvalidEntityOrRecordIsRefused422AndNothingIsWritten(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            for (String entities : List.of("\"nope:123\"", "\"msisdn\"", "\"msisdn:\"", "\":" + A + "\"", "\"msisdn:unknown\"", "42", "")) {
                HttpResponse<String> r = send(c, "POST", INBOUND, alert("fraudpro", "AL-1", entities, ""), OWNER);
                assertEquals(422, r.statusCode(), entities + " → " + r.body());
            }
            assertEquals(422, send(c, "POST", INBOUND, "{\"entities\":[\"msisdn:" + A + "\"],\"purpose\":\"p\"}", OWNER).statusCode(),
                    "no reference");
            assertEquals(422, send(c, "POST", INBOUND, alert("bad system!", "AL-1", "\"msisdn:" + A + "\"", ""), OWNER).statusCode());
            assertEquals(422, send(c, "POST", INBOUND, alert("fraudpro", "AL-1", "\"msisdn:" + A + "\"",
                    ",\"window\":{\"from\":\"2026-10-04T00:00:00Z\",\"to\":\"2026-10-01T00:00:00Z\"}"), OWNER).statusCode(), "inverted window");
            assertEquals(422, send(c, "POST", INBOUND, alert("fraudpro", "AL-1", "\"msisdn:" + A + "\"",
                    ",\"binding\":{\"dataset\":\"calls_ds\",\"sourceCol\":\"a_msisdn\",\"targetCol\":\"b_msisdn\"},"
                            + "\"window\":{\"from\":\"2026-10-01T00:00:00Z\"}"), OWNER).statusCode(), "a window needs a time column");
            assertEquals(422, send(c, "POST", INBOUND, alert("fraudpro", "AL-1", "\"cell:cell-7\"", ""), OWNER).statusCode(),
                    "no view or index links a cell column");
            HttpResponse<String> noPurpose = send(c, "POST", INBOUND, "{\"reference\":{\"system\":\"fraudpro\",\"id\":\"AL-1\"},"
                    + "\"entities\":[\"msisdn:" + A + "\"]}", OWNER);
            assertEquals(422, noPurpose.statusCode(), noPurpose.body());
            for (String raw : RAW) assertFalse(noPurpose.body().contains(raw), noPurpose.body());
            assertEquals(0, data(get(c, "/inv/investigations", OWNER)).get("total").asInt(), "nothing was created");

            JsonNode explicit = data(send(c, "POST", INBOUND, alert("fraudpro", "AL-2", "\"msisdn:" + A + "\"",
                    ",\"binding\":{\"dataset\":\"calls_ds\",\"sourceCol\":\"b_msisdn\",\"targetCol\":\"a_msisdn\"}"), OWNER));
            assertEquals("b_msisdn", data(get(c, "/inv/investigations/" + explicit.get("investigationId").asText() + "/log", OWNER))
                    .at("/header/sourceCol").asText(), "an explicit binding wins");
        }
    }

    @Test
    void theInboundRouteIsGatedAndARepeatByAnotherSubjectIsAConflict(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            String body = alert("fraudpro", "AL-7", "\"msisdn:" + A + "\"", "");
            assertEquals(403, send(c, "POST", INBOUND, body, READER).statusCode(), "canManageIncidents");
            assertEquals(401, send(c, "POST", INBOUND, body, "Bearer nobody").statusCode(), "no Subject");
            assertEquals(0, data(get(c, "/inv/investigations", OWNER)).get("total").asInt());

            data(send(c, "POST", INBOUND, body, OWNER));
            HttpResponse<String> theirs = send(c, "POST", INBOUND, body, ANALYST);
            assertEquals(409, theirs.statusCode(), "another Subject cannot adopt it: " + theirs.body());
            assertFalse(theirs.body().contains("ext-"), "and is not told its id: " + theirs.body());
        }
    }

    @Test
    void withoutAWriteRootTheRoutesAnswer503(@TempDir Path cfg) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, null)) {
            assertEquals(503, send(c, "POST", INBOUND, alert("fraudpro", "AL-1", "\"msisdn:" + A + "\"", ""), OWNER).statusCode());
            assertEquals(503, send(c, "POST", RESOLVE, "{\"entity\":\"cell:cell-7\"}", OWNER).statusCode());
        }
    }

    @Test
    void aDeepLinkResolvesAMaskedEntityByAliasOnlyAndRevealsItOnlyToARevealer(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            HttpResponse<String> raw = send(c, "POST", RESOLVE, "{\"entity\":\"msisdn:" + A + "\"}", OWNER);
            assertEquals(422, raw.statusCode(), "a raw value of a masked type never names it in a link: " + raw.body());
            assertTrue(raw.body().contains("/inv/entities/msisdn/resolve"), raw.body());
            for (String any : RAW) assertFalse(raw.body().contains(any), "the refusal repeats no raw id: " + raw.body());

            String alias = data(send(c, "POST", "/inv/entities/msisdn/resolve", "{\"key\":\"" + A + "\"}", OWNER)).get("key").asText();
            JsonNode masked = noRaw(send(c, "POST", RESOLVE, "{\"entity\":\"msisdn:" + alias + "\","
                    + "\"window\":{\"from\":\"2026-10-01T00:00:00Z\",\"to\":\"2026-10-04T00:00:00Z\"}}", ANALYST));
            assertEquals("msisdn", masked.at("/entity/type").asText());
            assertEquals(alias, masked.at("/entity/key").asText(), "shown masked");
            assertTrue(masked.at("/entity/masked").asBoolean());
            assertFalse(masked.get("entity").has("revealed"));
            assertEquals("2026-10-01T00:00:00Z", masked.at("/window/from").asText());

            assertEquals(403, send(c, "POST", RESOLVE, "{\"entity\":\"msisdn:" + alias + "\",\"reveal\":true}", ANALYST).statusCode(),
                    "only a holder of canRevealLinkEntities");
            JsonNode revealed = data(send(c, "POST", RESOLVE, "{\"entity\":\"msisdn:" + alias + "\",\"reveal\":true}", OWNER));
            assertEquals(A, revealed.at("/entity/revealed").asText());
            assertEquals(alias, revealed.at("/entity/key").asText());

            JsonNode cell = data(send(c, "POST", RESOLVE, "{\"entity\":\"cell: cell-7 \"}", READER));
            assertEquals("CELL-7", cell.at("/entity/key").asText(), "an unmasked type takes its value, normalised");
            assertFalse(cell.at("/entity/masked").asBoolean());

            assertEquals(422, send(c, "POST", RESOLVE, "{}", OWNER).statusCode(), "names nothing");
            assertEquals(422, send(c, "POST", RESOLVE, "{\"entity\":\"nope:1\"}", OWNER).statusCode(), "unknown Entity Type");
            assertEquals(422, send(c, "POST", RESOLVE, "{\"entity\":\"msisdn:masked:0000000000000000\"}", OWNER).statusCode(),
                    "an alias this server never minted");
            assertEquals(422, send(c, "POST", RESOLVE, "{\"entity\":\"cell:x\",\"window\":{\"from\":\"yesterday\"}}", OWNER).statusCode());
        }
    }

    @Test
    void aDeepLinkToAnInvestigationResolvesOnlyForAReader(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            String id = data(send(c, "POST", INBOUND, alert("fraudpro", "AL-9", "\"msisdn:" + A + "\"", ""), OWNER))
                    .get("investigationId").asText();
            JsonNode mine = data(send(c, "POST", RESOLVE, "{\"investigation\":\"" + id + "\"}", OWNER));
            assertEquals(id, mine.at("/investigation/id").asText());
            assertEquals("Alert AL-9", mine.at("/investigation/title").asText());
            assertFalse(mine.has("entity"));
            assertEquals(404, send(c, "POST", RESOLVE, "{\"investigation\":\"" + id + "\"}", ANALYST).statusCode(),
                    "not a member: absence");
            assertEquals(404, send(c, "POST", RESOLVE, "{\"investigation\":\"inv-missing\"}", OWNER).statusCode());
            assertEquals(422, send(c, "POST", RESOLVE, "{\"investigation\":\"../x\"}", OWNER).statusCode());
        }
    }
}
