package com.gamma.control;

import com.gamma.job.JobConfig;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** T5 residuals (a)+(b): the RUN path refuses an la.index.build Job whose owner is not its last editor, unless that
 *  editor's roles still grant canConfigureAccess. */
class IndexBuildOwnerRunAuthorityTest {

    @BeforeEach
    void arm() {
        Authenticators.forTest(new Authenticator() {
            @Override public Optional<Subject> authenticate(com.sun.net.httpserver.HttpExchange ex) { return Optional.empty(); }
        });
    }

    @AfterEach
    void disarm() { Authenticators.forTest(null); }

    private static JobConfig job(String type, String owner, String by, String roles) {
        return new JobConfig("ix", type, null, null, true, false,
                Map.of("owner", owner, JobConfig.UPDATED_BY, by, JobConfig.UPDATED_BY_ROLES, roles), null, null);
    }

    private static Optional<String> run(JobConfig c, Path root) {
        return JobAuthority.runAuthority(() -> root).refusal(c);
    }

    @Test
    void ownerEqualToLastEditorRuns(@TempDir Path root) {
        assertTrue(run(job("la.index.build", "bob", "bob", "developer"), root).isEmpty());
    }

    @Test
    void spoofedOwnerFromBeforeTheStampOrAChangedTemplateIsRefused(@TempDir Path root) {
        Optional<String> r = run(job("la.index.build", "victim", "bob", "developer"), root);
        assertTrue(r.isPresent() && r.get().contains("victim"), String.valueOf(r));
    }

    @Test
    void unauthoredJobIsRefused(@TempDir Path root) {
        assertTrue(run(job("la.index.build", "victim", "", ""), root).isPresent());
    }

    @Test
    void lastEditorStillHoldingConfigureAccessMayNameAnotherOwner(@TempDir Path root) {
        assertTrue(run(job("la.index.build", "analyst-9", "root", "admin"), root).isEmpty());
    }

    @Test
    void otherJobTypesAreNotAffected(@TempDir Path root) {
        assertTrue(run(job("maintenance", "victim", "bob", "developer"), root).isEmpty());
    }
}
