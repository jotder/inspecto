package com.gamma.job.testkit;

import com.gamma.job.JobConfig;
import com.gamma.job.JobResult;
import com.gamma.job.JobTypeDescriptor;
import com.gamma.job.JobTypeProvider;
import com.gamma.job.MaintenanceTaskContext;
import com.gamma.job.MaintenanceTaskProvider;
import com.gamma.job.ParamType;
import com.gamma.job.ParameterDecl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the JobTypeProvider and MaintenanceTaskProvider TCKs can fail (MODULE-REORG-1 P5b): each assertion runs against a
 * deliberately broken in-test implementer and must go red; a sound one must stay green.
 */
class JobTckSelfTest {

    private static JobTypeDescriptor descriptor(String id, String title, List<ParameterDecl> params, List<String> requires) {
        return new JobTypeDescriptor(id, title, "a description", params, List.of(), List.of(), requires);
    }

    private static JobTypeDescriptor good() {
        return descriptor("tck.good", "Good", List.of(ParameterDecl.required("target", ParamType.STRING, "where")), List.of("alerts"));
    }

    private static JobTypeProviderContract contract(JobTypeProvider p) {
        return new JobTypeProviderContract() {
            @Override protected JobTypeProvider provider() { return p; }
        };
    }

    private static JobTypeProvider of(JobTypeDescriptor d) {
        return JobTypeProvider.of(d, cfg -> null);
    }

    @Test
    void aSoundProviderPasses() {
        JobTypeProviderContract c = contract(of(good()));
        assertDoesNotThrow(() -> {
            c.idIsALowerCaseToken();
            c.idIsServedByExactlyOneProvider();
            c.descriptorIsConsistentAndDescribed();
            c.parametersAreWellFormed();
            c.requiresAreWellFormedGrants();
            c.deadlineIsPositiveAndWithinTheCeiling();
            c.constructionHasNoSideEffects();
        });
    }

    @Test
    void aBadIdIsCaught() {
        assertThrows(AssertionFailedError.class, () -> contract(of(descriptor("Mixed.Case", "T", List.of(), List.of()))).idIsALowerCaseToken());
        assertThrows(AssertionFailedError.class, () -> contract(of(descriptor("", "T", List.of(), List.of()))).idIsALowerCaseToken());
    }

    @Test
    void aDuplicateIdIsCaught() {
        JobTypeProvider a = of(good());
        JobTypeProvider b = of(good());
        JobTypeProviderContract c = new JobTypeProviderContract() {
            @Override protected JobTypeProvider provider() { return a; }
            @Override protected Iterable<JobTypeProvider> registered() { return List.of(a, b); }
        };
        assertThrows(AssertionFailedError.class, c::idIsServedByExactlyOneProvider);
    }

    @Test
    void anInconsistentOrUntitledDescriptorIsCaught() {
        JobTypeProvider mismatch = new JobTypeProvider() {
            @Override public JobTypeDescriptor descriptor() { return good(); }
            @Override public com.gamma.job.Job create(JobConfig config) { return null; }
            @Override public String id() { return "something.else"; }
        };
        assertThrows(AssertionFailedError.class, () -> contract(mismatch).descriptorIsConsistentAndDescribed());
        assertThrows(AssertionFailedError.class,
                () -> contract(of(descriptor("tck.untitled", " ", List.of(), List.of()))).descriptorIsConsistentAndDescribed());
    }

    @Test
    void malformedParametersAreCaught() {
        ParameterDecl p = ParameterDecl.required("dup", ParamType.STRING, "d");
        assertThrows(AssertionFailedError.class,
                () -> contract(of(descriptor("tck.p", "T", List.of(p, p), List.of()))).parametersAreWellFormed());
        ParameterDecl inverted = ParameterDecl.of("n", ParamType.INTEGER).min(10).max(1).build();
        assertThrows(AssertionFailedError.class,
                () -> contract(of(descriptor("tck.p", "T", List.of(inverted), List.of()))).parametersAreWellFormed());
        ParameterDecl strayDefault = ParameterDecl.of("mode", ParamType.STRING).options("a", "b").defaultValue("c").build();
        assertThrows(AssertionFailedError.class,
                () -> contract(of(descriptor("tck.p", "T", List.of(strayDefault), List.of()))).parametersAreWellFormed());
    }

