package com.gamma.analytics.catalogue;

import java.util.List;

/**
 * The registration point of the Analytics function catalogue (ANALYTICS-CORE-1 C8). A kernel that adds a function adds
 * ONE provider class and one line to {@code META-INF/services/com.gamma.analytics.catalogue.AnalyticsFunctionProvider};
 * {@link AnalyticsCatalogue#load()} picks it up with no edit to the catalogue, the route or the view.
 */
public interface AnalyticsFunctionProvider {

    /** The functions this provider contributes; ids must be unique across every provider. */
    List<AnalyticsFunction> functions();
}
