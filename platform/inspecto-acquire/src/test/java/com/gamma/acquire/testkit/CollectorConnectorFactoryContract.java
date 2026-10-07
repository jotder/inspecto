package com.gamma.acquire.testkit;

import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.acquire.ConnectionProfile;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The platform's TCK for a {@link CollectorConnectorFactory} (MODULE-REORG-1 P5b): the rules every Collector transport
 * must keep, whoever wrote it. A concrete subclass names the factory under test; it needs no host, no processor and no
 * network. The contract is what the SPI's callers ({@code CollectorConnectors.validate} at save time,
 * {@code forConfig} at run time) already assume.
 *
 * <p>Each test states the defect it catches:
 * <ul>
 *   <li>{@link #schemeIsALowerCaseToken} - a blank or mixed-case scheme is never matched by the case-folded lookup, so
 *       the connector is silently unreachable.</li>
 *   <li>{@link #schemeIsServedByExactlyOneFactory} - two factories on the classpath for one scheme: the first in
 *       ServiceLoader order wins, and which one that is depends on jar order.</li>
 *   <li>{@link #validateNeverOpensANetworkConnection} - a validate that dials the host blocks the save request (and
 *       can be used to probe the network); TEST-NET-1 192.0.2.1 is unroutable, so a connecting validate hangs or fails
 *       slowly and trips the 1 s bound.</li>
 *   <li>{@link #validateRefusesOnlyWithAnIllegalArgumentException} - the save seam catches
 *       {@link IllegalArgumentException} and turns it into a 422; any other type becomes a 500.</li>
 *   <li>{@link #aRefusalNeverEchoesTheCredential} - the refusal message reaches the operator and the audit trail;
 *       a password in it is a leak.</li>
 *   <li>{@link #validateIsRepeatable} - a factory that remembers the last profile (or counts calls) answers differently
 *       the second time.</li>
 * </ul>
 * Not covered (needs a PipelineConfig and a real endpoint): {@code create(...)} independence and egress behaviour; see
 * the P5b record in the architecture plan.
 */
public abstract class CollectorConnectorFactoryContract {

    /** A password no real credential resembles; the contract asserts it never appears in a refusal. */
    protected static final String SECRET = "S3CR3T-TCK-PW";
    /** RFC 5737 TEST-NET-1: never routable, so any dial attempt cannot succeed quickly. */
    protected static final String UNROUTABLE_HOST = "192.0.2.1";

    /** The factory under test. A fresh instance per call is fine; the contract itself checks statelessness. */
    protected abstract CollectorConnectorFactory factory();

    /** A profile for the factory's scheme that points at an unroutable host and carries {@link #SECRET}. */
    protected ConnectionProfile profile() {
        return new ConnectionProfile("tck-conn", factory().scheme(), UNROUTABLE_HOST, 1, null, null,
                "tck-user", SECRET, Map.of(), null);
    }

    @Test
    void schemeIsALowerCaseToken() {
        String s = factory().scheme();
        assertNotNull(s, "scheme()");
        assertFalse(s.isBlank(), "scheme() must not be blank");
        assertEquals(s.trim().toLowerCase(), s, "scheme() must be trimmed lower case: lookups fold the profile's connector");
        assertNotEquals("local", s, "'local' is the built-in transport and is never served by a factory");
    }

    /** Every factory registered on the classpath; a seam only so the self-test can plant a duplicate. */
    protected Iterable<CollectorConnectorFactory> registered() {
        return ServiceLoader.load(CollectorConnectorFactory.class);
    }

    @Test
    void schemeIsServedByExactlyOneFactory() {
        String scheme = factory().scheme();
        List<String> serving = new ArrayList<>();
        for (CollectorConnectorFactory f : registered())
            if (f.scheme().equalsIgnoreCase(scheme)) serving.add(f.getClass().getName());
        assertTrue(serving.size() <= 1, "scheme '" + scheme + "' is served by several factories: " + serving);
    }

    @Test
    void validateNeverOpensANetworkConnection() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> { outcome(); }, "validate() must not dial the host");
    }

    @Test
    void validateRefusesOnlyWithAnIllegalArgumentException() {
        Throwable t = thrown();
        if (t != null) {
            assertInstanceOf(IllegalArgumentException.class, t, "validate() may refuse only with IllegalArgumentException");
            assertNotNull(t.getMessage(), "a refusal needs a message naming what is wrong");
            assertFalse(t.getMessage().isBlank(), "a refusal needs a message naming what is wrong");
        }
    }

    @Test
    void aRefusalNeverEchoesTheCredential() {
        Throwable t = thrown();
        if (t != null && t.getMessage() != null)
            assertFalse(t.getMessage().contains(SECRET), "a refusal message must not echo the connection's password");
    }

    @Test
    void validateIsRepeatable() {
        assertEquals(outcome(), outcome(), "validate() on the same profile must answer the same way every time");
    }

    private Throwable thrown() {
        try {
            factory().validate(profile());
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private String outcome() {
        Throwable t = thrown();
        return t == null ? "ok" : t.getClass().getName() + ": " + t.getMessage();
    }
}
