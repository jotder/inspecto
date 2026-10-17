package com.gamma.geolink;

import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.la.core.LinkEventTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which ledger events become which webhook, and the signature's exact shape (LA-EDITIONS-1 phase 6). */
class IntegrationWebhooksTest {

    @Test
    void theFourEventsMapAndAPipelineAlertDoesNot() {
        assertEquals("investigation.created", IntegrationWebhooks.webhookEvent(Event.builder(LinkEventTypes.LINK_INVESTIGATION_CREATED).build()));
        assertEquals("draft.promoted", IntegrationWebhooks.webhookEvent(Event.builder(LinkEventTypes.LINK_DRAFT_PROMOTED).build()));
        assertEquals("approval.needed", IntegrationWebhooks.webhookEvent(Event.builder(LinkEventTypes.LINK_EXPANSION_REQUESTED).build()));
        assertEquals("alert.fired", IntegrationWebhooks.webhookEvent(
                Event.builder(EventType.ALERT_FIRED).attr("rule", "r").attr("investigation", "inv-a").build()));
        assertNull(IntegrationWebhooks.webhookEvent(Event.builder(EventType.ALERT_FIRED).attr("rule", "r").build()),
                "an Alert that watches no Investigation is not a Link Analysis event");
        assertNull(IntegrationWebhooks.webhookEvent(Event.builder(LinkEventTypes.LINK_WEBHOOK_DELIVERED).build()),
                "its own audit rows never loop");
    }

    @Test
    void theSignatureIsHmacSha256OverTimestampDotBody() {
        // computed independently (Python hmac): HMAC-SHA256(key="key", msg="1.{}")
        assertEquals("1ba6b8171186efc613e8bcc0cbdab2748f24984d7c5a84faa2637afa0e40d224", IntegrationWebhooks.sign("key", "1", "{}"));
        String sig = IntegrationWebhooks.sign("key", "1", "{}");
        assertEquals(false, sig.equals(IntegrationWebhooks.sign("key", "2", "{}")), "the timestamp is signed (replay)");
    }
}
