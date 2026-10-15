package com.gamma.control;

import com.gamma.audit.Event;
import com.gamma.audit.EventLevel;
import com.gamma.audit.EventType;
import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** {@link AuditReadMasking#maskOpened}: an {@code OBJECT_OPENED} row's raw title is masked with the object's own keys. */
class AuditReadMaskingOpenedEventTest {

    private static final String RAW = "99970001401";

    private static Event opened(String message, String cid) {
        return Event.builder(EventType.OBJECT_OPENED).level(EventLevel.INFO).source("ObjectService").correlationId(cid)
                .message(message).attr("objectId", "o1").build();
    }

    @Test
    void theResolvedObjectsClassifiedKeyIsMaskedInTitleAndCorrelationAndAnUnclassifiedOneIsNot(@TempDir Path root) throws Exception {
        new ComponentStore(root.resolve("registry")).write("dataset", "ds", Map.of("columns", List.of(
                Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "region", "classification", "NONE"))));
        ObjectReadMasking m = ObjectReadMasking.of(root);
        Event raw = opened("INCIDENT opened: breached " + RAW + " [o1]", "c-" + RAW);
        Event masked = AuditReadMasking.maskOpened(raw, m, id -> Optional.of(Map.of("attributes",
                Map.of("dataset", "ds", "key.msisdn", RAW))));
        assertFalse(masked.message().contains(RAW), masked.message());
        assertTrue(masked.message().startsWith("INCIDENT opened: breached masked:") && masked.message().endsWith(" [o1]"), masked.message());
        assertFalse(masked.correlationId().contains(RAW));
        Event other = AuditReadMasking.maskOpened(raw, m, id -> Optional.of(Map.of("attributes",
                Map.of("dataset", "ds", "key.region", RAW))));
        assertEquals(raw.message(), other.message(), "no classified key on the object: nothing to mask");
    }

    @Test
    void anUnresolvedObjectOrAnotherEventTypeOrATitleFreeMessage(@TempDir Path root) throws Exception {
        ObjectReadMasking m = ObjectReadMasking.of(root);
        Event gone = AuditReadMasking.maskOpened(opened("INCIDENT opened: " + RAW + " [o1]", "c-" + RAW), m, id -> Optional.empty());
        assertEquals("INCIDENT opened [o1]", gone.message());
        assertNull(gone.correlationId());
        Event fresh = opened("INCIDENT opened [o1]", "c");
        assertSame(fresh, AuditReadMasking.maskOpened(fresh, m, id -> Optional.empty()));
        Event other = Event.builder(EventType.OBJECT_ACTIVITY).source("s").message("x " + RAW).build();
        assertSame(other, AuditReadMasking.maskOpened(other, m, id -> Optional.empty()));
    }
}
