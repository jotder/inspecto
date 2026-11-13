package com.gamma.actionrequests;

import com.gamma.spi.auth.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SE-4 step 1 for the Action Request store: read from {@code state/action-requests} first, then {@code config/}; with
 * both the state one wins, and this release refuses to rewrite (and so to dispatch) a record a newer release owns.
 */
class ActionRequestsStateZoneTest {

    private static Map<String, Object> rec() {
        Map<String, Object> r = ActionRequests.draft("hook", "https://hooks.example.test/api", "POST",
                Map.of("text", "x"), null, "inc-1", null, "manual", "author-1", "user", null, 168);
        ActionRequests.transition(r, ActionRequests.PENDING, "author-1");
        return r;
    }

    private static Path inState(Path root, String id) throws Exception {
        return Files.createDirectories(root.resolveSibling("state").resolve(ActionRequests.DIR)).resolve(id + ".json");
    }

    @Test
    void aRecordUnderStateIsRead(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        Map<String, Object> r = rec();
        String id = (String) r.get("id");
        ActionRequests.save(root, r);
        Files.move(root.resolve(ActionRequests.DIR).resolve(id + ".json"), inState(root, id));
        Map<String, Object> back = ActionRequests.read(root, id);
        assertFalse(ActionRequests.invalid(back), "its MAC covers the content and the file name, not the folder: " + back);
        assertEquals(List.of(id), ActionRequests.list(root).stream().map(x -> x.get("id")).toList());
    }

    @Test
    void aRecordUnderConfigIsRead(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        Map<String, Object> r = rec();
        ActionRequests.save(root, r);
        assertFalse(Files.exists(root.resolveSibling("state")), "nothing is written to state/");
        assertEquals("pending", ActionRequests.read(root, (String) r.get("id")).get("status"));
        assertEquals(1, ActionRequests.list(root).size());
    }

    @Test
    void withBothTheStateRecordWinsAndIsNeverRewrittenHere(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        Map<String, Object> r = rec();
        String id = (String) r.get("id");
        ActionRequests.save(root, r);
        byte[] pending = Files.readAllBytes(root.resolve(ActionRequests.DIR).resolve(id + ".json"));
        ActionRequests.transition(r, ActionRequests.DECLINED, "approver-1");
        ActionRequests.save(root, r);
        Files.write(inState(root, id), pending);

        Map<String, Object> back = ActionRequests.read(root, id);
        assertEquals("pending", back.get("status"), "state/ wins");
        assertEquals(1, ActionRequests.list(root).size(), "one record per id across both places");
        ApiException e = assertThrows(ApiException.class, () -> ActionRequests.save(root, back));
        assertEquals(409, e.status, "every decision saves before it dispatches, so nothing is sent");
        back.put("expiresAt", "2000-01-01T00:00:00Z");
        assertFalse(ActionRequests.expireIfDue(root, back), "a newer release's record is never expired here");
    }
}
