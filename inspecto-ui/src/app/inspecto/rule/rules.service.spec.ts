import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService } from 'app/inspecto/api';
import { emptyGroup } from 'app/inspecto/query';
import { RulesService } from './rules.service';

function setup() {
    const create = vi.fn((_t: string, c: Record<string, unknown>) =>
        of({ type: 'rule-template', name: String(c['id']), ref: `rule-template/${c['id']}`, content: c }),
    );
    const list = vi.fn(() =>
        of([
            {
                type: 'rule-template',
                name: 'r1',
                ref: 'rule-template/r1',
                content: { name: 'r1', source: 'alerts', projection: '*', where: emptyGroup(), sqlOverride: null },
            },
        ]),
    );
    TestBed.configureTestingModule({
        providers: [
            RulesService,
            { provide: ComponentsService, useValue: { create, list, remove: vi.fn(() => of(null)) } },
        ],
    });
    return { svc: TestBed.inject(RulesService), create, list };
}

describe('RulesService', () => {
    it('saves a rule template as a "rule-template" registry component', () => {
        const { svc, create } = setup();
        let saved: { id: string } | undefined;
        svc.save({
            id: 'r1',
            name: 'r1',
            source: 'alerts',
            projection: '*',
            where: emptyGroup(),
            sqlOverride: null,
        }).subscribe((r) => (saved = r));
        expect(create).toHaveBeenCalledWith('rule-template', expect.objectContaining({ id: 'r1', source: 'alerts' }));
        expect(saved?.id).toBe('r1');
    });

    it('lists rules back from the registry', () => {
        const { svc } = setup();
        let rules: { name: string; source: string }[] = [];
        svc.list().subscribe((r) => (rules = r));
        expect(rules[0].name).toBe('r1');
        expect(rules[0].source).toBe('alerts');
    });
});
