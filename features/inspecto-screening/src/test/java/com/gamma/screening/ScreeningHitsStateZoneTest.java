package com.gamma.screening;

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
 * SE-4 step 1 for the Screening Hit store: a hit under {@code state/screening-hits} is read, listed, paged and indexed;
 * one under {@code config/} too; with both the state one wins, and this release refuses to rewrite it.
 */
class ScreeningHitsStateZoneTest {

    private static Path inState(Path root, String id) throws Exception {
        return Files.createDirectories(root.resolveSibling("state").resolve(ScreeningHits.DIR)).resolve(id + ".json");
    }

    private static String seed(Path root) throws Exception {
        return ScreeningLists.seedHit(root, "c1", "Vladimir Putin", "sanctions", "vladimir putin", 0.97);
    }

    @Test
    void aHitUnderStateIsRead(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        String id = seed(root);
        Files.move(ScreeningLists.hitFile(root, id), inState(root, id));
        Map<String, Object> back = ScreeningHits.read(root, id);
        assertFalse(ScreeningHits.invalid(back), "its MAC covers the content and the file name, not the folder: " + back);
        assertEquals(List.of(id), ScreeningHits.list(root).stream().map(x -> x.get("id")).toList());
        ScreeningHits.Page page = ScreeningHits.page(root, "open", null, 10);
        assertEquals(1, page.total(), "the index covers state/ too");
        assertEquals(id, page.hits().get(0).get("id"));
    }

    @Test
    void aHitUnderConfigIsRead(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        String id = seed(root);
        assertFalse(Files.exists(root.resolveSibling("state")), "nothing is written to state/");
        assertEquals("open", ScreeningHits.read(root, id).get("state"));
        assertEquals(1, ScreeningHits.page(root, null, null, 10).total());
    }

    @Test
    void withBothTheStateHitWinsAndIsNeverRewrittenHere(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        String id = seed(root);
        byte[] open = Files.readAllBytes(ScreeningLists.hitFile(root, id));
        Map<String, Object> rec = ScreeningHits.read(root, id);
        ScreeningHits.transition(rec, "dismissed", "analyst-1", "not him");
        ScreeningHits.save(root, rec);
        ScreeningHits.reindex(root, rec);
        Files.write(inState(root, id), open);

        assertEquals("open", ScreeningHits.read(root, id).get("state"), "state/ wins");
        assertEquals(1, ScreeningHits.list(root).size(), "one hit per id across both places");
        assertEquals(1, ScreeningHits.page(root, "open", null, 10).total(), "the index follows the hit that is read");
        assertEquals(0, ScreeningHits.page(root, "dismissed", null, 10).total());
        Map<String, Object> back = ScreeningHits.read(root, id);
        ApiException e = assertThrows(ApiException.class, () -> ScreeningHits.save(root, back));
        assertEquals(409, e.status);
    }
}
