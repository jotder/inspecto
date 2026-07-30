package com.gamma.skybase.decoder.asn2.reader;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

public final class SkipList implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Set<Integer> tagsToSkip;

    public SkipList(int[] tagsToSkip) {
        if (tagsToSkip == null) {
            this.tagsToSkip = Collections.emptySet();
        } else {
            this.tagsToSkip = Collections.unmodifiableSet(
                Arrays.stream(tagsToSkip).boxed().collect(Collectors.toSet())
            );
        }
    }

    public boolean contains(int tagId) {
        return tagsToSkip.contains(tagId);
    }
}
