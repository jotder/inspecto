package com.gamma.control;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MODULE-REORG-P7 (Action Requests): the base approver-eligibility check ({@link ApproverCheck}) the Pending Change
 * hold and the approver roster call with the Action Requests module ABSENT - none-eligible (no Authenticator; the only
 * approver is a maker), ok (a non-maker approver exists), unknown (a directory that throws). Every negative has a probe
 * that would otherwise succeed (same fixture, the maker removed from the makers). The Action Request's own record-aware
 * reading is pinned in the module ({@code ActionRequestApproverCheckTest}).
 */
class ApproverCheckParityTest {

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private static Authenticator enumerating(Map<String, List<String>> principals) {
        return new Authenticator() {
            @Override public Optional<Subject> authenticate(com.sun.net.httpserver.HttpExchange ex) { return Optional.empty(); }
            @Override public Optional<Map<String, List<String>>> principals(Path configRoot) { return Optional.of(principals); }
        };
    }

    @Test
    void noAuthenticatorMeansNoSubjectSoNoOneCanDecide(@TempDir Path root) {
        Authenticators.forTest(null);
        assertEquals(ApproverCheck.NONE_ELIGIBLE, ApproverCheck.check(root, Set.of("maker"), "canApproveChanges", true));
        assertEquals(ApproverCheck.NONE_ELIGIBLE, ApproverCheck.check(root, Set.of(), "canApproveChanges", false),
                "even with no makers at all");
    }

    @Test
    void aNonMakerApproverIsOkAndTheMakerAloneIsNoneEligible(@TempDir Path root) {
        Authenticators.forTest(enumerating(Map.of("maker", List.of("admin"), "checker", List.of("admin"))));
        assertEquals(ApproverCheck.OK, ApproverCheck.check(root, Set.of("maker"), "canApproveChanges", true),
                "checker is outside the makers");

        Authenticators.forTest(enumerating(Map.of("maker", List.of("admin"))));
        assertEquals(ApproverCheck.NONE_ELIGIBLE, ApproverCheck.check(root, Set.of("maker"), "canApproveChanges", true),
                "the only approver is the maker: four-eyes leaves no one");
        assertEquals(ApproverCheck.OK, ApproverCheck.check(root, Set.of(), "canApproveChanges", true),
                "probe: with no makers the same directory is ok");
    }

    @Test
    void aCoMakerIsExcludedToo(@TempDir Path root) {
        Authenticators.forTest(enumerating(Map.of("co", List.of("admin"))));
        assertEquals(ApproverCheck.OK, ApproverCheck.check(root, Set.of("someone-else"), "canApproveChanges", true),
                "probe: co is not (yet) a maker");
        assertEquals(ApproverCheck.NONE_ELIGIBLE, ApproverCheck.check(root, Set.of("someone-else", "co"), "canApproveChanges", true),
                "the only approver is one of the makers");
    }

    @Test
    void aDirectoryThatThrowsReadsUnknownNeverAnError(@TempDir Path root) {
        Authenticators.forTest(new Authenticator() {
            @Override public Optional<Subject> authenticate(com.sun.net.httpserver.HttpExchange ex) { return Optional.empty(); }
            @Override public Optional<Map<String, List<String>>> principals(Path configRoot) {
                throw new IllegalStateException("corrupt demo-users.toon");
            }
        });
        assertEquals(ApproverCheck.UNKNOWN, ApproverCheck.check(root, Set.of(), "canApproveChanges", false));
    }
}
