package com.gamma.la.api;

import com.gamma.la.core.InvestigationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * PERF-P4-3: every ingredient of the Working Set tag moves the tag ALONE. The store is a stub whose cache key and tokens are
 * constants, so a component that is also implied by another (draftId and baseStep are implied by a Draft's own directory
 * in the real store) is still proven to be in the tag by itself.
 */
class WorkingSetEtagTest {

    private static InvestigationStore store(Function<String, String> token) {
        return (InvestigationStore) Proxy.newProxyInstance(InvestigationStore.class.getClassLoader(), new Class<?>[] {InvestigationStore.class},
                (p, m, a) -> switch (m.getName()) {
                    case "cacheKey" -> "k";
                    case "logToken" -> token.apply(String.valueOf(a[0]));
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static InvestigationRoutes.Inv inv(Path root, InvestigationRoutes.Inv.DraftRef draft) {
        return new InvestigationRoutes.Inv(store(s -> "tok"), root, "inv-a", Map.of("dataset", "d"), draft);
    }

    private static String tag(InvestigationRoutes.Inv inv, String token, String content, String of, int limit, int offset, int at) {
        return WorkingSetEtag.of(inv, token, content, of, limit, offset, at);
    }

    @Test
    void eachIngredientMovesTheTagByItself(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");
        InvestigationRoutes.Inv main = inv(root, null);
        String base = tag(main, "t", "c", "entities", 10, 0, -1);
        assertEquals(base, tag(main, "t", "c", "entities", 10, 0, -1), "deterministic");
        assertNotEquals(base, tag(main, "t2", "c", "entities", 10, 0, -1), "token");
        assertNotEquals(base, tag(main, "t", "c2", "entities", 10, 0, -1), "content key");
        assertNotEquals(base, tag(main, "t", "c", "links", 10, 0, -1), "table");
        assertNotEquals(base, tag(main, "t", "c", "entities", 11, 0, -1), "limit");
        assertNotEquals(base, tag(main, "t", "c", "entities", 10, 1, -1), "offset");
        assertNotEquals(base, tag(main, "t", "c", "entities", 10, 0, 3), "pin");

        String d1 = tag(inv(root, new InvestigationRoutes.Inv.DraftRef("d1", 2)), "t", "c", "entities", 10, 0, -1);
        assertNotEquals(base, d1, "a Draft is not the main log");
        assertNotEquals(d1, tag(inv(root, new InvestigationRoutes.Inv.DraftRef("d2", 2)), "t", "c", "entities", 10, 0, -1), "draftId alone");
        // baseStep reaches the tag through logToken (the main prefix a Draft forked from)
        assertNotEquals(WorkingSetEtag.logToken(inv(root, new InvestigationRoutes.Inv.DraftRef("d1", 2))),
                WorkingSetEtag.logToken(inv(root, new InvestigationRoutes.Inv.DraftRef("d1", 3))), "baseStep alone");

        Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: all\n");
        assertNotEquals(base, tag(main, "t", "c", "entities", 10, 0, -1), "masking profile");
    }
}
