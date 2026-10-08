package com.gamma.control;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import dev.toonformat.jtoon.JToon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.gamma.access.Roles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A role name the table does not define must not lift an Access Profile deny: {@code AccessGrants} treats any
 * unprofiled role as "allow everywhere", so {@link JobAuthority#capabilitiesNow} must drop undefined roles first,
 * as {@code DemoAuthenticator.authenticate} does. Covers the Job re-check and the Action Request approver check.
 */
class CapabilitiesNowUnknownRoleTest {

    /** admin's profile denies canApproveChanges and canAdminister; "ghost" is in no role table. */
    private static Path deniedAdmin(Path root) throws Exception {
        Path cat = Files.createDirectories(root.resolve("registry").resolve("access-catalog"));
        Files.writeString(cat.resolve("catalog.toon"), JToon.encode(Map.of("version", 1, "nodes", List.of(
                Map.of("id", "approve", "label", "Approve", "kind", "action", "capability", Roles.CAN_APPROVE_CHANGES),
                Map.of("id", "admin", "label", "Admin", "kind", "action", "capability", Roles.CAN_ADMINISTER)))));
        Path prof = Files.createDirectories(root.resolve("registry").resolve("access-profiles"));
        Files.writeString(prof.resolve("role-admin.toon"), JToon.encode(Map.of("subjectType", "role", "subjectId", "admin",
                "label", "admin", "grants", Map.of("approve", "deny", "admin", "deny"))));
        return root;
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    @Test
    void anUndefinedRoleDoesNotLiftTheJobReCheckDeny(@TempDir Path root) throws Exception {
        deniedAdmin(root);
        assertFalse(JobAuthority.capabilitiesNow(List.of("admin"), root).contains(Roles.CAN_ADMINISTER));
        assertFalse(JobAuthority.capabilitiesNow(List.of("admin", "ghost"), root).contains(Roles.CAN_ADMINISTER),
                "'ghost' grants nothing and must not void admin's deny");
    }

    @Test
    void anUndefinedRoleDoesNotMakeADeniedApproverOk(@TempDir Path root) throws Exception {
        deniedAdmin(root);
        Authenticators.forTest(new Authenticator() {
            @Override public Optional<Subject> authenticate(com.sun.net.httpserver.HttpExchange ex) { return Optional.empty(); }
            @Override public Optional<Map<String, List<String>>> principals(Path configRoot) {
                return Optional.of(Map.of("maker", List.of("operations"), "checker", List.of("admin", "ghost")));
            }
        });
        Map<String, Object> rec = Map.of("author", "maker", "coAuthors", List.of(), "status", ActionRequests.PENDING);
        assertEquals(ApproverCheck.NONE_ELIGIBLE, ActionRequestRoutes.approverCheck(root, rec),
                "authenticate would deny checker canApproveChanges, so no one is eligible");
    }
}
