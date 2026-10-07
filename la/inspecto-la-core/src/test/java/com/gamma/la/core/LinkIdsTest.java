package com.gamma.la.core;

import com.gamma.control.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D-U9 remainder: the link wire id is the evaluator's key {@code source␀target␀kind}, reversibly and URL-safely encoded. */
class LinkIdsTest {

    @Test
    void roundTripsExactlyAndIsUrlSafeAndStable() {
        for (List<String> parts : List.of(List.of("a", "b", "voice"), List.of("x|y", "p/q?r=1&s", "null"),
                List.of("ÄÖ ü", "masked:0123456789abcdef", ""), List.of("+44 7700 900123", "b", "sms"))) {
            String id = LinkIds.encode(parts.get(0), parts.get(1), parts.get(2));
            assertTrue(id.matches("lk\\.[A-Za-z0-9_-]+"), id);
            assertEquals(parts, LinkIds.decode(id));
            assertEquals(id, LinkIds.encode(parts.get(0), parts.get(1), parts.get(2)), "stable");
        }
        assertEquals("a\u0000b\u0000voice", LinkIds.key("a", "b", "voice"), "the evaluator's existing key");
        assertNotEquals(LinkIds.encode("a", "b", "voice"), LinkIds.encode("b", "a", "voice"), "directional");
        assertEquals(LinkIds.encode("a", "b", "null"), LinkIds.encode("a", "b", null), "the inherited null-kind key");
    }

    @Test
    void refusesAnythingItDidNotProduce() {
        String good = LinkIds.encode("a", "b", "voice");
        for (String bad : new String[] {null, "", "a\u0000b\u0000voice", good.substring(3), "lk.!!", "lk.",
                LinkIds.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("a\u0000b".getBytes()),
                LinkIds.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("\u0000b\u0000k".getBytes()),
                good + "=", "lk." + "A".repeat(LinkIds.MAX_LENGTH)}) {
            ApiException e = assertThrows(ApiException.class, () -> LinkIds.decode(bad), String.valueOf(bad));
            assertTrue(e.getMessage().contains("link id"), e.getMessage());
        }
    }
}
