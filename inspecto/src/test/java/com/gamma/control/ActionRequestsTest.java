package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Action Request store ({@code ASSURE-ACTION-REQUESTS-1}): the signed round trip, tamper detection with the
 * Pending Change key, domain separation from Pending Changes, and payload rendering that cannot break the JSON.
 */
class ActionRequestsTest {

    private static Map<String, Object> rec() {
        Map<String, Object> r = ActionRequests.draft("hook", "https://hooks.example.test/api", "POST",
                Map.of("text", "x"), null, "inc-1", null, "manual", "author-1", "user", null, 168);
        ActionRequests.transition(r, ActionRequests.PENDING, "author-1");
        return r;
    }

    @Test
    void aSavedRecordReadsBackValidWithItsHistory(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        Map<String, Object> r = rec();
        ActionRequests.save(root, r);
        Map<String, Object> back = ActionRequests.read(root, (String) r.get("id"));
        assertFalse(ActionRequests.invalid(back));
        assertEquals("pending", back.get("status"));
        assertEquals(r.get("id"), back.get("idempotencyKey"), "the key defaults to the id");
        assertEquals(List.of("draft", "pending"), ((List<?>) back.get("history")).stream()
                .map(h -> ((Map<?, ?>) h).get("status")).toList());
        assertTrue(Files.exists(root.resolveSibling("config.secrets").resolve(PendingChanges.KEY_FILE)),
                "signed with the Pending Change key, in its secrets sibling — no second key");
    }

    @Test
    void anEditedRecordReadsBackInvalidAndIsNeverReSigned(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        Map<String, Object> r = rec();
        ActionRequests.save(root, r);
        Path f = root.resolve(ActionRequests.DIR).resolve(r.get("id") + ".json");
        Files.writeString(f, Files.readString(f).replace("hooks.example.test", "evil.example.test"));
        Map<String, Object> back = ActionRequests.read(root, (String) r.get("id"));
        assertTrue(ActionRequests.invalid(back));
        assertEquals("invalid", back.get("status"));
        assertThrows(IllegalStateException.class, () -> ActionRequests.save(root, back));
    }

    @Test
    void aRecordCopiedUnderAnotherIdIsInvalid(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        Map<String, Object> r = rec();
        ActionRequests.save(root, r);
        Path d = root.resolve(ActionRequests.DIR);
        Files.copy(d.resolve(r.get("id") + ".json"), d.resolve("ar-20260101000000-abcdef.json"));
        assertTrue(ActionRequests.invalid(ActionRequests.read(root, "ar-20260101000000-abcdef")));
    }

    @Test
    void aPendingChangeMacNeverVerifiesAsAnActionRequest(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        Map<String, Object> r = rec();
        ActionRequests.save(root, r);
        Map<String, Object> clean = ActionRequests.detail(ActionRequests.read(root, (String) r.get("id")));
        assertNotEquals(PendingChanges.domainMac(root, ActionRequests.DOMAIN, clean),
                PendingChanges.domainMac(root, "pending-change", clean), "the domain is inside the MAC");
    }

    @Test
    void renderingFillsStringLeavesAndCannotBreakTheJson() {
        Object out = ActionRequests.render(Map.of("title", "Incident {{incident.id}}", "n", 3,
                "tags", List.of("{{rule}}")), Map.of("incident", Map.of("id", "inc-\"1\"}"), "rule", "r1"));
        assertEquals(Map.of("title", "Incident inc-\"1\"}", "n", 3, "tags", List.of("r1")), out);
    }

    /** An import can never land a record here, signed or not. */
    @Test
    void theStoreDirectoryIsReservedFromEveryImport() {
        assertTrue(com.gamma.service.ReservedConfigPaths.reserved(ActionRequests.DIR + "/ar-20260101000000-abcdef.json"));
        assertTrue(com.gamma.service.ReservedConfigPaths.reserved("ACTION-REQUESTS/x.json"));
    }

    @Test
    void anUnsafeIdIsRefused(@TempDir Path tmp) {
        ApiException e = assertThrows(ApiException.class, () -> ActionRequests.read(tmp, "../../etc"));
        assertEquals(422, e.status);
    }
}
