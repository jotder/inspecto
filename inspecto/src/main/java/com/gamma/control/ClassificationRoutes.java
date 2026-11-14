package com.gamma.control;

import com.gamma.service.ClassificationVocabulary;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Space's column classification vocabulary (UI-CLASSIFICATION-PICKER-1), what the classification picker offers:
 * <pre>
 *   GET /classifications   {items: [{class, masked, source, entityType?, label?}]}, sorted by class
 * </pre>
 * The list is {@link ClassificationVocabulary#vocabulary}, the one the save gate refuses an unknown class by, read for
 * the Space's Entity Types ({@link ClassificationVocabulary#typesOf}; the built-in ones without a write root, as the
 * gate reads them). Bounded by the Space's Entity Type limit. A read, so open to anyone in the Space (reads are not
 * capability-gated, {@code CapabilityManifest}): the schema and Dataset reads it serves are open the same way.
 */
final class ClassificationRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/classifications", (e, m) -> Map.of("items", items(api)));
    }

    private static List<Map<String, Object>> items(ApiContext api) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ClassificationVocabulary.Entry v : ClassificationVocabulary.vocabulary(ClassificationVocabulary.typesOf(api.writeRoot()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("class", v.cls());
            item.put("masked", v.masked());
            item.put("source", v.source());
            if (v.entityType() != null) item.put("entityType", v.entityType());
            if (v.label() != null) item.put("label", v.label());
            out.add(item);
        }
        return out;
    }
}
