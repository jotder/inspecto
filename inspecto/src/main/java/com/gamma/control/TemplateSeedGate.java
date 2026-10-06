package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A Space Template's seed gate ({@code ASSURE-KPI-DEFINITIONS-RESIDUALS-1} (1)): run by
 * {@code SpaceManager.createFromTemplate} over the staged Space once the template's whole tree is copied and before
 * it boots. <b>A template is never a way around a kind's own route.</b> Every refusal refuses the whole template and
 * no Space is created:
 * <ol>
 *   <li>capability — {@link ImportCapabilityGuard#checkFiles} over the staged {@code config/} tree, exactly as
 *       {@code /spaces/import} runs it (the same kind→capability table: {@code connection}, {@code alert-rule},
 *       {@code findings-spec}, administer-only Jobs; a {@code registry/} file whose kind cannot be told is refused);</li>
 *   <li>save validation — every registry component the template seeds meets what {@code /components/{kind}} runs
 *       before its write: {@link ComponentRoutes#validateKind}, {@link AlertRoutes#parse} for an {@code alert-rule},
 *       {@link DecisionRuleGuard#prepare} for a {@code decision-rule} (its invoke-api gate, with the Connections the
 *       template itself carries counted as registered) — judged against the NEW Space's own registry and data, and a
 *       registry file (of the kind's own suffix, {@code .csv} for a mapping) the store cannot read is refused. A
 *       seeded Decision Rule is rewritten with the stamps {@code prepare} returns: the applying actor is its creator
 *       and maker, and the template file's {@code createdBy} / {@code updatedBy} / {@code restoredMakers} are dropped
 *       (template content is not a record of who made it);</li>
 *   <li>{@code kpi} — {@link KpiRoutes#requireTemplateKpis} (its capability and Measure check).</li>
 * </ol>
 * Every step runs on every create, the zero-Space recovery create included ({@code TEMPLATE-RECOVERY-IMPORT-GATE-1},
 * operator 2026-10-03: that create is Space governance and needs {@code canAdminister} like the rest, so there is
 * no capability-less applier left to exempt). With no Subject (Personal) every capability check is a no-op.
 * Pipelines and Jobs are additionally run through {@link SaveGate} with referents deferred
 * ({@code ASSURE-KPI-DEFINITIONS-RESIDUALS-1}), and every Connection file through
 * {@link ConnectionRoutes#validateProfile} (its connector checks and the https egress host check),
 * and every deferred Alert Rule ({@code pending/alert-rules/}) through {@link PendingAlertRules#requireDeclarable}.
 */
final class TemplateSeedGate {

    private TemplateSeedGate() {}

    static void require(HttpExchange ex, Path spaceBase) {
        Path config = spaceBase.resolve("config");
        try {
            ImportCapabilityGuard.checkFiles(ex, configEntries(config), true);
        } catch (ApiException denied) {
            throw new ApiException(denied.status, denied.errorCode, "template refused: " + denied.getMessage()
                    + "; nothing was created");
        }
        Path data = spaceBase.resolve("data");
        Supplier<Path> dataRoot = () -> data;
        Map<String, String> carried = DecisionRuleGuard.carriedConnections(configEntries(config));
        Path registry = config.resolve("registry");
        ComponentStore store = new ComponentStore(registry);
        for (String type : ComponentStore.WRITABLE_TYPES.stream().sorted().toList()) {
            if (KpiRoutes.TYPE.equals(type)) continue;   // its own gate, below
            Path dir = registry.resolve(ComponentRegistry.dirForType(type).orElse(type));
            if (!Files.isDirectory(dir)) continue;
            List<ComponentRegistry.Component> seeded = store.list(type);
            for (Path file : files(dir, ComponentStore.suffixFor(type))) {
                ComponentRegistry.Component c = seeded.stream().filter(k -> file.equals(k.path())).findFirst()
                        .orElseThrow(() -> new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "template "
                                + type + " file '" + file.getFileName() + "' is unreadable; nothing was created"));
                try {
                    validate(config, dataRoot, carried, ex, type, c);
                } catch (IllegalArgumentException bad) {
                    throw refused(type, c.name(), bad.getMessage());
                } catch (ApiException bad) {
                    if (bad.status == 403) throw bad;
                    throw refused(type, c.name(), bad.getMessage());
                }
            }
        }
        KpiRoutes.requireTemplateKpis(ex, spaceBase);
        requirePendingAlertRules(config);
        requireStructure(config);
        stampJobs(ex, config);   // T5 owner-spoofing: a seeded la.index.build Job is owned by the applier
        requireConnections(config);
    }

    /**
     * Each deferred Alert Rule ({@code pending/alert-rules/}, {@code TEMPLATE-RISK-SCORE-ALERT-RULE-1}) meets what can
     * be judged before its Risk Score runs: the edition carries Alert Rules, it parses, and it reads the
     * {@code _latest} output of a Risk Score model this template seeds. Its {@code by} Schema check runs when it is
     * created ({@link PendingAlertRules#onRiskScoreProduced}).
     */
    private static void requirePendingAlertRules(Path config) {
        Path dir = config.resolve(PendingAlertRules.DIR);
        if (!Files.isDirectory(dir)) return;
        for (Path file : files(dir, ".toon")) {
            String name = file.getFileName().toString().replaceFirst("\\.toon$", "");
            if (!com.gamma.etl.EditionFeatures.present(com.gamma.etl.EditionFeatures.ALERT_DISPATCH))
                throw refused("pending alert-rule", name, com.gamma.etl.EditionFeatures.refusal(
                        com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
            try {
                PendingAlertRules.requireDeclarable(config, com.gamma.util.ToonHelper.load(file.toString()), name);
            } catch (IOException | RuntimeException bad) {
                throw refused("pending alert-rule", name, bad.getMessage());
            }
        }
    }

    /** Server-stamp every seeded {@code *_job.toon} as the other Job doors do ({@link JobAuthority#stampFiles}); rewritten only when changed. */
    private static void stampJobs(HttpExchange ex, Path config) {
        Map<String, byte[]> entries = configEntries(config);
        Map<String, byte[]> stamped = JobAuthority.stampFiles(ex, entries);
        if (stamped == entries) return;
        try {
            for (Map.Entry<String, byte[]> e : stamped.entrySet())
                if (!java.util.Arrays.equals(e.getValue(), entries.get(e.getKey())))
                    Files.write(config.resolve(e.getKey()), e.getValue());
        } catch (IOException io) {
            throw new UncheckedIOException(io);
        }
    }

    /** Each {@code *_connection.toon} meets what a Connection save runs ({@link ConnectionRoutes#validateProfile}). */
    private static void requireConnections(Path config) {
        for (Path file : configFiles(config)) {
            String name = file.getFileName().toString();
            if (!name.endsWith("_connection.toon")) continue;
            try {
                Object block = com.gamma.util.ToonHelper.load(file.toString()).get("connection");
                @SuppressWarnings("unchecked")
                Map<String, Object> c = block instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
                ConnectionRoutes.validateProfile(com.gamma.acquire.ConnectionProfile.fromMap(c));
            } catch (IOException | RuntimeException bad) {
                throw refused("connection", name, bad.getMessage());
            }
        }
    }

    /**
     * The content gate every save path runs ({@link SaveGate}) over each staged Pipeline ({@code *_pipeline.toon})
     * and Job ({@code jobs/*.toon}), with referents DEFERRED ({@link SaveGate.Referents#MAY_ARRIVE_LATER}): a
     * template's Connections are filled in later, so a missing one is not a defect — a structurally invalid config is.
     */
    private static void requireStructure(Path config) {
        for (Path file : configFiles(config)) {
            String name = file.getFileName().toString();
            Path parent = file.getParent();
            String type = parent.equals(config.resolve("jobs")) && name.endsWith(".toon") ? "job"
                    : name.endsWith("_pipeline.toon") && !parent.startsWith(config.resolve("registry")) ? "pipeline" : null;
            if (type == null) continue;
            Map<String, Object> draft;
            try {
                draft = com.gamma.config.io.ConfigLoader.filesystem().decode(file.toString());
            } catch (IOException | RuntimeException unreadable) {
                throw refused(type, name, unreadable.getMessage());
            }
            List<com.gamma.config.spec.Finding> findings = SaveGate.check(null, type, draft, config, parent,
                    SaveGate.Referents.MAY_ARRIVE_LATER);
            for (com.gamma.config.spec.Finding f : findings)
                if (f.severity() == com.gamma.config.spec.Severity.ERROR)
                    throw refused(type, name, f.fieldPath() + ": " + f.message());
        }
    }

    private static List<Path> configFiles(Path config) {
        if (!Files.isDirectory(config)) return List.of();
        try (Stream<Path> walk = Files.walk(config)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What {@code ComponentRoutes.writeComponent} runs before its write, over the content as stored. */
    private static void validate(Path config, Supplier<Path> dataRoot, Map<String, String> carried, HttpExchange ex,
                                 String type, ComponentRegistry.Component c) {
        String id = c.name();
        Map<String, Object> body = new LinkedHashMap<>(c.content());
        ComponentRoutes.validateKind(config, dataRoot, type, id, body);
        if (DecisionRuleGuard.TYPE.equals(type)) {
            body.remove("createdBy");
            body.remove("updatedBy");
            body.remove(DecisionRuleGuard.RESTORED_MAKERS);
            body.remove(DecisionRuleGuard.MAKERS);
            Map<String, Object> stamped = DecisionRuleGuard.prepare(ex, body, null, carried, false, null, id);
            try {
                Files.writeString(c.path(), ConfigCodec.toToon(stamped));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if ("alert-rule".equals(type)) {
            body.put("name", id);
            AlertRoutes.parse(config, dataRoot, body);
        }
    }

    private static ApiException refused(String type, String id, String why) {
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "template " + type + " '" + id
                + "' is refused: " + why + "; nothing was created");
    }

    /** config-relative path ({@code /}-separated) → bytes, the shape {@link ImportCapabilityGuard#checkFiles} judges. */
    private static Map<String, byte[]> configEntries(Path config) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (!Files.isDirectory(config)) return out;
        try (Stream<Path> walk = Files.walk(config)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList())
                out.put(config.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static List<Path> files(Path dir, String suffix) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
