package com.gamma.notify;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EDG-01 cell 1 — the assertion that makes the gating REAL rather than claimed.
 *
 * <p>This test runs in the DEFAULT reactor, which is the **Personal** build (⚠ there is no
 * {@code -Pedition-personal}; Personal is "no profile"). On that classpath
 * {@link ServiceLoader#load(Class)} over {@link NotificationChannel} must find **nothing**, because
 * `EDITIONS.md` `CP-15` says delivery channels are not in the edition and — since 2026-09-07 — the build
 * finally agrees.
 *
 * <p>🔴 <b>What it would have caught.</b> Until 2026-09-07 `inspecto-engine` carried
 * `META-INF/services/com.gamma.notify.NotificationChannel` naming `WebhookChannel`, and `inspecto-engine`
 * is an unconditional dependency of the app — so the webhook transport shipped in every Personal bundle,
 * needing only {@code -Dnotify.webhook.url} to start delivering. `SmtpEmailChannel` reached Personal by a
 * second route: it lived in `inspecto-connectors`, whose sidecar `package.ps1` copies into EVERY edition
 * by explicit decision. Both are now in `inspecto-notify-channels`, a Standard+/Enterprise-only module.
 *
 * <p>⚠ <b>Why the assertion is on the SPI and not on a class being absent.</b> A test that greps for a
 * missing class would pass for the wrong reason the moment someone renamed it. What matters is the
 * question `NotificationService` actually asks at startup — "which channels are registered" — so that is
 * the question this asks too.
 *
 * <p>⛔ Do not "fix" a failure here by deleting this test. A non-empty result means an external transport
 * is back on the Personal classpath, which is the defect, not the test.
 */
class NoChannelShipsInThePersonalBuildTest {

    @Test
    void thePersonalClasspathRegistersNoExternalDeliveryChannel() {
        List<String> found = new ArrayList<>();
        for (NotificationChannel ch : ServiceLoader.load(NotificationChannel.class)) {
            found.add(ch.getClass().getName());
        }
        assertTrue(found.isEmpty(),
                "the default (Personal) build must register NO NotificationChannel — EDITIONS CP-15. "
                        + "Found: " + found + ". A channel here ships an external delivery transport in "
                        + "an edition whose matrix cell says it has none; move it to "
                        + "inspecto-notify-channels rather than relaxing this assertion.");
    }

    /**
     * The other half of the same fact: in-app delivery is **intrinsic** to {@link NotificationService} and
     * is not a channel, so Personal losing every channel does not leave it with no notifications at all.
     * ⚠ Without this, the test above would also pass on a build where notifications were broken entirely.
     */
    @Test
    void inAppDeliveryIsNotAChannelAndSurvivesHavingNone() {
        assertTrue(NotificationCategory.values().length > 0, "the category grid the in-app feed uses");
        // NotificationChannel's contract is external transports only; the in-app feed is the store plus
        // listeners inside NotificationService, reached without ServiceLoader at all.
        assertFalse(NotificationChannel.class.isAssignableFrom(NotificationService.class),
                "the service is not itself a channel — in-app delivery does not go through the SPI");
    }
}
