package com.gamma.la.api;

import com.gamma.access.WriteGates;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.entitystore.ValueShapes;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gamma.util.SqlIdent.q;

/**
 * {@code GET /inv/datasets/{id}/discover} (LA-BASIC-DISCOVER-1): what is in one Dataset at a glance - {@link DatasetDiscovery}.
 * Basic and up (plan Q-1): the geoLink feature only, read-only, like the preset queries it shares its roles and index path
 * with.
 *
 * <p>Gate order: write root 503 → Dataset 404 (R3 {@code canView} via {@link InvRoutes#relationFor}) → a link mapping naming a
 * column the data has not got 422. The busiest entity ids leave as exploration aliases when the Space masks them; the rest is
 * counts, periods, kinds and totals. Each read is bounded by the Space's statement timeout; every call is audited as
 * {@code LINK_DATASET_DISCOVERED}, with no entity id.
 */
public final class DiscoverRoutes implements RouteModule {

    @Override
    public Set<String> featureIds() {
        return Set.of("geoLink");
    }

    @Override
    public void register(ApiContext api) {
        api.get("/inv/datasets/([^/]+)/discover", (e, m) -> discover(api, e, m.group(1)));
        api.get("/inv/datasets/([^/]+)/shapes", (e, m) -> shapes(api, e, m.group(1)));
    }

    /** The most rows {@link #shapes} reads: Add data's own sample size class, cheap on any Dataset. */
    static final int SHAPE_SAMPLE_ROWS = 200;

    /**
     * {@code GET /inv/datasets/{id}/shapes} (ENTITY-TYPE-RECORD-ROLES-1): which Entity Types' value shapes each column's
     * values fit, for the Data page's "Shape matches" marks. Read over the first {@value #SHAPE_SAMPLE_ROWS} rows UNMASKED on
     * the server (the page's own row sample is masked for exactly the columns that matter, so a mark read there would be
     * wrong) and answered as marks only - {@code {dataset, sampled, columns: {<column>: [{type, strong}]}}} - never a value.
     * A column fits a type when every non-empty sampled value fits its shape (a free shape fits nothing); {@code strong}
     * when every one fits its narrow reading. Gate order as {@code discover}: write root 503 → Dataset 404.
     */
    private static Object shapes(ApiContext api, HttpExchange ex, String datasetId) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "dataset shapes");
        String relationSql = InvRoutes.relationFor(api, ex, writeRoot, datasetId);
        DatasetProvider.Result r;
        try {
            List<DatasetProvider.Column> columns = DatasetProviders.require().run(new DatasetProvider.Request(datasetId, relationSql,
                    "SELECT * FROM " + q(datasetId), 0, 0, List.of(), List.of())).columns();
            StringBuilder sql = new StringBuilder("SELECT ");
            for (int i = 0; i < columns.size(); i++)
                sql.append(i == 0 ? "" : ", ").append("CAST(").append(q(columns.get(i).name())).append(" AS VARCHAR) AS ").append(q(columns.get(i).name()));
            if (columns.isEmpty()) sql.append("1");
            sql.append(" FROM ").append(q(datasetId)).append(" LIMIT ").append(SHAPE_SAMPLE_ROWS);
            r = DatasetProviders.require().run(new DatasetProvider.Request(datasetId, relationSql, sql.toString(),
                    SHAPE_SAMPLE_ROWS, 0, List.of(), List.of()));
        } catch (Exception unusable) {
            throw DatasetReadFault.of("cannot read a sample of dataset '" + datasetId + "'", datasetId, writeRoot, unusable);
        }
        List<EntityTypes.EntityType> types = LinkAnalysisSettings.forRoot(writeRoot).effectiveEntityTypes();
        Map<String, Object> marks = new LinkedHashMap<>();
        for (DatasetProvider.Column c : r.columns()) {
            List<String> values = new ArrayList<>();
            for (Map<String, Object> row : r.rows())
                if (row.get(c.name()) != null && !String.valueOf(row.get(c.name())).isBlank()) values.add(String.valueOf(row.get(c.name())));
            marks.put(c.name(), fits(values, types));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", datasetId);
        out.put("sampled", r.rows().size());
        out.put("columns", marks);
        return out;
    }

    /** The types whose shape every one of {@code values} fits, in the types' order: {@code [{type, strong}]}; none for no value. */
    static List<Map<String, Object>> fits(List<String> values, List<EntityTypes.EntityType> types) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (values.isEmpty()) return out;
        for (EntityTypes.EntityType t : types) {
            if (ValueShapes.isFree(t.shape())) continue;
            boolean all = true, strong = true;
            for (String v : values) {
                ValueShapes.Fit f = ValueShapes.fit(t.shape(), v);
                if (f == ValueShapes.Fit.NONE) {
                    all = false;
                    break;
                }
                if (f != ValueShapes.Fit.STRONG) strong = false;
            }
            if (all) out.add(Map.of("type", t.id(), "strong", strong));
        }
        return out;
    }

    private static Object discover(ApiContext api, HttpExchange ex, String datasetId) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "dataset discovery");
        PresetRoutes.Target t = PresetRoutes.target(api, ex, writeRoot, datasetId);
        Map<String, Object> dataset = DatasetProviders.require().dataset(writeRoot, datasetId).orElseThrow();
        List<DatasetProvider.Column> columns;
        try {
            columns = DatasetProviders.require().run(new DatasetProvider.Request(datasetId, t.relationSql(),
                    "SELECT * FROM " + q(datasetId), 0, 0, List.of(), List.of())).columns();
        } catch (Exception unusable) {
            throw DatasetReadFault.of("cannot read the columns of dataset '" + datasetId + "'", datasetId, writeRoot, unusable);
        }
        Map<String, Object> raw = DatasetDiscovery.discover(api, writeRoot, t, dataset, columns);
        ExplorationMasking mask = ExplorationMasking.of(writeRoot, datasetId,
                Map.of("sourceCol", t.roles().from() == null ? "" : t.roles().from(), "targetCol", t.roles().to() == null ? "" : t.roles().to()));
        Map<String, Object> out = DatasetDiscovery.mask(raw, mask);
        try {
            EventSink.current().emit(Event.builder(LinkEventTypes.LINK_DATASET_DISCOVERED).source("inv")
                    .message("link.dataset.discover — " + datasetId)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("link.dataset.discover").actionCategory("analysis")
                    .attr("dataset", datasetId).attr("cached", Boolean.TRUE.equals(out.get("cached")))
                    .attr("masked", mask.active()));
        } catch (RuntimeException ignored) {
            // best effort (LA-04) - the read already succeeded
        }
        return out;
    }
}
