package com.gamma.control;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRODUCT-ASSEMBLY-1 PA-3: the descriptor reader and the resolution of Apps and nav, one gate at a time. The wiring into
 * {@code GET /bootstrap} is proved over real HTTP in {@link ControlApiBootstrapProductTest}.
 */
class ProductDescriptorTest {

    /** Two Apps: `core` needs an active module and declares one entry per gate; `absent` needs a module nobody runs. */
    static final String DESCRIPTOR = """
            {"schema":"inspecto.product/1","id":"la","title":"Inspecto LA","shape":"domain","host":"la-app",
             "brand":{"name":"Inspecto LA"},"edition":"Advanced","distribution":"demo",
             "apps":[
              {"id":"core","title":"Core","version":"1.0.0","modules":["engine"],"nav":[
                {"id":"open","path":"open","label":"Open","icon":"heroicons_outline:share","blurb":"Always drawn."},
                {"id":"featureOn","path":"on","label":"On","feature":"featOn"},
                {"id":"featureOff","path":"off","label":"Off","feature":"featOff"},
                {"id":"featureUnknown","path":"unknown","label":"Unknown","feature":"neverReported"},
                {"id":"needsCap","path":"cap","label":"Cap","capability":"canOperateRuns"}]},
              {"id":"absent","title":"Absent","version":"2.0.0","modules":["engine","no-such-module"],"nav":[
                {"id":"hidden","path":"hidden","label":"Hidden"}]}]}""";

    private static final Map<String, Object> FEATURES = Map.of("featOn", true, "featOff", false);
    /** The one module the `core` App needs, ACTIVE and (where passed as the second set) enabled in the Space. */
    private static final Set<String> ENGINE = Set.of("engine");

    @AfterEach
    void clear() {
        System.clearProperty(ProductDescriptor.PROPERTY);
    }

    private static ProductDescriptor parse() {
        return ProductDescriptor.parse(DESCRIPTOR, "test");
    }

    @SuppressWarnings("unchecked")
    private static List<String> navIds(Map<String, Object> resolved) {
        return ((List<Map<String, Object>>) resolved.get("nav")).stream().map(n -> (String) n.get("id")).toList();
    }

    @Test
    void identityIsWhatASignInPageMayShow() {
        assertEquals(Map.of("id", "la", "title", "Inspecto LA", "shape", "domain", "host", "la-app",
                "brand", Map.of("name", "Inspecto LA")), parse().identity());
    }

