package com.gamma.service;

import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.DeletionFence;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineLift;
import com.gamma.pipeline.PipelineStores;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A job's {@code store:} key is a <b>data-store id</b>, never a path. It is read at
 * {@code JobService:1407} ({@code fenceDelete}), split on commas, and handed to
 * {@link DeletionFence#check} — which matches it by <b>string equality</b> against
 * {@link PipelineStores#producedStores} of the lifted pipelines. Nothing resolves it against a
 * filesystem root, so it is deliberately absent from {@code ConfigSafetyValidator}'s
 * {@code JOB_PATH_KEYS} (pinned from the other side by {@code ConfigSafetyValidatorTest}:
 * {@code store: ../whatever} is not a finding).
 *
 * <p>🔴 <b>That is exactly why it needs a guard of its own.</b> A {@code store:} that names nothing
 * is not an error anywhere — {@link DeletionFence#check} {@code continue}s past a store with no
 * resting producer, so a miscopied value makes the fence <em>silently inert</em> while the example's
 * {@code probes.txt} still advertises it ("declares store: so DeletionFence flags a race with a
 * running pipeline"). The shipped {@code compact_job.toon} carried {@code store: out/database} —
 * the {@code dir:} value copied one line down — and so could never flag anything. The store the
 * sibling pipeline actually produces is {@code sales} ({@code PipelineLift.emitSinks} names the sink
 * after the schema's {@code mapping.canonicalName}).
 *
 * <p>Walks every shipped maintenance-library job rather than the one file, so the next job that
 * declares a {@code store:} is covered on arrival.
 */
class ShippedJobStoreKeyNamesARealStoreTest {

    /** Surefire's working directory is the module root, so the shipped examples resolve from here. */
    private static final Path LIBRARY = Path.of("examples/06-serve/maintenance-library");

    @Test
    void everyDeclaredStoreIsProducedByAShippedPipeline() throws Exception {
        // The stores the example's own pipelines produce — the only values the fence can ever match.
        Set<String> produced = new java.util.LinkedHashSet<>();
        for (Path p : toons("_pipeline.toon")) {
            PipelineGraph g = PipelineLift.lift(PipelineConfig.load(p.toAbsolutePath().toString()));
            produced.addAll(PipelineStores.produced(g));
        }
        assertFalse(produced.isEmpty(), "positive control: the library must lift at least one store");

        int declared = 0;
        for (Path p : toons("_job.toon")) {
            for (String store : storesOf(p)) {
                declared++;
                assertTrue(produced.contains(store),
                        p.getFileName() + " declares store '" + store + "', which no shipped pipeline "
                                + "produces — DeletionFence.check would skip it and the fence is dead. "
                                + "Produced stores: " + produced);
            }
        }
        assertEquals(1, declared, "positive control: exactly one shipped job declares a store: today "
                + "(compact_job.toon) — if this drops to 0 the loop above asserted nothing");
    }

    /** The {@code job.store} CSV of one job file, exactly as {@code JobService.fenceDelete} splits it. */
    private static List<String> storesOf(Path jobToon) throws Exception {
        Object job = ToonHelper.load(jobToon.toAbsolutePath().toString()).get("job");
        if (!(job instanceof Map<?, ?> m)) return List.of();
        Object v = m.get("store");
        if (v == null || v.toString().isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : v.toString().split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    private static List<Path> toons(String suffix) throws Exception {
        assertTrue(Files.isDirectory(LIBRARY), "shipped library missing: " + LIBRARY.toAbsolutePath());
        try (var s = Files.list(LIBRARY)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }
}
