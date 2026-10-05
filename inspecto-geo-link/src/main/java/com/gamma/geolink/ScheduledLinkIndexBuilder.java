package com.gamma.geolink;

import com.gamma.la.api.ScheduledIndexBuild;
import com.gamma.linkindex.LinkIndexAccess;
import com.gamma.linkindex.LinkIndexBuilder;

import java.nio.file.Path;

/**
 * The bridge behind the {@code link-index} Platform Service (LA-DAILY-INGEST-1, T5): the engine names only
 * {@link LinkIndexBuilder}; this module, which knows both worlds, binds it to Link Analysis'
 * {@link ScheduledIndexBuild}. Contributed by {@code ServiceLoader}; absent this module the Job fails closed.
 */
public final class ScheduledLinkIndexBuilder implements LinkIndexBuilder {

    @Override
    public LinkIndexAccess.Outcome build(Path writeRoot, Path dataRoot, LinkIndexAccess.Request q) {
        ScheduledIndexBuild.Outcome o = ScheduledIndexBuild.run(writeRoot, dataRoot, new ScheduledIndexBuild.Request(
                q.job(), q.dataset(), q.sourceCol(), q.targetCol(), q.kindCol(), q.timeCol(), q.timeColZone(),
                q.weightCol(), q.attrCols(), q.owner(), q.allowFull(), q.timeoutMs()));
        return new LinkIndexAccess.Outcome(o.result(), o.mode(), o.code(), o.message(), o.edges(), o.nodes(), o.deltas());
    }
}
