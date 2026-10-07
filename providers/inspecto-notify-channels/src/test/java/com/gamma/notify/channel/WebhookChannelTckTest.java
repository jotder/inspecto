package com.gamma.notify.channel;

import com.gamma.notify.NotificationChannel;
import com.gamma.notify.testkit.NotificationChannelContract;

/** {@link WebhookChannel} against the platform's NotificationChannel TCK (MODULE-REORG-1 P5b). */
class WebhookChannelTckTest extends NotificationChannelContract {
    @Override
    protected NotificationChannel channel() {
        return new WebhookChannel("https://hooks.example.test/x", "tck-webhook-token", 5);
    }

    @Override
    protected NotificationChannel unconfigured() {
        return new WebhookChannel(null, "tck-webhook-token", 5);
    }

    @Override
    protected String secret() {
        return "tck-webhook-token";
    }
}
