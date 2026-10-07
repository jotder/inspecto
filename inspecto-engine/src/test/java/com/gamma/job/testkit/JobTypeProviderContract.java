package com.gamma.job.testkit;

import com.gamma.job.JobDeadline;
import com.gamma.job.JobTypeDescriptor;
import com.gamma.job.JobTypeProvider;
import com.gamma.job.ParameterDecl;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The platform's TCK for a {@link JobTypeProvider} (MODULE-REORG-1 P5b): what {@code JobTypeRegistry} and the
 * {@code GET /jobs/types} catalogue assume of every Job Type, built-in or from an optional module. A subclass names the
 * provider; no host is needed.
 *
 * <p>Defects each test catches:
 * <ul>
 *   <li>{@link #idIsALowerCaseToken} - the registry stores the id as given but looks it up lower-cased, so a mixed-case
 *       id registers and is then unreachable; a blank id makes every job of the type fail.</li>
 *   <li>{@link #idIsServedByExactlyOneProvider} - two classpath providers for one id: the second is rejected with only a
 *       log warning, so a module silently loses its Job Type.</li>
 *   <li>{@link #descriptorIsConsistentAndDescribed} - {@code id()} differing from {@code descriptor().id()}, or a blank
 *       title, shows the operator a type they cannot identify.</li>
 *   <li>{@link #parametersAreWellFormed} - a blank or duplicate parameter name, an inverted min/max, or a default
 *       outside the declared options renders a form that cannot be filled in.</li>
 *   <li>{@link #requiresAreWellFormedGrants} - a blank, mixed-case or repeated Platform Service id in {@code requires}
 *       is refused at registration, taking the whole module's type with it. Whether each id names a service THIS host
 *       registers is checked by {@link #knownPlatformServices()} where a subclass can supply the host's list.</li>
 *   <li>{@link #deadlineIsPositiveAndWithinTheCeiling} - a zero or negative deadline kills every run at once; one above
 *       the 24 h ceiling is silently capped, so the type's own default is a lie.</li>
 *   <li>{@link #constructionHasNoSideEffects} - a provider is instantiated by ServiceLoader at boot, before any
 *       Space is bound: creating files in the working directory or changing system properties there is a leak into
 *       every Space.</li>
 * </ul>
 * Not covered (needs a {@code JobConfig} the type accepts and a {@code JobContext}): {@code create(config)} and running the
 * Job; each module's own tests keep those.
 */
public abstract class JobTypeProviderContract {

    private static final Pattern TOKEN = Pattern.compile("[a-z][a-z0-9._-]*");

    /** The provider under test. */
    protected abstract JobTypeProvider provider();

    /** Every provider the runtime would see; a seam only so the self-test can plant a duplicate. */
    protected Iterable<JobTypeProvider> registered() {
        return ServiceLoader.load(JobTypeProvider.class);
    }

    /**
     * The Platform Service ids the host registers, when the subclass can know them (a hand-kept mirror drifts - supply
     * one only from the host's own registry). Empty means the existence check is skipped.
     */
    protected Optional<Set<String>> knownPlatformServices() {
        return Optional.empty();
    }

    @Test
    void idIsALowerCaseToken() {
        String id = provider().id();
        assertNotNull(id, "id()");
        assertTrue(TOKEN.matcher(id).matches(), "job type id '" + id + "' must match " + TOKEN + " (the registry looks ids up lower-cased)");
    }

    @Test
    void idIsServedByExactlyOneProvider() {
        String id = provider().id();
        List<String> serving = new ArrayList<>();
        for (JobTypeProvider p : registered())
            if (id.equalsIgnoreCase(p.id())) serving.add(p.getClass().getName());
        assertTrue(serving.size() <= 1, "job type '" + id + "' is served by several providers: " + serving);
    }

    @Test
    void descriptorIsConsistentAndDescribed() {
        JobTypeProvider p = provider();
        JobTypeDescriptor d = p.descriptor();
        assertNotNull(d, "descriptor()");
        assertEquals(d.id(), p.id(), "id() must be the descriptor's id");
        assertNotNull(d.title(), "title");
        assertFalse(d.title().isBlank(), "a Job Type needs a title the operator can read");
        assertNotNull(d.description(), "description");
        assertFalse(p.implClass() == null || p.implClass().isBlank(), "implClass() names the provenance shown to the operator");
        assertEquals(d, p.descriptor(), "descriptor() must be stable between calls");
    }

    @Test
    void parametersAreWellFormed() {
        Set<String> seen = new HashSet<>();
        for (ParameterDecl pd : provider().descriptor().parameters()) {
            assertTrue(pd.name() != null && !pd.name().isBlank(), "a parameter needs a name");
            assertTrue(seen.add(pd.name()), "parameter '" + pd.name() + "' is declared twice");
            assertNotNull(pd.type(), "parameter '" + pd.name() + "' needs a type");
            if (pd.min() != null && pd.max() != null)
                assertTrue(pd.min() <= pd.max(), "parameter '" + pd.name() + "': min " + pd.min() + " > max " + pd.max());
            if (!pd.options().isEmpty() && pd.defaultValue() != null && !pd.defaultValue().isBlank())
                assertTrue(pd.options().contains(pd.defaultValue()),
                        "parameter '" + pd.name() + "': default '" + pd.defaultValue() + "' is not one of " + pd.options());
        }
    }

    @Test
    void requiresAreWellFormedGrants() {
        Set<String> seen = new HashSet<>();
        for (String r : provider().descriptor().requires()) {
            assertTrue(r != null && TOKEN.matcher(r).matches(), "requires entry '" + r + "' must be a lower-case Platform Service id");
            assertTrue(seen.add(r), "requires lists '" + r + "' twice");
        }
        knownPlatformServices().ifPresent(known -> {
            for (String r : provider().descriptor().requires())
                assertTrue(known.contains(r), "requires '" + r + "', which this host does not register (known: " + known + ")");
        });
    }

    @Test
    void deadlineIsPositiveAndWithinTheCeiling() {
        Duration d = provider().deadline();
        assertNotNull(d, "deadline()");
        assertFalse(d.isZero() || d.isNegative(), "deadline " + d + " must be positive");
        assertTrue(d.toSeconds() <= JobDeadline.DEFAULT_CEILING_SECONDS,
                "deadline " + d + " exceeds the " + JobDeadline.DEFAULT_CEILING_SECONDS + " s ceiling and would be silently capped");
    }

    @Test
    void constructionHasNoSideEffects() {
        File cwd = new File(System.getProperty("user.dir"));
        List<String> filesBefore = list(cwd);
        Properties propsBefore = (Properties) System.getProperties().clone();
        JobTypeProvider p = provider();
        p.id();
        p.descriptor();
        p.deadline();
        assertEquals(filesBefore, list(cwd), "constructing the provider created or removed files in the working directory");
        assertEquals(propsBefore, System.getProperties(), "constructing the provider changed system properties");
    }

    private static List<String> list(File dir) {
        String[] names = dir.list();
        if (names == null) return List.of();
        Arrays.sort(names);
        return List.of(names);
    }
}
