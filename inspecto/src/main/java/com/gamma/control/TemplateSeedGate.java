package com.gamma.control;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *       {@link DecisionRuleGuard}'s invoke-api gate for a {@code decision-rule} — judged against the NEW Space's own
 *       registry and data (a staged {@link ApiContext}), and a {@code .toon} the registry cannot read is refused;</li>
 *   <li>{@code kpi} — {@link KpiRoutes#requireTemplateKpis} (its capability and Measure check).</li>
 * </ol>
 * {@code checkCapability} false (the zero-Space recovery create) skips the capability half, as it asks none.
 * ⚠ Not gated here: a template's non-registry configs (Pipelines, Connections', Jobs' content) are not run through
 * {@code SaveGate} — only their capability.
 */
final class TemplateSeedGate {

    private TemplateSeedGate() {}

    static void require(ApiContext api, HttpExchange ex, Path spaceBase, boolean checkCapability) {
        Path config = spaceBase.resolve("config");
        if (checkCapability) {
            try {
                ImportCapabilityGuard.checkFiles(ex, configEntries(config), true);
            } catch (ApiException denied) {
                throw new ApiException(denied.status, denied.errorCode, "template refused: " + denied.getMessage()
                        + "; nothing was created");
            }
        }
        ApiContext staged = staged(api, config, spaceBase.resolve("data"));
        Path registry = config.resolve("registry");
        ComponentStore store = new ComponentStore(registry);
        for (String type : ComponentStore.WRITABLE_TYPES.stream().sorted().toList()) {
            if (KpiRoutes.TYPE.equals(type)) continue;   // its own gate, below
            Path dir = registry.resolve(ComponentRegistry.dirForType(type).orElse(type));
            if (!Files.isDirectory(dir)) continue;
            List<ComponentRegistry.Component> seeded = store.list(type);
            for (Path file : toonFiles(dir)) {
                ComponentRegistry.Component c = seeded.stream().filter(k -> file.equals(k.path())).findFirst()
                        .orElseThrow(() -> new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "template "
                                + type + " file '" + file.getFileName() + "' is unreadable; nothing was created"));
                try {
                    validate(staged, ex, type, c.name(), c.content());
                } catch (IllegalArgumentException bad) {
                    throw refused(type, c.name(), bad.getMessage());
                } catch (ApiException bad) {
                    if (bad.status == 403) throw bad;
                    throw refused(type, c.name(), bad.getMessage());
                }
            }
        }
        KpiRoutes.requireTemplateKpis(ex, spaceBase, checkCapability);
    }

    /** What {@code ComponentRoutes.writeComponent} runs before its write, over the content as stored. */
    private static void validate(ApiContext staged, HttpExchange ex, String type, String id, Map<String, Object> content) {
        Map<String, Object> body = new LinkedHashMap<>(content);
        ComponentRoutes.validateKind(staged, type, id, body);
        if (DecisionRuleGuard.TYPE.equals(type)) DecisionRuleGuard.prepare(ex, body, null, Map.of(), false);
        if ("alert-rule".equals(type)) {
            body.put("name", id);
            AlertRoutes.parse(staged, body);
        }
    }

    private static ApiException refused(String type, String id, String why) {
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "template " + type + " '" + id
                + "' is refused: " + why + "; nothing was created");
    }

    /** {@code api} with its write root and data root moved onto the staged Space. */
    private static ApiContext staged(ApiContext api, Path config, Path data) {
        return (ApiContext) Proxy.newProxyInstance(ApiContext.class.getClassLoader(), new Class<?>[]{ApiContext.class},
                (proxy, m, args) -> {
                    if (m.getParameterCount() == 0 && "writeRoot".equals(m.getName())) return config;
                    if (m.getParameterCount() == 0 && "dataRoot".equals(m.getName())) return data;
                    try {
                        return m.invoke(api, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
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

    private static List<Path> toonFiles(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".toon")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