    @Test
    void anAppIsInstalledOnlyWhenEveryModuleItNeedsIsActive() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apps = (List<Map<String, Object>>) parse().resolve(ENGINE, ENGINE, FEATURES, Set.of(), false).get("apps");
        assertEquals(true, apps.get(0).get("installed"));
        assertEquals(true, apps.get(0).get("enabled"));
        assertEquals(List.of(), apps.get(0).get("missing"));
        assertEquals(false, apps.get(1).get("installed"));
        assertEquals(false, apps.get(1).get("enabled"), "an App that is not installed is not enabled either");
        assertEquals(List.of("no-such-module"), apps.get(1).get("missing"), "the missing module is named");
    }

    @Test
    void anAppThatIsNotInstalledDrawsNoNav() {
        assertFalse(navIds(parse().resolve(ENGINE, ENGINE, FEATURES, Set.of(), false)).contains("hidden"));
        assertEquals(List.of(), navIds(parse().resolve(Set.of(), Set.of(), FEATURES, Set.of(), false)), "no active module, no nav at all");
    }

    @Test
    void anAppWhoseModulesThisSpaceSwitchedOffDrawsNoNavEvenWithoutAFeature() {
        Map<String, Object> r = parse().resolve(ENGINE, Set.of(), FEATURES, Set.of(), false);
        assertEquals(List.of(), navIds(r), "the 'open' entry has no feature of its own and still disappears: the App is not Enabled");
        @SuppressWarnings("unchecked")
        Map<String, Object> core = ((List<Map<String, Object>>) r.get("apps")).get(0);
        assertEquals(true, core.get("installed"));
        assertEquals(false, core.get("enabled"));
        assertEquals(List.of("engine"), core.get("switchedOff"), "the switched-off module is named");
    }

    @Test
    void anEntryWithAFeatureIsDrawnOnlyWhenTheFeatureIsTrue() {
        List<String> ids = navIds(parse().resolve(ENGINE, ENGINE, FEATURES, Set.of(), false));
        assertTrue(ids.contains("featureOn"));
        assertFalse(ids.contains("featureOff"), "a feature reported false hides the entry");
        assertFalse(ids.contains("featureUnknown"), "a feature the response does not report at all hides the entry");
    }

    @Test
    void aCapabilityGatesOnlyWhereAnAuthenticatorIsActive() {
        assertTrue(navIds(parse().resolve(ENGINE, ENGINE, FEATURES, Set.of(), false)).contains("needsCap"),
                "no Authenticator: there is no caller to check, as on Basic everywhere");
        assertFalse(navIds(parse().resolve(ENGINE, ENGINE, FEATURES, Set.of("canAuthorWorkbench"), true)).contains("needsCap"));
        assertTrue(navIds(parse().resolve(ENGINE, ENGINE, FEATURES, Set.of("canOperateRuns"), true)).contains("needsCap"));
    }

    @Test
    void theNavKeepsItsDrawingOrderAndOmitsAbsentFields() {
        Map<String, Object> r = parse().resolve(ENGINE, ENGINE, FEATURES, Set.of("canOperateRuns"), true);
        assertEquals(List.of("open", "featureOn", "needsCap"), navIds(r));
        @SuppressWarnings("unchecked")
        Map<String, Object> first = ((List<Map<String, Object>>) r.get("nav")).get(0);
        assertEquals(Map.of("app", "core", "id", "open", "path", "open", "label", "Open", "icon", "heroicons_outline:share",
                "blurb", "Always drawn."), first);
        @SuppressWarnings("unchecked")
        Map<String, Object> second = ((List<Map<String, Object>>) r.get("nav")).get(1);
        assertFalse(second.containsKey("icon"), "no icon declared, none written");
    }

    @Test
    void aDescriptorThatDoesNotParseIsRefusedNamingWhy() {
        assertTrue(assertThrows(IllegalStateException.class, () -> ProductDescriptor.parse("{nope", "f.json"))
                .getMessage().contains("f.json is not JSON"));
        assertTrue(assertThrows(IllegalStateException.class, () -> ProductDescriptor.parse(
                DESCRIPTOR.replace("inspecto.product/1", "inspecto.product/9"), "f.json")).getMessage()
                .contains("has schema 'inspecto.product/9', expected 'inspecto.product/1'"));
        assertTrue(assertThrows(IllegalStateException.class, () -> ProductDescriptor.parse(
                DESCRIPTOR.replace("\"id\":\"la\",", ""), "f.json")).getMessage().contains("f.json has no id"));
        assertTrue(assertThrows(IllegalStateException.class, () -> ProductDescriptor.parse(
                DESCRIPTOR.replace("\"path\":\"open\",", ""), "f.json")).getMessage()
                .contains("App 'core' nav entry has no path"));
    }

    @Test
    void theDescriptorIsReadFromThePropertyAndIsAbsentWithoutOne(@TempDir Path dir) throws Exception {
        assertTrue(ProductDescriptor.current().isEmpty(), "a target/classes run has no jar to sit beside, so no Product");
        Path f = dir.resolve("product.json");
        Files.writeString(f, DESCRIPTOR);
        System.setProperty(ProductDescriptor.PROPERTY, f.toString());
        assertEquals("la", ProductDescriptor.current().orElseThrow().id());
        Path missing = dir.resolve("none.json");
        System.setProperty(ProductDescriptor.PROPERTY, missing.toString());
        assertTrue(ProductDescriptor.current().isEmpty(), "a named file that does not exist is no Product, not an error");
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{\"schema\":\"inspecto.product/1\"}");
        System.setProperty(ProductDescriptor.PROPERTY, broken.toString());
        assertTrue(assertThrows(IllegalStateException.class, ProductDescriptor::current).getMessage().contains(broken.toString()));
    }
}
