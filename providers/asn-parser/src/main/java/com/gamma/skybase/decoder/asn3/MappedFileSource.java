package com.gamma.skybase.decoder.asn3;

import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

public class MappedFileSource implements ByteSource {

    private final MappedByteBuffer buffer;

    public MappedFileSource(Path path) throws Exception {

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
             FileChannel channel = raf.getChannel()) {
            buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
        }
    }

    public int read() {
        return buffer.get() & 0xFF;
    }

    public void skip(long n) {
        ((java.nio.Buffer) buffer).position((int) (buffer.position() + n));
    }

    public long position() {
        return buffer.position();
    }

    public long limit() {
        return buffer.limit();
    }

    public boolean hasRemaining() {
        return buffer.hasRemaining();
    }

    public byte[] readBytes(int len) {
        byte[] out = new byte[len];
        buffer.get(out);
        return out;
    }
}
