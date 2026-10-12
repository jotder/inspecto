package com.gamma.la.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorkingSetSizeLimitTest {

    @Test
    void humanSizeNeverRoundsASubMebibyteLimitDownToZeroMiB() {
        assertEquals("512 bytes", WorkingSetSizeLimit.humanSize(512));
        assertEquals("1 KiB", WorkingSetSizeLimit.humanSize(1024));
        assertEquals("600 KiB", WorkingSetSizeLimit.humanSize(600L * 1024));
        assertEquals("1 MiB", WorkingSetSizeLimit.humanSize(1L << 20));
        assertEquals("1.5 MiB", WorkingSetSizeLimit.humanSize((3L << 20) / 2));
        assertEquals("64 MiB", WorkingSetSizeLimit.humanSize(64L << 20));
        assertEquals("1 GiB", WorkingSetSizeLimit.humanSize(1L << 30));
    }
}
