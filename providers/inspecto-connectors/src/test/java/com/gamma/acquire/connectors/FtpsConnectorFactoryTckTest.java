package com.gamma.acquire.connectors;

import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.acquire.testkit.CollectorConnectorFactoryContract;

/** {@link FtpsConnectorFactory} against the platform's CollectorConnectorFactory TCK (MODULE-REORG-1 P5b). */
class FtpsConnectorFactoryTckTest extends CollectorConnectorFactoryContract {
    @Override
    protected CollectorConnectorFactory factory() {
        return new FtpsConnectorFactory();
    }
}
