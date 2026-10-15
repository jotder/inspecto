package com.gamma.la.api;

import java.util.Map;

/**
 * Where an Entity profile's link statistics come from (plan D-EP4: index first, Dataset fallback under the budget, else
 * {@code not assessed}). {@link EntityProfiles} asks each source in order and takes the first answer; a source that cannot
 * answer returns {@code null} and the next one is asked. Today the only source is {@link DatasetLinkStats} ({@code
 * "from":"dataset"}); the index source reads the manifest's {@code kindEdges} / {@code degree} fields once they ship
 * (plan §4.2) and goes FIRST in {@link EntityProfiles#LINK_SOURCES}.
 */
interface LinkStatsSource {

    /** The type's Links section ({@code from, kinds, degree, hubThreshold, hubs, singleLink, assessed, reason}), or null. */
    Map<String, Object> typeLinks(EntityProfiles.Frame f) throws Exception;

    /** One Entity's links ({@code degree, kinds, percentile, from, assessed, reason}) by its normalised key, or null. */
    Map<String, Object> entityLinks(EntityProfiles.Frame f, String key) throws Exception;
}
