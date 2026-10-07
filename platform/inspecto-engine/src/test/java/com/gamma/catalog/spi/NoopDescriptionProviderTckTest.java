package com.gamma.catalog.spi;

import com.gamma.catalog.spi.testkit.DescriptionProviderContract;

/** {@link NoopDescriptionProvider} against the platform's DescriptionProvider TCK (MODULE-REORG-1 P5b). */
class NoopDescriptionProviderTckTest extends DescriptionProviderContract {
    @Override
    protected DescriptionProvider provider() {
        return new NoopDescriptionProvider();
    }
}
