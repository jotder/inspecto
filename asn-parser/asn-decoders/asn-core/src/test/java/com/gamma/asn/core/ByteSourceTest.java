package com.gamma.asn.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ByteSourceTest {

    @TempDir
    Path dir;

    @Test
    void mappedSourceMatchesHeap() throws IOException {
        byte[] data = new byte[4096];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31);
        }
        Path file = dir.resolve("sample.bin");
        Files.write(file, data);

        ByteSource heap = ByteSource.of(data);
        try (ByteSource mapped = ByteSource.map(file)) {
            assertEquals(heap.size(), mapped.size());
            assertEquals(heap.byteAt(0), mapped.byteAt(0));
            assertEquals(heap.byteAt(4095), mapped.byteAt(4095));
            assertArrayEquals(heap.bytes(100, 64), mapped.bytes(100, 64));
        }
        // Arena closed: the file must be deletable on Windows (no lingering lock)
        Files.delete(file);
    }

    @Test
    void parseFromMappedFile() throws IOException {
        Path file = dir.resolve("tlv.bin");
        Files.write(file, new byte[]{0x30, 0x06, 0x02, 0x01, 0x2A, 0x04, 0x01, (byte) 0xAB});
        try (ByteSource mapped = ByteSource.map(file)) {
            Tlv t = BerReader.read(mapped, 0, mapped.size(), Strictness.BER);
            assertEquals(2, t.children().size());
            assertEquals(0x2A, t.children().getFirst().value(mapped)[0]);
        }
    }
}
