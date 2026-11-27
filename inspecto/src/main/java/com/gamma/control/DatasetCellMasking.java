package com.gamma.control;

import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.util.Map;

/**
 * {@link BiResultMasking} for an add-on route that builds its own server-side SQL over a Dataset and returns plain column
 * cells (RECON-READ-UNGATED-1: the reconciliation reads). The public seam, like {@link BiSpecRunner}: the add-on never
 * decides itself which columns are classified, which caller may reveal, or what a masked cell reads as, so a value comes
 * back exactly as {@code /bi/query} would show it to the same caller.
 */
public final class DatasetCellMasking {

    private final BiResultMasking masking;

    private DatasetCellMasking(BiResultMasking masking) {
        this.masking = masking;
    }

    /** True when the caller may see raw keys: holds {@code canRevealLinkEntities}, or no Subject is attached (Basic edition). */
    public static boolean mayReveal(HttpExchange ex) {
        return BiResultMasking.mayReveal(ex);
    }

    /** The masking of a read over {@code datasetId} ({@code dataset} = its registry content) for a caller who may ({@code reveal}) or may not reveal. */
    public static DatasetCellMasking of(Path writeRoot, ComponentStore store, String datasetId, Map<String, Object> dataset,
                                        boolean reveal) {
        return new DatasetCellMasking(BiResultMasking.of(writeRoot, store, new ViewStore(writeRoot.resolve("views")),
                datasetId, dataset, reveal));
    }

    /** Masking is in force for this caller over this Dataset (some column is masked and the caller cannot reveal). */
    public boolean engaged() {
        return masking.engaged();
    }

    /** This caller may not see {@code column} raw (classified, or not provably clean while the lineage is unknown). */
    public boolean hides(String column) {
        return masking.hidesFromCaller(column);
    }

    /** {@code value} of {@code column} as this caller sees it: raw, the Space alias, or {@code masked:withheld}. */
    public Object cell(String column, Object value) {
        return masking.cell(column, value);
    }

    /**
     * {@code fault} (a 422 built from a failed comparison's DuckDB text) as this caller may see it: while masking is in force,
     * any run-time error text (which can quote a stored key value, e.g. a failed cast) is replaced by a fixed sentence, as
     * {@code /bi/query} does ({@link BiResultMasking#scrub}).
     */
    public ApiException scrub(ApiException fault) {
        return masking.scrub(fault);
    }

    /** 422 when {@code column} is hidden from this caller and {@code what} (e.g. {@code sum over}) would read its values. */
    public void refuseValueRead(String column, String what) {
        if (hides(column))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, what + " column '" + column
                    + "' is refused: it is a masked key (or its lineage is untraceable), and " + what
                    + " it would leak its raw values; the caller needs " + BiResultMasking.REVEAL);
    }
}
