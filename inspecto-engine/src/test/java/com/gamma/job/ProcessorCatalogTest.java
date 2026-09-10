package com.gamma.job;

import com.gamma.consignment.ConsignmentProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code PROCESSOR-CATALOG-ROUTE-1} — {@link JobService#processorCatalog(int)}, the vocabulary behind
 * {@code GET /jobs/processors}.
 *
 * <p><b>Why this test builds its own classloader.</b> Discovery is {@link java.util.ServiceLoader}, which
 * reads {@code META-INF/services} off the thread's context classloader. Declaring the fixtures in a services
 * file under {@code src/test/resources} would publish them to every test in the module <em>and</em> destroy
 * {@link #theStockClasspathServesNothingAndThatIsNotAnError()}, which pins the fact the whole feature has to
 * be honest about. Publishing them through a loader this test creates keeps them scoped to the test that
 * wants them, and is the only way to reach the duplicate-id and unloadable-provider branches at all.
 */
class ProcessorCatalogTest {

    private static final String SERVICE = "META-INF/services/" + ConsignmentProcessor.class.getName();
    private static final String FIX = ProcessorFixtures.class.getName() + "$";

    /** Run {@code body} with a classloader that declares {@code lines} as the processor providers. */
    private static Map<String, Object> catalogWith(Path dir, int cap, String... lines) throws Exception {
        Path svc = dir.resolve(SERVICE);
        Files.createDirectories(svc.getParent());
        Files.write(svc, List.of(lines));
        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader cl = new URLClassLoader(new URL[]{dir.toUri().toURL()}, prev)) {
            Thread.currentThread().setContextClassLoader(cl);
            return JobService.processorCatalog(cap);
        } finally {
            Thread.currentThread().setContextClassLoader(prev);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> catalog) {
        return (List<Map<String, Object>>) catalog.get("processors");
    }

    /**
     * ⚠ The load-bearing fact about this whole feature: <b>the product ships no {@link ConsignmentProcessor}
     * implementation at all</b> — only the {@code tools/templates/processor} scaffold, whose services file
     * carries an unexpanded {@code {{packageName}}} placeholder and is never on a runtime classpath. So a
     * stock install serves an EMPTY catalog, and every consumer has to treat that as normal rather than as a
     * broken route. If this test ever fails because something now ships, that is good news — but the picker's
     * "suggests, does not constrain" behaviour must stay, because a processor can be deployed after a Job
     * config is authored.
     */
    @Test
    void theStockClasspathServesNothingAndThatIsNotAnError() {
        Map<String, Object> catalog = JobService.processorCatalog(JobService.PROCESSOR_CATALOG_CAP);

        assertEquals(List.of(), rows(catalog), "the product ships zero processors — see this test's javadoc");
        assertEquals(0, catalog.get("total"));
        assertEquals(false, catalog.get("truncated"));
        assertEquals(0, catalog.get("unusable"), "and nothing on the stock classpath is broken");
    }

    /** A deployed processor is served by the id a Job config selects it by, with the class that provides it. */
    @Test
    void servesADeployedProcessorByTheIdAJobConfigSelects(@TempDir Path dir) throws Exception {
        Map<String, Object> catalog = catalogWith(dir, 10, FIX + "Alpha", FIX + "Beta");

        assertEquals(2, catalog.get("total"));
        assertEquals(0, catalog.get("unusable"));
        assertEquals(false, catalog.get("truncated"));
        assertEquals(List.of("alpha", "beta"), rows(catalog).stream().map(r -> r.get("id")).toList());
        assertEquals(ProcessorFixtures.Alpha.class.getName(), rows(catalog).get(0).get("className"),
                "the providing class is how an operator tells two jars apart");
        assertFalse((Boolean) rows(catalog).get(0).get("shadowed"));
    }

    /**
     * Two classes claiming one id: {@code fromServiceLoader} takes the FIRST match, so the second is
     * unreachable. It is served and flagged rather than dropped — an operator who deployed two jars that
     * disagree needs to see both, and a picker that showed one id twice with no explanation would be worse.
     */
    @Test
    void aSecondClassClaimingATakenIdIsFlaggedShadowedNotDropped(@TempDir Path dir) throws Exception {
        Map<String, Object> catalog = catalogWith(dir, 10, FIX + "Alpha", FIX + "AlphaImpostor");

        assertEquals(2, catalog.get("total"), "both are on the classpath, so both are reported");
        assertFalse((Boolean) rows(catalog).get(0).get("shadowed"), "the first match is the one that resolves");
        assertTrue((Boolean) rows(catalog).get(1).get("shadowed"), "the second can never be selected");
        assertEquals(ProcessorFixtures.AlphaImpostor.class.getName(), rows(catalog).get(1).get("className"));
    }

    /**
     * A stale services file naming a deleted class is the realistic failure, and it throws from
     * {@code hasNext()} rather than {@code next()} — ServiceLoader resolves the class while looking ahead.
     * ⚠ So the entries listed AFTER the bad one are what a naive implementation loses: this asserts the scan
     * carried on and still found {@code beta}, not merely that the read returned something.
     */
    @Test
    void aStaleServicesEntryIsCountedAndTheScanCarriesOnPastIt(@TempDir Path dir) throws Exception {
        Map<String, Object> catalog = catalogWith(dir, 10, "com.gamma.job.NoSuchProcessorWasEverBuilt", FIX + "Beta");

        assertEquals(1, catalog.get("unusable"), "the deleted class is reported, not thrown at the caller");
        assertEquals(List.of("beta"), rows(catalog).stream().map(r -> r.get("id")).toList(),
                "the provider listed after the stale entry must still be served");
        assertEquals(1, catalog.get("total"));
    }

    /**
     * An id no chain can name is counted, not served as a choice the author cannot use. Two ways to be
     * unnameable: blank (the lookup matches only a non-blank id) and — less obvious — an id containing a
     * <b>comma</b>, because {@code ConsignmentProcessJobType.chainOf} splits the {@code processor} parameter
     * on commas, so such an id cannot survive being written into a chain. The chain editor refuses a comma
     * in a typed id for the same reason; a picker offering one would be offering a dead end.
     */
    @Test
    void anIdNoChainCanNameIsUnusableRatherThanAnUnusableChoice(@TempDir Path dir) throws Exception {
        Map<String, Object> catalog = catalogWith(dir, 10, FIX + "Blank", FIX + "Comma", FIX + "Alpha");

        assertEquals(2, catalog.get("unusable"), "the blank id and the comma-bearing id");
        assertEquals(List.of("alpha"), rows(catalog).stream().map(r -> r.get("id")).toList());
        assertEquals(1, catalog.get("total"), "an unselectable processor is not part of the vocabulary");
    }

    /** The cap bounds the list, and {@code total} still reports the TRUE count — a bounded read must not
     *  under-report what it left out. */
    @Test
    void theCapBoundsTheListWhileTotalStaysTrue(@TempDir Path dir) throws Exception {
        Map<String, Object> catalog = catalogWith(dir, 1, FIX + "Alpha", FIX + "Beta");

        assertEquals(1, rows(catalog).size(), "the cap bounds what is served");
        assertEquals(2, catalog.get("total"), "but the caller is told the true total");
        assertEquals(true, catalog.get("truncated"));
    }
}
