import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component, OnDestroy, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { RouteReuseStrategy, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';
import { SessionService } from './auth/session.service';
import { SpacesService } from './api/spaces.service';
import { KEEP_ALIVE, KeepAliveRouteReuseStrategy } from './keep-alive.strategy';

let destroyed = 0;

/** Stands in for the Link Analysis page: something open, and a count of destroyed instances. */
@Component({
    selector: 'test-kept-page',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<p>{{ open() }}</p>`,
})
class KeptPage implements OnDestroy {
    readonly open = signal('');
    ngOnDestroy(): void {
        destroyed++;
    }
}

@Component({
    selector: 'test-other-page',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<p>Settings</p>`,
})
class OtherPage {}

describe('KeepAliveRouteReuseStrategy wiring', () => {
    it('builds with the real Router, SessionService and SpacesService (the Router constructs it: no NG0200 cycle)', async () => {
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                provideRouter([{ path: 'link-analysis', component: KeptPage, data: { [KEEP_ALIVE]: true } }]),
                { provide: RouteReuseStrategy, useClass: KeepAliveRouteReuseStrategy },
            ],
        });
        const harness = await RouterTestingHarness.create();
        await expect(harness.navigateByUrl('/link-analysis', KeptPage)).resolves.toBeInstanceOf(KeptPage);
        expect(TestBed.inject(RouteReuseStrategy)).toBeInstanceOf(KeepAliveRouteReuseStrategy);
    });
});

describe('KeepAliveRouteReuseStrategy', () => {
    function setup() {
        destroyed = 0;
        const space = signal<string | null>('la-showcase');
        const actor = signal<string | null>('fm.analyst');
        TestBed.configureTestingModule({
            providers: [
                provideRouter([
                    { path: 'link-analysis', component: KeptPage, data: { [KEEP_ALIVE]: true } },
                    { path: 'settings', component: OtherPage },
                    { path: 'entities', component: OtherPage },
                ]),
                { provide: RouteReuseStrategy, useClass: KeepAliveRouteReuseStrategy },
                { provide: SpacesService, useValue: { currentSpaceId: space } },
                { provide: SessionService, useValue: { actor } },
            ],
        });
        return { space, actor };
    }

    it('keeps the flagged page, with what it had open, across a visit to another page and back', async () => {
        setup();
        const harness = await RouterTestingHarness.create();
        const page = await harness.navigateByUrl('/link-analysis', KeptPage);
        page.open.set('Investigation Mule hub');

        await harness.navigateByUrl('/settings', OtherPage);
        expect(destroyed).toBe(0); // detached, not destroyed
        const back = await harness.navigateByUrl('/link-analysis', KeptPage);

        expect(back).toBe(page);
        harness.detectChanges();
        expect(harness.routeNativeElement?.textContent).toContain('Investigation Mule hub');
        expect(destroyed).toBe(0);
    });

    it('leaves every other page to the default: a page without the flag is created anew', async () => {
        setup();
        const harness = await RouterTestingHarness.create();
        const first = await harness.navigateByUrl('/settings', OtherPage);
        await harness.navigateByUrl('/link-analysis', KeptPage);
        const again = await harness.navigateByUrl('/settings', OtherPage);
        expect(again).not.toBe(first);
    });

    it('never shows a page kept for another Space: it is destroyed and a fresh one opens', async () => {
        const { space } = setup();
        const harness = await RouterTestingHarness.create();
        const page = await harness.navigateByUrl('/link-analysis', KeptPage);
        page.open.set('Investigation Mule hub');
        await harness.navigateByUrl('/settings', OtherPage);

        space.set('la-daily-feed');
        const fresh = await harness.navigateByUrl('/link-analysis', KeptPage);

        expect(fresh).not.toBe(page);
        expect(fresh.open()).toBe('');
        expect(destroyed).toBe(1);
    });

    it('never shows a page kept for another signed-in user', async () => {
        const { actor } = setup();
        const harness = await RouterTestingHarness.create();
        const page = await harness.navigateByUrl('/link-analysis', KeptPage);
        await harness.navigateByUrl('/entities', OtherPage);

        actor.set('fm.manager');
        const fresh = await harness.navigateByUrl('/link-analysis', KeptPage);

        expect(fresh).not.toBe(page);
        expect(destroyed).toBe(1);
    });
});
