package com.gamma.notify;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-reader read state for the in-app feed (operator decision 2026-09-25): each reader — the authenticated
 * Subject, or on Personal the fallback {@code appUser} / {@code X-Actor} identity — marks ITS OWN
 * notifications read or unread, so one reader's read never changes another's feed or badge. The
 * {@link NotificationStore} stays the one shared feed; this is an overlay keyed by {@code (reader, id)}.
 *
 * <p>Held in memory, like the {@link InMemoryNotificationStore} it overlays — the two share a lifecycle, so
 * a restart forgets the feed and its read marks together. Each reader keeps at most {@link #MAX_PER_READER}
 * marks (the feed's own bound), oldest-read evicted first; an evicted mark reads as unread again.
 *
 * <p>Thread-safe: all access is {@code synchronized}.
 *
 * @since 4.0.0
 */
public final class NotificationReadState {

    /** Matches {@link InMemoryNotificationStore}'s feed bound — a reader cannot have read more than the feed holds. */
    static final int MAX_PER_READER = 1000;

    private final Map<String, LinkedHashMap<String, Long>> byReader = new HashMap<>();

    /** When {@code reader} read notification {@code id} (epoch millis), or {@code null} while unread to them. */
    public synchronized Long readAt(String reader, String id) {
        Map<String, Long> reads = byReader.get(reader);
        return reads == null ? null : reads.get(id);
    }

    /** Mark {@code id} read for {@code reader} at {@code at}; {@code false} if it already was (the first time is kept). */
    public synchronized boolean markRead(String reader, String id, long at) {
        LinkedHashMap<String, Long> reads = byReader.computeIfAbsent(reader, r -> new LinkedHashMap<>());
        if (reads.containsKey(id)) return false;
        reads.put(id, at);
        if (reads.size() > MAX_PER_READER) {
            var it = reads.keySet().iterator();
            it.next();
            it.remove();   // evict the oldest read (insertion order)
        }
        return true;
    }

    /** Mark {@code id} unread for {@code reader}; {@code false} if it already was. */
    public synchronized boolean markUnread(String reader, String id) {
        Map<String, Long> reads = byReader.get(reader);
        return reads != null && reads.remove(id) != null;
    }

    /** Mark every id in {@code ids} read for {@code reader}; returns how many were newly read. */
    public synchronized int markAllRead(String reader, Collection<String> ids, long at) {
        int changed = 0;
        for (String id : ids) if (markRead(reader, id, at)) changed++;
        return changed;
    }
}
