package com.gamma.asn.core;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * File-backed source using the FFM API: no 2 GB limit, and closing the {@link Arena}
 * deterministically unmaps the file (no Windows file-lock lingering).
 */
final class MappedSource implements ByteSource {

    private final Arena arena;
    private final MemorySegment segment;

    MappedSource(Path file) throws IOException {
        this.arena = Arena.ofShared();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            this.segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    @Override
    public long size() {
        return segment.byteSize();
    }

    @Override
    public int byteAt(long offset) {
        return segment.get(ValueLayout.JAVA_BYTE, offset) & 0xFF;
    }

    @Override
    public void copyTo(long offset, byte[] dst, int dstOffset, int length) {
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, dst, dstOffset, length);
    }

    @Override
    public void close() {
        arena.close();
    }
}
