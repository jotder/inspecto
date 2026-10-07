package com.gamma.control;

import com.gamma.module.KnownModules;
import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MODULE-REORG-1 P3b: the known-modules copy the processor ships, and the stub surface read from it. The 503 bodies and
 * statuses themselves are pinned by the {@code No*Ships*} tests over real HTTP; this class pins what feeds them.
 */
class AbsentModuleRoutesTest {

    private static Map<String, ModuleManifest> sourceTreeManifests() throws IOException {
        Map<String, ModuleManifest> out = new TreeMap<>();
        for (Path module : ReactorModules.modules()) {
            Path f = module.resolve("src/main/resources").resolve(ModuleManifests.RESOURCE);
            if (!Files.isRegularFile(f)) continue;
            for (String chunk : Files.readString(f).split("(?m)^" + ModuleManifests.SEPARATOR + "[ \\t]*\\r?$")) {
                if (chunk.isBlank()) continue;
                ModuleManifest m = ModuleManifests.parse(chunk.strip());
                out.put(m.id(), m);
            }
        }
        return out;
    }

    @Test
    void everyModuleManifestInTheSourceTreeHasAnIdenticalCopyInKnownModules() throws IOException {
        Map<String, ModuleManifest> source = sourceTreeManifests();
        assertTrue(source.size() > 30, "reactor discovery found only " + source.keySet());
        ModuleManifests.Loaded known = KnownModules.load(AbsentModuleRoutesTest.class.getClassLoader());
        assertEquals(List.of(), known.diagnostics());
        Map<String, ModuleManifest> copies = new TreeMap<>();
        for (ModuleManifest m : known.manifests()) copies.put(m.id(), m);
        assertEquals(source.keySet(), copies.keySet(),
                "known-modules (tools/KnownModules.java, run from inspecto/pom.xml) must hold exactly the module.toon files of the tree");
        source.forEach((id, m) -> assertEquals(m, copies.get(id), "stale known-modules copy of " + id));
    }

    @Test
    void theSurfaceIsReadFromTheManifestsOfTheOptionalModules() {
        assertEquals(69 + 6, AbsentModuleRoutes.surface("la-api", "geo-link").size());
        assertEquals(List.of("GET /risk-scores/([^/]+)/([^/]+)", "POST /risk-scores/preview"),
                AbsentModuleRoutes.surface("scoring").stream().map(r -> r[0] + " " + r[1]).toList());
    }

    /**
     * Routing is first-match, so a stub that matches a LATER stub's concrete path makes the later one unreachable (the
     * {@code /events/([^/]+)} catch-all registered before {@code /events/search} is the classic). Checked across every
     * known module in registration order, per HTTP method.
     */
    @Test
    void noStubShadowsALaterStub() {
        List<String[]> all = new ArrayList<>();
        for (ModuleManifest m : KnownModules.load(AbsentModuleRoutesTest.class.getClassLoader()).manifests())
            all.addAll(AbsentModuleRoutes.surface(m));
        assertTrue(all.size() > 100, "known-modules routes missing: " + all.size());
        for (int j = 0; j < all.size(); j++) {
            String probe = concrete(all.get(j)[1]);
            for (int i = 0; i < j; i++)
                if (all.get(i)[0].equals(all.get(j)[0]) && Pattern.matches(all.get(i)[1], probe))
                    throw new AssertionError(all.get(i)[0] + " " + all.get(i)[1] + " is registered BEFORE and shadows "
                            + all.get(j)[0] + " " + all.get(j)[1]);
        }
    }

    /** A request path that the pattern accepts: capture groups filled with a segment, alternations with their first branch. */
    private static String concrete(String pattern) {
        return pattern.replace("([^/]+)", "probe").replaceAll("\\(([a-z]+)(\\|[a-z|]+)\\)", "$1");
    }
}
