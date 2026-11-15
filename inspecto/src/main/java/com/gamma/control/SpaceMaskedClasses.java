package com.gamma.control;

import com.gamma.entitystore.EntityTypes;
import com.gamma.mask.MaskedClasses;
import com.gamma.service.ClassificationVocabulary;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * {@link MaskedClasses} for the engine (MASKING-TWO-VOCABULARIES-1): {@link EntityTypes#maskedClasses} over the Entity Types of
 * the Space, the list the save gate judges a classification against ({@link ClassificationVocabulary#typesOf}). Read on every
 * call, so a changed {@code link-analysis.toon} applies at once.
 */
public final class SpaceMaskedClasses implements MaskedClasses {

    @Override
    public Predicate<String> of(Path configRoot) {
        return EntityTypes.maskedClasses(ClassificationVocabulary.typesOf(configRoot));
    }

    /** {@link EntityTypes#classesSelectedBy} of each selected class over the Space's Entity Types (MASKING-TYPE-ID-EDGES-1). */
    @Override
    public Set<String> selected(Path configRoot, Collection<String> selected) {
        List<EntityTypes.EntityType> types = ClassificationVocabulary.typesOf(configRoot);
        Set<String> out = new LinkedHashSet<>();
        for (String c : selected) out.addAll(EntityTypes.classesSelectedBy(types, c));
        return out;
    }
}
