package com.gamma.asn.plugins.vendor;

import com.gamma.asn.plugin.TransformFunction;
import com.gamma.asn.plugin.TransformFunctionProvider;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyVendorFunctionsTest {

    private final Map<String, TransformFunction> functions = new LegacyVendorFunctions()
            .functions(() -> Map.of("subscriberType", Map.of("307", "POSTPAID")));

    @Test
    void discoveredViaServiceLoader() {
        assertTrue(ServiceLoader.load(TransformFunctionProvider.class).stream()
                        .anyMatch(p -> p.type() == LegacyVendorFunctions.class),
                "META-INF/services registration missing");
    }

    @Test
    void namesLegacyNeverImplementedStayUnregistered() {
        for (String ghost : List.of("getStartEndTime", "convertedClientDate",
                "interOperatorIdentifiers", "subscriptionId", "firstKey")) {
            assertTrue(!functions.containsKey(ghost), ghost);
        }
    }

    @Test
    void ccnBillablePulseRoundsUpPartialPulse() throws Exception {
        assertEquals(2, functions.get("ccnBillablePulse")
                .apply(List.of(BigInteger.valueOf(61), "60")));
        assertEquals(1, functions.get("ccnBillablePulse")
                .apply(List.of(BigInteger.valueOf(60), "60")));
    }

    @Test
    void fetchValueUsesContextLookupsAndDefault() throws Exception {
        TransformFunction fetch = functions.get("fetchValue");
        assertEquals("POSTPAID", fetch.apply(List.of("subscriberType", "307")));
        assertEquals("POSTPAID", fetch.apply(List.of("subscriberType", BigInteger.valueOf(307))));
        assertEquals("dflt", fetch.apply(List.of("subscriberType", "999", "dflt")));
    }

    @Test
    void normalizationKeepsLegacyQuirks() throws Exception {
        TransformFunction norm = functions.get("normalization");
        // the length/93 check reads the ORIGINAL number, so a 10-digit 0-prefixed
        // number is only trimmed, and a ≤9-digit one gets "93" + ORIGINAL (untrimmed)
        assertEquals("700123456", norm.apply(List.of("0700123456")));
        assertEquals("93070012345", norm.apply(List.of("070012345")));
        // no leading 0/19 and length > 9 falls through to ""
        assertEquals("", norm.apply(List.of("4470012345678")));
    }
}
