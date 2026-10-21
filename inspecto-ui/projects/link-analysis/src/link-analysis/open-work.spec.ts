import { beforeEach, describe, expect, it } from 'vitest';
import { closedBySpaceSwitchMessage, rememberOpenWork, takeClosedBySpaceSwitch } from './open-work';

const ANALYST = 'fm.analyst';

describe('open-work (what a Space switch closed)', () => {
    beforeEach(() => sessionStorage.clear());

    it('names what was open in another Space once, then forgets it', () => {
        rememberOpenWork({ space: 'la-showcase', actor: ANALYST, label: 'Investigation Mule hub' });
        expect(takeClosedBySpaceSwitch('la-daily-feed', ANALYST)).toEqual({
            space: 'la-showcase',
            actor: ANALYST,
            label: 'Investigation Mule hub',
        });
        expect(takeClosedBySpaceSwitch('la-daily-feed', ANALYST)).toBeNull();
    });

    it('says nothing after a reload in the same Space, or when nothing was open', () => {
        rememberOpenWork({ space: 'la-showcase', actor: ANALYST, label: 'the analysis' });
        expect(takeClosedBySpaceSwitch('la-showcase', ANALYST)).toBeNull();
        rememberOpenWork(null);
        expect(takeClosedBySpaceSwitch('la-daily-feed', ANALYST)).toBeNull();
    });

    it('never tells the next user in the tab what another user had open, and forgets it', () => {
        rememberOpenWork({ space: 'la-showcase', actor: ANALYST, label: 'Investigation Mule hub' });
        expect(takeClosedBySpaceSwitch('la-daily-feed', 'fm.manager')).toBeNull();
        expect(takeClosedBySpaceSwitch('la-daily-feed', ANALYST)).toBeNull();
    });

    it('treats an unreadable record as nothing open', () => {
        sessionStorage.setItem('inspecto.la.openWork', '{not json');
        expect(takeClosedBySpaceSwitch('la-daily-feed', ANALYST)).toBeNull();
    });

    it('tells the analyst where the closed item stays', () => {
        expect(
            closedBySpaceSwitchMessage({ space: 'la-showcase', actor: ANALYST, label: 'Investigation Mule hub' }),
        ).toBe(
            'Investigation Mule hub was closed when you switched Space. It stays in Space la-showcase; switch back to reopen it.',
        );
    });
});
