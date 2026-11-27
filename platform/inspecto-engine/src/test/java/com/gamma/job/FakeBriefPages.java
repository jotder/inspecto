package com.gamma.job;

import java.util.List;

/** Test stand-in for the Business Insights module's {@link BriefPageProvider} (ReportBriefPageTest): "denied" is a Profile the author may not view. */
public final class FakeBriefPages implements BriefPageProvider {

    @Override
    public BriefPage page(String profileId, JobConfig job) {
        if ("denied".equals(profileId))
            throw new SecurityException("insight-profile 'denied' has no Insights the author of the Job may view - refused");
        if ("tall".equals(profileId)) {   // taller than a chart page, so the Brief can be told apart by its height
            List<Line> many = new java.util.ArrayList<>();
            for (int i = 0; i < 30; i++) many.add(new Line("elevated", "amount for region = R" + i + " was far above what it usually is"));
            return new BriefPage(profileId, "Brief for tall", null, null, List.of(), many, false);
        }
        return new BriefPage(profileId, "Brief for " + profileId + " - period 2026-11-17", null, null,
                profileId.startsWith("model") ? List.of("[1] Amount rose in North.") : List.of(),
                List.of(new Line("high", "amount for region = North was 400"), new Line("elevated", "amount for region = South was 90")),
                profileId.startsWith("model"));
    }
}
