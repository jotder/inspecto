package com.gamma.job.testkit;

import com.gamma.job.JobTypeProvider;
import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * MODULE-REORG-1 P4e: a module that contributes Job Types declares them in {@code provides.jobTypes}, and the
 * declaration equals what its {@code JobTypeProvider}s register. The declaration is how a Space's module gate
 * ({@code modules.toon}) knows which module owns a Job Type; a stale list would let a disabled module's Jobs run
 * (a type missing here) or gate a type the module no longer owns.
 *
 * <p>A subclass names its module id and one main class of the module; only the providers loaded from the same code
 * source (the module's jar or classes directory) count, because a module's test class path also carries the modules
 * it requires.
 */
public abstract class JobTypeManifestParity {

    /** The manifest id of the module under test. */
    protected abstract String moduleId();

    /** Any main class of the module under test: its code source is the module's own. */
    protected abstract Class<?> moduleClass();

    @Test
    void declaredJobTypesEqualTheRegisteredProviders() {
        ModuleManifest m = ModuleManifests.load(getClass().getClassLoader()).manifests().stream()
                .filter(x -> x.id().equals(moduleId())).findFirst()
                .orElseThrow(() -> new AssertionError("no manifest '" + moduleId() + "' on the class path"));
        Set<String> registered = new TreeSet<>();
        Object home = moduleClass().getProtectionDomain().getCodeSource().getLocation().toString();
        for (JobTypeProvider p : ServiceLoader.load(JobTypeProvider.class))
            if (home.equals(p.getClass().getProtectionDomain().getCodeSource().getLocation().toString())) registered.add(p.id());
        assertFalse(registered.isEmpty(), "no JobTypeProvider of this module found - the test would prove nothing");
        assertEquals(registered, new TreeSet<>(m.provides().jobTypes()),
                "provides.jobTypes of '" + moduleId() + "' must equal the Job Types its providers register");
    }
}
