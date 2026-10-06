package com.gamma.module;

import java.util.List;

/** The activator's verdict on one module: ACTIVE, or INERT with one reason per unmet requirement. */
public record ModuleStatus(String id, State state, List<String> reasons) {
    public enum State { ACTIVE, INERT }
}
