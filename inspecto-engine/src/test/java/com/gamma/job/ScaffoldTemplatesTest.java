package com.gamma.job;

import com.gamma.notify.NotificationAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the scaffolder's templates (S1-8) against the rot they are uniquely prone to: they are Java
 * that no compiler ever sees. This test stamps {@code tools/templates/} exactly as
 * {@code tools/scaffold.mjs} does, compiles every generated source — the generated <em>test</em>
 * included, since a broken assertion is the failure a pack author meets first — packages the main
 * classes into a real pack jar, and loads it through {@link JobPackManager} into a scratch registry.
 *
 * <p>It deliberately does not shell out to Node or Maven: an engine unit test must stay offline and
 * self-contained. The token substitution below is the same {@code {{token}}} contract the script
 * implements, so a template that fails here would have failed there.
 */
class ScaffoldTemplatesTest {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{(\\w+)}}");

    /** Every token the scaffolder supplies. A template that grows one the script does not set fails
     *  there; one the script sets but this map does not fails here — both are the same bug. */
    private static final Map<String, String> TOKENS = Map.ofEntries(
            Map.entry("id", "acme.reconcile"),
            Map.entry("name", "Acme Reconcile"),
            Map.entry("className", "AcmeReconcile"),
            Map.entry("artifactId", "acme-reconcile"),
            Map.entry("packageName", "com.example.pack"),
            Map.entry("engineGroupId", "com.gamma.inspector"),
            Map.entry("engineArtifactId", "inspecto-engine"),
            Map.entry("engineVersion", "0-test"),
            Map.entry("junitVersion", "5.10.2"),
            Map.entry("javaRelease", "24"),
            Map.entry("compilerPluginVersion", "3.13.0"),
            Map.entry("surefirePluginVersion", "3.2.5"));

    @Test
    void theJobTemplateCompilesLoadsAndRuns(@TempDir Path work) throws Exception {
        Path project = stamp(templates().resolve("job"), work.resolve("project"));

        // Every stamped source compiles — main and test alike.
        Path classes = compile(project, work.resolve("classes"));

        // …and the same META-INF/services file the engine reads names a class that is really there.
        Path jar = work.resolve("acme-reconcile.jar");
        packageJar(jar, classes, project.resolve("src/main/resources"));

        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        List<String> notified = new ArrayList<>();
        platform.register("notifications", NotificationAccess.class, n -> {
            notified.add(n.title());
            return java.util.Optional.of(n);
        });
        JobTypeRegistry registry = new JobTypeRegistry(platform);

        Path packsDir = Files.createDirectories(work.resolve("packs"));
        Files.copy(jar, packsDir.resolve(jar.getFileName()));
        List<String> signals = new ArrayList<>();
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry,
                ExpressionRegistry.withBuiltins(),
                (type, sev, payload) -> signals.add(type))) {
            mgr.scanAtStartup();

            assertTrue(registry.has("acme.reconcile"),
                    "the scaffolded pack did not register: " + signals);
            assertEquals(List.of("notifications"),
                    registry.descriptor("acme.reconcile").orElseThrow().requires());
        }

        // The generated project's own claim — that PackTestHarness runs it green — verified here on
        // the compiled template rather than trusted from the README.
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                getClass().getClassLoader())) {
            List<JobTypeProvider> providers = new ArrayList<>();
            for (JobTypeProvider p : ServiceLoader.load(JobTypeProvider.class, loader))
                if (p.getClass().getClassLoader() == loader) providers.add(p);
            assertEquals(1, providers.size(), "one provider from META-INF/services");

            PackTestHarness.Outcome run = PackTestHarness.create()
                    .load(providers.get(0))
                    .run("acme.reconcile", Map.of("subject", "harness"));

            assertEquals("SUCCESS", run.status(), run.message());
            assertEquals("harness", run.params().get("subject"));
            assertEquals(1, run.notifications().size());
            assertTrue(run.logged("TODO: do the work"), run.log().toString());
        }
        assertTrue(notified.isEmpty(), "the harness must use its own recording feed, not the host's");
    }

    @Test
    void theProcessorTemplateCompiles(@TempDir Path work) throws Exception {
        Path project = stamp(templates().resolve("processor"), work.resolve("project"));

        compile(project, work.resolve("classes"));

        Path services = project.resolve(
                "src/main/resources/META-INF/services/com.gamma.consignment.ConsignmentProcessor");
        assertEquals("com.example.pack.AcmeReconcileProcessor",
                Files.readString(services).trim(),
                "the ServiceLoader entry must name the generated class");
    }

    // ── the scaffolder's substitution, in Java ────────────────────────────────

    /** Walk up from the module dir to the repo root, which is where {@code tools/templates} lives. */
    private static Path templates() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            Path candidate = p.resolve("tools/templates");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("tools/templates not found above " + dir);
    }

    /** Copy a template tree into {@code to}, stamping path segments and {@code {{token}}} content. */
    private static Path stamp(Path from, Path to) throws Exception {
        try (Stream<Path> tree = Files.walk(from)) {
            for (Path source : (Iterable<Path>) tree.filter(Files::isRegularFile)::iterator) {
                String rel = from.relativize(source).toString().replace('\\', '/')
                        .replace("__packageDir__", TOKENS.get("packageName").replace('.', '/'))
                        .replace("__className__", TOKENS.get("className"));
                Path target = to.resolve(rel);
                Files.createDirectories(target.getParent());
                Files.writeString(target, substitute(Files.readString(source)));
            }
        }
        return to;
    }

    private static String substitute(String text) {
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = TOKENS.get(m.group(1));
            if (value == null) throw new IllegalStateException("template token {{" + m.group(1)
                    + "}} has no value — scaffold.mjs would have failed here too");
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ── compile / package ────────────────────────────────────────────────────

    /** Compile every {@code .java} under {@code project} against the engine + JUnit. Test sources go
     *  to a separate output dir so they never reach the pack jar. */
    private static Path compile(Path project, Path out) throws Exception {
        List<Path> main = sources(project.resolve("src/main/java"));
        List<Path> test = sources(project.resolve("src/test/java"));
        assertTrue(!main.isEmpty(), "template has no main sources");

        // The running test classpath (under surefire a booter jar whose manifest Class-Path javac
        // follows) plus the two code sources that must be there whichever way surefire was launched.
        String cp = String.join(java.io.File.pathSeparator,
                System.getProperty("java.class.path"),
                codeSource(JobTypeProvider.class), codeSource(Test.class));
        Path mainClasses = Files.createDirectories(out.resolve("main"));
        javac(main, cp, mainClasses);
        if (!test.isEmpty())
            javac(test, cp + java.io.File.pathSeparator + mainClasses, out.resolve("test"));
        return mainClasses;
    }

    private static void javac(List<Path> sources, String classpath, Path out) throws Exception {
        Files.createDirectories(out);
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = jc.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<String> opts = List.of("-classpath", classpath, "-d", out.toString());
            boolean ok = jc.getTask(null, fm, null, opts, null,
                    fm.getJavaFileObjectsFromFiles(sources.stream().map(Path::toFile).toList())).call();
            assertTrue(ok, "template sources compiled: " + sources);
        }
    }

    private static List<Path> sources(Path root) throws Exception {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /** The jar/dir a class was loaded from — robust under surefire, whose classpath is a booter jar. */
    private static String codeSource(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    /** Package compiled classes + the template's own {@code META-INF/services} into a pack jar. */
    private static void packageJar(Path jar, Path classes, Path resources) throws Exception {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Pack-Id", TOKENS.get("id"));
        mf.getMainAttributes().putValue("Pack-Version", "1.0.0");
        Map<String, Path> entries = new LinkedHashMap<>();
        collect(classes, classes, entries);
        collect(resources, resources, entries);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar), mf)) {
            for (var e : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                Files.copy(e.getValue(), jos);
                jos.closeEntry();
            }
        }
    }

    private static void collect(Path root, Path dir, Map<String, Path> into) throws Exception {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> tree = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) tree.filter(Files::isRegularFile)::iterator)
                into.put(root.relativize(p).toString().replace('\\', '/'), p);
        }
    }
}
