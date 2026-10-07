package com.gamma.notify.testkit;

import com.gamma.notify.Notification;
import com.gamma.notify.NotificationChannel;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Proves the NotificationChannel TCK can fail (MODULE-REORG-1 P5b): broken in-test channels must go red, a sound one green. */
class NotificationChannelTckSelfTest {

    private static final String SECRET = "tck-secret-val";

    /** A channel whose misbehaviour is chosen by the test. */
    private static class Chan implements NotificationChannel {
        final String id;
        Chan(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public boolean configured() { return false; }
        @Override public void deliver(Notification n) throws Exception {
            if (n == null) throw new NullPointerException("notification");
            throw new IllegalStateException("not configured");
        }
    }

    private static NotificationChannelContract contract(NotificationChannel c) {
        return new NotificationChannelContract() {
            @Override protected NotificationChannel channel() { return c; }
            @Override protected NotificationChannel unconfigured() { return c; }
            @Override protected String secret() { return SECRET; }
        };
    }

    @Test
    void aSoundChannelPasses() {
        NotificationChannelContract c = contract(new Chan("good"));
        assertDoesNotThrow(() -> {
            c.idIsALowerCaseToken();
            c.idIsServedByExactlyOneChannel();
            c.theUnconfiguredChannelSaysSoQuickly();
            c.deliveringWhileUnconfiguredFailsLoudly();
            c.deliveringNothingFails();
            c.aFailureNeverEchoesTheSecret();
        });
    }

    @Test
    void aBadIdIsCaught() {
        assertThrows(AssertionFailedError.class, () -> contract(new Chan("Email")).idIsALowerCaseToken());
        assertThrows(AssertionFailedError.class, () -> contract(new Chan("")).idIsALowerCaseToken());
    }

    @Test
    void aDuplicateIdIsCaught() {
        NotificationChannel a = new Chan("dup");
        NotificationChannelContract c = new NotificationChannelContract() {
            @Override protected NotificationChannel channel() { return a; }
            @Override protected NotificationChannel unconfigured() { return a; }
            @Override protected Iterable<NotificationChannel> registered() { return List.of(a, new Chan("dup")); }
        };
        assertThrows(AssertionFailedError.class, c::idIsServedByExactlyOneChannel);
    }

    @Test
    void aChannelThatClaimsToBeConfiguredOrFlipsIsCaught() {
        NotificationChannel claims = new Chan("claims") { @Override public boolean configured() { return true; } };
        assertThrows(AssertionFailedError.class, () -> contract(claims).theUnconfiguredChannelSaysSoQuickly());
        AtomicInteger n = new AtomicInteger();
        NotificationChannel flips = new Chan("flips") { @Override public boolean configured() { return n.incrementAndGet() % 2 == 0; } };
        assertThrows(AssertionFailedError.class, () -> contract(flips).theUnconfiguredChannelSaysSoQuickly());
    }

    @Test
    void aChannelThatSilentlyDropsIsCaught() {
        NotificationChannel drops = new Chan("drops") { @Override public void deliver(Notification x) { } };
        assertThrows(AssertionFailedError.class, () -> contract(drops).deliveringWhileUnconfiguredFailsLoudly());
        assertThrows(AssertionFailedError.class, () -> contract(drops).deliveringNothingFails());
    }

    @Test
    void aChannelThatDialsOnConfiguredIsCaught() {
        NotificationChannel slow = new Chan("slow") {
            @Override public boolean configured() {
                try { Thread.sleep(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return false;
            }
        };
        assertThrows(AssertionFailedError.class, () -> contract(slow).theUnconfiguredChannelSaysSoQuickly());
    }

    @Test
    void aFailureThatEchoesTheSecretIsCaught() {
        NotificationChannel leaky = new Chan("leaky") {
            @Override public void deliver(Notification x) throws Exception {
                throw new IllegalStateException("auth failed with token " + SECRET);
            }
        };
        assertThrows(AssertionFailedError.class, () -> contract(leaky).aFailureNeverEchoesTheSecret());
    }
}