    @Test
    void malformedOrUnknownRequiresAreCaught() {
        assertThrows(AssertionFailedError.class,
                () -> contract(of(descriptor("tck.r", "T", List.of(), List.of("Alerts")))).requiresAreWellFormedGrants());
        assertThrows(AssertionFailedError.class,
                () -> contract(of(descriptor("tck.r", "T", List.of(), List.of("alerts", "alerts")))).requiresAreWellFormedGrants());
        JobTypeProviderContract unknown = new JobTypeProviderContract() {
            @Override protected JobTypeProvider provider() { return of(descriptor("tck.r", "T", List.of(), List.of("no-such-service"))); }
            @Override protected Optional<Set<String>> knownPlatformServices() { return Optional.of(Set.of("alerts")); }
        };
        assertThrows(AssertionFailedError.class, unknown::requiresAreWellFormedGrants);
    }

    @Test
    void anImpossibleDeadlineIsCaught() {
        for (Duration d : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(3))) {
            JobTypeProvider p = new JobTypeProvider() {
                @Override public JobTypeDescriptor descriptor() { return good(); }
                @Override public com.gamma.job.Job create(JobConfig config) { return null; }
                @Override public Duration deadline() { return d; }
            };
            assertThrows(AssertionFailedError.class, () -> contract(p).deadlineIsPositiveAndWithinTheCeiling(), d.toString());
        }
    }

    @Test
    void constructionThatTouchesGlobalStateIsCaught() {
        JobTypeProviderContract c = new JobTypeProviderContract() {
            @Override protected JobTypeProvider provider() {
                System.setProperty("tck.sideeffect", "1");   // a provider that configures the JVM when it is built
                return of(good());
            }
        };
        try {
            assertThrows(AssertionFailedError.class, c::constructionHasNoSideEffects);
        } finally {
            System.clearProperty("tck.sideeffect");
        }
    }

    // ------------------------------------------------------------ maintenance

    private record Maint(Set<String> tasks, Function<String, JobResult> run) implements MaintenanceTaskProvider {
        @Override public JobResult run(String task, MaintenanceTaskContext ctx) { return run.apply(task); }
    }

    private static MaintenanceTaskProviderContract maint(MaintenanceTaskProvider p) {
        return new MaintenanceTaskProviderContract() {
            @Override protected MaintenanceTaskProvider provider() { return p; }
        };
    }

    private static final Function<String, JobResult> STRICT = t -> {
        if (!t.equals("purge")) throw new IllegalArgumentException("no task " + t);
        return JobResult.ok("done", 0L);
    };

    @Test
    void aSoundMaintenanceProviderPasses(@TempDir Path dir) {
        MaintenanceTaskProviderContract c = maint(new Maint(Set.of("purge"), STRICT));
        assertDoesNotThrow(() -> {
            c.tasksAreLowerCaseTokensAndStable();
            c.taskNamesAreOwnedByOneProvider();
            c.anUnknownTaskIsRefusedNeverRun(dir);
            c.aDeclaredTaskNeverReturnsNull(dir);
        });
    }

    @Test
    void badTaskNamesAreCaught() {
        assertThrows(AssertionFailedError.class, () -> maint(new Maint(Set.of(), STRICT)).tasksAreLowerCaseTokensAndStable());
        assertThrows(AssertionFailedError.class, () -> maint(new Maint(Set.of("Purge"), STRICT)).tasksAreLowerCaseTokensAndStable());
    }

    @Test
    void aTaskNameClaimedTwiceIsCaught() {
        MaintenanceTaskProvider a = new Maint(Set.of("purge"), STRICT);
        MaintenanceTaskProvider b = new MaintenanceTaskProvider() {
            @Override public Set<String> tasks() { return Set.of("purge", "other"); }
            @Override public JobResult run(String t, MaintenanceTaskContext c) { return JobResult.ok("", 0L); }
        };
        MaintenanceTaskProviderContract c = new MaintenanceTaskProviderContract() {
            @Override protected MaintenanceTaskProvider provider() { return a; }
            @Override protected Iterable<MaintenanceTaskProvider> registered() { return List.of(a, b); }
        };
        assertThrows(AssertionFailedError.class, c::taskNamesAreOwnedByOneProvider);
    }

    @Test
    void aProviderThatRunsItsTaskForAnyNameIsCaught(@TempDir Path dir) {
        MaintenanceTaskProviderContract c = maint(new Maint(Set.of("purge"), t -> JobResult.ok("purged", 0L)));
        assertThrows(AssertionFailedError.class, () -> c.anUnknownTaskIsRefusedNeverRun(dir));
    }

    @Test
    void aNullResultIsCaught(@TempDir Path dir) {
        MaintenanceTaskProviderContract c = maint(new Maint(Set.of("purge"), t -> null));
        assertThrows(AssertionFailedError.class, () -> c.aDeclaredTaskNeverReturnsNull(dir));
    }
}
