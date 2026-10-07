package com.gamma.acquire.connectors;

import com.gamma.acquire.ExportConnectorFactory;
import com.gamma.acquire.testkit.ExportConnectorFactoryContract;

/** {@link S3ConnectorFactory}'s export side against the platform's ExportConnectorFactory TCK (MODULE-REORG-1 P5b). */
class S3ExportConnectorFactoryTckTest extends ExportConnectorFactoryContract {
    @Override
    protected ExportConnectorFactory factory() {
        return new S3ConnectorFactory();
    }
}
