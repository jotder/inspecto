package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * PERF-P10-1 test support: decode a compact edge body ({@code columns}, {@code attrCols}, rows as arrays) of
 * {@code /inv/projection} or {@code /inv/projection/neighbors} into the object rows the SPA's {@code decodeEdges} builds,
 * so a test asserts on {@code row.get("source")} as before. Any other body is returned unchanged.
 */
final class EdgeWireTestRows {

    private EdgeWireTestRows() {}

    static JsonNode decoded(JsonNode data) {
        if (data == null || !data.has("attrCols") || !data.path("columns").isArray() || !data.path("rows").isArray()) return data;
        JsonNode columns = data.get("columns"), attrCols = data.get("attrCols");
        boolean total = columns.size() > 4 && "total".equals(columns.get(4).asText());
        int first = total ? 5 : 4;
        ObjectNode out = ((ObjectNode) data).deepCopy();
        out.remove("columns");
        out.remove("attrCols");
        ArrayNode rows = JsonNodeFactory.instance.arrayNode();
        for (JsonNode a : data.get("rows")) {
            ObjectNode t = rows.addObject();
            t.set("source", a.get(0));
            t.set("target", a.get(1));
            t.set("kind", a.get(2));
            t.set("count", a.get(3));
            if (total) t.set("total", a.get(4));
            if (!attrCols.isEmpty()) {
                ObjectNode at = t.putObject("attrs");
                for (int i = 0; i < attrCols.size(); i++) at.set(attrCols.get(i).asText(), a.get(first + i));
            }
        }
        out.set("rows", rows);
        return out;
    }
}
