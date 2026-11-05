package com.gamma.control;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.ToonHelper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Edit filter</b> on an import Dataset's card ({@code ADD-DATA-FILTER-EDIT-1}, follows {@code ADD-DATA-ROW-FILTER-1}):
 * <ul>
 *   <li>{@code GET /datasets/{id}/filter} - the rows the import keeps ({@code filter}, as the entry's {@code importFilter})
 *       and the columns a condition may name, with the type the import gives each ({@code columns: [{name, type}]}).</li>
 *   <li>{@code PUT /datasets/{id}/filter} {@code {filter: [{column, op, value | min | max, matchCase?}]}} - new conditions
 *       over the Dataset's column names, checked and rendered by {@link ImportRowFilter} (a refused condition is 422 naming
 *       it); the import Pipeline's {@code processing.csv_settings.where} and the entry's {@code importFilter} are rewritten
 *       together. {@code []} (or {@code null}) keeps every row: the {@code where} and {@code importFilter} go.</li>
 * </ul>
 * The new filter applies to the files added from now on; rows already imported stay as they are (nothing is re-read).
 * Gates as <i>Keep this up to date</i> ({@link DatasetFeed#gate}, {@code canUploadData}: the capability every write of an
 * import's Pipeline needs): write root 503, {@code data.self_import} 403, unknown 404, view-only 403, not an import Add data
 * wrote for THIS id 409; then an approval policy over Datasets 409, the conditions 422, an approval policy over Pipelines
 * 409, the import claimed against a run or delete 409, the save gate 422. The entry is re-read and written inside the claim.
 */
final class ImportFilterEdit {

    private ImportFilterEdit() {}

    /** A decimal-mark column's mapping is {@code TRY_CAST(... AS <type>)} ({@code DecimalMark.sql}): its type is the cast's. */
    private static final Pattern CAST_TYPE = Pattern.compile("AS ([A-Z]+)\\)$");

    static void register(ApiContext api) {
        // ⚠ String LITERALS on purpose (CapabilityManifestTest's scanner).
        api.get("/datasets/([^/]+)/filter", ApiContext.withCapability("canUploadData", (e, m) -> read(api, e, ApiContext.name(m))));
        api.put("/datasets/([^/]+)/filter", ApiContext.withCapability("canUploadData",
                (e, m) -> edit(api, e, ApiContext.name(m), api.body(e))));
    }

    static Object read(ApiContext api, HttpExchange ex, String id) throws IOException {
        DatasetFeed.Import imp = DatasetFeed.gate(api, ex, id, "Edit filter");
        return answer(id, imp.content().get(OnboardImport.IMPORT_FILTER), columns(imp));
    }

    static Object edit(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        DatasetFeed.Import imp = DatasetFeed.gate(api, ex, id, "Edit filter");
        PendingChanges.holdRefusing(api, List.of("dataset"), "a Dataset's row filter is a Dataset change");
        if (body == null || !body.containsKey("filter"))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "filter is required: a list of {column, op, value | min | max}, or [] to keep every row");
        List<ImportRowFilter.Column> cols = columns(imp);
        ImportRowFilter.Result filter = ImportRowFilter.of(body.get("filter"), cols, Set.of(), id);

        withFilter(api, id, imp.content(), filter);   // refused before anything is written
        ComponentStore store = new ComponentStore(imp.writeRoot().resolve("registry"));
        DatasetFeed.rewritePipeline(api, imp, "Edit filter rewrites the import's Pipeline", p -> setWhere(p, filter.where()),
                () -> {
                    // re-read inside the claim: a column or record-type change since the gate is kept, not overwritten
                    Map<String, Object> now = store.get("dataset", id).map(ComponentRegistry.Component::content)
                            .orElseThrow(() -> new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is gone"));
                    store.write("dataset", id, withFilter(api, id, now, filter));
                });
        return answer(id, filter.stored(), cols);
    }

    /** {@code content} with its {@code importFilter} set to {@code filter} (removed when empty), checked by the Dataset rules. */
    private static Map<String, Object> withFilter(ApiContext api, String id, Map<String, Object> content, ImportRowFilter.Result filter) {
        Map<String, Object> entry = new LinkedHashMap<>(content);
        entry.remove("name");
        if (filter.stored().isEmpty()) entry.remove(OnboardImport.IMPORT_FILTER);
        else entry.put(OnboardImport.IMPORT_FILTER, filter.stored());
        try {
            ComponentRoutes.validateKind(api, "dataset", id, entry);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
        return entry;
    }

    /** The Pipeline's {@code processing.csv_settings.where} set to {@code where}, or removed when it is null. */
    @SuppressWarnings("unchecked")
    private static void setWhere(Map<String, Object> p, String where) {
        Map<String, Object> proc = p.get("processing") instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        Map<String, Object> csv = proc.get("csv_settings") instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        if (where == null) csv.remove("where");
        else csv.put("where", where);
        proc.put("csv_settings", csv);
        p.put("processing", proc);
    }

    /**
     * The columns a condition may name: the import schema's mapped columns, by the Dataset's names, each with the type the
     * Pipeline gives it (the raw field's, or a decimal-mark column's cast). The predicate runs over exactly these.
     */
    @SuppressWarnings("unchecked")
    static List<ImportRowFilter.Column> columns(DatasetFeed.Import imp) throws IOException {
        Path schema = imp.pipelineFile().resolveSibling(imp.id() + "_schema.toon");
        if (!Files.isRegularFile(schema))
            throw new ApiException(409, ErrorCodes.CONFLICT, "the import's schema '" + imp.id() + "' is gone");
        Map<String, Object> s = ToonHelper.load(schema.toString());
        Map<String, String> rawType = new LinkedHashMap<>();
        if (s.get("raw") instanceof Map<?, ?> raw && raw.get("fields") instanceof List<?> fields)
            for (Object f : fields)
                if (f instanceof Map<?, ?> m) rawType.put(String.valueOf(m.get("name")), String.valueOf(m.get("type")));
        List<ImportRowFilter.Column> out = new ArrayList<>();
        if (s.get("mapping") instanceof Map<?, ?> map && map.get("fields") instanceof List<?> fields)
            for (Object f : fields) {
                if (!(f instanceof Map<?, ?> m) || !(m.get("name") instanceof String name)) continue;
                String type = rawType.getOrDefault(String.valueOf(m.get("from")), "VARCHAR");
                if ("custom".equals(m.get("fn")) && m.get("args") instanceof Map<?, ?> a) {
                    Matcher cast = CAST_TYPE.matcher(String.valueOf(a.get("expression")));
                    if (cast.find()) type = cast.group(1);
                }
                out.add(new ImportRowFilter.Column(name, name, type));
            }
        return out;
    }

    private static Map<String, Object> answer(String id, Object filter, List<ImportRowFilter.Column> cols) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", id);
        out.put("filter", filter instanceof List<?> l ? l : List.of());
        List<Map<String, Object>> columns = new ArrayList<>();
        for (ImportRowFilter.Column c : cols) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("type", c.type());
            columns.add(m);
        }
        out.put("columns", columns);
        return out;
    }
}
