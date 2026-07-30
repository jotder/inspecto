package com.gamma.asn.core;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Iterates framed records of a file as TLV trees, with an explicit recovery policy and
 * per-file counters. Bad records are never silently dropped: every failure reaches the
 * error listener with offset, record index, and the action taken.
 */
public final class RecordReader implements Iterator<Tlv> {

    @FunctionalInterface
    public interface ErrorListener {
        void onError(ParseError error);
    }

    private final ByteSource src;
    private final Framing framing;
    private final Strictness strictness;
    private final RecoveryPolicy policy;
    private final ErrorListener listener;

    private final long contentEnd;
    private long pos;
    private boolean stopped;
    private Tlv pending;

    private long recordsOk;
    private long recordsFailed;
    private long bytesSkipped;

    public RecordReader(ByteSource src, Framing framing, Strictness strictness,
                        RecoveryPolicy policy, ErrorListener listener) {
        this.src = src;
        this.framing = framing;
        this.strictness = strictness;
        this.policy = policy;
        this.listener = listener == null ? e -> { } : listener;
        this.pos = framing.fileHeaderLength(src);
        this.contentEnd = src.size() - framing.trailerLength(src);
    }

    public RecordReader(ByteSource src, Framing framing) {
        this(src, framing, Strictness.BER, RecoveryPolicy.STOP_FILE, null);
    }

    public long recordsOk() {
        return recordsOk;
    }

    public long recordsFailed() {
        return recordsFailed;
    }

    public long bytesSkipped() {
        return bytesSkipped;
    }

    @Override
    public boolean hasNext() {
        while (pending == null && !stopped) {
            advance();
        }
        return pending != null;
    }

    @Override
    public Tlv next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        Tlv out = pending;
        pending = null;
        return out;
    }

    private void advance() {
        while (pos < contentEnd && framing.isPadding(src.byteAt(pos))) {
            pos++;
            bytesSkipped++;
        }
        if (pos >= contentEnd) {
            stopped = true;
            return;
        }
        long recordStart = pos;
        long recordIndex = recordsOk + recordsFailed;
        long declared = framing.recordLength(src, recordStart);
        long payloadStart = recordStart + framing.recordHeaderLength(src, recordStart);
        try {
            if (declared == 0) {
                throw new BerParseException(recordStart, "record header declares zero length");
            }
            long limit = declared > 0 ? recordStart + declared : contentEnd;
            if (limit > contentEnd) {
                throw new BerParseException(recordStart,
                        "record header declares " + declared + " bytes, past end of content");
            }
            Tlv tlv = BerReader.read(src, payloadStart, limit, strictness);
            pos = declared > 0 ? recordStart + declared : tlv.endOffset();
            recordsOk++;
            pending = tlv;
        } catch (BerParseException e) {
            recordsFailed++;
            boolean canSkip = declared > 0 && policy == RecoveryPolicy.SKIP_RECORD
                    && recordStart + declared <= contentEnd;
            RecoveryPolicy action = canSkip ? RecoveryPolicy.SKIP_RECORD : RecoveryPolicy.STOP_FILE;
            listener.onError(new ParseError(e.offset(), recordIndex, e.getMessage(), action));
            if (canSkip) {
                bytesSkipped += declared;
                pos = recordStart + declared;
            } else {
                stopped = true;
            }
        }
    }
}
