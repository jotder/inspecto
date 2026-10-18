package com.gamma.analytics.catalogue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The Analytics function catalogue (ANALYTICS-CORE-1 C8): every {@link AnalyticsFunction} the registered
 * {@link AnalyticsFunctionProvider}s contribute, ordered by kernel then id. It is read-only and built once; a duplicate
 * id fails loudly at load (two kernels must not claim one name).
 */
public final class AnalyticsCatalogue {

    private static final Comparator<AnalyticsFunction> ORDER =
            Comparator.comparing(AnalyticsFunction::kernel).thenComparing(AnalyticsFunction::id);

    private final List<AnalyticsFunction> functions;

    private AnalyticsCatalogue(List<AnalyticsFunction> functions) {
        this.functions = functions;
    }

    /** The catalogue of every provider on the class path. */
    public static AnalyticsCatalogue load() {
        List<AnalyticsFunctionProvider> providers = new ArrayList<>();
        for (AnalyticsFunctionProvider p : ServiceLoader.load(AnalyticsFunctionProvider.class)) providers.add(p);
        return of(providers);
    }

    /** The catalogue of exactly these providers. */
    public static AnalyticsCatalogue of(List<? extends AnalyticsFunctionProvider> providers) {
        List<AnalyticsFunction> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AnalyticsFunctionProvider p : providers) {
            for (AnalyticsFunction f : p.functions()) {
                if (!seen.add(f.id()))
                    throw new IllegalStateException("analytics function id '" + f.id() + "' is registered twice");
                all.add(f);
            }
        }
        all.sort(ORDER);
        return new AnalyticsCatalogue(List.copyOf(all));
    }

    public List<AnalyticsFunction> functions() {
        return functions;
    }

    public Optional<AnalyticsFunction> find(String id) {
        return functions.stream().filter(f -> f.id().equals(id)).findFirst();
    }
}
