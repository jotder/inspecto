package com.gamma.module;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/** MODULE-REORG-1 P3a: the build-id gate, over real (synthetic) jars. An absent or {@code dev} stamp is never a mismatch. */
class ModuleBuildIdTest {

    /** A class whose code source is the test-classes directory (no manifest) — or the host jar when a test copies it in. */
    public static final class HostMarker {}

    private static String toon(String id) {
        return "---\nid: " + id + "\ntitle: " + id + "\nbuildRole: implementation\nofferingRole: optional\nbindingTime: boot\n";
    }

    /** A jar holding {@code module.toon} and, when {@code stamp} is non-null, a manifest carrying it. */
    private static Path jar(Path dir, String name, String id, String stamp) throws Exception {
        Path f = dir.resolve(name + ".jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (stamp != null) mf.getMainAttributes().putValue(ModuleManifests.BUILD_ID_ATTRIBUTE, stamp);
        try (OutputStream o = Files.newOutputStream(f); JarOutputStream j = new JarOutputStream(o, mf)) {
            j.putNextEntry(new JarEntry(ModuleManifests.RESOURCE));
            j.write(toon(id).getBytes());
            j.closeEntry();
        }
        return f;
    }

    private static URL[] urls(Path... jars) throws Exception {
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toUri().toURL();
        return urls;
    }

    /** Loads module jars against a host jar stamped {@code hostStamp} (null = no manifest stamp). */
    private static ModuleManifests.Loaded loadWithHost(Path dir, String hostStamp, Path... modules) throws Exception {
        Path hostJar = dir.resolve("hostjar.jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (hostStamp != null) mf.getMainAttributes().putValue(ModuleManifests.BUILD_ID_ATTRIBUTE, hostStamp);
        String cls = HostMarker.class.getName().replace('.', '/') + ".class";
        try (OutputStream o = Files.newOutputStream(hostJar); JarOutputStream j = new JarOutputStream(o, mf);
             var in = HostMarker.class.getClassLoader().getResourceAsStream(cls)) {
            j.putNextEntry(new JarEntry(cls));
            j.write(in.readAllBytes());
            j.closeEntry();
        }
        try (URLClassLoader hostLoader = new URLClassLoader(urls(hostJar), null);
             URLClassLoader cl = new URLClassLoader(urls(modules), null)) {
            return ModuleManifests.load(cl, hostLoader.loadClass(HostMarker.class.getName()));
        }
    }

    @Test
    void activatorGate_matchMismatchAbsentAndUnknownHost() {
        ModuleManifest base = new ModuleManifest("x", "x", "implementation", "optional", "boot",
                ModuleManifest.Provides.NONE, ModuleManifest.Requires.NONE, null);
        ModuleManifest dep = new ModuleManifest("dep", "dep", "implementation", "optional", "boot",
                ModuleManifest.Provides.NONE, new ModuleManifest.Requires(List.of("x"), List.of()), null);
        assertEquals(ModuleStatus.State.ACTIVE, ModuleActivator.resolve(List.of(base.withBuildId("abc")), "abc").get(0).state());
        List<ModuleStatus> bad = ModuleActivator.resolve(List.of(base.withBuildId("abc"), dep.withBuildId("abc")), "def");
        assertEquals(ModuleStatus.State.INERT, bad.get(0).state());
        assertEquals(List.of("build id abc does not match host def"), bad.get(0).reasons());
        assertEquals(ModuleStatus.State.INERT, bad.get(1).state(), "a dependant of a mismatched module goes inert too");
        assertEquals(ModuleStatus.State.ACTIVE, ModuleActivator.resolve(List.of(base), "def").get(0).state(), "unstamped module");
        assertEquals(ModuleStatus.State.ACTIVE, ModuleActivator.resolve(List.of(base.withBuildId("abc")), null).get(0).state(), "unknown host");
    }

    @Test
    void sidecarJarWithADifferentStampIsInertAndNamed(@TempDir Path tmp) throws Exception {
        ModuleManifests.Loaded l = loadWithHost(tmp, "aaa111", jar(tmp, "mod", "sidecar", "bbb222"));
        assertEquals("aaa111", l.hostBuildId());
        assertEquals("bbb222", l.manifests().get(0).buildId());
        ModuleStatus s = ModuleActivator.resolve(l.manifests(), l.hostBuildId()).get(0);
        assertEquals(ModuleStatus.State.INERT, s.state());
        assertEquals(List.of("build id bbb222 does not match host aaa111"), s.reasons());
        assertTrue(l.diagnostics().stream().anyMatch(d -> d.contains("sidecar") && d.contains("does not match host")), l.diagnostics().toString());
    }

    @Test
    void matchingAbsentAndDevStampsStayActive(@TempDir Path tmp) throws Exception {
        ModuleManifests.Loaded l = loadWithHost(tmp, "aaa111",
                jar(tmp, "m1", "same", "aaa111"), jar(tmp, "m2", "unstamped", null), jar(tmp, "m3", "devbuild", "dev"));
        assertEquals(3, l.manifests().size());
        for (ModuleStatus s : ModuleActivator.resolve(l.manifests(), l.hostBuildId()))
            assertEquals(ModuleStatus.State.ACTIVE, s.state(), s.toString());
        assertNull(l.manifests().get(2).buildId(), "dev reads as unknown");
        assertTrue(l.diagnostics().isEmpty(), l.diagnostics().toString());
    }

    @Test
    void devOrUnstampedHostMeansNoCheck(@TempDir Path tmp) throws Exception {
        ModuleManifests.Loaded dev = loadWithHost(tmp, "dev", jar(tmp, "m", "x", "zzz"));
        assertNull(dev.hostBuildId());
        assertEquals("zzz", dev.manifests().get(0).buildId());
        assertEquals(ModuleStatus.State.ACTIVE, ModuleActivator.resolve(dev.manifests(), dev.hostBuildId()).get(0).state());
        // an exploded test-classes directory (the common IDE/test case) has no manifest: unknown
        assertNull(ModuleManifests.load(new URLClassLoader(new URL[0], null), HostMarker.class).hostBuildId());
        assertNull(ModuleManifests.load(new URLClassLoader(new URL[0], null)).hostBuildId());
    }
}
