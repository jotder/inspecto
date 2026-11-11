package com.gamma.module;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PRODUCT-ASSEMBLY-1 PA-7a: the entitlement verifier, its narrowing rules, and parity with tools/entitle.mjs. */
class EntitlementsTest {

    @TempDir Path tmp;

    @AfterEach
    void restore() {
        System.clearProperty(Entitlements.FILE_PROPERTY);
        Entitlements.trustForTests(null);
        Entitlements.clockForTests(null);
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = EntitlementsTest.class.getClassLoader().getResourceAsStream("entitlement/" + name)) {
            return in.readAllBytes();
        }
    }

    private static PublicKey fixtureKey() throws Exception {
        byte[] der = Base64.getDecoder().decode(new String(resource("fixture-issuer.pub"), StandardCharsets.US_ASCII).trim());
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    @Test
    void aFileSignedByEntitleMjsVerifiesHereByteForByte() throws Exception {
        Entitlement e = Entitlements.verify(resource("entitlement.toon"), new String(resource("entitlement.sig"), StandardCharsets.US_ASCII),
                Map.of("fixture-issuer", fixtureKey()), Path.of("entitlement.toon"));
        assertEquals("fixture", e.customer());
        assertEquals("Fixture Customer (synthetic)", e.title());
        assertEquals("standard", e.edition());
        assertEquals(List.of("incidents", "screening"), e.addons());
        assertEquals(List.of("ops", "screening"), e.modules());
        assertEquals(3, e.nodes());
        assertEquals(LocalDate.of(2027, 11, 10), e.expires());
        assertEquals(364L, e.daysLeft(LocalDate.of(2026, 11, 11)));
        assertEquals("fixture-issuer", e.summary(LocalDate.of(2026, 11, 11)).get("keyId"));
    }

    @Test
    void verifyRefusesAChangedByteAnUnknownKeyAndAnotherSchema() throws Exception {
        byte[] toon = resource("entitlement.toon");
        String sig = new String(resource("entitlement.sig"), StandardCharsets.US_ASCII);
        Map<String, PublicKey> trust = Map.of("fixture-issuer", fixtureKey());
        byte[] changed = new String(toon, StandardCharsets.UTF_8).replace("nodes: 3", "nodes: 9").getBytes(StandardCharsets.UTF_8);
        assertTrue(assertThrows(Entitlements.Invalid.class, () -> Entitlements.verify(changed, sig, trust, Path.of("f")))
                .getMessage().contains("signature does not match"));
        assertTrue(assertThrows(Entitlements.Invalid.class, () -> Entitlements.verify(toon, sig, Map.of(), Path.of("f")))
                .getMessage().contains("key id 'fixture-issuer' is not an issuer key"));
        byte[] other = new String(toon, StandardCharsets.UTF_8).replace("inspecto.entitlement/1", "inspecto.entitlement/9").getBytes(StandardCharsets.UTF_8);
        assertTrue(assertThrows(Entitlements.Invalid.class, () -> Entitlements.verify(other, sig, trust, Path.of("f")))
                .getMessage().contains("not inspecto.entitlement/1"));
        assertTrue(assertThrows(Entitlements.Invalid.class, () -> Entitlements.verify(toon, "not base64 !", trust, Path.of("f")))
                .getMessage().contains("signature does not match"));
    }

    @Test
    void noFileMeansEverythingIsEntitled() {
        System.setProperty(Entitlements.FILE_PROPERTY, tmp.resolve("absent.toon").toString());
        Entitlements.reset();
        assertTrue(Entitlements.current().isEmpty());
        assertFalse(Entitlements.installed());
        assertTrue(Entitlements.addonEntitled("anything"));
        assertTrue(Entitlements.moduleEntitled(manifest("screening", "optional")));
        assertTrue(Entitlements.unentitledModules(List.of(manifest("screening", "optional"))).isEmpty());
    }

    @Test
    void aVerifiedFileNarrowsOptionalModulesButNeverBaseOrInternalOnes() throws Exception {
        KeyPair k = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Entitlements.trustForTests(Map.of("k", k.getPublic()));
        String toon = "schema: inspecto.entitlement/1\nid: a\ncustomer: a\ntitle: A\nproduct: inspecto\nedition: basic\napps[0]:\n"
                + "addons[1]: screening\nmodules[1]: screening\nnodes: 1\nnotBefore: 2026-11-11\nissued: 2026-11-11\nkeyId: k\n";
        Path f = tmp.resolve("entitlement.toon");
        Files.writeString(f, toon);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(k.getPrivate());
        s.update(toon.getBytes(StandardCharsets.UTF_8));
        Files.writeString(tmp.resolve("entitlement.sig"), Base64.getEncoder().encodeToString(s.sign()));
        System.setProperty(Entitlements.FILE_PROPERTY, f.toString());
        Entitlements.reset();

        assertTrue(Entitlements.installed());
        assertNull(Entitlements.current().orElseThrow().expires(), "no expiry line = never expires");
        assertTrue(Entitlements.moduleEntitled(manifest("screening", "optional")));
        assertFalse(Entitlements.moduleEntitled(manifest("scoring", "optional")));
        assertFalse(Entitlements.moduleEntitled(manifest("connectors", "provider")));
        assertTrue(Entitlements.moduleEntitled(manifest("processor", "base")));
        assertTrue(Entitlements.moduleEntitled(manifest("kernel", "internal")));
        assertTrue(Entitlements.addonEntitled("screening"));
        assertFalse(Entitlements.addonEntitled("linkAnalysis"));
        assertEquals(Set.of("scoring"), Entitlements.unentitledModules(List.of(manifest("screening", "optional"),
                manifest("scoring", "optional"), manifest("processor", "base"))));
    }

    @Test
    void anUnentitledModuleIsInertAndItsDependantsFollow() {
        ModuleManifest scoring = manifest("scoring", "optional");
        ModuleManifest screening = new ModuleManifest("screening", "screening", "implementation", "optional", "boot",
                new ModuleManifest.Provides(List.of("screening"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of()),
                new ModuleManifest.Requires(List.of("scoring"), List.of()), null);
        List<ModuleStatus> st = ModuleActivator.resolve(List.of(scoring, screening), null, Set.of(), Set.of("scoring"));
        assertEquals(ModuleStatus.State.INERT, st.get(0).state());
        assertEquals(List.of(Entitlements.NOT_ENTITLED), st.get(0).reasons());
        assertEquals(ModuleStatus.State.INERT, st.get(1).state());
        assertEquals(List.of("requires module 'scoring', which is inert"), st.get(1).reasons());
        assertTrue(ModuleActivator.resolve(List.of(scoring, screening), null, Set.of()).stream()
                .allMatch(x -> x.state() == ModuleStatus.State.ACTIVE), "without an entitlement both stay active");
    }

    // ── PA-7b: expiry, grace, read-only, renewal, trial ──

    private KeyPair issuer;

    /** Sign and install an entitlement expiring on {@code expires} (null = never), with {@code nodes}; returns its file. */
    private Path install(String expires, int nodes, String notBefore) throws Exception {
        if (issuer == null) {
            issuer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Entitlements.trustForTests(Map.of("k", issuer.getPublic()));
        }
        String toon = "schema: inspecto.entitlement/1\nid: a\ncustomer: a\ntitle: A\nproduct: inspecto\nedition: basic\napps[0]:\n"
                + "addons[0]:\nmodules[0]:\nnodes: " + nodes + "\nnotBefore: " + notBefore + "\n" + (expires == null ? "" : "expires: " + expires + "\n")
                + "issued: 2026-01-01\nkeyId: k\n";
        Path f = tmp.resolve("entitlement.toon");
        Files.writeString(f, toon);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(issuer.getPrivate());
        s.update(toon.getBytes(StandardCharsets.UTF_8));
        Files.writeString(tmp.resolve("entitlement.sig"), Base64.getEncoder().encodeToString(s.sign()));
        System.setProperty(Entitlements.FILE_PROPERTY, f.toString());
        return f;
    }

    private static void on(String day) {
        Entitlements.clockForTests(java.time.Clock.fixed(LocalDate.parse(day).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), java.time.ZoneOffset.UTC));
    }

    @Test
    void theStateFollowsTheCalendarOkThenExpiringThenGraceThenReadOnly() throws Exception {
        install("2027-06-30", 1, "2026-01-01");
        Entitlements.reset();
        on("2027-05-30");
        assertEquals(Entitlements.State.OK, Entitlements.state(), "31 days left");
        on("2027-05-31");
        assertEquals(Entitlements.State.EXPIRING, Entitlements.state(), "30 days left");
        on("2027-06-30");
        assertEquals(Entitlements.State.EXPIRING, Entitlements.state(), "the last day is still valid");
        on("2027-07-01");
        assertEquals(Entitlements.State.GRACE, Entitlements.state());
        assertFalse(Entitlements.readOnly(), "grace only warns");
        on("2027-07-14");
        assertEquals(Entitlements.State.GRACE, Entitlements.state(), "day 14 of the grace");
        on("2027-07-15");
        assertEquals(Entitlements.State.READ_ONLY, Entitlements.state());
        assertTrue(Entitlements.readOnly());
        assertEquals(LocalDate.of(2027, 7, 15), Entitlements.readOnlyFrom());
        assertTrue(Entitlements.readOnlyReason().contains("expired on 2027-06-30"), Entitlements.readOnlyReason());
        assertEquals("read-only", Entitlements.publicSummary().get("state"));
        assertEquals("2027-07-15", Entitlements.publicSummary().get("readOnlyFrom"));
    }

    @Test
    void noExpiryIsAlwaysOkAndNoFileIsNone() throws Exception {
        install(null, 1, "2026-01-01");
        Entitlements.reset();
        on("2099-01-01");
        assertEquals(Entitlements.State.OK, Entitlements.state());
        System.setProperty(Entitlements.FILE_PROPERTY, tmp.resolve("absent.toon").toString());
        Entitlements.reset();
        assertEquals(Entitlements.State.NONE, Entitlements.state());
        assertFalse(Entitlements.readOnly());
        assertEquals("none", Entitlements.publicSummary().get("state"));
    }

    @Test
    void aTrialBuildWithoutItsFileIsReadOnly() {
        System.setProperty(Entitlements.FILE_PROPERTY, tmp.resolve("absent.toon").toString());
        Entitlements.reset();
        Entitlements.requireFile("trial build has no entitlement");
        assertEquals(Entitlements.State.READ_ONLY, Entitlements.state());
        assertTrue(Entitlements.readOnlyReason().contains("trial build has no entitlement"), Entitlements.readOnlyReason());
    }

    @Test
    void aRenewedFileIsTakenWithoutARestartAndABadOneLeavesTheLastInForce() throws Exception {
        Path f = install("2027-06-30", 1, "2026-01-01");
        Entitlements.reset();
        on("2027-08-01");
        assertTrue(Entitlements.readOnly());
        install("2028-06-30", 2, "2026-01-01");   // the renewal
        Entitlements.recheckNow();
        assertFalse(Entitlements.readOnly(), "renewed: writable again with no restart");
        assertEquals(2, Entitlements.current().orElseThrow().nodes());
        Files.writeString(f, Files.readString(f).replace("nodes: 2", "nodes: 7"));   // a tampered replacement
        Entitlements.recheckNow();
        assertEquals(2, Entitlements.current().orElseThrow().nodes(), "the last verified file stays in force");
        assertTrue(Entitlements.problem().contains("signature does not match"), Entitlements.problem());
        assertTrue(Entitlements.adminSummary().containsKey("problem"));
        Files.delete(f);
        Entitlements.recheckNow();
        assertTrue(Entitlements.current().isPresent(), "a removed file never drops a running server to 'no file'");
        assertTrue(Entitlements.problem().contains("was removed"), Entitlements.problem());
    }

    @Test
    void aFileForAnotherBuildIsRefusedAtBootAndOnARenewal() throws Exception {
        install("2028-06-30", 1, "2026-01-01");   // product inspecto, edition basic
        Entitlements.reset();
        on("2026-11-11");
        Entitlements.expectBuild("inspecto", "standard");
        assertTrue(assertThrows(Entitlements.Invalid.class, Entitlements::current).getMessage()
                .contains("is for the basic Edition, but this build is standard"));
        Entitlements.expectBuild("telco", "basic");
        assertTrue(assertThrows(Entitlements.Invalid.class, Entitlements::current).getMessage()
                .contains("is for Product 'inspecto', but this build is Product 'telco'"));
        Entitlements.expectBuild("inspecto", "basic");
        assertTrue(Entitlements.current().isPresent());
        Entitlements.expectBuild("inspecto", "basic");   // the running server
        Entitlements.current();
        // a renewal for another Product is not taken: the verified one stays in force, the problem is named
        String other = Files.readString(tmp.resolve("entitlement.toon")).replace("product: inspecto", "product: telco");
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(issuer.getPrivate());
        s.update(other.getBytes(StandardCharsets.UTF_8));
        Files.writeString(tmp.resolve("entitlement.toon"), other);
        Files.writeString(tmp.resolve("entitlement.sig"), Base64.getEncoder().encodeToString(s.sign()));
        Entitlements.recheckNow();
        assertEquals("inspecto", Entitlements.current().orElseThrow().product());
        assertTrue(Entitlements.problem().contains("is for Product 'telco'"), Entitlements.problem());
    }

    @Test
    void aFileNotYetValidIsRefused() throws Exception {
        install("2028-06-30", 1, "2027-01-01");
        Entitlements.reset();
        on("2026-12-31");
        assertTrue(assertThrows(Entitlements.Invalid.class, Entitlements::current).getMessage().contains("not valid before 2027-01-01"));
        Entitlements.reset();
        on("2027-01-01");
        assertTrue(Entitlements.current().isPresent());
    }

    @Test
    void theCoreJarCarriesTheIssuerKey() {
        assertTrue(Entitlements.trustedKeys().containsKey("inspecto-issuer-1"), "keygen's public half is a resource of this jar");
    }

    private static ModuleManifest manifest(String id, String role) {
        return new ModuleManifest(id, id, "implementation", role, "boot",
                new ModuleManifest.Provides(List.of(id), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of()),
                new ModuleManifest.Requires(List.of(), List.of()), null);
    }
}
