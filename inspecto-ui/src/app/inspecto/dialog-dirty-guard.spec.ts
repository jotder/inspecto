import { Subject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { MatDialogRef } from '@angular/material/dialog';
import type { InspectoConfirmService } from './confirm.service';
import { guardDirtyClose } from './dialog-dirty-guard';

function fakeRef() {
    const keydown = new Subject<KeyboardEvent>();
    const backdrop = new Subject<MouseEvent>();
    const ref = {
        disableClose: false,
        close: vi.fn(),
        keydownEvents: () => keydown.asObservable(),
        backdropClick: () => backdrop.asObservable(),
    };
    return { ref: ref as unknown as MatDialogRef<unknown, unknown>, keydown, backdrop, close: ref.close };
}

function fakeConfirm(answer: boolean) {
    const confirmDestructive = vi.fn(async () => answer);
    return { svc: { confirmDestructive } as unknown as InspectoConfirmService, confirmDestructive };
}

describe('guardDirtyClose', () => {
    it('takes over closing (disableClose) and closes immediately while pristine', async () => {
        const { ref, keydown, close } = fakeRef();
        const { svc, confirmDestructive } = fakeConfirm(true);
        guardDirtyClose(ref, () => false, svc);
        expect(ref.disableClose).toBe(true);

        keydown.next(new KeyboardEvent('keydown', { key: 'Escape' }));
        await Promise.resolve();
        expect(close).toHaveBeenCalledTimes(1);
        expect(confirmDestructive).not.toHaveBeenCalled(); // no prompt when nothing to lose
    });

    it('asks before discarding a dirty form; keeps the dialog open when declined', async () => {
        const { ref, backdrop, close } = fakeRef();
        const { svc, confirmDestructive } = fakeConfirm(false);
        guardDirtyClose(ref, () => true, svc);

        backdrop.next(new MouseEvent('click'));
        await Promise.resolve();
        await Promise.resolve();
        expect(confirmDestructive).toHaveBeenCalledTimes(1);
        expect(close).not.toHaveBeenCalled();
    });

    it('closes a dirty form when the user confirms the discard', async () => {
        const { ref, close } = fakeRef();
        const { svc } = fakeConfirm(true);
        const requestClose = guardDirtyClose(ref, () => true, svc);

        await requestClose(); // the Cancel-button path
        expect(close).toHaveBeenCalledTimes(1);
    });

    /**
     * ⚠ A manage-rules dialog's close VALUE is a signal its caller reloads on ("did anything change").
     * Adopting the guard without carrying it would swap that for `undefined` and silently drop the
     * refresh — the edits would be saved on the server and invisible in the list behind the dialog.
     */
    it('closes with the caller-meaningful result when one is supplied', async () => {
        const { ref, close } = fakeRef();
        const { svc } = fakeConfirm(true);
        let changed = false;
        const requestClose = guardDirtyClose(ref, () => false, svc, () => changed);

        await requestClose();
        expect(close).toHaveBeenLastCalledWith(false);

        changed = true; // a rule was saved in the meantime — the result is read AT close time
        await requestClose();
        expect(close).toHaveBeenLastCalledWith(true);
    });

    it('closes bare when no result is supplied, exactly as before', async () => {
        const { ref, close } = fakeRef();
        const { svc } = fakeConfirm(true);
        await guardDirtyClose(ref, () => false, svc)();
        expect(close).toHaveBeenLastCalledWith(undefined);
    });

    it('ignores non-Escape keys', async () => {
        const { ref, keydown, close } = fakeRef();
        const { svc } = fakeConfirm(true);
        guardDirtyClose(ref, () => false, svc);

        keydown.next(new KeyboardEvent('keydown', { key: 'Enter' }));
        await Promise.resolve();
        expect(close).not.toHaveBeenCalled();
    });
});
