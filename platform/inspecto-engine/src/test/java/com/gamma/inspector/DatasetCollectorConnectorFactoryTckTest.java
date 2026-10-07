package com.gamma.inspector;

import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.acquire.testkit.CollectorConnectorFactoryContract;

/** {@link DatasetCollectorConnectorFactory} against the platform's CollectorConnectorFactory TCK (MODULE-REORG-1 P5b). */
class DatasetCollectorConnectorFactoryTckTest extends CollectorConnectorFactoryContract {
    @Override
    protected CollectorConnectorFactory factory() {
        return new DatasetCollectorConnectorFactory();
    }
}
