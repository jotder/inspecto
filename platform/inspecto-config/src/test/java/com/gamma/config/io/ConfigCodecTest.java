package com.gamma.config.io;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the canonical {@code .toon} codec (P1): the re-encoded form is comment-free and decodes back
 * to the same map through the one (strict) decode, {@code toMap}.
 *
 * <p>The companion contract test that walks every SHIPPED sample config lives in the core module
 * ({@code ShippedExamplesRoundTripTest}) with the {@code examples/} fixture tree it validates —
 * it could not move here in the S5 split (surefire's working directory is the module root).
 */
class ConfigCodecTest {

    @Test
    void colonAndSlashBearingValuesSurviveStrictRoundTrip() {
        // mirrors the events_meta.toon refs ("events/CALL") and grain strings ("a, b") that broke
        // tabular parsing before — they must encode quoted and strict-decode back intact
        Map<String, Object> src = Map.of(
                "name", "x",
                "kpis", Map.of("arpu", Map.of(
                        "inputs", List.of("events/CALL", "EVENTS_DAILY_KPI"),
                        "grain", "msisdn, month")));
        String toon = ConfigCodec.toToon(src);
        assertEquals(src, ConfigCodec.toMap(toon));
    }

    @Test
    void encodeNormalisesCommentBearingInputToStrictCleanForm() {
        // JToon has no comment syntax, but a '#' line between two scalars happens to decode; the
        // canonical re-encode drops it and decodes back to the same map — the guarantee that matters.
        String commented = "name: x\n# a comment line\nversion: 1\n";
        Map<String, Object> m = ConfigCodec.toMap(commented);
        assertEquals("x", m.get("name"));

        String canonical = ConfigCodec.toToon(m);
        assertFalse(canonical.contains("#"), "canonical form carries no comments");
        assertEquals(m, ConfigCodec.toMap(canonical));
    }

    // ── TOON-UNQUOTED-DECIMAL-SKIPS-PIPELINE-1: a tabular row whose width disagrees with its header ──

    private static final String UNQUOTED_DECIMAL = """
            raw:
              fields[2]{name,type}:
                ID,INTEGER
                AMT,DECIMAL(18,2)
            """;

    @Test
    void aRowWiderThanItsHeaderNamesTheLineTheTableTheCountsAndTheQuotedFix() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConfigCodec.toMap(UNQUOTED_DECIMAL));
        String m = e.getMessage();
        assertTrue(m.contains("line 4"), m);
        assertTrue(m.contains("'fields'"), m);
        assertTrue(m.contains("3 values") && m.contains("2 columns"), m);
        assertTrue(m.contains("\"DECIMAL(18,2)\""), "suggests the quoted value: " + m);
        assertNotNull(e.getCause(), "JToon's own refusal is kept as the cause");
    }

    @Test
    void theQuotedValueIsOneValue() {
        Map<String, Object> m = ConfigCodec.toMap(UNQUOTED_DECIMAL.replace("DECIMAL(18,2)", "\"DECIMAL(18,2)\""));
        assertEquals(List.of(Map.of("name", "ID", "type", "INTEGER"), Map.of("name", "AMT", "type", "DECIMAL(18,2)")),
                ((Map<?, ?>) m.get("raw")).get("fields"));
    }

    @Test
    void aRowNarrowerThanItsHeaderIsNamedToo() {
        String m = assertThrows(IllegalArgumentException.class, () -> ConfigCodec.toMap("""
                sinks[2]{database,format}:
                  db1,CSV
                  db2
                """)).getMessage();
        assertTrue(m.contains("line 3") && m.contains("'sinks'"), m);
        assertTrue(m.contains("1 value") && m.contains("2 columns"), m);
    }

    @Test
    void aDecodeFailureThatIsNotARowWidthPassesThroughUnchanged() {
        String bad = "sinks[3]{database,format}:\n  db1,CSV\n";
        String direct = assertThrows(IllegalArgumentException.class,
                () -> dev.toonformat.jtoon.JToon.decode(bad)).getMessage();
        assertEquals(direct, assertThrows(IllegalArgumentException.class, () -> ConfigCodec.toMap(bad)).getMessage());
    }
}
