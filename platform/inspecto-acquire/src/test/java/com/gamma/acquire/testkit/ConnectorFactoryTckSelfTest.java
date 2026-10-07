package com.gamma.acquire.testkit;

import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ExportConnector;
import com.gamma.acquire.ExportConnectorFactory;
import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the connector-factory TCKs can fail (MODULE-REORG-1 P5b): each assertion is run against a deliberately broken
 * in-test implementer and must go red; a well-behaved one must stay green.
 */
class ConnectorFactoryTckSelfTest {

    /** A configurable factory; the broken behaviours are chosen per test. */
    private record Collector(String scheme, Consumer<ConnectionProfile> validate) implements CollectorConnectorFactory {
        @Override public CollectorConnector create(PipelineConfig cfg) { throw new UnsupportedOperationException(); }
        @Override public void validate(ConnectionProfile p) { validate.accept(p); }
    }

    private static CollectorConnectorFactoryContract contract(CollectorConnectorFactory f) {
        return new CollectorConnectorFactoryContract() {
            @Override protected CollectorConnectorFactory factory() { return f; }
        };
    }

    private static final Consumer<ConnectionProfile> NOOP = p -> { };

    @Test
    void aWellBehavedFactoryPasses() {
        CollectorConnectorFactoryContract c = contract(new Collector("good", p -> {
            if (p.host() == null) throw new IllegalArgumentException("host is required");
        }));
        assertDoesNotThrow(() -> {
            c.schemeIsALowerCaseToken();
            c.schemeIsServedByExactlyOneFactory();
            c.validateNeverOpensANetworkConnection();
            c.validateRefusesOnlyWithAnIllegalArgumentException();
            c.aRefusalNeverEchoesTheCredential();
            c.validateIsRepeatable();
        });
    }

    @Test
    void aBlankOrMixedCaseSchemeIsCaught() {
        assertThrows(AssertionFailedError.class, () -> contract(new Collector(" ", NOOP)).schemeIsALowerCaseToken());
        assertThrows(AssertionFailedError.class, () -> contract(new Collector("Ftp", NOOP)).schemeIsALowerCaseToken());
        assertThrows(AssertionFailedError.class, () -> contract(new Collector("local", NOOP)).schemeIsALowerCaseToken());
    }

    @Test
    void twoFactoriesForOneSchemeAreCaught() {
        CollectorConnectorFactory a = new Collector("dup", NOOP);
        CollectorConnectorFactory b = new Collector("dup", NOOP);
        CollectorConnectorFactoryContract c = new CollectorConnectorFactoryContract() {
            @Override protected CollectorConnectorFactory factory() { return a; }
            @Override protected Iterable<CollectorConnectorFactory> registered() { return List.of(a, b); }
        };
        assertThrows(AssertionFailedError.class, c::schemeIsServedByExactlyOneFactory);
    }

    @Test
    void aValidateThatBlocksLikeADialIsCaught() {
        CollectorConnectorFactoryContract c = contract(new Collector("slow", p -> {
            try { Thread.sleep(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        assertThrows(AssertionFailedError.class, c::validateNeverOpensANetworkConnection);
    }

    @Test
    void aNonIllegalArgumentRefusalIsCaught() {
        CollectorConnectorFactoryContract c = contract(new Collector("rude", p -> { throw new IllegalStateException("boom"); }));
        assertThrows(AssertionFailedError.class, c::validateRefusesOnlyWithAnIllegalArgumentException);
    }

    @Test
    void aMessagelessRefusalIsCaught() {
        CollectorConnectorFactoryContract c = contract(new Collector("mute", p -> { throw new IllegalArgumentException(); }));
        assertThrows(AssertionFailedError.class, c::validateRefusesOnlyWithAnIllegalArgumentException);
    }

    @Test
    void aRefusalThatEchoesThePasswordIsCaught() {
        CollectorConnectorFactoryContract c = contract(new Collector("leaky",
                p -> { throw new IllegalArgumentException("login failed for password " + p.password()); }));
        assertThrows(AssertionFailedError.class, c::aRefusalNeverEchoesTheCredential);
    }

    @Test
    void aFactoryThatRemembersItsCallsIsCaught() {
        AtomicInteger calls = new AtomicInteger();
        CollectorConnectorFactoryContract c = contract(new Collector("stateful", p -> {
            if (calls.incrementAndGet() > 1) throw new IllegalArgumentException("already used");
        }));
        assertThrows(AssertionFailedError.class, c::validateIsRepeatable);
    }

    // ------------------------------------------------------------ export

    private static ExportConnectorFactoryContract export(String scheme, Function<ConnectionProfile, ExportConnector> f) {
        ExportConnectorFactory factory = new ExportConnectorFactory() {
            @Override public String scheme() { return scheme; }
            @Override public ExportConnector exporter(ConnectionProfile p) { return f.apply(p); }
        };
        return new ExportConnectorFactoryContract() {
            @Override protected ExportConnectorFactory factory() { return factory; }
        };
    }

    @Test
    void aWellBehavedExporterPasses() {
        ExportConnectorFactoryContract c = export("good", p -> null);
        assertDoesNotThrow(() -> {
            c.schemeIsALowerCaseToken();
            c.schemeIsServedByExactlyOneFactory();
            c.buildingAnExporterNeverDials();
            c.exporterRefusesOnlyWithAnIllegalArgumentException();
            c.aRefusalNeverEchoesTheCredential();
        });
    }

    @Test
    void exportDefectsAreCaught() {
        assertThrows(AssertionFailedError.class, () -> export("Bad", p -> null).schemeIsALowerCaseToken());
        assertThrows(AssertionFailedError.class, () -> export("slow", p -> {
            try { Thread.sleep(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return null;
        }).buildingAnExporterNeverDials());
        assertThrows(AssertionFailedError.class, () -> export("rude", p -> { throw new IllegalStateException("x"); })
                .exporterRefusesOnlyWithAnIllegalArgumentException());
        assertThrows(AssertionFailedError.class, () -> export("leaky", p -> { throw new IllegalArgumentException(p.password()); })
                .aRefusalNeverEchoesTheCredential());
    }

    @Test
    void twoExportersForOneSchemeAreCaught() {
        ExportConnectorFactory a = new ExportConnectorFactory() {
            @Override public String scheme() { return "dup"; }
            @Override public ExportConnector exporter(ConnectionProfile p) { return null; }
        };
        ExportConnectorFactoryContract c = new ExportConnectorFactoryContract() {
            @Override protected ExportConnectorFactory factory() { return a; }
            @Override protected Iterable<ExportConnectorFactory> registered() { return List.of(a, a); }
        };
        assertThrows(AssertionFailedError.class, c::schemeIsServedByExactlyOneFactory);
    }
}
