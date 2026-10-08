package com.gamma.ops;

import com.gamma.service.StoreFamily;
import com.gamma.service.StoreFamilyProvider;

import java.util.List;

/** Contributes {@link OpsStoreFamily} to the operational-store roster; named in this module's {@code provides.storeFamilies}. */
public final class OpsStoreFamilyProvider implements StoreFamilyProvider {
    @Override
    public List<StoreFamily> families() {
        return List.of(OpsStoreFamily.values());
    }
}
