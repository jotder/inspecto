package com.gamma.control;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.job.JobConfig;
import com.gamma.job.PostgresPublishJobType;
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
 * ASSURE-BI-PUBLICATION-1: the author a {@code publish.postgres} run acts as — its last editor, with the capabilities
 * the role table grants their recorded roles NOW (an Access Profile deny applies), and {@code OPEN} with no
 * Authenticator.
 */
class PublishAuthorityTest {

    @AfterEach
    void disarm() { Authenticators.forTest(null); }

    private static JobConfig job(String by, String roles) {
        return new JobConfig("pub", "publish.postgres", null, null, true, false,
                Map.of(JobConfig.UPDATED_BY, by, JobConfig.UPDATED_BY_ROLES, roles), null, null);
    }

    @Test
    void noAuthenticatorIsOpen(@TempDir Path root) {
        assertEquals(PostgresPublishJobType.Author.OPEN, JobAuthority.publishAuthority(() -> root).authorOf(job("a", "admin")));
    }

    @Test
    void theLastEditorsRolesAreReResolvedAndADenyApplies(@TempDir Path root) throws Exception {
        Authenticators.forTest(new Authenticator() {
            @Override public Optional<Subject> authenticate(com.sun.net.httpserver.HttpExchange ex) { return Optional.empty(); }
        });
        PostgresPublishJobType.Author a = JobAuthority.publishAuthority(() -> root).authorOf(job("alice", "admin"));
        assertFalse(a.open());
        assertEquals("alice", a.id());
        assertTrue(a.capabilities().contains(Roles.CAN_ADMINISTER), "seeded admin holds canAdminister");

        Path cat = Files.createDirectories(root.resolve("registry").resolve("access-catalog"));
        Files.writeString(cat.resolve("catalog.toon"), JToon.encode(Map.of("version", 1, "nodes", List.of(
                Map.of("id", "admin", "label", "Admin", "kind", "action", "capability", Roles.CAN_ADMINISTER)))));
        Path prof = Files.createDirectories(root.resolve("registry").resolve("access-profiles"));
        Files.writeString(prof.resolve("role-admin.toon"), JToon.encode(Map.of("subjectType", "role", "subjectId", "admin",
                "label", "admin", "grants", Map.of("admin", "deny"))));
        assertFalse(JobAuthority.publishAuthority(() -> root).authorOf(job("alice", "admin")).capabilities()
                .contains(Roles.CAN_ADMINISTER), "a deny granted after the save is seen at run time");
    }
}
