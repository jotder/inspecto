package com.gamma.acquire.connectors;

import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.acquire.testkit.CollectorConnectorFactoryContract;

/** {@link GcsConnectorFactory} against the platform's CollectorConnectorFactory TCK (MODULE-REORG-1 P5b). */
class GcsConnectorFactoryTckTest extends CollectorConnectorFactoryContract {
    @Override
    protected CollectorConnectorFactory factory() {
        return new GcsConnectorFactory();
    }
}
