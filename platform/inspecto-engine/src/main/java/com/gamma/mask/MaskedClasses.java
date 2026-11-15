package com.gamma.mask;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Predicate;

/**
 * MASKING-TWO-VOCABULARIES-1 (operator decision 2026-11-14): the column classifications a Space masks, for the engine's
 * readers that mask or refuse by classification ({@link EvidenceMasker}, {@code publish.postgres}). The rule is one predicate
 * over the Space's Entity Types (a class in {@link com.gamma.util.ColumnClassification#SENSITIVE}, claimed by a masked Entity
 * Type, or the id of one), and those live in the entity-store module, which the engine cannot see, so the control-plane module
 * provides this ({@link ServiceLoader}); with no provider on the classpath (this module's own tests)
 * {@link com.gamma.util.ColumnClassification#SENSITIVE} alone holds.
 */
public interface MaskedClasses {

    /** The classes the Space whose config root is {@code configRoot} masks, as a test of a raw classification (trimmed, any case). */
    Predicate<String> of(Path configRoot);

    /**
     * The classes an author's {@code selected} classes stand for in that Space (MASKING-TYPE-ID-EDGES-1): an Entity Type's id
     * stands for every class the type claims as well; any other class for itself. Normalised (trimmed, upper case).
     */
    Set<String> selected(Path configRoot, Collection<String> selected);

    static Optional<MaskedClasses> provider() {
        return ServiceLoader.load(MaskedClasses.class).findFirst();
    }
}
