package com.gamma.la.core;

import java.nio.file.Path;

/** {@link InvestigationStoreTwoJvmRace} over {@link FsInvestigationStore} on one shared directory (the shared-volume pod case). */
class FsInvestigationStoreTwoJvmRaceTest extends InvestigationStoreTwoJvmRace {

    public static final class Opener implements TwoJvmRaceWorker.Opener {
        @Override
        public InvestigationStore open(String spec) {
            return new FsInvestigationStore(Path.of(spec));
        }
    }

    @Override
    Class<? extends TwoJvmRaceWorker.Opener> openerClass() {
        return Opener.class;
    }

    @Override
    String spec() {
        return work.resolve("space").toString();
    }
}
