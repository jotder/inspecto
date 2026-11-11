package com.gamma.module;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One customer's verified entitlement (PRODUCT-ASSEMBLY-1 PA-7a, D-PA22): what this install may run. Issued and signed by
 * {@code tools/entitle.mjs}; read and verified by {@link Entitlements}. It only narrows: {@link #modules} are the OPTIONAL
 * modules the customer may run (an Edition's base and internal modules always run), and a module the build lacks stays
 * absent whatever the file says.
 *
 * @param expires   null = no expiry (a trial always has one - PA-7b)
 * @param nodes     recorded and shown, not enforced (D-PA22)
 */
public record Entitlement(String id, String customer, String title, String product, String edition, List<String> apps,
                          List<String> addons, List<String> modules, int nodes, LocalDate notBefore, LocalDate expires,
                          LocalDate issued, String keyId, Path file) {

    public Entitlement {
        apps = List.copyOf(apps);
        addons = List.copyOf(addons);
        modules = List.copyOf(modules);
    }

    /** Days from {@code today} to the expiry (0 on the last day, negative once past); null when it never expires. */
    public Long daysLeft(LocalDate today) {
        return expires == null ? null : java.time.temporal.ChronoUnit.DAYS.between(today, expires);
    }

    /** The administrator's summary ({@code /bootstrap.entitlement}): who it is for, what it allows, and how long. */
    public Map<String, Object> summary(LocalDate today) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("installed", true);
        m.put("id", id);
        m.put("customer", customer);
        m.put("title", title);
        m.put("product", product);
        m.put("edition", edition);
        m.put("apps", apps);
        m.put("addons", addons);
        m.put("nodes", nodes);
        m.put("notBefore", notBefore.toString());
        m.put("expires", expires == null ? null : expires.toString());
        m.put("daysLeft", daysLeft(today));
        m.put("keyId", keyId);
        return m;
    }
}
