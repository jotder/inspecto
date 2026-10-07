package com.gamma.backup;

import com.gamma.etl.EditionFeatureProvider;
import com.gamma.etl.EditionFeatures;

import java.util.Set;

/**
 * Declares the compliance archive ({@code sink.archive}: {@code collector.post_action.on_success: MOVE}) and
 * DuckLake registration ({@code sink.ducklake}) present wherever this module ships — Professional and
 * Enterprise (`PROCESSOR-RELEASE-READINESS-1` G9). The archive's other half is this module's {@code backup}
 * task; DuckLake has no module of its own and rides with this storage module, which every Professional
 * bundle carries. A Personal build refuses both at save and at run.
 */
public final class BackupEditionFeatures implements EditionFeatureProvider {

    @Override
    public Set<String> features() {
        return Set.of(EditionFeatures.SINK_ARCHIVE, EditionFeatures.SINK_DUCKLAKE);
    }
}
