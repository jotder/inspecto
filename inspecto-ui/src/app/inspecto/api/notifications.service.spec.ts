import { beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NotificationRow, NotificationsService } from './notifications.service';

function row(id: string, read: boolean): NotificationRow {
    return {
        id,
        ts: 1,
        timestamp: '',
        category: 'pipeline',
        sourceType: 'BATCH_FAILED',
        sourceId: null,
        title: id,
        body: '',
        state: read ? 'READ' : 'UNREAD',
        readAt: read ? 1 : null,
        read,
    };
}

/** Read state is per user since 2026-09-25: the server answers read/unread with the CALLER's view of the row. */
describe('NotificationsService read state', () => {
    let svc: NotificationsService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(withXhr()), provideHttpClientTesting()] });
        svc = TestBed.inject(NotificationsService);
        http = TestBed.inject(HttpTestingController);
        svc.items.set([row('a', false), row('b', true)]);
        svc.unreadCount.set(1);
    });

    it('marks one read from the server row and drops the badge', () => {
        svc.markRead('a');
        http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/notifications/a/read')).flush(row('a', true));

        expect(svc.items().find((n) => n.id === 'a')?.read).toBe(true);
        expect(svc.unreadCount()).toBe(0);
    });

    it('marks one unread again and raises the badge', () => {
        svc.markUnread('b');
        http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/notifications/b/unread')).flush(row('b', false));

        expect(svc.items().find((n) => n.id === 'b')?.read).toBe(false);
        expect(svc.unreadCount()).toBe(2);
    });

    it('does not move the badge when the row was already in that state', () => {
        svc.markRead('b');
        http.expectOne((r) => r.url.endsWith('/notifications/b/read')).flush(row('b', true));

        expect(svc.unreadCount()).toBe(1);
    });

    it('counts an incoming notification by its read flag', () => {
        svc.applyIncoming(row('c', false));
        expect(svc.unreadCount()).toBe(2);
    });

    it('a polling tick is ONE request while the count holds; the feed is re-read only when it moved or is shown', () => {
        const all = () => http.match(() => true);
        svc.poll();
        let sent = all();
        expect(sent.map((r) => r.request.url)).toEqual([expect.stringMatching(/\/notifications\/unread-count$/)]);
        sent[0].flush({ count: 1 }); // unchanged
        expect(all()).toHaveLength(0);

        svc.poll();
        all()[0].flush({ count: 3 }); // moved: the feed follows, once
        sent = all();
        expect(sent).toHaveLength(1);
        expect(sent[0].request.url).toMatch(/\/notifications$/);
        sent[0].flush([row('c', false)]);
        expect(svc.unreadCount()).toBe(3);
        expect(svc.items().map((n) => n.id)).toEqual(['c']);

        svc.poll(true); // the panel is open: keep it current even when the count holds
        all()[0].flush({ count: 3 });
        expect(all()).toHaveLength(1);
    });
});
