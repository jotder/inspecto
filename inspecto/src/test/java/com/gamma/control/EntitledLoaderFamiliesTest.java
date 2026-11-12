package com.gamma.control;

import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.acquire.CollectorConnectors;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.decision.ConsequenceContext;
import com.gamma.decision.ConsequenceProvider;
import com.gamma.decision.Consequences;
import com.gamma.etl.PipelineConfig;
import com.gamma.job.JobResult;
import com.gamma.job.MaintenanceTaskContext;
import com.gamma.job.MaintenanceTaskProvider;
import com.gamma.module.EntitledServices;
import com.gamma.module.Entitlements;
import com.gamma.pipeline.exec.StepContext;
import com.gamma.pipeline.exec.StepExecutor;
import com.gamma.service.SpaceManager;
import com.gamma.spi.OptionalSpi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.ProtectionDomain;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENTITLEMENT-SPI-NARROWING-1: each extension-point family skips a provider whose module the signed entitlement leaves out.
 *
 * <p>The fixture is the production shape: an optional module's JAR carrying its {@code META-INF/inspecto/module.toon}, a
 * {@code META-INF/services} entry and the provider class, which is defined with that jar as its code source. Each family is
 * then asked three ways: no file (it loads, D-PA23), a file listing the module (it loads), a file leaving it out (it does
 * not, D-PA22). {@code Consequences}, {@code CollectorConnectors} and the boot hooks ({@code OptionalSpi}, as
 * {@code ControlApi} calls it) are driven through their real entry points. The step-kind and maintenance-task registries
 * load once per JVM, so they are driven through the exact {@code EntitledServices} call their loader makes;
 * {@code tools/check-entitled-loaders.mjs} pins that every loader makes it.
 */
class EntitledLoaderFamiliesTest {

    private static final String MODULE = "probe-module";

    @TempDir Path tmp;
    private KeyPair issuer;
    private URLClassLoader module;
    private ClassLoader priorContext;

    // ── one probe provider per family (public, no-arg: ServiceLoader instantiates them) ──

    public static final class ProbeConsequence implements ConsequenceProvider {
        @Override public String id() { return "probe-action"; }
        @Override public String displayName() { return "Probe"; }
        @Override public String group() { return "platform"; }
        @Override public Result execute(ConsequenceContext ctx, Map<String, Object> consequence) { return Result.skipped("probe"); }
    }

    public static final class ProbeConnector implements CollectorConnectorFactory {
        @Override public String scheme() { return "probe-scheme"; }
        @Override public CollectorConnector create(PipelineConfig cfg) { throw new UnsupportedOperationException(); }
        @Override public void validate(ConnectionProfile profile) { throw new IllegalStateException("probe connector reached"); }
    }

    public static final class ProbeStep implements StepExecutor {
        static final String CONSTRUCTED = "entitled-loader-test.probe-step-constructed";
        public ProbeStep() { System.setProperty(CONSTRUCTED, "yes"); }   // a side channel both class loaders' copies share
        @Override public String type() { return "probe.step"; }
        @Override public void execute(StepContext ctx) { }
    }

    public static final class ProbeMaintenance implements MaintenanceTaskProvider {
        @Override public Set<String> tasks() { return Set.of("probe-task"); }
        @Override public JobResult run(String task, MaintenanceTaskContext ctx) { throw new UnsupportedOperationException(); }
    }

    public static final class ProbeBootHook implements HostBootHook {
        @Override public void afterRoutes(SpaceManager spaces) { }
    }

    private static final Map<Class<?>, Class<?>> PROVIDERS = Map.of(
            ConsequenceProvider.class, ProbeConsequence.class,
            CollectorConnectorFactory.class, ProbeConnector.class,
            StepExecutor.class, ProbeStep.class,
            MaintenanceTaskProvider.class, ProbeMaintenance.class,
            HostBootHook.class, ProbeBootHook.class);

    /** Defines the probe classes itself, from the jar, so their code source is the module jar (child-first for them only). */
    private static final class ModuleJarLoader extends URLClassLoader {
        private final CodeSource source;
        private final Set<String> own;

        ModuleJarLoader(URL jar, ClassLoader parent, Set<String> own) {
            super(new URL[] {jar}, parent);
            this.source = new CodeSource(jar, (Certificate[]) null);
            this.own = own;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!own.contains(name)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c != null) return c;
                try (InputStream in = getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (in == null) throw new ClassNotFoundException(name);
                    byte[] b = in.readAllBytes();
                    return defineClass(name, b, 0, b.length, new ProtectionDomain(source, null, this, null));
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }
    }

