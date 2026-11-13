package com.gamma.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SE-1b (D-SE12) — what one Space lock computation knows about each Pipeline and Job that the activation holds act
 * on: the DANGLING links its own file holds, and whether its file is marked changed outside the server and not yet
 * accepted. Built from the full lists (never the capped report lists), kept on the {@link SpaceLock.Report} in memory,
 * never written to the lock. Only objects with at least one fact are kept, so a clean Space carries an empty map.
 */
public record ActivationFacts(Map<String, Entry> byObject) {

    /** The kinds a hold applies to: the two the schedulers run. */
    public static final Set<String> KINDS = Set.of("pipeline", "job");

    public static final ActivationFacts NONE = new ActivationFacts(Map.of());

    /**
     * One object: {@code dangling} = {@code {path, key, value, toKind}} per dangling link its file holds;
     * {@code outside} = {@code {path, change}} when its file is changed outside the server and not accepted.
     */
    public record Entry(String kind, String name, List<Map<String, Object>> dangling, List<Map<String, Object>> outside) {}

    /** The facts of {@code name} of {@code kind}, or {@code null} when the lock holds none. */
    public Entry of(String kind, String name) {
        return name == null ? null : byObject.get(key(kind, name));
    }

    public boolean isEmpty() {
        return byObject.isEmpty();
    }

    /** Case-insensitive, a Pipeline name's spaces read as {@code _} — how the lock and the loader spell its id. */
    static String key(String kind, String name) {
        return kind + ":" + name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    /** From one computation's {@code files} (with their {@code outside} marks) and {@code links}. */
    static ActivationFacts of(List<Map<String, Object>> files, List<Map<String, Object>> links) {
        Map<String, Map<String, Object>> fileByPath = new HashMap<>();
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Map<String, Object> f : files) {
            String kind = String.valueOf(f.get("kind"));
            if (!KINDS.contains(kind) || f.get("id") == null) continue;
            fileByPath.put(String.valueOf(f.get("path")), f);
            if (f.get("outside") instanceof Map<?, ?> o) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", f.get("path"));
                m.put("change", o.get("change"));
                entry(out, kind, String.valueOf(f.get("id"))).outside().add(m);
            }
        }
        for (Map<String, Object> l : links) {
            if (!"DANGLING".equals(l.get("status"))) continue;
            Map<String, Object> owner = fileByPath.get(String.valueOf(l.get("from")));
            if (owner == null || !String.valueOf(owner.get("kind")).equals(l.get("fromKind"))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", l.get("from"));
            m.put("key", l.get("key"));
            m.put("value", l.get("value"));
            m.put("toKind", l.get("toKind"));
            entry(out, String.valueOf(owner.get("kind")), String.valueOf(owner.get("id"))).dangling().add(m);
        }
        return out.isEmpty() ? NONE : new ActivationFacts(Map.copyOf(out));
    }

    private static Entry entry(Map<String, Entry> out, String kind, String name) {
        return out.computeIfAbsent(key(kind, name), k -> new Entry(kind, name, new ArrayList<>(), new ArrayList<>()));
    }
}
