package com.gamma.signal;

import com.gamma.event.EventLog;
import com.gamma.signal.SignalField.Kind;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** SIGNAL-BOARD-1 SB-1a: the Signal Type catalog, the core declarations and the post-time check. */
class SignalTypeCatalogTest {

    private static SignalTypeProvider provider(String source, SignalTypeDecl... decls) {
        return new SignalTypeProvider() {
            @Override public List<SignalTypeDecl> signalTypes() { return List.of(decls); }
            @Override public String source() { return source; }
        };
    }

    private static SignalTypeDecl decl(String type, SignalField... fields) {
        return new SignalTypeDecl(type, type, "engine", List.of(fields), "4.0.0");
    }

    @Test
    void everySignalTypeConstantIsDeclaredByCoreWithFields() throws Exception {
        SignalTypeCatalog catalog = SignalTypeCatalog.assemble(List.of(new CoreSignalTypes()), List.of());
        int constants = 0;
        for (Field f : SignalType.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
            constants++;
            String type = (String) f.get(null);
            SignalTypeCatalog.Entry e = catalog.find(type).orElseThrow(() -> new AssertionError("undeclared: " + type));
            assertEquals("core", e.source());
            assertFalse(e.decl().fields().isEmpty(), type + " declares no fields");
        }
        assertEquals(30, constants, "SignalType holds 30 constants (SIGNAL-BOARD-1 §11.1)");
        assertEquals(52, catalog.entries().size(), "CoreSignalTypes declares 52 types (plan §11.6): update both together");
    }

    @Test
    void theInstalledCatalogFindsCoreThroughServiceLoader() {
        assertEquals("core", SignalTypeCatalog.installed().find("pipeline.commit").orElseThrow().source());
    }

