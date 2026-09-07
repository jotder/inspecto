package com.gamma.job;

import java.util.Set;

/**
 * A contributed {@code maintenance} task — the seam that lets an optional module supply
 * {@code task:} values the core's {@link MaintenanceJob} switch does not carry (EDG-01 cell 2, 2026-09-07).
 *
 * <p><b>Why a lookup the switch can miss, rather than a registry that replaces it.</b> The twenty-odd
 * built-in tasks stay exactly where they are: a hard-coded {@code switch} that a reader can see in one
 * screen. Only the {@code default} arm changed — before throwing "unknown maintenance task" it asks the
 * providers on the classpath. So a built-in cannot be shadowed by a provider (the switch wins first), and
 * a Personal bundle with no provider behaves byte-identically to before, except for the tasks that were
 * deliberately moved out.
 *
 * <p><b>The first implementor</b> is {@code inspecto-backup}'s provider for {@code backup} /
 * {@code backup_verify} / {@code restore} — EDITIONS {@code OPS-06}, "not for Personal".
 *
 * <p>⚠ Discovered once per JVM through {@link java.util.ServiceLoader}, like every other SPI here. A task
 * name claimed by two providers is a deployment error and is reported as such at first use, fail-closed:
 * neither runs.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public interface MaintenanceTaskProvider {

    /** The {@code task:} values this provider runs, lower-case, as an author writes them. */
    Set<String> tasks();

    /**
     * Run {@code task} — one of {@link #tasks()}. Everything the built-in switch had in scope arrives in
     * {@code ctx}; a task that needs none of it simply ignores the rest.
     *
     * <p>⚠ Honour {@link MaintenanceTaskContext#dryRun()}: MNT-1's rule is that a task with no preview
     * does <em>nothing</em> on a dry run and says so — it never falls through to the real action.
     */
    JobResult run(String task, MaintenanceTaskContext ctx) throws Exception;
}
