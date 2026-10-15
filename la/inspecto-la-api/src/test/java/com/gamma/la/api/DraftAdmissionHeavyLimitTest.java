package com.gamma.la.api;

import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimits;
import com.gamma.spi.auth.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code LA-CONFIG-LIMITS-1}: the heavy Draft job cap is the install-wide {@code drafts.heavy_jobs} when the server-wide
 * document states it, else {@code -Dinspecto.la.draft.heavy}, else {@code min(4, cores / 3)}; a change applies at the next
 * heavy job, and an unchanged configuration never overrides {@link DraftAdmission#setHeavyLimit}.
 */
class DraftAdmissionHeavyLimitTest {

    @AfterEach
    void restore() throws Exception {
        LaLimits.installDocPath(null);
        DraftAdmission.heavy("restore", () -> null);   // re-applies the configured default
        DraftAdmission.setHeavyLimit(DraftAdmission.defaultHeavyLimit());
    }

    private static void state(Path doc, Long heavy) throws Exception {
        Map<LaLimit, Number> set = new EnumMap<>(LaLimit.class);
        set.put(LaLimit.DRAFTS_HEAVY_JOBS, heavy);
        LaLimits.writeInstall(doc, set);
    }

    @Test
    void theInstallValueAppliesAtTheNextHeavyJobAndAnUnchangedOneKeepsAnOverride(@TempDir Path dir) throws Exception {
        Path doc = dir.resolve(LaLimits.INSTALL_FILE);
        LaLimits.installDocPath(doc);
        state(doc, 3L);
        assertEquals(3, DraftAdmission.heavy("a", DraftAdmission::heavyLimit), "the document's value, from the first heavy job");
        assertEquals(3, DraftAdmission.configuredHeavyLimit());

        DraftAdmission.setHeavyLimit(1);
        assertEquals(1, DraftAdmission.heavy("b", DraftAdmission::heavyLimit), "an unchanged configuration leaves an override alone");
        // the override is real: with its one permit taken, a second heavy job is refused at once
        ApiException busy = assertThrows(ApiException.class,
                () -> DraftAdmission.heavy("outer", () -> DraftAdmission.heavy("inner", () -> 0)));
        assertEquals(429, busy.status);

        state(doc, 5L);
        assertEquals(5, DraftAdmission.heavy("c", DraftAdmission::heavyLimit), "a changed value applies at the next heavy job");
        state(doc, null);
        assertEquals(DraftAdmission.defaultHeavyLimit(), DraftAdmission.heavy("d", DraftAdmission::heavyLimit), "cleared: the default");
    }
}
