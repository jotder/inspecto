package com.gamma.actionrequests;

import com.gamma.decision.Consequences;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The module contributes {@code invoke-api} through the ServiceLoader consequence registry and the manifest declares it. */
class InvokeApiConsequenceContractTest {

    @Test
    void theRegistryFindsInvokeApiFromThisModule() {
        var found = Consequences.load(getClass().getClassLoader()).find("invoke-api").orElseThrow();
        assertInstanceOf(InvokeApiConsequence.class, found);
        assertEquals("integration", found.group());
        assertEquals(java.util.List.of("objects"), found.requires());
    }

    @Test
    void theManifestDeclaresTheConsequenceItProvides() {
        var manifest = com.gamma.module.ModuleManifests.load(getClass().getClassLoader()).manifests().stream()
                .filter(m -> m.id().equals("action-requests")).findFirst().orElseThrow();
        assertEquals(java.util.List.of("invoke-api"), manifest.provides().consequences());
        assertEquals(java.util.List.of("actionRequests"), manifest.provides().features());
        assertEquals(java.util.List.of("notify-channels"), manifest.requires().modules());
    }
}
