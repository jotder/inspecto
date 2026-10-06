package com.gamma.control;

import com.gamma.module.ModuleActivator;
import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;
import com.gamma.module.ModuleStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MODULE-REORG-1 P2a guard: every top-level Maven module with {@code src/main/java} ships a
 * {@code META-INF/inspecto/module.toon}, and each manifest's {@code provides.features} equals the ids the
 * module's RouteModules return from {@code featureIds()} — so the manifest cannot drift from the code.
 * Manifests are read from the SOURCE tree (not the class path), so a module that is not on this test's class path
 * is still checked. The {@code asn-parser/asn-decoders/asn-facade} manifest is a nested reactor and is checked for
 * existence only.
 */
class ModuleManifestGuardTest {

    private static final Pattern FEATURE_IDS = Pattern.compile("featureIds\\(\\)\\s*\\{[^}]*?Set\\.of\\(([^)]*)\\)");
    private static final Pattern STRING = Pattern.compile("\"([^\"]+)\"");

    private static Path repoRoot() throws IOException {
        return ReactorModules.root();
    }

    private static List<Path> modulesWithMainJava(Path root) throws IOException {
        List<Path> out = ReactorModules.withMainJava(ReactorModules.topLevelModules()).stream()
                .filter(p -> p.getFileName().toString().startsWith("inspecto")).sorted().toList();
        assertFalse(out.isEmpty(), "no modules with src/main/java found - reactor discovery broken?");
        return out;
    }

    private static Path asnFacade() throws IOException {
        return ReactorModules.modules().stream().filter(p -> p.getFileName().toString().equals("asn-facade")).findFirst()
                .orElseThrow(() -> new AssertionError("asn-facade module not found in the reactor"));
    }

    private static Path manifestOf(Path module) {
        return module.resolve("src/main/resources").resolve(ModuleManifests.RESOURCE);
    }

    @Test
    void everyModuleWithMainJavaHasAManifestWhoseFeaturesMatchTheCode() throws IOException {
        Path root = repoRoot();
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Path module : modulesWithMainJava(root)) {
            String name = module.getFileName().toString();
            Path mf = manifestOf(module);
            if (!Files.isRegularFile(mf)) { problems.add(name + ": no " + ModuleManifests.RESOURCE); continue; }
            ModuleManifest m;
            try {
                m = ModuleManifests.parse(Files.readString(mf).replaceFirst("(?m)^---[ \\t]*\\r?\\n", "").strip());
            } catch (RuntimeException e) {
                problems.add(name + ": " + e.getMessage());
                continue;
            }
            if (!ids.add(m.id())) problems.add(name + ": duplicate id " + m.id());
            Set<String> inCode = featureIdsInCode(module);
            if (!inCode.equals(new TreeSet<>(m.provides().features())))
                problems.add(name + ": provides.features " + m.provides().features() + " != featureIds() in code " + inCode);
        }
        assertTrue(Files.isRegularFile(manifestOf(asnFacade())), "asn-decoders manifest missing");
        assertTrue(problems.isEmpty(), "module manifest problems:\n" + String.join("\n", problems));
    }

    @Test
    void discoveredModulesEqualTheTrackedPomsMinusTheRootAndTheScaffoldTemplates() throws IOException {
        Path root = ReactorModules.root();
        List<Path> modules = ReactorModules.modules();
        assertTrue(modules.size() >= 40, "reactor discovery found only " + modules.size() + " modules");
        Process p = null;
        try {
            p = new ProcessBuilder("git", "ls-files", "*pom.xml").directory(root.toFile()).redirectErrorStream(true).start();
        } catch (IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "git not available: " + e.getMessage());
        }
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(p.waitFor() == 0 && !out.isBlank(), "not a git checkout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        Set<Path> tracked = new TreeSet<>();
        for (String line : out.split("\\R")) {
            if (line.isBlank() || line.equals("pom.xml") || line.startsWith("tools/templates/")) continue;
            tracked.add(root.resolve(line).getParent().normalize());
        }
        assertEquals(tracked, new TreeSet<>(modules), "reactor <module> entries differ from the tracked module poms");
    }

    @Test
    void everyDeclaredRequirementNamesAKnownModuleAndTheWholeSourceTreeResolvesActive() throws IOException {
        Path root = repoRoot();
        List<ModuleManifest> all = new ArrayList<>();
        List<Path> mods = new ArrayList<>(modulesWithMainJava(root));
        mods.add(asnFacade());
        for (Path module : mods)
            all.add(ModuleManifests.parse(Files.readString(manifestOf(module)).replaceFirst("(?m)^---[ \\t]*\\r?\\n", "").strip()));
        for (ModuleStatus s : ModuleActivator.resolve(all))
            assertEquals(ModuleStatus.State.ACTIVE, s.state(), s.id() + " would be inert in a full install: " + s.reasons());
    }

    private static Set<String> featureIdsInCode(Path module) throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.walk(module.resolve("src/main/java"))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = FEATURE_IDS.matcher(Files.readString(p));
                while (m.find()) {
                    Matcher q = STRING.matcher(m.group(1));
                    while (q.find()) out.add(q.group(1));
                }
            }
        }
        return out;
    }
}
