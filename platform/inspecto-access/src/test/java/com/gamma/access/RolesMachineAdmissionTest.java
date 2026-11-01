package com.gamma.access;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OIDC-CC-INTEGRATION-ROLE-1 (operator decisions B1 + C2, 2026-11-01): {@link Roles#admitMachine} admits a machine Subject only
 * in a Space whose {@code roles.toon} names its client in {@code identity.machineClients}, and keeps only
 * {@link Roles#MACHINE_CAPABILITIES}. A person's Subject passes through untouched.
 */
class RolesMachineAdmissionTest {

    private static Subject machine(String client, Set<String> caps) {
        return new Subject("client:" + client, caps, null, Map.of(), null, null, "svc-uuid");
    }

    @Test
    void aNamedMachineKeepsOnlyAllowlistedCapabilities(@TempDir Path root) throws Exception {
        Roles.write(root, Map.of(), List.of(), List.of("fraudpro"));
        Subject in = machine("fraudpro", Set.of(Roles.CAN_OPEN_INBOUND_INVESTIGATION, Roles.CAN_MANAGE_INCIDENTS,
                Roles.CAN_REVEAL_LINK_ENTITIES, Roles.CAN_ADMINISTER));
        Subject out = Roles.admitMachine(root, in);
        assertEquals(Set.of(Roles.CAN_OPEN_INBOUND_INVESTIGATION), out.capabilities());
        assertEquals("client:fraudpro", out.id());
        assertEquals("svc-uuid", out.machineSub(), "still a machine");
    }

    @Test
    void aMachineIsRefusedWhereItsClientIsNotNamedOrNoSpaceIsBound(@TempDir Path root) throws Exception {
        Subject m = machine("fraudpro", Set.of(Roles.CAN_OPEN_INBOUND_INVESTIGATION));
        assertEquals(403, assertThrows(ApiException.class, () -> Roles.admitMachine(root, m)).status, "no roles.toon");
        assertEquals(403, assertThrows(ApiException.class, () -> Roles.admitMachine(null, m)).status, "no Space bound");
        Roles.write(root, Map.of(), List.of(), List.of("other"));
        assertEquals(403, assertThrows(ApiException.class, () -> Roles.admitMachine(root, m)).status, "another client");
        Files.writeString(root.resolve(Roles.FILE), "roles: [ broken");
        assertEquals(403, assertThrows(ApiException.class, () -> Roles.admitMachine(root, m)).status, "unreadable doc");
    }

    @Test
    void aPersonIsUnaffected(@TempDir Path root) {
        Subject person = new Subject("analyst-1", Set.of(Roles.CAN_MANAGE_INCIDENTS, Roles.CAN_ADMINISTER));
        assertSame(person, Roles.admitMachine(root, person));
    }

    @Test
    void machineClientsRoundTripAndAreValidated(@TempDir Path root) throws Exception {
        Roles.write(root, Map.of(), List.of("department"), List.of("fraudpro", "casemgr"));
        assertEquals(List.of("fraudpro", "casemgr"), Roles.load(root).machineClients());
        assertEquals(List.of("department"), Roles.load(root).attributeClaims(), "the other identity list survives");
        assertEquals(List.of("a"), Roles.machineClients(Map.of("machineClients", List.of(" a ", "a"))));
        assertEquals(422, assertThrows(ApiException.class, () -> Roles.machineClients(Map.of("machineClients", "a"))).status);
        assertEquals(422, assertThrows(ApiException.class, () -> Roles.machineClients(Map.of("machineClients", List.of(" ")))).status);
    }

    @Test
    void theIntegrationSeedRoleGrantsOnlyTheInboundCapability() {
        assertEquals(Set.of(Roles.CAN_OPEN_INBOUND_INVESTIGATION), Roles.SEED.get("integration").capabilities());
        assertTrue(Roles.KNOWN_CAPABILITIES.containsAll(Roles.MACHINE_CAPABILITIES));
        for (var e : Roles.SEED.entrySet())
            if (!e.getKey().equals("integration") && !e.getKey().equals("super"))
                assertTrue(!e.getValue().capabilities().contains(Roles.CAN_OPEN_INBOUND_INVESTIGATION), e.getKey());
    }
}
