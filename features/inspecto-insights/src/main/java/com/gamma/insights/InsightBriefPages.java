package com.gamma.insights;

import com.gamma.job.BriefPageProvider;
import com.gamma.job.JobConfig;
import com.gamma.job.PostgresPublishJobType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.SpaceConfigRoot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Brief page of a Report Job (BI-INSIGHTS-1 P4 BR-4, D-BI24): the SAME selection as {@code GET /insights/brief}
 * ({@link InsightBrief#fromStore}) and the SAME narration and cache as {@code POST /insights/brief/narrate}
 * ({@link BriefNarrator}), built AS THE JOB'S AUTHOR instead of a caller, so a screen, the route and a delivered PDF
 * agree by construction. With AI Assist present the paragraph is narrated (D-BI23, cached per scan run); without it the
 * page is templated and says so.
 */
public final class InsightBriefPages implements BriefPageProvider {

    @Override
    public BriefPage page(String profileId, JobConfig job) throws Exception {
        Path writeRoot = SpaceConfigRoot.requireCurrent("a Brief page");
        Path data = SpaceConfigRoot.currentDataRoot();
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        var authority = PostgresPublishJobType.authority();
        PostgresPublishJobType.Author author = authority == null ? PostgresPublishJobType.Author.OPEN : authority.authorOf(job);
        if (profileId == null || !profileId.matches("[A-Za-z0-9_][A-Za-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("'" + profileId + "' is not an insight-profile id");
        // the view check of GET /insights, for the author: a Profile the author may not view is refused, in words
        Map<String, Object> ds = store.get("dataset", InsightStore.store(profileId)).map(ComponentRegistry.Component::content).orElse(null);
        if (ds == null || !PostgresPublishJobType.canView(author, ds))
            throw new SecurityException("insight-profile '" + profileId + "' has no Insights the Job's author '"
                    + author.id() + "' may view - refused");

        Map<String, Object> one = InsightBrief.fromStore(writeRoot, data == null ? null : InsightStore.existing(data, profileId),
                profileId, InsightBrief.DEFAULT_MAX);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = one.get("lines") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();

        List<String> paragraph = new ArrayList<>();
        boolean model = false;
        if (!lines.isEmpty()) {
            var agent = BriefNarrator.agentFor(data);
            if (agent.isPresent()) {
                Map<String, Object> n = BriefNarrator.narrate(agent.get(), data, one, InsightBrief.DEFAULT_MAX);
                model = Boolean.TRUE.equals(n.get("modelBacked"));
                if (model && n.get("sentences") instanceof List<?> ss)
                    for (Object s : ss)
                        if (s instanceof Map<?, ?> m)
                            paragraph.add(String.valueOf(m.get("text")) + ("template".equals(m.get("source")) ? " (templated)" : ""));
            }
        }
        List<Line> out = new ArrayList<>();
        for (Map<String, Object> l : lines) out.add(new Line(String.valueOf(l.get("band")), String.valueOf(l.get("sentence"))));
        return new BriefPage(profileId, String.valueOf(one.getOrDefault("header", "Brief for " + profileId)),
                (String) one.get("note"), (String) one.get("summary"), paragraph, out, model);
    }
}
