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
 * {@code AbsentGeoLinkRoutes.SURFACE} must list EXACTLY the routes this module registers — both directions.
 *
 * <p><b>Why this test exists.</b> The core cannot see this module's classes (it declares no dependency on it,
 * deliberately: {@code inspecto-geo-link} is an optional edition module and Personal must not ship it). So the
 * core mirrors the module's public surface in a hand-kept table, and TWO separate mechanisms read that table
 * rather than the real routes:
 * <ul>
 *   <li>the 503 "not installed" stubs a Personal build serves, and</li>
 *   <li>the OpenAPI skeleton generator, which derives {@code docs/api/openapi-v1.json} from it.</li>
 * </ul>
 *
 * <p>🔴 <b>Measured 2026-09-22: the hand-kept table had drifted by four routes</b> —
 * {@code /inv/schema/overlap-profile} (LA-15) and the three {@code /inv/snapshots*} (LA-03). One omission
 * produced two silent failures: those paths 404ed instead of 503ing on Personal, AND they were absent from the
 * published API contract while the contract guard still reported green, because that guard enforces "every
 * LIVE route has an operation" where <em>live</em> means <em>what it can see</em>.
 *
 * <p>⛔ The class javadoc had warned to "keep both lists in sync" since 2026-09-07. It drifted anyway. A
 * comment asking two lists to be kept in step is not a mechanism; this test is.
 *
 * <p>⚠ It must live HERE, not in the core, because only a module test has both halves on one classpath.
 */
class GeoLinkAbsentSurfaceParityTest {

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
                if (regex.startsWith("/inv/") || regex.startsWith("/geo/")) out.add(method + " " + regex);
            }
        }
        return out;
    }

    private static Set<String> declaredSurface() {
        Set<String> out = new TreeSet<>();
        for (String[] r : AbsentGeoLinkRoutes.SURFACE) out.add(r[0] + " " + r[1]);
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
                "registered by inspecto-geo-link but NOT in AbsentGeoLinkRoutes.SURFACE — these 404 instead of "
                        + "503 on Personal, and are missing from docs/api/openapi-v1.json with the contract "
                        + "guard still green");
        assertEquals(Set.of(), staleInSurface,
                "declared in AbsentGeoLinkRoutes.SURFACE but no longer registered here — a stub outliving its "
                        + "route keeps a dead path in the published contract");
        assertEquals(declared, registered, "the two lists must be identical, not merely overlapping");
    }
}
