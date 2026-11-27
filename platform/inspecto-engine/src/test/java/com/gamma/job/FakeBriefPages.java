package com.gamma.job;

import java.util.List;

/** Test stand-in for the Business Insights module's {@link BriefPageProvider} (ReportBriefPageTest): "denied" is a Profile the author may not view. */
public final class FakeBriefPages implements BriefPageProvider {

    @Override
    public BriefPage page(String profileId, JobConfig job) {
        if ("denied".equals(profileId))
            throw new SecurityException("insight-profile 'denied' has no Insights the author of the Job may view - refused");
        return new BriefPage(profileId, "Brief for " + profileId + " - period 2026-11-17", null, null,
                profileId.startsWith("model") ? List.of("[1] Amount rose in North.") : List.of(),
                List.of(new Line("high", "amount for region = North was 400"), new Line("elevated", "amount for region = South was 90")),
                profileId.startsWith("model"));
    }
}