    @BeforeEach
    void buildTheModuleJar() throws Exception {
        issuer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Entitlements.trustForTests(Map.of("test-issuer", issuer.getPublic()));
        Entitlements.clockForTests(java.time.Clock.fixed(java.time.Instant.parse("2026-11-12T00:00:00Z"), java.time.ZoneOffset.UTC));
        noFile();

        Path jar = tmp.resolve("probe-module.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream j = new JarOutputStream(out)) {
            put(j, "META-INF/inspecto/module.toon", String.join("\n", "id: " + MODULE, "title: Probe module",
                    "buildRole: implementation", "offeringRole: optional", "bindingTime: boot", ""));
            for (var e : PROVIDERS.entrySet()) {
                put(j, "META-INF/services/" + e.getKey().getName(), e.getValue().getName() + "\n");
                String path = e.getValue().getName().replace('.', '/') + ".class";
                try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
                    j.putNextEntry(new JarEntry(path));
                    in.transferTo(j);
                    j.closeEntry();
                }
            }
        }
        module = new ModuleJarLoader(jar.toUri().toURL(), getClass().getClassLoader(),
                Set.copyOf(PROVIDERS.values().stream().map(Class::getName).toList()));
        priorContext = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(module);   // EntitledServices.load(spi) / OptionalSpi use the context loader
    }

    @AfterEach
    void restore() throws IOException {
        Thread.currentThread().setContextClassLoader(priorContext);
        module.close();
        System.clearProperty(Entitlements.FILE_PROPERTY);
        Entitlements.trustForTests(null);
        Entitlements.clockForTests(null);
    }

    private static void put(JarOutputStream j, String name, String text) throws IOException {
        j.putNextEntry(new JarEntry(name));
        j.write(text.getBytes(StandardCharsets.UTF_8));
        j.closeEntry();
    }

    private void noFile() {
        System.setProperty(Entitlements.FILE_PROPERTY, tmp.resolve("conf/none.toon").toString());
        Entitlements.reset();
    }

    /** Sign and install an entitlement listing {@code modules}. */
    private void entitled(List<String> modules) throws Exception {
        String toon = String.join("\n", "schema: inspecto.entitlement/1", "id: probe-2026-11-12", "customer: acme", "title: Acme",
                "product: inspecto", "edition: advanced", "apps[0]:", "addons[0]:",
                "modules[" + modules.size() + "]:" + (modules.isEmpty() ? "" : " " + String.join(",", modules)),
                "nodes: 1", "notBefore: 2026-11-01", "expires: 2027-11-12", "issued: 2026-11-12", "keyId: test-issuer", "");
        Path dir = Files.createDirectories(tmp.resolve("conf"));
        Files.writeString(dir.resolve("entitlement.toon"), toon, StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(issuer.getPrivate());
        s.update(toon.getBytes(StandardCharsets.UTF_8));
        Files.writeString(dir.resolve("entitlement.sig"), Base64.getEncoder().encodeToString(s.sign()));
        System.setProperty(Entitlements.FILE_PROPERTY, dir.resolve("entitlement.toon").toString());
        Entitlements.reset();
    }

    /** No file and a file listing the module both load the probe; a file leaving it out does not. */
    private void narrowsTheFamily(String family, Callable<Boolean> loaded) throws Exception {
        noFile();
        assertTrue(loaded.call(), family + ": no file loads every provider (D-PA23)");
        entitled(List.of("other-module", MODULE));
        assertTrue(loaded.call(), family + ": a file listing the module loads it");
        entitled(List.of("other-module"));
        assertFalse(loaded.call(), family + ": a file leaving the module out skips it (D-PA22)");
    }

    @Test
    void theFixtureResolvesTheProbeToItsModuleJar() throws Exception {
        Class<?> probe = module.loadClass(ProbeConsequence.class.getName());
        assertEquals(List.of(MODULE), Entitlements.modulesOf(probe).stream().map(m -> m.id()).toList());
        assertTrue(Entitlements.modulesOf(ProbeConsequence.class).isEmpty(), "the test-classes copy carries no module manifest");
    }

    @Test
    void consequences() throws Exception {
        narrowsTheFamily("Consequences", () -> Consequences.load(module).find("probe-action").isPresent());
    }

    @Test
    void aConsequenceRegistryLoadedBeforeARenewalStopsServingTheLeftOutModule() throws Exception {
        noFile();
        Consequences registry = Consequences.load(module);
        assertTrue(registry.find("probe-action").isPresent());
        entitled(List.of("other-module"));
        assertTrue(registry.find("probe-action").isEmpty(), "checked at each find, not only when the registry loaded");
        assertTrue(registry.all().stream().noneMatch(p -> "probe-action".equals(p.id())));
    }

    @Test
    void collectorConnectors() throws Exception {
        ConnectionProfile profile = new ConnectionProfile("probe", "probe-scheme", "probe.test", 1, null, "x", null, null,
                Map.of(), null, null);
        narrowsTheFamily("Collector connectors", () -> {
            try {
                CollectorConnectors.validate(profile);
                return false;   // no factory serves the scheme
            } catch (IllegalStateException reached) {
                return "probe connector reached".equals(reached.getMessage());
            }
        });
    }

    @Test
    void stepKinds() throws Exception {
        narrowsTheFamily("step kinds", () -> EntitledServices.stream(StepExecutor.class).anyMatch(e -> "probe.step".equals(e.type())));
    }

    @Test
    void maintenanceTasks() throws Exception {
        narrowsTheFamily("maintenance tasks",
                () -> EntitledServices.stream(MaintenanceTaskProvider.class).anyMatch(p -> p.tasks().contains("probe-task")));
    }

    @Test
    void bootHooks() throws Exception {
        narrowsTheFamily("boot hooks", () -> OptionalSpi.all(HostBootHook.class).stream()
                .anyMatch(h -> h.getClass().getName().equals(ProbeBootHook.class.getName())));
    }

    @Test
    void aLeftOutProviderIsNeverInstantiated() throws Exception {
        System.clearProperty(ProbeStep.CONSTRUCTED);
        try {
            entitled(List.of("other-module"));
            EntitledServices.stream(StepExecutor.class).forEach(e -> { });
            assertEquals(null, System.getProperty(ProbeStep.CONSTRUCTED), "skipped on its type, before its constructor runs");
            entitled(List.of(MODULE));
            EntitledServices.stream(StepExecutor.class).forEach(e -> { });
            assertEquals("yes", System.getProperty(ProbeStep.CONSTRUCTED), "the probe is sound: an entitled one IS constructed");
        } finally {
            System.clearProperty(ProbeStep.CONSTRUCTED);
        }
    }
}
