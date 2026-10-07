package com.gamma.control;

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
 * {@code AbsentRiskScoreRoutes.SURFACE} must list EXACTLY the routes this module registers — both directions.
 * The sibling of {@code GeoLinkAbsentSurfaceParityTest}, for the same reason.
 *
 * <p><b>Why this test exists.</b> The core cannot see this module's classes (it declares no dependency on it,
 * deliberately: {@code inspecto-scoring} is an optional edition module and Personal must not ship it). So the
 * core mirrors the module's public surface in a hand-kept table, and TWO separate mechanisms read that table
 * rather than the real routes: the 503 "not installed" stubs a Personal build serves, and the OpenAPI skeleton
 * generator, which derives {@code docs/api/openapi-v1.json} from the Personal route table. A route added to the
 * module and not to the table 404s on Personal AND is missing from the published contract with the contract guard
 * still green.
 *
 * <p>⚠ It must live HERE, not in the core, because only a module test has both halves on one classpath.
 */
class ScoringAbsentSurfaceParityTest {

    /** The (method, pattern) pairs this module actually registers, read off the live route table. */
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
                // With this module on the classpath its real routes win registration, so anything still
                // STUBBED is the core's 503 stand-in for something else — never one of ours.
                if (stubbed.contains(method + " " + regex)) continue;
                if (regex.startsWith("/risk-scores")) out.add(method + " " + regex);
            }
        }
        return out;
    }

    private static Set<String> declaredSurface() {
        Set<String> out = new TreeSet<>();
        for (String[] r : AbsentRiskScoreRoutes.SURFACE) out.add(r[0] + " " + r[1]);
        return out;
    }

    @Test
    void theAbsentSurfaceMatchesWhatThisModuleRegisters(@TempDir Path cfg) throws Exception {
        Set<String> registered = registeredByThisModule(cfg);
        Set<String> declared = declaredSurface();

        Set<String> missingFromSurface = new TreeSet<>(registered);
        missingFromSurface.removeAll(declared);
        Set<String> staleInSurface = new TreeSet<>(declared);
        staleInSurface.removeAll(registered);

        assertEquals(Set.of(), missingFromSurface,
                "registered by inspecto-scoring but NOT in AbsentRiskScoreRoutes.SURFACE — these 404 instead of "
                        + "503 on Personal, and are missing from docs/api/openapi-v1.json with the contract "
                        + "guard still green");
        assertEquals(Set.of(), staleInSurface,
                "declared in AbsentRiskScoreRoutes.SURFACE but no longer registered here — a stub outliving its "
                        + "route keeps a dead path in the published contract");
        assertEquals(declared, registered, "the two lists must be identical, not merely overlapping");
    }
}
