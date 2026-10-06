package com.gamma.entitylist;

import com.gamma.control.GovernableKindProvider;

import java.util.Set;

/**
 * Puts the Entity List under maker-checker (ASSURE-ENTITY-LISTS-1, D-P5): member / range changes and retire
 * hold; an add-only change whose every entry expires within 24 h applies at once and is reviewed after.
 */
public final class EntityListGovernableKinds implements GovernableKindProvider {
    @Override
    public Set<String> kinds() {
        return Set.of(EntityListRoutes.KIND);
    }
}