    @Test
    void aTypeFromTwoProvidersIsAnAssemblyError() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> SignalTypeCatalog.assemble(
                List.of(provider("core", decl("a.b")), provider("module:x", decl("a.b"))), List.of()));
        assertTrue(e.getMessage().contains("core") && e.getMessage().contains("module:x"), e.getMessage());
    }

    @Test
    void aFamilyCoversItsMembersAndTheLongestFamilyWins() {
        SignalTypeCatalog c = SignalTypeCatalog.assemble(List.of(provider("module:x",
                decl("exchange.*"), decl("exchange.signal.*"), decl("exchange.signal.delivered"))), List.of());
        assertEquals("exchange.*", c.find("exchange.acme.done").orElseThrow().decl().type());
        assertEquals("exchange.signal.*", c.find("exchange.signal.other").orElseThrow().decl().type());
        assertEquals("exchange.signal.delivered", c.find("exchange.signal.delivered").orElseThrow().decl().type());
        assertTrue(c.find("exchange").isEmpty());
        assertTrue(c.find("exchanges.x").isEmpty());
    }

    @Test
    void aFamilyWithNoFieldsCarriesARunTimePayloadAndIsCheckedOnItsNameOnly() {
        SignalTypeCatalog c = SignalTypeCatalog.assemble(List.of(provider("module:exchange", decl("exchange.*"),
                decl("exchange.signal.delivered", SignalField.always("grant", Kind.STRING, "")))), List.of());
        SignalTypeCatalog.Entry family = c.find("exchange.acme.fraud.alert").orElseThrow();
        assertFalse(family.fieldsDeclared());
        assertEquals(List.of(), c.problems("exchange.acme.fraud.alert", Map.of("msisdn", "x")));
        assertTrue(c.find("exchange.signal.delivered").orElseThrow().fieldsDeclared(), "an exact type keeps its fields");
        assertFalse(c.problems("exchange.signal.delivered", Map.of("other", 1)).isEmpty());
    }

    @Test
    void aPackEmitIsListedWithoutFieldsAndNeverOverridesAProvider() {
        SignalTypeCatalog c = SignalTypeCatalog.assemble(List.of(provider("core", decl("a.b"))), List.of(
                new SignalTypeCatalog.PackEmit("acme.jar", "acme.detect", "acme.detected"),
                new SignalTypeCatalog.PackEmit("acme.jar", "acme.detect", "a.b"),
                new SignalTypeCatalog.PackEmit("acme.jar", "acme.detect", "NotDotted")));
        SignalTypeCatalog.Entry pack = c.find("acme.detected").orElseThrow();
        assertEquals("pack:acme.jar", pack.source());
        assertEquals("job-type:acme.detect", pack.decl().poster());
        assertFalse(pack.fieldsDeclared());
        assertEquals("core", c.find("a.b").orElseThrow().source());
        assertEquals(2, c.entries().size());
        assertEquals(List.of(), c.problems("acme.detected", Map.of("anything", 1)));
    }

    @Test
    void problemsNameTheUncataloguedTypeTheUndeclaredKeyAndTheMissingAlwaysField() {
        SignalTypeCatalog c = SignalTypeCatalog.assemble(List.of(provider("core", decl("a.b",
                SignalField.always("job", Kind.STRING, ""), SignalField.optional("rows", Kind.INTEGER, "")))), List.of());
        assertEquals(List.of(), c.problems("a.b", Map.of("job", "j", "chainDepth", 0)), "chainDepth is stamped");
        assertEquals(List.of(), c.problems("a.b", Map.of("job", "j", "rows", 3)));
        assertTrue(c.problems("x.y", Map.of()).get(0).contains("not catalogued"));
        List<String> p = c.problems("a.b", Map.of("extra", 1));
        assertEquals(2, p.size(), p.toString());
        assertTrue(p.get(0).contains("does not declare payload field 'extra'"), p.get(0));
        assertTrue(p.get(1).contains("always carries 'job'"), p.get(1));
    }

    @Test
    void aDeclarationRefusesABadNameAndARepeatedField() {
        assertThrows(IllegalArgumentException.class, () -> decl("nodots"));
        assertThrows(IllegalArgumentException.class, () -> decl("Upper.Case"));
        assertThrows(IllegalArgumentException.class, () -> decl("a.b",
                SignalField.always("x", Kind.STRING, ""), SignalField.optional("x", Kind.INTEGER, "")));
        assertTrue(decl("exchange.*").family());
    }

    /** The surefire run switches the check on for this module; this pins that a misfit posting fails loudly. */
    @Test
    void theCheckRefusesAMisfitPostingWhenSwitchedOn() {
        assertTrue(SignalPostCheck.enabled(), "-Dsignals.checkFields=true is set for the engine's tests (pom.xml)");
        EventLog ledger = EventLog.current();
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("job", "j", "run", "r", "trigger", "manual"));
        ledger.emit(post(SignalType.JOB_RUN_STARTED, "job", payload));   // fits

        payload.put("undeclared", 1);
        AssertionError e = assertThrows(AssertionError.class,
                () -> ledger.emit(post(SignalType.JOB_RUN_STARTED, "job", payload)));
        assertTrue(e.getMessage().contains("'undeclared'"), e.getMessage());
        assertThrows(AssertionError.class, () -> ledger.emit(post("never.declared", "job", Map.of())));
        assertThrows(AssertionError.class, () -> ledger.emit(post(SignalType.JOB_RUN_STARTED, "job", Map.of("job", "j"))),
                "an always field is missing");
        // A Decision Rule's emit-signal consequence chooses its own type and keys (D-SB8): let through.
        ledger.emit(post("decision-rule.my_rule", "decision-rule", Map.of("rule", "my_rule", "msisdn", "x")));
        // A fixture built and handed straight to a consumer is not a posting.
        assertNotNull(post("never.declared", "job", Map.of()));
        // Each misfit is also recorded, for a Run's worker thread whose throw nobody sees; these three were deliberate.
        assertEquals(3, SignalPostCheck.drainMisfits().size());
    }

    private static com.gamma.audit.Event post(String type, String sourceKind, Map<String, Object> payload) {
        return new Signal(null, type, null, null, Ref.of(sourceKind, "x"), null, null, null, null, null, null, payload, 1)
                .toEvent();
    }
}
