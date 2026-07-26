package com.gamma.control;

import com.gamma.notify.DeliveryEvent;
import com.gamma.notify.DeliveryStatusAdapter;

import java.util.List;
import java.util.Map;

/**
 * A test-only adapter that is discovered but never {@linkplain #configured() configured} — the case that
 * proves an unconfigured adapter answers <b>404, indistinguishably from one that does not exist</b>. A
 * different response for the two would let an unauthenticated caller enumerate which providers a
 * deployment has wired.
 */
public final class InertDeliveryStatusAdapter implements DeliveryStatusAdapter {

    @Override
    public String id() {
        return "test-inert";
    }

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public boolean verify(byte[] raw, Map<String, String> headers) {
        throw new AssertionError("an unconfigured adapter must never be reached");
    }

    @Override
    public List<DeliveryEvent> parse(byte[] raw) {
        throw new AssertionError("an unconfigured adapter must never be reached");
    }
}
