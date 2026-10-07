package com.gamma.acquire.connectors;

import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.acquire.testkit.CollectorConnectorFactoryContract;

/** {@link FtpConnectorFactory} against the platform's CollectorConnectorFactory TCK (MODULE-REORG-1 P5b). */
class FtpConnectorFactoryTckTest extends CollectorConnectorFactoryContract {
    @Override
    protected CollectorConnectorFactory factory() {
        return new FtpConnectorFactory();
    }
}
