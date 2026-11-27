package com.gamma.control;

import com.gamma.config.safety.DataRef;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code ENRICHMENT-PREVIEW-PATH-REACH-1} and {@code LOOKUP-TEST-PATH-RAW-1} (operator, 2026-11-27, reversing that part of
 * D-AK22 and D-AK25): a {@code path:} Reference that the enrichment preview or the lookup test reads may not name a file in
 * a data folder. Refused (422) when it lies in a loaded Pipeline's folders (its store, its rejected rows in
 * {@code errors}/{@code quarantine}, its raw copies in {@code backup}, its inbox and working folders), in the store of a
 * registry Dataset (its {@code physicalRef} or {@code sourceName} under the data root); a glob is refused when what it
 * can reach holds any of them. A Dataset is read by name ({@code ref:} / {@code reference/<pipeline>}), where its sharing
 * and its classification apply. A path under none of them keeps its behaviour: raw for a builder and a builder's agent,
 * 403 for a machine agent.
 *
 * <p>No rule is needed here for the restricted quarantine (the sealed read's allowlist already refuses a folder inside or
 * holding one, {@code PathJail}) nor for a backup archive (DuckDB refuses a {@code .zip}): falsified 2026-11-27, each
 * probe was refused without such a rule.
 *
 * <p>Refusing rather than classifying: only a Pipeline's store maps to a Dataset; the rejected rows and the raw copies have
 * no Dataset to classify them by, so one refusal covers every case and nothing is answered raw past a Dataset.
 */
final class PathReferenceGuard {

    private PathReferenceGuard() {}

    static final String REFUSAL = "a path Reference may not name a file in a data folder (a Dataset's store, a Pipeline's "
            + "rejected rows, raw copies or working folders): name the Dataset by "
            + "reference (ref: or reference/<pipeline>), where its sharing and classification apply";

    private static final Pattern GLOB = Pattern.compile("[*?\\[{]");

    /** 422 when {@code file} (the jailed path the read opens, a glob allowed) reaches a data folder; {@code field} names it. */
    static void refuseDataStore(ApiContext api, Path file, String field) {
        if (reachesDataStore(api, file))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + field + "': " + REFUSAL);
    }

    static boolean reachesDataStore(ApiContext api, Path file) {
        Path abs = file.toAbsolutePath().normalize();
        // the literal head: a glob can reach everything below it
        Path head = abs.getRoot();
        boolean glob = false;
        for (Path seg : abs) {
            if (GLOB.matcher(seg.toString()).find()) {
                glob = true;
                break;
            }
            head = head == null ? seg : head.resolve(seg);
        }
        if (head == null) return true;   // a glob from the very top reaches everything
        List<Path> heads = spellings(head);
        for (Path store : stores(api))
            for (Path s : spellings(store))
                for (Path h : heads)
                    if (h.startsWith(s) || glob && s.startsWith(h)) return true;   // in the store, or a glob reaching it
        return false;
    }

    /** Every folder that holds Dataset or Pipeline data in this Space (absolute, normalised). */
    private static List<Path> stores(ApiContext api) {
        List<Path> out = new ArrayList<>();
        for (PipelineConfig p : HostContext.of(api).service().loadedPipelines()) {
            PipelineConfig.Dirs d = p.dirs();
            if (d == null) continue;
            for (String s : Arrays.asList(d.poll(), d.database(), d.backup(), d.temp(), d.errors(), d.quarantine(),
                    d.markers(), d.logDir(), d.manifestsDir()))
                // blank = unset (it would resolve to the working directory); an object store is no local folder
                if (s != null && !s.isBlank() && !com.gamma.config.safety.PathJail.isUri(s))
                    out.add(Path.of(s).toAbsolutePath().normalize());
        }
        Path writeRoot = api.writeRoot(), dataRoot = api.dataRoot();
        if (writeRoot == null || dataRoot == null) return out;
        for (ComponentRegistry.Component ds : new ComponentStore(writeRoot.resolve("registry")).list("dataset"))
            for (String store : com.gamma.mask.EvidenceMasker.datasetStores(ds.content())) {
                int slash = store.indexOf('/');
                String head = slash < 0 ? store : store.substring(0, slash);
                if (DataRef.isSafeShape(head)) out.add(dataRoot.resolve(head).toAbsolutePath().normalize());
            }
        return out;
    }

    /** {@code p} and, when it exists, its real path (a link into a store is the store). */
    private static List<Path> spellings(Path p) {
        List<Path> out = new ArrayList<>(List.of(p));
        try {
            if (Files.exists(p)) out.add(p.toRealPath());
        } catch (IOException unreadable) {
            // the lexical spelling still counts
        }
        return out;
    }
}
