package com.gamma.insights;

import com.gamma.control.BiSpecRunner;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INSIGHT-PILOT-STALE-CORPUS-1: the {@code business-assurance} pilot (D-BI6) finds Insights out of the box. Its
 * {@code ba_margin_lines} corpus ends yesterday (dated from {@code CURRENT_DATE}; the values depend only on the day
 * index, so they are the same every day), and its shipped Profile {@code ba_margin_revenue}, scanned as the
 * {@code insight.scan} Job scans it (as of today, in the Space's zone), flags the planted drop: product P2 at a fifth
 * of its volume over the last four days.
 */
class BusinessAssurancePilotInsightsTest {

    static final Path CFG = Path.of("..", "..", "spaces", "_templates", "business-assurance", "config")
            .toAbsolutePath().normalize();

    @Test
    void theShippedProfileFindsThePlantedDropAsOfToday(@TempDir Path data) throws Exception {
        ComponentStore store = new ComponentStore(CFG.resolve("registry"));
        Map<String, Object> content = store.get(InsightProfile.KIND, "ba_margin_revenue")
                .map(ComponentRegistry.Component::content).orElseThrow();
        InsightProfile p = InsightProfile.parse("ba_margin_revenue", content);
        Map<String, Object> dataset = store.get("dataset", p.dataset()).map(ComponentRegistry.Component::content).orElseThrow();
        assertEquals(List.of("product", "channel", "partner"), dimensionColumns(dataset),
                "the Dataset marks the columns \"Propose dimensions\" and Explain read");

        InsightScan.Buckets b = InsightScan.buckets(p.grain(), LocalDate.now(BiSpecRunner.zone(CFG)), p.lookback());
        InsightScan.Fetcher fetcher = body -> BiSpecRunner.runAsJob(CFG, data, p.dataset(), dataset,
                BiSpecRunner.parseForJob(CFG, body));
        InsightScan.Fetched f = InsightScan.fetch(p, b, fetcher);
        assertNotNull(f.total().values()[b.size() - 1], "the corpus covers the last complete bucket (yesterday)");

        InsightScan.Result r = InsightScan.scan(p, f, 0, b.size(), k -> 0, Map.of());
        Set<String> found = r.insights().stream().map(i -> i.dimension() + "=" + i.value()).collect(Collectors.toSet());
        assertTrue(found.contains("product=P2"), "the planted P2 drop is an Insight: " + found);
    }

    @SuppressWarnings("unchecked")
    private static List<String> dimensionColumns(Map<String, Object> dataset) {
        return ((List<Map<String, Object>>) dataset.get("columns")).stream()
                .filter(c -> "dimension".equals(c.get("role"))).map(c -> (String) c.get("name")).toList();
    }
}
