package com.gamma.realtime;

import com.gamma.alert.RealtimeDetection;

/**
 * The Real-time Detection Add-on's commit hook (PRODUCT-ASSEMBLY-1 PA-9d), found by the host through
 * {@link java.util.ServiceLoader}: one {@link RealtimeSession} per Space.
 */
public final class RealtimeDetectionProvider implements RealtimeDetection {

    @Override
    public Session open(Context context) {
        return new RealtimeSession(context);
    }
}
