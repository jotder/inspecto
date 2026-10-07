package com.gamma.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ToonHelper#load} / {@link ToonHelper#decode} are the one TOON decode seam
 * ({@code CONFIGCODEC-LENIENT-IS-STRICT-1}): strict, and a row-width refusal names the file and line.
 */
class ToonHelperTest {

    @Test
    void aFileWithAnUnquotedDecimalRowIsRefusedNamingTheFileAndTheLine(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("orders_schema.toon"), """
                raw:
                  fields[2]{name,type}:
                    ID,INTEGER
                    AMT,DECIMAL(18,2)
                """);
        String m = assertThrows(IllegalArgumentException.class, () -> ToonHelper.load(f.toString())).getMessage();
        assertTrue(m.startsWith(f + ": line 4:"), m);
        assertTrue(m.contains("\"DECIMAL(18,2)\""), m);
    }

    @Test
    void decodeIsStrictSoAShortRowIsRefusedNotSilentlyPadded() {
        // Non-strict JToon would accept this and return {database=db2} with no format — a silent loss.
        String m = assertThrows(IllegalArgumentException.class, () -> ToonHelper.decode("""
                sinks[2]{database,format}:
                  db1,CSV
                  db2
                """)).getMessage();
        assertTrue(m.contains("line 3") && m.contains("'sinks'"), m);
    }
}
