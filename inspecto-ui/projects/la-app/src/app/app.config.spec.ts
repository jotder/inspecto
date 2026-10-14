import { MAT_DIALOG_DEFAULT_OPTIONS, MatDialogConfig } from '@angular/material/dialog';
import { describe, expect, it } from 'vitest';
import { appConfig } from './app.config';

describe('la-app app config', () => {
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
