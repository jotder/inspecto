package com.gamma.control;

import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.safety.SafetyPolicyFiles;
import com.gamma.config.safety.SafetyPolicyUnreadableException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * `SPACE-LINK-CENSUS-1` LC-4 — a Space's {@code links:} mode from its {@code config/safety-policy.toon}
 * ({@code spec-space-lock.md} §10), as the link checks act on it.
 *
 * <ul>
 *   <li>{@code report} (the default; also the key absent): the JVM-wide {@code -Dlinks.delete} / {@code -Dlinks.save}
 *       modes apply unchanged.</li>
 *   <li>{@code refuse-activation}: {@link LinkRefusals} acts as {@code refuse} for THIS Space's saves and deletes
 *       wherever the JVM mode is {@code shadow}; {@code ?force=true} still overrides a delete. The JVM kill switch
 *       {@code off} still wins. Per D-LC5 only on Standard and Advanced: on Basic the key parses and acts as
 *       {@code report}, and the integrity report says so.</li>
 * </ul>
 *
 * An unreadable policy file fails closed (acts as {@code refuse-activation} where the edition allows it): the
 * same file already refuses every run of the Space until it is fixed.
 */
final class SpaceLinksMode {

    private SpaceLinksMode() {}

    /** What the Space asks for ({@code report}, {@code refuse-activation} or {@code unreadable}) and what applies. */
    record State(String configured, String effective, String note) {
        boolean refuses() {
            return "refuse-activation".equals(effective);
        }

        Map<String, Object> body() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("configured", configured);
            m.put("effective", effective);
            if (note != null) m.put("note", note);
            return m;
        }
    }

    static State of(String spaceId) {
        String configured;
        String note = null;
        try {
            configured = SafetyPolicy.linksForSpace(spaceId) == SafetyPolicyFiles.Links.REFUSE_ACTIVATION
                    ? "refuse-activation" : "report";
        } catch (SafetyPolicyUnreadableException unreadable) {
            configured = "unreadable";
            note = "the Space's safety-policy.toon is unreadable, so the link checks refuse as under refuse-activation: "
                    + unreadable.getMessage();
        }
        if ("report".equals(configured)) return new State(configured, "report", null);
        if ("basic".equals(BootstrapRoutes.edition()))
            return new State(configured, "report",
                    "refuse modes need Standard or Advanced (D-LC5); on Basic this Space only reports broken links");
        return new State(configured, "refuse-activation", note);
    }
}
