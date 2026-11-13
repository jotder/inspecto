package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SE-4 step 1 for the Pending Change store: a record under {@code state/pending-changes} is read, one under
 * {@code config/pending-changes} is read, and with both the state one wins — while this release never writes there and
 * refuses to rewrite (or expire) a record a newer release owns.
 */
class PendingChangesStateZoneTest {

    private static final String ID = "pc-20261113000000-abcdef";

    private static Map<String, Object> rec(String status) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", ID);
        r.put("status", status);
        r.put("kind", "job");
        r.put("name", "nightly");
        r.put("expiresAt", "2000-01-01T00:00:00Z");
        return r;
    }

    private static Path stateFile(Path root) {
        return root.resolveSibling("state").resolve(PendingChanges.DIR).resolve(ID + ".json");
    }

    @Test
    void aRecordUnderStateIsRead(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        PendingChanges.save(root, rec("pending"));
        Path f = root.resolve(PendingChanges.DIR).resolve(ID + ".json");
        Files.move(f, Files.createDirectories(stateFile(root).getParent()).resolve(ID + ".json"));
        Map<String, Object> back = PendingChanges.read(root, ID);
        assertFalse(PendingChanges.invalid(back), "its MAC covers the content, not the path: " + back);
        assertEquals("pending", back.get("status"));
        assertEquals(List.of(ID), PendingChanges.list(root).stream().map(r -> r.get("id")).toList());
    }

    @Test
    void aRecordUnderConfigIsRead(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        PendingChanges.save(root, rec("pending"));
        assertFalse(Files.exists(root.resolveSibling("state")), "nothing is written to state/");
        assertEquals("pending", PendingChanges.read(root, ID).get("status"));
        assertEquals(List.of(ID), PendingChanges.list(root).stream().map(r -> r.get("id")).toList());
    }

    @Test
    void withBothTheStateRecordWinsAndIsNeverRewrittenHere(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        PendingChanges.save(root, rec("pending"));
        byte[] pending = Files.readAllBytes(root.resolve(PendingChanges.DIR).resolve(ID + ".json"));
        PendingChanges.save(root, rec("declined"));
        Files.write(Files.createDirectories(stateFile(root).getParent()).resolve(ID + ".json"), pending);

        Map<String, Object> back = PendingChanges.read(root, ID);
        assertEquals("pending", back.get("status"), "state/ wins");
        List<Map<String, Object>> all = PendingChanges.list(root);
        assertEquals(1, all.size(), "one record per id across both places");
        assertEquals("pending", all.get(0).get("status"));

        ApiException e = assertThrows(ApiException.class, () -> PendingChanges.save(root, rec("approved")));
        assertEquals(409, e.status);
        assertThrows(ApiException.class, () -> PendingChanges.requireWritable(root, ID));
        assertFalse(PendingChanges.expireIfDue(null, root, back), "a newer release's record is never expired here");
        assertEquals("pending", PendingChanges.read(root, ID).get("status"));
        assertTrue(Files.isRegularFile(stateFile(root)));
    }
}
