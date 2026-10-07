package com.gamma.entitystore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.gamma.entitystore.EntityFactLog;
import com.gamma.entitystore.EntityRegistry;

/** Test access to the Identity Fact log and its fold from other test packages. */
public final class EntityFactsForTest {

    private EntityFactsForTest() {}

    /** Append one fact as {@code actor}. */
    public static void append(Path root, String actor, String kind, String listId, Map<String, Object> payload)
            throws Exception {
        EntityFactLog log = new EntityFactLog(root);
        log.append(log.read(), actor, "test", kind, listId, payload);
    }

    /** Create a list of Entity Type {@code msisdn} (normaliser {@code e164}). */
    public static void create(Path root, String id, String purpose) throws Exception {
        append(root, "analyst-1", "list.created", id,
                Map.of("title", "T", "purpose", purpose, "entityType", "msisdn", "normaliser", "e164"));
    }

    /** Every fact body, in seq order. */
    public static List<Map<String, Object>> facts(Path root) throws Exception {
        return new EntityFactLog(root).read().facts().stream().map(EntityFactLog.Fact::body).toList();
    }

    /** The list's expiry per member key as of the head (absent = permanent). */
    public static Map<String, String> expiries(Path root, String id) throws Exception {
        EntityFactLog.Log head = new EntityFactLog(root).read();
        return EntityRegistry.fold(head.facts(), head.headSeq()).get(id).expiresAt();
    }
}
