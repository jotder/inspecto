package com.gamma.alert;

import java.util.List;

/** Test seam: a full Alert sweep at a chosen clock, for tests outside this package (e.g. past a rule's cooldown). */
public final class AlertSweeps {
    private AlertSweeps() {}

    public static List<Alert> at(AlertService svc, long nowMs) {
        return svc.evaluate(null, nowMs);
    }
}
