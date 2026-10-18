"""Notifications module diagram: one event fans out to in-app, SPI channels and ChannelConfig destinations."""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from svglib import Svg

s = Svg(940, 360, 'Notification dispatch', 'An event is rendered by a notification rule, deduped and rate-limited, '
        'then delivered in-app and through the email and webhook channels of inspecto-notify-channels.')
s.box(20, 40, 150, 60, 'gray', 'Event', 'EventBus (not LOG)')
s.box(200, 40, 170, 60, 'teal', 'NotificationRule', 'mask, render')
s.box(400, 40, 190, 60, 'teal', 'Dedupe + rate limit', '5 identical per hour')
s.arrow(170, 70, 200, 70)
s.arrow(370, 70, 400, 70)
s.group(620, 20, 300, 320, 'NotificationService.dispatch')
s.box(640, 45, 260, 60, 'blue', 'In-app feed', 'store + SSE /notifications/stream')
s.box(640, 125, 260, 60, 'teal', 'SPI channels (notify.* flags)', 'gated by preference per category')
s.box(640, 205, 260, 60, 'teal', 'ChannelConfig destinations', 'suppression, digest, receipt')
s.box(640, 280, 260, 50, 'gray', 'DeliveryReceipt', 'one per external attempt')
s.arrow(590, 70, 640, 75)
s.arrow(590, 80, 640, 155)
s.arrow(590, 90, 640, 235)
s.group(20, 150, 570, 190, 'providers/inspecto-notify-channels (not Basic)')
s.box(40, 180, 250, 60, 'purple', 'SmtpEmailChannel  id=email', 'javax.mail, STARTTLS required if on')
s.box(320, 180, 250, 60, 'coral', 'WebhookChannel  id=webhook', 'POST JSON via WebhookEgress')
s.box(320, 265, 250, 60, 'coral', 'HttpWebhookSinkTransport', 'Pipeline webhook sink, PinnedHttp')
s.arrow(640, 165, 570, 210, dash=True)
s.arrow(640, 245, 290, 225, dash=True)
s.save('docs/stakeholders/modules/assets/notifications.svg')
