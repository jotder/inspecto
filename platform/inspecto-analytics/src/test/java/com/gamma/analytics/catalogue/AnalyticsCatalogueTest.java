package com.gamma.analytics.catalogue;

import com.gamma.analytics.catalogue.AnalyticsFunction.Cost;
import com.gamma.analytics.catalogue.AnalyticsFunction.Form;
import com.gamma.analytics.catalogue.AnalyticsFunction.Kernel;
import com.gamma.analytics.catalogue.AnalyticsFunction.Param;
import com.gamma.analytics.catalogue.AnalyticsFunction.ResultKind;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsCatalogueTest {

    private static AnalyticsFunction fn(String id) {
        return new AnalyticsFunction(id, id, Kernel.STATISTICS, Cost.LINEAR, ResultKind.SCALAR, Set.of(Form.JVM), null, "s", List.of());
    }

    @Test
    void loadFindsTheBuiltInKernelsThroughTheServiceFile() {
        AnalyticsCatalogue c = AnalyticsCatalogue.load();
        Set<Kernel> kernels = EnumSet.noneOf(Kernel.class);
        for (AnalyticsFunction f : c.functions()) kernels.add(f.kernel());
        assertEquals(EnumSet.allOf(Kernel.class), kernels, "every S-tier kernel is catalogued");
        assertTrue(c.find("stats.mean").isPresent());
        assertTrue(c.find("nope.nothing").isEmpty());
    }

    @Test
    void aLaterKernelRegistersItselfWithoutEditingTheBuiltIns() {
        AnalyticsFunctionProvider later = () -> List.of(fn("anomaly.isolation_forest"));
        AnalyticsCatalogue c = AnalyticsCatalogue.of(List.of(new KernelFunctions(), later));
        assertTrue(c.find("anomaly.isolation_forest").isPresent());
        assertTrue(c.functions().size() > 1);
    }

    @Test
    void orderIsKernelThenId() {
        AnalyticsCatalogue c = AnalyticsCatalogue.of(List.of(() -> List.of(fn("stats.b"), fn("stats.a"))));
        assertEquals(List.of("stats.a", "stats.b"), c.functions().stream().map(AnalyticsFunction::id).toList());
    }

    @Test
    void aDuplicateIdFailsLoudly() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AnalyticsCatalogue.of(List.of(() -> List.of(fn("stats.mean")), () -> List.of(fn("stats.mean")))));
        assertTrue(e.getMessage().contains("stats.mean"));
    }

    @Test
    void everyBuiltInEntryIsWellFormed() {
        for (AnalyticsFunction f : new KernelFunctions().functions()) {
            assertTrue(f.id().startsWith(prefix(f.kernel())), f.id() + " sits under its kernel's prefix");
            assertTrue(!f.summary().isBlank(), f.id() + " has a summary");
            for (Param p : f.params()) {
                if (p.min() != null && p.max() != null) assertTrue(p.min() <= p.max(), f.id() + "." + p.name());
                if (p.defaultValue() != null && !p.choices().isEmpty())
                    assertTrue(p.choices().contains(p.defaultValue()), f.id() + "." + p.name() + " default is a choice");
            }
        }
    }

    @Test
    void anIdMustBeDotted() {
        assertThrows(IllegalArgumentException.class, () -> fn("Mean"));
        assertThrows(IllegalArgumentException.class, () -> fn("mean"));
    }

    private static String prefix(Kernel k) {
        return switch (k) {
            case STATISTICS -> "stats.";
            case PROFILER -> "profiler.";
            case TIME_SERIES -> "timeseries.";
            case FORECAST -> "forecast.";
            case SIMILARITY -> "similarity.";
            case GROUPING -> "grouping.";
            case GRAPH -> "graph.";
        };
    }
}
