package com.gamma.service;

import java.util.List;

/**
 * An optional module's contribution to the operational-store roster (MODULE-REORG-P1-FAMILY). Discovered through
 * {@link com.gamma.spi.OptionalSpi}, so a module that is not installed - or is present but unloadable - simply
 * contributes nothing and its families are not on the roster ({@code /system/operational-db} then reports them
 * {@code notInstalled}, from the module's {@code provides.storeFamilies}; the files they own are left untouched).
 *
 * <p>⛔ A provider must return the SAME families on every call, with names unique across the whole roster and
 * matching its module's {@code provides.storeFamilies}; {@link OperationalDb#all()} refuses a duplicate name and a
 * per-module test pins the manifest parity.
 */
public interface StoreFamilyProvider {

    /** The families this module owns. */
    List<StoreFamily> families();
}
