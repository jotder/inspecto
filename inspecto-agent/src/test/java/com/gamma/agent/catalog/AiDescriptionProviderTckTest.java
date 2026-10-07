package com.gamma.agent.catalog;

import com.gamma.agent.model.FakeModelProvider;
import com.gamma.catalog.spi.DescriptionProvider;
import com.gamma.catalog.spi.testkit.DescriptionProviderContract;

/** {@link AiDescriptionProvider} (over a deterministic fake model) against the platform's DescriptionProvider TCK (MODULE-REORG-1 P5b). */
class AiDescriptionProviderTckTest extends DescriptionProviderContract {
    @Override
    protected DescriptionProvider provider() {
        return new AiDescriptionProvider(FakeModelProvider.canned("The transaction amount."));
    }
}
