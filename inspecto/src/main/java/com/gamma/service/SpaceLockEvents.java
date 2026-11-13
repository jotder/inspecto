package com.gamma.service;

import com.gamma.audit.Event;
import com.gamma.audit.EventLevel;
import com.gamma.audit.EventType;
import com.gamma.event.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LC-4 / LC-6 of `SPACE-LINK-CENSUS-1` — the events of a Space lock computation ({@code spec-space-lock.md} §11):
 * one {@link EventType#CONFIG_LINK_BROKEN} per DANGLING link the previous lock did not hold (same file, key and
 * value), and one {@link EventType#CONFIG_CHANGED_OUTSIDE} per file this computation marked as changed outside the
 * server. The previous lock is the memory: a finding already in it is never emitted again, so a steady Space emits
 * nothing. With no previous lock (first boot, or an unverifiable one) every broken link is new; no file is marked
 * outside then. At most {@link SpaceLock#CAP} events of each type per computation; the rest are counted in the log.
 * Never throws: an event sink failure leaves the lock as computed.
 */
final class SpaceLockEvents {

    private static final Logger log = LoggerFactory.getLogger(SpaceLockEvents.class);

    private SpaceLockEvents() {}

    /** {@code prev}: the previous verified lock or null; the rest is what this computation produced. */
    static void emit(String spaceId, Map<String, Object> prev, List<Map<String, Object>> files,
                     List<Map<String, Object>> removed, List<Map<String, Object>> links, String nowIso) {
        try {
            List<Event.Builder> broken = new ArrayList<>(), outside = new ArrayList<>();
            Set<String> before = new HashSet<>();
            if (prev != null && prev.get("links") instanceof List<?> l)
                for (Object o : l) if (o instanceof Map<?, ?> m && "DANGLING".equals(m.get("status"))) before.add(key(m));
            Map<String, Integer> brokenByFile = new HashMap<>();
            for (Map<String, Object> link : links) {
                if (!"DANGLING".equals(link.get("status"))) continue;
                brokenByFile.merge(String.valueOf(link.get("from")), 1, Integer::sum);
                if (before.contains(key(link))) continue;
                broken.add(Event.builder(EventType.CONFIG_LINK_BROKEN).level(EventLevel.WARN).source("space-lock")
                        .message(link.get("fromKind") + " '" + link.get("fromName") + "' (" + link.get("from") + ") links "
                                + link.get("key") + " to " + link.get("toKind") + " '" + link.get("value")
                                + "', which does not exist in Space " + spaceId)
                        .attr("space", spaceId).attr("path", link.get("from")).attr("fromKind", link.get("fromKind"))
                        .attr("fromName", link.get("fromName")).attr("key", link.get("key"))
                        .attr("toKind", link.get("toKind")).attr("target", link.get("value")));
            }
            for (Map<String, Object> f : files)
                if (f.get("outside") instanceof Map<?, ?> o && nowIso.equals(o.get("at")))
                    outside.add(changed(spaceId, f, String.valueOf(o.get("change")), brokenByFile));
            for (Map<String, Object> r : removed)
                if (nowIso.equals(r.get("at"))) outside.add(changed(spaceId, r, "removed", brokenByFile));
            if (broken.isEmpty() && outside.isEmpty()) return;

            String prior = MDC.get(EventLog.SPACE_MDC_KEY);
            MDC.put(EventLog.SPACE_MDC_KEY, spaceId);   // the lock thread carries no Space: route to this Space's log
            try {
                send(spaceId, EventType.CONFIG_LINK_BROKEN, broken);
                send(spaceId, EventType.CONFIG_CHANGED_OUTSIDE, outside);
            } finally {
                if (prior == null) MDC.remove(EventLog.SPACE_MDC_KEY);
                else MDC.put(EventLog.SPACE_MDC_KEY, prior);
            }
        } catch (RuntimeException ex) {
            log.warn("[SPACE-LOCK] could not emit the integrity events of Space '{}': {}", spaceId, ex.toString());
        }
    }

    private static Event.Builder changed(String spaceId, Map<String, Object> f, String change, Map<String, Integer> brokenByFile) {
        String path = String.valueOf(f.get("path"));
        int brokenLinks = "removed".equals(change) ? 0 : brokenByFile.getOrDefault(path, 0);
        return Event.builder(EventType.CONFIG_CHANGED_OUTSIDE).level(EventLevel.INFO).source("space-lock")
                .message(path + " was " + change + " outside the server in Space " + spaceId
                        + (brokenLinks > 0 ? "; it now holds " + brokenLinks + " broken link(s)" : ""))
                .attr("space", spaceId).attr("path", path).attr("kind", f.get("kind")).attr("id", f.get("id"))
                .attr("change", change).attr("brokenLinks", String.valueOf(brokenLinks));
    }

    private static void send(String spaceId, String type, List<Event.Builder> events) {
        EventLog sink = EventLog.current();
        int n = Math.min(events.size(), SpaceLock.CAP);
        for (int i = 0; i < n; i++) sink.emit(events.get(i));
        if (events.size() > n)
            log.warn("[SPACE-LOCK] Space '{}': {} more {} finding(s) not emitted as events (cap {}); see /integrity",
                    spaceId, events.size() - n, type, SpaceLock.CAP);
    }

    private static String key(Map<?, ?> link) {
        return link.get("from") + "\u0000" + link.get("key") + "\u0000" + link.get("value");
    }
}
