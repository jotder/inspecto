package com.gamma.entitylist;

import com.gamma.control.EntityTypes;
import com.gamma.risk.WatchListFeed;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Risk Score watch-list feed (ASSURE-ENTITY-LISTS-1, WS-12 x WS-22): the one {@link WatchListFeed}, over this
 * module's Identity Fact log. A {@code risk.score} run appends ONE {@code list.member.added} fact with every newly
 * high entity key, expiring at most 24 h later. Under D-P5 an expiring entry applies at once and is reviewed after,
 * so a Job needs no Pending Change. A key already on the list PERMANENTLY stays permanent; an expiring one has its
 * expiry moved to the new run's.
 */
public final class RiskWatchListFeed implements WatchListFeed {

    @Override
    public void check(Path writeRoot, String listId) throws IOException {
        EntityFactLog.Log head = new EntityFactLog(writeRoot).read();
        watchList(EntityRegistry.fold(head.facts(), head.headSeq()).get(listId), listId, writeRoot);
    }

    @Override
    public int feed(Path writeRoot, Path dataDir, String listId, Collection<String> keys, Instant expiresAt, String actor,
                    String reason) throws IOException {
        if (!expiresAt.isAfter(Instant.now()) || expiresAt.isAfter(Instant.now().plus(java.time.Duration.ofHours(24))))
            throw new IllegalArgumentException("a fed watch entry expires within 1..24 h (D-P5)");
        EntityFactLog log = new EntityFactLog(writeRoot);
        synchronized (log.lock()) {
            EntityFactLog.Log head = log.read();
            EntityRegistry.EntityList l = watchList(EntityRegistry.fold(head.facts(), head.headSeq()).get(listId), listId,
                    writeRoot);
            String at = expiresAt.toString();
            Set<String> add = new TreeSet<>();
            for (String raw : keys) {
                String k = EntityTypes.normalise(l.normaliser(), raw);
                if (k.isEmpty()) continue;
                if (l.members().contains(k) && (l.expiresAt().get(k) == null || at.equals(l.expiresAt().get(k)))) continue;
                add.add(k);
            }
            if (add.isEmpty()) return 0;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("keys", List.copyOf(add));
            payload.put("expiresAt", at);
            head = log.append(head, actor, reason, "list.member.added", listId, payload);
            EntityListRoutes.emit(actor, "job", listId, "list.member.added", add.size(), 0, head.headSeq());
            EntityListSidecar.write(dataDir, EntityRegistry.fold(head.facts(), head.headSeq()).get(listId), head.facts());
            return add.size();
        }
    }

    private static EntityRegistry.EntityList watchList(EntityRegistry.EntityList l, String listId, Path writeRoot) {
        if (l == null) throw new IllegalArgumentException("watch list '" + listId + "' is not an Entity List of this Space");
        if (!"watch".equals(l.purpose()))
            throw new IllegalArgumentException("Entity List '" + listId + "' has purpose '" + l.purpose()
                    + "'; a Risk Score feeds only a 'watch' list");
        if (l.retired()) throw new IllegalArgumentException("Entity List '" + listId + "' is retired");
        if (EntityListFacts.type(writeRoot, l.entityType()).isEmpty())
            throw new IllegalArgumentException("Entity List '" + listId + "' is of an Entity Type no longer in force");
        return l;
    }
}
