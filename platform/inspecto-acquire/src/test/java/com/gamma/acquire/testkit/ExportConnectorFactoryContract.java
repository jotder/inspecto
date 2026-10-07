package com.gamma.acquire.testkit;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ExportConnectorFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The platform's TCK for an {@link ExportConnectorFactory} (MODULE-REORG-1 P5b). Defects caught:
 * <ul>
 *   <li>{@link #schemeIsALowerCaseToken} - {@code forProfile} folds case; a blank or mixed-case scheme is unreachable.</li>
 *   <li>{@link #schemeIsServedByExactlyOneFactory} - two factories for one scheme: jar order decides which exports.</li>
 *   <li>{@link #buildingAnExporterNeverDials} - constructing the exporter must be lazy: it runs on the request thread
 *       that starts an export; TEST-NET-1 192.0.2.1 is unroutable, so a dialling constructor trips the 1 s bound.</li>
 *   <li>{@link #exporterRefusesOnlyWithAnIllegalArgumentException} - the callers map IllegalArgumentException to a 422.</li>
 *   <li>{@link #aRefusalNeverEchoesTheCredential} - the message reaches the operator and the audit trail.</li>
 * </ul>
 */
public abstract class ExportConnectorFactoryContract {

    protected static final String SECRET = "S3CR3T-TCK-PW";
    protected static final String UNROUTABLE_HOST = "192.0.2.1";

    protected abstract ExportConnectorFactory factory();

    protected ConnectionProfile profile() {
        return new ConnectionProfile("tck-conn", factory().scheme(), UNROUTABLE_HOST, 1, null, null,
                "tck-user", SECRET, Map.of(), null);
    }

    @Test
    void schemeIsALowerCaseToken() {
        String s = factory().scheme();
        assertNotNull(s, "scheme()");
        assertFalse(s.isBlank(), "scheme() must not be blank");
        assertEquals(s.trim().toLowerCase(), s, "scheme() must be trimmed lower case");
    }

    /** Every factory registered on the classpath; a seam only so the self-test can plant a duplicate. */
    protected Iterable<ExportConnectorFactory> registered() {
        return ServiceLoader.load(ExportConnectorFactory.class);
    }

    @Test
    void schemeIsServedByExactlyOneFactory() {
        String scheme = factory().scheme();
        List<String> serving = new ArrayList<>();
        for (ExportConnectorFactory f : registered())
            if (f.scheme().equalsIgnoreCase(scheme)) serving.add(f.getClass().getName());
        assertTrue(serving.size() <= 1, "scheme '" + scheme + "' is served by several factories: " + serving);
    }

    @Test
    void buildingAnExporterNeverDials() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> { thrown(); }, "exporter() must not dial the host");
    }

    @Test
    void exporterRefusesOnlyWithAnIllegalArgumentException() {
        Throwable t = thrown();
        if (t != null) assertInstanceOf(IllegalArgumentException.class, t, "exporter() may refuse only with IllegalArgumentException");
    }

    @Test
    void aRefusalNeverEchoesTheCredential() {
        Throwable t = thrown();
        if (t != null && t.getMessage() != null)
            assertFalse(t.getMessage().contains(SECRET), "a refusal message must not echo the connection's password");
    }

    private Throwable thrown() {
        try {
            factory().exporter(profile());
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
