package com.gamma.ops;

import com.gamma.objects.EscalationRule;
import com.gamma.objects.ObjectType;
import com.gamma.objects.SlaPolicy;
import com.gamma.objects.Workflow;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * The Space's authored governance — {@code registry/workflows/}, {@code registry/sla-policies/},
 * {@code registry/escalation-rules/} (ASSURE-WORKFLOW-SLA-1) — re-read whenever those files change, so a saved
 * (or approved, or restored) Workflow takes effect on the next transition with no restart.
 *
 * <p>Change detection is a content checksum of the three directories' files, taken on every {@link #snapshot()}:
 * whichever door wrote the file (the component route, a maker-checker approval replaying it, a restore, a hand edit)
 * the next read sees it. The directories hold a handful of small files, so the check is cheap.
 *
 * <p>⚠ Every file is validated again at load: one that fails (hand-edited, or saved before a rule tightened) is
 * warned about and SKIPPED — the object type falls back to its {@code *_workflow.toon} or built-in workflow — rather
 * than served. At most {@link #MAX_RULES} Escalation Rules are loaded (by id), so a runaway directory cannot turn one
 * sweep into an unbounded fan-out.
 */
final class GovernanceRegistry {

    private static final Logger log = LoggerFactory.getLogger(GovernanceRegistry.class);
    static final int MAX_RULES = 50;
    private static final List<String> DIRS = List.of("workflows", "sla-policies", "escalation-rules");

    record Snapshot(Map<ObjectType, Workflow> workflows, Map<ObjectType, SlaPolicy> slaPolicies,
                    List<EscalationRule> escalationRules) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), List.of());
    }

    private final Path registryRoot;
    private long fingerprint = Long.MIN_VALUE;
    private Snapshot current = Snapshot.EMPTY;

    GovernanceRegistry(Path registryRoot) {
        this.registryRoot = registryRoot;
    }

    /** The governance currently on disk, re-read if any of its files changed since the last call. */
    synchronized Snapshot snapshot() {
        long fp = fingerprint();
        if (fp != fingerprint) {
            current = load();
            fingerprint = fp;
        }
        return current;
    }

    private long fingerprint() {
        CRC32 crc = new CRC32();
        for (String dir : DIRS) {
            Path d = registryRoot.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> files = Files.list(d)) {
                for (Path f : files.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList()) {
                    crc.update(f.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    crc.update(Files.readAllBytes(f));
                }
            } catch (IOException e) {
                log.warn("Could not read governance directory {}: {}", d, e.getMessage());
            }
        }
        return crc.getValue();
    }

    private Snapshot load() {
        ComponentStore store = new ComponentStore(registryRoot);
        Map<ObjectType, Workflow> workflows = new EnumMap<>(ObjectType.class);
        for (ComponentRegistry.Component c : store.list("workflow")) {
            try {
                Workflow w = Workflow.fromComponent(c.name(), c.content());
                workflows.put(w.objectType(), w);
            } catch (RuntimeException e) {
                log.warn("Skipping invalid workflow component {}: {}", c.path(), e.getMessage());
            }
        }
        Map<ObjectType, SlaPolicy> policies = new EnumMap<>(ObjectType.class);
        for (ComponentRegistry.Component c : store.list("sla-policy")) {
            try {
                SlaPolicy p = SlaPolicy.fromComponent(c.name(), c.content());
                policies.put(p.objectType(), p);
            } catch (RuntimeException e) {
                log.warn("Skipping invalid sla-policy component {}: {}", c.path(), e.getMessage());
            }
        }
        List<EscalationRule> rules = new ArrayList<>();
        List<ComponentRegistry.Component> authored = new ArrayList<>(store.list("escalation-rule"));
        authored.sort(Comparator.comparing(ComponentRegistry.Component::name));
        for (ComponentRegistry.Component c : authored) {
            if (rules.size() == MAX_RULES) {
                log.warn("More than {} Escalation Rules — {} and later are not loaded", MAX_RULES, c.name());
                break;
            }
            try {
                rules.add(EscalationRule.fromComponent(c.name(), c.content()));
            } catch (RuntimeException e) {
                log.warn("Skipping invalid escalation-rule component {}: {}", c.path(), e.getMessage());
            }
        }
        return new Snapshot(Map.copyOf(workflows), Map.copyOf(policies), List.copyOf(rules));
    }
}
