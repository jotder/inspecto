package com.gamma.alert;

import java.nio.file.Path;
import java.util.OptionalDouble;

/**
 * Evaluates an LA-23 <b>Investigation rule</b> ({@link AlertRule#isInvestigationRule()}): the rule's Measure over
 * one relation of a Link Analysis Investigation's Working Set.
 *
 * <p>A {@code ServiceLoader} SPI because the Investigation lives in the OPTIONAL {@code inspecto-geo-link} module
 * (Professional and above), which this engine does not depend on. Absent the module no implementation is found,
 * no probe is wired, and Investigation rules are inert — the same "unknown, never fire" a Dataset measure rule
 * degrades to without its probe.
 *
 * <p>⛔ An implementation answers EMPTY — never a value — unless the rule was bound by the Investigation's owner
 * through the module's authoring route, and is unchanged since. A background sweep carries no caller, so the
 * owner-only / PDP gate that route applies cannot be re-run here; the recorded binding is what carries it.
 */
public interface InvestigationMeasureProbe {

    /**
     * The rule's current value over the sealed Working Set of {@code rule.investigation()}, resolved under
     * {@code writeRoot} (the Space's config root — where the Investigation routes write); empty when it cannot be
     * computed or the rule has no matching owner binding. Never throws.
     */
    OptionalDouble value(Path writeRoot, AlertRule rule);
}
