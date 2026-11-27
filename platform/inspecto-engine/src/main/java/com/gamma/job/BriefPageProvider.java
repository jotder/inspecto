package com.gamma.job;

import java.util.List;

/**
 * The seam through which a Report Job asks the Business Insights module for a Dashboard Brief page (BI-INSIGHTS-1 P4 BR-4,
 * D-BI24): a {@code ServiceLoader} contract in the engine, implemented in {@code features/inspecto-insights}, the same
 * shape as Job types. The engine (a lower layer) never reads Insights itself.
 *
 * <p>An implementation builds the page AS THE JOB'S AUTHOR: it checks the author's right to view the Profile's Insights
 * and throws {@link SecurityException} (in words) when the author may not, so the Run fails with nothing delivered.
 */
public interface BriefPageProvider {

    /**
     * One Profile's Brief page, selected exactly as {@code GET /insights/brief} selects it, narrated when AI Assist is present.
     *
     * @throws IllegalArgumentException when the Profile does not exist
     * @throws SecurityException        when the Job's author may not view it
     */
    BriefPage page(String profileId, JobConfig job) throws Exception;

    /**
     * What a Brief page carries.
     *
     * @param header       "Brief for sales - scanned …, period …", or the Profile name when never scanned
     * @param note         "Last scan was 9 days ago" / "No scan has run…", or {@code null}
     * @param summary      "Nothing unusual in …" when no Insight qualifies, else {@code null}
     * @param paragraph    the narrated sentences (each with its {@code [n]}); empty when the page is templated
     * @param lines        the cited lines, best first: band word and the Insight's stored sentence
     * @param modelWritten {@code true} when a model wrote {@code paragraph} (the footer says so)
     */
    record BriefPage(String title, String header, String note, String summary, List<String> paragraph,
                     List<Line> lines, boolean modelWritten) {}

    record Line(String band, String text) {}
}
