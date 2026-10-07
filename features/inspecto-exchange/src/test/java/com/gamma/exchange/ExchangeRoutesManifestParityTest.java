package com.gamma.exchange;

import com.gamma.control.ControlApi;
import com.gamma.control.testkit.ModuleRoutesParity;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes {@code ExchangeRoutes}
 * registers - both directions (MODULE-REORG-1 P3b); a route added here and not in the manifest 404s where the module is
 * absent instead of answering 503.
 *
 * <p>⚠ Unlike its siblings this one boots a real {@link ControlApi}: {@code ExchangeRoutes.register} installs host
 * services and cannot be driven on the test kit's fake context (MODULE-REORG-P5-TCKS). The module's routes are the
 * non-stubbed ones under {@code /exchange}.
 */
class ExchangeRoutesManifestParityTest {

    private static Set<String> registeredByThisModule(Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.clearProperty("assist.write.root");
        Set<String> out = new TreeSet<>();
        try (CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
             ControlApi api = new ControlApi(svc, 0)) {
            Field rf = ControlApi.class.getDeclaredField("routes");
            rf.setAccessible(true);
            Field sf = ControlApi.class.getDeclaredField("stubbedRoutes");
            sf.setAccessible(true);
            Set<?> stubbed = (Set<?>) sf.get(api);
            for (Object route : (List<?>) rf.get(api)) {
                var m = route.getClass().getDeclaredMethod("method");
                m.setAccessible(true);
                var p = route.getClass().getDeclaredMethod("pattern");
                p.setAccessible(true);
                String method = (String) m.invoke(route);
                String regex = ((Pattern) p.invoke(route)).pattern();
                if (regex.startsWith("^")) regex = regex.substring(1);
                if (regex.endsWith("$")) regex = regex.substring(0, regex.length() - 1);
                // With this module on the class path its real routes win registration, so anything still STUBBED
                // is the host's 503 stand-in for something else - never one of ours.
                if (stubbed.contains(method + " " + regex)) continue;
                if (regex.startsWith("/exchange")) out.add(method + " " + regex);
            }
        }
        return out;
    }

    @Test
    void theManifestSurfaceMatchesWhatThisModuleRegisters(@TempDir Path cfg) throws Exception {
        Set<String> registered = registeredByThisModule(cfg);
        Set<String> declared = new TreeSet<>(ModuleRoutesParity.declared(ExchangeRoutes.class));

        Set<String> missing = new TreeSet<>(registered);
        missing.removeAll(declared);
        Set<String> stale = new TreeSet<>(declared);
        stale.removeAll(registered);

        assertEquals(Set.of(), missing, "registered by inspecto-exchange but NOT in its module.toon provides.routes - "
                + "these 404 instead of 503 where the module is absent");
        assertEquals(Set.of(), stale, "declared in inspecto-exchange's module.toon provides.routes but no longer registered");
        assertEquals(declared, registered);
    }
}
