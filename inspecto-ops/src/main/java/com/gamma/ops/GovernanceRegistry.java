package com.gamma.ops;

import com.gamma.objects.EscalationRule;
import com.gamma.objects.ObjectType;
import com.gamma.objects.SlaPolicy;
import com.gamma.objects.Workflow;
import com.gamma.util.ToonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * The Space's authored governance — {@code registry/workflows/}, {@code registry/sla-policies/},
 * {@code registry/escalation-rules/} (ASSURE-WORKFLOW-SLA-1) — re-read whenever those files change, so a saved
 * (or approved, or restored) Workflow takes effect on the next transition with no restart.
 *
 * <p>Change detection is per FILE, by last-modified time and size: each {@link #snapshot()} lists the three
 * directories and re-parses only a file whose stamp moved, whichever door wrote it (the component route, a
 * maker-checker approval replaying it, a restore, a hand edit).
 *
 * <p>⚠ Every file is validated again at load. One that fails (hand-edited, or saved before a rule tightened) is
 * warned about and its <b>last valid version stays in force</b> — a broken edit never silently drops the authored
 * layer back to {@code *_workflow.toon} or the built-in. A file that was never valid contributes nothing. At most
 * {@link #MAX_RULES} Escalation Rules are loaded (by id), so a runaway directory cannot turn one sweep into an
 * unbounded fan-out.
 */
final class GovernanceRegistry {

    private static final Logger log = LoggerFactory.getLogger(GovernanceRegistry.class);
    static final int MAX_RULES = 50;

    record Snapshot(Map<ObjectType, Workflow> workflows, Map<ObjectType, SlaPolicy> slaPolicies,
                    List<EscalationRule> escalationRules) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), List.of());
    }

    /** One file's stamp and the last version of it that validated ({@code null} = never valid). */
    private record Entry(long mtime, long size, Object lastValid) {}

    private final Path registryRoot;
    private final Map<Path, Entry> files = new HashMap<>();
    private Snapshot current = Snapshot.EMPTY;
    private boolean loaded;

    GovernanceRegistry(Path registryRoot) {
        this.registryRoot = registryRoot;
    }

    /** The governance currently on disk, re-reading only the files that changed since the last call. */
    synchronized Snapshot snapshot() {
        boolean changed = !loaded;
        Map<String, Object> workflows = new TreeMap<>();
        Map<String, Object> policies = new TreeMap<>();
        Map<String, Object> rules = new TreeMap<>();
        java.util.Set<Path> seen = new java.util.HashSet<>();
        changed |= scan("workflows", Workflow::fromComponent, workflows, seen);
        changed |= scan("sla-policies", SlaPolicy::fromComponent, policies, seen);
        changed |= scan("escalation-rules", EscalationRule::fromComponent, rules, seen);
        changed |= files.keySet().retainAll(seen);
        if (changed) current = build(workflows, policies, rules);
        loaded = true;
        return current;
    }

    private boolean scan(String dir, BiFunction<String, Map<String, Object>, Object> parse, Map<String, Object> out,
                         java.util.Set<Path> seen) {
        Path d = registryRoot.resolve(dir);
        if (!Files.isDirectory(d)) return false;
        boolean changed = false;
        List<Path> list;
        try (Stream<Path> s = Files.list(d)) {
            list = s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".toon")).toList();
        } catch (IOException e) {
            log.warn("Could not list governance directory {}: {}", d, e.getMessage());
            return false;
        }
        for (Path f : list) {
            seen.add(f);
            String id = f.getFileName().toString().replaceFirst("\\.toon$", "");
            long mtime, size;
            try {
                mtime = Files.getLastModifiedTime(f).toMillis();
                size = Files.size(f);
            } catch (IOException e) {
                continue;
            }
            Entry e = files.get(f);
            if (e == null || e.mtime() != mtime || e.size() != size) {
                Object valid = e == null ? null : e.lastValid();
                try {
                    valid = parse.apply(id, ToonHelper.load(f.toString()));
                } catch (Exception bad) {
                    log.warn("Invalid governance file {} — {}: {}", f,
                            valid == null ? "not served" : "its last valid version stays in force", bad.getMessage());
                }
                e = new Entry(mtime, size, valid);
                files.put(f, e);
                changed = true;
            }
            if (e.lastValid() != null) out.put(id, e.lastValid());
        }
        return changed;
    }

    private static Snapshot build(Map<String, Object> wfs, Map<String, Object> pols, Map<String, Object> rs) {
        Map<ObjectType, Workflow> workflows = new EnumMap<>(ObjectType.class);
        for (Object o : wfs.values()) workflows.put(((Workflow) o).objectType(), (Workflow) o);
        Map<ObjectType, SlaPolicy> policies = new EnumMap<>(ObjectType.class);
        for (Object o : pols.values()) policies.put(((SlaPolicy) o).objectType(), (SlaPolicy) o);
        List<EscalationRule> rules = new ArrayList<>();
        for (Map.Entry<String, Object> r : rs.entrySet()) {       // sorted by id (TreeMap)
            if (rules.size() == MAX_RULES) {
                log.warn("More than {} Escalation Rules — {} and later are not loaded", MAX_RULES, r.getKey());
                break;
            }
            rules.add((EscalationRule) r.getValue());
        }
        return new Snapshot(Map.copyOf(workflows), Map.copyOf(policies), List.copyOf(rules));
    }
}
