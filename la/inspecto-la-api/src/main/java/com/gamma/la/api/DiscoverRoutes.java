package com.gamma.la.api;

import com.gamma.access.WriteGates;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
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
