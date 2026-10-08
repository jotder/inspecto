package com.gamma.actionrequests;

import com.gamma.control.ApproverCheck;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MODULE-REORG-P7 (Action Requests) - the record-aware reading (an Action Request's author and co-authors are its makers; a record failing its integrity check reads unknown) over the base {@link ApproverCheck}. Pins the four outcomes of the approver-eligibility check BEFORE it moves
 * to its own base class: {@code none-eligible} (no Authenticator; the only approver is a maker), {@code ok} (a
 * non-maker approver exists), {@code unknown} (a record failing its integrity check; a directory that throws). Every
 * negative has a probe that would otherwise succeed (same fixture, the maker removed from the makers).
 */
class ActionRequestApproverCheckTest {

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

    private static Map<String, Object> request(String author) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("author", author);
        rec.put("coAuthors", List.of());
        rec.put("status", ActionRequests.PENDING);
        return rec;
    }

    @Test
    void noAuthenticatorMeansNoSubjectSoNoOneCanDecide(@TempDir Path root) {
        Authenticators.forTest(null);
        assertEquals(ApproverCheck.NONE_ELIGIBLE, ActionRequestRoutes.approverCheck(root, request("maker")));
        assertEquals(ApproverCheck.NONE_ELIGIBLE,
                ApproverCheck.check(root, Set.of(), "canApproveChanges", false), "even with no makers at all");
    }

    @Test
    void aNonMakerApproverIsOkAndTheMakerAloneIsNoneEligible(@TempDir Path root) {
        Authenticators.forTest(enumerating(Map.of("maker", List.of("admin"), "checker", List.of("admin"))));
        assertEquals(ApproverCheck.OK, ActionRequestRoutes.approverCheck(root, request("maker")), "checker is outside the makers");

        Authenticators.forTest(enumerating(Map.of("maker", List.of("admin"))));
        assertEquals(ApproverCheck.NONE_ELIGIBLE, ActionRequestRoutes.approverCheck(root, request("maker")),
                "the only approver is the maker: four-eyes leaves no one");
        assertEquals(ApproverCheck.OK, ApproverCheck.check(root, Set.of(), "canApproveChanges", true),
                "probe: with no makers the same directory is ok");
    }

    @Test
    void aCoAuthorIsAMakerToo(@TempDir Path root) {
        Authenticators.forTest(enumerating(Map.of("co", List.of("admin"))));
        Map<String, Object> rec = request("someone-else");
        assertEquals(ApproverCheck.OK, ActionRequestRoutes.approverCheck(root, rec), "probe: co is not (yet) a maker");
        rec.put("coAuthors", List.of("co"));
        assertEquals(ApproverCheck.NONE_ELIGIBLE, ActionRequestRoutes.approverCheck(root, rec),
                "the Decision Rule's editors are makers: the only approver is one of them");
    }

    @Test
    void aRecordFailingItsIntegrityCheckReadsUnknown(@TempDir Path root) {
        Authenticators.forTest(enumerating(Map.of("checker", List.of("admin"))));
        Map<String, Object> rec = request("maker");
        assertEquals(ApproverCheck.OK, ActionRequestRoutes.approverCheck(root, rec), "probe: the intact record is ok");
        rec.put("integrity", "invalid");
        assertEquals(true, ActionRequests.invalid(rec), "the fixture really is an invalid record");
        assertEquals(ApproverCheck.UNKNOWN, ActionRequestRoutes.approverCheck(root, rec, new HashMap<>()));
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
