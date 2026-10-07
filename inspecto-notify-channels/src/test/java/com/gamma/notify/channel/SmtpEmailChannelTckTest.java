package com.gamma.notify.channel;

import com.gamma.notify.NotificationChannel;
import com.gamma.notify.testkit.NotificationChannelContract;

/** {@link SmtpEmailChannel} against the platform's NotificationChannel TCK (MODULE-REORG-1 P5b). */
class SmtpEmailChannelTckTest extends NotificationChannelContract {
    @Override
    protected NotificationChannel channel() {
        return new SmtpEmailChannel("mail.example.test", 25, "from@example.test", "to@example.test", "tck-user", "tck-smtp-pass", false);
    }

    @Override
    protected NotificationChannel unconfigured() {
        return new SmtpEmailChannel(null, 25, null, null, "tck-user", "tck-smtp-pass", false);
    }

    @Override
    protected String secret() {
        return "tck-smtp-pass";
    }
}
