package com.gamma.notify.testkit;

import com.gamma.notify.Notification;
import com.gamma.notify.NotificationChannel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The platform's TCK for a {@link NotificationChannel} (MODULE-REORG-1 P5b): what the dispatcher and the preference grid
 * assume of every delivery channel. Every check runs on an UNCONFIGURED instance, so no message ever leaves the process.
 *
 * <p>Defects each test catches:
 * <ul>
 *   <li>{@link #idIsALowerCaseToken} - the id is the key of the preference grid and of {@code ChannelConfig}; a blank or
 *       mixed-case one never matches a stored preference.</li>
 *   <li>{@link #idIsServedByExactlyOneChannel} - two channels with one id: preferences route to whichever loads first.</li>
 *   <li>{@link #theUnconfiguredChannelSaysSoQuickly} - {@code configured()} is called on the request thread to decide
 *       whether to offer the channel; one that dials out, throws, or answers differently each call breaks the grid.</li>
 *   <li>{@link #deliveringWhileUnconfiguredFailsLoudly} - a channel that returns normally without a destination drops the
 *       notification silently, and the dispatcher records it as delivered.</li>
 *   <li>{@link #deliveringNothingFails} - {@code deliver(null)} must not be accepted as a delivery.</li>
 *   <li>{@link #aFailureNeverEchoesTheSecret} - the dispatcher stores the failure message in the delivery record and
 *       shows it to the operator; a token or password in it is a disclosure.</li>
 * </ul>
 * Not covered: delivery to a real endpoint, and a positive path; each channel's own tests (SMTP/webhook egress) keep those.
 */
public abstract class NotificationChannelContract {

    private static final Pattern TOKEN = Pattern.compile("[a-z][a-z0-9_-]*");

    /** The channel under test, configured as production would be (used for id and uniqueness only). */
    protected abstract NotificationChannel channel();

    /** A channel of the same type with NO destination configured (so {@code configured()} is false) but its secret set. */
    protected abstract NotificationChannel unconfigured();

    /** The secret {@link #unconfigured()} carries (a token, a password), or null if the channel has none. */
    protected String secret() {
        return null;
    }

    /** Every channel the runtime would see; a seam only so the self-test can plant a duplicate. */
    protected Iterable<NotificationChannel> registered() {
        return ServiceLoader.load(NotificationChannel.class);
    }

    @Test
    void idIsALowerCaseToken() {
        String id = channel().id();
        assertNotNull(id, "id()");
        assertTrue(TOKEN.matcher(id).matches(), "channel id '" + id + "' must match " + TOKEN);
    }

    @Test
    void idIsServedByExactlyOneChannel() {
        String id = channel().id();
        List<String> serving = new ArrayList<>();
        for (NotificationChannel c : registered())
            if (id.equalsIgnoreCase(c.id())) serving.add(c.getClass().getName());
        assertTrue(serving.size() <= 1, "channel id '" + id + "' is served by several channels: " + serving);
    }

    @Test
    void theUnconfiguredChannelSaysSoQuickly() {
        NotificationChannel c = unconfigured();
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertFalse(c.configured(), "unconfigured() must build a channel whose configured() is false");
            assertEquals(c.configured(), c.configured(), "configured() must not change between calls");
        });
    }

    @Test
    void deliveringWhileUnconfiguredFailsLoudly() {
        NotificationChannel c = unconfigured();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThrows(Exception.class, () -> c.deliver(sample()),
                        "an unconfigured channel must refuse to deliver, not return as if it had"));
    }

    @Test
    void deliveringNothingFails() {
        NotificationChannel c = unconfigured();
        assertThrows(Exception.class, () -> c.deliver(null), "deliver(null) must fail");
    }

    @Test
    void aFailureNeverEchoesTheSecret() {
        String secret = secret();
        if (secret == null) return;
        NotificationChannel c = unconfigured();
        try {
            c.deliver(sample());
        } catch (Throwable t) {
            for (Throwable x = t; x != null; x = x.getCause())
                assertFalse(String.valueOf(x.getMessage()).contains(secret), "a delivery failure echoed the channel's secret: " + x);
        }
    }

    private static Notification sample() {
        return Notification.create("general", "tck", "tck-1", "TCK title", "TCK body", null);
    }
}
