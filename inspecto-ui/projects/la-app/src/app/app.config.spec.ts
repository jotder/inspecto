import { MAT_DIALOG_DEFAULT_OPTIONS, MatDialogConfig } from '@angular/material/dialog';
import { describe, expect, it } from 'vitest';
import { appEnvironment } from '@inspecto/core/api/app-environment';
import { appConfig } from './app.config';

describe('la-app app config', () => {
    it('names the product Inspecto LA for the shared sign-in screen (ED-1)', () => {
        expect(appConfig.providers.length).toBeGreaterThan(0);
        expect(appEnvironment().productName).toBe('Inspecto LA');
        expect(appEnvironment().signInPitch?.title).toBe('Follow every link, one case at a time.');
    });

    it('makes every dialog modal to assistive technology and keeps the dialog role', () => {
        const p = (appConfig.providers as { provide?: unknown; useFactory?: () => MatDialogConfig }[]).find(
            (x) => x.provide === MAT_DIALOG_DEFAULT_OPTIONS,
        );
        expect(p?.useFactory).toBeTruthy();
        const cfg = p!.useFactory!();
        expect(cfg.ariaModal).toBe(true);
        expect(cfg.role).toBe('dialog');
    });
});
